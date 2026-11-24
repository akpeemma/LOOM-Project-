package com.loom.assistant.execution.interaction

/**
 * Master implementation §4 — tap-queue guard for non-TAP plan steps.
 *
 * TYPE/SUBMIT steps belong to the search sequence (SearchExecutor); the
 * tap queue must fail honestly on them instead of mistreating one as a
 * tap. Covers: TYPE step first (fails at step 0, zero dispatches);
 * SUBMIT step after a completed TAP step (tap dispatches, then fails at
 * step 1).
 *
 * Run via test-harness/run-search-tests.sh (queue-guard runner — the T3
 * compile recipe plus this file).
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

fun main() {
    initGateForTests()

    // ---- TYPE step first: sequence starts, then fails at step 0 ----
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "Search")) }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        val plan = demoPlan(
            PlanStep(
                id = "s1",
                kind = PlanStepKind.TYPE,
                targetQuery = "Search",
                instruction = "Type cats",
                text = "cats",
            ),
            PlanStep(id = "s2", targetQuery = "Search", instruction = "Tap Search"),
        )
        var outcome: SequenceOutcome? = null
        rig.queue.start(plan) { outcome = it }
        check("TYPE step -> Failed", outcome is SequenceOutcome.Failed)
        check(
            "TYPE step fails at step 0",
            (outcome as? SequenceOutcome.Failed)?.stepIndex == 0,
        )
        check(
            "TYPE failure names the kind",
            (outcome as? SequenceOutcome.Failed)?.reason?.contains("TYPE") == true,
        )
        check("TYPE step: zero dispatches", rig.dispatcher.calls.isEmpty())
    }

    // ---- SUBMIT step after a completed TAP step ----
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "Search")) }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        val plan = demoPlan(
            PlanStep(id = "s1", targetQuery = "Search", instruction = "Tap Search"),
            PlanStep(
                id = "s2",
                kind = PlanStepKind.SUBMIT,
                targetQuery = "Search",
                instruction = "Submit the search",
            ),
        )
        var outcome: SequenceOutcome? = null
        rig.queue.start(plan) { outcome = it }
        check("SUBMIT step -> Failed", outcome is SequenceOutcome.Failed)
        check(
            "SUBMIT step fails at step 1",
            (outcome as? SequenceOutcome.Failed)?.stepIndex == 1,
        )
        check(
            "one tap dispatched before the SUBMIT failure",
            rig.dispatcher.calls.size == 1,
        )
    }

    println("passed=$passed failed=$failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        println("$failed TESTS FAILED")
    }
}
