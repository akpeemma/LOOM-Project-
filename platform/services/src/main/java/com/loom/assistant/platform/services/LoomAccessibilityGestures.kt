package com.loom.assistant.platform.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate

/**
 * Stage 2C (decision 4) — incremental device-control gesture implementations
 * as Kotlin extension functions on [LoomAccessibilityService].
 *
 * This file is NEW; [LoomAccessibilityService] itself is untouched (the class
 * file is owned by another track — extensions only).
 *
 * Every function here follows the structural pattern of
 * [LoomAccessibilityService.requestTap]:
 *  1. Sensitive-package refusal FIRST (banking/finance/authenticator apps are
 *     permanently guidance-only — §4 hard rule), before any token is touched.
 *  2. The atomic [AuthGate.consumeAndDispatch] boundary for the function's
 *     own registry key (single-use, 90s, key-bound): check → consume →
 *     re-check → the real platform API call runs as the handoff INSIDE the
 *     gate lock. No token → honest refusal, never a silent dispatch; a
 *     lifecycle flip or purgeAll() racing the handoff refuses instead of
 *     dispatching (§8.2).
 *  3. The real platform API call (intent / ACTION_SET_TEXT /
 *     dispatchGesture swipe / GLOBAL_ACTION_BACK) is the handoff — kept
 *     short and non-blocking while holding the gate.
 *
 * Return contract: true = the system accepted the action; false = refused
 * (reason in the sealed result). "Accepted" for gestures means the system
 * took the gesture — async completion is reported via LoomStateEvent, never
 * faked synchronously.
 *
 * The DeviceControlCenter catalog reports each of these Ready ONLY because
 * the implementation below exists and calls the real API (decision 4 —
 * no phantom claims).
 */
sealed class GestureResult {
    data object Accepted : GestureResult()
    data class Refused(val reason: String) : GestureResult()
}

private fun LoomAccessibilityService.foregroundPackage(): String? = try {
    rootInActiveWindow?.packageName?.toString()
} catch (_: Exception) {
    null
}

/**
 * Backstop shared by every extension: refuse inside sensitive packages
 * BEFORE the token is consumed, so a token can never be spent on a refused
 * action. Audited against `automation.sensitive_app` by name.
 */
private fun LoomAccessibilityService.checkSensitiveGate(): GestureResult? {
    val pkg = foregroundPackage()
    return if (pkg != null && ScreenDataPolicy.isSensitivePackage(pkg)) {
        GestureResult.Refused(
            "Automation is disabled inside banking and finance apps — guidance only.",
        )
    } else {
        null
    }
}

/**
 * Stage 2C (§8.2): shared gate accessor for the atomic consume+dispatch
 * boundary. Returns null (with the refusal) when the gate is unavailable;
 * the caller then routes its platform API call through
 * [AuthGate.consumeAndDispatch] as the handoff — check → consume →
 * re-check → dispatch execute as ONE critical section on the gate monitor,
 * so no lifecycle flip or purgeAll() can interleave between authorization
 * and the dispatch call.
 */
private fun gateOrRefuse(): AuthGate? = AuthGate.getOrNull()

private fun gateUnavailable(): GestureResult =
    GestureResult.Refused("AuthGate is not initialized.")

private fun dispatchRefused(reason: String): GestureResult =
    GestureResult.Refused(reason)

/**
 * Launch an installed app by package name via a package-manager intent.
 * Real implementation: PackageManager.getLaunchIntentForPackage.
 */
fun LoomAccessibilityService.launchApp(
    packageName: String,
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    // Resolve the intent BEFORE the gate: read-only, and a token is never
    // spent on a refused action.
    val intent = try {
        packageManager.getLaunchIntentForPackage(packageName)
    } catch (_: Exception) {
        null
    } ?: return GestureResult.Refused("No launch intent for package $packageName.")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val gate = gateOrRefuse() ?: return gateUnavailable()
    // §8.2: the startActivity call is the handoff inside the gate lock.
    return when (
        val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_APP_LAUNCH)) {
            try {
                startActivity(intent)
                GestureResult.Accepted
            } catch (e: Exception) {
                GestureResult.Refused(e.message ?: "launch threw")
            }
        }
    ) {
        is AuthGate.DispatchResult.Dispatched -> r.value
        is AuthGate.DispatchResult.Refused -> dispatchRefused("Launch refused: ${r.reason}")
    }
}

/**
 * Type text into the currently focused input field.
 * Real implementation: AccessibilityNodeInfo.ACTION_SET_TEXT on the
 * FOCUS_INPUT node.
 */
fun LoomAccessibilityService.typeText(
    text: String,
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    // Find the field BEFORE the gate: read-only, and a token is never spent
    // on a refused action.
    val node = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (_: Exception) {
        null
    } ?: return GestureResult.Refused("No focused text field on screen.")
    // Mark 57 transfer (2026-11-11, M2) — password typing refusal
    // at the type sink, unconditional, with no parameter to turn
    // it off: LOOM never types into a platform-flagged password /
    // PIN field. The field may hold a password David typed himself;
    // LOOM's text must never land in it. Refused BEFORE the gate,
    // so no token is spent on a refused action.
    if (node.isPassword) {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
        return GestureResult.Refused(
            "Refusing to type into a password field — LOOM never types passwords.",
        )
    }
    val gate = gateOrRefuse() ?: run {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
        return gateUnavailable()
    }
    // §8.2: the ACTION_SET_TEXT call is the handoff inside the gate lock.
    return try {
        when (
            val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_DEVICE_TYPE)) {
                try {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                    node.refresh()
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        GestureResult.Accepted
                    } else {
                        GestureResult.Refused("The focused field rejected the text.")
                    }
                } catch (e: Exception) {
                    GestureResult.Refused(e.message ?: "type threw")
                }
            }
        ) {
            is AuthGate.DispatchResult.Dispatched -> r.value
            is AuthGate.DispatchResult.Refused -> dispatchRefused("Type refused: ${r.reason}")
        }
    } finally {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }
}

/**
 * Submit the focused text field — the search-sequence primitive (master
 * implementation §4). Real implementation: IME Enter
 * (`AccessibilityAction.ACTION_IME_ENTER`, API 30+) on the FOCUS_INPUT
 * node. On older Android there is no equivalent accessibility API, so
 * the call refuses honestly and the search sequence falls back to
 * tapping an on-screen search button. Gated `consumeAndDispatch
 * ([device.submit])`, sensitive pre-check, same pattern as [typeText].
 */
fun LoomAccessibilityService.submitSearch(
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    // Find the field BEFORE the gate: read-only, and a token is never spent
    // on a refused action.
    val node = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (_: Exception) {
        null
    } ?: return GestureResult.Refused("No focused text field on screen.")
    val gate = gateOrRefuse() ?: run {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
        return gateUnavailable()
    }
    // §8.2: the ACTION_IME_ENTER call is the handoff inside the gate lock.
    return try {
        when (
            val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_DEVICE_SUBMIT)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        node.refresh()
                        val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        if (node.performAction(imeEnter)) {
                            GestureResult.Accepted
                        } else {
                            GestureResult.Refused("The focused field does not accept IME Enter.")
                        }
                    } catch (e: Exception) {
                        GestureResult.Refused(e.message ?: "submit threw")
                    }
                } else {
                    GestureResult.Refused(
                        "Submitting from the keyboard needs Android 11 or newer on this phone.",
                    )
                }
            }
        ) {
            is AuthGate.DispatchResult.Dispatched -> r.value
            is AuthGate.DispatchResult.Refused -> dispatchRefused("Submit refused: ${r.reason}")
        }
    } finally {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }
}

/**
 * App-aware execution (2026-11-07, David's directive §1) — submit
 * mechanism 2: tap the keyboard's own Search / Go / Done / Send /
 * Next key.
 *
 * WHY this exists: [submitSearch] (mechanism 1) sends ACTION_IME_ENTER
 * to the focused field, and David's screenshot proves the failure mode
 * — the text is typed, the field is focused, but the search never
 * executes: the field refuses IME Enter (or the IME swallows it) and
 * LOOM had no other way to press the key the user would press. The
 * keyboard's action key is NOT in the app's node tree; it lives in the
 * input-method window, so this reads [windows] for the
 * TYPE_INPUT_METHOD window and taps the action key inside it.
 *
 * Same contract as [submitSearch]: sensitive gate first, read-only
 * key lookup before any token is spent, atomic consumeAndDispatch
 * under the SAME `device.submit` key (submitting is one action class
 * however the key is pressed — no new action key), honest Refused
 * when the key cannot be found. "Accepted" means the system took the
 * tap — whether the search executed is verified afterwards against a
 * fresh screen snapshot, never assumed here.
 *
 * Heuristic, stated plainly: keyboards do not expose the action key's
 * IME action through accessibility, so the key is identified by its
 * label (exact match on Search / Go / Done / Send / Next / Enter) and,
 * when several nodes match, the lowest one on screen inside the IME
 * window (the action key sits at the bottom-right of the keyboard;
 * Gboard's toolbar Search icon sits higher). A wrong tap is
 * recoverable by design — the executor verifies the screen after the
 * tap and treats an unexpected screen as a failure to recover from,
 * never as success. Never taps blind coordinates: no matching node,
 * no tap.
 */
fun LoomAccessibilityService.submitSearchViaKeyboardKey(
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    // Find the key BEFORE the gate: read-only, and a token is never
    // spent on a refused action.
    val keyBounds = try {
        findKeyboardActionKeyBounds()
    } catch (_: Exception) {
        null
    } ?: return GestureResult.Refused(
        "No Search, Go or Done key found on the keyboard.",
    )
    val gate = gateOrRefuse() ?: return gateUnavailable()
    return when (
        val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_DEVICE_SUBMIT)) {
            try {
                val path = Path().apply {
                    moveTo(keyBounds.exactCenterX(), keyBounds.exactCenterY())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                    .build()
                if (dispatchGesture(gesture, null, null)) GestureResult.Accepted
                else GestureResult.Refused("System refused the keyboard-key tap dispatch call.")
            } catch (e: Exception) {
                GestureResult.Refused(e.message ?: "keyboard key tap threw")
            }
        }
    ) {
        is AuthGate.DispatchResult.Dispatched -> r.value
        is AuthGate.DispatchResult.Refused -> dispatchRefused("Submit refused: ${r.reason}")
    }
}

/**
 * Bounds of the keyboard's action key, or null. Delegates to the
 * service's member walk ([LoomAccessibilityService.findKeyboardActionKey]
 * — a member, not an extension here, so the T10 runners that compile
 * the service without this file still resolve it). The returned Rect
 * is a plain value, never a live node.
 */
private fun LoomAccessibilityService.findKeyboardActionKeyBounds(): Rect? =
    findKeyboardActionKey()?.bounds

/**
 * Find the scrollable node in the active window, if any.
 *
 * Device-test failures (2026-11-11, D-09): the old walk returned the
 * FIRST scrollable node in traversal order — on YouTube (and any feed
 * app) the tree holds many nested scrollables, and the first found is
 * often a small horizontal shelf (chip bar, video shelf, Shorts row).
 * Its node action scrolls sideways or does nothing vertically, and
 * when it returns true the step reported DISPATCHED while the feed
 * never moved. The main feed is the LARGEST scrollable on the screen
 * (a shelf is a thin strip of it), so the walk now returns the
 * largest-area scrollable node. A full-screen horizontal pager can
 * still win on area — that is a named device-test case, not a
 * silent guess.
 */
private fun LoomAccessibilityService.findScrollableNode(): AccessibilityNodeInfo? {
    val root = try {
        rootInActiveWindow
    } catch (_: Exception) {
        null
    } ?: return null
    var best: AccessibilityNodeInfo? = null
    var bestArea = 0L
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        try {
            if (node.isScrollable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area > bestArea) {
                    bestArea = area
                    best?.let {
                        try {
                            it.recycle()
                        } catch (_: Exception) {
                        }
                    }
                    best = node
                    // The winning node is kept (not recycled below) —
                    // its children are still walked for a larger one.
                    // Children of the current best are added after
                    // this block, so the loop must not skip them.
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { stack.add(it) }
                    }
                    continue
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        } catch (_: Exception) {
            // keep walking
        }
    }
    return best
}

/**
 * Scroll the current screen.
 * Real implementation: ACTION_SCROLL_FORWARD/BACKWARD on the scrollable
 * node; falls back to a dispatchGesture swipe inside the scrollable (or
 * root) bounds when the node action is unavailable.
 */
fun LoomAccessibilityService.scrollScreen(
    forward: Boolean,
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    // Resolve the node and bounds BEFORE the gate: read-only, and a token is
    // never spent on a refused action.
    val node = findScrollableNode()
    val rootBounds = Rect()
    try {
        rootInActiveWindow?.getBoundsInScreen(rootBounds)
    } catch (_: Exception) {
    }
    val bounds = Rect()
    try {
        (node ?: rootInActiveWindow)?.getBoundsInScreen(bounds)
    } catch (_: Exception) {
    }
    // D-09: a thin scrollable (a shelf, a chip bar) makes the fallback
    // swipe a stroke over a ~50px strip — nothing moves. When the
    // chosen node is shorter than a quarter of the screen, swipe
    // across the full root bounds instead.
    if (!rootBounds.isEmpty && bounds.height() < rootBounds.height() / 4) {
        bounds.set(rootBounds)
    }
    if (bounds.isEmpty) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
        return GestureResult.Refused("No scrollable content found on screen.")
    }
    val gate = gateOrRefuse() ?: run {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
        return gateUnavailable()
    }
    // §8.2: the scroll action / fallback swipe is the handoff inside the
    // gate lock. Async completion is reported via LoomStateEvent by the
    // caller path; here "accepted" means the system took the dispatch.
    return try {
        when (
            val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_DEVICE_SCROLL)) {
                scrollHandoff(node, bounds, forward)
            }
        ) {
            is AuthGate.DispatchResult.Dispatched -> r.value
            is AuthGate.DispatchResult.Refused -> dispatchRefused("Scroll refused: ${r.reason}")
        }
    } finally {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }
}

/**
 * The scroll handoff: ACTION_SCROLL_FORWARD/BACKWARD on the scrollable node,
 * falling back to a dispatchGesture swipe inside the scrollable (or root)
 * bounds when the node action is unavailable. Forward scroll = swipe up.
 */
private fun LoomAccessibilityService.scrollHandoff(
    node: AccessibilityNodeInfo?,
    bounds: Rect,
    forward: Boolean,
): GestureResult {
    if (node != null) {
        try {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.performAction(action)) {
                return GestureResult.Accepted
            }
        } catch (_: Exception) {
            // fall through to the gesture swipe
        }
    }
    val x = bounds.exactCenterX()
    val startY: Float
    val endY: Float
    if (forward) {
        startY = bounds.bottom - bounds.height() * 0.25f
        endY = bounds.top + bounds.height() * 0.25f
    } else {
        startY = bounds.top + bounds.height() * 0.25f
        endY = bounds.bottom - bounds.height() * 0.25f
    }
    return try {
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (dispatched) GestureResult.Accepted else GestureResult.Refused(
            "System refused the scroll gesture dispatch call.",
        )
    } catch (e: Exception) {
        GestureResult.Refused(e.message ?: "scroll threw")
    }
}

/**
 * Universal Console (2026-11-07) — swipe the screen in [direction]
 * ("up" | "down" | "left" | "right"). The gesture form of scrolling:
 * unlike [scrollScreen] there is no scrollable node to act on (a
 * horizontal swipe has no node action at all), so the swipe is a
 * dispatchGesture stroke across the live root bounds — the bounds
 * come from the live tree, never invented coordinates, and a swipe
 * with no readable screen refuses instead of gesturing blind. Same
 * `device.scroll` key as scrolling (a swipe IS scrolling by
 * gesture), same sensitive pre-check, same atomic
 * consume+dispatch boundary. "Accepted" means the system took the
 * gesture — the executor verifies the screen changed afterwards.
 * Forward = swipe up (content moves up); back = swipe down; left /
 * right swipe horizontally. Unknown direction refuses honestly.
 */
fun LoomAccessibilityService.swipeScreen(
    direction: String,
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    val dir = direction.lowercase()
    if (dir !in setOf("up", "down", "left", "right")) {
        return GestureResult.Refused("Unknown swipe direction: $direction.")
    }
    val bounds = Rect()
    try {
        rootInActiveWindow?.getBoundsInScreen(bounds)
    } catch (_: Exception) {
    }
    if (bounds.isEmpty) {
        return GestureResult.Refused("No readable screen to swipe on.")
    }
    val gate = gateOrRefuse() ?: return gateUnavailable()
    return try {
        when (
            val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_DEVICE_SCROLL)) {
                swipeHandoff(bounds, dir)
            }
        ) {
            is AuthGate.DispatchResult.Dispatched -> r.value
            is AuthGate.DispatchResult.Refused -> dispatchRefused("Swipe refused: ${r.reason}")
        }
    } catch (e: Exception) {
        GestureResult.Refused(e.message ?: "swipe threw")
    }
}

/** The swipe handoff: one stroke across the screen, per direction. */
private fun LoomAccessibilityService.swipeHandoff(bounds: Rect, direction: String): GestureResult {
    val cx = bounds.exactCenterX()
    val cy = bounds.exactCenterY()
    val w = bounds.width().toFloat()
    val h = bounds.height().toFloat()
    val (startX, startY, endX, endY) = when (direction) {
        "up" -> Quad(cx, bounds.bottom - h * 0.25f, cx, bounds.top + h * 0.25f)
        "down" -> Quad(cx, bounds.top + h * 0.25f, cx, bounds.bottom - h * 0.25f)
        "left" -> Quad(bounds.right - w * 0.25f, cy, bounds.left + w * 0.25f, cy)
        else -> Quad(bounds.left + w * 0.25f, cy, bounds.right - w * 0.25f, cy)
    }
    return try {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (dispatched) GestureResult.Accepted else GestureResult.Refused(
            "System refused the swipe gesture dispatch call.",
        )
    } catch (e: Exception) {
        GestureResult.Refused(e.message ?: "swipe threw")
    }
}

private data class Quad(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * Press the system back action.
 * Real implementation: performGlobalAction(GLOBAL_ACTION_BACK).
 */
fun LoomAccessibilityService.goBack(authToken: String?): GestureResult {
    // NOTE: back is OS-level navigation (leaving the app), not automation
    // inside it — it is intentionally NOT subject to the sensitive-app gate.
    val gate = gateOrRefuse() ?: return gateUnavailable()
    // §8.2: the performGlobalAction call is the handoff inside the gate lock.
    return when (
        val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_DEVICE_BACK)) {
            try {
                if (performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                    GestureResult.Accepted
                } else {
                    GestureResult.Refused("System refused the back action.")
                }
            } catch (e: Exception) {
                GestureResult.Refused(e.message ?: "back threw")
            }
        }
    ) {
        is AuthGate.DispatchResult.Dispatched -> r.value
        is AuthGate.DispatchResult.Refused -> dispatchRefused("Back refused: ${r.reason}")
    }
}

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) — the SEND
 * tap. Taps the send button at [element]'s centre, consuming a
 * `message.send` token inside the atomic consume+dispatch boundary.
 *
 * WHY A NEW SINK: IME Enter must NOT be reused for sending — WhatsApp's
 * Enter key inserts a newline by default, so `submitSearch` would type
 * a newline, not send. And a `tap.guidance` token cannot substitute:
 * tokens are key-bound, so the gate refuses a `message.send` token at
 * a sink probing only `tap.guidance` (and vice versa). The send button
 * is icon-only (role ICON, contentDescription "Send"); after typing it
 * replaces the mic button, so the executor re-finds it fresh at this
 * step — never reuse the mic button's identity. Same sensitive
 * pre-check + atomic boundary as every other sink in this file.
 */
fun LoomAccessibilityService.sendTap(
    element: ScreenElement,
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    if (element.bounds.isEmpty) {
        return GestureResult.Refused("The send button has no position on screen.")
    }
    val gate = gateOrRefuse() ?: return gateUnavailable()
    return when (
        val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_MESSAGE_SEND)) {
            try {
                val path = Path().apply {
                    moveTo(element.bounds.exactCenterX(), element.bounds.exactCenterY())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                    .build()
                if (dispatchGesture(gesture, null, null)) GestureResult.Accepted
                else GestureResult.Refused("System refused the send-tap dispatch call.")
            } catch (e: Exception) {
                GestureResult.Refused(e.message ?: "send tap threw")
            }
        }
    ) {
        is AuthGate.DispatchResult.Dispatched -> r.value
        is AuthGate.DispatchResult.Refused -> dispatchRefused("Send refused: ${r.reason}")
    }
}

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) — the CALL
 * tap. Taps the call button (in the Phone app's contact row / contact
 * screen) at [element]'s centre, consuming a `call.place` token.
 *
 * WHY `call.place`, NOT `tap.guidance`: the tap PLACES the call — it
 * is the consequential action, so it carries the per-call CONFIRM
 * card (factory `callPlace(contact, number)` shows exactly who is
 * being called). A `tap.guidance` token would let a generic guidance
 * tap place a call with no call-specific confirmation. Same sensitive
 * pre-check + atomic boundary as [sendTap].
 */
fun LoomAccessibilityService.callTap(
    element: ScreenElement,
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    if (element.bounds.isEmpty) {
        return GestureResult.Refused("The call button has no position on screen.")
    }
    val gate = gateOrRefuse() ?: return gateUnavailable()
    return when (
        val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_CALL_PLACE)) {
            try {
                val path = Path().apply {
                    moveTo(element.bounds.exactCenterX(), element.bounds.exactCenterY())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                    .build()
                if (dispatchGesture(gesture, null, null)) GestureResult.Accepted
                else GestureResult.Refused("System refused the call-tap dispatch call.")
            } catch (e: Exception) {
                GestureResult.Refused(e.message ?: "call tap threw")
            }
        }
    ) {
        is AuthGate.DispatchResult.Dispatched -> r.value
        is AuthGate.DispatchResult.Refused -> dispatchRefused("Call refused: ${r.reason}")
    }
}

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) — the
 * ACTION_DIAL fallback for dictated numbers. Opens the dialer with
 * [telUri] (a `tel:` URI, e.g. `tel:08031234567`) pre-filled; David
 * presses call himself — the OS UI is the final confirmation.
 *
 * Zero new permissions (no CALL_PHONE; ACTION_CALL is deliberately
 * NOT built — it would place the call with no further user press and
 * needs the manifest permission David's build dropped). Gated with a
 * `call.place` token anyway: the intent carries the number, so it is
 * consequential even though the user presses call. The URI is
 * validated here (must be `tel:` + digits/`+`/`*`/`#` only) so a
 * malformed or non-tel URI can never be dispatched.
 */
fun LoomAccessibilityService.dialNumber(
    telUri: String,
    authToken: String?,
): GestureResult {
    checkSensitiveGate()?.let { return it }
    val digits = telUri.removePrefix("tel:")
    val valid = telUri.startsWith("tel:") && digits.isNotBlank() &&
        digits.all { it.isDigit() || it == '+' || it == '*' || it == '#' }
    if (!valid) {
        return GestureResult.Refused("Not a valid phone number to dial.")
    }
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse(telUri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val gate = gateOrRefuse() ?: return gateUnavailable()
    return when (
        val r = gate.consumeAndDispatch(authToken, listOf(AuthActionRegistry.ACTION_CALL_PLACE)) {
            try {
                startActivity(intent)
                GestureResult.Accepted
            } catch (e: Exception) {
                GestureResult.Refused(e.message ?: "dial threw")
            }
        }
    ) {
        is AuthGate.DispatchResult.Dispatched -> r.value
        is AuthGate.DispatchResult.Refused -> dispatchRefused("Dial refused: ${r.reason}")
    }
}
