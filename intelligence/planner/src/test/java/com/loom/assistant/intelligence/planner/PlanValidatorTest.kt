package com.loom.assistant.intelligence.planner

import com.loom.assistant.core.common.RequestOrigin

/**
 * Stage 2D (Worker A) — pure-JVM checks for PlanValidator
 * (the grounded-fallback gate). Same kotlinc command as
 * TaskPlannerTest, with PlanValidatorTestKt as the main class.
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

private val validator = PlanValidator()

private fun req(goal: String) =
    PlanRequest(goal, RequestOrigin.MODEL, transparency = false)

private fun goodJson() = """{"goal": "open YouTube and play Davido", "steps": [
  {"kind": "LAUNCH_APP", "description": "Open YouTube Music", "params": {"app": "YouTube Music", "package": "com.google.android.apps.youtube.music"}, "critical": true},
  {"kind": "WAIT_FOR_APP", "description": "Wait for YouTube Music", "params": {"app": "YouTube Music"}, "critical": true},
  {"kind": "OBSERVE_SCREEN", "description": "Look at the screen", "params": {}, "critical": false},
  {"kind": "SEARCH_IN_APP", "description": "Search for Davido", "params": {"app": "YouTube Music", "query": "Davido"}, "critical": true},
  {"kind": "TAP_TARGET", "description": "Tap the first result for Davido", "params": {"target": "Davido", "match": "first"}, "critical": true},
  {"kind": "VERIFY_OUTCOME", "description": "Check that Davido is playing", "params": {"expected": "playing Davido"}, "critical": false}
]}"""

fun main() {
    // ---- accepts a good LLM plan ----
    // MASTER Wave 4: the good fixture's TAP_TARGET step uses
    // MATCH="first", so the request goal must name "first" — the
    // validator now rejects a model-proposed first pick the user
    // never asked for (see the MATCH=first tests below).
    val good = validator.parsePlanJson(goodJson(), req("open YouTube Music and play the first Davido song"))
    check("good llm plan accepted", good is PlanValidator.ParseOutcome.Valid)
    if (good is PlanValidator.ParseOutcome.Valid) {
        checkEq("good plan steps", good.plan.steps.size, 6)
        checkEq("good plan kind", good.plan.steps[0].kind, TaskStepKind.LAUNCH_APP)
        checkEq("good plan goal is the request goal", good.plan.goal, "open YouTube Music and play the first Davido song")
        checkEq("good plan origin is the request origin", good.plan.origin, RequestOrigin.MODEL)
    }

    // ---- rejects a bad one: unknown kind ----
    val unknownKind = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "TELEPORT", "description": "Teleport", "params": {}, "critical": true}
        ]}""",
        req("x"),
    )
    check("unknown kind rejected", unknownKind is PlanValidator.ParseOutcome.Invalid)
    if (unknownKind is PlanValidator.ParseOutcome.Invalid) {
        check("unknown kind reason names it", unknownKind.reasons.any { it.contains("TELEPORT") })
    }

    // ---- rejects a bad one: sensitive target ----
    val sensitive = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "LAUNCH_APP", "description": "Open GTBank", "params": {"app": "GTBank"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("sensitive target rejected", sensitive is PlanValidator.ParseOutcome.Invalid)
    val sensitiveTap = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "TAP_TARGET", "description": "Tap pay", "params": {"target": "Pay", "app": "Kuda"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("sensitive tap rejected", sensitiveTap is PlanValidator.ParseOutcome.Invalid)

    // ---- guidance-only steps in a sensitive app are allowed ----
    val guidanceOk = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "OBSERVE_SCREEN", "description": "Look at the screen", "params": {"app": "GTBank"}, "critical": false},
          {"kind": "GUIDE_TO", "description": "Guide to GTBank", "params": {"guideTarget": "GTBank"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("guidance-only in sensitive app allowed", guidanceOk is PlanValidator.ParseOutcome.Valid)

    // ---- rejects a bad one: empty target ----
    val emptyTarget = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "TAP_TARGET", "description": "Tap something", "params": {}, "critical": true}
        ]}""",
        req("x"),
    )
    check("empty target rejected", emptyTarget is PlanValidator.ParseOutcome.Invalid)

    // ---- rejects malformed JSON, empty steps, bad lengths ----
    check("bad json rejected", validator.parsePlanJson("not json at all", req("x")) is PlanValidator.ParseOutcome.Invalid)
    check("non-object rejected", validator.parsePlanJson("[1, 2]", req("x")) is PlanValidator.ParseOutcome.Invalid)
    check(
        "empty steps rejected",
        validator.parsePlanJson("""{"goal": "x", "steps": []}""", req("x")) is PlanValidator.ParseOutcome.Invalid,
    )
    val longParam = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "TYPE_TEXT", "description": "Type", "params": {"text": "${"a".repeat(1001)}"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("absurd param length rejected", longParam is PlanValidator.ParseOutcome.Invalid)
    val unknownParam = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "LAUNCH_APP", "description": "Open", "params": {"app": "YouTube", "explode": "yes"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("unknown param rejected", unknownParam is PlanValidator.ParseOutcome.Invalid)
    val badDirection = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "POINTER_MOVE", "description": "Move", "params": {"direction": "sideways"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("bad direction rejected", badDirection is PlanValidator.ParseOutcome.Invalid)

    // ---- Universal Console (2026-11-07): the console kinds pass
    // the gate, a direction-less swipe does not, and a search step
    // may carry its goal param.
    val consoleOk = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "LONG_PRESS", "description": "Long-press the row", "params": {"target": "Davido"}, "critical": true},
          {"kind": "SWIPE", "description": "Swipe left", "params": {"direction": "left"}, "critical": true},
          {"kind": "FOCUS_FIELD", "description": "Focus the field", "params": {"target": "search"}, "critical": true},
          {"kind": "OPEN_MENU", "description": "Open the menu", "params": {}, "critical": true}
        ]}""",
        req("x"),
    )
    check("console kinds accepted", consoleOk is PlanValidator.ParseOutcome.Valid)
    val badSwipe = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "SWIPE", "description": "Swipe", "params": {"direction": "sideways"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("bad swipe direction rejected", badSwipe is PlanValidator.ParseOutcome.Invalid)
    val noSwipeDirection = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "SWIPE", "description": "Swipe", "params": {}, "critical": true}
        ]}""",
        req("x"),
    )
    check("missing swipe direction rejected", noSwipeDirection is PlanValidator.ParseOutcome.Invalid)
    val searchGoalOk = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "SEARCH_IN_APP", "description": "Search", "params": {"app": "YouTube", "query": "cats", "searchGoal": "show_results"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("search goal param accepted", searchGoalOk is PlanValidator.ParseOutcome.Valid)
    val consoleSensitive = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "LONG_PRESS", "description": "Long-press pay", "params": {"target": "Pay", "app": "Kuda"}, "critical": true}
        ]}""",
        req("x"),
    )
    check("console kind in sensitive app rejected", consoleSensitive is PlanValidator.ParseOutcome.Invalid)

    // ---- validate() on a built plan ----
    val built = TaskPlan(
        id = "plan-test", goal = "open YouTube",
        steps = listOf(
            PlanStep(0, TaskStepKind.LAUNCH_APP, "Open YouTube", mapOf(PlanParams.APP to "YouTube")),
        ),
        origin = RequestOrigin.DIRECT_USER,
    )
    check("built plan validates", validator.validate(built) is PlanValidator.ValidationResult.Valid)
    val blankGoal = built.copy(goal = "")
    check("blank goal rejected", validator.validate(blankGoal) is PlanValidator.ValidationResult.Invalid)

    // ---- parse never throws ----
    try {
        validator.parsePlanJson("", req("x"))
        validator.parsePlanJson("{", req("x"))
        validator.parsePlanJson("""{"steps": "nope"}""", req("x"))
        check("parse never throws", true)
    } catch (_: Exception) {
        check("parse never throws", false)
    }

    // ---- MASTER Wave 4 (validator hardening): MATCH="first" ----
    // A model-proposed first pick is never trusted unless David's
    // own goal names "first".
    val firstWithoutAsk = validator.parsePlanJson(goodJson(), req("open YouTube Music and play Davido"))
    check("MATCH=first without 'first' in the goal rejected", firstWithoutAsk is PlanValidator.ParseOutcome.Invalid)
    if (firstWithoutAsk is PlanValidator.ParseOutcome.Invalid) {
        check(
            "rejection names MATCH=first",
            firstWithoutAsk.reasons.any { it.contains("MATCH=first") },
        )
    }
    val firstWithAsk = validator.parsePlanJson(goodJson(), req("open the first Davido result"))
    check("MATCH=first with 'first' in the goal accepted", firstWithAsk is PlanValidator.ParseOutcome.Valid)
    val firstlyGoal = validator.parsePlanJson(goodJson(), req("firstly open YouTube Music and play Davido"))
    check("'firstly' does not count as asking for the first", firstlyGoal is PlanValidator.ParseOutcome.Invalid)
    val longPressFirst = validator.parsePlanJson(
        """{"goal": "x", "steps": [
          {"kind": "LONG_PRESS", "description": "Long-press the result", "params": {"target": "Davido", "match": "first"}, "critical": true}
        ]}""",
        req("long-press the Davido result"),
    )
    check("LONG_PRESS MATCH=first without 'first' in the goal rejected", longPressFirst is PlanValidator.ParseOutcome.Invalid)

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
