package com.loom.assistant.intelligence.planner

import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Stage 2D — the seams the executor (Worker B) and the voice entry
 * (Worker C) implement / consume. Every interface here is free of
 * Android framework types: pure Kotlin + [ScreenModel] + [TaskStepKind]
 * only. (ScreenModel itself carries `android.graphics.Rect` bounds;
 * that is the screen model's own type, not a framework dependency of
 * these contracts — [TargetBounds] is the plain-Kotlin bounds form
 * used across this seam so implementors never import android.* here.)
 *
 * Nothing in this file executes anything: these are the boundaries
 * the planner core is tested against with fakes.
 */

/** Plain-Kotlin rectangle, mirroring `android.graphics.Rect` fields. */
data class TargetBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** App resolution, mirroring `execution:search`'s AppResolver shape
 *  exactly (same method, same result variants) so Worker B can adapt
 *  AppCatalogue / SearchExecutor's AppResolver directly with a
 *  one-line adapter — this module does NOT depend on
 *  `execution:search`, so no new module edge is created here. */
sealed class AppResolution {
    data class Found(val packageName: String, val label: String) : AppResolution()
    data object NotFound : AppResolution()
    data class Ambiguous(val candidates: List<String>) : AppResolution()
}

/** Resolves a spoken app name to a package. Production: AppCatalogue.resolveByName. */
fun interface AppResolver {
    fun resolve(name: String): AppResolution
}

/** Result of handing one step to the executor's dispatch sink. */
sealed class StepDispatchResult {
    /** The step was accepted for dispatch (NOT a claim it succeeded —
     *  verification happens against a fresh snapshot afterwards). */
    data object DISPATCHED : StepDispatchResult()

    /** Dispatch failed; [reason] is what David is told, verbatim. */
    data class FAILED(val reason: String) : StepDispatchResult()

    /** The gate / policy refused this step outright (e.g. sensitive
     *  target, financial action, no token); [reason] names the rule. */
    data class REFUSED(val reason: String) : StepDispatchResult()
}

/**
 * Dispatches one planned step against the current screen.
 * Implemented by Worker B. Never called by the planner itself —
 * the planner only produces plans.
 */
interface StepDispatcher {
    fun dispatch(step: PlanStep, screen: ScreenModel): StepDispatchResult

    /**
     * AOIE Phase 2 — dismiss the on-screen keyboard without
     * submitting (the real "dismiss-and-retry" recovery: global
     * Back dismisses the keyboard). Default refuses honestly so
     * existing fakes keep compiling; PlanStepDispatcher implements
     * it with the cover-aware device.back token.
     */
    fun dismissKeyboard(): StepDispatchResult =
        StepDispatchResult.FAILED("Keyboard dismiss is not available.")
}

/**
 * Fresh screen reads. Every call returns a NEW snapshot from the
 * live tree — never a cached model — so TOCTOU re-resolution and
 * VERIFY_OUTCOME always judge the screen as it is now.
 */
interface ScreenObserver {
    fun observeNow(): ScreenModel
}

/**
 * Pointer guidance for a planned step. Display-only: moving or
 * showing the pointer never taps, types, or submits anything.
 */
interface PointerGuide {
    /** Moves the pointer to [bounds]; [highlight] draws the target ring. */
    fun moveTo(bounds: TargetBounds, highlight: Boolean)

    /** Shows the intended step (tooltip / highlight) without moving. */
    fun showIntended(step: PlanStep)

    /**
     * Re-resolves [targetId] (a ScreenElement id) against the live
     * tree and tracks it. Returns false when the target is gone —
     * the executor must then treat it as target-gone (ask or abort),
     * never tap stale coordinates. A pointer-DRAW failure inside the
     * track (the target is there, only the draw failed) returns true
     * and is reported through [lastPointerFailure] instead — a draw
     * failure is never reported as "target gone".
     */
    fun trackTarget(targetId: String): Boolean

    /**
     * Pointer-drive fix (2026-11-06): the last pointer-drive failure,
     * if the most recent move / show / track attempt could not be
     * shown (no renderer, marshal timeout, policy HIDE, draw threw).
     * Null when the last attempt drew fine, or when there was nothing
     * to draw. The executor reads this after every pointer call and
     * names it in the plan outcome — a display failure never fails
     * the action, but it is never silent. A successful draw clears
     * it. Default null so existing implementers keep compiling.
     */
    fun lastPointerFailure(): String? = null

    /**
     * Pointer-drive fix (2026-11-06): how long the caller should wait
     * after a move for the pointer to ARRIVE before dispatching — the
     * glide duration for the move to [bounds] (250ms baseline, capped
     * at 300ms — PointerMotion). The pointer moves first and the action
     * happens only after it arrives. Default 0 (no live renderer to
     * wait for — fakes and guides without a renderer).
     */
    fun glideWaitMs(bounds: TargetBounds): Long = 0L

    /**
     * Plan over: release the plan target so the pointer may idle
     * again. Called by the executor in its plan-completion path for
     * every outcome (completed / failed / interrupted). Display-only.
     */
    fun clearPlanTarget()

    /**
     * MASTER Wave 4 (F11 strengthening): park at the neutral home
     * position when there is no verified target — visible, but
     * claiming nothing. The orb never parks on an unverified element
     * (a call-log row, a post, a banner). Default no-op so existing
     * implementers keep compiling; PlanPointerGuide implements it
     * from the live screen size.
     */
    fun parkAtNeutralHome() {}
}

/** Result of asking for the per-sequence cover for a whole plan. */
sealed class CoverResult {
    /** The whole plan is covered; steps run without further cards
     *  (per-sequence model, Stage 2D confirmation decision). */
    data object GRANTED : CoverResult()

    /** No cover; [reason] names why (declined, timed out, sensitive). */
    data class DENIED(val reason: String) : CoverResult()
}

/**
 * Per-sequence authorization cover (Stage 2D confirmation model,
 * corrected 2026-11-06: the plan is LOOM's internal mechanism, NOT
 * David's to see or approve. He approves sequence plans IN ADVANCE,
 * in blanket form — a DIRECT_USER plan runs under that blanket
 * approval with no card and no plan shown; a MODEL plan still goes
 * through the per-sequence card, because a model-guessed command
 * never inherits direct-user authorization). Implemented by Worker B
 * over AuthGate's `demo.sequence`-style cover. AuthGate still gates
 * each action class underneath.
 */
interface PlanAuthCover {
    /** Requests cover for the whole [plan] (one card, one decision). */
    fun requestPlanCover(plan: TaskPlan): CoverResult

    /** True when [step] is covered by the live plan cover. */
    fun checkCover(step: PlanStep): Boolean
}

/**
 * The stop signal. True when EITHER independent stop fired — David
 * said / tapped stop, OR the system halted the sequence (service
 * death, shutdown, AuthGate purge). The executor checks this before
 * every step; the planner never clears it.
 */
interface PlanStopSignal {
    fun isStopped(): Boolean
}

/** Audit sink. Production: the append-only audit log. Never logs
 *  screen content — step names, indices and outcomes only. */
interface PlanAuditSink {
    fun log(planId: String, stepIndex: Int, event: String, detail: String)
}

/**
 * Simple-task execution fix (2026-11-07, David's Nov 7 12:38
 * directive) — the ONE task-state vocabulary every host speaks.
 * The executor owns the state; hosts only deliver it. Five states,
 * no more: a task is either running, done, partly done, failed, or
 * blocked on a question for David. There is no sixth state and no
 * host-local wording — the same status reaches the spoken ack AND
 * the shared conversation store, so the chat can never disagree
 * with what LOOM said out loud.
 */
enum class TaskStatus {
    /** The plan is running; David has been told it started. */
    IN_PROGRESS,

    /** Every step verified. */
    COMPLETED,

    /** Some steps verified, then a step failed or the task was
     *  stopped — honest partial progress, never "done". */
    PARTIALLY_COMPLETED,

    /** Nothing verified (or the plan could not be built at all). */
    FAILED,

    /** Blocked on a question only David can answer (a missing app,
     *  a target that is gone). The task waits; it has not failed. */
    WAITING_FOR_USER_INPUT,
}

/** One task-state delivery: the state plus its plain-words text. */
data class TaskStatusUpdate(
    val status: TaskStatus,
    /** Plain words: what was done, what failed, what LOOM needs. */
    val message: String,
    /** David's instruction, verbatim — the task this state is about. */
    val goal: String,
)

/** Sink for [TaskStatusUpdate]s. Production: LoomPlanRunner fans
 *  each update out to the calling host, which speaks it AND appends
 *  it to the shared conversation store. A throwing sink never fails
 *  the plan — the executor swallows sink errors. */
fun interface TaskStatusSink {
    fun post(update: TaskStatusUpdate)
}

/**
 * Plain-words text for a [TaskStatusUpdate] from an executor
 * [PlanOutcome] summary. The summary already names what happened;
 * this only prefixes the state where the summary alone would read
 * as done: a partial run says so up front, and an in-progress
 * update names the goal instead of a summary that does not exist yet.
 */
fun taskStatusMessage(status: TaskStatus, goal: String, summary: String): String = when (status) {
    // Device-failure fix (2026-11-13, F2) — per-step chat state:
    // an IN_PROGRESS update with a non-blank summary IS the step
    // line (the executor posts one per completed step); the
    // blank-summary start post keeps the old wording.
    TaskStatus.IN_PROGRESS -> if (summary.isBlank()) "Working on it — $goal." else summary
    TaskStatus.COMPLETED -> summary
    TaskStatus.PARTIALLY_COMPLETED -> "Partly done — $summary"
    TaskStatus.FAILED -> summary
    TaskStatus.WAITING_FOR_USER_INPUT -> summary
}
