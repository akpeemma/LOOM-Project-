package com.loom.assistant.intelligence.skilllab

import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.intelligence.planner.PlanStatus
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskStepKind

/**
 * AOIE Phase 7 — learner → catalog bridge: a verified workflow
 * becomes a CANDIDATE (never a fact); a second verified run makes
 * it TESTED; promotion requires David's explicit approval AND
 * TESTED status — an unverified observation can never be promoted.
 *
 * Run via test-harness/run-2c-tests.sh (runner: aioeskill).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) passed++ else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passed++ else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun plan(goal: String) = TaskPlan(
    id = "plan-test",
    goal = goal,
    steps = listOf(
        PlanStep(index = 0, kind = TaskStepKind.LAUNCH_APP, description = "open"),
        PlanStep(index = 1, kind = TaskStepKind.SEARCH_IN_APP, description = "search"),
    ),
    origin = RequestOrigin.DIRECT_USER,
    status = PlanStatus.COMPLETED,
)

fun main() {
    val adapter = SkillIntegrationAdapter.withoutCatalog()
    val key = "workflow||LAUNCH_APP,SEARCH_IN_APP"

    run {
        adapter.onVerifiedWorkflow(plan("open YouTube Music and search for Davido"))
        val c = adapter.candidate(key)
        check("first verified run creates a candidate", c != null)
        checkEq("first run is UNVERIFIED", c?.status, CandidateStatus.UNVERIFIED)
        checkEq("first run observed once", c?.runsObserved, 1)
        check("evidence attached", c?.evidence?.isNotEmpty() == true)
        check("candidate names the goal", c?.goal == "open YouTube Music and search for Davido")
    }
    run {
        // Promotion is refused while UNVERIFIED, even with approval.
        checkEq(
            "UNVERIFIED cannot be promoted even with approval",
            adapter.promote(key, approvedByUser = true),
            null,
        )
    }
    run {
        adapter.onVerifiedWorkflow(plan("open YouTube Music and search for Davido"))
        val c = adapter.candidate(key)
        checkEq("second verified run → TESTED", c?.status, CandidateStatus.TESTED)
        checkEq("second run observed twice", c?.runsObserved, 2)
        checkEq("evidence accumulates", c?.evidence?.size, 2)
    }
    run {
        // TESTED + approval, but no catalog in this seam → still
        // refused (nothing installs without the real catalog).
        checkEq(
            "TESTED + approval but no catalog → refused",
            adapter.promote(key, approvedByUser = true),
            null,
        )
        checkEq(
            "TESTED without approval → refused",
            adapter.promote(key, approvedByUser = false),
            null,
        )
        checkEq(
            "unknown key → refused",
            adapter.promote("workflow||TAP_TARGET", approvedByUser = true),
            null,
        )
        checkEq(
            "candidate stays TESTED after refused promotes",
            adapter.candidate(key)?.status,
            CandidateStatus.TESTED,
        )
    }
    run {
        // A different workflow is a different candidate.
        adapter.onVerifiedWorkflow(plan("play Davido on YouTube Music"))
        checkEq("same kinds share one candidate", adapter.candidates().size, 1)
        // Same kinds, different goal → same key (kinds + app), so
        // the second goal refreshes the same candidate.
        checkEq(
            "same kinds refresh runsObserved",
            adapter.candidate(key)?.runsObserved,
            3,
        )
    }

    println("AioeSkillIntegrationTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILED: $failed")
}
