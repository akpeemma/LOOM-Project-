package com.loom.assistant.execution.tasks

import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.TargetBounds

/**
 * App-aware execution (2026-11-07, David's directive §4) — the
 * pointer is a real interaction mechanism, not decoration.
 *
 * Proves the pointer → tap link at the coordinator level: the tap
 * is issued AT the exact bounds the pointer parked on (same bounds
 * object), only after the pointer arrived, and a draw failure is
 * reported but never fails the tap.
 *
 * Run (from loom-android), see run-2c-tests.sh `ptrcoord`:
 *   kotlinc -d out -jvm-target 17 [Rect fake] [RequestOrigin]
 *     [ScreenModel] [planner main] [PlanVerify]
 *     PointerTapCoordinator.kt PointerTapCoordinationTest.kt
 *   java -cp out com.loom.assistant.execution.tasks.PointerTapCoordinationTestKt
 */
private var ptPassed = 0
private var ptFailed = 0

private fun ptCheck(name: String, actual: Boolean) {
    if (actual) {
        ptPassed++
    } else {
        ptFailed++
        println("FAIL: $name")
    }
}

private fun ptCheckEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        ptPassed++
    } else {
        ptFailed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

/** Records the order of pointer calls and tap calls. */
private class PtFakePointer : PointerGuide {
    val events = mutableListOf<String>()
    var glideWaitMsValue: Long = 0L
    var failWith: String? = null
    var throwOnMove = false
    var lastFailure: String? = null

    override fun moveTo(bounds: TargetBounds, highlight: Boolean) {
        events.add("moveTo(${bounds.left},${bounds.top})")
        if (throwOnMove) {
            lastFailure = "the pointer could not be shown (IllegalStateException)"
            throw IllegalStateException("fake draw threw")
        }
        lastFailure = failWith
    }

    override fun showIntended(step: PlanStep) {
        events.add("showIntended")
        lastFailure = failWith
    }

    override fun trackTarget(targetId: String): Boolean {
        events.add("trackTarget")
        return true
    }

    override fun clearPlanTarget() {
        events.add("clearPlanTarget")
    }

    override fun lastPointerFailure(): String? = lastFailure

    override fun glideWaitMs(bounds: TargetBounds): Long {
        events.add("glideWaitMs")
        return glideWaitMsValue
    }
}

fun main() {
    val bounds = TargetBounds(10, 120, 500, 200)

    // ---- tap point == pointer position (same bounds object) ----
    run {
        val pointer = PtFakePointer()
        var tapReceived: TargetBounds? = null
        var tapReceivedSameObject = false
        val coordinator = PointerTapCoordinator()
        val (result, evidence) = coordinator.coordinateTap(bounds, "row-fall", pointer) { b ->
            tapReceived = b
            tapReceivedSameObject = (b === bounds)
            StepDispatchResult.DISPATCHED
        }
        ptCheckEq("tap dispatched", result, StepDispatchResult.DISPATCHED)
        ptCheckEq("evidence tap point == parked bounds", evidence.tapPoint, evidence.lastPointerBounds)
        ptCheckEq("evidence tap point == the bounds passed in", evidence.tapPoint, bounds)
        ptCheck("tap received the SAME bounds object", tapReceivedSameObject)
        ptCheck("tap received the parked bounds", tapReceived == bounds)
        ptCheckEq("no pointer failure", evidence.pointerFailure, null)
        ptCheckEq("arrived before tap", evidence.arrivedBeforeTap, true)
    }

    // ---- tap happens after arrival (order recorded) ----
    run {
        val pointer = PtFakePointer()
        pointer.glideWaitMsValue = 250L
        val order = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        val coordinator = PointerTapCoordinator(sleeper = { ms -> sleeps.add(ms) })
        // Re-record with a shared order list: the tap lambda appends
        // to it, so the assertion proves move → glide → tap.
        val trackingPointer = object : PointerGuide by pointer {
            override fun moveTo(bounds: TargetBounds, highlight: Boolean) {
                order.add("move")
                pointer.moveTo(bounds, highlight)
            }

            override fun glideWaitMs(bounds: TargetBounds): Long {
                order.add("glide")
                return pointer.glideWaitMs(bounds)
            }
        }
        coordinator.coordinateTap(bounds, "row-fall", trackingPointer) {
            order.add("tap")
            StepDispatchResult.DISPATCHED
        }
        ptCheckEq("order is move → glide → tap", order, listOf("move", "glide", "tap"))
        ptCheckEq("glide wait honored before the tap", sleeps, listOf(250L))
    }

    // ---- draw failure reported but tap still proceeds ----
    run {
        val pointer = PtFakePointer()
        pointer.failWith = "no renderer: pointer could not be shown"
        var tapped = false
        val coordinator = PointerTapCoordinator()
        val (result, evidence) = coordinator.coordinateTap(bounds, "row-fall", pointer) {
            tapped = true
            StepDispatchResult.DISPATCHED
        }
        ptCheck("tap still proceeds on draw failure", tapped)
        ptCheckEq("dispatch still returns", result, StepDispatchResult.DISPATCHED)
        ptCheckEq(
            "draw failure named in evidence",
            evidence.pointerFailure,
            "no renderer: pointer could not be shown",
        )
        // MASTER Wave 4 (F11): a draw failure is NOT arrival — the
        // old code claimed arrivedBeforeTap=true whenever moveTo did
        // not throw, even when nothing rendered.
        ptCheckEq("not arrived when the draw failed", evidence.arrivedBeforeTap, false)
        ptCheckEq("tap point still the parked bounds", evidence.tapPoint, bounds)
    }

    // ---- moveTo throws: reported, tap still proceeds, not "arrived" ----
    run {
        val pointer = PtFakePointer()
        pointer.throwOnMove = true
        var tapped = false
        val coordinator = PointerTapCoordinator()
        val (_, evidence) = coordinator.coordinateTap(bounds, "row-fall", pointer) {
            tapped = true
            StepDispatchResult.DISPATCHED
        }
        ptCheck("tap still proceeds when the move throws", tapped)
        ptCheck("draw failure named in evidence", evidence.pointerFailure != null)
        ptCheckEq("not arrived when the move threw", evidence.arrivedBeforeTap, false)
    }

    // ---- a failed tap is reported, not hidden ----
    run {
        val pointer = PtFakePointer()
        val coordinator = PointerTapCoordinator()
        val (result, evidence) = coordinator.coordinateTap(bounds, "row-fall", pointer) {
            StepDispatchResult.FAILED("target gone")
        }
        ptCheckEq("failed tap returned", result, StepDispatchResult.FAILED("target gone"))
        ptCheckEq("tap point still recorded", evidence.tapPoint, bounds)
    }

    println("PointerTapCoordinationTest: $ptPassed passed, $ptFailed failed")
    if (ptFailed == 0) {
        println("ALL $ptPassed TESTS PASSED")
    } else {
        throw AssertionError("$ptFailed test(s) failed")
    }
}
