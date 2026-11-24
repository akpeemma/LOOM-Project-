package com.loom.assistant.platform.services

import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.CoverResult
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskStepKind

/**
 * Stage 2D (Worker B) — PlanCover checks.
 *
 * Run (from loom-android):
 *   KOTLINC=$HOME/workspace/brahma/loom/toolchain/kotlin/kotlinc/bin/kotlinc
 *   JAVA=$HOME/workspace/brahma/loom/toolchain/jdk-17/bin/java
 *   STDLIB=$HOME/workspace/brahma/loom/toolchain/kotlin/kotlinc/lib/kotlin-stdlib.jar
 *   R=$HOME/workspace/brahma/loom/build/loom-android
 *   H=$HOME/workspace/brahma/loom/build/test-harness
 *   $KOTLINC -d out -jvm-target 17 -cp $STDLIB \
 *     $H/fakes/android/graphics/Rect.kt \
 *     $R/core/common/src/main/java/com/loom/assistant/core/common/RequestOrigin.kt \
 *     $R/execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     $R/execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenDataPolicy.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanContracts.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/TaskPlan.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanRequest.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanValidator.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/TaskPlanner.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanFailureAnalyzer.kt \
 *     $R/platform/services/src/main/java/com/loom/assistant/platform/services/PlanCover.kt \
 *     $R/platform/services/src/test/java/com/loom/assistant/platform/services/PlanCoverTest.kt \
 *   && $JAVA -cp out:$STDLIB com.loom.assistant.platform.services.PlanCoverTestKt
 *
 * The fake gate stands in for AuthGate: the point under test is the
 * cover's own logic (origin passthrough, consume-once, stop-abandon,
 * scope checks) — the gate's grant/card behaviour is AuthGate's, and
 * the demo.sequence action carries no scope, so no origin can ever
 * inherit a stored grant for a plan.
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

private const val YTM_PKG = "com.google.android.apps.youtube.music"

private class CStop(var stopped: Boolean = false) : PlanStopSignal {
    override fun isStopped(): Boolean = stopped
}

private class FakeGate(private val stop: CStop) : CoverTokenRequest {
    var nextToken: String? = "tok-demo-1"
    var requests = 0
    var lastOrigin: RequestOrigin? = null
    var lastGoal: String? = null
    var lastAppLabel: String? = null
    var lastStepCount: Int? = null
    var lastScopePackage: String? = null
    /** When true, the stop fires inside the request (card-wait stop). */
    var flipStopDuringRequest = false

    override fun request(
        goal: String,
        appLabel: String,
        stepCount: Int,
        origin: RequestOrigin,
        scopePackage: String,
    ): String? {
        requests++
        lastGoal = goal
        lastAppLabel = appLabel
        lastStepCount = stepCount
        lastOrigin = origin
        lastScopePackage = scopePackage
        if (flipStopDuringRequest) stop.stopped = true
        return nextToken
    }
}

private class FakeConsumer(var consumeResult: Boolean = true) : CoverTokenConsumer {
    val consumed = mutableListOf<String>()
    override fun consume(tokenId: String): Boolean {
        consumed.add(tokenId)
        return consumeResult
    }
}

private val cApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" -> AppResolution.Found(YTM_PKG, "YT Music")
        else -> AppResolution.NotFound
    }
}

private fun coverPlan(origin: RequestOrigin = RequestOrigin.DIRECT_USER) = TaskPlan(
    id = "plan-1",
    goal = "Open YouTube Music and play a song by Davido",
    steps = listOf(
        PlanStep(
            0, TaskStepKind.LAUNCH_APP, "Open YouTube Music",
            mapOf(PlanParams.APP to "YouTube Music", PlanParams.PACKAGE to YTM_PKG),
        ),
        PlanStep(
            1, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
            mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first"),
        ),
    ),
    origin = origin,
)

private fun tapStep() = PlanStep(
    1, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first"),
)

fun main() {
    // ---------- 1. GRANTED (MODEL card path): consumed exactly once, scope recorded ----------
    run {
        val stop = CStop()
        val gate = FakeGate(stop)
        val consumer = FakeConsumer()
        val cover = PlanCover(gate, consumer, stop, cApps)
        val result = cover.requestPlanCover(coverPlan(RequestOrigin.MODEL))
        checkEq("granted", result, CoverResult.GRANTED)
        checkEq("token consumed exactly once", consumer.consumed, listOf("tok-demo-1"))
        checkEq("covered package recorded", cover.coveredPackage, YTM_PKG)
        checkEq("covered label recorded", cover.coveredAppLabel, "YouTube Music")
        check("launch step covered", cover.checkCover(coverPlan().steps[0]))
        check("tap step covered", cover.checkCover(tapStep()))
        checkEq("one gate request", gate.requests, 1)
        checkEq("demo.sequence names the goal", gate.lastGoal, "Open YouTube Music and play a song by Davido")
        checkEq("demo.sequence names the app", gate.lastAppLabel, "YouTube Music")
        checkEq("demo.sequence names the step count", gate.lastStepCount, 2)
    }

    // ---------- 2. DENIED on decline (MODEL card path): nothing consumed ----------
    run {
        val stop = CStop()
        val gate = FakeGate(stop).also { it.nextToken = null }
        val consumer = FakeConsumer()
        val cover = PlanCover(gate, consumer, stop, cApps)
        val result = cover.requestPlanCover(coverPlan(RequestOrigin.MODEL))
        check("decline denies", result is CoverResult.DENIED)
        checkEq("decline consumes nothing", consumer.consumed.size, 0)
        check("decline leaves no cover", !cover.checkCover(tapStep()))
        check("decline reason is plain", (result as CoverResult.DENIED).reason.contains("Not confirmed"))
    }

    // ---------- 3. origin split: MODEL cards, DIRECT_USER never reaches the gate ----------
    run {
        val stop = CStop()
        val gate = FakeGate(stop)
        val cover = PlanCover(gate, FakeConsumer(), stop, cApps)
        cover.requestPlanCover(coverPlan(RequestOrigin.MODEL))
        checkEq("MODEL origin passed through", gate.lastOrigin, RequestOrigin.MODEL)
        checkEq("MODEL asks the gate once", gate.requests, 1)
        val gate2 = FakeGate(CStop())
        val cover2 = PlanCover(gate2, FakeConsumer(), CStop(), cApps)
        val directResult = cover2.requestPlanCover(coverPlan(RequestOrigin.DIRECT_USER))
        checkEq("DIRECT_USER granted without a card", directResult, CoverResult.GRANTED)
        checkEq("DIRECT_USER never asks the gate", gate2.requests, 0)
        check("DIRECT_USER still covered", cover2.checkCover(tapStep()))
    }

    // ---------- 4. stop during the card wait (MODEL) abandons the token ----------
    run {
        val stop = CStop()
        val gate = FakeGate(stop).also { it.flipStopDuringRequest = true }
        val consumer = FakeConsumer()
        val cover = PlanCover(gate, consumer, stop, cApps)
        val result = cover.requestPlanCover(coverPlan(RequestOrigin.MODEL))
        check("card-wait stop denies", result is CoverResult.DENIED)
        checkEq("card-wait stop never consumes", consumer.consumed.size, 0)
        check("card-wait stop leaves no cover", !cover.checkCover(tapStep()))
        check("card-wait stop reason names the abandon", (result as CoverResult.DENIED).reason.contains("abandoned"))
    }

    // ---------- 5. stop after grant voids the cover ----------
    run {
        val stop = CStop()
        val cover = PlanCover(FakeGate(stop), FakeConsumer(), stop, cApps)
        cover.requestPlanCover(coverPlan())
        check("covered before stop", cover.checkCover(tapStep()))
        stop.stopped = true
        check("not covered after stop", !cover.checkCover(tapStep()))
    }

    // ---------- 6. out-of-scope step is not covered ----------
    run {
        val stop = CStop()
        val cover = PlanCover(FakeGate(stop), FakeConsumer(), stop, cApps)
        cover.requestPlanCover(coverPlan())
        val other = PlanStep(
            2, TaskStepKind.TAP_TARGET, "Tap elsewhere",
            mapOf(PlanParams.TARGET to "Home", PlanParams.PACKAGE to "com.other.app"),
        )
        check("out-of-scope step not covered", !cover.checkCover(other))
        val inherited = PlanStep(
            2, TaskStepKind.TAP_TARGET, "Tap a result",
            mapOf(PlanParams.TARGET to "Davido"),
        )
        check("package-less step inherits the plan app", cover.checkCover(inherited))
    }

    // ---------- 7. message / call steps are never covered ----------
    run {
        val stop = CStop()
        val cover = PlanCover(FakeGate(stop), FakeConsumer(), stop, cApps)
        cover.requestPlanCover(coverPlan())
        val message = PlanStep(
            0, TaskStepKind.MESSAGE_SEND, "Send",
            mapOf(
                PlanParams.RECIPIENT to "Mum",
                PlanParams.MESSAGE to "hi",
                PlanParams.CHANNEL to "whatsapp",
            ),
        )
        val call = PlanStep(
            0, TaskStepKind.CALL_PLACE, "Call",
            mapOf(PlanParams.RECIPIENT to "Mum"),
        )
        check("message never covered", !cover.checkCover(message))
        check("call never covered", !cover.checkCover(call))
    }

    // ---------- 8. consume failure (MODEL) → DENIED, no cover ----------
    run {
        val stop = CStop()
        val cover = PlanCover(FakeGate(stop), FakeConsumer(consumeResult = false), stop, cApps)
        val result = cover.requestPlanCover(coverPlan(RequestOrigin.MODEL))
        check("unconsumable token denies", result is CoverResult.DENIED)
        check("unconsumable token leaves no cover", !cover.checkCover(tapStep()))
    }

    // ---------- 9. clear() drops the cover ----------
    run {
        val stop = CStop()
        val cover = PlanCover(FakeGate(stop), FakeConsumer(), stop, cApps)
        cover.requestPlanCover(coverPlan())
        check("covered before clear", cover.checkCover(tapStep()))
        cover.clear()
        check("not covered after clear", !cover.checkCover(tapStep()))
    }

    // ---------- 10. DIRECT_USER blanket approval: no card, no token, audit-logged ----------
    // Corrected model (David, 2026-11-06 14:39): the plan is for LOOM,
    // not for David. His own words run under blanket advance approval.
    run {
        val stop = CStop()
        val gate = FakeGate(stop)
        val consumer = FakeConsumer()
        val events = mutableListOf<String>()
        val cover = PlanCover(gate, consumer, stop, cApps, onEvent = { events.add(it) })
        val result = cover.requestPlanCover(coverPlan(RequestOrigin.DIRECT_USER))
        checkEq("blanket approval grants", result, CoverResult.GRANTED)
        checkEq("blanket approval asks the gate never", gate.requests, 0)
        checkEq("blanket approval consumes nothing", consumer.consumed.size, 0)
        checkEq("blanket approval covers the plan app", cover.coveredPackage, YTM_PKG)
        check("blanket approval covers steps", cover.checkCover(tapStep()))
        check(
            "blanket approval logged as blanket approval",
            events.any { it.contains("blanket advance approval") },
        )
        check(
            "blanket approval never logged as a card approval",
            events.none { it.contains("card") },
        )
    }

    // ---------- 11. DIRECT_USER grant stands even when the gate would decline ----------
    // The gate is never consulted for his own words: a declining gate
    // cannot block a blanket-approved plan (and no card ever appears).
    run {
        val stop = CStop()
        val gate = FakeGate(stop).also { it.nextToken = null }
        val consumer = FakeConsumer()
        val cover = PlanCover(gate, consumer, stop, cApps)
        val result = cover.requestPlanCover(coverPlan(RequestOrigin.DIRECT_USER))
        checkEq("blanket approval ignores a declining gate", result, CoverResult.GRANTED)
        checkEq("declining gate never asked", gate.requests, 0)
        check("blanket approval covers steps", cover.checkCover(tapStep()))
    }

    // ---------- 12. DIRECT_USER + stop already fired: blanket approval does not override a stop ----------
    run {
        val stop = CStop(stopped = true)
        val gate = FakeGate(stop)
        val consumer = FakeConsumer()
        val cover = PlanCover(gate, consumer, stop, cApps)
        val result = cover.requestPlanCover(coverPlan(RequestOrigin.DIRECT_USER))
        check("stopped direct-user plan denied", result is CoverResult.DENIED)
        checkEq("stopped direct-user plan consumes nothing", consumer.consumed.size, 0)
        check("stopped direct-user plan leaves no cover", !cover.checkCover(tapStep()))
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
