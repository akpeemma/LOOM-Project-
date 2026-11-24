package com.loom.assistant.execution.tasks

import com.loom.assistant.intelligence.planner.PlanOutcome
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStatus
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskPlanner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 2D (Worker B) — the single-active-plan queue (Brahma TaskQueue port).
 *
 * One plan at a time: a second [submit] while one runs returns
 * [SubmitResult.Busy] with the running plan's id — never queues silently
 * behind it, never runs two plans at once. The queue plans first (so it
 * can hand out the plan id), then runs the already-built plan through an
 * executor from [executorFactory] on a background thread.
 *
 * [cancel] flips the run's stop signal: the executor halts at the next
 * check, the remaining steps stay UNVERIFIED (they never ran), and the
 * record ends CANCELLED. The outcome (or the cancel) is kept per plan
 * id for [getStatus]/[getOutcome].
 *
 * The queue never plans or executes on the calling thread: [submit]
 * returns as soon as the plan is built and the run is started.
 */
enum class PlanQueueStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

sealed class SubmitResult {
    /** The plan was accepted; [planId] is the id to watch / cancel. */
    data class Accepted(val planId: String) : SubmitResult()

    /** A plan is already running; [runningPlanId] names it. */
    data class Busy(val runningPlanId: String) : SubmitResult()

    /** No plan could be built ([reason] is what David is told). */
    data class Rejected(val reason: String) : SubmitResult()
}

data class PlanQueueRecord(
    val planId: String,
    val goal: String,
    val status: PlanQueueStatus,
    val outcome: PlanOutcome?,
)

/**
 * The stop signal for one queue run: the queue's own cancel flag OR the
 * host's external signal (halt + lifecycle). The executor is built with
 * this instance, so [cancel] reaches the running plan through the same
 * [PlanStopSignal] the executor checks before every step.
 */
class QueueStopSignal(private val external: PlanStopSignal? = null) : PlanStopSignal {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun reset() {
        cancelled.set(false)
    }

    fun isCancelled(): Boolean = cancelled.get()

    override fun isStopped(): Boolean =
        cancelled.get() || external?.isStopped() == true
}

class TaskPlanQueue(
    private val planner: TaskPlanner,
    /** Builds a fresh executor per run (per-plan origin + fresh cover). */
    private val executorFactory: (stop: PlanStopSignal, request: PlanRequest) -> TaskPlanExecutor,
    private val externalStop: PlanStopSignal? = null,
) {

    private val lock = Any()

    @Volatile
    private var active: ActiveRun? = null

    private val records = mutableMapOf<String, PlanQueueRecord>()

    private data class ActiveRun(
        val planId: String,
        val stop: QueueStopSignal,
        @Volatile var cancelled: Boolean = false,
    )

    /**
     * Plans [request] and starts it, or refuses honestly. The plan id
     * comes from the planner's own [TaskPlan.id] — the queue never
     * invents one.
     */
    fun submit(request: PlanRequest): SubmitResult {
        val plan = synchronized(lock) {
            val running = active
            if (running != null) {
                return SubmitResult.Busy(running.planId)
            }
            when (val result = safePlan(request)) {
                is TaskPlanner.PlanResult.Planned -> result.plan
                is TaskPlanner.PlanResult.ClarificationNeeded ->
                    return SubmitResult.Rejected(result.question)
                is TaskPlanner.PlanResult.RefusedSensitive ->
                    return SubmitResult.Rejected(
                        result.reason,
                    )
                is TaskPlanner.PlanResult.Refused ->
                    return SubmitResult.Rejected(result.reason)
                is TaskPlanner.PlanResult.Unsupported ->
                    return SubmitResult.Rejected(result.reason)
            }
        }
        val stop = QueueStopSignal(externalStop)
        val run = ActiveRun(plan.id, stop)
        synchronized(lock) {
            // A second submit may have landed while planning: re-check.
            val running = active
            if (running != null) {
                return SubmitResult.Busy(running.planId)
            }
            active = run
            records[plan.id] = PlanQueueRecord(
                planId = plan.id,
                goal = plan.goal,
                status = PlanQueueStatus.RUNNING,
                outcome = null,
            )
        }
        val executor = executorFactory(stop, request)
        Thread({
            val outcome = try {
                executor.executePlan(plan, request)
            } catch (e: Exception) {
                PlanOutcome(
                    status = PlanStatus.FAILED,
                    verifiedSteps = 0,
                    totalSteps = plan.steps.size,
                    failedStep = null,
                    summary = "That task stopped unexpectedly: ${e.message ?: "unknown error"}",
                )
            }
            finish(run, outcome)
        }, "loom-plan-queue").start()
        return SubmitResult.Accepted(plan.id)
    }

    /**
     * Cancels the running plan (no-op false when [planId] is not the
     * active run). The stop signal halts the executor; remaining steps
     * stay UNVERIFIED and the record ends CANCELLED.
     */
    fun cancel(planId: String): Boolean {
        val run = synchronized(lock) { active } ?: return false
        if (run.planId != planId) return false
        run.cancelled = true
        run.stop.cancel()
        synchronized(lock) {
            val record = records[planId]
            if (record != null && record.status == PlanQueueStatus.RUNNING) {
                records[planId] = record.copy(status = PlanQueueStatus.CANCELLED)
            }
        }
        return true
    }

    /** The status of a submitted plan, or null when unknown. */
    fun getStatus(planId: String): PlanQueueStatus? = synchronized(lock) {
        records[planId]?.status
    }

    /** The honest outcome of a finished plan, or null while it runs / unknown. */
    fun getOutcome(planId: String): PlanOutcome? = synchronized(lock) {
        records[planId]?.outcome
    }

    /** True while a plan is running. */
    fun isBusy(): Boolean = active != null

    private fun finish(run: ActiveRun, outcome: PlanOutcome) {
        synchronized(lock) {
            if (active?.planId == run.planId) active = null
            val status = when {
                run.cancelled -> PlanQueueStatus.CANCELLED
                outcome.status == PlanStatus.COMPLETED -> PlanQueueStatus.COMPLETED
                outcome.status == PlanStatus.INTERRUPTED -> PlanQueueStatus.INTERRUPTED
                outcome.status == PlanStatus.CANCELLED -> PlanQueueStatus.CANCELLED
                else -> PlanQueueStatus.FAILED
            }
            val existing = records[run.planId]
            // A cancel already marked CANCELLED: keep it (a late
            // INTERRUPTED outcome must not overwrite the cancel).
            if (existing?.status == PlanQueueStatus.CANCELLED) {
                records[run.planId] = existing.copy(outcome = outcome)
            } else {
                records[run.planId] = PlanQueueRecord(
                    planId = run.planId,
                    goal = existing?.goal ?: "",
                    status = status,
                    outcome = outcome,
                )
            }
        }
    }

    private fun safePlan(request: PlanRequest): TaskPlanner.PlanResult = try {
        planner.plan(request)
    } catch (e: Exception) {
        TaskPlanner.PlanResult.Unsupported("I couldn't build a plan for that: ${e.message}")
    }
}
