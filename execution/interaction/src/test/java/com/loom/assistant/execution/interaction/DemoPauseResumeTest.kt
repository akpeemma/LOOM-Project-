package com.loom.assistant.execution.interaction

import android.graphics.Rect
import com.loom.assistant.core.common.LoomStateEvent

/**
 * JVM tests for genuine demo pause/continue (closure decision 3, §7-D).
 *
 * Proves: "pause" mid-demo freezes the queue deterministically (zero
 * dispatches while paused, step index + resolved targets preserved);
 * "continue"/"resume" performs TOCTOU re-resolution + screen-context
 * revalidation before any dispatch; unresolvable target → honest
 * TargetLost (never a guess) with restart-from-step / abort offers;
 * "stop" stays halt-with-INTERRUPTED (halt-and-restart is not pause/resume);
 * pause with no demo → "Nothing is running."; resume without pause → no-op.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name $detail")
    }
}

/** Starts a 2-step plan parked at step 1's completion callback. */
private fun startParked(rig: QueueRig): Pair<ActionPlan, () -> SequenceOutcome?> {
    val target = el("b1", "Calculator")
    rig.tree.modelProvider = { model("com.demo.app", target) }
    var outcome: SequenceOutcome? = null
    val plan = demoPlan(planStep("s1", "Calculator"), planStep("s2", "Calculator"))
    rig.queue.start(plan) { outcome = it }
    return plan to { outcome }
}

fun main() {
    initGateForTests()

    // 1. Pause with no demo → NothingRunning ("Nothing is running.").
    run {
        val rig = newRig()
        val r = rig.queue.pause()
        check("pause with no demo → NothingRunning", r is ActionQueue.PauseResult.NothingRunning, r.toString())
        check("not paused", !rig.queue.isDemoPaused())
        check("no active demo", !rig.queue.isDemoActive())
    }

    // 2. Pause mid-demo freezes: zero dispatches while paused, index preserved.
    run {
        val rig = newRig()
        val (_, getOutcome) = startParked(rig)
        check("step 1 dispatched", rig.dispatcher.calls.size == 1)
        val pr = rig.queue.pause()
        check("pause → Paused", pr is ActionQueue.PauseResult.Paused, pr.toString())
        check(
            "paused at step index 0",
            (pr as ActionQueue.PauseResult.Paused).stepIndex == 0,
            pr.toString(),
        )
        check("isDemoPaused", rig.queue.isDemoPaused())
        // Step 1's completion lands while paused → stashed, not advanced.
        rig.completions.complete(GestureOutcome.COMPLETED)
        check("no dispatch while paused", rig.dispatcher.calls.size == 1, "calls=${rig.dispatcher.calls.size}")
        check("not completed while paused", getOutcome() == null)
        // Resume → TOCTOU re-resolution → step 2 dispatches → completes.
        val rr = rig.queue.resume()
        check("resume → Resumed", rr is ActionQueue.ResumeResult.Resumed, rr.toString())
        check(
            "resumed at step 1",
            (rr as ActionQueue.ResumeResult.Resumed).stepIndex == 1,
            rr.toString(),
        )
        check("step 2 dispatched after resume", rig.dispatcher.calls.size == 2, "calls=${rig.dispatcher.calls.size}")
        rig.completions.complete(GestureOutcome.COMPLETED)
        check("completes after resume", getOutcome() == SequenceOutcome.Completed)
        check("pause state cleared", !rig.queue.isDemoPaused() && !rig.queue.isDemoActive())
    }

    // 3. Resume without pause → harmless no-op ack.
    run {
        val rig = newRig()
        startParked(rig)
        val rr = rig.queue.resume()
        check("resume without pause → NotPaused", rr is ActionQueue.ResumeResult.NotPaused, rr.toString())
        check("still not paused", !rig.queue.isDemoPaused())
        // Clean up: finish the parked run.
        rig.completions.complete(GestureOutcome.COMPLETED)
        rig.completions.complete(GestureOutcome.COMPLETED)
    }

    // 4. Target lost on resume → honest TargetLost, stays paused, abort works.
    run {
        val rig = newRig()
        val (_, getOutcome) = startParked(rig)
        rig.queue.pause()
        rig.completions.complete(GestureOutcome.COMPLETED)
        // The target vanishes from the screen.
        rig.tree.modelProvider = { model("com.demo.app") }
        val rr = rig.queue.resume()
        check("resume → TargetLost", rr is ActionQueue.ResumeResult.TargetLost, rr.toString())
        check(
            "reason names target-lost",
            (rr as ActionQueue.ResumeResult.TargetLost).reason.contains("target-lost"),
            rr.reason,
        )
        check("stays paused after TargetLost", rig.queue.isDemoPaused())
        check("zero dispatches after failed resume", rig.dispatcher.calls.size == 1)
        check("abortPaused → true", rig.queue.abortPaused())
        check(
            "aborted with AbortedByUser",
            getOutcome() is SequenceOutcome.AbortedByUser,
            getOutcome().toString(),
        )
    }

    // 5. Target moved beyond tolerance → TargetLost (never guesses).
    run {
        val rig = newRig()
        startParked(rig)
        rig.queue.pause()
        rig.completions.complete(GestureOutcome.COMPLETED)
        // Same target, drifted far beyond TOCTOU_TOLERANCE_PX.
        rig.tree.modelProvider = {
            model("com.demo.app", el("b1", "Calculator", Rect(400, 400, 500, 450)))
        }
        val rr = rig.queue.resume()
        check("moved target → TargetLost", rr is ActionQueue.ResumeResult.TargetLost, rr.toString())
        check(
            "reason names the move",
            (rr as ActionQueue.ResumeResult.TargetLost).reason.contains("moved"),
            rr.reason,
        )
        check("no dispatch on moved target", rig.dispatcher.calls.size == 1)
    }

    // 6. Screen context changed (other app foreground) → TargetLost.
    run {
        val rig = newRig()
        startParked(rig)
        rig.queue.pause()
        rig.completions.complete(GestureOutcome.COMPLETED)
        rig.tree.modelProvider = { model("com.other.app", el("b1", "Calculator")) }
        val rr = rig.queue.resume()
        check("context change → TargetLost", rr is ActionQueue.ResumeResult.TargetLost, rr.toString())
        check(
            "reason names context",
            (rr as ActionQueue.ResumeResult.TargetLost).reason.contains("context"),
            rr.reason,
        )
    }

    // 7. resumeFromStep restarts from the offered step.
    run {
        val rig = newRig()
        val (_, getOutcome) = startParked(rig)
        rig.queue.pause()
        rig.completions.complete(GestureOutcome.COMPLETED)
        val rr = rig.queue.resumeFromStep(0)
        check("resumeFromStep(0) → Resumed(0)", rr is ActionQueue.ResumeResult.Resumed, rr.toString())
        check(
            "step 0 re-dispatched",
            rig.dispatcher.calls.size == 2,
            "calls=${rig.dispatcher.calls.size}",
        )
        rig.completions.complete(GestureOutcome.COMPLETED)
        rig.completions.complete(GestureOutcome.COMPLETED)
        check("completes", getOutcome() == SequenceOutcome.Completed)
    }

    // 8. "stop" stays halt-with-INTERRUPTED — halt-and-restart is not pause/resume.
    run {
        val rig = newRig()
        val (_, getOutcome) = startParked(rig)
        rig.halt.halted = true
        rig.halt.source = "voice"
        rig.completions.complete(GestureOutcome.COMPLETED)
        val outcome = getOutcome()
        check(
            "halt → Interrupted",
            outcome is SequenceOutcome.Interrupted,
            outcome.toString(),
        )
        // After halt the run is over: pause finds nothing running.
        val pr = rig.queue.pause()
        check(
            "pause after halt → NothingRunning (run is over)",
            pr is ActionQueue.PauseResult.NothingRunning,
            pr.toString(),
        )
    }

    // 9. Double pause → AlreadyPaused; double start refused.
    run {
        val rig = newRig()
        startParked(rig)
        rig.queue.pause()
        val again = rig.queue.pause()
        check("second pause → AlreadyPaused", again is ActionQueue.PauseResult.AlreadyPaused, again.toString())
        var outcome2: SequenceOutcome? = null
        rig.queue.start(demoPlan(planStep("s9", "Calculator"))) { outcome2 = it }
        check(
            "second start refused while paused",
            outcome2 is SequenceOutcome.Failed,
            outcome2.toString(),
        )
    }

    println("demo-pause-resume: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILURES: $failed")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
