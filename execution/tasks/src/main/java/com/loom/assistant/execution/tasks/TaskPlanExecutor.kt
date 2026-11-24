package com.loom.assistant.execution.tasks

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.MeasuredControl
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenDigest
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SearchScreenPredicates
import com.loom.assistant.execution.screenawareness.StopLimits
import com.loom.assistant.execution.screenawareness.firstResultRow
import com.loom.assistant.execution.screenawareness.isBareFirstTarget
import com.loom.assistant.execution.screenawareness.NoChangeTracker
import com.loom.assistant.execution.screenawareness.LiveTapResolution
import com.loom.assistant.execution.screenawareness.LiveTapResolutionChecker
import com.loom.assistant.execution.screenawareness.NavigationResolver
import com.loom.assistant.execution.screenawareness.NavigationVerification
import com.loom.assistant.execution.screenawareness.NavigationVerificationChecker
import com.loom.assistant.execution.screenawareness.ScreenshotCaptureGate
import com.loom.assistant.execution.screenawareness.ScreenshotCaptureState
import com.loom.assistant.execution.screenawareness.TargetAliases
import com.loom.assistant.execution.screenawareness.VideoPresence
import com.loom.assistant.execution.screenawareness.VisualDetection
import com.loom.assistant.execution.screenawareness.VisualObservation
import com.loom.assistant.intelligence.planner.AppProfileLearner
import com.loom.assistant.intelligence.planner.AppProfileStore
import com.loom.assistant.intelligence.planner.ScreenKnowledgeStore
import com.loom.assistant.intelligence.planner.DEFAULT_SUBMIT_ORDER
import com.loom.assistant.intelligence.planner.PerceptionCoordinator
import com.loom.assistant.intelligence.planner.ScreenClassifier
import com.loom.assistant.intelligence.planner.ScreenKind
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.CoverResult
import com.loom.assistant.intelligence.planner.PlanAuditSink
import com.loom.assistant.intelligence.planner.PlanAuthCover
import com.loom.assistant.intelligence.planner.PlanFailureAnalyzer
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStatus
import com.loom.assistant.intelligence.planner.PlanValidator
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.StepDispatcher
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.ExecutionTraceEntry
import com.loom.assistant.intelligence.planner.ExecutionTraceStore
import com.loom.assistant.intelligence.planner.TaskPlanner
import com.loom.assistant.intelligence.planner.TaskStatus
import com.loom.assistant.intelligence.planner.VerificationResult
import com.loom.assistant.intelligence.planner.VerifiedWorkflowSink
import com.loom.assistant.intelligence.planner.TaskStatusSink
import com.loom.assistant.intelligence.planner.TaskStatusUpdate
import com.loom.assistant.intelligence.planner.taskStatusMessage
import com.loom.assistant.intelligence.planner.TaskStepKind
import com.loom.assistant.intelligence.planner.TargetBounds
import com.loom.assistant.intelligence.planner.PlanOutcome
import com.loom.assistant.intelligence.planner.StepStatus

/**
 * Stage 2D (Worker B) — the plan executor.
 *
 * Drives Worker A's planner core through the enforced cycle, on a
 * background thread (never the main thread — confirmation cards for
 * MODEL plans and the dispatcher's per-step cards block up to 90 s
 * each):
 *
 *   UNDERSTAND → OBSERVE → IDENTIFY → PLAN → AUTHORIZE → ACT →
 *   OBSERVE AGAIN → VERIFY → CONTINUE
 *
 *  - UNDERSTAND / PLAN: [TaskPlanner.plan] turns the [PlanRequest] into
 *    a [TaskPlan]. A planner refusal (clarification / sensitive /
 *    unsupported) becomes an honest FAILED [PlanOutcome] — never a
 *    guessed plan, never a dispatch.
 *  - AUTHORIZE: once per plan, [PlanAuthCover.requestPlanCover]
 *    authorizes the sequence. Corrected model (David, 2026-11-06):
 *    the plan is LOOM's internal mechanism — David never sees it
 *    and never approves it. A DIRECT_USER plan runs under his
 *    blanket advance approval (no card, no plan shown); a MODEL plan
 *    still goes through the per-sequence card. Guidance-only plans
 *    (no action step) skip authorization — there is nothing to
 *    authorize. A DENIED cover ends the plan before any dispatch.
 *  - OBSERVE + IDENTIFY: before EVERY action step, a fresh snapshot
 *    ([ScreenObserver.observeNow]) and a fresh target resolution
 *    against it (TOCTOU). The pointer is driven for EVERY step with
 *    an on-screen target — [PointerGuide.moveTo] to the verified
 *    target + [PointerGuide.showIntended] (the step's label in the
 *    pill) BEFORE the dispatch, for TAP_TARGET, TYPE_TEXT, SUBMIT,
 *    SCROLL, SEARCH_IN_APP, GUIDE_TO, GO_BACK and SWIPE (GO_BACK
 *    drives to the Back button or the back-swipe edge, SWIPE to its
 *    start point); LAUNCH_APP has no on-screen target so it only
 *    narrates the step in the pill (never a fake outline). The
 *    pointer moves first and the action happens only after it
 *    arrives (the glide wait). For TAP_TARGET
 *    the target id is re-tracked ([PointerGuide.trackTarget]) — a
 *    moved or gone target never gets a stale tap — and a pointer-draw
 *    failure is reported as a pointer failure, never as "target gone".
 *  - ACT: one [StepDispatcher.dispatch] per step. The dispatcher's
 *    DISPATCHED only means "accepted" — never success.
 *  - OBSERVE AGAIN + VERIFY: after EVERY action step, a fresh snapshot
 *    is checked against the step's expected effect ([verifyStep]).
 *    Dispatch-only success is NEVER counted as verified: a dispatched
 *    tap with no confirming observation fails the step.
 *  - CONTINUE / recover: on failure [PlanFailureAnalyzer.analyze]
 *    decides RETRY (max 3 attempts/step) / SKIP (non-critical only) /
 *    REPLAN (rebuild remaining steps only, max 2 replans — completed
 *    steps are never repeated) / ABORT.
 *  - STOP: [PlanStopSignal] (either independent stop) or a lifecycle
 *    flip underneath it halts immediately — remaining steps stay
 *    UNVERIFIED, the outcome is INTERRUPTED, and the summary says so.
 *    A stop that lands while the cover card is up abandons the token:
 *    it is never consumed and never used.
 *
 * The executor never reports unverified success: COMPLETED requires
 * every step VERIFIED; anything else is FAILED / INTERRUPTED with a
 * summary naming how many steps were verified. Every step is
 * audit-logged ([PlanAuditSink]) with step names, indices and outcomes
 * only — never screen content.
 *
 * The executor never calls dispatchGesture, never touches AuthGate
 * directly, and never logs element text. All execution goes through the
 * injected seams.
 */
class TaskPlanExecutor(
    private val apps: AppResolver,
    private val planner: TaskPlanner = TaskPlanner(apps),
    private val analyzer: PlanFailureAnalyzer = PlanFailureAnalyzer(),
    private val dispatcher: StepDispatcher,
    private val observer: ScreenObserver,
    private val pointer: PointerGuide,
    private val cover: PlanAuthCover,
    private val stop: PlanStopSignal,
    private val audit: PlanAuditSink,
    /**
     * App-aware execution (2026-11-07): the learned app profiles
     * (submit orders, mechanism wins). Default is a fresh seeded
     * store; callers that own one (production wiring) pass it in.
     */
    private val profiles: AppProfileStore = AppProfileStore(),
    /**
     * KNOWLEDGE BASE (2026-11-09): the durable screen knowledge
     * the executor reads before planning (the submit order for a
     * known screen) and writes on observation (every screen it
     * sees) and on outcomes (submit wins, back-recovery results).
     * Default is an empty in-memory store — no knowledge means
     * the executor behaves exactly as before. Production wiring
     * passes the shared disk-backed store.
     */
    private val knowledge: ScreenKnowledgeStore = ScreenKnowledgeStore(),
    /**
     * App-aware execution (2026-11-07): coordinates TAP_TARGET
     * taps so the tap lands exactly where the pointer parked.
     */
    private val tapCoordinator: PointerTapCoordinator = PointerTapCoordinator(),
    /**
     * Simple-task execution fix (2026-11-07): the ONE task-state
     * sink. The executor owns the state and posts every transition
     * here — In progress at start, the honest terminal state at the
     * end (Completed / Partially completed / Failed / Waiting for
     * user input). Hosts deliver each update to BOTH the spoken ack
     * and the shared conversation store. Null in tests / callers
     * that do not deliver status; a throwing sink never fails a plan.
     */
    private val statusSink: TaskStatusSink? = null,
    /**
     * AOIE Phase 1 — the typed per-attempt trace (action id →
     * dispatch → verification verdict + confidence). One store
     * per executor (per plan run); every entry is also flushed
     * to the audit sink as a structured line.
     */
    private val trace: ExecutionTraceStore = ExecutionTraceStore(),
    /**
     * AOIE Phase 7 — offered one COMPLETED plan per run (all
     * steps verified). The adapter records a promotion candidate
     * only; it never installs anything. Null = no promotion path.
     */
    private val verifiedWorkflowSink: VerifiedWorkflowSink? = null,
    /**
     * NAV-SCREEN fix (2026-11-11, N5) — the screenshot channel.
     * [visualSource] is the platform capture (the service's
     * takeScreenshot adapter); null = no capture available, and
     * navigation proceeds tree-only, exactly as before. The gate
     * is the consent record: a capture runs only under a
     * task-scoped grant for a NAVIGATE_TAB step David asked for.
     * Production wiring passes the shared service gate + source;
     * tests pass a fake source or leave both null.
     */
    private val screenshotGate: ScreenshotCaptureGate = ScreenshotCaptureGate(),
    private val visualSource: (() -> VisualObservation?)? = null,
) {

    private val learner = AppProfileLearner(profiles)

    /**
     * Per-plan submit-mechanism cursor: "$planId:$stepIndex" →
     * mechanisms already tried for that search step. A
     * TRY_ALTERNATIVE retry advances it and passes the remaining
     * order to the search sequence.
     */
    private val submitCursor = mutableMapOf<String, Int>()

    companion object {
        /** Half-size (px) of the marker ring for a gesture point (swipe start, back edge). */
        const val POINTER_POINT_HALF_PX = 32

        /**
         * Device-test failures (2026-11-11, D-01/D-08): LOOM's own
         * application id (app/build.gradle.kts). A tap for another
         * app never resolves against this package's screens — its
         * chat bubbles echo the task words and are not the app.
         */
        const val LOOM_PACKAGE_NAME = "com.loom.assistant"

        /** Steps that act (everything else is read-only / display-only). */
        val ACTION_KINDS: Set<TaskStepKind> = setOf(
            TaskStepKind.LAUNCH_APP,
            TaskStepKind.SEARCH_IN_APP,
            TaskStepKind.TAP_TARGET,
            TaskStepKind.TYPE_TEXT,
            TaskStepKind.SUBMIT,
            TaskStepKind.SCROLL,
            TaskStepKind.GO_BACK,
            TaskStepKind.MESSAGE_SEND,
            TaskStepKind.CALL_PLACE,
            // Universal Console (2026-11-07): the console operations
            // act inside an app — pre-step screen checks, cover
            // audit, and stop checks apply to them like any action.
            TaskStepKind.LONG_PRESS,
            TaskStepKind.SWIPE,
            TaskStepKind.FOCUS_FIELD,
            TaskStepKind.OPEN_MENU,
            // MASTER Wave 3 (F15b): play/pause acts inside an app.
            TaskStepKind.PLAY_PAUSE,
            // NAV-SCREEN fix (2026-11-11, N1): navigation taps act
            // inside an app — pre-step screen checks, cover audit,
            // and stop checks apply to them like any action.
            TaskStepKind.NAVIGATE_TAB,
        )

        /**
         * Steps whose target MUST resolve (tap targets only) — the
         * strict subset of [POINTER_DRIVE_KINDS]: a TAP_TARGET with a
         * gone / ambiguous / unnamed target fails the step instead of
         * dispatching blind.
         */
        val POINTER_KINDS: Set<TaskStepKind> = setOf(TaskStepKind.TAP_TARGET)

        /**
         * Pointer-drive fix (2026-11-06, David's direction): every step
         * kind the executor drives the pointer for, BEFORE dispatch.
         * TAP_TARGET, TYPE_TEXT, SUBMIT, SCROLL, SEARCH_IN_APP and
         * GUIDE_TO resolve an on-screen target and move the pointer
         * there (guide mode leads step by step; search drives through
         * the search box, then the type position, then submit via the
         * search wiring). LAUNCH_APP has no on-screen target — the
         * launch is by intent, not by tapping the icon — so it only
         * narrates the step in the pill, never a fake target outline.
         * POINTER EVERYWHERE (2026-11-09): GO_BACK and SWIPE joined
         * this set — GO_BACK drives to the measured system Back
         * button, or to the left edge (the back-swipe origin) on
         * gesture navigation; SWIPE drives to its computed start
         * point. When neither can be established, the step narrates
         * instead of drawing a fake target.
         */
        val POINTER_DRIVE_KINDS: Set<TaskStepKind> = setOf(
            TaskStepKind.LAUNCH_APP,
            TaskStepKind.SEARCH_IN_APP,
            TaskStepKind.TAP_TARGET,
            TaskStepKind.TYPE_TEXT,
            TaskStepKind.SUBMIT,
            TaskStepKind.SCROLL,
            TaskStepKind.GO_BACK,
            TaskStepKind.GUIDE_TO,
            // Universal Console (2026-11-07): the pointer parks on
            // the long-press / focus target and the menu control
            // before the console acts, exactly as for a tap — the
            // cursor visibly represents the same execution system.
            // POINTER EVERYWHERE (2026-11-09): SWIPE drives to its
            // computed start point (see resolvePointerPoint); GO_BACK
            // drives to the Back button or the left edge.
            // MASTER Wave 3 (F15b): PLAY_PAUSE parks on the
            // play/pause control before tapping it.
            TaskStepKind.LONG_PRESS,
            TaskStepKind.SWIPE,
            TaskStepKind.FOCUS_FIELD,
            TaskStepKind.OPEN_MENU,
            TaskStepKind.PLAY_PAUSE,
            // NAV-SCREEN fix (2026-11-11, N1): a navigation step
            // drives the pointer to its tab before tapping it.
            TaskStepKind.NAVIGATE_TAB,
        )

        /**
         * Device-failure fix (2026-11-13, F2) — pointer-display
         * gating: an operation step of one of these kinds whose
         * pointer failed to display is UNVERIFIED, even when the
         * screen check passes — the tap/scroll/type dispatched
         * invisibly, and David must never be told it completed
         * without the hand showing it. Deliberately NOT here:
         * SEARCH_IN_APP (results-based verification; the
         * device-proven play path verifies on results, and the
         * search sequence drives the pointer through its own
         * wiring), LAUNCH_APP / WAIT_FOR_APP (intent launches, no
         * on-screen target), GUIDE_TO (already fails on pointer
         * failure), MESSAGE_SEND / CALL_PLACE (their executors
         * verify internally), OBSERVE_SCREEN / REPORT /
         * VERIFY_OUTCOME / POINTER_MOVE / GO_BACK (read-only,
         * display-only, or leaving a screen).
         */
        val POINTER_GATE_KINDS: Set<TaskStepKind> = setOf(
            TaskStepKind.TAP_TARGET,
            TaskStepKind.NAVIGATE_TAB,
            TaskStepKind.SCROLL,
            TaskStepKind.SWIPE,
            TaskStepKind.TYPE_TEXT,
            TaskStepKind.SUBMIT,
            TaskStepKind.LONG_PRESS,
            TaskStepKind.FOCUS_FIELD,
            TaskStepKind.OPEN_MENU,
            TaskStepKind.PLAY_PAUSE,
        )
    }

    /**
     * MASTER Wave 3 (F14d) — the no-change rule, per plan run: a
     * step whose dispatch produced no observable change twice in a
     * row stops the run instead of retrying forever. Reset per run.
     */
    private val noChangeTracker = NoChangeTracker()

    /**
     * Device-failure fix (2026-11-13, F2) — the signature of the
     * step whose no-change failure the tracker last counted
     * (kind + sorted params). The streak counts consecutive
     * failures of the SAME attempted step: when the analyzer
     * changes the step (TRY_ALTERNATIVE rotates the submit
     * order, a replan re-issues with new params) the system is
     * adapting, not repeating, so the streak resets. Without
     * this the widened rule aborts the Nov 9 built-in recovery
     * (submit rotation → replan → clear → re-issue) on its
     * second failed attempt. Reset everywhere the tracker is.
     */
    private var lastNoChangeSignature: String? = null

    /**
     * MASTER Wave 3 (F14b) — scrolls dispatched in this plan run.
     * Bounded by [StopLimits.MAX_SCROLLS_PER_SEARCH]; reset per run.
     */
    private var scrollsThisRun = 0

    /**
     * Screen navigation (2026-11-10): the screen a TAP_TARGET
     * scroll hunt found its target on. Consumed once by the
     * attempt that hunted (see effectiveBefore in runAttempt);
     * null when no hunt ran or the hunt found nothing.
     */
    private var lastHuntScreen: ScreenModel? = null

    /**
     * Pointer-drive failures collected during this run, by reason (each
     * reason names the steps it hit, in order). A display failure never
     * fails the action, but the outcome names it — see
     * [pointerFailureSuffix]. Cleared per plan run ([executePlan]).
     */
    private val pointerFailures = linkedMapOf<String, MutableList<Int>>()

    /**
     * Simple-task execution fix (2026-11-07): the last question the
     * run asked David (target gone — "what should I tap instead?").
     * Set when a step aborts with an ask-user message; the terminal
     * status then maps to WAITING_FOR_USER_INPUT, never FAILED.
     * Cleared per plan run ([executePlan]).
     */
    private var lastAskUserMessage: String? = null

    /**
     * Simple-task execution fix (2026-11-07): a critical step a
     * replan abandoned (dropped with its dependents — see
     * [TaskPlanner.replan]). The old run accounting counted a
     * dropped step as "superseded" and could still report
     * COMPLETED; a dropped critical step means the task's core was
     * never done, so the run is FAILED and names it. Cleared per run.
     */
    private var droppedCriticalStep: PlanStep? = null

    /** Plans the request, then runs the plan. Blocking — background thread only. */
    fun execute(request: PlanRequest): PlanOutcome {
        return when (val result = safePlan(request)) {
            is TaskPlanner.PlanResult.Planned -> executePlan(result.plan, request)
            is TaskPlanner.PlanResult.ClarificationNeeded -> {
                // Simple-task execution fix (2026-11-07): a question
                // is WAITING FOR USER INPUT, not a failure — the task
                // is blocked on an answer only David can give. The
                // status is posted, not just returned: the host must
                // show it in the chat, not only speak it.
                postStatus(
                    TaskStatus.WAITING_FOR_USER_INPUT,
                    request.goal,
                    "I need to know: ${result.question}",
                )
                PlanOutcome(
                    status = PlanStatus.FAILED,
                    verifiedSteps = 0,
                    totalSteps = 0,
                    failedStep = null,
                    summary = "I need to know: ${result.question}",
                )
            }
            is TaskPlanner.PlanResult.RefusedSensitive -> {
                val guidance = result.guidancePlan
                if (guidance != null) {
                    // Guidance-only alternative: observe + point + report.
                    // No action step, so no cover card — display-only.
                    executePlan(guidance, request)
                } else {
                    postStatus(TaskStatus.FAILED, request.goal, result.reason)
                    PlanOutcome(
                        status = PlanStatus.FAILED,
                        verifiedSteps = 0,
                        totalSteps = 0,
                        failedStep = null,
                        summary = result.reason,
                    )
                }
            }
            is TaskPlanner.PlanResult.Refused -> {
                postStatus(TaskStatus.FAILED, request.goal, result.reason)
                PlanOutcome(
                    status = PlanStatus.FAILED,
                    verifiedSteps = 0,
                    totalSteps = 0,
                    failedStep = null,
                    summary = result.reason,
                )
            }
            is TaskPlanner.PlanResult.Unsupported -> {
                postStatus(TaskStatus.FAILED, request.goal, result.reason)
                PlanOutcome(
                    status = PlanStatus.FAILED,
                    verifiedSteps = 0,
                    totalSteps = 0,
                    failedStep = null,
                    summary = result.reason,
                )
            }
        }
    }

    /**
     * Runs an already-built [plan] (the [TaskPlanQueue] path — the queue
     * planned first so it could hand out the plan id). Blocking.
     */
    fun executePlan(plan: TaskPlan, request: PlanRequest): PlanOutcome {
        val planId = plan.id
        pointerFailures.clear()
        noChangeTracker.reset()
        lastNoChangeSignature = null
        scrollsThisRun = 0
        // A re-run of the same plan id starts its submit-mechanism
        // cursor from the beginning.
        submitCursor.clear()
        lastAskUserMessage = null
        droppedCriticalStep = null
        audit.log(planId, -1, "plan-started", "steps=${plan.steps.size}")
        // Simple-task execution fix (2026-11-07): the run is visible
        // from the first moment — "In progress" is posted before any
        // step runs, so David can see the task is running instead of
        // silence until the outcome arrives.
        postStatus(TaskStatus.IN_PROGRESS, plan.goal, "")
        try {
            // Device-failure fix (2026-11-13, F2) — the
            // launch-order entry gate, the last defence on BOTH
            // plan paths: a plan whose app-scoped steps have no
            // launch before them fails honestly HERE, before any
            // dispatch, never against the wrong screen. The
            // planner repairs LLM plans (prepend) and the
            // rule-based planner builds launches first; this gate
            // catches anything that still arrives malformed.
            when (val order = PlanValidator().validateLaunchOrder(plan)) {
                is PlanValidator.ValidationResult.Invalid -> {
                    val reason = order.reasons.joinToString("; ")
                    audit.log(planId, -1, "launch-order-gate", reason)
                    val gateOutcome = PlanOutcome(
                        status = PlanStatus.FAILED,
                        verifiedSteps = 0,
                        totalSteps = plan.steps.size,
                        failedStep = plan.steps.firstOrNull { step ->
                            step.kind in PlanValidator.APP_SCOPED_KINDS &&
                                (!step.params[PlanParams.APP].isNullOrBlank() ||
                                    !step.params[PlanParams.PACKAGE].isNullOrBlank())
                        },
                        summary = "Not done — $reason. " +
                            "Nothing was run, because the plan would have acted " +
                            "on the wrong screen.",
                    )
                    postTerminalStatus(plan.goal, gateOutcome)
                    return gateOutcome
                }
                else -> Unit
            }
            val outcome = runPlan(plan)
            postTerminalStatus(plan.goal, outcome)
            return outcome
        } finally {
            // Plan over, any outcome: release the pointer's plan
            // target so the idle pulse may resume (host-lifecycle
            // gap, 2026-11-06 — the executor is the single completion
            // path every host funnels through, so the clear lands here
            // for completed / failed / interrupted / thrown runs).
            // Display-only: a missed clear never fails the outcome.
            try {
                pointer.clearPlanTarget()
            } catch (_: Exception) {
            }
            // MASTER Wave 4 (F11 strengthening): plan over = no
            // verified target — the pointer rests at the neutral home
            // position, never parked on the last element (which may
            // be gone on the new screen). Display-only, like the clear.
            try {
                pointer.parkAtNeutralHome()
            } catch (_: Exception) {
            }
            analyzer.reset(planId)
            audit.log(planId, -1, "plan-finished", "goal=${plan.goal}")
        }
    }

    /**
     * The terminal task state for a finished run, mapped honestly
     * from the outcome — the five-state rule (2026-11-07):
     * COMPLETED only when every step verified; a run that verified
     * some steps and then failed or stopped is PARTIALLY_COMPLETED
     * (never "done"); a run blocked on a question for David
     * ([lastAskUserMessage] — target gone, or a step that needs
     * him) is WAITING_FOR_USER_INPUT; anything else with nothing
     * verified is FAILED. A dropped critical step (a replan that
     * abandoned it) can never complete — see [droppedCriticalStep].
     */
    private fun postTerminalStatus(goal: String, outcome: PlanOutcome) {
        val status = when {
            droppedCriticalStep != null -> TaskStatus.FAILED
            lastAskUserMessage != null -> TaskStatus.WAITING_FOR_USER_INPUT
            outcome.status == PlanStatus.COMPLETED -> TaskStatus.COMPLETED
            outcome.status == PlanStatus.INTERRUPTED && outcome.verifiedSteps > 0 ->
                TaskStatus.PARTIALLY_COMPLETED
            outcome.status == PlanStatus.FAILED && outcome.verifiedSteps > 0 ->
                TaskStatus.PARTIALLY_COMPLETED
            else -> TaskStatus.FAILED
        }
        postStatus(status, goal, outcome.summary)
    }

    /** Posts one status update; a throwing / missing sink never fails the plan. */
    private fun postStatus(status: TaskStatus, goal: String, summary: String) {
        val sink = statusSink ?: return
        try {
            sink.post(
                TaskStatusUpdate(
                    status = status,
                    message = taskStatusMessage(status, goal, summary),
                    goal = goal,
                ),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Device-failure fix (2026-11-13, F2) — per-step chat state
     * (David's standing rule): every step posts its outcome to
     * chat as it completes — verified/failed/skipped/replanned,
     * with the evidence — through the same status sink and the
     * same swallowing behaviour as [postStatus]. The message is
     * the step line itself ([taskStatusMessage] passes a non-blank
     * IN_PROGRESS summary through); hosts append it to the shared
     * conversation, so David sees the task's state step by step,
     * never only a terminal summary.
     */
    private fun postStepUpdate(plan: TaskPlan, step: PlanStep, totalSteps: Int, outcome: String) {
        postStatus(
            TaskStatus.IN_PROGRESS,
            plan.goal,
            "Step ${step.index + 1} of $totalSteps — ${step.description}: $outcome",
        )
    }

    // ---------- the run ----------

    private fun runPlan(initialPlan: TaskPlan): PlanOutcome {
        var currentPlan = initialPlan
        val verified = mutableListOf<PlanStep>()
        // Device-failure fix (2026-11-13, F2) — the evidence each
        // verified ACTION step produced (step, evidence text,
        // observed package). The outcome gate reads this: "Done"
        // requires a verified action whose evidence package is the
        // target app, and the terminal summary names the evidence.
        val actionEvidence = mutableListOf<Triple<PlanStep, String, String>>()
        var failedStep: PlanStep? = null
        var askUserMessage: String? = null
        // Device-failure fix (2026-11-13, F2): the reason a step
        // ABORTED the run (no-change stop, scroll bound, no
        // mechanism left) — the terminal summary names it, so a
        // NOT DONE names the exact reason the run stopped, not
        // just the step it stopped at.
        var abortReason: String? = null
        var status = PlanStatus.RUNNING
        // Run-level accounting (a replan rebuilds the tail, so the plan's
        // own step count stops describing the run): [totalSteps] starts
        // at the initial plan's size and grows by the replan's
        // genuinely new steps — the fresh OBSERVE_SCREEN the replan
        // prepends, plus a recovery step when the replan inserted one
        // (BUILT-IN RECOVERY, 2026-11-09); the remaining steps are
        // re-presented, not new work. [superseded] counts failed steps
        // a replan dropped. A run is COMPLETED only when every step
        // was verified or superseded.
        var totalSteps = initialPlan.steps.size
        var superseded = 0

        // AUTHORIZE — once per plan, before any action step. Guidance-only
        // plans (no action step) skip the card: there is nothing to
        // authorize and nothing to dispatch.
        val needsCover = currentPlan.steps.any { it.kind in ACTION_KINDS }
        if (needsCover) {
            audit.log(currentPlan.id, -1, "authorize", "requesting per-sequence cover")
            when (val coverResult = safeRequestCover(currentPlan)) {
                is CoverResult.GRANTED -> {
                    audit.log(currentPlan.id, -1, "authorize", "cover granted")
                    // Stop landed while the cover was being requested:
                    // the token is abandoned (never consumed, never
                    // used) and the plan is interrupted before any
                    // dispatch.
                    if (stop.isStopped()) {
                        return interruptedOutcome(
                            currentPlan, verified, totalSteps,
                            currentPlan.steps.firstOrNull(),
                        )
                    }
                    // Task-record fix (2026-11-07): approval flips
                    // ONLY the record's status (UNDERSTOOD → APPROVED).
                    // The SAME record — instruction, app, package,
                    // query — runs from here on; nothing is
                    // re-derived, re-parsed, or re-asked. The audit
                    // event names the approved app so the run is
                    // checkable after the fact.
                    currentPlan.record?.let { record ->
                        currentPlan = currentPlan.copy(record = record.approve())
                        audit.log(
                            currentPlan.id, -1, "record-approved",
                            "app=${record.app ?: "?"} status=${currentPlan.record?.status}",
                        )
                    }
                }
                is CoverResult.DENIED -> {
                    audit.log(currentPlan.id, -1, "authorize", "cover denied: ${coverResult.reason}")
                    return PlanOutcome(
                        status = PlanStatus.FAILED,
                        verifiedSteps = 0,
                        totalSteps = currentPlan.steps.size,
                        failedStep = currentPlan.steps.firstOrNull { it.kind in ACTION_KINDS },
                        summary = coverResult.reason,
                    )
                }
            }
        }

        // ACT loop — the step list is replaced wholesale by a replan
        // (OBSERVE + remaining steps, re-indexed); verified steps persist
        // across replans and are never re-run.
        var steps = currentPlan.steps
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            if (stop.isStopped()) {
                return interruptedOutcome(currentPlan, verified, totalSteps, step)
            }
            audit.log(currentPlan.id, step.index, "step-started", "${step.kind}: ${step.description}")
            when (val result = runStep(currentPlan, step)) {
                is StepRun.Done -> {
                    verified.add(result.step)
                    if (result.step.kind in ACTION_KINDS) {
                        actionEvidence.add(Triple(result.step, result.evidence, result.packageName))
                    }
                    audit.log(currentPlan.id, step.index, "step-verified", "${step.kind}")
                    postStepUpdate(
                        currentPlan, step, totalSteps,
                        "verified" + if (result.evidence.isNotBlank()) " — ${result.evidence}" else "",
                    )
                    i++
                }
                is StepRun.Skipped -> {
                    audit.log(currentPlan.id, step.index, "step-skipped", "${step.kind}: ${result.reason}")
                    postStepUpdate(currentPlan, step, totalSteps, "skipped — ${result.reason}")
                    i++
                }
                is StepRun.Replanned -> {
                    analyzer.noteReplan(currentPlan.id)
                    // Dependency-aware replan (2026-11-07): the
                    // repair-first replan RE-ISSUES the failed step,
                    // so it is not superseded — it gets fresh
                    // attempts. Only a step the replan actually
                    // DROPPED (second replan: abandoned with its
                    // dependents) counts as superseded, and a dropped
                    // CRITICAL step can never complete — it is
                    // recorded in [droppedCriticalStep] and the run
                    // ends FAILED naming it (the old code counted
                    // every dropped step as superseded and could
                    // still report COMPLETED with the core undone).
                    val dropped = result.failedStep
                    val stillPresent = result.newPlan.steps.any {
                        it.kind == dropped.kind && it.description == dropped.description
                    }
                    if (!stillPresent) {
                        if (dropped.critical) {
                            droppedCriticalStep = dropped
                        } else {
                            superseded++
                        }
                    }
                    // BUILT-IN RECOVERY (2026-11-09): every replan
                    // adds one genuinely new step (the fresh OBSERVE
                    // it prepends); a recovery replan adds one more —
                    // the recovery step itself (GO_BACK / clear tap).
                    // The old `totalSteps++` counted only the OBSERVE,
                    // so a recovery run verified MORE steps than the
                    // total named and reported FAILED after succeeding.
                    // A recovery step counts as new only when no step
                    // in the old plan matches it (kind + description).
                    totalSteps += 1 + result.newPlan.steps.count { newStep ->
                        newStep.params[PlanParams.RECOVERY] != null &&
                            currentPlan.steps.none {
                                it.kind == newStep.kind && it.description == newStep.description
                            }
                    }
                    audit.log(
                        currentPlan.id, step.index, "replan",
                        // Task-record fix: the audit names the record
                        // the replanned plan carries, so a replan that
                        // lost the app is checkable after the fact.
                        "remaining steps rebuilt (${result.newPlan.steps.size} steps) " +
                            "record app=${result.newPlan.record?.app ?: "?"} " +
                            if (stillPresent) "failed step re-issued for repair"
                            else "failed step dropped" +
                                (if (dropped.critical) " (critical)" else ""),
                    )
                    currentPlan = result.newPlan
                    steps = currentPlan.steps
                    postStepUpdate(
                        currentPlan, step, totalSteps,
                        "replanned — rebuilding the remaining steps after this step failed",
                    )
                    i = 0
                }
                is StepRun.Aborted -> {
                    failedStep = step
                    abortReason = result.reason
                    askUserMessage = result.askUserMessage
                    if (result.askUserMessage != null) {
                        lastAskUserMessage = result.askUserMessage
                    }
                    status = PlanStatus.FAILED
                    audit.log(currentPlan.id, step.index, "step-aborted", "${step.kind}: ${result.reason}")
                    postStepUpdate(currentPlan, step, totalSteps, "failed — ${result.reason}")
                    i = steps.size // end the loop
                }
                is StepRun.Interrupted -> {
                    return interruptedOutcome(currentPlan, verified, totalSteps, step)
                }
            }
        }

        // Honest completion, run-level (see the accounting note above —
        // buildOutcome counts one plan's steps, which stops describing
        // the run after a replan; the wording below mirrors it exactly
        // for the no-replan case). COMPLETED only when every step was
        // VERIFIED or superseded by a replan; skipped non-critical steps
        // leave the plan unconfirmed — FAILED with a summary naming the
        // verified count, never "Done".
        val outcome = when {
            droppedCriticalStep != null -> PlanOutcome(
                status = PlanStatus.FAILED,
                verifiedSteps = verified.size,
                totalSteps = totalSteps,
                failedStep = droppedCriticalStep,
                summary = "Not done — stopped at step ${droppedCriticalStep!!.index + 1} " +
                    "(${droppedCriticalStep!!.description}). " +
                    "Verified ${verified.size} of $totalSteps step(s); " +
                    "${droppedCriticalStep!!.description} could not be completed, " +
                    "and the steps after it depended on it, so the task is not done." +
                    pointerFailureSuffix(),
            )
            status == PlanStatus.FAILED && failedStep != null -> PlanOutcome(
                status = PlanStatus.FAILED,
                verifiedSteps = verified.size,
                totalSteps = totalSteps,
                failedStep = failedStep,
                summary = "Not done — stopped at step ${failedStep.index + 1} (${failedStep.description}). " +
                    "Verified ${verified.size} of $totalSteps step(s); " +
                    "${failedStep.description} was not completed" +
                    (abortReason?.let { " ($it)" } ?: "") +
                    ", so the task is not done." +
                    pointerFailureSuffix(),
            )
            // Device-failure fix (2026-11-13, F2) — the outcome
            // gate: every step verified is not enough when the plan
            // names a target app. "Done" additionally requires at
            // least one verified ACTION step whose evidence package
            // IS the target app. Without that, the run verified
            // screens that were never the app — the Nov 13 fake
            // completion — and reports NOT DONE naming the gap.
            // Plans naming no app (media controls, guidance) skip
            // the gate: there is no target package to require.
            verified.size + superseded == totalSteps && !outcomeGatePasses(currentPlan, actionEvidence) ->
                PlanOutcome(
                    status = PlanStatus.FAILED,
                    verifiedSteps = verified.size,
                    totalSteps = totalSteps,
                    failedStep = currentPlan.steps.firstOrNull { it.kind in ACTION_KINDS },
                    summary = "Not done — ${outcomeGateReason(currentPlan, actionEvidence)} " +
                        "Verified ${verified.size} of $totalSteps step(s), but none produced " +
                        "a verified effect in the target app, so the task is not done." +
                        pointerFailureSuffix(),
                )
            verified.size + superseded == totalSteps -> PlanOutcome(
                status = PlanStatus.COMPLETED,
                verifiedSteps = verified.size,
                totalSteps = totalSteps,
                failedStep = null,
                summary = if (superseded == 0) {
                    "Done — completed all $totalSteps step(s) for: ${currentPlan.goal}." +
                        evidenceSuffix(actionEvidence) +
                        pointerFailureSuffix()
                } else {
                    "Done — completed the task for: ${currentPlan.goal}. " +
                        "Verified ${verified.size} step(s); " +
                        "$superseded step(s) superseded by a replan." +
                        evidenceSuffix(actionEvidence) +
                        pointerFailureSuffix()
                },
            )
            else -> PlanOutcome(
                status = PlanStatus.FAILED,
                verifiedSteps = verified.size,
                totalSteps = totalSteps,
                failedStep = failedStep,
                summary = "Not done — finished with ${verified.size} of $totalSteps step(s) verified " +
                    "for: ${currentPlan.goal}. The remaining step(s) were not verified, " +
                    "so the task is not confirmed done." +
                    pointerFailureSuffix(),
            )
        }
        // AOIE Phase 7: a COMPLETED plan (every step verified) is
        // offered as a promotion CANDIDATE — evidence, not a fact.
        // The adapter decides whether it is worth proposing; nothing
        // is installed without David's approval.
        if (outcome.status == PlanStatus.COMPLETED) {
            try {
                verifiedWorkflowSink?.onVerifiedWorkflow(currentPlan)
            } catch (_: Exception) {
            }
        }
        return if (askUserMessage != null) {
            outcome.copy(summary = outcome.summary + " " + askUserMessage)
        } else {
            outcome
        }
    }

    /** One step: observe → identify → (pointer) → dispatch → observe → verify, with recovery. */
    private fun runStep(
        plan: TaskPlan,
        step: PlanStep,
    ): StepRun {
        var lastError = ""
        var lastAnalysis: PlanFailureAnalyzer.FailureAnalysis? = null
        // TRY_ALTERNATIVE retries re-dispatch the step with a new
        // submit order; [currentStep] carries that order in its
        // params. Every other path keeps the step as built.
        var currentStep = step
        // The analyzer counts attempts (max 3/step); the loop below runs
        // at most MAX_STEP_ATTEMPTS attempts and lets the analyzer's
        // decision end it sooner (ABORT / SKIP / REPLAN).
        repeat(PlanFailureAnalyzer.MAX_STEP_ATTEMPTS) {
            if (stop.isStopped()) return StepRun.Interrupted
            // MASTER Wave 3 (F14b) — bounded scrolls per run: the
            // 11th scroll in one plan never dispatches. The run
            // stops and reports honestly instead of scrolling forever.
            if (currentStep.kind == TaskStepKind.SCROLL || currentStep.kind == TaskStepKind.SWIPE) {
                scrollsThisRun++
                if (scrollsThisRun > StopLimits.MAX_SCROLLS_PER_SEARCH) {
                    recordStepOutcome(plan, currentStep, succeeded = false)
                    return StepRun.Aborted(
                        StopLimits.MAX_SCROLLS_STOPPING,
                        askUserMessage = null,
                    )
                }
            }
            val attemptBefore = safeObserve()
            val attempt = runAttempt(plan, currentStep)
            val attemptAfter = safeObserve()
            when (attempt) {
                is AttemptResult.Verified -> {
                    noChangeTracker.reset()
                    lastNoChangeSignature = null
                    recordStepOutcome(plan, currentStep, succeeded = true, screen = attemptBefore)
                    return StepRun.Done(
                        currentStep.copy(status = StepStatus.VERIFIED),
                        evidence = attempt.evidence,
                        packageName = attempt.packageName,
                    )
                }
                is AttemptResult.Failed -> {
                    // MASTER Wave 3 (F14d) — the no-change rule: a
                    // step whose dispatch produced no observable
                    // change twice in a row stops the run instead of
                    // retrying forever; any observable change resets
                    // the counter. (Widened 2026-11-13, F2: every
                    // no-change failure counts — see the else below.)
                    val changed = SearchScreenPredicates
                        .viewportChanged(attemptBefore, attemptAfter)
                    if (changed) {
                        noChangeTracker.reset()
                        lastNoChangeSignature = null
                    } else {
                        // Device-failure fix (2026-11-13, F2) —
                        // David's stop rule, widened (reconciliation
                        // §6): ANY failed attempt with no observable
                        // change counts, not only failures whose
                        // reason contains "no confirming
                        // observation". Two in a row stop the run
                        // and report, whatever shape the failure
                        // took. A pre-dispatch refusal with no
                        // change counts too — retrying it forever
                        // changes nothing. "In a row" means the
                        // SAME attempted step (kind + params): a
                        // step the analyzer changed — a rotated
                        // submit order, a replan re-issue — resets
                        // the streak, because the system is trying
                        // something different, not repeating.
                        val signature = currentStep.kind.name + "|" +
                            currentStep.params.entries.sortedBy { it.key }
                                .joinToString(",") { "${it.key}=${it.value}" }
                        if (signature != lastNoChangeSignature) {
                            noChangeTracker.reset()
                            lastNoChangeSignature = signature
                        }
                        if (noChangeTracker.observe(false)) {
                            recordStepOutcome(plan, currentStep, succeeded = false, screen = attemptBefore)
                            return StepRun.Aborted(
                                StopLimits.NO_CHANGE_STOPPING,
                                askUserMessage = null,
                            )
                        }
                    }
                    lastError = attempt.reason
                    val observation = safeObserve()
                    val analysis = analyzer.analyze(plan.id, currentStep, attempt.reason, observation)
                    lastAnalysis = analysis
                    when (analysis.decision) {
                        PlanFailureAnalyzer.FailureDecision.RETRY -> {
                            audit.log(plan.id, currentStep.index, "step-retry", analysis.reason)
                            // Loop: same step, same params, fresh observe.
                        }
                        is PlanFailureAnalyzer.FailureDecision.TRY_ALTERNATIVE -> {
                            val alternative = analysis.decision.alternative
                            // AOIE Phase 2 — dismiss-and-retry is REAL now:
                            // the keyboard is actually dismissed (global
                            // Back) and the step is retried against the
                            // fresh screen. If the dismiss itself is
                            // refused, fall through to the submit-order
                            // rotation below instead of failing outright.
                            // (No `continue` here: this block runs inside
                            // repeat{}, so falling out of the when ends
                            // the attempt and the loop re-runs the step.)
                            var dismissHandled = false
                            if (alternative == "dismiss-and-retry") {
                                dismissHandled = true
                                when (val dismissed = safeDismissKeyboard()) {
                                    is StepDispatchResult.DISPATCHED -> {
                                        val afterDismiss = safeObserve()
                                        if (afterDismiss != null && !afterDismiss.keyboardVisible) {
                                            audit.log(
                                                plan.id, currentStep.index, "step-dismiss-retry",
                                                "keyboard dismissed — retrying the step",
                                            )
                                        } else {
                                            audit.log(
                                                plan.id, currentStep.index, "step-dismiss-retry",
                                                "dismissed but the keyboard is still showing — retrying anyway",
                                            )
                                        }
                                    }
                                    else -> {
                                        audit.log(
                                            plan.id, currentStep.index, "step-dismiss-failed",
                                            "keyboard dismiss refused — rotating the submit order instead",
                                        )
                                    }
                                }
                                // Search steps rotate the submit order as
                                // well (the dismiss alone may not have
                                // been enough); other kinds just retry.
                                if (currentStep.kind == TaskStepKind.SEARCH_IN_APP) {
                                    when (val advanced = advanceSubmitOrder(plan, currentStep)) {
                                        null -> {
                                            recordStepOutcome(plan, currentStep, succeeded = false, screen = attemptBefore)
                                            return StepRun.Aborted(
                                                "No submit mechanism left to try for " +
                                                    "\"${currentStep.description}\" — the search never ran.",
                                                askUserMessage = null,
                                            )
                                        }
                                        else -> {
                                            currentStep = advanced
                                            audit.log(
                                                plan.id, currentStep.index, "step-try-alternative",
                                                "retrying with the remaining submit order ($alternative)",
                                            )
                                        }
                                    }
                                }
                            }
                            if (!dismissHandled) {
                                if (currentStep.kind == TaskStepKind.SEARCH_IN_APP) {
                                    when (val advanced = advanceSubmitOrder(plan, currentStep)) {
                                        null -> {
                                            recordStepOutcome(plan, currentStep, succeeded = false, screen = attemptBefore)
                                            return StepRun.Aborted(
                                                "No submit mechanism left to try for " +
                                                    "\"${currentStep.description}\" — the search never ran.",
                                                askUserMessage = null,
                                            )
                                        }
                                        else -> {
                                            currentStep = advanced
                                            audit.log(
                                                plan.id, currentStep.index, "step-try-alternative",
                                                "retrying with the remaining submit order ($alternative)",
                                            )
                                            // Loop: same step, next mechanisms, fresh observe.
                                        }
                                    }
                                } else {
                                    // No alternative order applies to this
                                    // kind — retry the same step once more.
                                    audit.log(plan.id, currentStep.index, "step-try-alternative", analysis.reason)
                                }
                            }
                        }
                        PlanFailureAnalyzer.FailureDecision.SKIP -> {
                            if (!currentStep.critical) {
                                return StepRun.Skipped(analysis.reason)
                            }
                            // Critical steps never skip (analyzer guarantees
                            // this, but never trust a decision blindly).
                            recordStepOutcome(plan, currentStep, succeeded = false, screen = attemptBefore)
                            return StepRun.Aborted(
                                "Critical step could not be skipped: ${analysis.reason}",
                                askUserMessage = null,
                            )
                        }
                        PlanFailureAnalyzer.FailureDecision.REPLAN -> {
                            when (val replanned = safeReplan(plan, currentStep, attempt.reason, observation)) {
                                is TaskPlanner.PlanResult.Planned -> {
                                    // KNOWLEDGE BASE (2026-11-09): a
                                    // GO_BACK recovery that failed is
                                    // remembered for its screen, so the
                                    // next replan for the same screen
                                    // does not repeat it. (A step that
                                    // replans records nothing in
                                    // recordStepOutcome — its fate is
                                    // undecided — so the recovery
                                    // outcome is recorded here, where
                                    // the decision to move past it is
                                    // made.)
                                    if (currentStep.params[PlanParams.RECOVERY] ==
                                        PlanParams.RECOVERY_GO_BACK
                                    ) {
                                        try {
                                            attemptBefore?.let { screen ->
                                                knowledge.recordBackRecovery(screen, false)
                                            }
                                        } catch (_: Exception) {
                                        }
                                    }
                                    return StepRun.Replanned(replanned.plan, currentStep)
                                }
                                else -> {
                                    val reason = when (replanned) {
                                        is TaskPlanner.PlanResult.Unsupported -> replanned.reason
                                        is TaskPlanner.PlanResult.ClarificationNeeded -> replanned.question
                                        is TaskPlanner.PlanResult.Refused -> replanned.reason
                                        is TaskPlanner.PlanResult.RefusedSensitive -> replanned.reason
                                        else -> "Could not rebuild the remaining steps."
                                    }
                                    recordStepOutcome(plan, currentStep, succeeded = false, screen = attemptBefore)
                                    return StepRun.Aborted(reason, askUserMessage = null)
                                }
                            }
                        }
                        PlanFailureAnalyzer.FailureDecision.ABORT -> {
                            recordStepOutcome(plan, currentStep, succeeded = false, screen = attemptBefore)
                            return StepRun.Aborted(
                                analysis.reason,
                                askUserMessage = if (analysis.askUser) analysis.userMessage else null,
                            )
                        }
                    }
                }
            }
        }
        // Attempts exhausted without a decision ending the loop: abort
        // honestly (the analyzer also aborts at max attempts).
        recordStepOutcome(plan, currentStep, succeeded = false)
        return StepRun.Aborted(
            lastError.ifBlank { "Step failed ${PlanFailureAnalyzer.MAX_STEP_ATTEMPTS} times." },
            askUserMessage = lastAnalysis?.takeIf { it.askUser }?.userMessage,
        )
    }

    /**
     * Advances the per-plan submit-mechanism cursor for a SEARCH_IN_APP
     * step and returns the step with the remaining order in its
     * [PlanParams.SUBMIT_ORDER] param. Null when no mechanism is left.
     * The base order is the app's learned preferred order (or the
     * default when the app is unknown).
     */
    private fun advanceSubmitOrder(plan: TaskPlan, step: PlanStep): PlanStep? {
        // BUILT-IN RECOVERY (2026-11-09): continue from the order
        // the step is ACTUALLY running with. A replanned step carries
        // its advanced order in its params — and the cursor below is
        // keyed by plan id + step index, so the re-issue's NEW index
        // restarts the cursor at zero and re-tries an already-tried
        // mechanism (the second submit failure replans, the re-issue
        // re-ran KEYBOARD_ACTION_KEY twice). Steps without a param
        // (the first rotation) still use the profile base + cursor.
        val fromParam = step.params[PlanParams.SUBMIT_ORDER]
            ?.split(",")
            ?.mapNotNull { name -> runCatching { SubmitMechanism.valueOf(name.trim()) }.getOrNull() }
            ?.drop(1)
            ?.takeIf { it.isNotEmpty() }
        if (fromParam != null) {
            return step.copy(
                params = step.params + (PlanParams.SUBMIT_ORDER to fromParam.joinToString(",") { it.name }),
            )
        }
        val key = "${plan.id}:${step.index}"
        val cursor = (submitCursor[key] ?: 0) + 1
        submitCursor[key] = cursor
        val pkg = step.params[PlanParams.PACKAGE].orEmpty()
        val base = if (pkg.isBlank()) DEFAULT_SUBMIT_ORDER else profiles.preferredOrder(pkg)
        val remaining = base.drop(cursor)
        if (remaining.isEmpty()) return null
        return step.copy(
            params = step.params + (PlanParams.SUBMIT_ORDER to remaining.joinToString(",") { it.name }),
        )
    }

    /**
     * Records a terminal step outcome into the app profiles via the
     * learner (success / failure per mechanism where known) and into
     * the knowledge base (per-screen submit wins, back-recovery
     * results). Only SEARCH_IN_APP and SUBMIT steps carry a submit
     * mechanism; other kinds record nothing — except a GO_BACK
     * recovery step, whose outcome the knowledge base remembers per
     * screen. The mechanism is the PRIMARY one — the first of the
     * order the step ran with. The chain tries several, so this is
     * recorded as "the order starting with X worked / failed", not
     * "X alone worked". A step that replans records nothing: its
     * fate is undecided until the rebuilt tail runs.
     *
     * [screen] is the screen the step acted on (the attempt's
     * before-screen) — the win belongs to THAT screen, not to
     * whatever the run landed on afterwards. Null falls back to a
     * fresh observation, as before.
     */
    private fun recordStepOutcome(
        plan: TaskPlan,
        step: PlanStep,
        succeeded: Boolean,
        screen: ScreenModel? = null,
    ) {
        // KNOWLEDGE BASE (2026-11-09): a recovery GO_BACK step's
        // outcome is remembered per screen — a failed back recovery
        // is not repeated for the same screen; a verified one
        // clears the flag.
        if (step.params[PlanParams.RECOVERY] == PlanParams.RECOVERY_GO_BACK) {
            val observed = screen ?: safeObserve() ?: return
            try {
                knowledge.recordBackRecovery(observed, succeeded)
            } catch (_: Exception) {
            }
            return
        }
        if (step.kind != TaskStepKind.SEARCH_IN_APP && step.kind != TaskStepKind.SUBMIT) return
        val observed = screen ?: safeObserve() ?: return
        val pkg = step.params[PlanParams.PACKAGE].orEmpty().ifBlank { observed.packageName }
        if (pkg.isBlank()) return
        val mechanism = when (step.kind) {
            TaskStepKind.SEARCH_IN_APP -> {
                step.params[PlanParams.SUBMIT_ORDER]?.split(",")?.firstOrNull()
                    ?.let { name -> runCatching { SubmitMechanism.valueOf(name.trim()) }.getOrNull() }
                    ?: profiles.preferredOrder(pkg).firstOrNull()
                    ?: SubmitMechanism.IME_ENTER
            }
            TaskStepKind.SUBMIT -> SubmitMechanism.IME_ENTER
            else -> return
        }
        try {
            learner.learn(
                model = observed,
                packageName = pkg,
                label = step.params[PlanParams.APP].orEmpty(),
                mechanism = mechanism,
                succeeded = succeeded,
            )
            // KNOWLEDGE BASE: the winning mechanism is also recorded
            // per screen, so the next plan for THIS screen tries it
            // first instead of re-deriving the order.
            knowledge.recordSubmitWin(observed, mechanism, succeeded)
        } catch (_: Exception) {
        }
    }

    /** One attempt at one step. Returns Verified only on fresh-observation proof. */
    private fun runAttempt(plan: TaskPlan, step: PlanStep): AttemptResult {
        // OBSERVE (fresh) + IDENTIFY (fresh resolution against it).
        val beforeRaw = safeObserve()
            ?: return AttemptResult.Failed("Could not read the screen — the screen went unreadable.")
        // KNOWLEDGE BASE (2026-11-09): every screen LOOM sees is
        // recorded — what it is, what it affords, where its controls
        // are, how to go back and clear it. Silent bookkeeping: a
        // recording failure never fails the attempt it observes.
        try {
            knowledge.recordObservation(beforeRaw)
        } catch (_: Exception) {
        }
        // NAV-SCREEN fix (2026-11-11, N5 + <1s budget): a navigation
        // step screenshots the screen (consent-gated, task-scoped)
        // and runs the BOUNDED detection for its destination — the
        // detection time is audit-logged per step. Any other step
        // keeps the tree-only observation unchanged.
        val before = captureVisualForNav(plan, step, beforeRaw)
        // App-aware execution (B3i): classify the current screen
        // BEFORE dispatching. A dialog, or the wrong app in the
        // foreground, means do NOT dispatch — fail with an
        // "unexpected screen" reason so the analyzer replans (or
        // stops honestly). LAUNCH_APP / WAIT_FOR_APP are exempt: their
        // job is to GET to the app, so the current screen cannot be
        // the expected one yet. GO_BACK is exempt for the mirror
        // reason (BUILT-IN RECOVERY, 2026-11-09): its job is to
        // LEAVE the wrong screen — refusing it on a dialog or a
        // wrong page would make the go-back recovery un-runnable,
        // and going back from any screen is safe (GLOBAL_ACTION_BACK).
        // Read-only steps need no check either — they fail honestly
        // on their own terms.
        if (step.kind in ACTION_KINDS && step.kind != TaskStepKind.LAUNCH_APP &&
            step.kind != TaskStepKind.GO_BACK
        ) {
            preStepScreenCheck(step, before)?.let { reason ->
                return AttemptResult.Failed(reason)
            }
        }
        // MASTER Wave 3 (F14a) — results-ready predicate as a hard
        // stop: a SEARCH_IN_APP step whose goal is SHOW_RESULTS is
        // already COMPLETE when results matching the query are on
        // screen — it must never re-type, re-tap or re-submit. The
        // step verifies without dispatching. (OPEN_FIRST_RESULT does
        // NOT short-circuit here: the result still has to be opened,
        // and the search sequence skips its own type/submit when the
        // results are already showing.)
        if (step.kind == TaskStepKind.SEARCH_IN_APP &&
            step.params[PlanParams.SEARCH_GOAL] == PlanParams.SEARCH_GOAL_SHOW_RESULTS
        ) {
            val query = step.params[PlanParams.QUERY].orEmpty()
            // Device-failure fix (2026-11-13, F2) — the F14a
            // short-circuit is GUARDED, not removed: it verifies
            // without dispatching ONLY when the step names its
            // package, the FRESH observation is in that package,
            // and the package-gated results predicate holds on
            // it. A blank PACKAGE, a wrong-package screen (LOOM's
            // own chat included), or a non-results screen falls
            // through to dispatch. The guard cannot be proven
            // sound any looser than this — package + results,
            // both true, on a fresh observation.
            val pkg = step.params[PlanParams.PACKAGE].orEmpty()
            if (query.isNotBlank() && pkg.isNotBlank() && before.packageName == pkg &&
                searchResultsShown(before, query, null, pkg)
            ) {
                audit.log(plan.id, step.index, "search-already-showing", query)
                return AttemptResult.Verified(
                    evidence = "results for \"$query\" already showing in $pkg",
                    packageName = pkg,
                )
            }
        }
        // SEARCH_IN_APP runs with the app's learned submit order on
        // the first attempt too (not only on TRY_ALTERNATIVE retries).
        // KNOWLEDGE BASE (2026-11-09): the order for a KNOWN screen
        // puts the mechanism that last worked HERE first; an unknown
        // screen uses the package profile order unchanged.
        val stepToRun = if (step.kind == TaskStepKind.SEARCH_IN_APP &&
            step.params[PlanParams.SUBMIT_ORDER] == null
        ) {
            val pkg = step.params[PlanParams.PACKAGE].orEmpty()
            val order = if (pkg.isBlank()) DEFAULT_SUBMIT_ORDER
            else knowledge.preferredSubmitOrderForScreen(before, profiles.preferredOrder(pkg))
            step.copy(params = step.params + (PlanParams.SUBMIT_ORDER to order.joinToString(",") { it.name }))
        } else {
            step
        }
        // POINTER — the pointer moves first, then LOOM acts. Every step
        // with an on-screen target drives the pointer there (moveTo +
        // showIntended) before dispatch; steps without a target only
        // narrate in the pill. A TAP_TARGET without a verified target
        // never dispatches; a pointer-display failure never fails the
        // step — it is recorded and named in the outcome instead.
        var resolvedStep = stepToRun
        var drivenBounds: TargetBounds? = null
        var drivenTargetId: String? = null
        if (step.kind in POINTER_DRIVE_KINDS) {
            when (val driven = drivePointerForStep(plan, stepToRun, before)) {
                is PointerDrive.TargetFailed -> return AttemptResult.Failed(driven.reason)
                is PointerDrive.Driven -> {
                    resolvedStep = driven.step
                    drivenBounds = driven.bounds
                    drivenTargetId = driven.targetId
                }
            }
        }
        // Screen navigation (2026-11-10): a scroll hunt that found
        // the target leaves the hunted screen behind — the tap below
        // must re-resolve against THAT screen, and verification
        // compares against it, not the pre-hunt screen. Consumed
        // once per attempt; a hunt that found nothing leaves it null.
        val effectiveBefore = lastHuntScreen ?: before
        lastHuntScreen = null

        // Cover check (audit only — the dispatcher requests its own
        // per-step token for steps the cover does not span; see
        // PlanStepDispatcher. A step never dispatches without either the
        // cover or the dispatcher's own token request).
        if (step.kind in ACTION_KINDS) {
            val covered = try {
                cover.checkCover(resolvedStep)
            } catch (_: Exception) {
                false
            }
            audit.log(
                plan.id, step.index, "step-authorized",
                if (covered) "covered by the approved plan" else "own per-step check",
            )
        }

        if (stop.isStopped()) return AttemptResult.Failed("Stopped before dispatch.")

        // ACT. TAP_TARGET goes through the PointerTapCoordinator: the
        // pointer moves to the verified bounds and the tap is issued AT
        // those exact bounds — tap point == pointer position, by
        // construction. Other kinds dispatch directly. NAV-SCREEN fix
        // (2026-11-11, N1): NAVIGATE_TAB takes the same coordinated
        // path — its tap lands exactly where the pointer parked on
        // the tab.
        val dispatchResult = if ((step.kind == TaskStepKind.TAP_TARGET ||
                step.kind == TaskStepKind.NAVIGATE_TAB) && drivenBounds != null) {
            val (result, evidence) = tapCoordinator.coordinateTap(
                drivenBounds, drivenTargetId.orEmpty(), pointer,
            ) { bounds ->
                dispatcher.dispatch(resolvedStep, effectiveBefore)
            }
            audit.log(
                plan.id, resolvedStep.index, "pointer-tap",
                "tap at ${evidence.tapPoint.left},${evidence.tapPoint.top} " +
                    "arrived=${evidence.arrivedBeforeTap} " +
                    (evidence.pointerFailure?.let { "pointer-failed: $it" } ?: "pointer-ok"),
            )
            // A draw failure inside the coordinated tap names the step
            // in the outcome (display failure ≠ action failure, but it
            // is never silent).
            evidence.pointerFailure?.let { notePointerFailureReason(plan, resolvedStep, it) }
            result
        } else {
            try {
                dispatcher.dispatch(resolvedStep, effectiveBefore)
            } catch (e: Exception) {
                return AttemptResult.Failed("Dispatch failed: ${e.message ?: "unknown error"}")
            }
        }
        when (dispatchResult) {
            is StepDispatchResult.FAILED -> {
                recordTrace(
                    plan, resolvedStep, "failed",
                    VerificationResult(
                        actionId = resolvedStep.actionId,
                        expected = resolvedStep.description,
                        observed = dispatchResult.reason,
                        evidence = "dispatch failed",
                        confidence = 0.0,
                        verdict = VerificationResult.Verdict.FAIL,
                        nextStep = "retry or replan",
                    ),
                )
                return AttemptResult.Failed(dispatchResult.reason)
            }
            is StepDispatchResult.REFUSED -> {
                recordTrace(
                    plan, resolvedStep, "refused",
                    VerificationResult(
                        actionId = resolvedStep.actionId,
                        expected = resolvedStep.description,
                        observed = dispatchResult.reason,
                        evidence = "gate or policy refused",
                        confidence = 0.0,
                        verdict = VerificationResult.Verdict.FAIL,
                        nextStep = "abort — a refusal is never retried blind",
                    ),
                )
                return AttemptResult.Failed("refused: ${dispatchResult.reason}")
            }
            StepDispatchResult.DISPATCHED -> Unit // accepted — not success yet
        }

        // OBSERVE AGAIN + VERIFY against a fresh snapshot. Dispatch-only
        // success is NEVER counted as verified.
        //
        // Mark 57 transfer (2026-11-11, M3) — NAVIGATE_TAB verification
        // waits for the screen to SETTLE first: [ScreenDigest.waitStable]
        // polls fresh observations until the position-blind stable
        // digest is unchanged for 350 ms (max 6 s), instead of a
        // fixed sleep (always wrong twice — too long on an instant
        // dialog, too short on a slow app, and the short case is the
        // misclick / the false "screen unchanged"). A tab switch that
        // is still animating when the verifier reads it is the false
        // FAIL this removes. On timeout the LAST reading is used
        // rather than refusing — an animated page still has a screen
        // to verify against. Other step kinds keep the immediate
        // re-observe (their verifiers already tolerate transition
        // frames); only navigation waits.
        val after = if (resolvedStep.kind == TaskStepKind.NAVIGATE_TAB) {
            val settled = try {
                ScreenDigest.waitStable({ safeObserve() })
            } catch (_: Exception) {
                null
            }
            val model = when (settled) {
                is ScreenDigest.WaitStableResult.Settled -> settled.model
                is ScreenDigest.WaitStableResult.TimedOut -> settled.model
                null -> null
            }
            model ?: return AttemptResult.Failed(
                "no confirming observation after dispatch — the screen went unreadable",
            )
        } else {
            safeObserve()
                ?: return AttemptResult.Failed("no confirming observation after dispatch — the screen went unreadable")
        }
        // KNOWLEDGE BASE: the screen LOOM landed on is recorded too.
        try {
            knowledge.recordObservation(after)
        } catch (_: Exception) {
        }
        // AOIE Phase 1: the typed verdict is recorded in the trace
        // (with its confidence); the VERIFIED decision still uses
        // the existing Boolean check, so no behaviour changes here.
        // Screen confirmation (2026-11-10): the typed verdict and
        // the Boolean must agree — a step that cannot be verified
        // (e.g. TYPE_TEXT on an unreadable field) fails in both, so
        // an unverified step is never counted as done in the trace
        // or in the run decision.
        val typed = verifyStepTyped(resolvedStep, effectiveBefore, after)
        recordTrace(plan, resolvedStep, "dispatched", typed)
        return if (verifyStep(resolvedStep, effectiveBefore, after)) {
            // Device-failure fix (2026-11-13, F2) — pointer-display
            // gating: an operation step whose pointer failed to
            // display is UNVERIFIED even though the screen check
            // passed. The failure is the per-step record
            // (notePointerFailure), never a stale last-call value.
            pointerGateFailure(plan, resolvedStep)?.let { reason ->
                return AttemptResult.Failed(reason)
            }
            AttemptResult.Verified(
                evidence = typed.observed,
                packageName = after.packageName,
            )
        } else {
            AttemptResult.Failed(verifyFailureReason(resolvedStep, effectiveBefore, after))
        }
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N5 + <1s budget, David binding
     * Nov 11 ~19:43) — screenshot + bounded detection for a
     * navigation step, and only for a navigation step.
     *
     *  1. The gate is granted for this plan's task (the navigation
     *     David asked for, under its approved cover) and the capture
     *     runs through it. No grant / no visual source / a failed
     *     capture → the tree-only screen is returned unchanged —
     *     navigation never depends on the frame.
     *  2. A captured frame is attached to the observation (source
     *     becomes "accessibility+vision").
     *  3. The BOUNDED detection runs for the step's destination and
     *     its measured time is audit-logged (per detection, worst
     *     case included in the final report). A TimedOut detection
     *     is logged, never hidden — the step still proceeds on the
     *     tree, which stays the primary perception source.
     * The grant is revoked before returning either way: one capture
     * per grant, never a standing permission.
     */
    private fun captureVisualForNav(plan: TaskPlan, step: PlanStep, screen: ScreenModel): ScreenModel {
        if (step.kind != TaskStepKind.NAVIGATE_TAB) return screen
        val target = step.params[PlanParams.TARGET].orEmpty()
        var withVisual = screen
        try {
            val source = visualSource
            if (source != null) {
                screenshotGate.grantForTask(plan.id)
                when (val captured = screenshotGate.capture(plan.id) { source() }) {
                    is ScreenshotCaptureState.Captured -> {
                        withVisual = screen.copy(
                            visual = captured.frame,
                            source = "accessibility+vision",
                        )
                        audit.log(
                            plan.id, step.index, "screenshot-captured",
                            "frame ${captured.frame.width}x${captured.frame.height}",
                        )
                    }
                    is ScreenshotCaptureState.Failed -> {
                        audit.log(plan.id, step.index, "screenshot-failed", captured.reason)
                    }
                    else -> Unit
                }
            }
            if (target.isNotBlank()) {
                val detection = try {
                    VisualDetection.detectDestination(withVisual, target)
                } catch (_: Exception) {
                    null
                }
                if (detection != null) {
                    val detail = "destination=\"$target\" elapsedMs=${detection.elapsedMs} " +
                        "examined=${detection.examined} result=${detection::class.simpleName}"
                    audit.log(plan.id, step.index, "screenshot-detection", detail)
                    if (detection is VisualDetection.DetectionResult.TimedOut) {
                        audit.log(
                            plan.id, step.index, "screenshot-detection-timed-out",
                            "detection exceeded the ${VisualDetection.ABORT_AT_MS}ms abort " +
                                "inside the ${VisualDetection.DETECTION_BUDGET_MS}ms budget — " +
                                "proceeding on the tree only",
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // A capture/detection failure never fails the step — the
            // tree is the primary source and the step proceeds on it.
        } finally {
            screenshotGate.revoke()
        }
        return withVisual
    }

    /**
     * App-aware execution (B3i): the pre-dispatch screen check. Null
     * means "go ahead".
     *
     *  - Wrong foreground package: only TYPE_TEXT / SUBMIT /
     *    FOCUS_FIELD are blocked — they inject input into the
     *    CURRENT screen and have no app-transition path. LAUNCH_APP
     *    gets to the app; SEARCH_IN_APP launches it itself;
     *    TAP_TARGET may legitimately target another app (the
     *    dispatcher's out-of-scope path, with its own confirmation —
     *    blocking it would break the per-sequence cover's
     *    out-of-scope flow). GO_BACK / SCROLL act wherever they land
     *    and fail honestly on their own terms.
     *  - A dialog showing: no action step dispatches through it.
     *  - SEARCH_IN_APP on a composer / settings screen: cannot
     *    search from there.
     */
    private fun preStepScreenCheck(step: PlanStep, screen: ScreenModel): String? {
        // MASTER Wave 3 (F9) — PerceptionCoordinator confidence is
        // wired into the live path here: below the threshold the
        // executor stops instead of guessing. An empty tree (0.3)
        // never authorizes an action step; an UNKNOWN-kind screen
        // (0.7) is exactly at the threshold and may proceed.
        val confidence = PerceptionCoordinator.confidenceFor(screen)
        if (confidence < PerceptionCoordinator.MIN_ACTION_CONFIDENCE) {
            return "the screen is too uncertain to act on " +
                "(confidence $confidence) — stopping instead of guessing"
        }
        val expectedPkg = step.params[PlanParams.PACKAGE]
        if (step.kind in setOf(TaskStepKind.TYPE_TEXT, TaskStepKind.SUBMIT, TaskStepKind.FOCUS_FIELD) &&
            !expectedPkg.isNullOrBlank() && screen.packageName != expectedPkg
        ) {
            return "unexpected screen: expected $expectedPkg but " +
                "${screen.packageName} is in the foreground"
        }
        val signature = try {
            ScreenClassifier.classify(screen, step.params[PlanParams.QUERY])
        } catch (_: Exception) {
            null
        } ?: return null
        if (signature.kind == ScreenKind.DIALOG) {
            return "unexpected screen: a dialog is showing"
        }
        if (step.kind == TaskStepKind.SEARCH_IN_APP &&
            signature.kind in setOf(ScreenKind.COMPOSER, ScreenKind.SETTINGS)
        ) {
            return "unexpected screen: ${signature.kind} — cannot search from here"
        }
        return null
    }

    /**
     * The verification-failure reason. For SEARCH_IN_APP / SUBMIT
     * steps the failure is classified from the before/after screens
     * via the analyzer's submit classifier, so the reason carries
     * the marker the analyzer's TRY_ALTERNATIVE rules match
     * ("submit not executed" / "keyboard still open") instead of a
     * generic "no confirming observation".
     */
    private fun verifyFailureReason(step: PlanStep, before: ScreenModel, after: ScreenModel): String {
        val generic = "no confirming observation after dispatch — " +
            "the screen did not show the expected effect of \"${step.description}\""
        // Device-failure fix (2026-11-13, F2): when the step names
        // a package and the screen is a different one, the reason
        // says so plainly — the NOT DONE summary names the wrong
        // screen instead of a generic miss. GO_BACK is exempt: it
        // leaves the app by design.
        val wantPkg = step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() }
        if (wantPkg != null && step.kind != TaskStepKind.GO_BACK && after.packageName != wantPkg) {
            return "wrong app on screen: expected $wantPkg but the foreground package is " +
                "${after.packageName.ifBlank { "unknown" }} — $generic"
        }
        val submitClass = when {
            step.kind == TaskStepKind.SEARCH_IN_APP -> {
                val query = step.params[PlanParams.QUERY].orEmpty()
                if (query.isBlank()) null
                else analyzer.classifySubmitFailure(before, after, query)
            }
            step.kind == TaskStepKind.SUBMIT && after.keyboardVisible ->
                PlanFailureAnalyzer.ErrorClass.KEYBOARD_STILL_OPEN
            else -> null
        }
        return when (submitClass) {
            PlanFailureAnalyzer.ErrorClass.KEYBOARD_STILL_OPEN ->
                "keyboard still open after submit — $generic"
            PlanFailureAnalyzer.ErrorClass.SUBMIT_NOT_EXECUTED ->
                "submit not executed — $generic"
            else -> generic
        }
    }

    // ---------- pointer drive (David: the pointer is LOOM's hand) ----------

    /**
     * Screen navigation (2026-11-10, David's rule): scrolls down to
     * look for a TAP_TARGET that is not on the current screen, so a
     * target below the fold is found instead of reported as gone.
     *
     * Bounded by the Master P0 stop rule ([StopLimits]): at most
     * [StopLimits.MAX_SCROLLS_PER_SEARCH] scrolls, and a scroll that
     * changes nothing twice in a row stops the hunt ([NoChangeTracker]
     * — the end of the list). Each scroll is a synthetic SCROLL step
     * with no PACKAGE param, so the live plan cover spans it exactly
     * as it spans the plan's own steps — no extra confirmation card
     * per scroll for a plan David approved. A scroll that is refused
     * or a stop that lands ends the hunt honestly; the caller then
     * fails the step with the target-gone reason, never with a
     * guessed target.
     *
     * Returns the screen the target was found on plus its matches,
     * or null when the target was not found within the bounds.
     */
    private fun scrollHuntForTarget(
        plan: TaskPlan,
        step: PlanStep,
        startScreen: ScreenModel,
    ): Pair<ScreenModel, List<ScreenElement>>? {
        val target = step.params[PlanParams.TARGET].orEmpty()
        if (target.isBlank()) return null
        // NAV-SCREEN fix (2026-11-11, N8): NEVER scroll the feed
        // looking for a navigation target. The bottom nav is already
        // visible and never moves; scrolling it away is pointless.
        // A chrome target that is not resolved where it is fails
        // honestly, with the chrome-aware reason.
        if (NavigationResolver.isChromeQuery(target)) {
            audit.log(
                plan.id, step.index, "scroll-hunt-skipped-chrome",
                "\"$target\" is a navigation target — the navigation bar does not scroll, so no hunt ran",
            )
            return null
        }
        // Nothing to scroll: a screen with no scrollable element and
        // no list cannot reveal a target below the fold.
        if (startScreen.elements.none { it.scrollable || it.role == ElementRole.LIST }) {
            return null
        }
        var current = startScreen
        var lastSignature = SearchScreenPredicates.viewportSignature(current)
        val noChange = NoChangeTracker()
        var scrolls = 0
        while (scrolls < StopLimits.MAX_SCROLLS_PER_SEARCH) {
            if (stop.isStopped()) return null
            val scrollStep = PlanStep(
                index = step.index,
                kind = TaskStepKind.SCROLL,
                description = "Scroll down to look for \"$target\"",
                params = mapOf(PlanParams.DIRECTION to "down"),
            )
            val result = try {
                dispatcher.dispatch(scrollStep, current)
            } catch (_: Exception) {
                null
            }
            if (result !is StepDispatchResult.DISPATCHED) return null
            scrolls++
            val after = safeObserve() ?: return null
            val signature = SearchScreenPredicates.viewportSignature(after)
            if (noChange.observe(signature == lastSignature)) {
                audit.log(
                    plan.id, step.index, "scroll-hunt-end",
                    "viewport unchanged after $scrolls scroll(s) — ${StopLimits.REACHED_END_NOT_FOUND}",
                )
                return null
            }
            lastSignature = signature
            val found = findTargetElements(after, target)
            if (found.isNotEmpty()) {
                audit.log(
                    plan.id, step.index, "scroll-hunt-found",
                    "found \"$target\" after $scrolls scroll(s)",
                )
                return after to found
            }
            current = after
        }
        audit.log(plan.id, step.index, "scroll-hunt-end", StopLimits.MAX_SCROLLS_STOPPING)
        return null
    }

    /**
     * Drives the pointer for one step, BEFORE dispatch.
     *
     * TAP_TARGET keeps its strict target rules (blank / gone /
     * ambiguous fails the step — a tap without a verified target never
     * dispatches). Every other kind resolves its target best-effort:
     * no on-screen target is not a step failure, the step just
     * narrates in the pill instead of drawing a fake outline.
     */
    private fun drivePointerForStep(
        plan: TaskPlan,
        step: PlanStep,
        screen: ScreenModel,
    ): PointerDrive {
        // NAV-SCREEN fix (2026-11-11, N1/N2): a NAVIGATE_TAB step
        // resolves its destination through the tab resolver first
        // (label → synonym → ordinal → positional); a TAP_TARGET
        // keeps its text resolution (which now includes the nav
        // fallback tier for known destinations).
        if (step.kind == TaskStepKind.TAP_TARGET || step.kind == TaskStepKind.NAVIGATE_TAB) {
            val target = step.params[PlanParams.TARGET].orEmpty()
            if (target.isBlank()) {
                return PointerDrive.TargetFailed("No target to tap — the step names nothing.")
            }
            // Device-test failures (2026-11-11, D-01/D-08): never
            // resolve a tap against the wrong app's screen. A step
            // that names its app (PACKAGE) runs only when that app
            // is foreground; and a tap for the plan's recorded app
            // never resolves against LOOM's own chat screen, whose
            // bubbles echo the task words ("tap my story") and would
            // park the pointer on chat text instead of the thing in
            // the app. Failing as "target gone" is deliberate: the
            // planner's replan then brings the app up and re-issues
            // the tap, instead of tapping an echo. A step with no
            // app anywhere is unaffected (system dialogs, cross-app
            // taps keep working).
            val stepPkg = step.params[PlanParams.PACKAGE]
            if (!stepPkg.isNullOrBlank() && screen.packageName.isNotBlank() &&
                screen.packageName != stepPkg
            ) {
                return PointerDrive.TargetFailed(
                    "target gone: \"$target\" is in $stepPkg but the screen shows " +
                        "${screen.packageName} — open the app first",
                )
            }
            val recordPkg = plan.record?.appPackage
            if (stepPkg.isNullOrBlank() && !recordPkg.isNullOrBlank() &&
                screen.packageName == LOOM_PACKAGE_NAME
            ) {
                return PointerDrive.TargetFailed(
                    "target gone: \"$target\" is in the ${plan.record?.app ?: "task"} app " +
                        "but the screen shows LOOM's own chat — open the app first",
                )
            }
            // Screen navigation (2026-11-10, David's rule): a target
            // that is not on the current screen may be below the
            // fold — scroll to look for it (bounded, stop rule)
            // instead of reporting "cannot find it". The hunt runs
            // before the step fails, so a found target flows into
            // the tap below on its verified bounds. NAV-SCREEN fix
            // (N1/N2): a NAVIGATE_TAB step resolves through the tab
            // resolver first; (N8) the hunt never runs for a chrome
            // target — the nav bar does not scroll.
            var matches = if (step.kind == TaskStepKind.NAVIGATE_TAB) {
                // Mark 57 transfer (2026-11-11, M6) — the knowledge
                // base's preferred tab for this destination on this
                // screen is consulted FIRST (label/position from
                // before); the resolver validates it against the
                // live tab and falls through when it is stale.
                val preferred = try {
                    knowledge.preferredNavTabFor(screen, target)
                } catch (_: Exception) {
                    null
                }
                NavigationResolver.resolveNavigationTarget(
                    screen, target, preferred?.position, preferred?.label,
                ).map { it.element }
                    .ifEmpty { findTargetElements(screen, target) }
            } else {
                findTargetElements(screen, target)
            }
            if (matches.isEmpty()) {
                scrollHuntForTarget(plan, step, screen)?.let { (foundScreen, found) ->
                    matches = found
                    // The tap below re-resolves against the hunted
                    // screen, and verification compares against it —
                    // runAttempt consumes this once (see
                    // effectiveBefore) instead of the pre-hunt screen.
                    lastHuntScreen = foundScreen
                }
            }
            // MASTER Wave 3 (F9) — same rule as the dispatcher:
            // MATCH=first is a user-directed first selector (the
            // planner emits it only when the user said "first"); a
            // bare tap with several matches refuses honestly instead
            // of parking on — and then tapping — the first one.
            // Result selection after verified results is the search
            // sequence's job, never a blind TAP_TARGET.
            // MASTER Wave 4 (Wave 3 residual) — a BARE first-result
            // target ("tap the first result") is positional, not
            // textual: resolve the first result ROW instead of
            // substring-matching words no element contains.
            val firstOnly = step.params[PlanParams.MATCH] == "first"
            val resolvedMatches = if (isBareFirstTarget(target)) {
                val row = firstResultRow(screen)
                    ?: return PointerDrive.TargetFailed(
                        "No results on screen to pick the first from.",
                    )
                listOf(row)
            } else {
                matches
            }
            if (resolvedMatches.isEmpty()) {
                return PointerDrive.TargetFailed(
                    "target gone: \"$target\" no longer matches anything on screen",
                )
            }
            if (resolvedMatches.size > 1 && !firstOnly) {
                return PointerDrive.TargetFailed(
                    "${resolvedMatches.size} matches for \"$target\" — be more specific",
                )
            }
            val el = resolvedMatches[0]
            val bounds = boundsOf(el)
            // Record the verified bounds so the failure analyzer can
            // tell target-moved from target-gone afterwards.
            val resolved = step.copy(
                params = step.params + (PlanParams.BOUNDS to
                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"),
            )
            // MASTER Wave 3 (F9) — pointer/pill agreement: the pill
            // must name the exact node the frame is drawn on. The
            // pill copy carries the resolved element's label; the
            // dispatched step keeps its own description (the token
            // label), so only the display names the node.
            // The pill still narrates the step first. The pointer
            // itself is driven by the PointerTapCoordinator at dispatch
            // time (move → arrive → tap at these same bounds) — this
            // branch only resolves + tracks the target, never moves
            // the pointer, so the tap cannot land anywhere the pointer
            // never parked.
            try {
                pointer.showIntended(pillStep(resolved, el))
            } catch (_: Exception) {
            }
            notePointerFailure(plan, step)
            // Track: re-resolve the target id — a moved or gone target
            // never gets a stale tap.
            val tracked = try {
                pointer.trackTarget(el.id)
            } catch (_: Exception) {
                false
            }
            val drawFailure = try {
                pointer.lastPointerFailure()
            } catch (_: Exception) {
                null
            }
            notePointerFailure(plan, step)
            if (!tracked) {
                if (drawFailure != null) {
                    // The target is THERE — only the DRAW failed. The
                    // tap can still be correct, so proceed and name the
                    // pointer gap in the outcome (never "target gone").
                    audit.log(plan.id, step.index, "pointer-draw-failed", drawFailure)
                } else {
                    return PointerDrive.TargetFailed(
                        "target gone: \"$target\" left the screen before the tap",
                    )
                }
            }
            return PointerDrive.Driven(resolved, bounds, targetId = el.id)
        }
        val el = resolveStepTarget(screen, step)
        if (el == null) {
            // POINTER EVERYWHERE (2026-11-09): no element — but a
            // SWIPE still has a real start point and a GO_BACK still
            // has a real origin (the Back button, or the left edge on
            // gesture navigation). Drive there; only when even that
            // cannot be established does the step narrate instead of
            // drawing a fake target outline.
            resolvePointerPoint(screen, step)?.let { point ->
                val resolved = step.copy(
                    params = step.params + (PlanParams.BOUNDS to
                        "${point.bounds.left},${point.bounds.top}," +
                        "${point.bounds.right},${point.bounds.bottom}"),
                )
                val waitMs = drivePointerToTarget(plan, resolved, point.bounds, point.pillStep)
                waitForPointerArrival(waitMs)
                return PointerDrive.Driven(resolved, point.bounds)
            }
            // No on-screen target (LAUNCH_APP, or an unresolvable
            // field / button / gesture point): narrate the step in
            // the pill, never draw a fake target outline.
            try {
                pointer.showIntended(step)
            } catch (_: Exception) {
            }
            notePointerFailure(plan, step)
            return PointerDrive.Driven(step, null)
        }
        val bounds = boundsOf(el)
        val resolved = step.copy(
            params = step.params + (PlanParams.BOUNDS to
                "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"),
        )
        val waitMs = drivePointerToTarget(plan, resolved, bounds, pillStep(resolved, el))
        waitForPointerArrival(waitMs)
        return PointerDrive.Driven(resolved, bounds)
    }

    /**
     * MASTER Wave 3 (F9) — pointer/pill agreement: the pill copy of
     * a step names the exact node the frame is drawn on (the
     * resolved element's label), so the frame and the pill can never
     * name different things — the 346s disagreement (frame on a
     * "protect your phone" banner, pill "Tapping Nigeria versus
     * Japan...") came from the pill narrating the step query while
     * the frame parked on a separately resolved element. The copy
     * keeps the step's BOUNDS (the frame) and only rewrites the
     * display description; the dispatched step is untouched.
     */
    private fun pillStep(step: PlanStep, el: ScreenElement): PlanStep {
        val label = el.label().trim()
        if (label.isBlank() || step.description.contains(label, ignoreCase = true)) {
            return step
        }
        return step.copy(description = "${step.description} — $label")
    }

    /**
     * Shows the intended step (outline + plain-words label in the
     * status pill) and moves the pointer to the VERIFIED target.
     * Returns the glide wait for the move (measured BEFORE moving,
     * from the current cursor — 250ms baseline, 300ms cap). Display-
     * only: a missed visual never fails the step — the failure is
     * recorded and named in the outcome instead.
     *
     * MASTER Wave 3 (F9): [pillStep] is the copy the pill shows —
     * it names the exact node the frame is drawn on, while [step]
     * (the dispatched step) keeps its own description. Both derive
     * from the same resolved target.
     */
    private fun drivePointerToTarget(
        plan: TaskPlan,
        step: PlanStep,
        bounds: TargetBounds,
        pillStep: PlanStep = step,
    ): Long {
        val waitMs = try {
            pointer.glideWaitMs(bounds)
        } catch (_: Exception) {
            0L
        }
        try {
            pointer.showIntended(pillStep)
        } catch (_: Exception) {
        }
        notePointerFailure(plan, step)
        try {
            pointer.moveTo(bounds, highlight = true)
        } catch (_: Exception) {
        }
        notePointerFailure(plan, step)
        return waitMs
    }

    /**
     * The pointer moves first; the action happens only after it
     * arrives. No wait when the move could not be shown (there is
     * nothing to wait for) or when there is no live renderer.
     */
    private fun waitForPointerArrival(waitMs: Long) {
        if (waitMs <= 0L) return
        val failed = try {
            pointer.lastPointerFailure()
        } catch (_: Exception) {
            null
        }
        if (failed != null) return
        try {
            Thread.sleep(waitMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Resolves the on-screen target for a non-TAP step, best-effort
     * (null = narrate only, never a step failure). An explicit TARGET
     * param wins for every kind that carries one; otherwise:
     * TYPE_TEXT parks on the text field, SUBMIT on the keyboard's
     * action key when the keyboard is up (then the on-screen search
     * button — SearchExecutor's fallback rule — exactly one enabled,
     * non-field element labelled "search" — then the focused field),
     * GO_BACK on the measured system Back button (3-button
     * navigation; gesture nav has no button — the edge origin is
     * handled by [resolvePointerPoint]), SCROLL on the list
     * container, SEARCH_IN_APP on the search field, GUIDE_TO on its
     * guide target. LAUNCH_APP has no on-screen target (intent
     * launch, not an icon tap).
     * Universal Console (2026-11-07): LONG_PRESS / FOCUS_FIELD
     * resolve through their TARGET param (above); OPEN_MENU parks on
     * the menu control — the same semantic predicate the dispatcher
     * re-resolves before dispatch (the dispatcher is the authority;
     * this is only where the pointer parks).
     */
    private fun resolveStepTarget(screen: ScreenModel, step: PlanStep): ScreenElement? {
        step.params[PlanParams.TARGET]?.takeIf { it.isNotBlank() }?.let { query ->
            // MASTER Wave 4 (Wave 3 residual) — the old code returned
            // matches[0] for any non-TAP step, parking the pointer on
            // the first of several matches (an unverified element).
            // Now: a bare first-result target resolves positionally
            // (first row); a single match resolves; several matches
            // resolve ONLY with a user-directed MATCH=first; several
            // matches without it park on NOTHING (narrate only) — the
            // dispatcher re-resolves before any tap and refuses
            // honestly there, so no wrong tap can result from this
            // pointer-park-only choice.
            if (isBareFirstTarget(query)) {
                firstResultRow(screen)?.let { return it }
            } else {
                val matches = findTargetElements(screen, query)
                val firstOnly = step.params[PlanParams.MATCH] == "first"
                if (matches.size == 1) return matches[0]
                if (matches.size > 1) {
                    if (firstOnly) return matches[0]
                    return null
                }
                // No matches: fall through to the kind fallbacks below.
            }
        }
        return when (step.kind) {
            TaskStepKind.TYPE_TEXT ->
                screen.elements.firstOrNull { isFieldElement(it) }
            TaskStepKind.SUBMIT -> {
                // POINTER EVERYWHERE (2026-11-09): when the keyboard
                // is up, the submit lands on its action key — the key
                // is the visible submit control (IME_ENTER is
                // invisible; the key is tried next in the chain). A
                // key measured while the keyboard is hidden is stale
                // and must never be parked on.
                if (screen.keyboardVisible) {
                    screen.keyboardActionKey?.let { key ->
                        return controlElement("system:keyboard-action-key", key, ElementRole.BUTTON)
                    }
                }
                val buttons = screen.elements.filter { el ->
                    el.enabled && !el.bounds.isEmpty && !isFieldElement(el) &&
                        // MASTER Wave 3 (F9) — the voice-search mic
                        // mentions "search" and must never be the
                        // submit button.
                        !TargetAliases.isVoiceControl(el) &&
                        ((el.text?.contains("search", ignoreCase = true) == true) ||
                            (el.contentDescription?.contains("search", ignoreCase = true) == true))
                }
                if (buttons.size == 1) buttons[0]
                else screen.elements.firstOrNull { it.focused && isFieldElement(it) }
                    ?: screen.elements.firstOrNull { isFieldElement(it) }
            }
            TaskStepKind.GO_BACK ->
                // POINTER EVERYWHERE (2026-11-09): the system Back
                // button, measured in the navigation-bar window.
                // Null on gesture navigation — the edge origin is
                // handled by resolvePointerPoint, never faked here.
                screen.backButton?.let { back ->
                    controlElement("system:back-button", back, ElementRole.ICON)
                }
            // MASTER Wave 3 (F15b) — PLAY_PAUSE parks on the
            // play/pause control: the Pause control when a video is
            // playing, the Play control when it is paused.
            TaskStepKind.PLAY_PAUSE -> {
                val wantPlaying = when (step.params[PlanParams.PLAY_STATE]?.lowercase()?.trim()) {
                    "pause" -> false
                    "play" -> true
                    else -> !VideoPresence.isPlaying(screen)
                }
                VideoPresence.findPlayPauseControl(screen, wantPlaying)
            }
            TaskStepKind.SCROLL ->
                screen.elements.firstOrNull { it.role == ElementRole.LIST }
            TaskStepKind.SEARCH_IN_APP ->
                screen.elements.firstOrNull { isFieldElement(it) }
                    ?: findTargetElements(screen, "search").firstOrNull()
            TaskStepKind.GUIDE_TO -> {
                val query = step.params[PlanParams.GUIDE_TARGET].orEmpty()
                if (query.isBlank()) null
                else findTargetElements(screen, query).firstOrNull()
            }
            TaskStepKind.OPEN_MENU -> {
                val candidates = screen.elements.filter { el ->
                    el.enabled && !el.bounds.isEmpty &&
                        (el.role == ElementRole.MENU_ITEM ||
                            el.text?.trim()?.equals("menu", ignoreCase = true) == true ||
                            el.contentDescription?.trim()?.equals("menu", ignoreCase = true) == true ||
                            el.contentDescription?.contains("more options", ignoreCase = true) == true ||
                            el.text?.contains("more options", ignoreCase = true) == true)
                }
                if (candidates.size == 1) candidates[0] else null
            }
            else -> null
        }
    }

    /**
     * A measured non-app-window control (nav Back button, keyboard
     * action key) as a ScreenElement carrier: bounds, label and class
     * name are the node's own measured values, only transported
     * across the layer boundary — nothing invented. Never used for
     * TAP_TARGET tracking (trackTarget is TAP-only); only the pointer
     * parks on it, and the dispatch re-resolves its own target.
     */
    private fun controlElement(id: String, control: MeasuredControl, role: ElementRole): ScreenElement =
        ScreenElement(
            id = id,
            text = control.label,
            contentDescription = control.label,
            className = control.className,
            bounds = Rect(control.bounds.left, control.bounds.top, control.bounds.right, control.bounds.bottom),
            role = role,
            enabled = true,
            isPassword = false,
        )

    /** A real gesture point (not an element) plus the pill copy naming it. */
    private data class PointerPoint(val bounds: TargetBounds, val pillStep: PlanStep)

    /**
     * POINTER EVERYWHERE (2026-11-09): the pointer point for a step
     * that has no on-screen element but still has a real place on
     * the screen — the SWIPE start point, or the GO_BACK origin on
     * gesture navigation. Null when no point can be established
     * (no measured screen bounds, unknown swipe direction) — the
     * step then narrates only, never a fake point. The pill copy
     * names the direction and the region the pointer shows, so the
     * pill and the pointer can never disagree.
     *
     * The navigation mode is decided at runtime from live evidence,
     * never hardcoded: a measured Back button means 3-button
     * navigation (resolveStepTarget handles it); reaching here with
     * no Back button means gesture navigation, and back starts at
     * the left edge. No device evidence pins David's Redmi 14C to
     * either mode, so both branches stay live.
     */
    private fun resolvePointerPoint(screen: ScreenModel, step: PlanStep): PointerPoint? {
        val bounds = screen.screenBounds ?: return null
        return when (step.kind) {
            TaskStepKind.GO_BACK -> {
                val cy = (bounds.top + bounds.bottom) / 2
                val cx = bounds.left + POINTER_POINT_HALF_PX
                PointerPoint(
                    markerAt(cx, cy),
                    step.copy(description = step.description + " — from the left edge"),
                )
            }
            TaskStepKind.SWIPE -> {
                val direction = step.params[PlanParams.DIRECTION].orEmpty().lowercase()
                val (startX, startY, region) = swipeStart(bounds, direction) ?: return null
                PointerPoint(
                    markerAt(startX, startY),
                    step.copy(description = step.description + " — from $region"),
                )
            }
            else -> null
        }
    }

    /**
     * The swipe start point, computed from the MEASURED screen
     * bounds with the exact formula the service's swipe handoff
     * uses (LoomAccessibilityGestures.swipeHandoff: up starts at
     * centre-x / bottom-25%, down at centre-x / top+25%, left at
     * right-25% / centre-y, right at left+25% / centre-y) — so the
     * pointer parks where the gesture will actually start. The
     * region name is the pill copy. Unknown direction → null.
     */
    private fun swipeStart(bounds: Rect, direction: String): Triple<Int, Int, String>? {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        return when (direction) {
            "up" -> Triple(cx.toInt(), (bounds.bottom - h * 0.25f).toInt(), "the bottom")
            "down" -> Triple(cx.toInt(), (bounds.top + h * 0.25f).toInt(), "the top")
            "left" -> Triple((bounds.right - w * 0.25f).toInt(), cy.toInt(), "the right edge")
            "right" -> Triple((bounds.left + w * 0.25f).toInt(), cy.toInt(), "the left edge")
            else -> null
        }
    }

    /** A 64x64 marker ring centred on a gesture point. */
    private fun markerAt(cx: Int, cy: Int): TargetBounds =
        TargetBounds(
            cx - POINTER_POINT_HALF_PX, cy - POINTER_POINT_HALF_PX,
            cx + POINTER_POINT_HALF_PX, cy + POINTER_POINT_HALF_PX,
        )

    /**
     * Reads the pointer's last drive failure (if any) into the run's
     * collection, so the outcome names it. One entry per (reason,
     * step): the same reason across steps is named once, with every
     * step it hit.
     */
    private fun notePointerFailure(plan: TaskPlan, step: PlanStep) {
        val reason = try {
            pointer.lastPointerFailure()
        } catch (_: Exception) {
            null
        } ?: return
        notePointerFailureReason(plan, step, reason)
    }

    /** Records one pointer failure reason for one step (see [notePointerFailure]). */
    private fun notePointerFailureReason(plan: TaskPlan, step: PlanStep, reason: String) {
        val steps = pointerFailures.getOrPut(reason) { mutableListOf() }
        if (step.index !in steps) {
            steps.add(step.index)
            audit.log(plan.id, step.index, "pointer-failed", reason)
        }
    }

    /** Honest pointer-gap suffix for the plan outcome summary ("" when the pointer showed). */
    private fun pointerFailureSuffix(): String {
        if (pointerFailures.isEmpty()) return ""
        return pointerFailures.entries.joinToString("") { (reason, indices) ->
            val steps = if (indices.size == 1) {
                "step ${indices[0] + 1}"
            } else {
                "steps ${indices.joinToString(", ") { (it + 1).toString() }}"
            }
            " I could not show the pointer for $steps — $reason."
        }
    }

    /**
     * Device-failure fix (2026-11-13, F2) — the pointer-display
     * gate. Null means "go ahead". For [POINTER_GATE_KINDS], a
     * pointer failure recorded for THIS step (see
     * [notePointerFailure]) makes the step unverified: the action
     * dispatched invisibly, and the reason names the gap in the
     * outcome. Other kinds are never gated here.
     */
    private fun pointerGateFailure(plan: TaskPlan, step: PlanStep): String? {
        if (step.kind !in POINTER_GATE_KINDS) return null
        val reason = pointerFailures.entries.firstOrNull { step.index in it.value }?.key
            ?: return null
        audit.log(plan.id, step.index, "pointer-gate", reason)
        return "unverified: the pointer did not display for this step — $reason. " +
            "The action may have run, but it was not shown, so it cannot count as done."
    }

    /**
     * Device-failure fix (2026-11-13, F2) — the package-consistency
     * arm shared by the verify functions: a step that names a
     * package verifies only against that package. A blank/unknown
     * observed package never matches a named one. A step naming no
     * package is not gated here (current-screen steps).
     */
    private fun packageOk(step: PlanStep, after: ScreenModel): Boolean {
        val want = step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() } ?: return true
        return after.packageName == want
    }

    /**
     * Verifies one dispatched step against the fresh post-action screen.
     * Per-kind expectations (all read-only, all against [after]):
     *  - LAUNCH_APP / WAIT_FOR_APP: the expected package is foreground.
     *  - SEARCH_IN_APP: goal-aware — SHOW_RESULTS needs real results
     *    present ([searchResultsShown] — a results list / ≥2 matching
     *    rows; the box alone never counts); OPEN_FIRST_RESULT needs
     *    the screen to have changed into the opened result — and the
     *    landing must not be the voice-search permission screen, a
     *    suggestion list, or a loading spinner (2026-11-10).
     *  - TAP_TARGET / SUBMIT / SCROLL / GO_BACK / LONG_PRESS /
     *    SWIPE / OPEN_MENU: the screen changed.
     *  - NAVIGATE_TAB (2026-11-11, N4): the destination tab is
     *    selected, or destination content is present, and the package
     *    is unchanged. A TAP_TARGET on a known destination with an
     *    enumerable tab bar gets the same destination check (a lone
     *    NAVIGATION-role element is not a tab bar).
     *  - FOCUS_FIELD: a field actually holds focus afterwards.
     *  - TYPE_TEXT: the typed text is visible in the field (a field
     *    that exposes no text FAILS — typing that cannot be seen is
     *    unverified, never counted as done, 2026-11-10).
     *  - GUIDE_TO: readable, the guided target on screen, and the
     *    pointer drew on it (confirm AND perform, 2026-11-10).
     *  - OBSERVE_SCREEN / POINTER_MOVE / REPORT: readable.
     *  - VERIFY_OUTCOME: the EXPECTED text / playback indicator is present.
     */
    /**
     * AOIE Phase 1 — the typed verification verdict (blueprint §4).
     * Same checks as [verifyStep], but with a confidence and an
     * explicit verdict: PASS (the expected state was directly
     * observed), FAIL (it is absent), UNCERTAIN (the screen cannot
     * prove or disprove it — an unreadable field). Confidence is
     * deliberately below 1.0 for heuristic arms (a screen change
     * is a proxy for a tap's effect, not proof of it). The executor
     * records this in the trace; the VERIFIED decision still uses
     * the Boolean check, so this changes reporting, not behaviour.
     */
    fun verifyStepTyped(step: PlanStep, before: ScreenModel?, after: ScreenModel?): VerificationResult {
        val expected = step.expectedPostcondition.ifBlank { step.description }
        fun result(
            verdict: VerificationResult.Verdict,
            confidence: Double,
            observed: String,
            evidence: String,
            nextStep: String,
        ) = VerificationResult(
            actionId = step.actionId,
            expected = expected,
            observed = observed,
            evidence = evidence,
            confidence = confidence,
            verdict = verdict,
            nextStep = nextStep,
        )
        if (after == null) {
            return result(
                VerificationResult.Verdict.FAIL, 0.0,
                "no confirming observation", "screen unreadable after dispatch",
                "re-observe before retrying",
            )
        }
        val pkg = after.packageName
        val changed = screenChanged(before, after)
        return when (step.kind) {
            TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP -> {
                // Device-failure fix (2026-11-13, F2): a launch
                // with a BLANK package param FAILS — readability
                // of any screen is not proof the app opened.
                val want = step.params[PlanParams.PACKAGE]
                if (!want.isNullOrBlank() && pkg == want) {
                    result(
                        VerificationResult.Verdict.PASS, 1.0,
                        "foreground package is $pkg", "package match",
                        "continue",
                    )
                } else if (want.isNullOrBlank()) {
                    result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "no package named to launch — cannot confirm the app opened",
                        "blank package", "name the app and relaunch",
                    )
                } else {
                    result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "foreground package is $pkg, expected $want", "package mismatch",
                        "wait for the app or relaunch",
                    )
                }
            }
            TaskStepKind.SEARCH_IN_APP -> {
                val query = step.params[PlanParams.QUERY].orEmpty()
                val ok = if (step.params[PlanParams.SEARCH_GOAL] == PlanParams.SEARCH_GOAL_SHOW_RESULTS) {
                    // Device-failure fix (2026-11-13, F2): results
                    // verify only in the named package.
                    searchResultsShown(
                        after, query, before,
                        step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() },
                    )
                } else {
                    val want = step.params[PlanParams.PACKAGE]
                    // Screen confirmation (2026-11-10): OPEN_FIRST_RESULT
                    // must land on a real result — a screen change into
                    // the voice-search permission screen, the suggestion
                    // list, or a loading spinner is not an opened result.
                    // Device-test failures (2026-11-11, D-07): a screen
                    // change into the SAME results list is not an
                    // opened result either — see openFirstLanded: a
                    // player up, or navigated away from the results.
                    val landingOk =
                        step.params[PlanParams.SEARCH_GOAL] != PlanParams.SEARCH_GOAL_OPEN_FIRST ||
                            (!SearchScreenPredicates.isSuggestionsScreen(after) &&
                                !SearchScreenPredicates.isLoadingScreen(after, query) &&
                                !isVoiceSearchLanding(after, query))
                    val openedOk =
                        step.params[PlanParams.SEARCH_GOAL] != PlanParams.SEARCH_GOAL_OPEN_FIRST ||
                            openFirstLanded(after, query, before, want?.takeIf { it.isNotBlank() })
                    changed && (want.isNullOrBlank() || pkg == want) && landingOk && openedOk
                }
                if (ok) result(
                    VerificationResult.Verdict.PASS, 1.0,
                    "search results shown in $pkg", "results on screen",
                    "continue",
                ) else result(
                    VerificationResult.Verdict.FAIL, 0.0,
                    "no results on screen in $pkg", "no confirming observation",
                    if (before != null) verifyFailureReason(step, before, after)
                    else "re-observe and retry",
                )
            }
            // NAV-SCREEN fix (2026-11-11, N4): a navigation step is
            // verified by its DESTINATION, never by a bare screen
            // change. A selection-only tab switch IS a pass; tapping
            // the wrong tab FAILS as "wrong destination" even though
            // the screen changed. A TAP_TARGET on a known destination
            // with an enumerable tab bar on the before screen gets
            // the same destination check (it resolved via the nav
            // tier); every other tap keeps the screen-change check.
            TaskStepKind.NAVIGATE_TAB -> {
                val destination = step.params[PlanParams.TARGET].orEmpty()
                when (val nav = NavigationVerificationChecker.verifyNavigation(
                    before, after, destination, step.params[PlanParams.PACKAGE].orEmpty(),
                )) {
                    is NavigationVerification.Passed -> result(
                        VerificationResult.Verdict.PASS, 1.0,
                        nav.reason, "destination verified", "continue",
                    )
                    is NavigationVerification.Failed -> result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        nav.reason, "destination not reached", "re-observe and retry",
                    )
                }
            }
            TaskStepKind.TAP_TARGET -> {
                val target = step.params[PlanParams.TARGET].orEmpty()
                // NAV-SCREEN fix (2026-11-11, N4) — the destination
                // check applies only when the before screen has an
                // actual enumerable TAB BAR (a lone NAVIGATION-role
                // element, e.g. a single bottom "Home" control with
                // no tabs, is not one — such a tap keeps the
                // screen-change check, as before).
                val navApplicable = before != null &&
                    NavigationResolver.isKnownDestination(target) &&
                    NavigationResolver.enumerateTabs(before).isNotEmpty()
                if (navApplicable) {
                    when (val nav = NavigationVerificationChecker.verifyNavigation(
                        before, after, target, step.params[PlanParams.PACKAGE].orEmpty(),
                    )) {
                        is NavigationVerification.Passed -> result(
                            VerificationResult.Verdict.PASS, 1.0,
                            nav.reason, "destination verified", "continue",
                        )
                        is NavigationVerification.Failed -> result(
                            VerificationResult.Verdict.FAIL, 0.0,
                            nav.reason, "destination not reached", "re-observe and retry",
                        )
                    }
                } else if (changed && packageOk(step, after)) {
                    result(
                        VerificationResult.Verdict.PASS, 0.9,
                        "screen changed in $pkg", "before/after screens differ",
                        "continue",
                    )
                } else {
                    result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "screen unchanged in $pkg", "no confirming observation",
                        "re-observe and retry",
                    )
                }
            }
            TaskStepKind.SUBMIT,
            TaskStepKind.SCROLL, TaskStepKind.GO_BACK,
            TaskStepKind.LONG_PRESS, TaskStepKind.SWIPE,
            TaskStepKind.OPEN_MENU -> if (changed && (step.kind == TaskStepKind.GO_BACK || packageOk(step, after))) {
                result(
                    VerificationResult.Verdict.PASS, 0.9,
                    "screen changed in $pkg", "before/after screens differ",
                    "continue",
                )
            } else {
                result(
                    VerificationResult.Verdict.FAIL, 0.0,
                    "screen unchanged in $pkg", "no confirming observation",
                    "re-observe and retry",
                )
            }
            // MASTER Wave 3 (F15b) — PLAY_PAUSE verifies the player
            // state CHANGED (playing ↔ paused). A tap alone is not
            // success: "pause" passes only when a playing video
            // stopped, "play" only when a paused video started, and
            // a bare toggle only when the state flipped either way.
            TaskStepKind.PLAY_PAUSE -> {
                val want = step.params[PlanParams.PLAY_STATE]?.lowercase()?.trim()
                val wasPlaying = before?.let { VideoPresence.isPlaying(it) } ?: false
                val nowPlaying = VideoPresence.isPlaying(after)
                val ok = (when (want) {
                    "pause" -> wasPlaying && !nowPlaying
                    "play" -> !wasPlaying && nowPlaying
                    else -> wasPlaying != nowPlaying
                }) && packageOk(step, after)
                if (ok) result(
                    VerificationResult.Verdict.PASS, 1.0,
                    "player state changed in $pkg (playing: $wasPlaying → $nowPlaying)",
                    "playback state flipped", "continue",
                ) else result(
                    VerificationResult.Verdict.FAIL, 0.0,
                    "player state did not change in $pkg (playing: $wasPlaying → $nowPlaying)",
                    "no state flip after the tap", "re-observe and retry",
                )
            }
            TaskStepKind.FOCUS_FIELD -> {
                if (packageOk(step, after) && after.elements.any { it.focused && isFieldElement(it) }) {
                    result(
                        VerificationResult.Verdict.PASS, 1.0,
                        "a field holds focus in $pkg", "focused field present",
                        "continue",
                    )
                } else {
                    result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "no field holds focus in $pkg", "focus missing",
                        "tap the field again",
                    )
                }
            }
            TaskStepKind.TYPE_TEXT -> {
                val text = step.params[PlanParams.TEXT].orEmpty()
                val field = after.elements.firstOrNull { isFieldElement(it) }
                    ?: return result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "no field on screen in $pkg", "field gone",
                        "re-observe and retry",
                    )
                val shown = field.text
                when {
                    shown == null -> result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "field exposes no text in $pkg", "unreadable field — typing unverified",
                        "re-observe and retry",
                    )
                    (text.isBlank() || shown.contains(text, ignoreCase = true)) &&
                        packageOk(step, after) -> result(
                        VerificationResult.Verdict.PASS, 1.0,
                        "field shows the typed text in $pkg", "text match",
                        "continue",
                    )
                    else -> result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "field does not show the typed text in $pkg", "text mismatch",
                        "retype into the focused field",
                    )
                }
            }
            TaskStepKind.VERIFY_OUTCOME -> {
                if (expectedOnScreen(
                        after, step.params[PlanParams.EXPECTED].orEmpty(),
                        step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() },
                    )
                ) {
                    result(
                        VerificationResult.Verdict.PASS, 1.0,
                        "expected state on screen in $pkg", "expected text present",
                        "continue",
                    )
                } else {
                    result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "expected state absent in $pkg", "expected text not found",
                        "re-observe or replan",
                    )
                }
            }
            TaskStepKind.POINTER_MOVE -> result(
                VerificationResult.Verdict.PASS, 0.8,
                "pointer nudge accepted in $pkg", "nudge dispatch accepted",
                "continue",
            )
            // Confirm AND perform (2026-11-10): a guide step counts
            // only when the screen is readable, the guided target is
            // actually on screen, and the pointer drew on it — a draw
            // failure (or a target that was never found) means the
            // pointer never showed the thing, so the step did not
            // happen. The pointer-failure check reads the per-step
            // record (see notePointerFailure), never a stale last-call
            // value from an earlier step.
            TaskStepKind.GUIDE_TO -> {
                val query = step.params[PlanParams.GUIDE_TARGET].orEmpty()
                val pointerFailed = pointerFailures.values.any { step.index in it }
                val targetOnScreen = query.isBlank() ||
                    findTargetElements(after, query).isNotEmpty()
                if (after.elements.isNotEmpty() && targetOnScreen && !pointerFailed) {
                    result(
                        VerificationResult.Verdict.PASS, 0.9,
                        "pointer on target in $pkg", "target on screen, pointer drawn",
                        "continue",
                    )
                } else {
                    result(
                        VerificationResult.Verdict.FAIL, 0.0,
                        "guide target not shown in $pkg", "target missing or pointer failed",
                        "re-observe and retry",
                    )
                }
            }
            TaskStepKind.OBSERVE_SCREEN, TaskStepKind.REPORT -> if (after.elements.isNotEmpty()) {
                result(
                    VerificationResult.Verdict.PASS, 0.7,
                    "screen readable in $pkg (${after.elements.size} elements)",
                    "observation only — no action to verify",
                    "continue",
                )
            } else {
                result(
                    VerificationResult.Verdict.FAIL, 0.0,
                    "screen unreadable in $pkg", "empty tree",
                    "re-observe",
                )
            }
            TaskStepKind.MESSAGE_SEND, TaskStepKind.CALL_PLACE -> if (after.elements.isNotEmpty()) {
                result(
                    VerificationResult.Verdict.PASS, 0.8,
                    "screen readable in $pkg after send/call",
                    "executor verified internally (sent bubble / in-call UI)",
                    "continue",
                )
            } else {
                result(
                    VerificationResult.Verdict.FAIL, 0.0,
                    "screen unreadable in $pkg", "empty tree",
                    "re-observe",
                )
            }
        }
    }

    fun verifyStep(step: PlanStep, before: ScreenModel?, after: ScreenModel?): Boolean {
        if (after == null) return false
        return when (step.kind) {
            TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP -> {
                // Device-failure fix (2026-11-13, F2): a blank
                // package param FAILS — readability is not proof
                // the app opened.
                val pkg = step.params[PlanParams.PACKAGE]
                if (pkg.isNullOrBlank()) false
                else after.packageName == pkg
            }
            TaskStepKind.SEARCH_IN_APP -> {
                // Simple-task execution fix (2026-11-07): real results
                // only — see searchResultsShown. The old any-word
                // check passed on the search box's own text, so typed
                // text counted as results.
                // Universal Console (2026-11-07): the goal decides
                // what counts. SHOW_RESULTS needs real results on
                // screen; OPEN_FIRST_RESULT needs the screen to have
                // changed into the opened result (the sequence
                // already verified results + landing internally).
                val query = step.params[PlanParams.QUERY].orEmpty()
                if (step.params[PlanParams.SEARCH_GOAL] == PlanParams.SEARCH_GOAL_SHOW_RESULTS) {
                    searchResultsShown(
                        after, query, before,
                        step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() },
                    )
                } else {
                    val pkg = step.params[PlanParams.PACKAGE]
                    // Screen confirmation (2026-11-10): OPEN_FIRST_RESULT
                    // must land on a real result — a screen change into
                    // the voice-search permission screen, the suggestion
                    // list, or a loading spinner is not an opened result.
                    // Device-test failures (2026-11-11, D-07): a screen
                    // change into the SAME results list is not an
                    // opened result either — see openFirstLanded.
                    val landingOk =
                        step.params[PlanParams.SEARCH_GOAL] != PlanParams.SEARCH_GOAL_OPEN_FIRST ||
                            (!SearchScreenPredicates.isSuggestionsScreen(after) &&
                                !SearchScreenPredicates.isLoadingScreen(after, query) &&
                                !isVoiceSearchLanding(after, query))
                    val openedOk =
                        step.params[PlanParams.SEARCH_GOAL] != PlanParams.SEARCH_GOAL_OPEN_FIRST ||
                            openFirstLanded(after, query, before, pkg?.takeIf { it.isNotBlank() })
                    screenChanged(before, after) &&
                        (pkg.isNullOrBlank() || after.packageName == pkg) && landingOk && openedOk
                }
            }
            // NAV-SCREEN fix (2026-11-11, N4): destination-aware
            // verification for navigation steps (see verifyStepTyped
            // above for the rule). A selection-only switch passes; a
            // wrong tab fails even though the screen changed.
            TaskStepKind.NAVIGATE_TAB -> {
                val destination = step.params[PlanParams.TARGET].orEmpty()
                NavigationVerificationChecker.verifyNavigation(
                    before, after, destination, step.params[PlanParams.PACKAGE].orEmpty(),
                ) is NavigationVerification.Passed
            }
            TaskStepKind.TAP_TARGET -> {
                val target = step.params[PlanParams.TARGET].orEmpty()
                // NAV-SCREEN fix (2026-11-11, N4) — same tab-bar
                // gate as verifyStepTyped above: a lone
                // NAVIGATION-role element is not a tab bar.
                val navApplicable = before != null &&
                    NavigationResolver.isKnownDestination(target) &&
                    NavigationResolver.enumerateTabs(before).isNotEmpty()
                if (navApplicable) {
                    NavigationVerificationChecker.verifyNavigation(
                        before, after, target, step.params[PlanParams.PACKAGE].orEmpty(),
                    ) is NavigationVerification.Passed
                } else {
                    screenChanged(before, after) && packageOk(step, after)
                }
            }
            TaskStepKind.SUBMIT,
            TaskStepKind.SCROLL, TaskStepKind.GO_BACK,
            TaskStepKind.LONG_PRESS, TaskStepKind.SWIPE,
            // GO_BACK is exempt from the package arm: its job is
            // to LEAVE the app, so a changed package is expected.
            TaskStepKind.OPEN_MENU -> screenChanged(before, after) &&
                (step.kind == TaskStepKind.GO_BACK || packageOk(step, after))
            // MASTER Wave 3 (F15b) — PLAY_PAUSE: the player state
            // must have flipped (playing ↔ paused); a tap alone is
            // never success.
            TaskStepKind.PLAY_PAUSE -> {
                val want = step.params[PlanParams.PLAY_STATE]?.lowercase()?.trim()
                val wasPlaying = before?.let { VideoPresence.isPlaying(it) } ?: false
                val nowPlaying = VideoPresence.isPlaying(after)
                (when (want) {
                    "pause" -> wasPlaying && !nowPlaying
                    "play" -> !wasPlaying && nowPlaying
                    else -> wasPlaying != nowPlaying
                }) && packageOk(step, after)
            }
            TaskStepKind.FOCUS_FIELD -> {
                // Universal Console (2026-11-07): a focus step only
                // counts when a field actually holds focus afterwards
                // — a screen change alone could be anything.
                packageOk(step, after) && after.elements.any { it.focused && isFieldElement(it) }
            }
            TaskStepKind.TYPE_TEXT -> {
                val text = step.params[PlanParams.TEXT].orEmpty()
                val field = after.elements.firstOrNull { isFieldElement(it) }
                    ?: return false
                val shown = field.text
                when {
                    shown == null -> false // unreadable field: typing is unverified, never counted as done
                    text.isBlank() -> packageOk(step, after)
                    else -> shown.contains(text, ignoreCase = true) && packageOk(step, after)
                }
            }
            TaskStepKind.VERIFY_OUTCOME ->
                expectedOnScreen(
                    after, step.params[PlanParams.EXPECTED].orEmpty(),
                    step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() },
                )
            // Confirm AND perform (2026-11-10): a guide step counts
            // only when the screen is readable, the guided target is
            // actually on screen, and the pointer drew on it — a draw
            // failure (or a target that was never found) means the
            // pointer never showed the thing.
            TaskStepKind.GUIDE_TO -> {
                val query = step.params[PlanParams.GUIDE_TARGET].orEmpty()
                val pointerFailed = pointerFailures.values.any { step.index in it }
                after.elements.isNotEmpty() &&
                    (query.isBlank() || findTargetElements(after, query).isNotEmpty()) &&
                    !pointerFailed
            }
            TaskStepKind.OBSERVE_SCREEN,
            TaskStepKind.POINTER_MOVE, TaskStepKind.REPORT -> after.elements.isNotEmpty()
            TaskStepKind.MESSAGE_SEND, TaskStepKind.CALL_PLACE -> {
                // The message/call executors verify internally (sent bubble
                // / in-call UI); their DISPATCHED already means verified.
                // The executor's fresh observation still has to be readable.
                after.elements.isNotEmpty()
            }
        }
    }

    /**
     * Device-failure fix (2026-11-13, F2) — the outcome gate.
     * Returns true when the run may report "Done": either the plan
     * names no target app (no gate applies), or at least one
     * verified action step's evidence package equals the target
     * package (record, else the first step PACKAGE). A verified
     * step whose evidence package is blank never satisfies the
     * gate — an unknown screen is not the target app.
     */
    private fun outcomeGatePasses(
        plan: TaskPlan,
        evidence: List<Triple<PlanStep, String, String>>,
    ): Boolean {
        // No app-scoped action step (guidance-only, media-control,
        // current-screen plans): no gate applies, whatever the
        // record carries — a guidance plan must never fail this
        // gate for having no action in an app it was never meant
        // to act in.
        val hasAppScopedAction = plan.steps.any { step ->
            step.kind in ACTION_KINDS &&
                (!step.params[PlanParams.APP].isNullOrBlank() ||
                    !step.params[PlanParams.PACKAGE].isNullOrBlank())
        }
        if (!hasAppScopedAction) return true
        val targetPkg = targetPackage(plan) ?: return true
        return evidence.any { (_, _, pkg) -> pkg == targetPkg }
    }

    private fun outcomeGateReason(
        plan: TaskPlan,
        evidence: List<Triple<PlanStep, String, String>>,
    ): String {
        val targetApp = plan.record?.app?.takeIf { it.isNotBlank() }
            ?: plan.steps.firstNotNullOfOrNull {
                it.params[PlanParams.APP]?.takeIf { a -> a.isNotBlank() }
            }
            ?: targetPackage(plan)
        val seen = evidence.map { it.third }.filter { it.isNotBlank() }.distinct()
        return "no verified action in ${targetApp ?: "the target app"}" +
            if (seen.isEmpty()) " — no step produced evidence in any app."
            else " — the verified steps only showed ${seen.joinToString(", ")}."
    }

    private fun targetPackage(plan: TaskPlan): String? =
        plan.record?.appPackage?.takeIf { it.isNotBlank() }
            ?: plan.steps.firstNotNullOfOrNull {
                it.params[PlanParams.PACKAGE]?.takeIf { p -> p.isNotBlank() }
            }

    /** Evidence suffix for a COMPLETED summary: what was actually seen, per verified action step. */
    private fun evidenceSuffix(evidence: List<Triple<PlanStep, String, String>>): String {
        val parts = evidence.mapNotNull { (_, text, _) -> text.takeIf { it.isNotBlank() } }.distinct()
        return if (parts.isEmpty()) "" else " Evidence: " + parts.joinToString("; ") + "."
    }

    // ---------- outcomes ----------

    private fun interruptedOutcome(
        plan: TaskPlan,
        verified: List<PlanStep>,
        totalSteps: Int,
        failedStep: PlanStep?,
    ): PlanOutcome {
        audit.log(plan.id, failedStep?.index ?: -1, "interrupted", "stopped — remaining steps unverified")
        return PlanOutcome(
            status = PlanStatus.INTERRUPTED,
            verifiedSteps = verified.size,
            totalSteps = totalSteps,
            failedStep = failedStep,
            summary = "Stopped. Verified ${verified.size} of $totalSteps step(s); " +
                "the remaining step(s) were not verified, so the task is not done." +
                pointerFailureSuffix(),
        )
    }

    // ---------- safe seams (a throwing seam fails the step, never the run) ----------

    private fun safePlan(request: PlanRequest): TaskPlanner.PlanResult = try {
        planner.plan(request)
    } catch (e: Exception) {
        TaskPlanner.PlanResult.Unsupported("I couldn't build a plan for that: ${e.message}")
    }

    private fun safeRequestCover(plan: TaskPlan): CoverResult = try {
        cover.requestPlanCover(plan)
    } catch (e: Exception) {
        CoverResult.DENIED("Could not ask for approval: ${e.message ?: "unknown error"}")
    }

    private fun safeReplan(
        plan: TaskPlan,
        failedStep: PlanStep,
        error: String,
        observation: ScreenModel?,
    ): TaskPlanner.PlanResult = try {
        planner.replan(plan, failedStep, error, observation)
    } catch (e: Exception) {
        TaskPlanner.PlanResult.Unsupported("Could not rebuild the plan: ${e.message}")
    }

    private fun safeObserve(): ScreenModel? = try {
        observer.observeNow()
    } catch (_: Exception) {
        null
    }

    /** AOIE Phase 2 — the real keyboard dismiss, via the dispatcher.
     *  A throwing dispatcher fails the recovery, never the run. */
    private fun safeDismissKeyboard(): StepDispatchResult = try {
        dispatcher.dismissKeyboard()
    } catch (e: Exception) {
        StepDispatchResult.FAILED("Keyboard dismiss failed: ${e.message ?: "unknown error"}")
    }

    /**
     * AOIE Phase 1 — records one typed trace entry and flushes its
     * structured line to the audit sink. Never throws: a trace
     * failure must never fail a step.
     */
    private fun recordTrace(
        plan: TaskPlan,
        step: PlanStep,
        dispatch: String,
        verification: VerificationResult,
        detail: String = "",
    ) {
        try {
            val entry = ExecutionTraceEntry(
                planId = plan.id,
                actionId = step.actionId,
                stepIndex = step.index,
                kind = step.kind,
                dispatch = dispatch,
                verification = verification.verdict,
                confidence = verification.confidence,
                detail = detail,
            )
            trace.record(entry)
            audit.log(plan.id, step.index, "trace", entry.toAuditLine())
        } catch (_: Exception) {
        }
    }

    // ---------- small types ----------

    private sealed interface PointerDrive {
        /** Pointer driven (or narrated) — [bounds] is the parked target, or null when narration-only. */
        data class Driven(
            val step: PlanStep,
            val bounds: TargetBounds?,
            /** The resolved target's element id (TAP_TARGET only) — the coordinator taps it. */
            val targetId: String? = null,
        ) : PointerDrive

        /** TAP_TARGET only: the target is gone / ambiguous / unnamed — the step fails. */
        data class TargetFailed(val reason: String) : PointerDrive
    }

    private sealed interface AttemptResult {
        /**
         * Device-failure fix (2026-11-13, F2): a verification
         * carries its EVIDENCE (what was seen) and the observed
         * PACKAGE it was seen in — the outcome gate and the
         * terminal summary read these, never a bare step count.
         */
        data class Verified(val evidence: String = "", val packageName: String = "") : AttemptResult
        data class Failed(val reason: String) : AttemptResult
    }

    private sealed interface StepRun {
        data class Done(
            val step: PlanStep,
            val evidence: String = "",
            val packageName: String = "",
        ) : StepRun
        data class Skipped(val reason: String) : StepRun
        /**
         * Simple-task execution fix (2026-11-07): the replan carries
         * the step that failed, so the run loop can tell a
         * repair-first replan (failed step re-issued) from a
         * drop-the-step replan — a dropped critical step must end
         * the run FAILED, never COMPLETED.
         */
        data class Replanned(val newPlan: TaskPlan, val failedStep: PlanStep) : StepRun
        data class Aborted(val reason: String, val askUserMessage: String?) : StepRun
        data object Interrupted : StepRun
    }
}
