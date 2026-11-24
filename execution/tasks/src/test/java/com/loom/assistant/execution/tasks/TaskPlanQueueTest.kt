package com.loom.assistant.execution.tasks

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.CoverResult
import com.loom.assistant.intelligence.planner.PlanAuditSink
import com.loom.assistant.intelligence.planner.PlanAuthCover
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.StepDispatcher
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskPlanner
import com.loom.assistant.intelligence.planner.TaskStepKind
import com.loom.assistant.intelligence.planner.TargetBounds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stage 2D (Worker B) — TaskPlanQueue checks.
 *
 * Same kotlinc recipe as TaskPlanExecutorTest, with this file in place
 * of TaskPlanExecutorTest.kt (plus TaskPlanQueue.kt), run as
 * com.loom.assistant.execution.tasks.TaskPlanQueueTestKt.
 *
 * The fake dispatcher blocks inside SEARCH_IN_APP until the test
 * releases its latch, so the busy / cancel cases can observe a live
 * run instead of racing a finished one.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private const val Q_YTM_PKG = "com.google.android.apps.youtube.music"

private fun qEl(id: String, text: String?, role: ElementRole = ElementRole.TEXT) =
    ScreenElement(
        id = id,
        text = text,
        contentDescription = null,
        className = "android.widget.TextView",
        bounds = Rect(0, 0, 100, 50),
        role = role,
        enabled = true,
        isPassword = false,
    )

private fun qHome() = ScreenModel(
    packageName = Q_YTM_PKG,
    elements = listOf(
        qEl("search-field", null, ElementRole.SEARCH_FIELD),
        qEl("row-fall", "Davido — Fall"),
    ),
)

private fun qPlaying() = ScreenModel(
    packageName = Q_YTM_PKG,
    elements = listOf(
        qEl("now-playing", "Now playing: Davido — Fall"),
        qEl("row-fall", "Davido — Fall"),
    ),
)

/**
 * Simple-task execution fix (2026-11-07): the SEARCH step must
 * move the world to a RESULTS screen, not back to the home screen
 * — the executor only verifies a search when results actually
 * appear (new rows vs the before screen). The old fake returned
 * the home screen, which shows no evidence any search ran.
 */
private fun qResults() = ScreenModel(
    packageName = Q_YTM_PKG,
    elements = listOf(
        qEl("search-field", null, ElementRole.SEARCH_FIELD),
        qEl("res-fall", "Davido — Fall"),
        qEl("res-if", "Davido — If"),
    ),
)

private val qApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" -> AppResolution.Found(Q_YTM_PKG, "YT Music")
        "youtube" -> AppResolution.Found("com.google.android.youtube", "YouTube")
        else -> AppResolution.NotFound
    }
}

private class QWorld(var screen: ScreenModel)

private class QObserver(private val world: QWorld) : ScreenObserver {
    override fun observeNow(): ScreenModel = world.screen
}

private class QPointer : PointerGuide {
    override fun moveTo(bounds: TargetBounds, highlight: Boolean) {}
    override fun showIntended(step: PlanStep) {}
    override fun trackTarget(targetId: String): Boolean = true
    override fun clearPlanTarget() {}
}

private class QCover : PlanAuthCover {
    override fun requestPlanCover(plan: TaskPlan): CoverResult = CoverResult.GRANTED
    override fun checkCover(step: PlanStep): Boolean = true
}

private class QAudit : PlanAuditSink {
    override fun log(planId: String, stepIndex: Int, event: String, detail: String) {}
}

/** Blocks in SEARCH until [gate] is released (null = never block). */
private class QDispatcher(private val world: QWorld) : StepDispatcher {
    val calls = mutableListOf<TaskStepKind>()
    @Volatile var gate: CountDownLatch? = null

    override fun dispatch(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        calls.add(step.kind)
        return when (step.kind) {
            TaskStepKind.SEARCH_IN_APP -> {
                gate?.await(10, TimeUnit.SECONDS)
                // Universal Console (2026-11-07): goal-aware like the
                // real search sequence — a play search opens the
                // first result itself (world → playing); a plain
                // search stops at the results list.
                val goal = step.params[PlanParams.SEARCH_GOAL].orEmpty()
                world.screen = if (goal == PlanParams.SEARCH_GOAL_OPEN_FIRST) {
                    qPlaying()
                } else {
                    qResults()
                }
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.TAP_TARGET -> {
                world.screen = qPlaying()
                StepDispatchResult.DISPATCHED
            }
            else -> StepDispatchResult.DISPATCHED
        }
    }
}

private fun qReq(goal: String) =
    PlanRequest(goal = goal, origin = RequestOrigin.DIRECT_USER, transparency = true)

private fun waitForStatus(
    queue: TaskPlanQueue,
    planId: String,
    want: PlanQueueStatus,
    timeoutMs: Long = 5_000,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (queue.getStatus(planId) == want) return true
        Thread.sleep(25)
    }
    return queue.getStatus(planId) == want
}

private fun waitForOutcome(
    queue: TaskPlanQueue,
    planId: String,
    timeoutMs: Long = 5_000,
): com.loom.assistant.intelligence.planner.PlanOutcome? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        queue.getOutcome(planId)?.let { return it }
        Thread.sleep(25)
    }
    return queue.getOutcome(planId)
}

fun main() {
    val goal = "Open YouTube Music and play a song by Davido"

    // ---------- 1. submit → accepted → completes ----------
    run {
        val world = QWorld(qHome())
        val dispatcher = QDispatcher(world)
        val queue = TaskPlanQueue(TaskPlanner(qApps), executorFactory = { stop: PlanStopSignal, _: PlanRequest ->
            TaskPlanExecutor(
                apps = qApps,
                dispatcher = dispatcher,
                observer = QObserver(world),
                pointer = QPointer(),
                cover = QCover(),
                stop = stop,
                audit = QAudit(),
            )
        })
        val submitted = queue.submit(qReq(goal))
        check("submit accepted", submitted is SubmitResult.Accepted)
        val planId = (submitted as SubmitResult.Accepted).planId
        check("completes", waitForStatus(queue, planId, PlanQueueStatus.COMPLETED))
        val outcome = queue.getOutcome(planId)
        check("outcome completed", outcome?.status?.name == "COMPLETED")
        check("queue idle after run", !queue.isBusy())
    }

    // ---------- 2. second submit while running → Busy ----------
    run {
        val world = QWorld(qHome())
        val dispatcher = QDispatcher(world)
        val gate = CountDownLatch(1)
        dispatcher.gate = gate
        val queue = TaskPlanQueue(TaskPlanner(qApps), executorFactory = { stop: PlanStopSignal, _: PlanRequest ->
            TaskPlanExecutor(
                apps = qApps,
                dispatcher = dispatcher,
                observer = QObserver(world),
                pointer = QPointer(),
                cover = QCover(),
                stop = stop,
                audit = QAudit(),
            )
        })
        val first = queue.submit(qReq(goal))
        check("first accepted", first is SubmitResult.Accepted)
        val firstId = (first as SubmitResult.Accepted).planId
        Thread.sleep(200) // let the run reach the blocked SEARCH
        val second = queue.submit(qReq(goal))
        check("second is busy, never queued silently", second is SubmitResult.Busy)
        checkEq(
            "busy names the running plan",
            (second as? SubmitResult.Busy)?.runningPlanId,
            firstId,
        )
        gate.countDown()
        check("first completes after release", waitForStatus(queue, firstId, PlanQueueStatus.COMPLETED))
    }

    // ---------- 3. cancel → CANCELLED, remaining steps UNVERIFIED ----------
    run {
        val world = QWorld(qHome())
        val dispatcher = QDispatcher(world)
        val gate = CountDownLatch(1)
        dispatcher.gate = gate
        val queue = TaskPlanQueue(TaskPlanner(qApps), executorFactory = { stop: PlanStopSignal, _: PlanRequest ->
            TaskPlanExecutor(
                apps = qApps,
                dispatcher = dispatcher,
                observer = QObserver(world),
                pointer = QPointer(),
                cover = QCover(),
                stop = stop,
                audit = QAudit(),
            )
        })
        val submitted = queue.submit(qReq(goal))
        val planId = (submitted as SubmitResult.Accepted).planId
        Thread.sleep(200)
        check("cancel accepted", queue.cancel(planId))
        gate.countDown()
        check("cancelled status", waitForStatus(queue, planId, PlanQueueStatus.CANCELLED))
        val outcome = waitForOutcome(queue, planId)
        check("cancel leaves steps unverified", outcome != null && outcome.verifiedSteps < outcome.totalSteps)
        check("cancel never taps", TaskStepKind.TAP_TARGET !in dispatcher.calls)
        check("cancel unknown id is false", !queue.cancel("no-such-plan"))
    }

    // ---------- 4. unplannable goal → Rejected, nothing runs ----------
    run {
        val world = QWorld(qHome())
        val dispatcher = QDispatcher(world)
        val queue = TaskPlanQueue(TaskPlanner(qApps), executorFactory = { stop: PlanStopSignal, _: PlanRequest ->
            TaskPlanExecutor(
                apps = qApps,
                dispatcher = dispatcher,
                observer = QObserver(world),
                pointer = QPointer(),
                cover = QCover(),
                stop = stop,
                audit = QAudit(),
            )
        })
        val result = queue.submit(qReq("open"))
        check("unplannable rejected", result is SubmitResult.Rejected)
        check("rejected names the missing app", (result as? SubmitResult.Rejected)?.reason?.contains("Which app") == true)
        checkEq("unknown id has no status", queue.getStatus("no-such-plan"), null)
        check("queue idle after rejection", !queue.isBusy())
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
