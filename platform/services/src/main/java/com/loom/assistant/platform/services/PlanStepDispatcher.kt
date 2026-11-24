package com.loom.assistant.platform.services

import com.loom.assistant.core.common.PlanStopRegistry
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.interaction.FinancialActionDetector
import com.loom.assistant.execution.messaging.CallOutcome
import com.loom.assistant.execution.messaging.MessageOutcome
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.LiveTapResolution
import com.loom.assistant.execution.screenawareness.LiveTapResolutionChecker
import com.loom.assistant.core.common.MediaControl
import com.loom.assistant.execution.screenawareness.MediaControlRouter
import com.loom.assistant.execution.screenawareness.NavigationResolver
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.VideoPresence
import com.loom.assistant.execution.screenawareness.firstResultRow
import com.loom.assistant.execution.screenawareness.isBareFirstTarget
import com.loom.assistant.execution.search.SearchOutcome
import com.loom.assistant.execution.tasks.expectedOnScreen
import com.loom.assistant.execution.tasks.findTargetElements
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.PlanAuthCover
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.ScreenKnowledgeStore
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.StepDispatcher
import com.loom.assistant.intelligence.planner.TaskStepKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stage 2D (Worker B) — the plan step dispatcher.
 *
 * Implements [StepDispatcher] using ONLY existing machinery. Every step
 * hands off to a sink that already exists; nothing here calls
 * dispatchGesture, and no new AuthGate key is introduced. The
 * TaskStepKind → hand-off table (the PlanStepKind mapping Worker A's
 * TaskPlan.kt KDoc names, completed here):
 *
 * - LAUNCH_APP → `app.launch` via LoomAppLauncher (origin passed
 *   through); no PlanStepKind equivalent.
 * - WAIT_FOR_APP → poll [ScreenObserver] until the foreground package
 *   matches; no PlanStepKind equivalent.
 * - OBSERVE_SCREEN → no-op success (observation is the executor's job).
 * - SEARCH_IN_APP → SearchExecutor.execute(app, query, origin); the
 *   sequence does its own affordance → TYPE → SUBMIT → select →
 *   verify internally, so no single PlanStepKind equivalent.
 * - TAP_TARGET → PlanStepKind.TAP on the re-resolved target, via the
 *   accessibility tap sink with a token from [PlanTokenProvider]
 *   (sequenced ALLOW when the plan cover spans the step, the
 *   out-of-scope CONFIRM card otherwise).
 * - TYPE_TEXT → PlanStepKind.TYPE with params["text"] — only after the
 *   fresh screen proves a field has focus.
 * - SUBMIT → PlanStepKind.SUBMIT (device.submit: IME Enter).
 * - SCROLL / GO_BACK → device.scroll / device.back.
 * - VERIFY_OUTCOME → read-only check of the fresh screen against the
 *   step's EXPECTED param (text present / playback indicator);
 *   DISPATCHED only on match, FAILED otherwise.
 * - GUIDE_TO → GuidanceSession.start(query) (display-only).
 * - MESSAGE_SEND / CALL_PLACE → MessageExecutor / CallExecutor with
 *   the exact dictated words; a FRESH message.send / call.place token
 *   per send/call — NEVER covered by the plan cover, always their own
 *   confirmation (the executors own that token flow).
 * - POINTER_MOVE → display-only pointer nudge (no dispatch).
 * - REPORT → no-op success.
 *
 * Sensitive-app pre-check before ANY dispatch: when the current
 * foreground package is sensitive, action steps return REFUSED
 * (guidance-only steps — OBSERVE_SCREEN, VERIFY_OUTCOME, GUIDE_TO,
 * REPORT, POINTER_MOVE — still run). Financial / security targets are
 * refused outright via [FinancialActionDetector], before any token is
 * requested, exactly like the ActionQueue's pre-scan.
 *
 * A DISPATCHED result means "the sink accepted the step" — the
 * executor still verifies against a fresh snapshot afterwards.
 * Everything here is pure Kotlin + injected seams, so this file
 * compiles on the JVM with fakes (no Android framework in it).
 */

/** Registry action keys, mirrored from AuthActionRegistry — keep in sync. */
object PlanActionKeys {
    const val APP_LAUNCH = "app.launch"
    const val TAP_GUIDANCE = "tap.guidance"
    const val DEVICE_TYPE = "device.type"
    const val DEVICE_SUBMIT = "device.submit"
    const val DEVICE_SCROLL = "device.scroll"
    const val DEVICE_BACK = "device.back"
}

/** One gesture acceptance from a [PlanGestures] sink. */
sealed class PlanGestureResult {
    data object Accepted : PlanGestureResult()
    data class Refused(val reason: String) : PlanGestureResult()
}

/** The gesture sinks. Production: LoomAccessibilityService extensions. */
interface PlanGestures {
    fun tap(element: ScreenElement, token: String?): PlanGestureResult
    fun type(text: String, token: String?): PlanGestureResult
    fun submit(token: String?): PlanGestureResult

    /**
     * App-aware execution (2026-11-07, David's directive §1) — submit
     * mechanism 2: tap the keyboard's own Search / Go / Done key.
     * Default refuses honestly so existing fakes keep compiling; the
     * dispatcher treats Refused as "try the next mechanism", never as
     * a step failure.
     */
    fun submitViaKey(token: String?): PlanGestureResult =
        PlanGestureResult.Refused("Keyboard action key not available.")

    /**
     * Universal Console (2026-11-07) — long-press the element (a
     * tap held down). Same `tap.guidance` authorization as a tap:
     * a long-press IS a tap held down, not a new authorization
     * class (the service sink already runs under the tap keys).
     * Default refuses honestly so existing fakes keep compiling.
     */
    fun longPress(element: ScreenElement, token: String?): PlanGestureResult =
        PlanGestureResult.Refused("Long-press not available.")

    /**
     * Universal Console (2026-11-07) — swipe the screen in
     * [direction] ("up" | "down" | "left" | "right"). Same
     * `device.scroll` authorization as scrolling: a swipe IS the
     * gesture form of scrolling (the scroll sink already falls back
     * to a swipe when no scrollable node acts). Default refuses
     * honestly so existing fakes keep compiling.
     */
    fun swipe(direction: String, token: String?): PlanGestureResult =
        PlanGestureResult.Refused("Swipe not available.")

    fun scroll(forward: Boolean, token: String?): PlanGestureResult
    fun back(token: String?): PlanGestureResult
}

/**
 * What the dispatcher asks the gate for. [sequenced] means a live plan
 * cover spans this step: the production provider builds the ALLOW
 * sequenced action (the ActionQueue precedent). [outOfScope] means the
 * step targets another app than the covered one: the production
 * provider builds the out-of-scope CONFIRM card naming both apps.
 * Neither flag ever invents a key — both map to registry factories.
 */
data class PlanTokenRequest(
    val key: String,
    val label: String,
    val scope: String = "",
    val appLabel: String = "",
    val sequenced: Boolean = false,
    val outOfScope: Boolean = false,
    val outOfScopeApp: String = "",
    val outOfScopeOtherApp: String = "",
)

/** Blocking token acquisition. Null = declined, timed out, or no gate. */
fun interface PlanTokenProvider {
    fun tokenFor(request: PlanTokenRequest): String?
}

class PlanStepDispatcher(
    private val origin: RequestOrigin,
    private val cover: PlanAuthCover?,
    private val observer: ScreenObserver,
    private val apps: AppResolver,
    private val tokens: PlanTokenProvider,
    private val gestures: PlanGestures,
    private val hasInputFocus: () -> Boolean,
    /** LoomAppLauncher.launch shape: (appName, origin, onResult) -> Unit. */
    private val launchApp: (appName: String, origin: RequestOrigin, onResult: (String) -> Unit) -> Unit,
    /**
     * AOIE order (a) — cover-aware launch: (appName, sequenced token,
     * onResult) -> Unit. When a live plan cover spans the LAUNCH_APP
     * step, the dispatcher requests the sequenced app.launch ALLOW
     * token itself and launches with it, instead of routing through
     * [launchApp] (which would request its own CONFIRM card). Null =
     * no cover-aware path (existing callers, fakes): a covered
     * launch falls back to [launchApp] and its card.
     */
    private val launchAppWithToken: ((appName: String, token: String, onResult: (String) -> Unit) -> Unit)? = null,
    private val searchInApp: (appName: String, query: String, origin: RequestOrigin) -> SearchOutcome,
    private val sendMessage: (channel: String, recipient: String, text: String, origin: RequestOrigin) -> MessageOutcome,
    private val placeCall: (target: String, origin: RequestOrigin) -> CallOutcome,
    /** GuidanceSession.start(query) shape: true when the target was shown. */
    private val guideTo: (target: String) -> Boolean,
    /** Pointer nudge by direction ("left"/"right"/"up"/"down"); null = no pointer. */
    private val nudgePointer: ((direction: String) -> Boolean)? = null,
    /** True when the live plan cover spans [step]; default reads [cover]. */
    private val isStepCovered: (PlanStep) -> Boolean = { step ->
        try {
            cover?.checkCover(step) == true
        } catch (_: Exception) {
            false
        }
    },
    /**
     * (covered app label, other app label) when [step] targets an app
     * outside the plan cover; null when in-scope or unknown. Default null.
     */
    private val outOfScopeInfo: (PlanStep) -> Pair<String, String>? = { null },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /**
     * App-aware execution (2026-11-07): the search hand-off that
     * accepts a per-call submit order (the executor's TRY_ALTERNATIVE
     * retry). Null = the plain [searchInApp] hand-off (existing
     * callers, fakes). When set and the step carries a
     * [PlanParams.SUBMIT_ORDER], dispatchSearch uses this; otherwise
     * it falls back to [searchInApp].
     */
    private val searchInAppWithOrder: ((appName: String, query: String, origin: RequestOrigin, order: List<SubmitMechanism>?) -> SearchOutcome)? = null,
    /**
     * Universal Console (2026-11-07): the search hand-off that
     * accepts the step's goal (SHOW_RESULTS vs OPEN_FIRST_RESULT)
     * AND the per-call submit order, in one call — the order-aware
     * and goal-aware hand-offs combined, so a TRY_ALTERNATIVE retry
     * of a plain search still stops at the results list instead of
     * opening a result. Null = fall back to [searchInAppWithOrder]
     * / [searchInApp] (existing callers, fakes). Production
     * (PlanWiring) always sets this.
     */
    private val searchInAppFull: ((appName: String, query: String, origin: RequestOrigin, goal: com.loom.assistant.execution.search.SearchGoal, order: List<SubmitMechanism>?) -> SearchOutcome)? = null,
    /**
     * MASTER F7a — stop signal for this dispatch. Default reads the
     * shared [PlanStopRegistry] (voice "stop", notification stop,
     * Stop-window stop, shutdown all set it); tests inject a flag.
     * Every dispatch checks it before requesting a token, after a
     * token wait returns, and before interpreting a launch report —
     * so a stop that lands during a parked 90 s card prevents the
     * step from executing even if David then approves.
     */
    private val isStopped: () -> Boolean = { PlanStopRegistry.isStopped() },
    /**
     * Mark 57 transfer (2026-11-11, M6) — the shared, disk-backed
     * screen knowledge, consulted FIRST for a nav destination's
     * preferred tab. Null = no knowledge (existing callers, fakes):
     * the resolver's normal tiers run instead. Production
     * (PlanWiring) always sets this, to the same store the planner
     * and executor share.
     */
    private val knowledge: ScreenKnowledgeStore? = null,
) : StepDispatcher {

    companion object {
        /** Foreground wait for WAIT_FOR_APP (matches the search sequence). */
        const val FOREGROUND_WAIT_MS = 10_000L
        const val POLL_INTERVAL_MS = 250L

        /**
         * Simple-task execution fix (2026-11-07): consecutive
         * foreground polls with an empty tree that still count as
         * "the app is up" — 2 polls × 250 ms = 500 ms of stable
         * foreground.
         */
        const val STABLE_FOREGROUND_POLLS = 2

        /** The launch card can park up to 90 s; wait a little past it. */
        const val LAUNCH_WAIT_MS = 95_000L

        /**
         * MASTER F7a — the token wait bound. A blocking token
         * provider parks up to 90 s (PlanBlockingGateTokenProvider);
         * the stop-aware token wait gives up a little past that, so
         * a provider that never resolves cannot park a step forever.
         */
        const val TOKEN_WAIT_MS = 95_000L

        /**
         * MASTER F7a — the stop-poll slice. Latch and token waits
         * are polled in slices of this size so a stop interrupts
         * them within ~100 ms instead of at the 90 s deadline.
         */
        const val POLL_SLICE_MS = 100L

        /** Guidance-only kinds: allowed inside sensitive apps (read-only). */
        val GUIDANCE_ONLY_KINDS: Set<TaskStepKind> = setOf(
            TaskStepKind.OBSERVE_SCREEN,
            TaskStepKind.VERIFY_OUTCOME,
            TaskStepKind.GUIDE_TO,
            TaskStepKind.REPORT,
            TaskStepKind.POINTER_MOVE,
        )
    }

    override fun dispatch(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        // MASTER F7a — a stop is checked before anything else: a
        // stopped step never requests a token or dispatches, whatever
        // the step kind or the foreground app.
        if (isStopped()) {
            return StepDispatchResult.FAILED("Stopped.")
        }
        // Sensitive pre-check BEFORE any token request or dispatch.
        if (ScreenDataPolicy.isSensitivePackage(screen.packageName) &&
            step.kind !in GUIDANCE_ONLY_KINDS
        ) {
            return StepDispatchResult.REFUSED(
                "Guidance only in this app — banking, financial, and " +
                    "authenticator apps are never automated.",
            )
        }
        // Session/state awareness (2026-11-11): never re-launch an
        // app that is already in the foreground — the launch is
        // already done, and launching again restarts the task. The
        // step succeeds as already-there; the plan continues on the
        // current screen. (The launcher has the same short-circuit
        // at the service level — MASTER F5 "Already in" — this is
        // the dispatch-level form of the same rule, so no token is
        // spent and the launcher is never invoked for it.)
        // The skip applies ONLY to the plain launch path: a
        // cover-spanned launch still runs through dispatchLaunch,
        // because the sequenced token is the authorization proof
        // the AOIE cover accounts for (AioeCoverSpanningTest §5
        // pins one token per covered launch) — and the launcher's
        // own "Already in" short-circuit still prevents any actual
        // re-launch inside launchAppWithToken.
        if (step.kind == TaskStepKind.LAUNCH_APP &&
            !(launchAppWithToken != null && isStepCovered(step))
        ) {
            val appName = step.params[PlanParams.APP]
                ?: step.params[PlanParams.PACKAGE].orEmpty()
            val pkg = (apps.resolve(appName) as? AppResolution.Found)?.packageName
            if (pkg != null && pkg == screen.packageName) {
                return StepDispatchResult.DISPATCHED
            }
        }
        // MASTER F7a — a stop that abandons a token wait mid-step
        // surfaces here as FAILED("Stopped."), never as a decline.
        return try {
            when (step.kind) {
                TaskStepKind.LAUNCH_APP -> dispatchLaunch(step)
                TaskStepKind.WAIT_FOR_APP -> dispatchWaitForApp(step)
                TaskStepKind.OBSERVE_SCREEN -> StepDispatchResult.DISPATCHED
                TaskStepKind.SEARCH_IN_APP -> dispatchSearch(step)
                TaskStepKind.TAP_TARGET -> dispatchTap(step, screen)
                TaskStepKind.NAVIGATE_TAB -> dispatchNavigateTab(step, screen)
                TaskStepKind.LONG_PRESS -> dispatchLongPress(step, screen)
                TaskStepKind.SWIPE -> dispatchSwipe(step)
                TaskStepKind.FOCUS_FIELD -> dispatchFocusField(step, screen)
                TaskStepKind.OPEN_MENU -> dispatchOpenMenu(step, screen)
                TaskStepKind.TYPE_TEXT -> dispatchType(step)
                TaskStepKind.SUBMIT -> dispatchSubmit(step)
                TaskStepKind.SCROLL -> dispatchScroll(step)
                TaskStepKind.GO_BACK -> dispatchBack(step)
                TaskStepKind.VERIFY_OUTCOME -> dispatchVerify(step, screen)
                TaskStepKind.GUIDE_TO -> dispatchGuide(step)
                TaskStepKind.MESSAGE_SEND -> dispatchMessage(step)
                TaskStepKind.CALL_PLACE -> dispatchCall(step)
                TaskStepKind.POINTER_MOVE -> dispatchPointerMove(step)
                TaskStepKind.PLAY_PAUSE -> dispatchPlayPause(step, screen)
                TaskStepKind.REPORT -> StepDispatchResult.DISPATCHED
            }
        } catch (_: StoppedException) {
            StepDispatchResult.FAILED("Stopped.")
        }
    }

    // ---------- launch / wait ----------

    /**
     * AOIE order (a) — the token request for an ordinary step,
     * honouring the live plan cover exactly as dispatchTap already
     * did: covered → the sequenced ALLOW variant of the same key
     * (no card); out-of-scope → the out-of-scope CONFIRM card
     * naming both apps; otherwise the plain request (which the
     * gate turns into a card, or skips on a stored DIRECT_USER
     * scoped grant). MESSAGE_SEND / CALL_PLACE never reach here —
     * checkCover excludes them and their dispatchers own their
     * fresh tokens.
     */
    private fun coveredTokenRequest(
        key: String,
        label: String,
        step: PlanStep,
    ): PlanTokenRequest {
        val scope = step.params[PlanParams.PACKAGE].orEmpty()
        val covered = isStepCovered(step)
        val outOfScope = outOfScopeInfo(step)
        return when {
            covered -> PlanTokenRequest(
                key = key, label = label, scope = scope, sequenced = true,
            )
            outOfScope != null -> PlanTokenRequest(
                key = key, label = label, scope = scope,
                outOfScope = true,
                outOfScopeApp = outOfScope.first,
                outOfScopeOtherApp = outOfScope.second,
            )
            else -> PlanTokenRequest(key = key, label = label, scope = scope)
        }
    }

    private fun dispatchLaunch(step: PlanStep): StepDispatchResult {
        val appName = step.params[PlanParams.APP]
            ?: step.params[PlanParams.PACKAGE].orEmpty()
        if (appName.isBlank()) {
            return StepDispatchResult.FAILED("No app named to open.")
        }
        // AOIE order (a): a covered launch uses the sequenced
        // app.launch ALLOW token (no card) instead of the
        // launcher's own CONFIRM request.
        if (launchAppWithToken != null && isStepCovered(step)) {
            val token = safeToken(
                coveredTokenRequest(
                    PlanActionKeys.APP_LAUNCH, "Open $appName", step,
                ),
            ) ?: return StepDispatchResult.REFUSED(
                "Not confirmed — I didn't open $appName.",
            )
            val latch = CountDownLatch(1)
            var report: String? = null
            try {
                launchAppWithToken.invoke(appName, token) { result ->
                    report = result
                    latch.countDown()
                }
            } catch (e: Exception) {
                return StepDispatchResult.FAILED("Could not open $appName: ${e.message}")
            }
            val answered = awaitLatch(latch, LAUNCH_WAIT_MS)
            if (!answered) {
                return if (isStopped()) {
                    // MASTER F7a — a stop during the card wait
                    // prevents the step even if David then approves:
                    // the launch report (if any) is never interpreted.
                    StepDispatchResult.FAILED("Stopped.")
                } else {
                    StepDispatchResult.FAILED("Opening $appName timed out waiting for confirmation.")
                }
            }
            if (isStopped()) {
                // MASTER F7a — the approval landed as the stop did:
                // abandon, do not interpret the report.
                return StepDispatchResult.FAILED("Stopped.")
            }
            val text = report.orEmpty()
            return if (text.startsWith("Opened ") || text.startsWith("Already in ")) {
                // MASTER F5 — the foreground short-circuit reports
                // "Already in $label."; that is a success, not a
                // failure to open.
                StepDispatchResult.DISPATCHED
            } else {
                StepDispatchResult.FAILED(text.ifBlank { "Could not open $appName." })
            }
        }
        // LoomAppLauncher is callback-based; block for its report (the
        // caller runs on a background thread — cards block up to 90 s).
        val latch = CountDownLatch(1)
        var report: String? = null
        try {
            launchApp(appName, origin) { result ->
                report = result
                latch.countDown()
            }
        } catch (e: Exception) {
            return StepDispatchResult.FAILED("Could not open $appName: ${e.message}")
        }
        val answered = awaitLatch(latch, LAUNCH_WAIT_MS)
        if (!answered) {
            return if (isStopped()) {
                // MASTER F7a — see the covered branch above.
                StepDispatchResult.FAILED("Stopped.")
            } else {
                StepDispatchResult.FAILED("Opening $appName timed out waiting for confirmation.")
            }
        }
        if (isStopped()) {
            // MASTER F7a — the approval landed as the stop did.
            return StepDispatchResult.FAILED("Stopped.")
        }
        val text = report.orEmpty()
        return if (text.startsWith("Opened ") || text.startsWith("Already in ")) {
            // MASTER F5 — the foreground short-circuit reports
            // "Already in $label."; that is a success.
            StepDispatchResult.DISPATCHED
        } else {
            StepDispatchResult.FAILED(text.ifBlank { "Could not open $appName." })
        }
    }

    /**
     * Simple-task execution fix (2026-11-07) — WAIT_FOR_APP was the
     * first stall point in David's failure: it demanded the
     * foreground package AND a non-empty tree within 10 s, so a slow
     * cold start or a lagging snapshot failed the step even though
     * the app was up (LAUNCH_APP had already verified the same
     * package foreground). The wait now passes on a STABLE
     * foreground: non-empty tree passes at once; an empty tree
     * passes when the package has held the foreground across two
     * consecutive polls (500 ms apart) — the app is up, only its
     * tree is lagging. A package that never comes foreground still
     * fails honestly at the deadline.
     */
    private fun dispatchWaitForApp(step: PlanStep): StepDispatchResult {
        val pkg = step.params[PlanParams.PACKAGE]
            ?: resolvePackage(step.params[PlanParams.APP].orEmpty())
        if (pkg.isNullOrBlank()) {
            return StepDispatchResult.FAILED("No app named to wait for.")
        }
        val label = step.params[PlanParams.APP] ?: pkg
        // AOIE Phase 1: a per-step timeout, when the plan sets one
        // (clamped 1 s–120 s so a model plan can never wait forever
        // or zero); otherwise the 10 s default.
        val waitMs = step.params[PlanParams.TIMEOUT_MS]?.toLongOrNull()
            ?.coerceIn(1_000L, 120_000L) ?: FOREGROUND_WAIT_MS
        val deadline = System.currentTimeMillis() + waitMs
        var foregroundPolls = 0
        while (true) {
            // MASTER F7a — a stop interrupts the foreground wait
            // instead of polling to the deadline.
            if (isStopped()) {
                return StepDispatchResult.FAILED("Stopped.")
            }
            val snap = safeObserve()
            if (snap != null && snap.packageName == pkg) {
                if (snap.elements.isNotEmpty()) {
                    return StepDispatchResult.DISPATCHED
                }
                foregroundPolls++
                if (foregroundPolls >= STABLE_FOREGROUND_POLLS) {
                    return StepDispatchResult.DISPATCHED
                }
            } else {
                foregroundPolls = 0
            }
            if (System.currentTimeMillis() >= deadline) {
                return StepDispatchResult.FAILED("$label did not come to the foreground.")
            }
            sleeper(POLL_INTERVAL_MS)
        }
    }

    // ---------- search ----------

    private fun dispatchSearch(step: PlanStep): StepDispatchResult {
        val app = step.params[PlanParams.APP].orEmpty()
        val query = step.params[PlanParams.QUERY].orEmpty()
        if (app.isBlank()) return StepDispatchResult.FAILED("No app named to search in.")
        if (query.isBlank()) return StepDispatchResult.FAILED("Nothing to search for.")
        // App-aware execution (2026-11-07): the executor's
        // TRY_ALTERNATIVE retry passes the remaining submit order in
        // the step params; unknown names are ignored, an empty order
        // falls back to the plain hand-off.
        val order = step.params[PlanParams.SUBMIT_ORDER]
            ?.split(",")
            ?.mapNotNull { name -> runCatching { SubmitMechanism.valueOf(name.trim()) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        // Universal Console (2026-11-07): the step's goal decides
        // whether the search stops at the results list or opens the
        // first result. A goal-aware hand-off is required for
        // SHOW_RESULTS: the plain hand-off ALWAYS opens the first
        // result, so routing a plain search through it would open a
        // video David never asked to open — fail honestly instead.
        val goalRaw = step.params[PlanParams.SEARCH_GOAL].orEmpty()
        val goal = when (goalRaw.lowercase().trim()) {
            "", PlanParams.SEARCH_GOAL_OPEN_FIRST ->
                com.loom.assistant.execution.search.SearchGoal.OPEN_FIRST_RESULT
            PlanParams.SEARCH_GOAL_SHOW_RESULTS ->
                com.loom.assistant.execution.search.SearchGoal.SHOW_RESULTS
            else -> return StepDispatchResult.FAILED("Unknown search goal: \"$goalRaw\".")
        }
        val outcome = when {
            searchInAppFull != null -> safeSearchFull(app, query, goal, order)
            order != null && searchInAppWithOrder != null -> {
                if (goal == com.loom.assistant.execution.search.SearchGoal.SHOW_RESULTS) {
                    return StepDispatchResult.FAILED(
                        "This search should stop at the results list, but the search wiring " +
                            "cannot stop there — it would open the first result instead.",
                    )
                }
                safeSearchWithOrder(app, query, order)
            }
            goal == com.loom.assistant.execution.search.SearchGoal.SHOW_RESULTS -> {
                return StepDispatchResult.FAILED(
                    "This search should stop at the results list, but the search wiring " +
                        "cannot stop there — it would open the first result instead.",
                )
            }
            else -> safeSearch(app, query)
        }
        return when (outcome) {
            is SearchOutcome.Success -> StepDispatchResult.DISPATCHED
            is SearchOutcome.Failed -> StepDispatchResult.FAILED(outcome.reason)
            SearchOutcome.AppNotFound ->
                StepDispatchResult.FAILED("I couldn't find an app called '$app' on this phone.")
            is SearchOutcome.Ambiguous ->
                StepDispatchResult.FAILED("Which one — ${outcome.candidates.joinToString(", ")}?")
        }
    }

    // ---------- tap / type / submit / scroll / back ----------

    private fun dispatchTap(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        val target = step.params[PlanParams.TARGET].orEmpty()
        if (target.isBlank()) {
            return StepDispatchResult.FAILED("No target named to tap.")
        }
        // Financial pre-scan (ActionQueue §4.3 parity): refused before
        // any token is requested, never carded, never dispatched.
        if (FinancialActionDetector.matches(step.description, target)) {
            return StepDispatchResult.REFUSED(
                "That looks like a financial or security-sensitive action, " +
                    "so it is never tapped.",
            )
        }
        // TOCTOU re-resolution against the fresh screen the executor
        // passed in (the executor already resolved + moved the pointer;
        // this is the second resolution, immediately before dispatch).
        // MASTER Wave 3 (F9) — MATCH=first is a USER-DIRECTED first
        // selector, not a blind pick: the planner emits it only when
        // the user explicitly said "first" ("the first result",
        // "first one"); a bare tap carries no MATCH, so several
        // matches refuse honestly ("N matches — be more specific")
        // instead of tapping the first one. The voice-search mic was
        // tapped exactly by blind-picking the first match; result
        // selection after verified results is the search sequence's
        // job, never a blind TAP_TARGET.
        // MASTER Wave 4 (Wave 3 residual) — a BARE first-result
        // target ("tap the first result") names no text to match, so
        // substring matching finds nothing and the step failed
        // honestly forever. The first result is positional: the
        // topmost row of the first list on screen (see
        // FirstRowResolution). Only a bare selector takes this path;
        // a named target still substring-matches.
        val bareFirst = isBareFirstTarget(target)
        val firstRow = if (bareFirst) firstResultRow(screen) else null
        if (bareFirst && firstRow == null) {
            return StepDispatchResult.FAILED(
                "No results on screen to pick the first from.",
            )
        }
        val matches = if (firstRow != null) listOf(firstRow)
        else findTargetElements(screen, target)
        val firstOnly = step.params[PlanParams.MATCH] == "first"
        return when {
            matches.isEmpty() ->
                StepDispatchResult.FAILED("target gone: \"$target\" no longer matches anything on screen")
            matches.size > 1 && !firstOnly ->
                StepDispatchResult.FAILED("${matches.size} matches for \"$target\" — be more specific")
            else -> {
                val el = matches[0]
                val scope = step.params[PlanParams.PACKAGE].orEmpty()
                val covered = isStepCovered(step)
                val outOfScope = outOfScopeInfo(step)
                val tokenRequest = when {
                    covered -> PlanTokenRequest(
                        key = PlanActionKeys.TAP_GUIDANCE,
                        label = step.description,
                        scope = scope,
                        sequenced = true,
                    )
                    outOfScope != null -> PlanTokenRequest(
                        key = PlanActionKeys.TAP_GUIDANCE,
                        label = step.description,
                        scope = scope,
                        outOfScope = true,
                        outOfScopeApp = outOfScope.first,
                        outOfScopeOtherApp = outOfScope.second,
                    )
                    else -> PlanTokenRequest(
                        key = PlanActionKeys.TAP_GUIDANCE,
                        label = step.description,
                        scope = scope,
                    )
                }
                val token = safeToken(tokenRequest)
                    ?: return StepDispatchResult.REFUSED(
                        "Not confirmed — the tap of \"$target\" was not approved.",
                    )
                // NAV-SCREEN fix (2026-11-11, N6): live re-resolution
                // at tap time. The token wait can be up to 90 s; the
                // element resolved against the passed-in snapshot may
                // have moved since. Re-resolve against a FRESH
                // snapshot now (identity + 24px / 2× guards, the
                // search path's tapFresh pattern) and tap the live
                // element — never frozen coordinates.
                val (freshEl, freshFailure) = freshTapElement(step, el, screen)
                if (freshFailure != null) {
                    return StepDispatchResult.FAILED(freshFailure)
                }
                when (val tapped = safeTap(freshEl ?: el, token)) {
                    PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
                    is PlanGestureResult.Refused ->
                        StepDispatchResult.FAILED("Tap refused: ${tapped.reason}")
                }
            }
        }
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N1/N2/N6) — NAVIGATE_TAB dispatch.
     * Same shape as [dispatchTap] (financial pre-scan, `tap.guidance`
     * token — a navigation tap IS a tap, no new key — live
     * re-resolution before the tap), but the target resolves through
     * the tab resolver first (label → synonym → ordinal →
     * positional), falling back to the shared text resolution. A
     * destination with no tab on screen fails honestly as target
     * gone; several matches refuse instead of guessing.
     */
    private fun dispatchNavigateTab(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        val target = step.params[PlanParams.TARGET].orEmpty()
        if (target.isBlank()) {
            return StepDispatchResult.FAILED("No destination named to navigate to.")
        }
        if (FinancialActionDetector.matches(step.description, target)) {
            return StepDispatchResult.REFUSED(
                "That looks like a financial or security-sensitive action, " +
                    "so it is never tapped.",
            )
        }
        // Mark 57 transfer (2026-11-11, M6) — the knowledge base's
        // preferred tab for this destination on this screen is
        // consulted FIRST (label/position from before); the
        // resolver validates it against the live tab and falls
        // through when it is stale.
        val preferred = try {
            knowledge?.preferredNavTabFor(screen, target)
        } catch (_: Exception) {
            null
        }
        val navMatches = NavigationResolver.resolveNavigationTarget(
            screen, target, preferred?.position, preferred?.label,
        )
        val matches = if (navMatches.isNotEmpty()) navMatches.map { it.element }
        else findTargetElements(screen, target)
        val firstOnly = step.params[PlanParams.MATCH] == "first"
        return when {
            matches.isEmpty() ->
                StepDispatchResult.FAILED("target gone: \"$target\" no longer matches anything on screen")
            matches.size > 1 && !firstOnly ->
                StepDispatchResult.FAILED("${matches.size} matches for \"$target\" — be more specific")
            else -> {
                val el = matches[0]
                val scope = step.params[PlanParams.PACKAGE].orEmpty()
                val token = safeToken(coveredTokenRequest(PlanActionKeys.TAP_GUIDANCE, step.description, step))
                    ?: return StepDispatchResult.REFUSED(
                        "Not confirmed — the navigation to \"$target\" was not approved.",
                    )
                val (freshEl, freshFailure) = freshTapElement(step, el, screen)
                if (freshFailure != null) {
                    return StepDispatchResult.FAILED(freshFailure)
                }
                when (val tapped = safeTap(freshEl ?: el, token)) {
                    PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
                    is PlanGestureResult.Refused ->
                        StepDispatchResult.FAILED("Tap refused: ${tapped.reason}")
                }
            }
        }
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N6) — live re-resolution of a tap
     * target against a FRESH snapshot taken after the token wait.
     * Returns the fresh element to tap, or (null, reason) when the
     * target moved / is gone / is ambiguous. When the fresh snapshot
     * cannot be read at all (empty / throwing observer) the
     * passed-in element is returned: an unreadable screen is not
     * proof the target moved, and the service-side live re-find
     * (LoomAccessibilityService.requestTap) still guards the tap.
     */
    private fun freshTapElement(
        step: PlanStep,
        element: ScreenElement,
        screen: ScreenModel,
    ): Pair<ScreenElement?, String?> {
        val target = step.params[PlanParams.TARGET].orEmpty()
        val fresh = try {
            observer.observeNow()
        } catch (_: Exception) {
            null
        } ?: return element to null
        if (fresh.elements.isEmpty()) return element to null
        return when (val resolved = LiveTapResolutionChecker.reResolveTapTarget(fresh, element, target)) {
            is LiveTapResolution.Resolved -> resolved.element to null
            is LiveTapResolution.Moved -> null to resolved.reason
            is LiveTapResolution.Gone -> null to resolved.reason
            is LiveTapResolution.Ambiguous -> null to resolved.reason
            // Mark 57 transfer (M2): a VISION-origin tap can never
            // reach here (reResolveTapTarget only resolves tree
            // elements), but the arm is required — and if it ever
            // did, it refuses, never taps pixels.
            is LiveTapResolution.VisionRefused -> null to resolved.reason
        }
    }

    /**
     * Universal Console (2026-11-07) — LONG_PRESS. Same shape as
     * [dispatchTap] (financial pre-scan, TOCTOU re-resolution
     * against the fresh screen, `tap.guidance` token): a long-press
     * IS a tap held down, so it carries the tap authorization, not
     * a new key. The executor drives the pointer to the target
     * first, exactly as for a tap; this dispatch only holds it.
     */
    private fun dispatchLongPress(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        val target = step.params[PlanParams.TARGET].orEmpty()
        if (target.isBlank()) {
            return StepDispatchResult.FAILED("No target named to long-press.")
        }
        if (FinancialActionDetector.matches(step.description, target)) {
            return StepDispatchResult.REFUSED(
                "That looks like a financial or security-sensitive action, " +
                    "so it is never long-pressed.",
            )
        }
        // MASTER Wave 4 — bare first-result target resolves
        // positionally (same rule as dispatchTap); a named target
        // still substring-matches.
        val bareFirst = isBareFirstTarget(target)
        val firstRow = if (bareFirst) firstResultRow(screen) else null
        if (bareFirst && firstRow == null) {
            return StepDispatchResult.FAILED(
                "No results on screen to pick the first from.",
            )
        }
        val matches = if (firstRow != null) listOf(firstRow)
        else findTargetElements(screen, target)
        // MASTER Wave 3 (F9) — same rule as dispatchTap: MATCH=first
        // is a user-directed first selector; without it, several
        // matches refuse honestly instead of long-pressing the first.
        val firstOnly = step.params[PlanParams.MATCH] == "first"
        return when {
            matches.isEmpty() ->
                StepDispatchResult.FAILED("target gone: \"$target\" no longer matches anything on screen")
            matches.size > 1 && !firstOnly ->
                StepDispatchResult.FAILED("${matches.size} matches for \"$target\" — be more specific")
            else -> {
                val el = matches[0]
                val token = safeToken(
                    coveredTokenRequest(
                        PlanActionKeys.TAP_GUIDANCE, step.description, step,
                    ),
                ) ?: return StepDispatchResult.REFUSED(
                    "Not confirmed — the long-press of \"$target\" was not approved.",
                )
                when (val pressed = safeLongPress(el, token)) {
                    PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
                    is PlanGestureResult.Refused ->
                        StepDispatchResult.FAILED("Long-press refused: ${pressed.reason}")
                }
            }
        }
    }

    /**
     * Universal Console (2026-11-07) — SWIPE. Direction is the
     * step's whole meaning: an unknown or missing direction fails
     * honestly (the validator rejects it for LLM plans; the
     * planner asks for it in rule plans). `device.scroll` token:
     * a swipe is the gesture form of scrolling.
     */
    private fun dispatchSwipe(step: PlanStep): StepDispatchResult {
        val direction = step.params[PlanParams.DIRECTION].orEmpty().lowercase()
        if (direction !in setOf("up", "down", "left", "right")) {
            return StepDispatchResult.FAILED(
                "No swipe direction — swipe up, down, left, or right.",
            )
        }
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.DEVICE_SCROLL, "Swipe $direction", step,
            ),
        ) ?: return StepDispatchResult.REFUSED("Not confirmed — nothing swiped.")
        return when (val swiped = safeSwipe(direction, token)) {
            PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
            is PlanGestureResult.Refused ->
                StepDispatchResult.FAILED("Swipe refused: ${swiped.reason}")
        }
    }

    /**
     * Universal Console (2026-11-07) — FOCUS_FIELD. Taps the named
     * field (preferring a real text-entry field among the matches)
     * under `tap.guidance`, then PROVES the field holds input focus
     * before reporting DISPATCHED — a tap that never focused the
     * field types nowhere, so dispatch alone is never success here.
     */
    private fun dispatchFocusField(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        val target = step.params[PlanParams.TARGET].orEmpty()
        if (target.isBlank()) {
            return StepDispatchResult.FAILED("No field named to focus.")
        }
        val matches = findTargetElements(screen, target)
        if (matches.isEmpty()) {
            return StepDispatchResult.FAILED("target gone: \"$target\" no longer matches anything on screen")
        }
        val field = matches.firstOrNull {
            it.role == ElementRole.INPUT || it.role == ElementRole.SEARCH_FIELD
        } ?: matches[0]
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.TAP_GUIDANCE, step.description, step,
            ),
        ) ?: return StepDispatchResult.REFUSED(
            "Not confirmed — the field was not tapped.",
        )
        return when (val tapped = safeTap(field, token)) {
            PlanGestureResult.Accepted -> {
                val focused = try {
                    hasInputFocus()
                } catch (_: Exception) {
                    false
                }
                if (focused) StepDispatchResult.DISPATCHED
                else StepDispatchResult.FAILED(
                    "The field did not take focus — nothing was typed into it.",
                )
            }
            is PlanGestureResult.Refused ->
                StepDispatchResult.FAILED("Tap refused: ${tapped.reason}")
        }
    }

    /**
     * Universal Console (2026-11-07) — OPEN_MENU. The menu control
     * is resolved semantically from the live tree (a MENU_ITEM role,
     * or a control labelled "menu" / "more options") — never blind
     * coordinates, never a guessed position. Zero matches or several
     * matches fail honestly instead of tapping the wrong control.
     */
    private fun dispatchOpenMenu(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        val candidates = screen.elements.filter { el ->
            el.enabled && !el.bounds.isEmpty &&
                (el.role == ElementRole.MENU_ITEM ||
                    el.text?.trim()?.equals("menu", ignoreCase = true) == true ||
                    el.contentDescription?.trim()?.equals("menu", ignoreCase = true) == true ||
                    el.contentDescription?.contains("more options", ignoreCase = true) == true ||
                    el.text?.contains("more options", ignoreCase = true) == true)
        }
        return when {
            candidates.isEmpty() ->
                StepDispatchResult.FAILED("No menu control on screen to open.")
            candidates.size > 1 ->
                StepDispatchResult.FAILED(
                    "${candidates.size} menu controls on screen — be more specific",
                )
            else -> {
                val el = candidates[0]
                val token = safeToken(
                    coveredTokenRequest(
                        PlanActionKeys.TAP_GUIDANCE, step.description, step,
                    ),
                ) ?: return StepDispatchResult.REFUSED(
                    "Not confirmed — the menu was not opened.",
                )
                when (val tapped = safeTap(el, token)) {
                    PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
                    is PlanGestureResult.Refused ->
                        StepDispatchResult.FAILED("Tap refused: ${tapped.reason}")
                }
            }
        }
    }

    /**
     * MASTER Wave 3 (F15b) — PLAY_PAUSE. Resolves the play/pause
     * control from the live tree (a Pause control when the video is
     * playing, a Play control when it is paused — never a
     * voice-search control) and taps it under `tap.guidance`. A
     * named TARGET is resolved the same way (no blind-pick; a
     * play/pause control among the matches wins). DISPATCHED only
     * means the tap was accepted — the executor verifies the player
     * state actually flipped afterwards; a tap alone is never
     * success.
     */
    private fun dispatchPlayPause(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        val wantPlaying = when (step.params[PlanParams.PLAY_STATE]?.lowercase()?.trim()) {
            "pause" -> false
            "play" -> true
            null, "" -> {
                // Toggle: pause when playing, play when paused.
                val state = VideoPresence.state(screen)
                if (!state.present) {
                    return StepDispatchResult.FAILED(
                        "No video is on screen to play or pause.",
                    )
                }
                !state.playing
            }
            else -> return StepDispatchResult.FAILED(
                "Unknown play state: \"${step.params[PlanParams.PLAY_STATE]}\".",
            )
        }
        val target = step.params[PlanParams.TARGET].orEmpty()
        val el = if (target.isNotBlank()) {
            val matches = findTargetElements(screen, target)
            val controls = matches.filter {
                if (wantPlaying) VideoPresence.isPlayControl(it)
                else VideoPresence.isPauseControl(it)
            }
            when {
                controls.size == 1 -> controls[0]
                controls.size > 1 ->
                    return StepDispatchResult.FAILED(
                        "${controls.size} matches for \"$target\" — be more specific",
                    )
                matches.isEmpty() ->
                    return StepDispatchResult.FAILED(
                        "target gone: \"$target\" no longer matches anything on screen",
                    )
                matches.size == 1 -> matches[0]
                else ->
                    return StepDispatchResult.FAILED(
                        "${matches.size} matches for \"$target\" — be more specific",
                    )
            }
        } else {
            // Session/state awareness (2026-11-11, OBSERVE-FIRST):
            // the control is resolved on the CURRENT screen by the
            // router — tap it there, never launch. No control on
            // screen is an honest failure, never a fresh play-search.
            val control = if (wantPlaying) MediaControl.PLAY else MediaControl.PAUSE
            when (val route = MediaControlRouter.route(screen, control)) {
                is MediaControlRouter.ControlRoute.TapOnCurrentScreen -> route.element
                is MediaControlRouter.ControlRoute.NoControlOnScreen ->
                    return StepDispatchResult.FAILED(route.reason)
            }
        }
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.TAP_GUIDANCE, step.description, step,
            ),
        ) ?: return StepDispatchResult.REFUSED(
            "Not confirmed — the video was not ${if (wantPlaying) "played" else "paused"}.",
        )
        return when (val tapped = safeTap(el, token)) {
            PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
            is PlanGestureResult.Refused ->
                StepDispatchResult.FAILED("Tap refused: ${tapped.reason}")
        }
    }

    private fun dispatchType(step: PlanStep): StepDispatchResult {
        val text = step.params[PlanParams.TEXT].orEmpty()
        if (text.isBlank()) return StepDispatchResult.FAILED("Nothing to type.")
        if (FinancialActionDetector.matches(text)) {
            return StepDispatchResult.REFUSED(
                "That looks like a financial or security-sensitive action, " +
                    "so it is never typed.",
            )
        }
        // The field must hold focus first (SearchExecutor pattern — type
        // into a field that never took focus types nowhere).
        val focused = try {
            hasInputFocus()
        } catch (_: Exception) {
            false
        }
        if (!focused) {
            return StepDispatchResult.FAILED("The text field doesn't have focus — nothing was typed.")
        }
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.DEVICE_TYPE, "Type the text", step,
            ),
        ) ?: return StepDispatchResult.REFUSED("Not confirmed — nothing was typed.")
        return when (val typed = safeType(text, token)) {
            PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
            is PlanGestureResult.Refused ->
                StepDispatchResult.FAILED("Type refused: ${typed.reason}")
        }
    }

    /**
     * AOIE order (a) + Phase 2 — ONE token decision per submit
     * step: the old code requested a second fresh token for the
     * keyboard-key fallback (two cards for one submit when the
     * first mechanism was refused). Both mechanisms now share the
     * step's single token decision (same device.submit key), and a
     * covered step uses the sequenced ALLOW token. The keyboard
     * intent (KeyboardAction) is honoured: "dismiss" dismisses the
     * keyboard instead of submitting (the dismiss-and-retry
     * recovery, now real), "newline" types a newline instead of
     * submitting, anything else submits.
     */
    private fun dispatchSubmit(step: PlanStep): StepDispatchResult {
        when (step.params[PlanParams.KEYBOARD_ACTION]?.lowercase()?.trim()) {
            "dismiss" -> return dismissKeyboard()
            "newline" -> {
                val token = safeToken(
                    coveredTokenRequest(
                        PlanActionKeys.DEVICE_TYPE, "Type a newline", step,
                    ),
                ) ?: return StepDispatchResult.REFUSED("Not confirmed — nothing was typed.")
                return when (val typed = safeType("\n", token)) {
                    PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
                    is PlanGestureResult.Refused ->
                        StepDispatchResult.FAILED("Type refused: ${typed.reason}")
                }
            }
        }
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.DEVICE_SUBMIT, "Submit", step,
            ),
        ) ?: return StepDispatchResult.REFUSED("Not confirmed — nothing was submitted.")
        return when (val submitted = safeSubmit(token)) {
            PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
            is PlanGestureResult.Refused -> {
                // Mechanism 2 — the keyboard's own Search / Go / Done
                // key, with the SAME token decision (no second card).
                // Accepted here still only means DISPATCHED; the
                // executor verifies against a fresh snapshot.
                when (val viaKey = safeSubmitViaKey(token)) {
                    PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
                    is PlanGestureResult.Refused ->
                        StepDispatchResult.FAILED(
                            "Submit refused: ${submitted.reason}; " +
                                "keyboard key also refused: ${viaKey.reason}",
                        )
                }
            }
        }
    }

    /**
     * AOIE Phase 2 — the real keyboard dismiss: the system Back
     * action closes the keyboard without submitting. Cover-aware
     * (device.back sequenced ALLOW under a live cover), so the
     * dismiss-and-retry recovery no longer cards mid-recovery.
     * The executor verifies afterwards that the keyboard is gone.
     */
    override fun dismissKeyboard(): StepDispatchResult {
        val step = PlanStep(
            index = -1,
            kind = TaskStepKind.GO_BACK,
            description = "Dismiss the keyboard",
        )
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.DEVICE_BACK, "Dismiss the keyboard", step,
            ),
        ) ?: return StepDispatchResult.REFUSED("Not confirmed — the keyboard was not dismissed.")
        return when (val backed = safeBack(token)) {
            PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
            is PlanGestureResult.Refused ->
                StepDispatchResult.FAILED("Dismiss refused: ${backed.reason}")
        }
    }

    private fun dispatchScroll(step: PlanStep): StepDispatchResult {
        val direction = step.params[PlanParams.DIRECTION].orEmpty().lowercase()
        val forward = direction !in setOf("up", "left")
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.DEVICE_SCROLL,
                "Scroll ${if (forward) "down" else "up"}", step,
            ),
        ) ?: return StepDispatchResult.REFUSED("Not confirmed — nothing scrolled.")
        return when (val scrolled = safeScroll(forward, token)) {
            PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
            is PlanGestureResult.Refused ->
                StepDispatchResult.FAILED("Scroll refused: ${scrolled.reason}")
        }
    }

    private fun dispatchBack(step: PlanStep): StepDispatchResult {
        val token = safeToken(
            coveredTokenRequest(
                PlanActionKeys.DEVICE_BACK, "Go back", step,
            ),
        ) ?: return StepDispatchResult.REFUSED("Not confirmed — did not go back.")
        return when (val backed = safeBack(token)) {
            PlanGestureResult.Accepted -> StepDispatchResult.DISPATCHED
            is PlanGestureResult.Refused ->
                StepDispatchResult.FAILED("Back refused: ${backed.reason}")
        }
    }

    // ---------- verify / guide / message / call / pointer ----------

    private fun dispatchVerify(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        val expected = step.params[PlanParams.EXPECTED].orEmpty()
        return if (expectedOnScreen(screen, expected)) {
            StepDispatchResult.DISPATCHED
        } else {
            StepDispatchResult.FAILED(
                "I couldn't confirm: $expected",
            )
        }
    }

    private fun dispatchGuide(step: PlanStep): StepDispatchResult {
        val target = step.params[PlanParams.GUIDE_TARGET].orEmpty()
        if (target.isBlank()) return StepDispatchResult.FAILED("Nothing to guide to.")
        val shown = try {
            guideTo(target)
        } catch (_: Exception) {
            false
        }
        return if (shown) StepDispatchResult.DISPATCHED
        else StepDispatchResult.FAILED("Guidance isn't available right now — the pointer isn't on screen.")
    }

    private fun dispatchMessage(step: PlanStep): StepDispatchResult {
        val recipient = step.params[PlanParams.RECIPIENT].orEmpty()
        val text = step.params[PlanParams.MESSAGE]
            ?: step.params[PlanParams.TEXT].orEmpty()
        val channel = step.params[PlanParams.CHANNEL].orEmpty().lowercase()
        if (recipient.isBlank()) return StepDispatchResult.FAILED("No recipient for the message.")
        if (text.isBlank()) return StepDispatchResult.FAILED("No message text to send.")
        if (channel !in setOf("whatsapp", "sms")) {
            return StepDispatchResult.FAILED("Unknown message channel: $channel.")
        }
        // The executor owns the fresh message.send token per send — the
        // plan cover never spans this step (see PlanCover.checkCover).
        return when (val outcome = safeSend(channel, recipient, text)) {
            is MessageOutcome.Success -> StepDispatchResult.DISPATCHED
            is MessageOutcome.Failed -> StepDispatchResult.FAILED(outcome.reason)
            MessageOutcome.AppNotFound ->
                StepDispatchResult.FAILED("I couldn't find the messaging app on this phone.")
            is MessageOutcome.AmbiguousApp ->
                StepDispatchResult.FAILED("Which one — ${outcome.candidates.joinToString(", ")}?")
            is MessageOutcome.AmbiguousContact ->
                StepDispatchResult.FAILED(
                    "I found more than one contact matching '$recipient': " +
                        "${outcome.candidates.joinToString(", ")}. Which one did you mean?",
                )
        }
    }

    private fun dispatchCall(step: PlanStep): StepDispatchResult {
        val target = step.params[PlanParams.RECIPIENT].orEmpty()
        if (target.isBlank()) return StepDispatchResult.FAILED("Nobody named to call.")
        // The executor owns the fresh call.place token per call — the
        // plan cover never spans this step. ACTION_DIAL only, never
        // ACTION_CALL (the call executor owns that boundary).
        return when (val outcome = safeCall(target)) {
            is CallOutcome.Success -> StepDispatchResult.DISPATCHED
            is CallOutcome.Failed -> StepDispatchResult.FAILED(outcome.reason)
            CallOutcome.AppNotFound ->
                StepDispatchResult.FAILED("I couldn't find the Phone app on this phone.")
            is CallOutcome.AmbiguousApp ->
                StepDispatchResult.FAILED("Which one — ${outcome.candidates.joinToString(", ")}?")
            is CallOutcome.AmbiguousContact ->
                StepDispatchResult.FAILED(
                    "I found more than one contact matching '$target': " +
                        "${outcome.candidates.joinToString(", ")}. Which one did you mean?",
                )
        }
    }

    private fun dispatchPointerMove(step: PlanStep): StepDispatchResult {
        val nudge = nudgePointer
            ?: return StepDispatchResult.FAILED("The pointer isn't visible right now — nothing moved.")
        val direction = step.params[PlanParams.DIRECTION].orEmpty()
        if (direction.isBlank()) {
            return StepDispatchResult.FAILED("No direction to move the pointer.")
        }
        val moved = try {
            nudge(direction)
        } catch (_: Exception) {
            false
        }
        return if (moved) StepDispatchResult.DISPATCHED
        else StepDispatchResult.FAILED("The pointer isn't visible right now — nothing moved.")
    }

    // ---------- small helpers ----------

    private fun resolvePackage(appName: String): String? {
        if (appName.isBlank()) return null
        return try {
            when (val r = apps.resolve(appName)) {
                is com.loom.assistant.intelligence.planner.AppResolution.Found -> r.packageName
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeObserve(): ScreenModel? = try {
        observer.observeNow()
    } catch (_: Exception) {
        null
    }

    /**
     * MASTER F7a — waits for [latch] in short slices so a stop
     * interrupts the wait within ~100 ms instead of at the deadline.
     * Returns true only when the latch counted down; false on stop
     * or timeout — the caller distinguishes via [isStopped].
     */
    private fun awaitLatch(latch: CountDownLatch, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isStopped()) return false
            try {
                if (latch.await(POLL_SLICE_MS, TimeUnit.MILLISECONDS)) return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (System.currentTimeMillis() >= deadline) return false
        }
    }

    /**
     * MASTER F7a — thrown by [safeToken] when a stop abandons the
     * token wait. Caught once in [dispatch] and reported as
     * FAILED("Stopped.") — a stop must never surface as "not
     * confirmed", which would misreport David's stop as a decline.
     */
    private class StoppedException : Exception("Stopped.")

    /**
     * MASTER F7a — stop-aware token acquisition. The blocking token
     * provider parks up to 90 s on a card, so `tokenFor` runs on a
     * daemon worker thread while this thread polls the stop flag in
     * slices: a stop abandons the wait and the token is never used
     * (an approval arriving later issues a token that expires
     * unconsumed — the worker thread is left to finish on the
     * provider's own timeout). The stop is re-checked after the
     * wait returns — a token issued as the stop landed is still
     * abandoned. Throws [StoppedException] on stop; returns null
     * on decline, timeout, or error.
     */
    private fun safeToken(request: PlanTokenRequest): String? {
        if (isStopped()) throw StoppedException()
        var result: String? = null
        var thrown: Exception? = null
        val worker = Thread({
            try {
                result = tokens.tokenFor(request)
            } catch (e: Exception) {
                thrown = e
            }
        }, "plan-token-wait").apply { isDaemon = true }
        worker.start()
        val deadline = System.currentTimeMillis() + TOKEN_WAIT_MS
        while (worker.isAlive) {
            if (isStopped()) throw StoppedException()
            try {
                worker.join(POLL_SLICE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw StoppedException()
            }
            if (System.currentTimeMillis() >= deadline) return null
        }
        if (isStopped()) throw StoppedException()
        if (thrown != null) return null
        return result
    }

    private fun safeTap(el: ScreenElement, token: String?): PlanGestureResult = try {
        gestures.tap(el, token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "tap threw")
    }

    private fun safeType(text: String, token: String?): PlanGestureResult = try {
        gestures.type(text, token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "type threw")
    }

    private fun safeSubmit(token: String?): PlanGestureResult = try {
        gestures.submit(token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "submit threw")
    }

    private fun safeSubmitViaKey(token: String?): PlanGestureResult = try {
        gestures.submitViaKey(token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "keyboard-key submit threw")
    }

    private fun safeLongPress(el: ScreenElement, token: String?): PlanGestureResult = try {
        gestures.longPress(el, token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "long-press threw")
    }

    private fun safeSwipe(direction: String, token: String?): PlanGestureResult = try {
        gestures.swipe(direction, token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "swipe threw")
    }

    private fun safeScroll(forward: Boolean, token: String?): PlanGestureResult = try {
        gestures.scroll(forward, token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "scroll threw")
    }

    private fun safeBack(token: String?): PlanGestureResult = try {
        gestures.back(token)
    } catch (e: Exception) {
        PlanGestureResult.Refused(e.message ?: "back threw")
    }

    private fun safeSearch(app: String, query: String): SearchOutcome = try {
        searchInApp(app, query, origin)
    } catch (e: Exception) {
        SearchOutcome.Failed("Search failed: ${e.message ?: "unknown error"}")
    }

    private fun safeSearchWithOrder(
        app: String,
        query: String,
        order: List<SubmitMechanism>,
    ): SearchOutcome {
        val fn = searchInAppWithOrder ?: return safeSearch(app, query)
        return try {
            fn(app, query, origin, order)
        } catch (e: Exception) {
            SearchOutcome.Failed("Search failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun safeSearchFull(
        app: String,
        query: String,
        goal: com.loom.assistant.execution.search.SearchGoal,
        order: List<SubmitMechanism>?,
    ): SearchOutcome {
        val fn = searchInAppFull ?: return safeSearch(app, query)
        return try {
            fn(app, query, origin, goal, order)
        } catch (e: Exception) {
            SearchOutcome.Failed("Search failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun safeSend(channel: String, recipient: String, text: String): MessageOutcome = try {
        sendMessage(channel, recipient, text, origin)
    } catch (e: Exception) {
        MessageOutcome.Failed("Message failed: ${e.message ?: "unknown error"}")
    }

    private fun safeCall(target: String): CallOutcome = try {
        placeCall(target, origin)
    } catch (e: Exception) {
        CallOutcome.Failed("Call failed: ${e.message ?: "unknown error"}")
    }
}
