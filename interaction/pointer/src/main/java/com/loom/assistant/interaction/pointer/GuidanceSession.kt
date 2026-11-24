package com.loom.assistant.interaction.pointer

import android.graphics.Rect
import android.graphics.RectF
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.NavigationResolver
import com.loom.assistant.execution.screenawareness.NoChangeTracker
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenDescriber
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SearchScreenPredicates
import com.loom.assistant.execution.screenawareness.StopLimits
import com.loom.assistant.execution.screenawareness.TargetAliases
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate

/**
 * Seam for the accessibility tree behind a guidance session.
 *
 * The production default is [DefaultGuidanceTreeSource] (separate file, so
 * JVM tests can compile this session with a fake tree without pulling in
 * the Android-coupled [ScreenAwarenessHolder]).
 */
interface GuidanceTreeSource {
    val isBound: Boolean
    fun snapshot(): ScreenModel?
}

/**
 * Phase 4 — pointer guidance session (arch §2.5, lockdown card 17),
 * Stage 2C: target verification (B1/B2/B3/B4).
 *
 * A session is an ordered list of steps. Each step names a target UI element
 * (matched against the accessibility tree) and an instruction. The session
 * drives the guidance renderer: beam + highlight ring + "Step N of M"
 * tooltip — via [GuidanceRendererContract] when the interaction layer
 * injects a renderer, otherwise through the host-registered
 * [PointerGuideViewHolder.view] and the host's [Listener] (which applies its
 * own pointer-policy gate before drawing; that path is behavior-unchanged).
 *
 * Anchor point: the character/orb's current screen position, or the screen
 * edge in invisible mode — supplied per step by [anchorProvider] (Stage 2C:
 * the host drives this from AnchorResolver; anchor changes arrive at the
 * renderer as animated events, never teleports).
 *
 * Honest limits (A5/B2/B3/B4):
 *  - Every step re-verifies its target against the live tree before
 *    highlighting (TOCTOU-safe): tree unavailable / no match / multi-match
 *    ("N matches, be more specific") / target moved between two consecutive
 *    snapshots all surface as [StepResult.Unavailable] with the exact
 *    reason — never a fake step.
 *  - A stability check (same bounds across two consecutive snapshots) kills
 *    highlight jitter on animated screens.
 *  - Inside a sensitive package, resolution returns bounds+roles only
 *    (ScreenDataPolicy.redact); identification says honestly what it can
 *    see ("I can see a button here, but the app hides its label").
 *
 * Performing the tap (dispatchGesture) stays behind the AuthGate — see
 * [requestTap]. Guidance itself is display-only and tokenless.
 *
 * POINTER OPERATION SET (David's direction 2026-11-05 18:38 + 18:40):
 * the pointer serves TWO purposes — guidance AND operations. The few
 * bright darts are a general interaction device, not guidance-only:
 * TAP/CLICK (the tap at the verified target, [requestTap]), OPEN
 * ([requestOpen] — tap-to-open an element; app launch uses the existing
 * `app.launch` sink), SCROLL ([requestScroll] — pointer-directed,
 * `device.scroll` key), LONG-PRESS ([requestLongPress]), SELECT +
 * COPY ([requestSelect]/[requestCopy] — verified + previewed + narrated,
 * but UNWIRED at the gate: no AuthGate key exists for `select.text` /
 * `copy.selection` yet, so they refuse honestly until David approves
 * those keys; nothing is selected, nothing reaches the clipboard).
 * SEARCH + TYPE are first-class verbs too — the search sequence (tap
 * the field → wait for focus → type the exact text → submit → verify
 * results) is owned by SearchExecutor; the pointer's role in it is the
 * choreography (glide to the field, park the cursor, pill narrates
 * "Typing…"), wired in SearchExecutorWiring, not here. Safety floor
 * throughout: sensitive apps guidance-only, password fields never
 * selected/copied, one user-directed action at a time.
 */
class GuidanceSession(
    steps: List<GuideStep>,
    private val tree: GuidanceTreeSource = DefaultGuidanceTreeSource,
    /**
     * Stage 2C — renderer injected by the interaction layer. When null, the
     * session falls back to [PointerGuideViewHolder.view] and the host's
     * [Listener.onStepShown] (LoomOverlayService path — behavior unchanged,
     * including the host's pointer-policy gate).
     *
     * Device-evidence fix (2026-11-05, pointer H6): [rendererProvider]
     * re-resolves the renderer on EVERY step instead of holding the one
     * injected at construction — after the accessibility service
     * rebounds or a mode switch re-layers the window, the construction
     * renderer belongs to a dead host and draws into a detached view.
     * The provider wins when non-null; [renderer] stays as the fallback
     * (and the only renderer the JVM tests use).
     */
    private val renderer: GuidanceRendererContract? = null,
    private val rendererProvider: (() -> GuidanceRendererContract?)? = null,
    /**
     * MASTER F6a — provenance of this session: DIRECT_USER when the
     * session was started by David's own voice/text command, MODEL
     * only for model-proposed guidance. Rides into AuthGate.request
     * so a stored "always allow" grant can skip the card only for
     * his own commands — never for model requests. Default
     * DIRECT_USER: user-started sessions are the norm; the host
     * passes MODEL explicitly for model-proposed guidance.
     */
    private val origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    /**
     * MASTER F6a — resolves a package name to its display label for
     * the gate action. The label is what makes "Always allow"
     * renderable (the registry builds the grant offer only when
     * scope AND label are non-blank) and what stored grants match
     * on. Null = no catalogue available; the session then falls
     * back to the package name itself — never a blank label while
     * the package is known.
     */
    private val appLabelProvider: ((packageName: String) -> String?)? = null,
    /**
     * Device-test failures (2026-11-11, D-08): LOOM's own package.
     * On the own screen, TEXT-role matches are chat echoes, never
     * targets (see resolveOnScreen). Blank = unknown: no filtering.
     */
    private val ownPackageName: String = "",
    /**
     * Anchor point: the character/orb's current screen position, or the
     * screen edge in invisible mode. LAST parameter so the host's trailing-
     * lambda call `GuidanceSession(steps) { currentAnchor() }` keeps
     * compiling unchanged.
     */
    private val anchorProvider: () -> Pair<Float, Float>,
) {

    data class GuideStep(
        /** Text / content-desc / class fragment matched against the a11y tree. */
        val targetQuery: String,
        val instruction: String,
    )

    sealed class StepResult {
        data class Shown(val stepIndex: Int, val stepCount: Int) : StepResult()
        data class Unavailable(val reason: String) : StepResult()
        data object Finished : StepResult()
    }

    sealed class TapResult {
        data object NotImplemented : TapResult()
        data object Dispatched : TapResult()
        data class AwaitingConfirmation(val message: String) : TapResult()
        data class GateRequired(val reason: String) : TapResult()
        data class Unavailable(val reason: String) : TapResult()
    }

    interface Listener {
        fun onStepShown(view: PointerGuideView, step: PointerGuideView.Step)
        fun onSessionEvent(result: StepResult)
    }

    var listener: Listener? = null

    /**
     * Phase 6 — tap dispatch hook, wired by the overlay host (which owns both
     * this session and the accessibility service). Keeping the dispatch behind
     * a lambda avoids a module cycle (services already depends on pointer).
     * Invoked with the resolved element and the gate-issued token id;
     * returns true when the system accepted the gesture.
     */
    var tapDispatcher: ((element: ScreenElement, tokenId: String) -> Boolean)? = null

    /**
     * Phase 6 (pointer deltas 2026-11-05) — long-press dispatch hook,
     * wired by the overlay host (which owns both this session and the
     * accessibility service). Invoked with the resolved element, the
     * gate-issued token id and the hold duration in ms; returns true
     * when the system accepted the hold gesture. Same AuthGate key as
     * the tap (`tap.guidance`) — a long-press IS a tap held down, not a
     * new authorization class — and the same verify-before-dispatch
     * runs before it. No step requests a long-press today (no voice
     * command builds one); the capability is wired, the trigger is not
     * invented.
     */
    var longPressDispatcher: ((element: ScreenElement, tokenId: String, holdMs: Long) -> Boolean)? = null

    /**
     * POINTER MOUSE-VERBS (2026-11-05) — pointer-directed scroll hook,
     * wired by the overlay host to `LoomAccessibilityService.scrollScreen`
     * (`device.scroll` key, existing). Invoked with the scroll direction
     * and the gate-issued token id; returns true when the system
     * accepted the scroll. The session moves the pointer to the scroll
     * target and narrates ("Scrolling down…") before dispatching.
     */
    var scrollDispatcher: ((forward: Boolean, tokenId: String) -> Boolean)? = null

    /**
     * POINTER MOUSE-VERBS (2026-11-05) — the last CONFIRMED selection.
     * Set ONLY by a confirmed select (which cannot happen until David
     * approves the `select.text` key), read by [requestCopy] so copy
     * only ever copies the exact user-directed selection. Null until
     * then — copy without a selection refuses honestly. Internal so
     * JVM tests can stage a selection; production never sets it by hand.
     */
    internal var lastSelection: PointerSelection.TextSelection? = null

    private var steps: List<GuideStep> = steps
    private var index = -1
    val isActive: Boolean get() = index in steps.indices
    val currentIndex: Int get() = index

    /**
     * MASTER F6a — the display label for a foreground package:
     * the catalogue label when the provider knows it, the package
     * name itself when it does not, "" only when no package is
     * known at all. A blank label would suppress the grant offer
     * and the stored-grant match, so it is never produced while
     * the package is known.
     */
    private fun appLabelFor(pkg: String): String =
        if (pkg.isBlank()) "" else appLabelProvider?.invoke(pkg)?.takeIf { it.isNotBlank() } ?: pkg

    fun start(): StepResult {
        if (steps.isEmpty()) return StepResult.Unavailable("Guidance session has no steps.")
        index = 0
        LoomEventBus.post(LoomStateEvent.Executing)
        return showCurrent()
    }

    /**
     * Stage 2C (A4) — start a single-target guidance session from a raw
     * query ("guide me to X"). Display-only and tokenless: no AuthGate
     * involvement. The interaction layer builds richer multi-step plans
     * itself and calls [start].
     */
    fun start(query: String): StepResult {
        val q = query.trim()
        if (q.isEmpty()) {
            return StepResult.Unavailable("Nothing to guide to — say what to look for on screen.")
                .also { listener?.onSessionEvent(it) }
        }
        steps = listOf(GuideStep(targetQuery = q, instruction = "Guide to \"$q\""))
        return start()
    }

    fun next(): StepResult {
        if (!isActive) return StepResult.Unavailable("No active guidance session.")
        index++
        return if (index >= steps.size) {
            index = -1
            LoomEventBus.post(LoomStateEvent.Completed)
            StepResult.Finished.also { listener?.onSessionEvent(it) }
        } else {
            showCurrent()
        }
    }

    fun back(): StepResult {
        if (!isActive) return StepResult.Unavailable("No active guidance session.")
        if (index > 0) index--
        return showCurrent()
    }

    fun end() {
        index = -1
        listener?.onSessionEvent(StepResult.Finished)
    }

    // ---- Stage 2C target verification (B1/B2/B3/B4) ----

    private sealed interface TargetResolution {
        data class Found(val element: ScreenElement, val redacted: Boolean) : TargetResolution
        data class Failed(val reason: String) : TargetResolution
    }

    /** B2: bounds are stable when every edge moved at most [STABLE_TOLERANCE_PX]. */
    private fun boundsStable(a: Rect, b: Rect): Boolean {
        if (a == b) return true
        return kotlin.math.abs(a.left - b.left) <= STABLE_TOLERANCE_PX &&
            kotlin.math.abs(a.top - b.top) <= STABLE_TOLERANCE_PX &&
            kotlin.math.abs(a.right - b.right) <= STABLE_TOLERANCE_PX &&
            kotlin.math.abs(a.bottom - b.bottom) <= STABLE_TOLERANCE_PX
    }

    /**
     * Resolve [query] against the live tree with re-verification:
     * two consecutive snapshots must agree on the element's bounds, or the
     * step is refused honestly (no highlight jitter, no stale taps).
     *
     * Common sense (2026-11-10): resolution runs through
     * [TargetAliases.resolveElements] — the same resolver the plan
     * path uses — so a perspective label ("Your story" for "my
     * story") and a close label ("Nigeria vs Japan" for "Nigeria
     * versus Japan") resolve, and the voice-search mic never counts.
     * Exact matches win; a close match is accepted only when no exact
     * match exists and only one close candidate is plausible — the
     * B3 multi-match refusal below still guards ambiguity.
     */
    private fun resolveTarget(query: String): TargetResolution {
        if (!tree.isBound) {
            return TargetResolution.Failed(
                "Screen reading is not enabled — turn on LOOM in " +
                    "Settings → Accessibility → Device Control to use guidance.",
            )
        }
        val snap1 = tree.snapshot()
            ?: return TargetResolution.Failed(
                "Could not read the screen — the accessibility tree is unavailable.",
            )
        val rawFirst = TargetAliases.resolveElements(snap1.elements, query)
        // NAV-SCREEN fix (2026-11-11, N2): "guide me to Reels" must
        // resolve an icon-only tab too. When the alias tier finds
        // nothing, the tab resolver gets its turn (label → synonym →
        // ordinal → positional) before the step fails as not-found.
        val navFirst = if (rawFirst.isEmpty()) {
            NavigationResolver.resolveNavigationTarget(snap1, query).map { it.element }
        } else emptyList()
        val firstPass = resolveOnScreen(snap1.elements, query, snap1.packageName)
            .ifEmpty { navFirst }
        if (firstPass.isEmpty()) {
            // D-08: the only matches were chat echoes on LOOM's own
            // screen — say so and name the fix, instead of parking
            // the pointer on the conversation.
            if (rawFirst.isNotEmpty() && ownPackageName.isNotBlank() &&
                snap1.packageName == ownPackageName
            ) {
                return TargetResolution.Failed(
                    "You're looking at LOOM's own chat screen — the words " +
                        "there are our conversation, not the app. Open the app " +
                        "and ask me to guide you to \"$query\" again.",
                )
            }
            return TargetResolution.Failed(notFoundReason(query, snap1))
        }
        // Screen fix (2026-11-11, CORRECTED — differentiation): a
        // Found is only honest for a target the user can SEE. The
        // full tree may hold the target below the fold — LOOM knows
        // where it is, the user does not. Matches with no visible
        // member report not-found, so the scroll hunt
        // (resolveTargetWithScroll) brings it up instead of drawing
        // the pointer — and claiming Shown — at off-screen
        // coordinates. The snapshot itself stays full-tree; only the
        // user-facing resolution is viewport-grounded.
        val visibleFirst = firstPass.filter { snap1.isVisibleElement(it) }
        if (visibleFirst.isEmpty()) {
            return TargetResolution.Failed(notFoundReason(query, snap1))
        }
        if (visibleFirst.size > 1) {
            // B3: refuse to guess between multiple matches.
            return TargetResolution.Failed("${visibleFirst.size} matches for \"$query\" — be more specific.")
        }
        val first = visibleFirst[0]
        val snap2 = tree.snapshot()
            ?: return TargetResolution.Failed(
                "The screen changed while verifying the target — try again.",
            )
        val secondPass = resolveOnScreen(snap2.elements, query, snap2.packageName)
            // NAV-SCREEN fix (2026-11-11, N2): the stability re-check
            // needs the nav tier too — an icon-only tab resolved in
            // the first pass must still be findable in the second.
            .ifEmpty { NavigationResolver.resolveNavigationTarget(snap2, query).map { it.element } }
            .filter { snap2.isVisibleElement(it) }
        val second = secondPass.firstOrNull { it.id == first.id } ?: secondPass.firstOrNull()
            ?: return TargetResolution.Failed(
                "The target disappeared while verifying — try again.",
            )
        if (!boundsStable(first.bounds, second.bounds)) {
            return TargetResolution.Failed(
                "The target is moving on screen — wait a moment and try again.",
            )
        }
        // B4: inside a sensitive package only bounds+roles survive redaction.
        val redacted = ScreenDataPolicy.isSensitivePackage(snap2.packageName)
        return TargetResolution.Found(second, redacted)
    }

    /**
     * Device-test failures (2026-11-11, D-08): resolve a target
     * against a screen, excluding LOOM's own chat echoes. On the
     * own screen the transcript echoes David's words — a bubble
     * saying "tap my story" matches the query "my story" as TEXT,
     * and the pointer parked on the chat echo (the cyan frame over
     * the chat in the Nov 11 screenshots) instead of the app.
     * TEXT-role elements are never guidance targets on the own
     * screen; BUTTON/IMAGE/ICON targets (the orb, the mic) still
     * resolve. Any other screen resolves unchanged.
     */
    private fun resolveOnScreen(
        elements: List<ScreenElement>,
        query: String,
        packageName: String,
    ): List<ScreenElement> {
        val matches = TargetAliases.resolveElements(elements, query)
        if (ownPackageName.isBlank() || packageName != ownPackageName) return matches
        return matches.filter { it.role != ElementRole.TEXT }
    }

    /**
     * Screen search (2026-11-10, corrected 2026-11-11 01:54, David's
     * rule): a not-found answer names what was looked for and WHERE
     * LOOM looked — never a list of what is on the screen, and never
     * a question asking the user where the thing is. "Guide me to X"
     * means LOOM finds X and shows it; when it is not on the current
     * screen LOOM goes and finds it (scroll / navigate / open the
     * right screen — see resolveTargetWithScroll). The outcome is
     * found-and-shown, or an honest report that it searched and
     * could not find it. (The one exception is ambiguity: two
     * visible candidates that could both be the one he means — there
     * LOOM still asks which one instead of guessing and tapping the
     * wrong one. That is disambiguation, not asking where it is.)
     */
    private fun notFoundReason(query: String, snap: ScreenModel): String =
        "I looked for \"$query\" in ${ScreenDescriber.appLabel(snap.packageName)} " +
            "and it is not on this screen."

    /**
     * Screen navigation (2026-11-10, David's rule): resolve with a
     * bounded scroll hunt — a target below the fold is found by
     * scrolling, not reported as gone. Bounded by the Master P0 stop
     * rule ([StopLimits]): at most [StopLimits.MAX_SCROLLS_PER_SEARCH]
     * scrolls, and a scroll that changes nothing twice in a row stops
     * the hunt (end of the list). Only a plain not-found failure is
     * worth scrolling for — an unbound tree, a moving target, or an
     * ambiguous match is not fixed by scrolling. A scroll that needs
     * David's approval stops the hunt honestly instead of scrolling
     * unconfirmed; the hunt's outcome (found / scrolled to the end /
     * could not scroll) is named in the failure reason.
     */
    private fun resolveTargetWithScroll(query: String): TargetResolution {
        val first = resolveTarget(query)
        if (first is TargetResolution.Found) return first
        if (first is TargetResolution.Failed && !first.reason.startsWith("I looked for")) {
            return first
        }
        // NAV-SCREEN fix (2026-11-11, N8): never scroll the feed
        // looking for a navigation target — the nav bar is already
        // visible and never moves. The not-found answer stands.
        if (NavigationResolver.isChromeQuery(query)) {
            return first
        }
        // Nothing to scroll: a screen with no scrollable element and
        // no list cannot reveal a target below the fold — the plain
        // not-found answer stands, no useless scrolls dispatched.
        val firstSnap = tree.snapshot()
        if (firstSnap != null &&
            firstSnap.elements.none { it.scrollable || it.role == ElementRole.LIST }
        ) {
            return first
        }
        var lastSignature = tree.snapshot()?.let { SearchScreenPredicates.viewportSignature(it) }
        val noChange = NoChangeTracker()
        var scrolls = 0
        while (scrolls < StopLimits.MAX_SCROLLS_PER_SEARCH) {
            when (val scrolled = requestScroll(forward = true, resolveFirst = false)) {
                is TapResult.Dispatched -> Unit
                is TapResult.AwaitingConfirmation -> return TargetResolution.Failed(
                    "I need your approval to scroll — the scroll card is on screen. " +
                        "Approve it, then ask me to guide you to \"$query\" again.",
                )
                is TapResult.Unavailable -> return TargetResolution.Failed(
                    "I looked for \"$query\" and could not scroll to look for it — ${scrolled.reason}",
                )
                else -> return TargetResolution.Failed(
                    "I looked for \"$query\" and could not scroll to look for it.",
                )
            }
            scrolls++
            val snap = tree.snapshot()
            val signature = snap?.let { SearchScreenPredicates.viewportSignature(it) }
            if (signature != null && lastSignature != null &&
                noChange.observe(signature == lastSignature)
            ) {
                // Corrected 2026-11-11 01:54: the end-of-list report
                // names where LOOM looked — never a list of what is
                // on the screen.
                return TargetResolution.Failed(
                    "I looked for \"$query\" and scrolled to the end of this screen — ${StopLimits.REACHED_END_NOT_FOUND}.",
                )
            }
            lastSignature = signature
            when (val found = resolveTarget(query)) {
                is TargetResolution.Found -> return found
                is TargetResolution.Failed -> {
                    if (!found.reason.startsWith("I looked for")) return found
                }
            }
        }
        return TargetResolution.Failed(
            "I looked for \"$query\" and ${StopLimits.MAX_SCROLLS_STOPPING} — it is not on this screen.",
        )
    }

    private fun roleNoun(role: ElementRole): String = when (role) {
        ElementRole.BUTTON -> "button"
        ElementRole.TOGGLE -> "toggle"
        ElementRole.INPUT -> "text field"
        ElementRole.MENU_ITEM -> "menu item"
        ElementRole.TEXT -> "text"
        ElementRole.IMAGE -> "image"
        ElementRole.OTHER -> "control"
        ElementRole.ICON -> "icon"
        ElementRole.SEARCH_FIELD -> "search field"
        ElementRole.DIALOG -> "dialog"
        ElementRole.NAVIGATION -> "navigation"
        ElementRole.LIST -> "list"
    }

    private fun showCurrent(): StepResult {
        val step = steps.getOrNull(index)
            ?: return StepResult.Unavailable("Step out of range.")
        // Screen navigation (2026-11-10): a target below the fold
        // is found by scrolling, not reported as gone.
        return when (val res = resolveTargetWithScroll(step.targetQuery)) {
            is TargetResolution.Failed -> {
                val r = StepResult.Unavailable(res.reason)
                listener?.onSessionEvent(r)
                r
            }
            is TargetResolution.Found -> {
                val (ax, ay) = anchorProvider()
                val target = RectF(res.element.bounds)
                // B4: honest identification inside sensitive apps — the
                // bounds are verified, the label is withheld.
                val instruction = if (res.redacted) {
                    "I can see a ${roleNoun(res.element.role)} here, but the app hides its label."
                } else {
                    step.instruction
                }
                // H6: re-resolve the renderer per step — never draw into
                // a stale one after a host rebind or re-layer.
                val injected = rendererProvider?.invoke() ?: renderer
                if (injected != null) {
                    // Layer-driven path: drive the renderer via the contract.
                    injected.showStep(ax, ay, target, index + 1, steps.size, instruction)
                    // POINTER FEW-CURSOR SPEC DELTAS (2026-11-05): the
                    // pill narrates the step — "Moving to <target>…",
                    // not just "Guiding you — Step N of M".
                    injected.setStatusNarration("Moving to ${step.targetQuery}…")
                    // Confirm AND perform (2026-11-10): a Shown claim
                    // is only honest when the pointer actually drew —
                    // a failed draw is Unavailable with the reason,
                    // never a Shown the user cannot see.
                    val drawFailure = injected.lastDrawFailure()
                    if (drawFailure != null) {
                        val r = StepResult.Unavailable(
                            "The pointer could not be shown — $drawFailure.",
                        )
                        listener?.onSessionEvent(r)
                        return r
                    }
                } else {
                    val view = PointerGuideViewHolder.view
                    if (view == null) {
                        // Accessibility fix (2026-11-05, audit §7.2): no
                        // internal state words to the user — "Guidance
                        // overlay is not attached" becomes plain words.
                        return StepResult.Unavailable("The pointer isn't on screen right now.")
                            .also { listener?.onSessionEvent(it) }
                    }
                    // Host path: the host applies its pointer-policy gate
                    // before drawing (LoomOverlayService — unchanged).
                    listener?.onStepShown(
                        view,
                        PointerGuideView.Step(
                            anchorX = ax,
                            anchorY = ay,
                            target = target,
                            stepIndex = index + 1,
                            stepCount = steps.size,
                            instruction = instruction,
                        ),
                    )
                }
                val shown = StepResult.Shown(index + 1, steps.size)
                listener?.onSessionEvent(shown)
                shown
            }
        }
    }

    /**
     * Phase 6: tap execution is WIRED through the AuthGate (arch §2.5 + §2.14).
     * The current step's target is resolved against the live accessibility
     * tree, the gate decides (ALLOW → dispatch now; CONFIRM → card shown,
     * dispatch on David's approval; DENY → refused), and the tap dispatches
     * only with a valid AuthToken. Nothing here can bypass the gate.
     *
     * CLICK = TAP (plain-language name for the same action): the pointer's
     * tap at the verified target through the AuthGate path
     * (preview-before-gate, dispatchGesture), with the visible ripple
     * ([GuidanceRendererContract.showTapRipple]) so David sees it land.
     */
    fun requestTap(): TapResult = executeTapLike(TapKind.TAP)

    /**
     * POINTER MOUSE-VERBS (2026-11-05): LONG-PRESS the current step's
     * target — the tap held down for [holdMsMs]. Same gate key as the
     * tap (`tap.guidance`), same verify-before-dispatch, dispatched
     * through [longPressDispatcher] (the AccessibilityService gesture
     * path: dispatchGesture with a hold). The renderer holds the press
     * state (press dot + ring) for the duration, then releases into the
     * tap ripple. No step requests a long-press today — the capability
     * is wired, the trigger is not invented.
     */
    fun requestLongPress(holdMsMs: Long = DEFAULT_LONG_PRESS_HOLD_MS): TapResult =
        executeTapLike(TapKind.LONG_PRESS, holdMsMs.coerceIn(MIN_LONG_PRESS_HOLD_MS, MAX_LONG_PRESS_HOLD_MS))

    /**
     * POINTER MOUSE-VERBS (2026-11-05): OPEN the current step's target
     * — tap-to-open an element (a link, a result, a row) through the
     * same gated tap path, narrated as "Opening …". Opening an APP by
     * name uses the existing `app.launch` sink (DeviceControlCenter),
     * not this path. Same gate key as the tap (`tap.guidance`), same
     * verify-before-dispatch, same visible ripple.
     */
    fun requestOpen(): TapResult = executeTapLike(TapKind.OPEN)

    private enum class TapKind { TAP, LONG_PRESS, OPEN }

    /** The live renderer (H6: re-resolved per step, never a stale one). */
    private fun liveRenderer(): GuidanceRendererContract? =
        rendererProvider?.invoke() ?: renderer

    /**
     * Stage 2D (Worker C) — PlanPointerGuide seam: re-resolve one
     * element by its tree id against a FRESH snapshot. Returns null
     * when the tree is unbound, no snapshot is available, or no
     * element with [id] is on screen — the caller treats that as
     * target-gone, never tracks stale coordinates. A single-snapshot
     * read: tracking only needs the element's CURRENT bounds; the
     * executor re-verifies (two snapshots) before any tap.
     */
    fun resolveElementById(id: String): ScreenElement? {
        if (!tree.isBound) return null
        val snap = try {
            tree.snapshot()
        } catch (_: Exception) {
            null
        } ?: return null
        // Screen fix (2026-11-11, CORRECTED): the seam promises "on
        // screen" — an off-screen element is target-gone here, never
        // tracked at coordinates the user cannot see. The plan guide
        // re-resolves (and scrolls) when it comes back as gone.
        return snap.visibleElements.firstOrNull { it.id == id }
    }

    /**
     * Stage 2D (Worker C) — PlanPointerGuide seam: the verified
     * bounds of a target named by [query] (same two-snapshot
     * verification as [resolveTarget]), or null when it cannot be
     * found — the caller draws nothing rather than a fake outline.
     */
    fun resolveTargetBounds(query: String): Rect? {
        return when (val res = resolveTarget(query)) {
            is TargetResolution.Found -> res.element.bounds
            is TargetResolution.Failed -> null
        }
    }

    /**
     * Stage 2D (Worker C) — PlanPointerGuide seam: the live renderer
     * (H6: re-resolved per call, never a stale one), so the plan
     * guide reuses the session's renderer instead of the host
     * wiring a second one.
     */
    fun currentRenderer(): GuidanceRendererContract? = liveRenderer()

    /**
     * MASTER Wave 4 (F11) — the screen size from the live tree
     * (the furthest element edges), or null when the tree is
     * unbound / unreadable. Never invented: a null size means the
     * neutral-home park is skipped, not guessed.
     */
    fun screenSize(): Pair<Int, Int>? {
        val snap = try {
            tree.snapshot()
        } catch (_: Exception) {
            null
        } ?: return null
        val w = snap.elements.maxOfOrNull { it.bounds.right } ?: return null
        val h = snap.elements.maxOfOrNull { it.bounds.bottom } ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    /**
     * POINTER FEW-CURSOR SPEC DELTAS (2026-11-05) + MOUSE-VERBS:
     * verify-before-dispatch — the ghost-cursor lesson. Immediately
     * before the token is spent, the target is re-resolved against the
     * live tree (two snapshots, stable bounds — [resolveTarget]) and
     * must still be the SAME element ([ScreenElement.id] + bounds
     * within tolerance of the previewed one). Then, when the renderer
     * CAN report a position, the owned cursor must really be over the
     * verified target bounds ([PointerMotion.isCursorOverTarget]) —
     * a cursor parked elsewhere refuses, never taps blind.
     *
     * A renderer that reports NO position (null) skips the cursor
     * check: HIDE policy swallows the draw, so there IS no cursor to
     * check — and HIDE must never disable automation (2B D3a). The
     * two-snapshot tree verification is the check there.
     */
    private sealed interface VerifyResult {
        data class Ok(val element: ScreenElement) : VerifyResult
        data class Failed(val reason: String) : VerifyResult
    }

    private fun verifyBeforeDispatch(
        previewed: ScreenElement,
        renderer: GuidanceRendererContract?,
    ): VerifyResult {
        val step = steps.getOrNull(index)
            ?: return VerifyResult.Failed("No active guidance step.")
        val fresh = when (val r = resolveTarget(step.targetQuery)) {
            is TargetResolution.Failed -> return VerifyResult.Failed(r.reason)
            is TargetResolution.Found -> r.element
        }
        if (fresh.id != previewed.id || !boundsStable(fresh.bounds, previewed.bounds)) {
            return VerifyResult.Failed("The target moved before the tap — nothing was tapped.")
        }
        val cursor = renderer?.cursorPosition()
        if (cursor != null) {
            val bounds = GeomRect(
                fresh.bounds.left.toFloat(), fresh.bounds.top.toFloat(),
                fresh.bounds.right.toFloat(), fresh.bounds.bottom.toFloat(),
            )
            if (!PointerMotion.isCursorOverTarget(cursor, bounds)) {
                return VerifyResult.Failed(
                    "The pointer is not over the target — nothing was tapped.",
                )
            }
        }
        return VerifyResult.Ok(fresh)
    }

    /**
     * Shared tap-like executor for TAP / LONG_PRESS / OPEN: resolve the
     * current step's target (two-snapshot verification), glide the
     * pointer to it BEFORE the gate request (preview-before-gate, so
     * David sees the intended action before it executes — including
     * while a confirmation card is on screen), request the
     * `tap.guidance` token, verify again immediately before dispatch,
     * then dispatch through the kind's sink and show the visible
     * feedback (ripple for tap/open, held press state for long-press).
     */
    private fun executeTapLike(kind: TapKind, holdMs: Long = 0L): TapResult {
        val gate = AuthGate.getOrNull()
            ?: return TapResult.GateRequired("AuthGate is not initialized yet.")
        val step = steps.getOrNull(index)
            ?: return TapResult.Unavailable("No active guidance step.")
        if (!tree.isBound) {
            return TapResult.Unavailable(
                "Screen reading is not enabled — turn on LOOM in " +
                    "Settings → Accessibility → Device Control to use guidance.",
            )
        }
        val previewed = when (val res = resolveTarget(step.targetQuery)) {
            is TargetResolution.Failed -> return TapResult.Unavailable(res.reason)
            is TargetResolution.Found -> res.element
        }
        if (kind == TapKind.LONG_PRESS && longPressDispatcher == null) {
            return TapResult.Unavailable("Long-press isn't available right now — the helper isn't running.")
        }
        if (kind != TapKind.LONG_PRESS && tapDispatcher == null) {
            return TapResult.Unavailable("Tapping isn't available right now — the helper isn't running.")
        }
        // Master implementation §6: move the pointer to the freshly
        // VERIFIED target BEFORE the gate request. The beam glides while
        // any confirmation card is on screen, so David sees the intended
        // tap before it executes; on immediate ALLOW the glide starts as
        // the tap dispatches. H6: the preview goes to the LIVE renderer.
        val renderer = liveRenderer()
        renderer?.previewMoveTo(RectF(previewed.bounds))
        val verb = when (kind) {
            TapKind.TAP -> "Tapping"
            TapKind.LONG_PRESS -> "Long-pressing"
            TapKind.OPEN -> "Opening"
        }
        renderer?.setStatusNarration("$verb ${step.targetQuery}…")
        val dispatch: (ScreenElement, String) -> Boolean = when (kind) {
            TapKind.LONG_PRESS -> { el, tok ->
                longPressDispatcher?.invoke(el, tok, holdMs) == true
            }
            else -> { el, tok -> tapDispatcher?.invoke(el, tok) == true }
        }
        val pastTense = when (kind) {
            TapKind.TAP -> "Tap"
            TapKind.LONG_PRESS -> "Long-press"
            TapKind.OPEN -> "Open"
        }
        var outcome: TapResult = TapResult.Unavailable("Gate did not resolve.")
        // MASTER F6a — scope the tap by foreground package AND pass
        // the real app label (never "" while the package is known),
        // with this session's origin: that is what makes "Always
        // allow" renderable and stored grants applicable on the
        // guidance path. Before this the label was blank, so no grant
        // was ever offered here even for David's own commands.
        val foregroundPkg = try {
            tree.snapshot()?.packageName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val action = AuthActionRegistry.tapGuidanceTarget(
            step.instruction, scope = foregroundPkg, appLabel = appLabelFor(foregroundPkg),
        )
        val decision = gate.request(action, origin = origin) { token ->
            outcome = if (token == null) {
                TapResult.Unavailable("$pastTense was not approved — nothing was tapped.")
            } else {
                when (val verified = verifyBeforeDispatch(previewed, renderer)) {
                    is VerifyResult.Failed ->
                        TapResult.Unavailable(verified.reason)
                    is VerifyResult.Ok -> {
                        val accepted = try {
                            dispatch(verified.element, token.id)
                        } catch (_: Exception) {
                            false
                        }
                        if (accepted) {
                            // Visible record that the action landed: the
                            // tap ripple for tap/open, the held press
                            // state (then ripple) for a long-press.
                            if (kind == TapKind.LONG_PRESS) {
                                renderer?.showPressState(holdMs)
                            } else {
                                renderer?.showTapRipple()
                            }
                            TapResult.Dispatched
                        } else {
                            TapResult.Unavailable("$pastTense was not accepted by the system.")
                        }
                    }
                }
            }
        }
        return when (decision) {
            is AuthGate.GateResult.Allow -> outcome
            is AuthGate.GateResult.NeedsConfirmation -> {
                // SPEED (David, 2026-11-05 18:58): never leave him staring
                // at a silent wait — the pill says what the step is
                // waiting on while the card is up.
                renderer?.setStatusNarration(
                    "Waiting for your approval — ${pastTense.lowercase()} \"${step.targetQuery}\"",
                )
                TapResult.AwaitingConfirmation(
                    "Confirmation is on screen — approve it to complete the " +
                        "${pastTense.lowercase()} of \"${step.targetQuery}\".",
                )
            }
            is AuthGate.GateResult.Deny ->
                TapResult.Unavailable("Denied: ${decision.reason}")
        }
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): SCROLL — pointer-directed
     * scrolling, the way a mouse scrolls the page under its cursor.
     * The pointer glides to the scroll target ([targetQuery], or the
     * live step's target when none is given), the pill narrates
     * ("Scrolling down…"), and the scroll dispatches through
     * [scrollDispatcher] (`device.scroll` — an EXISTING key, CONFIRM,
     * already covered by David's authorizations; nothing new invented).
     * Sensitive apps refuse (guidance-only); [scrollDispatcher] null
     * refuses honestly. No target is needed to scroll — without one the
     * scroll still dispatches, with narration but no pointer move.
     * [resolveFirst] false skips the resolve-then-scroll step: the
     * scroll hunt ([resolveTargetWithScroll]) scrolls precisely
     * because the target is NOT resolvable yet, so resolving first
     * would refuse every hunt scroll. The AuthGate request below
     * still runs — skipping resolution never skips the gate.
     */
    fun requestScroll(forward: Boolean, targetQuery: String? = null, resolveFirst: Boolean = true): TapResult {
        val gate = AuthGate.getOrNull()
            ?: return TapResult.GateRequired("AuthGate is not initialized yet.")
        if (!tree.isBound) {
            return TapResult.Unavailable(
                "Screen reading is not enabled — turn on LOOM in " +
                    "Settings → Accessibility → Device Control to use guidance.",
            )
        }
        val pkg = try {
            tree.snapshot()?.packageName
        } catch (_: Exception) {
            null
        }
        if (pkg != null && ScreenDataPolicy.isSensitivePackage(pkg)) {
            return TapResult.Unavailable(
                "Guidance only in this app — scrolling is disabled in " +
                    "banking and finance apps.",
            )
        }
        val dispatcher = scrollDispatcher
            ?: return TapResult.Unavailable("Scrolling isn't available right now — the helper isn't running.")
        val direction = if (forward) "down" else "up"
        val query = if (resolveFirst) (targetQuery ?: steps.getOrNull(index)?.targetQuery) else targetQuery
        val renderer = liveRenderer()
        if (query != null) {
            when (val res = resolveTarget(query)) {
                is TargetResolution.Failed ->
                    return TapResult.Unavailable(res.reason)
                is TargetResolution.Found -> {
                    if (res.redacted) {
                        return TapResult.Unavailable(
                            "Guidance only in this app — scrolling is disabled " +
                                "in banking and finance apps.",
                        )
                    }
                    if (targetQuery != null) {
                        // New scroll target: show it so the pointer
                        // gathers on it before scrolling.
                        val (ax, ay) = anchorProvider()
                        renderer?.showStep(
                            ax, ay, RectF(res.element.bounds),
                            if (isActive) index + 1 else 1,
                            if (isActive) steps.size else 1,
                            "Scroll $direction here",
                        )
                    }
                    renderer?.previewMoveTo(RectF(res.element.bounds))
                }
            }
        }
        renderer?.setStatusNarration("Scrolling $direction…")
        var outcome: TapResult = TapResult.Unavailable("Gate did not resolve.")
        // MASTER F6a — scope the scroll by foreground package AND
        // pass the real app label (never "" while the package is
        // known), with this session's origin — same grant rule as
        // the tap above.
        val scrollAction = if (pkg.isNullOrBlank()) AuthActionRegistry.DEVICE_SCROLL
        else AuthActionRegistry.deviceScroll(pkg, appLabelFor(pkg))
        val decision = gate.request(scrollAction, origin = origin) { token ->
            outcome = if (token == null) {
                TapResult.Unavailable("Scroll was not approved — nothing scrolled.")
            } else {
                val accepted = try {
                    dispatcher(forward, token.id)
                } catch (_: Exception) {
                    false
                }
                if (accepted) TapResult.Dispatched
                else TapResult.Unavailable("Scroll was not accepted by the system.")
            }
        }
        return when (decision) {
            is AuthGate.GateResult.Allow -> outcome
            is AuthGate.GateResult.NeedsConfirmation -> {
                // SPEED (David, 2026-11-05 18:58): the pill says what the
                // step is waiting on while the card is up — never a
                // silent wait.
                renderer?.setStatusNarration("Waiting for your approval — scroll $direction")
                TapResult.AwaitingConfirmation(
                    "Confirmation is on screen — approve it to scroll $direction.",
                )
            }
            is AuthGate.GateResult.Deny ->
                TapResult.Unavailable("Denied: ${decision.reason}")
        }
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): SELECT text — "select this
     * paragraph". Resolves the target, refuses in sensitive apps and
     * on password fields (never selected, no exceptions), verifies the
     * selection is real ([PointerSelection.selectAll] — one element,
     * one user-directed action, no bulk selection), glides the pointer
     * to it and narrates — then refuses HONESTLY at the gate:
     *
     * NO AuthGate key exists for text selection yet. The platform path
     * (long-press / ACTION_SET_SELECTION in LoomAccessibilityService)
     * is built but stays UNWIRED until David approves the proposed
     * `select.text` key (CONFIRM, never grant-eligible — selection
     * feeds the clipboard, so every select cards, like message.send).
     * Nothing is selected and nothing reaches the clipboard here.
     */
    fun requestSelect(targetQuery: String? = null): TapResult {
        val query = targetQuery ?: steps.getOrNull(index)?.targetQuery
            ?: return TapResult.Unavailable("No active guidance step — say what to select.")
        if (!tree.isBound) {
            return TapResult.Unavailable(
                "Screen reading is not enabled — turn on LOOM in " +
                    "Settings → Accessibility → Device Control to use guidance.",
            )
        }
        val element = when (val res = resolveTarget(query)) {
            is TargetResolution.Failed -> return TapResult.Unavailable(res.reason)
            is TargetResolution.Found -> {
                if (res.redacted) {
                    return TapResult.Unavailable(
                        "Guidance only in this app — text is never selected " +
                            "in banking and finance apps.",
                    )
                }
                res.element
            }
        }
        if (element.isPassword) {
            return TapResult.Unavailable("Password fields are never selected.")
        }
        PointerSelection.selectAll(element)
            ?: return TapResult.Unavailable(
                "Nothing selectable here — this element has no text to select.",
            )
        val renderer = liveRenderer()
        renderer?.previewMoveTo(RectF(element.bounds))
        renderer?.setStatusNarration("Selecting $query…")
        return TapResult.GateRequired(
            "Text selection needs your explicit authorization — no AuthGate " +
                "key exists for it yet (proposed: $PROPOSED_SELECT_KEY). " +
                "Nothing was selected.",
        )
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): COPY the current selection —
     * copies exactly [lastSelection] and nothing else. Refuses when
     * nothing is selected, when the selected element left the screen,
     * in sensitive apps, and on password fields. Then refuses HONESTLY
     * at the gate, for the same reason as [requestSelect]: no AuthGate
     * key exists for copying yet (proposed `copy.selection`, CONFIRM,
     * never grant-eligible — copy touches the clipboard, which can hold
     * passwords, so every copy cards). This method NEVER reads the
     * clipboard — it only ever writes the exact user-directed selection.
     */
    fun requestCopy(): TapResult {
        val selection = lastSelection
            ?: return TapResult.Unavailable("Nothing selected yet — select text first, then copy.")
        if (!tree.isBound) {
            return TapResult.Unavailable(
                "Screen reading is not enabled — turn on LOOM in " +
                    "Settings → Accessibility → Device Control to use guidance.",
            )
        }
        val snap = try {
            tree.snapshot()
        } catch (_: Exception) {
            null
        }
        if (snap != null && ScreenDataPolicy.isSensitivePackage(snap.packageName)) {
            return TapResult.Unavailable(
                "Guidance only in this app — nothing is copied in " +
                    "banking and finance apps.",
            )
        }
        val element = snap?.elements?.firstOrNull { it.id == selection.elementId }
            ?: return TapResult.Unavailable(
                "The selected text is no longer on screen — nothing was copied.",
            )
        if (element.isPassword) {
            return TapResult.Unavailable("Password fields are never copied.")
        }
        val renderer = liveRenderer()
        renderer?.previewMoveTo(RectF(element.bounds))
        renderer?.setStatusNarration("Copying selection…")
        return TapResult.GateRequired(
            "Copying needs your explicit authorization — no AuthGate key " +
                "exists for it yet (proposed: $PROPOSED_COPY_KEY). " +
                "Nothing was copied.",
        )
    }

    companion object {
        /** B2: per-edge movement tolerance for the stability check, in pixels. */
        const val STABLE_TOLERANCE_PX = 4

        /** Default long-press hold: comfortably past Android's ~500ms threshold. */
        const val DEFAULT_LONG_PRESS_HOLD_MS = 700L

        /** Shortest hold that still reads as a long-press, not a tap. */
        const val MIN_LONG_PRESS_HOLD_MS = 300L

        /** Longest hold the session will request (clamps runaway holds). */
        const val MAX_LONG_PRESS_HOLD_MS = 5000L

        /**
         * PROPOSED AuthGate keys for select/copy — NOT created, NOT wired.
         * David has not authorized text selection or clipboard copy yet;
         * creating them would invent grant-eligibility. Proposed risk
         * class for both: CONFIRM, never grant-eligible (copy touches
         * the clipboard, which can hold passwords — every copy cards,
         * like message.send). Named here so the refusal messages and
         * the spec name the exact keys awaiting his say-so.
         */
        const val PROPOSED_SELECT_KEY = "select.text"
        const val PROPOSED_COPY_KEY = "copy.selection"
    }
}

/** The overlay host registers its [PointerGuideView] here when attached. */
object PointerGuideViewHolder {
    @Volatile
    var view: PointerGuideView? = null
}
