package com.loom.assistant.intelligence.planner

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.core.common.TaskRecordStatus
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Stage 2D (Worker A) — pure-JVM checks for TaskPlanner,
 * PlanFailureAnalyzer and buildOutcome.
 *
 * Run:
 *   kotlinc -d out -jvm-target 17 -cp kotlin-stdlib.jar \
 *     fakes/android/graphics/Rect.kt \
 *     core/common/.../RequestOrigin.kt \
 *     execution/screenawareness/.../ScreenModel.kt \
 *     execution/screenawareness/.../ScreenDataPolicy.kt \
 *     intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/ \
 *     intelligence/planner/src/test/java/com/loom/assistant/intelligence/planner/TaskPlannerTest.kt \
 *   && java -cp out:kotlin-stdlib.jar com.loom.assistant.intelligence.planner.TaskPlannerTestKt
 * (No android.* imports in planner main sources except through
 * ScreenModel's Rect, covered by the fake above.)
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

private val fakeApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" ->
            AppResolution.Found("com.google.android.apps.youtube.music", "YT Music")
        "youtube" -> AppResolution.Found("com.google.android.youtube", "YouTube")
        "chrome" -> AppResolution.Found("com.android.chrome", "Chrome")
        "facebook" -> AppResolution.Found("com.facebook.katana", "Facebook")
        "whatsapp" -> AppResolution.Found("com.whatsapp", "WhatsApp")
        "messages" -> AppResolution.Found("com.google.android.apps.messaging", "Messages")
        "music" -> AppResolution.Ambiguous(listOf("YT Music", "Music Player"))
        else -> AppResolution.NotFound
    }
}

private fun planner(llm: ((String) -> String)? = null) = TaskPlanner(fakeApps, llm)

private fun req(goal: String, contextApp: String? = null) =
    PlanRequest(goal, RequestOrigin.DIRECT_USER, transparency = true, contextApp = contextApp)

private fun planned(result: TaskPlanner.PlanResult): TaskPlan? =
    (result as? TaskPlanner.PlanResult.Planned)?.plan

private fun kinds(plan: TaskPlan): List<TaskStepKind> = plan.steps.map { it.kind }

private fun el(id: String, text: String?, left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 50) =
    ScreenElement(
        id = id, text = text, contentDescription = null,
        className = "android.widget.TextView", bounds = Rect(left, top, right, bottom),
        role = ElementRole.TEXT, enabled = true, isPassword = false,
    )

fun main() {
    val p = planner()

    // ---- Davido decomposition end-to-end (exact kinds + params) ----
    val davido = planned(p.plan(req("Open YouTube Music and play a song by Davido")))
    check("davido planned", davido != null)
    if (davido != null) {
        checkEq(
            "davido kinds",
            kinds(davido),
            listOf(
                TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP,
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SEARCH_IN_APP,
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME,
            ),
        )
        checkEq("davido launch app", davido.steps[0].params[PlanParams.APP], "YouTube Music")
        checkEq("davido launch pkg", davido.steps[0].params[PlanParams.PACKAGE], "com.google.android.apps.youtube.music")
        checkEq("davido wait app", davido.steps[1].params[PlanParams.APP], "YouTube Music")
        checkEq("davido search query", davido.steps[3].params[PlanParams.QUERY], "Davido")
        checkEq("davido search app", davido.steps[3].params[PlanParams.APP], "YouTube Music")
        // Universal Console (2026-11-07): the search step itself
        // opens the first result — there is deliberately NO
        // TAP_TARGET step after it any more (the old plan tapped
        // the query text a second time on the opened player).
        check("davido has no tap step", davido.steps.none { it.kind == TaskStepKind.TAP_TARGET })
        checkEq(
            "davido search goal opens the first result",
            davido.steps[3].params[PlanParams.SEARCH_GOAL],
            PlanParams.SEARCH_GOAL_OPEN_FIRST,
        )
        checkEq("davido verify expected", davido.steps[5].params[PlanParams.EXPECTED], "playing Davido")
        checkEq("davido indices", davido.steps.map { it.index }, listOf(0, 1, 2, 3, 4, 5))
        checkEq("davido goal kept", davido.goal, "Open YouTube Music and play a song by Davido")
        checkEq("davido origin", davido.origin, RequestOrigin.DIRECT_USER)
        check("davido launch critical", davido.steps[0].critical)
        check("davido search critical", davido.steps[3].critical)
        check("davido observe not critical", !davido.steps[2].critical)
    }

    // ---- composite generality: another app + artist, same shape ----
    val other = planned(p.plan(req("Open YouTube and play a song by Burna Boy")))
    check("composite other app planned", other != null)
    if (other != null) {
        checkEq("composite other kinds", kinds(other), kinds(davido!!))
        checkEq("composite other app", other.steps[0].params[PlanParams.APP], "YouTube")
        checkEq("composite other query", other.steps[3].params[PlanParams.QUERY], "Burna Boy")
    }

    // ---- search-in-app ----
    val search = planned(p.plan(req("search for cats in YouTube")))
    check("search planned", search != null)
    if (search != null) {
        checkEq(
            "search kinds",
            kinds(search),
            listOf(
                TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP,
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SEARCH_IN_APP,
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME,
            ),
        )
        checkEq("search query", search.steps[3].params[PlanParams.QUERY], "cats")
        checkEq("search app", search.steps[3].params[PlanParams.APP], "YouTube")
        // Universal Console (2026-11-07): a plain search stops at
        // the results list — its goal is show_results, never
        // open_first.
        checkEq(
            "search goal stops at the results list",
            search.steps[3].params[PlanParams.SEARCH_GOAL],
            PlanParams.SEARCH_GOAL_SHOW_RESULTS,
        )
    }
    val searchAppFirst = planned(p.plan(req("search YouTube for cats")))
    checkEq("search app-first query", searchAppFirst?.steps?.get(3)?.params?.get(PlanParams.QUERY), "cats")
    checkEq("search app-first app", searchAppFirst?.steps?.get(3)?.params?.get(PlanParams.APP), "YouTube")
    val searchOn = planned(p.plan(req("search Spider-Man on YouTube")))
    checkEq("search on-form query", searchOn?.steps?.get(3)?.params?.get(PlanParams.QUERY), "Spider-Man")
    val searchCtx = planned(p.plan(req("search for Davido", contextApp = "YouTube Music")))
    checkEq("search context app", searchCtx?.steps?.get(3)?.params?.get(PlanParams.APP), "YouTube Music")

    // ---- guide-to ----
    val guide = planned(p.plan(req("guide me through setting up voicemail")))
    check("guide planned", guide != null)
    if (guide != null) {
        checkEq(
            "guide kinds",
            kinds(guide),
            listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.GUIDE_TO, TaskStepKind.REPORT),
        )
        checkEq("guide target", guide.steps[1].params[PlanParams.GUIDE_TARGET], "setting up voicemail")
    }
    val guideTo = planned(p.plan(req("guide me to the settings screen")))
    checkEq("guide-to target", guideTo?.steps?.get(1)?.params?.get(PlanParams.GUIDE_TARGET), "the settings screen")

    // ---- open-app ----
    val open = planned(p.plan(req("open YouTube")))
    check("open planned", open != null)
    if (open != null) {
        checkEq(
            "open kinds",
            kinds(open),
            listOf(TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN),
        )
        checkEq("open pkg", open.steps[0].params[PlanParams.PACKAGE], "com.google.android.youtube")
    }

    // ---- dictated messages ----
    val wa = planned(p.plan(req("send WhatsApp to Mum saying I will be home by six")))
    check("whatsapp planned", wa != null)
    if (wa != null) {
        checkEq(
            "whatsapp kinds",
            kinds(wa),
            listOf(
                TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP,
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.MESSAGE_SEND,
                TaskStepKind.VERIFY_OUTCOME,
            ),
        )
        checkEq("whatsapp recipient", wa.steps[3].params[PlanParams.RECIPIENT], "Mum")
        checkEq("whatsapp message", wa.steps[3].params[PlanParams.MESSAGE], "I will be home by six")
        checkEq("whatsapp channel", wa.steps[3].params[PlanParams.CHANNEL], "whatsapp")
        checkEq("whatsapp app", wa.steps[0].params[PlanParams.APP], "WhatsApp")
    }
    val sms = planned(p.plan(req("text Dad saying call me")))
    checkEq("sms channel", sms?.steps?.get(3)?.params?.get(PlanParams.CHANNEL), "sms")
    checkEq("sms recipient", sms?.steps?.get(3)?.params?.get(PlanParams.RECIPIENT), "Dad")
    checkEq("sms message", sms?.steps?.get(3)?.params?.get(PlanParams.MESSAGE), "call me")
    // Dictated text is never scanned for financial words.
    val payText = planned(p.plan(req("text Mum saying pay me back please")))
    check("message text mentioning pay still plans", payText != null)

    // ---- call ----
    val call = planned(p.plan(req("call Mum")))
    check("call planned", call != null)
    if (call != null) {
        checkEq("call kinds", kinds(call), listOf(TaskStepKind.CALL_PLACE, TaskStepKind.VERIFY_OUTCOME))
        checkEq("call recipient", call.steps[0].params[PlanParams.RECIPIENT], "Mum")
    }

    // ---- pointer move ----
    val move = planned(p.plan(req("move the pointer left")))
    check("pointer move planned", move != null)
    if (move != null) {
        checkEq("pointer kinds", kinds(move), listOf(TaskStepKind.POINTER_MOVE, TaskStepKind.VERIFY_OUTCOME))
        checkEq("pointer direction", move.steps[0].params[PlanParams.DIRECTION], "left")
    }

    // ---- ambiguity → clarification (only when genuinely ambiguous) ----
    val clarOpen = p.plan(req("open"))
    check("bare open clarifies", clarOpen is TaskPlanner.PlanResult.ClarificationNeeded)
    checkEq("bare open missing", (clarOpen as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "app")
    val clarQuery = p.plan(req("search for"))
    checkEq("bare search missing", (clarQuery as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "query")
    val clarMsgText = p.plan(req("send WhatsApp to Mum"))
    checkEq("message no text missing", (clarMsgText as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "messageText")
    val clarCall = p.plan(req("call"))
    checkEq("bare call missing", (clarCall as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "target")
    val clarMove = p.plan(req("move the pointer"))
    checkEq("bare move missing", (clarMove as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "direction")
    val clarPlay = p.plan(req("play a song by Davido"))
    check("bare play clarifies app", clarPlay is TaskPlanner.PlanResult.ClarificationNeeded)
    checkEq("bare play missing", (clarPlay as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "app")
    val clarChannel = p.plan(req("send Mum a message saying hi"))
    checkEq("no channel missing", (clarChannel as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "channel")
    val clarAmbiguousApp = p.plan(req("open Music"))
    check("ambiguous app clarifies", clarAmbiguousApp is TaskPlanner.PlanResult.ClarificationNeeded)
    checkEq("ambiguous app missing", (clarAmbiguousApp as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "app")

    // ---- sensitive-app refusal (banking tap refused; guidance-only allowed) ----
    val gtbank = p.plan(req("open GTBank"))
    check("gtbank refused sensitive", gtbank is TaskPlanner.PlanResult.RefusedSensitive)
    if (gtbank is TaskPlanner.PlanResult.RefusedSensitive) {
        checkEq("gtbank app name", gtbank.appName, "GTBank")
        val g = gtbank.guidancePlan
        check("gtbank guidance plan present", g != null)
        if (g != null) {
            checkEq(
                "gtbank guidance kinds only",
                kinds(g),
                listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.GUIDE_TO, TaskStepKind.REPORT),
            )
            check("gtbank guidance has no action kind", g.steps.none { it.kind in PlanValidator.ACTION_KINDS })
        }
    }
    val kudaSearch = p.plan(req("search for balance in Kuda"))
    check("kuda search refused", kudaSearch is TaskPlanner.PlanResult.RefusedSensitive)
    val payGoal = p.plan(req("open GTBank and pay my bill"))
    check("financial goal refused", payGoal is TaskPlanner.PlanResult.RefusedSensitive)

    // ---- bulk messaging refused ----
    val bulk = p.plan(req("send WhatsApp to everyone saying hi"))
    check("bulk refused", bulk is TaskPlanner.PlanResult.Refused)

    // ---- unsupported goal says so instead of inventing steps ----
    val weird = p.plan(req("do something completely weird xyzzy"))
    check("unsupported goal", weird is TaskPlanner.PlanResult.Unsupported)

    // ---- LLM path: good plan used, bad plan / throw falls back ----
    val llmGood = planner(llm = {
        """{"goal": "x", "steps": [
          {"kind": "LAUNCH_APP", "description": "Open YouTube", "params": {"app": "YouTube"}, "critical": true},
          {"kind": "WAIT_FOR_APP", "description": "Wait for YouTube", "params": {"app": "YouTube"}, "critical": true}
        ]}"""
    })
    val llmPlan = planned(llmGood.plan(req("open YouTube")))
    check("llm good plan used", llmPlan != null && llmPlan.id.startsWith("plan-llm-"))
    val llmBad = planner(llm = { "not json at all" })
    check("llm bad json falls back to rules", planned(llmBad.plan(req("open YouTube"))) != null)
    val llmThrow = planner(llm = { throw RuntimeException("down") })
    check("llm throw falls back to rules", planned(llmThrow.plan(req("open YouTube"))) != null)
    val llmSensitive = planner(llm = {
        """{"goal": "x", "steps": [
          {"kind": "LAUNCH_APP", "description": "Open GTBank", "params": {"app": "GTBank"}, "critical": true}
        ]}"""
    })
    check(
        "llm sensitive plan rejected, rules refuse too",
        llmSensitive.plan(req("open GTBank")) is TaskPlanner.PlanResult.RefusedSensitive,
    )

    // ---- replan never repeats completed steps ----
    // Simple-task execution fix (2026-11-07): the replan is
    // dependency-aware now — the FIRST replan REPAIRS the failed
    // step (re-issues it after a fresh observe) instead of dropping
    // it and running its dependents blind; the SECOND replan drops
    // the failed step AND its dependents.
    if (davido != null) {
        val failedSearch = davido.steps[3]
        val first = p.replan(davido, failedSearch, "timeout", null)
        check("replan planned", first is TaskPlanner.PlanResult.Planned)
        if (first is TaskPlanner.PlanResult.Planned) {
            val rp = first.plan
            checkEq("replan count", rp.replanCount, 1)
            checkEq(
                "first replan re-issues the failed step, then the tail",
                kinds(rp),
                listOf(
                    TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SEARCH_IN_APP,
                    TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME,
                ),
            )
            check("replan has no launch repeat", rp.steps.none { it.kind == TaskStepKind.LAUNCH_APP })
            checkEq("replan indices", rp.steps.map { it.index }, listOf(0, 1, 2, 3))
            // Second replan, failed OBSERVE (no dependents): planned,
            // tail kept.
            val second = p.replan(rp, rp.steps[2], "target moved", null)
            check("second replan planned", second is TaskPlanner.PlanResult.Planned)
            if (second is TaskPlanner.PlanResult.Planned) {
                checkEq("second replan count", second.plan.replanCount, 2)
                val third = p.replan(second.plan, second.plan.steps.last(), "timeout", null)
                check("third replan exhausted", third is TaskPlanner.PlanResult.Unsupported)
            }
            // Second replan, failed SEARCH_IN_APP: the search is
            // abandoned AND its dependent VERIFY_OUTCOME is dropped
            // with it — only a look-at-the-screen step remains, and
            // looking is not doing the task, so the replan refuses
            // honestly (the Universal Console guard: a rebuild with
            // no goal-advancing step and no verify left).
            val dropSearch = p.replan(rp, rp.steps[1], "target moved", null)
            check(
                "second replan drops the failed search and its dependent verify",
                dropSearch is TaskPlanner.PlanResult.Unsupported,
            )
            if (dropSearch is TaskPlanner.PlanResult.Unsupported) {
                check("drop reason names the dependents", dropSearch.reason.contains("depended"))
            }
        }
        // Failing the LAST step: the first replan repairs it (re-issue
        // after a fresh observe); a second failure of the same step
        // abandons it, and with nothing after it the replan refuses.
        val failedLast = p.replan(davido, davido.steps.last(), "timeout", null)
        check("replan of last step repairs it", failedLast is TaskPlanner.PlanResult.Planned)
        if (failedLast is TaskPlanner.PlanResult.Planned) {
            checkEq(
                "last-step repair is observe + verify",
                kinds(failedLast.plan),
                listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME),
            )
            val failedAgain = p.replan(failedLast.plan, failedLast.plan.steps.last(), "timeout", null)
            check(
                "second failure of the last step has nothing left",
                failedAgain is TaskPlanner.PlanResult.Unsupported,
            )
        }
    }

    // ---- honest outcome ----
    if (davido != null) {
        val allVerified = buildOutcome(davido, davido.steps, null, PlanStatus.COMPLETED)
        checkEq("outcome completed", allVerified.status, PlanStatus.COMPLETED)
        checkEq("outcome verified count", allVerified.verifiedSteps, 6)
        check("outcome done text", allVerified.summary.startsWith("Done"))
        check("outcome never claims unverified", !allVerified.summary.contains("not done"))
        val failedOutcome = buildOutcome(davido, davido.steps.take(3), davido.steps[3], PlanStatus.FAILED)
        checkEq("outcome failed step", failedOutcome.failedStep, davido.steps[3])
        checkEq("outcome verified 3 of 6", failedOutcome.verifiedSteps, 3)
        check("outcome says not done", failedOutcome.summary.contains("not done"))
        check("outcome names verified count", failedOutcome.summary.contains("3 of 6"))
    }

    // ---- failure analyzer ----
    val analyzer = PlanFailureAnalyzer()
    val tapStep = PlanStep(5, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
        mapOf(PlanParams.TARGET to "Davido"), critical = true)
    val optStep = PlanStep(2, TaskStepKind.OBSERVE_SCREEN, "Look at the screen",
        critical = false)
    val screenWithTarget = ScreenModel("com.google.android.apps.youtube.music", listOf(el("e1", "Davido - Fall")))
    val screenMoved = ScreenModel(
        "com.google.android.apps.youtube.music",
        listOf(el("e1", "Davido - Fall", left = 500, top = 600, right = 600, bottom = 650)),
    )
    val screenGone = ScreenModel("com.google.android.apps.youtube.music", listOf(el("e2", "Something else")))

    val transient = analyzer.analyze("plan-a", tapStep, "timeout waiting for foreground", screenWithTarget)
    checkEq("transient retries", transient.decision, PlanFailureAnalyzer.FailureDecision.RETRY)
    checkEq("transient attempt 1", transient.attempt, 1)

    val analyzer2 = PlanFailureAnalyzer()
    val movedStep = tapStep.copy(params = mapOf(PlanParams.TARGET to "Davido", PlanParams.BOUNDS to "0,0,100,50"))
    val moved = analyzer2.analyze("plan-b", movedStep, "tap failed", screenMoved)
    checkEq("target moved replans", moved.decision, PlanFailureAnalyzer.FailureDecision.REPLAN)
    checkEq("target moved state", analyzer2.resolveTarget(movedStep, screenMoved), PlanFailureAnalyzer.TargetState.MOVED)
    checkEq("target present state", analyzer2.resolveTarget(movedStep, screenWithTarget), PlanFailureAnalyzer.TargetState.PRESENT)
    checkEq("target gone state", analyzer2.resolveTarget(movedStep, screenGone), PlanFailureAnalyzer.TargetState.GONE)

    // Common sense (2026-11-10): the analyzer resolves through the
    // same TargetAliases resolver as the plan path — an X labelled
    // "Close" reads as present, and the voice-search mic never
    // counts as the target.
    val xStep = tapStep.copy(params = mapOf(PlanParams.TARGET to "x", PlanParams.BOUNDS to "0,0,100,50"))
    val closeScreen = ScreenModel(
        "com.google.android.apps.youtube.music",
        listOf(el("x-btn", null).copy(contentDescription = "Close")),
    )
    checkEq("x alias reads Close as present", analyzer2.resolveTarget(xStep, closeScreen), PlanFailureAnalyzer.TargetState.PRESENT)
    val micScreen = ScreenModel(
        "com.google.android.apps.youtube.music",
        listOf(el("mic", null).copy(contentDescription = "Voice search")),
    )
    checkEq("mic never counts as the search target", analyzer2.resolveTarget(tapStep.copy(params = mapOf(PlanParams.TARGET to "search")), micScreen), PlanFailureAnalyzer.TargetState.GONE)

    val analyzer3 = PlanFailureAnalyzer()
    val gone = analyzer3.analyze("plan-c", tapStep, "tap failed", screenGone)
    checkEq("target gone aborts", gone.decision, PlanFailureAnalyzer.FailureDecision.ABORT)
    check("target gone asks user", gone.askUser)

    val analyzer4 = PlanFailureAnalyzer()
    var last = analyzer4.analyze("plan-d", tapStep, "timeout", screenWithTarget)
    last = analyzer4.analyze("plan-d", tapStep, "timeout", screenWithTarget)
    last = analyzer4.analyze("plan-d", tapStep, "timeout", screenWithTarget)
    checkEq("max attempts aborts", last.decision, PlanFailureAnalyzer.FailureDecision.ABORT)
    checkEq("max attempts count", analyzer4.attemptsFor("plan-d", 5), 3)

    val analyzer5 = PlanFailureAnalyzer()
    val skipped = analyzer5.analyze("plan-e", optStep, "screen unreadable", null)
    // "screen unreadable" is transient → RETRY wins over SKIP on attempt 1.
    checkEq("transient beats skip on attempt 1", skipped.decision, PlanFailureAnalyzer.FailureDecision.RETRY)
    val skipped2 = analyzer5.analyzeWithAttempt("plan-e", optStep, "weird failure", screenWithTarget, 1)
    checkEq("non-critical skips", skipped2.decision, PlanFailureAnalyzer.FailureDecision.SKIP)
    val criticalSkip = analyzer5.analyzeWithAttempt("plan-e", tapStep, "weird failure", screenWithTarget, 1)
    check("critical never skips", criticalSkip.decision != PlanFailureAnalyzer.FailureDecision.SKIP)

    val analyzer6 = PlanFailureAnalyzer()
    val unsafe = analyzer6.analyze("plan-f", tapStep, "sensitive app — automation refused", screenWithTarget)
    checkEq("unsafe aborts", unsafe.decision, PlanFailureAnalyzer.FailureDecision.ABORT)

    val analyzer7 = PlanFailureAnalyzer()
    analyzer7.noteReplan("plan-g")
    analyzer7.noteReplan("plan-g")
    checkEq("replan budget tracked", analyzer7.replansFor("plan-g"), 2)
    val spent = analyzer7.analyzeWithAttempt("plan-g", movedStep, "tap failed", screenMoved, 1)
    checkEq("replan budget spent aborts", spent.decision, PlanFailureAnalyzer.FailureDecision.ABORT)
    analyzer7.reset("plan-g")
    checkEq("reset clears replans", analyzer7.replansFor("plan-g"), 0)

    // ---- BUILT-IN RECOVERY (2026-11-09, David's direction) ----
    // Analyzer 2b: the first submit failure rotates the mechanism;
    // a SECOND failure replans so the planner can clear-and-retry.
    // A recovery step whose target is gone skips instead of
    // aborting and asking David.
    val submitStep = PlanStep(
        3, TaskStepKind.SEARCH_IN_APP, "Search YouTube Music for Davido",
        mapOf(PlanParams.PACKAGE to "com.google.android.apps.youtube.music"), critical = true,
    )
    val searchScreen = ScreenModel(
        "com.google.android.apps.youtube.music",
        listOf(el("s1", "Davido - Fall")),
    )
    val analyzer8 = PlanFailureAnalyzer()
    val sub1 = analyzer8.analyze(
        "plan-h", submitStep,
        "submit not executed — no confirming observation", searchScreen,
    )
    checkEq(
        "submit failure 1 rotates mechanism",
        sub1.decision,
        PlanFailureAnalyzer.FailureDecision.TRY_ALTERNATIVE("next-submit-mechanism"),
    )
    val sub2 = analyzer8.analyze(
        "plan-h", submitStep,
        "submit not executed — no confirming observation", searchScreen,
    )
    checkEq("submit failure 2 replans", sub2.decision, PlanFailureAnalyzer.FailureDecision.REPLAN)
    check("submit failure 2 names clear-and-retry", sub2.fixSuggestion.contains("Clear the search"))
    val analyzer9 = PlanFailureAnalyzer()
    val clearStep = PlanStep(
        0, TaskStepKind.TAP_TARGET, "Clear the search and try again",
        mapOf(
            PlanParams.TARGET to "clear",
            PlanParams.RECOVERY to PlanParams.RECOVERY_CLEAR_RETRY,
        ),
        critical = false,
    )
    val goneRecovery = analyzer9.analyze("plan-i", clearStep, "target moved", searchScreen)
    checkEq("gone recovery target skips", goneRecovery.decision, PlanFailureAnalyzer.FailureDecision.SKIP)

    // ---- replan built-in recoveries (B1 wrong page, B2 wrong search) ----
    if (davido != null) {
        val wrongScreen = ScreenModel("com.instagram.android", listOf(el("i1", "Home")))
        val failedSearch = davido.steps[3]
        // B1: wrong page on the FIRST replan -> GO_BACK recovery,
        // then the failed step re-issued, then the tail.
        val back = p.replan(davido, failedSearch, "unexpected screen — wrong app in the foreground", wrongScreen)
        check("B1 wrong page replans", back is TaskPlanner.PlanResult.Planned)
        if (back is TaskPlanner.PlanResult.Planned) {
            checkEq(
                "B1 kinds",
                kinds(back.plan),
                listOf(
                    TaskStepKind.OBSERVE_SCREEN, TaskStepKind.GO_BACK,
                    TaskStepKind.SEARCH_IN_APP, TaskStepKind.OBSERVE_SCREEN,
                    TaskStepKind.VERIFY_OUTCOME,
                ),
            )
            val goBack = back.plan.steps[1]
            checkEq("B1 go-back is a recovery step", goBack.params[PlanParams.RECOVERY], PlanParams.RECOVERY_GO_BACK)
            check("B1 go-back is critical", goBack.critical)
            checkEq("B1 replan count", back.plan.replanCount, 1)
            checkEq("B1 same plan id", back.plan.id, davido.id)
            // Second replan: no second GO_BACK — the drop logic runs.
            val second = p.replan(back.plan, goBack, "unexpected screen — still the wrong app", wrongScreen)
            check("B1 second replan planned", second is TaskPlanner.PlanResult.Planned)
            if (second is TaskPlanner.PlanResult.Planned) {
                check("B1 second replan has no go-back", second.plan.steps.none { it.kind == TaskStepKind.GO_BACK })
                checkEq(
                    "B1 second kinds",
                    kinds(second.plan),
                    listOf(
                        TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SEARCH_IN_APP,
                        TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME,
                    ),
                )
            }
        }
        // B1 skipped when the knowledge base records that a back
        // recovery already failed on this screen: the drop logic
        // runs instead, and with the search gone the replan refuses
        // honestly (its dependents are named).
        val kb = ScreenKnowledgeStore()
        kb.recordObservation(wrongScreen)
        kb.recordBackRecovery(wrongScreen, succeeded = false)
        val pk = TaskPlanner(fakeApps, knowledge = kb)
        val noBack = pk.replan(davido, failedSearch, "unexpected screen — wrong app in the foreground", wrongScreen)
        check("B1 skipped after failed back recovery", noBack is TaskPlanner.PlanResult.Unsupported)
        if (noBack is TaskPlanner.PlanResult.Unsupported) {
            check("B1 skip reason names the dependents", noBack.reason.contains("depended"))
        }
        // B2: wrong search with a clear control on screen -> clear
        // step, then the search re-issued, then the tail.
        val clearScreen = ScreenModel(
            "com.google.android.apps.youtube.music",
            listOf(el("c1", "Clear"), el("s1", "Davido - Fall")),
        )
        val clearRetry = p.replan(davido, failedSearch, "submit not executed — no confirming observation", clearScreen)
        check("B2 clear-and-retry replans", clearRetry is TaskPlanner.PlanResult.Planned)
        if (clearRetry is TaskPlanner.PlanResult.Planned) {
            checkEq(
                "B2 kinds",
                kinds(clearRetry.plan),
                listOf(
                    TaskStepKind.OBSERVE_SCREEN, TaskStepKind.TAP_TARGET,
                    TaskStepKind.SEARCH_IN_APP, TaskStepKind.OBSERVE_SCREEN,
                    TaskStepKind.VERIFY_OUTCOME,
                ),
            )
            val clear = clearRetry.plan.steps[1]
            checkEq("B2 clear step targets clear", clear.params[PlanParams.TARGET], "clear")
            checkEq("B2 clear step is a recovery step", clear.params[PlanParams.RECOVERY], PlanParams.RECOVERY_CLEAR_RETRY)
            check("B2 clear step is non-critical", !clear.critical)
        }
        // B2 with no clear control known -> plain repair-first
        // retry, no clear step.
        val repairFirst = listOf(
            TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SEARCH_IN_APP,
            TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME,
        )
        val noClear = p.replan(davido, failedSearch, "submit not executed — no confirming observation", searchScreen)
        check("B2 no clear known still replans", noClear is TaskPlanner.PlanResult.Planned)
        if (noClear is TaskPlanner.PlanResult.Planned) {
            checkEq("B2 no clear known is repair-first", kinds(noClear.plan), repairFirst)
        }
        // B2 with an AMBIGUOUS clear control (>1 match) -> no clear
        // step: an ambiguous screen never grounds a tap.
        val ambiguousScreen = ScreenModel(
            "com.google.android.apps.youtube.music",
            listOf(el("c1", "Clear"), el("c2", "Clear search"), el("s1", "Davido - Fall")),
        )
        val ambiguous = p.replan(davido, failedSearch, "submit not executed — no confirming observation", ambiguousScreen)
        check("B2 ambiguous clear still replans", ambiguous is TaskPlanner.PlanResult.Planned)
        if (ambiguous is TaskPlanner.PlanResult.Planned) {
            checkEq("B2 ambiguous clear is repair-first", kinds(ambiguous.plan), repairFirst)
        }
        // B2 from KNOWLEDGE only: the live screen shows no clear
        // match, but the base knows one for this screen (same
        // structure — only the label changed) -> clear step present.
        val kb2 = ScreenKnowledgeStore()
        val withClear = ScreenModel(
            "com.google.android.apps.youtube.music",
            listOf(
                ScreenElement(
                    id = "f1", text = null, contentDescription = null,
                    className = "android.widget.EditText",
                    bounds = Rect(0, 0, 1080, 120), role = ElementRole.SEARCH_FIELD,
                    enabled = true, isPassword = false, hintText = "Search",
                ),
                ScreenElement(
                    id = "c1", text = "Clear", contentDescription = null,
                    className = "android.widget.ImageButton",
                    bounds = Rect(900, 0, 1080, 120), role = ElementRole.BUTTON,
                    enabled = true, isPassword = false,
                ),
                ScreenElement(
                    id = "s1", text = "Search", contentDescription = null,
                    className = "android.widget.TextView",
                    bounds = Rect(0, 200, 1080, 300), role = ElementRole.BUTTON,
                    enabled = true, isPassword = false,
                ),
            ),
        )
        kb2.recordObservation(withClear)
        val withoutClear = ScreenModel(
            "com.google.android.apps.youtube.music",
            listOf(
                ScreenElement(
                    id = "f1", text = null, contentDescription = null,
                    className = "android.widget.EditText",
                    bounds = Rect(0, 0, 1080, 120), role = ElementRole.SEARCH_FIELD,
                    enabled = true, isPassword = false, hintText = "Search",
                ),
                ScreenElement(
                    id = "c1", text = "Done", contentDescription = null,
                    className = "android.widget.ImageButton",
                    bounds = Rect(900, 0, 1080, 120), role = ElementRole.BUTTON,
                    enabled = true, isPassword = false,
                ),
                ScreenElement(
                    id = "s1", text = "Search", contentDescription = null,
                    className = "android.widget.TextView",
                    bounds = Rect(0, 200, 1080, 300), role = ElementRole.BUTTON,
                    enabled = true, isPassword = false,
                ),
            ),
        )
        val pk2 = TaskPlanner(fakeApps, knowledge = kb2)
        val kbClear = pk2.replan(davido, failedSearch, "submit not executed — no confirming observation", withoutClear)
        check("B2 knowledge-only clear replans", kbClear is TaskPlanner.PlanResult.Planned)
        if (kbClear is TaskPlanner.PlanResult.Planned) {
            checkEq(
                "B2 knowledge-only clear has the clear step",
                kinds(kbClear.plan),
                listOf(
                    TaskStepKind.OBSERVE_SCREEN, TaskStepKind.TAP_TARGET,
                    TaskStepKind.SEARCH_IN_APP, TaskStepKind.OBSERVE_SCREEN,
                    TaskStepKind.VERIFY_OUTCOME,
                ),
            )
        }
        // B2 does NOT fire for a keyboard still open (the
        // dismiss-and-retry path owns that failure) or for a generic
        // failure (a timeout is a repair-first retry, not a wrong
        // search) — even when a clear control is on screen.
        val kbOpen = p.replan(davido, failedSearch, "keyboard still open after submit", clearScreen)
        check("B2 keyboard-open still replans", kbOpen is TaskPlanner.PlanResult.Planned)
        if (kbOpen is TaskPlanner.PlanResult.Planned) {
            checkEq("B2 keyboard-open is repair-first", kinds(kbOpen.plan), repairFirst)
        }
        val generic = p.replan(davido, failedSearch, "timeout", clearScreen)
        check("B2 generic failure still replans", generic is TaskPlanner.PlanResult.Planned)
        if (generic is TaskPlanner.PlanResult.Planned) {
            checkEq("B2 generic failure is repair-first", kinds(generic.plan), repairFirst)
        }
    }

    // ---- task record (2026-11-07): one record, created once,
    // carried unchanged; a filled slot is never re-asked. The Nov 6
    // failure asked "YouTube Music or YouTube?" AFTER the app was
    // already named, because the plan was re-derived from the bare
    // tail with no record. ----
    val ytmRecord = TaskRecord(
        instruction = "open YouTube Music and play music",
        app = "YouTube Music",
        appPackage = "com.google.android.apps.youtube.music",
        query = "music",
        origin = RequestOrigin.DIRECT_USER,
    )
    // (a) The full instruction builds the record: spoken label kept,
    // real package resolved ("YouTube Music" -> YT Music package).
    checkEq("record app on davido plan", davido?.record?.app, "YouTube Music")
    checkEq(
        "record package on davido plan",
        davido?.record?.appPackage, "com.google.android.apps.youtube.music",
    )
    checkEq("record query on davido plan", davido?.record?.query, "Davido")
    checkEq("record origin on davido plan", davido?.record?.origin, RequestOrigin.DIRECT_USER)
    checkEq("record status starts UNDERSTOOD", davido?.record?.status, TaskRecordStatus.UNDERSTOOD)
    checkEq(
        "record instruction is the verbatim goal",
        davido?.record?.instruction, "Open YouTube Music and play a song by Davido",
    )
    // (b) The exact Nov 6 sentence plans end to end, record included.
    val nov6 = planned(p.plan(req("open YouTube Music and play music")))
    check("nov6 sentence planned", nov6 != null)
    if (nov6 != null) {
        checkEq("nov6 launch app", nov6.steps[0].params[PlanParams.APP], "YouTube Music")
        checkEq("nov6 launch pkg", nov6.steps[0].params[PlanParams.PACKAGE], "com.google.android.apps.youtube.music")
        checkEq("nov6 search app", nov6.steps[3].params[PlanParams.APP], "YouTube Music")
        checkEq("nov6 search query", nov6.steps[3].params[PlanParams.QUERY], "music")
        checkEq("nov6 record app", nov6.record?.app, "YouTube Music")
    }
    // (c) Bare tail WITH the record: planned silently, no question —
    // the exact Nov 6 failure, replayed through the record.
    val bareWithRecord = planned(
        p.plan(
            PlanRequest(
                goal = "play music",
                origin = RequestOrigin.MODEL,
                transparency = true,
                contextApp = null,
                taskRecord = ytmRecord,
            ),
        ),
    )
    check("bare play with record planned, no question", bareWithRecord != null)
    if (bareWithRecord != null) {
        checkEq("bare play record kinds", kinds(bareWithRecord), kinds(davido!!))
        checkEq("bare play record launch app", bareWithRecord.steps[0].params[PlanParams.APP], "YouTube Music")
        checkEq("bare play record launch pkg", bareWithRecord.steps[0].params[PlanParams.PACKAGE], "com.google.android.apps.youtube.music")
        checkEq("bare play record search app", bareWithRecord.steps[3].params[PlanParams.APP], "YouTube Music")
        checkEq("bare play record search query", bareWithRecord.steps[3].params[PlanParams.QUERY], "music")
        checkEq("bare play record app kept", bareWithRecord.record?.app, "YouTube Music")
        checkEq("bare play record instruction kept", bareWithRecord.record?.instruction, "open YouTube Music and play music")
        // Provenance: adopting the record's app never changes the
        // plan's origin — the MODEL plan still goes through its card.
        checkEq("bare play record origin stays MODEL", bareWithRecord.origin, RequestOrigin.MODEL)
    }
    // (d) Bare tail with NO record: the question is legitimate —
    // asked exactly once, naming the missing slot.
    val bareNoRecord = p.plan(req("play music"))
    check("bare play without record asks", bareNoRecord is TaskPlanner.PlanResult.ClarificationNeeded)
    checkEq(
        "bare play question text",
        (bareNoRecord as? TaskPlanner.PlanResult.ClarificationNeeded)?.question,
        "Which app should I play that in — YouTube Music or YouTube?",
    )
    checkEq("bare play missing slot", (bareNoRecord as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing, "app")
    // (e) Asked once already: never asked twice — fails honestly.
    val askedRecord = ytmRecord.copy(app = null, appPackage = null, askedApp = true, awaitingApp = true)
    val bareAskedTwice = p.plan(
        PlanRequest(
            goal = "play music", origin = RequestOrigin.MODEL,
            transparency = true, taskRecord = askedRecord,
        ),
    )
    check("second ask fails honestly, never re-asks", bareAskedTwice is TaskPlanner.PlanResult.Unsupported)
    check(
        "second ask reason never repeats the question",
        (bareAskedTwice as? TaskPlanner.PlanResult.Unsupported)?.reason?.contains("Which app should I play") != true,
    )
    // (f) Bare search with the record: same rule, search shape.
    val bareSearch = planned(
        p.plan(
            PlanRequest(
                goal = "search for Davido",
                origin = RequestOrigin.DIRECT_USER,
                transparency = true,
                taskRecord = ytmRecord,
            ),
        ),
    )
    check("bare search with record planned", bareSearch != null)
    checkEq("bare search record app", bareSearch?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.APP), "YouTube Music")
    // (g) An explicit app in the new goal wins over the carried record.
    val explicitWins = planned(
        p.plan(
            PlanRequest(
                goal = "open YouTube and play a song by Burna Boy",
                origin = RequestOrigin.DIRECT_USER,
                transparency = true,
                taskRecord = ytmRecord,
            ),
        ),
    )
    checkEq("explicit app wins over record", explicitWins?.steps?.get(0)?.params?.get(PlanParams.APP), "YouTube")
    checkEq("explicit app record replaced", explicitWins?.record?.app, "YouTube")
    // (h) Replan carries the SAME record forward, never rebuilds it.
    if (davido != null) {
        val replanned = p.replan(davido, davido.steps[3], "target moved", null)
        val newPlan = (replanned as? TaskPlanner.PlanResult.Planned)?.plan
        check("replan planned", newPlan != null)
        checkEq("replan keeps record app", newPlan?.record?.app, "YouTube Music")
        checkEq("replan keeps record package", newPlan?.record?.appPackage, "com.google.android.apps.youtube.music")
        checkEq("replan keeps record instruction", newPlan?.record?.instruction, "Open YouTube Music and play a song by Davido")
        checkEq("replan count bumped", newPlan?.replanCount, 1)
    }
    // (i) LLM-proposed plan steps are used, but the record stays the
    // resolved one — the model never rewrites what was understood.
    val llmRecordPlan = planned(llmGood.plan(req("open YouTube")))
    check("llm plan carries the resolved record", llmRecordPlan?.record?.app == "YouTube")
    checkEq("llm plan record package", llmRecordPlan?.record?.appPackage, "com.google.android.youtube")
    // (j) Context app alone (no record) still fills a bare search —
    // the old channel, unchanged by the record.
    val ctxSearch = planned(p.plan(req("search for Davido", contextApp = "YouTube Music")))
    checkEq("context app fills bare search", ctxSearch?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.APP), "YouTube Music")

    // ---- Universal Console (2026-11-07): the five natural-language
    // tasks have DIFFERENT final goals, so they must plan different
    // step shapes. A search stops at results; a play opens the
    // first result and verifies playback; an open stops at the app.
    // The negative case is built in: an open-only plan contains no
    // search and no playback verify, so it can never satisfy a play
    // goal — and the search plan contains no tap and no playback
    // verify, so it can never satisfy one either.
    run {
        // 1. "Open YouTube." — launch only, no search, no verify-playback.
        val open = planned(p.plan(req("Open YouTube.")))
        check("NL1 open planned", open != null)
        checkEq(
            "NL1 open kinds",
            open?.let { kinds(it) },
            listOf(TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN),
        )
        check("NL1 open has no search", open?.steps?.none { it.kind == TaskStepKind.SEARCH_IN_APP } == true)
        check("NL1 open has no playback verify", open?.steps?.none { (it.params[PlanParams.EXPECTED] ?: "").startsWith("playing") } == true)

        // 2. "Search YouTube for Burna Boy." — results list, no open, no playback.
        val searchBurna = planned(p.plan(req("Search YouTube for Burna Boy.")))
        check("NL2 search planned", searchBurna != null)
        checkEq(
            "NL2 search goal stops at results",
            searchBurna?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.SEARCH_GOAL),
            PlanParams.SEARCH_GOAL_SHOW_RESULTS,
        )
        checkEq(
            "NL2 search query",
            searchBurna?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "Burna Boy",
        )
        check("NL2 search has no tap", searchBurna?.steps?.none { it.kind == TaskStepKind.TAP_TARGET } == true)
        check("NL2 search has no playback verify", searchBurna?.steps?.none { (it.params[PlanParams.EXPECTED] ?: "").startsWith("playing") } == true)
        checkEq(
            "NL2 search verify expects results",
            searchBurna?.steps?.firstOrNull { it.kind == TaskStepKind.VERIFY_OUTCOME }?.params?.get(PlanParams.EXPECTED),
            "results for Burna Boy",
        )

        // 3. "Find Last Last by Burna Boy on YouTube and play it." — open first result + playback.
        val findPlay = planned(p.plan(req("Find Last Last by Burna Boy on YouTube and play it.")))
        check("NL3 find-and-play planned", findPlay != null)
        checkEq(
            "NL3 search goal opens the first result",
            findPlay?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.SEARCH_GOAL),
            PlanParams.SEARCH_GOAL_OPEN_FIRST,
        )
        checkEq(
            "NL3 query",
            findPlay?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "Last Last by Burna Boy",
        )
        checkEq(
            "NL3 app",
            findPlay?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.APP),
            "YouTube",
        )
        check("NL3 has no duplicate tap after the search", findPlay?.steps?.none { it.kind == TaskStepKind.TAP_TARGET } == true)
        checkEq(
            "NL3 verify expects playback",
            findPlay?.steps?.firstOrNull { it.kind == TaskStepKind.VERIFY_OUTCOME }?.params?.get(PlanParams.EXPECTED),
            "playing Last Last by Burna Boy",
        )

        // 4. "Open YouTube Music and play Last Last by Burna Boy." — same shape, YT Music.
        val ytmPlay = planned(p.plan(req("Open YouTube Music and play Last Last by Burna Boy.")))
        check("NL4 ytm play planned", ytmPlay != null)
        checkEq(
            "NL4 kinds match the find-and-play shape",
            ytmPlay?.let { kinds(it) },
            findPlay?.let { kinds(it) },
        )
        checkEq(
            "NL4 app is YouTube Music",
            ytmPlay?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.APP),
            "YouTube Music",
        )
        checkEq(
            "NL4 package is YT Music",
            ytmPlay?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.PACKAGE),
            "com.google.android.apps.youtube.music",
        )
        checkEq(
            "NL4 verify expects playback",
            ytmPlay?.steps?.firstOrNull { it.kind == TaskStepKind.VERIFY_OUTCOME }?.params?.get(PlanParams.EXPECTED),
            "playing Last Last by Burna Boy",
        )

        // 5. "Search Chrome for the best Android phones." — Chrome results list, never a tap.
        val chromeSearch = planned(p.plan(req("Search Chrome for the best Android phones.")))
        check("NL5 chrome search planned", chromeSearch != null)
        checkEq(
            "NL5 app is Chrome",
            chromeSearch?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.APP),
            "Chrome",
        )
        checkEq(
            "NL5 package is Chrome",
            chromeSearch?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.PACKAGE),
            "com.android.chrome",
        )
        checkEq(
            "NL5 query",
            chromeSearch?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "the best Android phones",
        )
        checkEq(
            "NL5 goal stops at results",
            chromeSearch?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.SEARCH_GOAL),
            PlanParams.SEARCH_GOAL_SHOW_RESULTS,
        )
        check("NL5 chrome search has no tap", chromeSearch?.steps?.none { it.kind == TaskStepKind.TAP_TARGET } == true)

        // Negative: the five plans are pairwise different in goal —
        // no two share the same (search goal, playback verify) pair.
        val goalPairs = listOf(open, searchBurna, findPlay, ytmPlay, chromeSearch).map { plan ->
            val searchGoal = plan?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }
                ?.params?.get(PlanParams.SEARCH_GOAL) ?: "none"
            val playback = plan?.steps?.any { (it.params[PlanParams.EXPECTED] ?: "").startsWith("playing") } == true
            searchGoal to playback
        }
        checkEq("NL five goals are distinct shapes", goalPairs.toSet().size, 3)
        check("NL open-only cannot satisfy a play goal", goalPairs[0] == ("none" to false))
        check("NL search cannot satisfy a play goal", goalPairs[1] == (PlanParams.SEARCH_GOAL_SHOW_RESULTS to false))
        check("NL play satisfies a play goal", goalPairs[2] == (PlanParams.SEARCH_GOAL_OPEN_FIRST to true))
    }

    // ---- Universal Console operations as utterances ----
    run {
        val longPress = planned(p.plan(req("Long press the Davido row")))
        checkEq(
            "console long-press kinds",
            longPress?.let { kinds(it) },
            listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.LONG_PRESS),
        )
        checkEq(
            "console long-press target",
            longPress?.steps?.last()?.params?.get(PlanParams.TARGET),
            "the Davido row",
        )
        val swipeLeft = planned(p.plan(req("Swipe left")))
        checkEq(
            "console swipe kinds",
            swipeLeft?.let { kinds(it) },
            listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SWIPE),
        )
        checkEq(
            "console swipe direction",
            swipeLeft?.steps?.last()?.params?.get(PlanParams.DIRECTION),
            "left",
        )
        val swipeBare = p.plan(req("Swipe"))
        check("console bare swipe asks the direction", swipeBare is TaskPlanner.PlanResult.ClarificationNeeded)
        checkEq(
            "console bare swipe missing slot",
            (swipeBare as? TaskPlanner.PlanResult.ClarificationNeeded)?.missing,
            "direction",
        )
        val focus = planned(p.plan(req("Focus the search field")))
        checkEq(
            "console focus kinds",
            focus?.let { kinds(it) },
            listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.FOCUS_FIELD),
        )
        checkEq(
            "console focus target",
            focus?.steps?.last()?.params?.get(PlanParams.TARGET),
            "the search field",
        )
        val menu = planned(p.plan(req("Open the menu")))
        checkEq(
            "console menu kinds",
            menu?.let { kinds(it) },
            listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.OPEN_MENU),
        )
        check("console menu is not a launch", menu?.steps?.none { it.kind == TaskStepKind.LAUNCH_APP } == true)
        val select = planned(p.plan(req("Select the first result")))
        checkEq(
            "console select is a first-match tap",
            select?.steps?.lastOrNull()?.kind,
            TaskStepKind.TAP_TARGET,
        )
        checkEq(
            "console select match is first",
            select?.steps?.lastOrNull()?.params?.get(PlanParams.MATCH),
            "first",
        )
    }

    // ---- MASTER F1d: bare console follow-ups plan to the existing
    // step machinery (OBSERVE first, then the step) — never the LLM. ----
    val scrollDown = planned(p.plan(req("scroll down")))
    checkEq(
        "scroll down kinds",
        scrollDown?.let { kinds(it) },
        listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SCROLL),
    )
    checkEq(
        "scroll down direction",
        scrollDown?.steps?.lastOrNull()?.params?.get(PlanParams.DIRECTION),
        "down",
    )
    val scrollUp = planned(p.plan(req("scroll up")))
    checkEq(
        "scroll up kinds",
        scrollUp?.let { kinds(it) },
        listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SCROLL),
    )
    checkEq(
        "scroll up direction",
        scrollUp?.steps?.lastOrNull()?.params?.get(PlanParams.DIRECTION),
        "up",
    )
    val goBack = planned(p.plan(req("go back")))
    checkEq(
        "go back kinds",
        goBack?.let { kinds(it) },
        listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.GO_BACK),
    )
    val clickFirst = planned(p.plan(req("click on the first result")))
    checkEq(
        "click on the first result kinds",
        clickFirst?.let { kinds(it) },
        listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.TAP_TARGET),
    )
    checkEq(
        "click on the first result target",
        clickFirst?.steps?.lastOrNull()?.params?.get(PlanParams.TARGET),
        "the first result",
    )
    checkEq(
        "click on the first result match",
        clickFirst?.steps?.lastOrNull()?.params?.get(PlanParams.MATCH),
        "first",
    )
    val pressEnter = planned(p.plan(req("press enter")))
    checkEq(
        "press enter kinds",
        pressEnter?.let { kinds(it) },
        listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SUBMIT),
    )
    checkEq(
        "press enter keyboard action",
        pressEnter?.steps?.lastOrNull()?.params?.get(PlanParams.KEYBOARD_ACTION),
        "submit",
    )
    val clickEnter = planned(p.plan(req("click on enter")))
    checkEq(
        "click on enter is SUBMIT, never a tap",
        clickEnter?.steps?.lastOrNull()?.kind,
        TaskStepKind.SUBMIT,
    )

    // ---- MASTER F1c/F2a: the planner's launch extractor tolerates
    // leading words and strips trailing filler, like the voice copy. ----
    val openFiller = planned(p.plan(req("open YouTube for me")))
    check("open YouTube for me plans", openFiller != null)
    checkEq("open YouTube for me launch kind", openFiller?.steps?.firstOrNull()?.kind, TaskStepKind.LAUNCH_APP)
    checkEq("open YouTube for me launch app", openFiller?.steps?.firstOrNull()?.params?.get(PlanParams.APP), "YouTube")
    val openFillerPlease = planned(p.plan(req("open YouTube for me please")))
    checkEq("open YouTube for me please launch app", openFillerPlease?.steps?.firstOrNull()?.params?.get(PlanParams.APP), "YouTube")
    val letsGoOpen = planned(p.plan(req("let's go open YouTube")))
    check("let's go open YouTube plans", letsGoOpen != null)
    checkEq("let's go open YouTube launch kind", letsGoOpen?.steps?.firstOrNull()?.kind, TaskStepKind.LAUNCH_APP)
    checkEq("let's go open YouTube launch app", letsGoOpen?.steps?.firstOrNull()?.params?.get(PlanParams.APP), "YouTube")
    val pleaseOpen = planned(p.plan(req("please open YouTube")))
    checkEq("please open YouTube launch app", pleaseOpen?.steps?.firstOrNull()?.params?.get(PlanParams.APP), "YouTube")

    // ---- MASTER Wave 3 (F15b): play/pause console ops plan a
    // PLAY_PAUSE step whose state is VERIFIED at dispatch — a tap
    // alone is never success (David had to pause the Facebook video
    // himself, video ~715s). ----
    val pauseVideo = planned(p.plan(req("pause the video")))
    checkEq(
        "pause the video kinds",
        pauseVideo?.let { kinds(it) },
        listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.PLAY_PAUSE),
    )
    checkEq(
        "pause the video state",
        pauseVideo?.steps?.lastOrNull()?.params?.get(PlanParams.PLAY_STATE),
        "pause",
    )
    val playVideo = planned(p.plan(req("play the video")))
    checkEq(
        "play the video state",
        playVideo?.steps?.lastOrNull()?.params?.get(PlanParams.PLAY_STATE),
        "play",
    )
    val pauseThis = planned(p.plan(req("pause this")))
    checkEq(
        "pause this state",
        pauseThis?.steps?.lastOrNull()?.params?.get(PlanParams.PLAY_STATE),
        "pause",
    )

    // ---- MASTER Wave 3 (F9): MATCH=first is user-directed only —
    // a bare tap carries NO match hint (several matches refuse
    // honestly at dispatch); an explicit "first" keeps it. ----
    val tapDavido = planned(p.plan(req("tap Davido")))
    checkEq(
        "tap Davido is a tap",
        tapDavido?.steps?.lastOrNull()?.kind,
        TaskStepKind.TAP_TARGET,
    )
    check(
        "bare tap carries no match hint",
        tapDavido?.steps?.lastOrNull()?.params?.containsKey(PlanParams.MATCH) != true,
    )
    val tapFirstDavido = planned(p.plan(req("tap the first Davido")))
    checkEq(
        "tap the first Davido keeps first",
        tapFirstDavido?.steps?.lastOrNull()?.params?.get(PlanParams.MATCH),
        "first",
    )

    // ---- Device-test failures (2026-11-11, D-01/D-07): messy
    // utterances plan to action + target, never the whole sentence ----
    val storySentence =
        "open my story now you're on Facebook that's what I've told you to do so tap it and open it"
    val story = planned(p.plan(req(storySentence)))
    check("story planned", story != null)
    if (story != null) {
        checkEq("story goal sanitized", story.goal, "open my story")
        checkEq(
            "story kinds",
            kinds(story),
            listOf(
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.LAUNCH_APP,
                TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN,
                TaskStepKind.TAP_TARGET,
            ),
        )
        val tap = story.steps.last()
        checkEq("story tap target", tap.params[PlanParams.TARGET], "my story")
        checkEq("story tap package", tap.params[PlanParams.PACKAGE], "com.facebook.katana")
        checkEq(
            "story launch package",
            story.steps[1].params[PlanParams.PACKAGE],
            "com.facebook.katana",
        )
        check(
            "story no step carries the sentence",
            story.steps.none {
                it.description.contains("that's what") || it.description.contains("I've told")
            },
        )
        checkEq("story record app is Facebook", story.record?.app, "Facebook")
        checkEq(
            "story record package is Facebook",
            story.record?.appPackage,
            "com.facebook.katana",
        )
        checkEq("story record keeps the verbatim instruction", story.record?.instruction, storySentence)
        // The wrong-app replan: the tap failed "target gone" on
        // LOOM's own chat screen — the first replan brings Facebook
        // forward, then re-issues the tap.
        val loomScreen = ScreenModel(
            packageName = "com.loom.assistant",
            elements = listOf(el("bubble", "tap my story")),
        )
        val storyReplan = p.replan(story, tap, "target gone: tap ran on the wrong screen", loomScreen)
        check("story replan planned", storyReplan is TaskPlanner.PlanResult.Planned)
        if (storyReplan is TaskPlanner.PlanResult.Planned) {
            checkEq(
                "story replan launches Facebook first",
                kinds(storyReplan.plan).take(3),
                listOf(
                    TaskStepKind.OBSERVE_SCREEN, TaskStepKind.LAUNCH_APP,
                    TaskStepKind.WAIT_FOR_APP,
                ),
            )
            check(
                "story replan re-issues the tap",
                storyReplan.plan.steps.any {
                    it.kind == TaskStepKind.TAP_TARGET &&
                        it.params[PlanParams.TARGET] == "my story"
                },
            )
        }
    }

    // ---- Optimization (2026-11-11): the "open my " fast path plans
    // without a model round-trip ----
    var llmCalls = 0
    val countingLlm = planner(llm = {
        llmCalls++
        throw RuntimeException("the model must not be called for an in-app tap")
    })
    val fastStory = planned(countingLlm.plan(req(storySentence)))
    check("fast path plans the story without the model", fastStory != null)
    checkEq("fast path makes zero model calls", llmCalls, 0)
    // "open my Youtube" is a launch, never a tap — the bare-target
    // resolve ("Youtube") wins over the possessive ("my Youtube").
    val yt = planned(p.plan(req("open my Youtube")))
    check("open my Youtube planned", yt != null)
    if (yt != null) {
        check("open my Youtube has no tap", yt.steps.none { it.kind == TaskStepKind.TAP_TARGET })
        check(
            "open my Youtube launches YouTube",
            yt.steps.any {
                it.kind == TaskStepKind.LAUNCH_APP &&
                    it.params[PlanParams.PACKAGE] == "com.google.android.youtube"
            },
        )
    }
    // "now on tap on my story" (verbatim from the screenshots) plans
    // a tap on "my story", not a step named after the sentence.
    val tapStory = planned(p.plan(req("now on tap on my story")))
    check("tap-my-story planned", tapStory != null)
    if (tapStory != null) {
        check(
            "tap-my-story ends in a tap on my story",
            tapStory.steps.last().kind == TaskStepKind.TAP_TARGET &&
                tapStory.steps.last().params[PlanParams.TARGET] == "my story",
        )
    }

    // ---- D-07: filler is stripped from the search task text ----
    val searchPlan = planned(
        p.plan(req("search for Spider-Man no way home on YouTube now and stop being stupid")),
    )
    check("messy search planned", searchPlan != null)
    if (searchPlan != null) {
        check("messy search goal has no filler", !searchPlan.goal.contains("stupid"))
        val searchStep = searchPlan.steps.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }
        check("messy search has a search step", searchStep != null)
        checkEq(
            "messy search query is clean",
            searchStep?.params?.get(PlanParams.QUERY)?.toString()?.lowercase(),
            "spider-man no way home",
        )
    }

    // ---- D-07: an LLM plan carrying the whole sentence is sanitized ----
    val llmMessy = planner(llm = {
        """{"goal": "x", "steps": [
          {"kind": "TAP_TARGET", "description": "Tap my story now you're on Facebook that's what I've told you to do so tap it and open it", "params": {"target": "my story now you're on Facebook"}, "critical": true}
        ]}"""
    })
    val llmMessyPlan = planned(llmMessy.plan(req("tap my story on Facebook")))
    check("llm messy plan used", llmMessyPlan != null)
    if (llmMessyPlan != null) {
        // maybePrependLaunch prepends the Facebook open steps, so
        // the tap is the LAST step, not the first.
        val step = llmMessyPlan.steps.firstOrNull { it.kind == TaskStepKind.TAP_TARGET }
        check("llm messy plan has a tap step", step != null)
        if (step != null) {
            check(
                "llm messy description sanitized",
                !step.description.contains("that's what") && !step.description.contains("I've told"),
            )
            checkEq("llm messy target sanitized", step.params[PlanParams.TARGET], "my story")
        }
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
