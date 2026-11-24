package com.loom.assistant.execution.tasks

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.core.common.VoiceCommands
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SearchScreenPredicates
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.CoverResult
import com.loom.assistant.intelligence.planner.PlanAuditSink
import com.loom.assistant.intelligence.planner.PlanAuthCover
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStatus
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.PlanValidator
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.StepDispatcher
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskPlanner
import com.loom.assistant.intelligence.planner.TaskStatus
import com.loom.assistant.intelligence.planner.TaskStatusSink
import com.loom.assistant.intelligence.planner.TaskStatusUpdate
import com.loom.assistant.intelligence.planner.TaskStepKind
import com.loom.assistant.intelligence.planner.TaskTextSanitizer
import com.loom.assistant.intelligence.planner.TargetBounds
import com.loom.assistant.intelligence.planner.VerificationResult
import com.loom.assistant.platform.services.CoverTokenConsumer
import com.loom.assistant.platform.services.CoverTokenRequest
import com.loom.assistant.platform.services.PlanCover

/**
 * Device-failure fix round (2026-11-13, F2/F3/F4) — the harness
 * cases that FAIL a build with the Nov 13 defects. The old harness
 * fed canned screens with the correct package and fake dispatchers
 * returning DISPATCHED, so package-less predicates and dispatch-less
 * short-circuits passed by construction. These cases feed the
 * WRONG screen (LOOM's own chat, a wrong package, a blank package)
 * and demand FAILURE, honest dispatch, or an honest NOT DONE.
 *
 *  A. matchesQuery / resultsPresent / searchResultsShown /
 *     expectedOnScreen: package gates + meaningful-term matching.
 *  B. verifyStep / verifyStepTyped: wrong-screen + blank-package
 *     arms, typed/Boolean agreement.
 *  C. Launch-order rule: validator, executor entry gate, LLM
 *     repair-or-reject, rule-based regression.
 *  D. F14a short-circuit guard: no verify without dispatch unless
 *     package + results both hold on a fresh observation.
 *  E. Outcome gate: no verified action in the target app → NOT DONE.
 *  F. Pointer-display gating: invisible operation steps unverified.
 *  G. Stop-rule widening: any no-change failure shape counts.
 *  H. Per-step chat state: every step posts its outcome.
 *  I. Meta-speech cleaning: sanitizer, VoiceCommands, planner.
 *  J. Plan-cover scope: the cover request carries the plan package.
 *
 * F1 (immediate shutdown) is covered by ShutdownImmediateTest
 * (classifier + lifecycle) and VoiceSessionManagerTest (voice
 * forwarding); F4 policy/gate cases live in PseudoControlTest.
 * What this file CANNOT prove (device tests only): STT hears, the
 * pointer draws, a tap lands, the foreground changes.
 */
private var dfPassed = 0
private var dfFailed = 0

private fun dfCheck(name: String, actual: Boolean) {
    if (actual) {
        dfPassed++
    } else {
        dfFailed++
        println("FAIL: $name")
    }
}

private fun dfCheckEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        dfPassed++
    } else {
        dfFailed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

// ---------- fixtures ----------

private const val DF_YT_PKG = "com.google.android.youtube"
private const val DF_YTM_PKG = "com.google.android.apps.youtube.music"
private const val DF_OTHER_PKG = "com.other.app"
private const val DF_LOOM_PKG = "com.loom.assistant"

private fun dfEl(
    id: String,
    text: String?,
    role: ElementRole = ElementRole.TEXT,
    top: Int = 0,
): ScreenElement = ScreenElement(
    id = id,
    text = text,
    contentDescription = null,
    className = "android.widget.TextView",
    bounds = Rect(10, top, 500, top + 50),
    role = role,
    enabled = true,
    isPassword = false,
)

/** LOOM's own chat screen, echoing the task words — the Nov 13 false-positive screen. */
private fun dfLoomChatScreen() = ScreenModel(
    packageName = DF_LOOM_PKG,
    elements = listOf(
        dfEl("bubble-1", "search for Spider-Man No Way Home", top = 10),
        dfEl("bubble-2", "Working on it — search for Spider-Man No Way Home in YouTube.", top = 80),
        dfEl("bubble-3", "Spider-Man No Way Home", top = 150),
        dfEl("nav-home", "Home", ElementRole.NAVIGATION, top = 2200),
        dfEl("nav-notif", "Notifications", ElementRole.NAVIGATION, top = 2200),
    ),
    screenBounds = Rect(0, 0, 1080, 2400),
)

/** A bare bottom-nav screen: navigation chrome only, no content. */
private fun dfNavOnlyScreen(pkg: String = DF_YT_PKG) = ScreenModel(
    packageName = pkg,
    elements = listOf(
        dfEl("nav-home", "Home", ElementRole.NAVIGATION, top = 2200),
        dfEl("nav-watch", "Watch", ElementRole.NAVIGATION, top = 2200),
        dfEl("nav-profile", "Profile", ElementRole.NAVIGATION, top = 2200),
    ),
    screenBounds = Rect(0, 0, 1080, 2400),
)

private fun dfYtHome() = ScreenModel(
    packageName = DF_YT_PKG,
    elements = listOf(
        dfEl("search-field", null, ElementRole.SEARCH_FIELD, top = 10),
        dfEl("home-row", "Home", ElementRole.TEXT, top = 120),
    ),
)

private fun dfYtResults(query: String = "Davido") = ScreenModel(
    packageName = DF_YT_PKG,
    elements = listOf(
        dfEl("search-field", null, ElementRole.SEARCH_FIELD, top = 10),
        dfEl("row-1", "$query — Fall", ElementRole.TEXT, top = 120),
        dfEl("row-2", "$query — If", ElementRole.TEXT, top = 220),
    ),
)

private fun dfYtResultsMore(query: String = "Davido") = ScreenModel(
    packageName = DF_YT_PKG,
    elements = listOf(
        dfEl("search-field", null, ElementRole.SEARCH_FIELD, top = 10),
        dfEl("row-1", "$query — Fall", ElementRole.TEXT, top = 120),
        dfEl("row-2", "$query — If", ElementRole.TEXT, top = 220),
        dfEl("row-3", "$query — Assurance", ElementRole.TEXT, top = 320),
        dfEl("row-4", "$query — Fem", ElementRole.TEXT, top = 420),
    ),
)

private fun dfOtherHome() = ScreenModel(
    packageName = DF_OTHER_PKG,
    elements = listOf(
        dfEl("search-field", null, ElementRole.SEARCH_FIELD, top = 10),
        dfEl("home-row", "Home", ElementRole.TEXT, top = 120),
    ),
)

private fun dfOtherResults(query: String = "Davido") = ScreenModel(
    packageName = DF_OTHER_PKG,
    elements = listOf(
        dfEl("search-field", null, ElementRole.SEARCH_FIELD, top = 10),
        dfEl("row-1", "$query — Fall", ElementRole.TEXT, top = 120),
        dfEl("row-2", "$query — If", ElementRole.TEXT, top = 220),
    ),
)

private fun dfYtPlaying() = ScreenModel(
    packageName = DF_YT_PKG,
    elements = listOf(
        dfEl("now-playing", "Now playing: Davido — Fall", ElementRole.TEXT, top = 10),
        dfEl("pause", "Pause", ElementRole.BUTTON, top = 120),
    ),
)

private val dfApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube" -> AppResolution.Found(DF_YT_PKG, "YouTube")
        "youtube music", "yt music" -> AppResolution.Found(DF_YTM_PKG, "YT Music")
        "other" -> AppResolution.Found(DF_OTHER_PKG, "Other")
        else -> AppResolution.NotFound
    }
}

private fun dfReq(goal: String, origin: RequestOrigin = RequestOrigin.DIRECT_USER) =
    PlanRequest(goal = goal, origin = origin, transparency = true)

// ---------- fakes ----------

private class DfStop(var stopped: Boolean = false) : PlanStopSignal {
    override fun isStopped(): Boolean = stopped
}

private class DfAudit : PlanAuditSink {
    val events = mutableListOf<Triple<Int, String, String>>()
    override fun log(planId: String, stepIndex: Int, event: String, detail: String) {
        events.add(Triple(stepIndex, event, detail))
    }
}

private class DfCover(private val stop: DfStop) : PlanAuthCover {
    var requests = 0
    override fun requestPlanCover(plan: TaskPlan): CoverResult {
        requests++
        return CoverResult.GRANTED
    }

    override fun checkCover(step: PlanStep): Boolean = !stop.stopped
}

private class DfPointer : PointerGuide {
    var failAllWith: String? = null
    private var lastFailure: String? = null
    val moves = mutableListOf<TargetBounds>()

    override fun moveTo(bounds: TargetBounds, highlight: Boolean) {
        moves.add(bounds)
        lastFailure = failAllWith
    }

    override fun showIntended(step: PlanStep) {
        lastFailure = failAllWith
    }

    override fun trackTarget(targetId: String): Boolean {
        lastFailure = failAllWith
        return true
    }

    override fun clearPlanTarget() {}
    override fun lastPointerFailure(): String? = lastFailure
    override fun glideWaitMs(bounds: TargetBounds): Long = 0L
}

/** A world-moving dispatcher: LAUNCH → home, SEARCH → results, TAP → playing. */
private class DfDispatcher(
    private val world: DfWorld,
    var failTapWith: String? = null,
    var tapChangesWorld: Boolean = true,
) : StepDispatcher {
    val calls = mutableListOf<TaskStepKind>()

    override fun dispatch(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        calls.add(step.kind)
        return when (step.kind) {
            TaskStepKind.LAUNCH_APP -> {
                world.screen = if (step.params[PlanParams.PACKAGE] == DF_OTHER_PKG) dfOtherHome() else dfYtHome()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN,
            TaskStepKind.VERIFY_OUTCOME, TaskStepKind.REPORT -> StepDispatchResult.DISPATCHED
            TaskStepKind.SEARCH_IN_APP -> {
                val query = step.params[PlanParams.QUERY].orEmpty()
                world.screen = if (step.params[PlanParams.PACKAGE] == DF_OTHER_PKG) {
                    dfOtherResults(query)
                } else {
                    dfYtResults(query)
                }
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.TAP_TARGET -> {
                val fail = failTapWith
                if (fail != null) return StepDispatchResult.FAILED(fail)
                if (tapChangesWorld) world.screen = dfYtPlaying()
                StepDispatchResult.DISPATCHED
            }
            else -> StepDispatchResult.DISPATCHED
        }
    }
}

private class DfWorld(var screen: ScreenModel)

private class DfObserver(private val world: DfWorld) : ScreenObserver {
    override fun observeNow(): ScreenModel = world.screen
}

/** An observer that replays a scripted sequence, then repeats the last screen. */
private class DfSeqObserver(private val seq: List<ScreenModel>) : ScreenObserver {
    var calls = 0
    override fun observeNow(): ScreenModel {
        val screen = seq[minOf(calls, seq.lastIndex)]
        calls++
        return screen
    }
}

private class DfSink : TaskStatusSink {
    val updates = mutableListOf<TaskStatusUpdate>()
    override fun post(update: TaskStatusUpdate) {
        updates.add(update)
    }
}

private class DfRig(
    world: DfWorld = DfWorld(dfYtHome()),
    val stop: DfStop = DfStop(),
    val pointer: DfPointer = DfPointer(),
    val dispatcher: DfDispatcher = DfDispatcher(world),
    val sink: DfSink = DfSink(),
    val audit: DfAudit = DfAudit(),
    val observer: ScreenObserver = DfObserver(world),
    private val planner: TaskPlanner? = null,
) {
    val cover = DfCover(stop)

    fun executor() = TaskPlanExecutor(
        apps = dfApps,
        planner = planner ?: TaskPlanner(dfApps),
        dispatcher = dispatcher,
        observer = observer,
        pointer = pointer,
        cover = cover,
        stop = stop,
        audit = audit,
        statusSink = sink,
    )
}

// ---------- A. predicates: package gates + meaningful terms ----------

private fun dfSectionA() {
    val query = "Spider-Man No Way Home"
    val navEl = dfEl("nav-home", "Home", ElementRole.NAVIGATION, top = 2200)
    dfCheck("A: navigation chrome never matches a query", !SearchScreenPredicates.matchesQuery(navEl, query))
    dfCheck(
        "A: bare 'Home' label does not match the full query",
        !SearchScreenPredicates.matchesQuery(dfEl("h", "Home"), query),
    )
    dfCheck(
        "A: a row naming the film matches",
        SearchScreenPredicates.matchesQuery(dfEl("r", "Spider-Man: No Way Home trailer"), query),
    )
    dfCheck("A: blank query never matches", !SearchScreenPredicates.matchesQuery(dfEl("r", "Davido"), ""))
    dfCheck(
        "A: meaningful term matches",
        SearchScreenPredicates.matchesQuery(dfEl("r", "Davido — Fall"), "Davido"),
    )
    dfCheck(
        "A: results present in the named package",
        SearchScreenPredicates.resultsPresent(dfYtResults(), "Davido", DF_YT_PKG),
    )
    dfCheck(
        "A: results in a wrong package are not results",
        !SearchScreenPredicates.resultsPresent(dfYtResults(), "Davido", DF_OTHER_PKG),
    )
    dfCheck(
        "A: LOOM chat echoing the query is not results in YouTube",
        !SearchScreenPredicates.resultsPresent(dfLoomChatScreen(), query, DF_YT_PKG),
    )
    dfCheck(
        "A: nav-only screen never shows results",
        !SearchScreenPredicates.resultsPresent(dfNavOnlyScreen(), "home", DF_YT_PKG),
    )
    dfCheck(
        "A: searchResultsShown in the named package",
        searchResultsShown(dfYtResults(), "Davido", dfYtHome(), DF_YT_PKG),
    )
    dfCheck(
        "A: searchResultsShown fails on LOOM's chat screen",
        !searchResultsShown(dfLoomChatScreen(), query, null, DF_YT_PKG),
    )
    val blankPkg = ScreenModel(packageName = "", elements = dfYtResults().elements)
    dfCheck(
        "A: searchResultsShown fails on a blank/unknown package",
        !searchResultsShown(blankPkg, "Davido", null, DF_YT_PKG),
    )
    dfCheck(
        "A: expectedOnScreen in the named package",
        expectedOnScreen(dfYtPlaying(), "playing Davido", DF_YT_PKG),
    )
    dfCheck(
        "A: expectedOnScreen fails in the wrong package",
        !expectedOnScreen(dfYtPlaying(), "playing Davido", DF_OTHER_PKG),
    )
    dfCheck(
        "A: empty-words EXPECTED never passes on LOOM's own screen",
        !expectedOnScreen(dfLoomChatScreen(), ""),
    )
    dfCheck(
        "A: empty-words EXPECTED passes on a readable foreign screen",
        expectedOnScreen(dfYtHome(), ""),
    )
}

// ---------- B. verifyStep / verifyStepTyped ----------

private fun dfSectionB() {
    val exec = DfRig().executor()
    val launchBlank = PlanStep(0, TaskStepKind.LAUNCH_APP, "Open YouTube", mapOf(PlanParams.APP to "YouTube"))
    dfCheck("B: LAUNCH with a blank package fails", !exec.verifyStep(launchBlank, dfYtHome(), dfYtHome()))
    dfCheck(
        "B: LAUNCH blank package is a typed FAIL too",
        exec.verifyStepTyped(launchBlank, dfYtHome(), dfYtHome()).verdict ==
            VerificationResult.Verdict.FAIL,
    )
    val launchYt = PlanStep(
        0, TaskStepKind.LAUNCH_APP, "Open YouTube",
        mapOf(PlanParams.APP to "YouTube", PlanParams.PACKAGE to DF_YT_PKG),
    )
    dfCheck("B: LAUNCH in the right package verifies", exec.verifyStep(launchYt, dfYtHome(), dfYtHome()))
    dfCheck("B: LAUNCH in the wrong package fails", !exec.verifyStep(launchYt, dfYtHome(), dfLoomChatScreen()))
    val waitBlank = PlanStep(0, TaskStepKind.WAIT_FOR_APP, "Wait", mapOf(PlanParams.APP to "YouTube"))
    dfCheck("B: WAIT_FOR_APP with a blank package fails", !exec.verifyStep(waitBlank, dfYtHome(), dfYtHome()))
    val search = PlanStep(
        0, TaskStepKind.SEARCH_IN_APP, "Search",
        mapOf(
            PlanParams.QUERY to "Davido",
            PlanParams.APP to "YouTube",
            PlanParams.PACKAGE to DF_YT_PKG,
            PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_SHOW_RESULTS,
        ),
    )
    dfCheck(
        "B: SEARCH fails when the results are on LOOM's chat screen",
        !exec.verifyStep(search, dfYtHome(), dfLoomChatScreen()),
    )
    dfCheck(
        "B: SEARCH verifies on real results in the named app",
        exec.verifyStep(search, dfYtHome(), dfYtResults()),
    )
    val tapWrongPkg = PlanStep(
        0, TaskStepKind.TAP_TARGET, "Tap Davido",
        mapOf(PlanParams.TARGET to "Davido", PlanParams.PACKAGE to DF_YT_PKG),
    )
    dfCheck(
        "B: TAP fails when the screen changed into the wrong app",
        !exec.verifyStep(tapWrongPkg, dfYtHome(), dfOtherResults()),
    )
    val tapNoPkg = PlanStep(0, TaskStepKind.TAP_TARGET, "Tap Davido", mapOf(PlanParams.TARGET to "Davido"))
    dfCheck(
        "B: TAP without a package verifies on a screen change",
        exec.verifyStep(tapNoPkg, dfYtHome(), dfYtPlaying()),
    )
    val verifyWrongPkg = PlanStep(
        0, TaskStepKind.VERIFY_OUTCOME, "Check",
        mapOf(PlanParams.EXPECTED to "playing Davido", PlanParams.PACKAGE to DF_OTHER_PKG),
    )
    dfCheck(
        "B: VERIFY_OUTCOME fails in the wrong package",
        !exec.verifyStep(verifyWrongPkg, dfYtHome(), dfYtPlaying()),
    )
    val fieldOther = ScreenModel(
        packageName = DF_OTHER_PKG,
        elements = listOf(
            ScreenElement(
                id = "f", text = "hello", contentDescription = null,
                className = "android.widget.EditText", bounds = Rect(10, 10, 500, 90),
                role = ElementRole.INPUT, enabled = true, isPassword = false,
            ),
        ),
    )
    val typeWrongPkg = PlanStep(
        0, TaskStepKind.TYPE_TEXT, "Type hello",
        mapOf(PlanParams.TEXT to "hello", PlanParams.PACKAGE to DF_YT_PKG),
    )
    dfCheck(
        "B: TYPE_TEXT fails when the field is in the wrong app",
        !exec.verifyStep(typeWrongPkg, dfYtHome(), fieldOther),
    )
    val fieldYt = fieldOther.copy(packageName = DF_YT_PKG)
    dfCheck(
        "B: TYPE_TEXT verifies when the field shows the text in the named app",
        exec.verifyStep(typeWrongPkg, dfYtHome(), fieldYt),
    )
}

// ---------- C. launch-order rule ----------

private fun dfSectionC() {
    val validator = PlanValidator()
    fun orderPlan(vararg steps: PlanStep) = TaskPlan(
        id = "df-order", goal = "g", steps = steps.toList(), origin = RequestOrigin.MODEL,
    )
    val launch = PlanStep(
        0, TaskStepKind.LAUNCH_APP, "Open YouTube",
        mapOf(PlanParams.APP to "YouTube", PlanParams.PACKAGE to DF_YT_PKG),
    )
    val search = PlanStep(
        1, TaskStepKind.SEARCH_IN_APP, "Search",
        mapOf(PlanParams.QUERY to "Davido", PlanParams.APP to "YouTube", PlanParams.PACKAGE to DF_YT_PKG),
    )
    dfCheck(
        "C: app-scoped step without a launch is invalid",
        validator.validateLaunchOrder(orderPlan(search)) is PlanValidator.ValidationResult.Invalid,
    )
    dfCheck(
        "C: launch before the app-scoped step is valid",
        validator.validateLaunchOrder(orderPlan(launch, search)) is PlanValidator.ValidationResult.Valid,
    )
    val searchNoApp = PlanStep(0, TaskStepKind.SEARCH_IN_APP, "Search", mapOf(PlanParams.QUERY to "Davido"))
    dfCheck(
        "C: unscoped search is not app-scoped (current-screen plan)",
        validator.validateLaunchOrder(orderPlan(searchNoApp)) is PlanValidator.ValidationResult.Valid,
    )
    val pause = PlanStep(0, TaskStepKind.PLAY_PAUSE, "Pause", mapOf(PlanParams.PLAY_STATE to "pause"))
    dfCheck(
        "C: media control without an app needs no launch",
        validator.validateLaunchOrder(orderPlan(pause)) is PlanValidator.ValidationResult.Valid,
    )
    val tapOther = PlanStep(
        1, TaskStepKind.TAP_TARGET, "Tap",
        mapOf(PlanParams.TARGET to "X", PlanParams.PACKAGE to DF_OTHER_PKG),
    )
    dfCheck(
        "C: launch for a different app does not satisfy the rule",
        validator.validateLaunchOrder(orderPlan(launch, tapOther)) is PlanValidator.ValidationResult.Invalid,
    )

    // Executor entry gate: nothing dispatches.
    val rig = DfRig()
    val gatePlan = orderPlan(search)
    val gateOutcome = rig.executor().executePlan(gatePlan, dfReq("search"))
    dfCheck("C: entry gate fails the launch-less plan", gateOutcome.status == PlanStatus.FAILED)
    dfCheck("C: entry gate dispatches nothing", rig.dispatcher.calls.isEmpty())
    dfCheck("C: entry gate says NOT DONE", gateOutcome.summary.contains("Not done"))
    dfCheck("C: entry gate names the launch rule", gateOutcome.summary.contains("LAUNCH_APP"))

    // LLM repair: a 6-step model plan without a launch gets one prepended.
    val llmJson = """{"goal": "x", "steps": [
      {"kind": "OBSERVE_SCREEN", "description": "Look", "params": {}},
      {"kind": "SEARCH_IN_APP", "description": "Search", "params": {"query": "Spider-Man No Way Home"}},
      {"kind": "OBSERVE_SCREEN", "description": "Look", "params": {}},
      {"kind": "TAP_TARGET", "description": "Tap first result", "params": {"target": "first result"}},
      {"kind": "OBSERVE_SCREEN", "description": "Look", "params": {}},
      {"kind": "VERIFY_OUTCOME", "description": "Check", "params": {"expected": "results"}}
    ]}"""
    val llmPlanner = TaskPlanner(dfApps, llmPlanJson = { llmJson })
    val repaired = llmPlanner.plan(dfReq("search for Spider-Man No Way Home on YouTube"))
    check(repaired is TaskPlanner.PlanResult.Planned) {
        "LLM repair test setup failed: $repaired"
    }
    val repairedPlan = (repaired as TaskPlanner.PlanResult.Planned).plan
    dfCheck("C: LLM plan gets a launch prepended", repairedPlan.steps[0].kind == TaskStepKind.LAUNCH_APP)
    dfCheck("C: launch is followed by a wait", repairedPlan.steps[1].kind == TaskStepKind.WAIT_FOR_APP)
    val repairedSearch = repairedPlan.steps.first { it.kind == TaskStepKind.SEARCH_IN_APP }
    dfCheckEq(
        "C: repaired search step carries the target package",
        repairedSearch.params[PlanParams.PACKAGE], DF_YT_PKG,
    )
    dfCheck(
        "C: repaired plan satisfies the launch-order rule",
        validator.validateLaunchOrder(repairedPlan) is PlanValidator.ValidationResult.Valid,
    )

    // LLM reject: a search with no determinable app falls back to the rules, which ask.
    val noAppPlanner = TaskPlanner(dfApps, llmPlanJson = { llmJson })
    val noApp = noAppPlanner.plan(dfReq("search for Davido"))
    dfCheck(
        "C: model search with no app falls back to asking which app",
        noApp is TaskPlanner.PlanResult.ClarificationNeeded,
    )

    // Rule-based regression: the rule planner still launches first.
    val rulePlanner = TaskPlanner(dfApps)
    val rulePlan = rulePlanner.plan(dfReq("search for Davido in YouTube"))
    dfCheck(
        "C: rule-based search plan launches first",
        rulePlan is TaskPlanner.PlanResult.Planned &&
            (rulePlan as TaskPlanner.PlanResult.Planned).plan.steps[0].kind == TaskStepKind.LAUNCH_APP,
    )
}

// ---------- D. F14a short-circuit guard ----------

private fun dfSectionD() {
    val searchStep = PlanStep(
        1, TaskStepKind.SEARCH_IN_APP, "Search YouTube for Davido",
        mapOf(
            PlanParams.QUERY to "Davido",
            PlanParams.APP to "YouTube",
            PlanParams.PACKAGE to DF_YT_PKG,
            PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_SHOW_RESULTS,
        ),
    )
    val launchStep = PlanStep(
        0, TaskStepKind.LAUNCH_APP, "Open YouTube",
        mapOf(PlanParams.APP to "YouTube", PlanParams.PACKAGE to DF_YT_PKG),
    )
    fun planWith(search: PlanStep) = TaskPlan(
        id = "df-f14a", goal = "search", steps = listOf(launchStep, search),
        origin = RequestOrigin.DIRECT_USER,
    )

    // Negative: results-like rows on LOOM's chat screen must NOT short-circuit.
    run {
        val seq = listOf(
            dfYtHome(), dfYtHome(), dfYtHome(), dfYtHome(), // LAUNCH attempt
            dfYtHome(), dfLoomChatScreen(), dfYtResults(), dfYtResults(), // SEARCH attempt
        )
        val dispatcher = DfDispatcher(DfWorld(dfYtHome()))
        val rig = DfRig(dispatcher = dispatcher, observer = DfSeqObserver(seq))
        val outcome = rig.executor().executePlan(planWith(searchStep), dfReq("search"))
        dfCheckEq(
            "D: wrong-package results screen still dispatches the search",
            dispatcher.calls.count { it == TaskStepKind.SEARCH_IN_APP }, 1,
        )
        dfCheck("D: wrong-package run completes on real results", outcome.status == PlanStatus.COMPLETED)
    }
    // Positive: package + results on a fresh observation short-circuits (no re-type).
    run {
        val seq = listOf(
            dfYtHome(), dfYtHome(), dfYtHome(), dfYtHome(),
            dfYtHome(), dfYtResults(), dfYtResults(), dfYtResults(),
        )
        val dispatcher = DfDispatcher(DfWorld(dfYtHome()))
        val rig = DfRig(dispatcher = dispatcher, observer = DfSeqObserver(seq))
        val outcome = rig.executor().executePlan(planWith(searchStep), dfReq("search"))
        dfCheckEq(
            "D: proven results screen short-circuits without dispatch",
            dispatcher.calls.count { it == TaskStepKind.SEARCH_IN_APP }, 0,
        )
        dfCheck("D: short-circuited run completes", outcome.status == PlanStatus.COMPLETED)
    }
    // Blank package: no short-circuit even on a results screen.
    run {
        val blankSearch = searchStep.copy(
            params = searchStep.params - PlanParams.PACKAGE,
        )
        val seq = listOf(
            dfYtHome(), dfYtHome(), dfYtHome(), dfYtHome(),
            dfYtHome(), dfYtResults(), dfYtResultsMore(), dfYtResultsMore(),
        )
        val dispatcher = DfDispatcher(DfWorld(dfYtHome()))
        val rig = DfRig(dispatcher = dispatcher, observer = DfSeqObserver(seq))
        val outcome = rig.executor().executePlan(planWith(blankSearch), dfReq("search"))
        dfCheckEq(
            "D: blank-package search never short-circuits",
            dispatcher.calls.count { it == TaskStepKind.SEARCH_IN_APP }, 1,
        )
        dfCheck("D: blank-package run completes on dispatched results", outcome.status == PlanStatus.COMPLETED)
    }
}

// ---------- E. outcome gate ----------

private fun dfSectionE() {
    fun gatePlan(recordPkg: String) = TaskPlan(
        id = "df-gate",
        goal = "search",
        steps = listOf(
            PlanStep(
                0, TaskStepKind.LAUNCH_APP, "Open Other",
                mapOf(PlanParams.APP to "Other", PlanParams.PACKAGE to DF_OTHER_PKG),
            ),
            PlanStep(
                1, TaskStepKind.SEARCH_IN_APP, "Search Other for Davido",
                mapOf(
                    PlanParams.QUERY to "Davido",
                    PlanParams.APP to "Other",
                    PlanParams.PACKAGE to DF_OTHER_PKG,
                    PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_SHOW_RESULTS,
                ),
            ),
        ),
        origin = RequestOrigin.DIRECT_USER,
        record = TaskRecord(
            instruction = "search", app = "YouTube", appPackage = recordPkg,
            query = "Davido", origin = RequestOrigin.DIRECT_USER,
        ),
    )
    run {
        val rig = DfRig(world = DfWorld(dfOtherHome()))
        val outcome = rig.executor().executePlan(gatePlan(DF_YTM_PKG), dfReq("search"))
        dfCheck("E: verified steps in the wrong app are NOT DONE", outcome.status == PlanStatus.FAILED)
        dfCheck("E: gate summary names the gap", outcome.summary.contains("no verified action"))
        dfCheck("E: gate summary says NOT DONE", outcome.summary.contains("Not done"))
    }
    run {
        val rig = DfRig(world = DfWorld(dfOtherHome()))
        val outcome = rig.executor().executePlan(gatePlan(DF_OTHER_PKG), dfReq("search"))
        dfCheck("E: verified action in the target app completes", outcome.status == PlanStatus.COMPLETED)
        dfCheck("E: Done summary names the evidence", outcome.summary.contains("Evidence:"))
        dfCheck("E: Done summary keeps the stable prefix", outcome.summary.startsWith("Done — completed all"))
    }
}

// ---------- F. pointer-display gating ----------

private fun dfSectionF() {
    run {
        val pointer = DfPointer()
        pointer.failAllWith = "the pointer has nowhere to draw — the floating assistant isn't running"
        val rig = DfRig(world = DfWorld(dfYtResults()), pointer = pointer)
        val plan = TaskPlan(
            id = "df-pointer", goal = "tap",
            steps = listOf(
                PlanStep(
                    0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first"),
                ),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, dfReq("tap"))
        dfCheck("F: invisible tap never verifies", outcome.status == PlanStatus.FAILED)
        // The replan-injected OBSERVE step may verify (read-only, the
        // screen is readable) — the assertion that matters is that the
        // TAP step itself is the failed step, never a verified one.
        dfCheckEq("F: the invisible tap is the failed step", outcome.failedStep?.kind, TaskStepKind.TAP_TARGET)
        dfCheck(
            "F: outcome names the pointer gap",
            outcome.summary.contains("could not show the pointer"),
        )
        dfCheck("F: the tap did dispatch (unverified, not unrun)", rig.dispatcher.calls.contains(TaskStepKind.TAP_TARGET))
    }
    run {
        // SEARCH_IN_APP is exempt: results-based verification stands.
        val pointer = DfPointer()
        pointer.failAllWith = "the pointer has nowhere to draw"
        val rig = DfRig(pointer = pointer)
        val outcome = rig.executor().execute(dfReq("search for Davido in YouTube"))
        dfCheck(
            "F: search still verifies on results when the pointer is down (device-proven path preserved)",
            outcome.status == PlanStatus.COMPLETED,
        )
    }
}

// ---------- G. stop-rule widening ----------

private fun dfSectionG() {
    val dispatcher = DfDispatcher(DfWorld(dfYtResults()), failTapWith = "gesture timed out")
    val rig = DfRig(world = DfWorld(dfYtResults()), dispatcher = dispatcher)
    val plan = TaskPlan(
        id = "df-stop", goal = "tap",
        steps = listOf(
            PlanStep(
                0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first"),
            ),
        ),
        origin = RequestOrigin.DIRECT_USER,
    )
    val outcome = rig.executor().executePlan(plan, dfReq("tap"))
    dfCheckEq(
        "G: two no-change failures stop the run (generic reason counts)",
        dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 2,
    )
    dfCheck("G: stopped run reports FAILED", outcome.status == PlanStatus.FAILED)
    dfCheck(
        "G: the terminal summary names the no-change stop",
        outcome.summary.contains("no change after two tries"),
    )
    dfCheck(
        "G: the stop is reported in chat state",
        rig.sink.updates.any { it.message.contains("no change after two tries") },
    )
}

// ---------- H. per-step chat state ----------

private fun dfSectionH() {
    val rig = DfRig()
    val plan = TaskPlan(
        id = "df-steps", goal = "open YouTube and look",
        steps = listOf(
            PlanStep(
                0, TaskStepKind.LAUNCH_APP, "Open YouTube",
                mapOf(PlanParams.APP to "YouTube", PlanParams.PACKAGE to DF_YT_PKG),
            ),
            PlanStep(1, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
        ),
        origin = RequestOrigin.DIRECT_USER,
    )
    val outcome = rig.executor().executePlan(plan, dfReq("open YouTube and look"))
    dfCheck("H: two-step plan completes", outcome.status == PlanStatus.COMPLETED)
    val messages = rig.sink.updates.map { it.message }
    dfCheck("H: start post keeps the Working-on-it wording", messages.first() == "Working on it — open YouTube and look.")
    dfCheck(
        "H: step 1 posts its verified outcome to chat",
        messages.any { it.contains("Step 1 of 2") && it.contains("verified") },
    )
    dfCheck(
        "H: step 2 posts its verified outcome to chat",
        messages.any { it.contains("Step 2 of 2") && it.contains("verified") },
    )
    dfCheck(
        "H: step posts carry evidence",
        messages.any { it.contains("Step 1 of 2") && it.contains("foreground package is") },
    )
    dfCheckEq("H: terminal status is COMPLETED", rig.sink.updates.last().status, TaskStatus.COMPLETED)
}

// ---------- I. meta-speech cleaning ----------

private fun dfSectionI() {
    dfCheckEq(
        "I: sanitizer cuts 'don't make me repeat myself'",
        TaskTextSanitizer.sanitizeQuery("Spider-Man No Way Home don't make me repeat myself again"),
        "Spider-Man No Way Home",
    )
    dfCheckEq(
        "I: sanitizer cuts the STT variant 'don't alarm repeat myself'",
        TaskTextSanitizer.sanitizeQuery("Spider-Man No Way Home don't alarm repeat myself again"),
        "Spider-Man No Way Home",
    )
    dfCheckEq(
        "I: sanitizer cuts 'you're still quiet / not doing anything'",
        TaskTextSanitizer.sanitizeQuery("it you're still quiet you're not doing anything"),
        "it",
    )
    dfCheckEq(
        "I: sanitizer cuts 'you're not doing anything'",
        TaskTextSanitizer.sanitizeQuery("Davido you're not doing anything"),
        "Davido",
    )
    dfCheckEq(
        "I: sanitizer cuts the STT variant 'still quite'",
        TaskTextSanitizer.sanitizeQuery("Davido you're still quite"),
        "Davido",
    )
    dfCheckEq(
        "I: sanitizer cuts 'you never listen'",
        TaskTextSanitizer.sanitizeQuery("Davido you never listen"),
        "Davido",
    )
    dfCheckEq(
        "I: bare trailing 'again' is never cut alone",
        TaskTextSanitizer.sanitizeQuery("play it again"),
        "play it again",
    )
    dfCheckEq(
        "I: a query may start with 'now'",
        TaskTextSanitizer.sanitizeQuery("Now You See Me"),
        "Now You See Me",
    )
    dfCheckEq(
        "I: sanitizeGoal cuts meta-speech from the whole goal",
        TaskTextSanitizer.sanitizeGoal("search for Spider-Man No Way Home don't make me repeat myself again"),
        "search for Spider-Man No Way Home",
    )
    val dirtyPlan = TaskPlan(
        id = "df-san", goal = "search for Davido don't make me repeat myself",
        steps = listOf(
            PlanStep(
                0, TaskStepKind.SEARCH_IN_APP, "Search",
                mapOf(PlanParams.QUERY to "Davido you're not doing anything"),
            ),
        ),
        origin = RequestOrigin.DIRECT_USER,
    )
    val cleanPlan = TaskTextSanitizer.sanitizePlan(dirtyPlan)
    dfCheckEq(
        "I: sanitizePlan cleans QUERY params",
        cleanPlan.steps[0].params[PlanParams.QUERY], "Davido",
    )
    dfCheck("I: sanitizePlan cleans the goal", !cleanPlan.goal.contains("repeat myself"))
    dfCheckEq(
        "I: VoiceCommands extracts the clean query (lowercase path)",
        VoiceCommands.extractSearchQuery("search for Spider-Man No Way Home don't make me repeat myself again"),
        "spider-man no way home",
    )
    dfCheckEq(
        "I: VoiceCommands extracts 'it' without the remarks",
        VoiceCommands.extractSearchQuery("search for it you're still quiet you're not doing anything"),
        "it",
    )
    dfCheckEq(
        "I: VoiceCommands extracts the app before the remark",
        VoiceCommands.extractSearchApp("search for Davido in YouTube don't make me repeat myself"),
        "youtube",
    )
    dfCheckEq(
        "I: stripMetaSpeech cuts at goal formation",
        VoiceCommands.stripMetaSpeech("Davido don't make me repeat myself again"),
        "Davido",
    )
    val planner = TaskPlanner(dfApps)
    val planned = planner.plan(
        dfReq("search for Spider-Man No Way Home in YouTube don't make me repeat myself again"),
    )
    check(planned is TaskPlanner.PlanResult.Planned) { "planner test setup failed: $planned" }
    val searchStep = (planned as TaskPlanner.PlanResult.Planned).plan.steps
        .first { it.kind == TaskStepKind.SEARCH_IN_APP }
    dfCheckEq(
        "I: planner query is exactly 'Spider-Man No Way Home'",
        searchStep.params[PlanParams.QUERY], "Spider-Man No Way Home",
    )
    dfCheck(
        "I: planner goal carries no meta-speech",
        !(planned as TaskPlanner.PlanResult.Planned).plan.goal.contains("repeat myself"),
    )
}

// ---------- J. plan-cover scope ----------

private class DfFakeGate : CoverTokenRequest {
    var requests = 0
    var lastScope: String? = null
    var nextToken: String? = "tok-df-1"
    override fun request(
        goal: String,
        appLabel: String,
        stepCount: Int,
        origin: RequestOrigin,
        scopePackage: String,
    ): String? {
        requests++
        lastScope = scopePackage
        return nextToken
    }
}

private fun dfSectionJ() {
    val stop = DfStop()
    val gate = DfFakeGate()
    val cover = PlanCover(
        requestToken = gate,
        consumeToken = CoverTokenConsumer { true },
        stop = stop,
        apps = dfApps,
    )
    val modelPlan = TaskPlan(
        id = "df-cover", goal = "play Davido",
        steps = listOf(
            PlanStep(
                0, TaskStepKind.LAUNCH_APP, "Open YouTube Music",
                mapOf(PlanParams.APP to "YouTube Music", PlanParams.PACKAGE to DF_YTM_PKG),
            ),
            PlanStep(1, TaskStepKind.SEARCH_IN_APP, "Search", mapOf(PlanParams.QUERY to "Davido")),
        ),
        origin = RequestOrigin.MODEL,
    )
    val result = cover.requestPlanCover(modelPlan)
    dfCheck("J: MODEL cover request is granted by the fake gate", result == CoverResult.GRANTED)
    dfCheckEq("J: cover request carries the plan package", gate.lastScope, DF_YTM_PKG)
    val unscopedPlan = TaskPlan(
        id = "df-cover-2", goal = "open NoSuchApp",
        steps = listOf(
            PlanStep(0, TaskStepKind.LAUNCH_APP, "Open", mapOf(PlanParams.APP to "NoSuchApp")),
        ),
        origin = RequestOrigin.MODEL,
    )
    cover.requestPlanCover(unscopedPlan)
    dfCheckEq("J: unresolvable plan requests with a blank scope (cards in production)", gate.lastScope, "")
}

fun main() {
    dfSectionA()
    dfSectionB()
    dfSectionC()
    dfSectionD()
    dfSectionE()
    dfSectionF()
    dfSectionG()
    dfSectionH()
    dfSectionI()
    dfSectionJ()
    println("DeviceFailureTest: $dfPassed passed, $dfFailed failed")
    if (dfFailed == 0) println("ALL $dfPassed TESTS PASSED")
}
