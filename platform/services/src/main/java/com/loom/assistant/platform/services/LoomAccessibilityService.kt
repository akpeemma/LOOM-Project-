package com.loom.assistant.platform.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.execution.interaction.HaltSource
import com.loom.assistant.execution.interaction.InteractionLayerRegistry
import com.loom.assistant.execution.screenawareness.A11yNodeProvider
import com.loom.assistant.execution.screenawareness.MeasuredControl
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenshotCaptureGate
import com.loom.assistant.execution.screenawareness.ScreenshotCaptureState
import com.loom.assistant.execution.screenawareness.VisualObservation
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Labels a keyboard action key carries (Gboard, Samsung Keyboard, SwiftKey). Exact match only. */
private val KEYBOARD_ACTION_LABELS = setOf("search", "go", "done", "send", "next", "enter")

/**
 * Phase 4 — LOOM accessibility service (arch Platform layer, §2.19, §2.5).
 *
 * Backs screen awareness (tree snapshots for the pointer guidance module).
 * Gesture dispatch is implemented and AUTH-GATED (arch §2.14): [requestTap]
 * dispatches ONLY with a valid AuthToken issued by [AuthGate] — no token,
 * no dispatch. The enforcement is structural: the token is single-use,
 * 90-second, and bound to the tap action key.
 *
 * Phase 6: the AuthGate is real. [authGateReady] is flipped by the app
 * once AuthGate.init() has run.
 *
 * Disabled by default — David enables it in Settings → Device Control.
 * Nothing here runs without his explicit enablement.
 */
class LoomAccessibilityService : AccessibilityService(), A11yNodeProvider {

    sealed class TapResult {
        data object Dispatched : TapResult()
        data class GateRequired(val reason: String) : TapResult()
        data class Failed(val reason: String) : TapResult()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenAwarenessHolder.bind(this)
        // Device-evidence fix (2026-11-05, pointer H1): a fresh service
        // instance means every renderer cached against the old host is
        // stale — set the invalidation flag the overlay service reads on
        // its next ensureGuidanceRenderer() call.
        invalidateGuidanceRenderer()
        Log.i("LoomPointer", "a11y service connected: guidance renderer cache invalidated")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        ScreenAwarenessHolder.unbind()
        lastUnbindAtMs = System.currentTimeMillis()
        lastUnbindWhy = "service unbound by the system"
        // Same staleness signal on the way out: the host is gone, so any
        // cached renderer is dead from here on.
        invalidateGuidanceRenderer()
        Log.i("LoomPointer", "a11y service unbound: guidance renderer cache invalidated")
        haltSequencesOnToggleRevocation()
        // Binding survival (2026-11-06): the disconnect watch.
        // AccessibilityService has NO onServiceDisconnected callback —
        // onUnbind IS the disconnect signal. When the switch is still
        // on, this unbind was a kill, not the user switching off, and
        // Android should rebind the service into the live process on
        // its own. Watch for that rebind (2 s) and log the verdict,
        // so logcat shows whether the system reconnected or the
        // toggle is genuinely needed. The watch only observes — the
        // app cannot force the rebind; Home's wait (tap) and
        // re-checks (open) are the app-side attempts.
        scheduleRebindWatch()
        return super.onUnbind(intent)
    }

    /**
     * Binding survival (2026-11-06): after an unbind with the switch
     * still on, check once, 2 s later, whether Android rebound the
     * service. Never throws, never posts to the user — logcat only.
     */
    private fun scheduleRebindWatch() {
        val flagStillOn = try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            enabled.split(':').any {
                it.equals(
                    android.content.ComponentName(
                        this,
                        LoomAccessibilityService::class.java.name,
                    ).flattenToString(),
                    ignoreCase = true,
                )
            }
        } catch (_: Exception) {
            false
        }
        if (!flagStillOn) {
            Log.i("LoomPointer", "a11y service unbound: switch is off, no rebind expected")
            return
        }
        try {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    Log.i(
                        "LoomPointer",
                        "a11y rebind watch: flag still on, live now=${isLive()} " +
                            "(false here means the system did not reconnect — " +
                            "the toggle in Settings is the fix)",
                    )
                },
                2_000L,
            )
        } catch (_: Exception) {
            // A diagnostic watch must never throw out of a lifecycle callback.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tree snapshots are pulled on demand by ScreenAwareness; events only
        // keep the service warm. No logging of screen content.
    }

    /**
     * Binding-survival fix (2026-11-06): a destroyed service must never
     * leave a stale live instance behind. Without this, a service that
     * died without onUnbind would keep [isLive] reading true against a
     * dead host, and Home would attempt renders it cannot win. Process
     * death clears statics anyway; this covers the in-process destroy
     * path (and HyperOS kills that route through onDestroy).
     */
    override fun onDestroy() {
        instance = null
        ScreenAwarenessHolder.unbind()
        lastUnbindAtMs = System.currentTimeMillis()
        lastUnbindWhy = "service destroyed"
        invalidateGuidanceRenderer()
        Log.i("LoomPointer", "a11y service destroyed: live instance cleared")
        super.onDestroy()
    }

    /**
     * Binding-survival fix (2026-11-06): onInterrupt is NOT an unbind.
     * The system calls this when feedback is interrupted — the service
     * stays connected and the system can resume it. The old code called
     * ScreenAwarenessHolder.unbind() here, which permanently cleared
     * the "bound" signal ([ScreenAwarenessHolder.isBound] /
     * [isLive]) until a reconnect that may never come, while the
     * Settings switch still read on. That is a live candidate for the
     * Nov 6 device evidence (Bound Nov 5 22:05 → Not bound Nov 6 08:01
     * with the switch still Enabled): any overnight interruption
     * silently dropped the binding from LOOM's side even though the
     * service was still connected. Interrupt now only halts a live
     * sequence; the binding stays until onUnbind/onDestroy says
     * otherwise.
     */
    override fun onInterrupt() {
        Log.i("LoomPointer", "a11y service interrupted: binding kept, sequences halted")
        haltSequencesOnToggleRevocation()
    }

    /**
     * Stage 2C (§5.2 P4): toggle revocation. Disabling the accessibility
     * service mid-sequence halts any live sequence honestly (INTERRUPTED,
     * source TOGGLE_REVOKED — never "completed") and reports the degraded
     * state: guidance stopped, automation unavailable until re-enabled.
     * Append-only: requestTap and the 2C guidance-overlay host region above
     * are untouched.
     */
    private fun haltSequencesOnToggleRevocation() {
        try {
            val layer = InteractionLayerRegistry.get()
            if (layer != null && layer.isSequenceActive) {
                layer.halt(HaltSource.TOGGLE_REVOKED)
            }
            LoomEventBus.post(
                LoomStateEvent.Error(
                    "LOOM accessibility was turned off — guidance stopped and " +
                        "automation is unavailable until it is re-enabled.",
                ),
            )
        } catch (_: Exception) {
            // A halt path must never throw out of a service lifecycle callback.
        }
    }

    /** Safe accessor for the screen-awareness module (null when no window). */
    override fun safeRootInActiveWindow(): AccessibilityNodeInfo? =
        try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }

    /**
     * App-aware execution (2026-11-07, David's directive §1): whether an
     * input-method window (the on-screen keyboard) is present right now.
     * The service's window list is the only honest source — the active
     * window's node tree does not report the keyboard. False on any
     * error; never throws out of a service callback path.
     */
    override fun isInputMethodVisible(): Boolean = try {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (_: Exception) {
        false
    }

    /**
     * POINTER EVERYWHERE (2026-11-09): the system Back button as
     * measured in the navigation-bar window, or null when there is
     * no Back node (gesture navigation — the bar carries no Back
     * button) or the windows cannot be read. Read-only: walks the
     * system windows' trees for the node labelled "Back" (exact
     * match, same discipline as the keyboard-key labels) and
     * returns its measured bounds, label and class — never invented
     * coordinates. Null on any error; never throws out of a service
     * callback path. The executor decides the navigation mode from
     * this at runtime: a button here means 3-button nav, null
     * means the back-swipe edge.
     *
     * NOTE: AccessibilityWindowInfo has NO TYPE_NAVIGATION_BAR
     * constant (verified against android-35: only APPLICATION,
     * INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY,
     * MAGNIFICATION_OVERLAY, SPLIT_SCREEN_DIVIDER exist) — the
     * navigation bar's accessibility window is TYPE_SYSTEM. The
     * status bar is TYPE_SYSTEM too but carries no Back node, so
     * the label match is what identifies the bar's button.
     */
    override fun navBackButton(): MeasuredControl? = try {
        val systemWindows = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
        } catch (_: Exception) {
            null
        } ?: return null
        for (barWindow in systemWindows) {
            val root = try {
                barWindow.root
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                findBackButton(root)?.let { return it }
            } finally {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }

    /**
     * POINTER EVERYWHERE (2026-11-09): the keyboard's action key as
     * measured in the input-method window, or null when the keyboard
     * is hidden or the key is not exposed as a node. Delegates to
     * the same walk the submit path already uses
     * ([findKeyboardActionKey]), so the pointer parks on exactly
     * the key the submit would tap. Read-only; null on any error.
     */
    override fun keyboardActionKey(): MeasuredControl? = try {
        findKeyboardActionKey()
    } catch (_: Exception) {
        null
    }

    /**
     * POINTER EVERYWHERE (2026-11-09): the system Back button inside
     * a window root, or null. Exact label match on "Back" (text or
     * content-description), visible + enabled, with real bounds.
     * Same walk-and-recycle discipline as the keyboard key lookup.
     * A member (not a Gestures.kt extension) so the T10 runners —
     * which compile this file without the gesture file — resolve it.
     */
    private fun findBackButton(root: AccessibilityNodeInfo): MeasuredControl? {
        fun visit(node: AccessibilityNodeInfo): MeasuredControl? {
            val rawLabel = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
            if (rawLabel?.lowercase() == "back" && node.isVisibleToUser && node.isEnabled) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (!b.isEmpty) {
                    return MeasuredControl(
                        Rect(b.left, b.top, b.right, b.bottom),
                        rawLabel,
                        node.className?.toString() ?: "unknown",
                    )
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child)?.let { return it }
                } finally {
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
            return null
        }
        return try {
            visit(root)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * POINTER EVERYWHERE (2026-11-09): the keyboard's action key as
     * a measured control (bounds + label + class name), or null.
     * Walks the input-method window's tree; among matching nodes
     * prefers the lowest on screen (the action key is the
     * bottom-right key). Labels are the exact-match set (Search /
     * Go / Done / Send / Next / Enter) — keyboards do not expose
     * the action key's IME action through accessibility. The label
     * and class name are the matched node's own, so the pointer
     * pill names the real key. Every node the walk touches is
     * recycled inside the walk — the returned control holds plain
     * values, never a live node. A member (not a Gestures.kt
     * extension) for the same T10 reason as [findBackButton]; the
     * submit path's [findKeyboardActionKeyBounds] delegates to it —
     * so it is `internal`, not `private`: an extension in another
     * file cannot call a private member (the real APK build caught
     * this; the harness never compiles the two files together).
     */
    internal fun findKeyboardActionKey(): MeasuredControl? {
        val imeWindow = try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            null
        } ?: return null
        val root = try {
            imeWindow.root
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            var best: MeasuredControl? = null
            var bestCenterY = Int.MIN_VALUE
            val imeBounds = Rect().also { imeWindow.getBoundsInScreen(it) }
            fun visit(node: AccessibilityNodeInfo) {
                val rawLabel = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
                val label = rawLabel?.lowercase()
                if (label in KEYBOARD_ACTION_LABELS && node.isVisibleToUser && node.isEnabled) {
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    // Action key lives in the lower half of the keyboard.
                    if (!b.isEmpty && b.centerY() >= imeBounds.centerY() && b.centerY() > bestCenterY) {
                        best = MeasuredControl(
                            Rect(b.left, b.top, b.right, b.bottom),
                            rawLabel ?: label ?: "unknown",
                            node.className?.toString() ?: "unknown",
                        )
                        bestCenterY = b.centerY()
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try {
                        visit(child)
                    } finally {
                        try {
                            child.recycle()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            visit(root)
            best
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Request a tap on [element]. Phase 6: the AuthGate is real.
     * [authToken] must be a token ID issued by AuthGate for the tap action —
     * single-use, 90s expiry, key-bound. Without one, this refuses honestly.
     *
     * "Dispatched" means the system ACCEPTED the gesture for dispatch; the
     * completion callback is async, so landing is reported via the event bus
     * (LoomStateEvent.Completed) rather than faked synchronously.
     */
    fun requestTap(element: ScreenElement, authToken: String?): TapResult {
        if (!authGateReady) {
            return TapResult.GateRequired("AuthGate is not ready.")
        }
        val gate = AuthGate.getOrNull()
            ?: return TapResult.GateRequired("AuthGate is not initialized.")
        // NAV-SCREEN fix (2026-11-11, N6): live re-find BEFORE the
        // token is consumed. The element's bounds were copied into a
        // snapshot before the token wait; on a live screen (a feed,
        // a playing video) they may be stale by dispatch time. The
        // node is re-found live by identity (class + text +
        // content-description, the search path's tapFresh pattern)
        // with the 24px / 2× guards — moved / gone / ambiguous
        // refuses honestly here, before any token is spent and
        // before any gesture is built. An icon-only element (no
        // text/description) re-finds by class + centre proximity.
        val liveBounds = when (val found = reFindLive(element)) {
            is LiveTapFind.Found -> found.bounds
            is LiveTapFind.Moved -> return TapResult.Failed(found.reason)
            is LiveTapFind.Gone -> return TapResult.Failed(found.reason)
            is LiveTapFind.Ambiguous -> return TapResult.Failed(found.reason)
            is LiveTapFind.Unreadable -> return TapResult.Failed(found.reason)
        }
        // Stage 2C (§8.2): the atomic consume+dispatch boundary. Check at
        // consumption → probe the two tap-family keys in order (fail-closed:
        // a wrong-key probe never destroys the token — mismatch #6) →
        // re-check at the dispatch handoff → run the handoff INSIDE the gate
        // lock. A token consumed while ACTIVE but handed to dispatch after
        // the lifecycle left ACTIVE is refused; purgeAll() additionally
        // destroys issued tokens, so post-purge dispatches are refused.
        return try {
            when (
                val r = gate.consumeAndDispatch(
                    authToken,
                    listOf(
                        AuthActionRegistry.ACTION_TAP_GUIDANCE,
                        AuthActionRegistry.ACTION_TAP,
                    ),
                ) {
                    // Handoff (inside the gate lock): build the gesture and
                    // make the dispatch call. Kept short and non-blocking —
                    // completion stays async via the callback below.
                    // NAV-SCREEN fix (2026-11-11, N6): the gesture taps
                    // the LIVE bounds re-found just above, never the
                    // frozen snapshot bounds.
                    val path = Path().apply {
                        moveTo(liveBounds.exactCenterX(), liveBounds.exactCenterY())
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                        .build()
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                LoomEventBus.post(LoomStateEvent.Completed)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                LoomEventBus.post(LoomStateEvent.Error("Tap gesture cancelled by the system."))
                            }
                        },
                        null,
                    )
                }
            ) {
                is AuthGate.DispatchResult.Dispatched ->
                    if (r.value) TapResult.Dispatched
                    else TapResult.Failed("System refused the gesture dispatch call.")
                is AuthGate.DispatchResult.Refused ->
                    TapResult.GateRequired("Tap dispatch refused: ${r.reason}")
            }
        } catch (e: Exception) {
            TapResult.Failed(e.message ?: "dispatch threw")
        }
    }

    /** Outcome of the live re-find in [requestTap]. */
    private sealed class LiveTapFind {
        data class Found(val bounds: Rect) : LiveTapFind()
        data class Moved(val reason: String) : LiveTapFind()
        data class Gone(val reason: String) : LiveTapFind()
        data class Ambiguous(val reason: String) : LiveTapFind()
        data class Unreadable(val reason: String) : LiveTapFind()
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N6) — re-finds [element] in the
     * LIVE tree immediately before a tap. Identity is (class, text,
     * content-description); centre moved > 24px or size changed > 2×
     * is Moved; no match is Gone; several matches is Ambiguous. An
     * icon-only element (no text/description) falls back to one
     * same-class node whose centre is within 24px. A null root is
     * Unreadable — the screen went away, so nothing is tapped.
     * Bounded walk (depth 24, same as the snapshot walk).
     */
    private fun reFindLive(element: ScreenElement): LiveTapFind {
        val root = try {
            safeRootInActiveWindow()
        } catch (_: Exception) {
            null
        } ?: return LiveTapFind.Unreadable(
            "screen went unreadable before the tap — nothing was tapped",
        )
        return try {
            val matches = ArrayList<Rect>()
            collectLiveMatches(root, element, matches, 0)
            if (matches.size > 1) {
                // Icon-only siblings share class/text/description —
                // identity alone cannot tell them apart. Narrow by
                // centre proximity (same rule as
                // LiveTapResolutionChecker): exactly one within 24px
                // is the target; several is ambiguous. None within
                // tolerance means it moved — except for an icon-only
                // target, whose description may have changed while it
                // stayed put: the centre-proximity fallback over all
                // same-class nodes still identifies it by where it is.
                val near = matches.filter {
                    abs(it.exactCenterX() - element.bounds.exactCenterX()) <= 24 &&
                        abs(it.exactCenterY() - element.bounds.exactCenterY()) <= 24
                }
                if (near.size == 1) return finishLiveMatch(near[0], element)
                if (near.size > 1) {
                    return LiveTapFind.Ambiguous(
                        "the target \"${element.label()}\" now matches ${near.size} elements — be more specific",
                    )
                }
                val labelled = !element.text.isNullOrBlank() ||
                    !element.contentDescription.isNullOrBlank()
                if (!labelled) {
                    val prox = ArrayList<Rect>()
                    collectLiveNear(root, element, prox, 0)
                    if (prox.size == 1) return finishLiveMatch(prox[0], element)
                    if (prox.size > 1) {
                        return LiveTapFind.Ambiguous(
                            "the target \"${element.label()}\" now matches ${prox.size} elements — be more specific",
                        )
                    }
                }
                return LiveTapFind.Moved("target moved: \"${element.label()}\" is no longer where it was")
            }
            if (matches.size == 1) {
                return finishLiveMatch(matches[0], element)
            }
            // No identity match: icon-only proximity fallback.
            val labelled = !element.text.isNullOrBlank() || !element.contentDescription.isNullOrBlank()
            if (!labelled) {
                val near = ArrayList<Rect>()
                collectLiveNear(root, element, near, 0)
                if (near.size == 1) return LiveTapFind.Found(near[0])
                if (near.size > 1) {
                    return LiveTapFind.Ambiguous(
                        "the target \"${element.label()}\" now matches ${near.size} elements — be more specific",
                    )
                }
            }
            LiveTapFind.Gone("target gone: \"${element.label()}\" no longer matches anything on screen")
        } finally {
            root.recycle()
        }
    }

    /** Applies the 24px / 2× guards to one live match: Found or Moved. */
    private fun finishLiveMatch(bounds: Rect, element: ScreenElement): LiveTapFind {
        val moved = abs(bounds.exactCenterX() - element.bounds.exactCenterX()) > 24 ||
            abs(bounds.exactCenterY() - element.bounds.exactCenterY()) > 24 ||
            bounds.width().toDouble() / element.bounds.width().toDouble().coerceAtLeast(1.0) !in 0.5..2.0 ||
            bounds.height().toDouble() / element.bounds.height().toDouble().coerceAtLeast(1.0) !in 0.5..2.0
        return if (moved) {
            LiveTapFind.Moved("target moved: \"${element.label()}\" is no longer where it was")
        } else {
            LiveTapFind.Found(bounds)
        }
    }

    private fun collectLiveMatches(
        node: AccessibilityNodeInfo,
        element: ScreenElement,
        out: MutableList<Rect>,
        depth: Int,
    ) {
        if (depth > 24) return
        if (node.className?.toString() == element.className &&
            node.text?.toString() == element.text &&
            node.contentDescription?.toString() == element.contentDescription &&
            node.isVisibleToUser
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) out.add(bounds)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLiveMatches(child, element, out, depth + 1)
            child.recycle()
        }
    }

    private fun collectLiveNear(
        node: AccessibilityNodeInfo,
        element: ScreenElement,
        out: MutableList<Rect>,
        depth: Int,
    ) {
        if (depth > 24) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && node.isVisibleToUser &&
            node.className?.toString() == element.className &&
            abs(bounds.exactCenterX() - element.bounds.exactCenterX()) <= 24 &&
            abs(bounds.exactCenterY() - element.bounds.exactCenterY()) <= 24
        ) {
            out.add(bounds)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLiveNear(child, element, out, depth + 1)
            child.recycle()
        }
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N5) — the screenshot channel's
     * Android half. The consent gate for screen capture, shared
     * with the plan executor (PlanWiring passes this same instance).
     * The executor grants it per navigation task and revokes it
     * after the capture; [captureScreenshotFrame] is only ever
     * called through that gate.
     */
    val screenshotGate = ScreenshotCaptureGate()

    /**
     * Captures one screenshot frame via
     * AccessibilityService.takeScreenshot (API 30+ — David's Redmi
     * 14C qualifies; below API 30 this returns null, honestly).
     * Only runs when the gate holds a grant (Granted) or a capture
     * is in flight (Capturing) — a direct call with no grant
     * returns null and captures nothing. The frame is downscaled
     * to at most 360px wide, held in memory only, never written to
     * disk, never logged. Null = no frame (unavailable / timed out
     * after 5 s / decode failed) — the caller proceeds tree-only.
     * Blocking up to 5 s; called from the executor's background
     * thread, never the main thread.
     */
    fun captureScreenshotFrame(): VisualObservation? {
        val gateState = screenshotGate.state()
        if (gateState !is ScreenshotCaptureState.Granted &&
            gateState !is ScreenshotCaptureState.Capturing
        ) {
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var frame: VisualObservation? = null
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            frame = decodeFrame(result)
                        } catch (_: Exception) {
                            frame = null
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        frame = null
                        latch.countDown()
                    }
                },
            )
        } catch (_: Exception) {
            return null
        }
        return try {
            if (!latch.await(5, TimeUnit.SECONDS)) null else frame
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** Decodes a ScreenshotResult into a downscaled RGB frame. */
    private fun decodeFrame(result: ScreenshotResult): VisualObservation? {
        val buffer = result.hardwareBuffer ?: return null
        return try {
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val maxWidth = 360
            val scaled = if (bitmap.width > maxWidth) {
                val h = (bitmap.height * maxWidth) / bitmap.width.coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, maxWidth, h.coerceAtLeast(1), true)
            } else {
                bitmap
            }
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            for (i in pixels.indices) {
                val px = pixels[i]
                rgb[i * 3] = ((px shr 16) and 0xFF).toByte()
                rgb[i * 3 + 1] = ((px shr 8) and 0xFF).toByte()
                rgb[i * 3 + 2] = (px and 0xFF).toByte()
            }
            if (scaled !== bitmap) scaled.recycle()
            VisualObservation(
                width = w,
                height = h,
                capturedAtMs = System.currentTimeMillis(),
                pixelBytes = rgb,
            )
        } finally {
            buffer.close()
        }
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): LONG-PRESS [element] — the tap
     * held down for [holdMsMs] (default 700ms, clamped 300–5000ms).
     * Same gate keys as [requestTap] (`tap.guidance` / `tap.device`) —
     * a long-press IS a tap held down, not a new authorization class —
     * same atomic consume+dispatch boundary, same async completion
     * callback. The gesture is a single-stroke hold: the stroke stays
     * at the element centre for the full duration, so the system reads
     * it as a long-press (context menu / selection handles), not a tap.
     * No step requests a long-press today; the sink is wired, the
     * trigger is not invented.
     */
    fun requestLongPress(
        element: ScreenElement,
        authToken: String?,
        holdMsMs: Long = 700L,
    ): TapResult {
        if (!authGateReady) {
            return TapResult.GateRequired("AuthGate is not ready.")
        }
        // Universal Console (2026-11-07): the sensitive pre-check
        // every other sink in this file makes FIRST — this sink
        // never had it, so a long-press was the one gesture that
        // could reach a banking app without the service refusing.
        // The dispatcher also pre-checks, but the sink must refuse
        // on its own: defence in depth, same rule, same wording.
        val pkg = foregroundPackageForPointer()
        if (pkg != null && ScreenDataPolicy.isSensitivePackage(pkg)) {
            return TapResult.GateRequired(
                "Guidance only in this app — banking and finance apps are never automated.",
            )
        }
        if (element.bounds.isEmpty) {
            return TapResult.Failed("The target has no position on screen.")
        }
        val holdMs = holdMsMs.coerceIn(300L, 5000L)
        val gate = AuthGate.getOrNull()
            ?: return TapResult.GateRequired("AuthGate is not initialized.")
        return try {
            when (
                val r = gate.consumeAndDispatch(
                    authToken,
                    listOf(
                        AuthActionRegistry.ACTION_TAP_GUIDANCE,
                        AuthActionRegistry.ACTION_TAP,
                    ),
                ) {
                    val path = Path().apply {
                        moveTo(element.bounds.exactCenterX(), element.bounds.exactCenterY())
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, holdMs))
                        .build()
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                LoomEventBus.post(LoomStateEvent.Completed)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                LoomEventBus.post(LoomStateEvent.Error("Long-press gesture cancelled by the system."))
                            }
                        },
                        null,
                    )
                }
            ) {
                is AuthGate.DispatchResult.Dispatched ->
                    if (r.value) TapResult.Dispatched
                    else TapResult.Failed("System refused the gesture dispatch call.")
                is AuthGate.DispatchResult.Refused ->
                    TapResult.GateRequired("Long-press dispatch refused: ${r.reason}")
            }
        } catch (e: Exception) {
            TapResult.Failed(e.message ?: "dispatch threw")
        }
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): SELECT the text of [element] —
     * UNWIRED at the gate. The safety pre-checks below are real and run
     * first (sensitive package, password field, empty bounds), but the
     * dispatch itself refuses: NO AuthGate key exists for text
     * selection yet (proposed `select.text`, CONFIRM, never
     * grant-eligible). Creating or probing a key here would invent
     * grant-eligibility — so this sink refuses with the exact missing
     * key instead of dispatching. The platform mechanics are built and
     * documented in [selectTextHandoff] (node lookup by bounds,
     * ACTION_SET_SELECTION on editable text, long-press fallback for
     * read-only text); the handoff runs ONLY once a `select.text` token
     * can be issued, i.e. after David approves the key. Nothing is
     * selected and nothing reaches the clipboard until then.
     */
    fun requestSelectText(element: ScreenElement, authToken: String?): TapResult {
        // Sensitive-package refusal FIRST, before any token is touched.
        val pkg = foregroundPackageForPointer()
        if (pkg != null && ScreenDataPolicy.isSensitivePackage(pkg)) {
            return TapResult.GateRequired(
                "Guidance only in this app — text is never selected in " +
                    "banking and finance apps.",
            )
        }
        if (element.isPassword) {
            return TapResult.GateRequired("Password fields are never selected.")
        }
        if (element.bounds.isEmpty) {
            return TapResult.Failed("The target has no position on screen.")
        }
        // No key exists: refuse honestly, token untouched.
        return TapResult.GateRequired(
            "Text selection needs your explicit authorization — no AuthGate " +
                "key exists for it yet (proposed: select.text). Nothing was selected.",
        )
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): COPY the current selection of
     * [element] — UNWIRED at the gate, same reason as [requestSelectText]:
     * no AuthGate key exists for copying yet (proposed `copy.selection`,
     * CONFIRM, never grant-eligible — copy touches the clipboard, which
     * can hold passwords, so every copy cards). The safety pre-checks
     * are real and run first; the dispatch refuses with the exact
     * missing key. The platform mechanics are built in [copyHandoff]
     * (ACTION_COPY on the node holding the selection). This method
     * NEVER reads the clipboard — it only ever writes the exact
     * user-directed selection, once the key exists.
     */
    fun requestCopySelection(element: ScreenElement, authToken: String?): TapResult {
        val pkg = foregroundPackageForPointer()
        if (pkg != null && ScreenDataPolicy.isSensitivePackage(pkg)) {
            return TapResult.GateRequired(
                "Guidance only in this app — nothing is copied in " +
                    "banking and finance apps.",
            )
        }
        if (element.isPassword) {
            return TapResult.GateRequired("Password fields are never copied.")
        }
        if (element.bounds.isEmpty) {
            return TapResult.Failed("The target has no position on screen.")
        }
        return TapResult.GateRequired(
            "Copying needs your explicit authorization — no AuthGate key " +
                "exists for it yet (proposed: copy.selection). Nothing was copied.",
        )
    }

    /**
     * Foreground package for the pointer sinks' sensitive pre-check.
     * (Named for the pointer verbs to avoid colliding with the
     * extensions file's private foregroundPackage().)
     */
    private fun foregroundPackageForPointer(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    /**
     * UNWIRED select handoff (see [requestSelectText]): find the node
     * at [element]'s bounds, then select its text. Editable text first
     * (ACTION_SET_SELECTION over the whole text — the programmatic
     * route), read-only text second (a hold gesture at the text, which
     * raises the system's own selection handles). Returns true when a
     * selection is now active on the node. NOT called anywhere until a
     * `select.text` token can be issued — it is the implementation
     * waiting on David's authorization, not a live path.
     */
    @Suppress("unused")
    private fun selectTextHandoff(element: ScreenElement): Boolean {
        val node = findNodeByBounds(element.bounds) ?: return false
        try {
            val text = node.text?.toString().orEmpty()
            if (text.isEmpty()) return false
            // Route 1 — editable text: ACTION_SET_SELECTION (API 18+).
            try {
                val args = Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        0,
                    )
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                        text.length,
                    )
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
                    return true
                }
            } catch (_: Exception) {
                // fall through to the long-press route
            }
            // Route 2 — read-only text: hold at the text centre so the
            // system raises its own selection handles.
            val path = Path().apply {
                moveTo(element.bounds.exactCenterX(), element.bounds.exactCenterY())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 700))
                .build()
            return dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            return false
        } finally {
            try {
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * UNWIRED copy handoff (see [requestCopySelection]): ACTION_COPY on
     * the node at [element]'s bounds — copies whatever selection is
     * active on that node to the clipboard. The caller guarantees the
     * selection is the exact user-directed one ([GuidanceSession]
     * tracks it); this handoff never selects, never reads the
     * clipboard, never copies anything else. NOT called anywhere until
     * a `copy.selection` token can be issued.
     */
    @Suppress("unused")
    private fun copyHandoff(element: ScreenElement): Boolean {
        val node = findNodeByBounds(element.bounds) ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_COPY)
        } catch (_: Exception) {
            false
        } finally {
            try {
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Find the node whose screen bounds match [bounds] (exact match). */
    private fun findNodeByBounds(bounds: Rect): AccessibilityNodeInfo? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)
                if (nodeBounds == bounds) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            } catch (_: Exception) {
                // keep walking
            }
        }
        return null
    }

    // == 2C guidance overlay host ==
    // Stage 2C (A1) — trusted guidance window. The guidance renderer is
    // hosted in a TYPE_ACCESSIBILITY_OVERLAY window from this service's own
    // WindowManager (deterministic topmost z-order per the 2B D2 decision).
    // The window is NOT_TOUCHABLE | NOT_FOCUSABLE | LAYOUT_IN_SCREEN, so
    // David's touches pass through to the app under the highlight.
    //
    // Fallback matrix (2B design §7): when this host is unavailable (a11y
    // service off), guidance degrades to a display-only constrained overlay
    // hosted by LoomOverlayService — gestures stay disabled there. This
    // service never hosts the fallback itself.
    //
    // APPEND-ONLY region: requestTap and every existing method above are
    // untouched.

    /** Views currently attached to the trusted guidance overlay. */
    private val guidanceOverlayViews = mutableSetOf<View>()

    /**
     * True while the trusted overlay host can attach guidance windows:
     * this service instance is the live one. (minSdk 26, so
     * TYPE_ACCESSIBILITY_OVERLAY always exists.)
     */
    fun isTrustedHostAvailable(): Boolean = instance === this

    /**
     * Adds [view] to the trusted guidance overlay window. Returns true when
     * the view is attached (or was already attached). False when the host is
     * unavailable or the window manager refused — the caller then falls back
     * to the display-only constrained overlay per the 2B §7 matrix.
     *
     * Device-evidence fix (2026-11-05, item 1): log the outcome every
     * time — the old code swallowed refusals silently, so a dead window
     * looked identical to a live one from every surface.
     */
    fun addGuidanceOverlayView(view: View): Boolean {
        if (!isTrustedHostAvailable()) {
            Log.w("LoomPointer", "addGuidanceOverlayView refused: host not available")
            return false
        }
        if (guidanceOverlayViews.contains(view)) {
            Log.d(
                "LoomPointer",
                "addGuidanceOverlayView: already attached, size=${view.width}x${view.height}",
            )
            return true
        }
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            wm.addView(view, params)
            guidanceOverlayViews.add(view)
            Log.i(
                "LoomPointer",
                "addGuidanceOverlayView OK: attached=${view.isAttachedToWindow} " +
                    "size=${view.width}x${view.height}",
            )
            true
        } catch (e: Exception) {
            Log.w("LoomPointer", "addGuidanceOverlayView failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Removes a view previously added with [addGuidanceOverlayView]. Safe to call for unknown views. */
    fun removeGuidanceOverlayView(view: View) {
        if (!guidanceOverlayViews.remove(view)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
            Log.d("LoomPointer", "removeGuidanceOverlayView OK: attached=${view.isAttachedToWindow}")
        } catch (e: Exception) {
            Log.w("LoomPointer", "removeGuidanceOverlayView failed: ${e.javaClass.simpleName}")
            // Already gone — nothing to do.
        }
    }

    /** True when [view] is currently attached to the trusted overlay. */
    fun isGuidanceOverlayAttached(view: View): Boolean = guidanceOverlayViews.contains(view)

    /**
     * Device-evidence fix (2026-11-05, item 1): the Device Control
     * "Guidance window" row reads this — the state the old indicators
     * never showed. ATTACHED = a guidance view is in the window;
     * DETACHED = views are tracked but none is actually attached (the
     * stale-renderer symptom); NONE = no guidance window exists.
     */
    fun guidanceOverlayState(): GuidanceOverlayState {
        if (guidanceOverlayViews.isEmpty()) return GuidanceOverlayState.NONE
        return if (guidanceOverlayViews.any { it.isAttachedToWindow }) {
            GuidanceOverlayState.ATTACHED
        } else {
            GuidanceOverlayState.DETACHED
        }
    }

    /** Guidance-window state for the Device Control row. */
    enum class GuidanceOverlayState {
        NONE,
        ATTACHED,
        DETACHED,
    }

    companion object {
        /** Set by the app once AuthGate.init() has run (Phase 6 wiring). */
        @Volatile
        var authGateReady: Boolean = false

        /** Live service instance, or null when not connected. */
        @Volatile
        var instance: LoomAccessibilityService? = null
            private set

        /**
         * Binding-survival diagnostics (2026-11-06): when the live
         * binding was last lost, and why — read from logcat, never
         * shown to the user. "service unbound by the system" is the
         * normal system path; "service destroyed" is a kill. 0 =
         * never lost in this process. Set in onUnbind/onDestroy only
         * — onInterrupt no longer clears the binding, so it never
         * writes here.
         */
        @Volatile
        var lastUnbindAtMs: Long = 0L
            private set

        @Volatile
        var lastUnbindWhy: String = ""
            private set

        /**
         * Pointer failure fix (2026-11-05): LIVE liveness, not the
         * switch. True only when the service instance is connected
         * AND bound into [ScreenAwarenessHolder] — the same live
         * signal DeviceControlCenter.isServiceBound() reads. The
         * Settings.Secure flag alone can stay "on" after HyperOS
         * kills the service, so Home checks this before attempting
         * a render it cannot win. Never throws.
         *
         * Binding survival (2026-11-06): Android offers NO API for
         * an app to bind — or rebind — its own accessibility
         * service; only the system binds it, and only the user can
         * switch it on in Settings. So nothing here "reconnects"
         * the service: when this reads false with the flag on, the
         * honest paths are (a) wait briefly — opening the app gives
         * Android a live process to bind into, and the system
         * rebinds an enabled service on its own — then (b) send the
         * user to the toggle with the exact steps. Any code that
         * claims to rebind the service programmatically is wrong.
         */
        fun isLive(): Boolean = try {
            instance != null && ScreenAwarenessHolder.isBound
        } catch (_: Exception) {
            false
        }

        /**
         * Device-evidence fix (2026-11-05, item 1): guidance-window
         * state for the Device Control row, readable without holding a
         * service reference. NONE when the service is not connected.
         */
        fun currentGuidanceOverlayState(): GuidanceOverlayState =
            instance?.guidanceOverlayState() ?: GuidanceOverlayState.NONE

        /**
         * Device-evidence fix (2026-11-05, pointer H1): host-change
         * invalidation, owned HERE (not in LoomOverlayService) so this
         * service's lifecycle callbacks need no cross-service reference
         * and the flag is readable from the overlay service's
         * ensureGuidanceRenderer() — which already depends on this class.
         * Set on onServiceConnected / onUnbind; the overlay service
         * consumes it on its next renderer validation and drops any
         * cache bound to the old host.
         */
        @Volatile
        private var guidanceRendererInvalidated = false

        fun invalidateGuidanceRenderer() {
            guidanceRendererInvalidated = true
        }

        fun consumeGuidanceRendererInvalidation(): Boolean {
            val was = guidanceRendererInvalidated
            guidanceRendererInvalidated = false
            return was
        }
    }
}
