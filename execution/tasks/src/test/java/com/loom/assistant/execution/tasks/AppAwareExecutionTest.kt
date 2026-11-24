package com.loom.assistant.execution.tasks

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.planner.AppProfileStore
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.CoverResult
import com.loom.assistant.intelligence.planner.PlanAuditSink
import com.loom.assistant.intelligence.planner.PlanAuthCover
import com.loom.assistant.intelligence.planner.PlanFailureAnalyzer
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStatus
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.StepDispatcher
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskStepKind
import com.loom.assistant.intelligence.planner.TargetBounds

/**
 * App-aware execution (2026-11-07, David's directive §2–§4) —
 * executor-level proofs.
 *
 * - Unexpected screen → NO dispatch, analyzer replans (or stops honestly).
 * - TRY_ALTERNATIVE on a submit failure → the search re-runs with the
 *   remaining submit order, and the outcome is recorded in the profile store.
 * - A second submit failure replans (BUILT-IN RECOVERY, 2026-11-09) —
 *   clear-and-retry when a clear control is known, repair-first
 *   otherwise — instead of rotating forever; the re-issue gets its
 *   own bounded attempts, then the step is dropped and the run fails honestly.
 * - The Davido end-to-end still completes, and the profile store learned it.
 * - The tap lands where the pointer parked (tap BOUNDS == last pointer move).
 *
 * Self-contained rig (fakes local to this file) so the `appaware`
 * runner compiles it without the other executor test file.
 *
 * Run (from loom-android), see run-2c-tests.sh `appaware`:
 *   kotlinc -d out -jvm-target 17 [Rect fake] [RequestOrigin]
 *     [ScreenModel + SubmitVerification] [planner main + AppProfile.kt]
 *     [PlanVerify] TaskPlanExecutor.kt PointerTapCoordinator.kt
 *     AppAwareExecutionTest.kt
 *   java -cp out com.loom.assistant.execution.tasks.AppAwareExecutionTestKt
 */
private var aaPassed = 0
private var aaFailed = 0

private fun aaCheck(name: String, actual: Boolean) {
    if (actual) {
        aaPassed++
    } else {
        aaFailed++
        println("FAIL: $name")
    }
}

private fun aaCheckEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        aaPassed++
    } else {
        aaFailed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private const val AA_YTM = "com.google.android.apps.youtube.music"
private const val AA_OTHER = "com.other.app"

private fun aaEl(
    id: String,
    text: String? = null,
    role: ElementRole = ElementRole.TEXT,
    left: Int = 0,
    top: Int = 0,
    right: Int = 100,
    bottom: Int = 50,
    hint: String? = null,
    focused: Boolean = false,
) = ScreenElement(
    id = id,
    text = text,
    contentDescription = null,
    className = "android.widget.TextView",
    bounds = Rect(left, top, right, bottom),
    role = role,
    enabled = true,
    isPassword = false,
    hintText = hint,
    focused = focused,
)

private fun aaYtmHome() = ScreenModel(
    packageName = AA_YTM,
    elements = listOf(
        aaEl("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
        aaEl("home-row", "Home", ElementRole.TEXT, 10, 120, 500, 170),
    ),
)

private fun aaYtmResults() = ScreenModel(
    packageName = AA_YTM,
    elements = listOf(
        aaEl("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
        aaEl("row-fall", "Davido — Fall", ElementRole.TEXT, 10, 120, 500, 200),
        aaEl("row-if", "Davido — If", ElementRole.TEXT, 10, 220, 500, 300),
    ),
)

private fun aaYtmPlaying() = ScreenModel(
    packageName = AA_YTM,
    elements = listOf(
        aaEl("now-playing", "Now playing: Davido — Fall", ElementRole.TEXT, 10, 10, 500, 90),
        aaEl("pause", "Pause", ElementRole.BUTTON, 10, 120, 120, 170),
    ),
)

private fun aaOtherApp() = ScreenModel(
    packageName = AA_OTHER,
    elements = listOf(aaEl("other-row", "Something else", ElementRole.TEXT, 10, 10, 500, 90)),
)

private fun aaDialogScreen() = ScreenModel(
    packageName = AA_YTM,
    elements = listOf(
        aaEl("dlg", "Allow?", ElementRole.DIALOG, 10, 100, 500, 300),
        aaEl("row-fall", "Davido — Fall", ElementRole.TEXT, 10, 320, 500, 400),
    ),
)

private val aaFakeApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" -> AppResolution.Found(AA_YTM, "YT Music")
        "youtube" -> AppResolution.Found("com.google.android.youtube", "YouTube")
        else -> AppResolution.NotFound
    }
}

private class AaWorld(var screen: ScreenModel)

private class AaObserver(private val world: AaWorld) : ScreenObserver {
    override fun observeNow(): ScreenModel = world.screen
}

private class AaPointer : PointerGuide {
    val moves = mutableListOf<TargetBounds>()
    val shownSteps = mutableListOf<PlanStep>()
    var trackResult = true
    var failAllWith: String? = null
    private var lastFailure: String? = null

    override fun moveTo(bounds: TargetBounds, highlight: Boolean) {
        moves.add(bounds)
        lastFailure = failAllWith
    }

    override fun showIntended(step: PlanStep) {
        shownSteps.add(step)
        lastFailure = failAllWith
    }

    override fun trackTarget(targetId: String): Boolean {
        lastFailure = failAllWith
        return trackResult
    }

    override fun clearPlanTarget() {}
    override fun lastPointerFailure(): String? = lastFailure
    override fun glideWaitMs(bounds: TargetBounds): Long = 0L
}

private class AaStop(var stopped: Boolean = false) : PlanStopSignal {
    override fun isStopped(): Boolean = stopped
}

private class AaCover : PlanAuthCover {
    override fun requestPlanCover(plan: TaskPlan): CoverResult = CoverResult.GRANTED
    override fun checkCover(step: PlanStep): Boolean = true
}

private class AaAudit : PlanAuditSink {
    val events = mutableListOf<Triple<Int, String, String>>()
    override fun log(planId: String, stepIndex: Int, event: String, detail: String) {
        events.add(Triple(stepIndex, event, detail))
    }
}

/**
 * Recording dispatcher. SEARCH_IN_APP fails [failSearchTimes] times
 * with the submit marker, then moves the world to results. TAP_TARGET
 * records the BOUNDS param it was dispatched with and moves the world
 * to playing.
 */
private class AaDispatcher(private val world: AaWorld) : StepDispatcher {
    val calls = mutableListOf<TaskStepKind>()
    val searchSteps = mutableListOf<PlanStep>()
    val tapBounds = mutableListOf<String?>()
    var failSearchTimes = 0

    override fun dispatch(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        calls.add(step.kind)
        return when (step.kind) {
            TaskStepKind.LAUNCH_APP -> {
                world.screen = aaYtmHome()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN,
            TaskStepKind.VERIFY_OUTCOME, TaskStepKind.REPORT -> StepDispatchResult.DISPATCHED
            TaskStepKind.SEARCH_IN_APP -> {
                searchSteps.add(step)
                if (failSearchTimes > 0) {
                    failSearchTimes--
                    StepDispatchResult.FAILED("submit not executed — the search never ran")
                } else {
                    // Universal Console (2026-11-07): goal-aware like
                    // the real search sequence — a play search opens
                    // the first result itself (world → playing); a
                    // plain search stops at the results list.
                    val goal = step.params[PlanParams.SEARCH_GOAL].orEmpty()
                    world.screen = if (goal == PlanParams.SEARCH_GOAL_OPEN_FIRST) {
                        aaYtmPlaying()
                    } else {
                        aaYtmResults()
                    }
                    StepDispatchResult.DISPATCHED
                }
            }
            TaskStepKind.TAP_TARGET -> {
                tapBounds.add(step.params[PlanParams.BOUNDS])
                world.screen = aaYtmPlaying()
                StepDispatchResult.DISPATCHED
            }
            else -> StepDispatchResult.DISPATCHED
        }
    }
}

private class AaRig(
    val world: AaWorld = AaWorld(aaYtmHome()),
    val profiles: AppProfileStore = AppProfileStore(),
) {
    val observer = AaObserver(world)
    val pointer = AaPointer()
    val cover = AaCover()
    val audit = AaAudit()
    val dispatcher = AaDispatcher(world)
    val stop = AaStop()

    fun executor() = TaskPlanExecutor(
        apps = aaFakeApps,
        dispatcher = dispatcher,
        observer = observer,
        pointer = pointer,
        cover = cover,
        stop = stop,
        audit = audit,
        profiles = profiles,
    )
}

private fun aaReq(goal: String) = PlanRequest(goal = goal, origin = RequestOrigin.DIRECT_USER, transparency = true)

private fun aaSearchPlan(id: String, pkg: String? = null): TaskPlan {
    val params = mutableMapOf(PlanParams.APP to "YT Music", PlanParams.QUERY to "Davido")
    if (pkg != null) params[PlanParams.PACKAGE] = pkg
    // Device-failure fix (2026-11-13, F2): the launch-order entry
    // gate requires a launch before any app-scoped step. The
    // fake world's LAUNCH lands on the YTM home screen, so the
    // search steps below run exactly as before.
    val launchParams = mapOf(PlanParams.APP to "YT Music", PlanParams.PACKAGE to AA_YTM)
    return TaskPlan(
        id = id,
        goal = "Search for Davido in YT Music",
        steps = listOf(
            PlanStep(0, TaskStepKind.LAUNCH_APP, "Open YT Music", launchParams),
            PlanStep(1, TaskStepKind.WAIT_FOR_APP, "Wait for YT Music", launchParams),
            PlanStep(2, TaskStepKind.SEARCH_IN_APP, "Search for Davido in YT Music", params),
        ),
        origin = RequestOrigin.DIRECT_USER,
    )
}

fun main() {
    // ---- 1. launch-less app-scoped plan → rejected before any dispatch ----
    // Device-failure fix (2026-11-13, F2): the launch-order entry
    // gate rejects a plan whose app-scoped steps have no launch
    // before them — nothing dispatches, and the summary names the
    // rule. This subsumes the old shape of this test (a lone
    // TYPE_TEXT on the wrong screen, refused by the pre-step
    // package guard with a go-back recovery): a malformed plan
    // never reaches the recovery path at all. The pre-step guard
    // itself is still covered by the dialog test below and by
    // DeviceFailureTest §B (verifyStep package arms).
    run {
        val typePlan = TaskPlan(
            id = "aa-wrong-app",
            goal = "Type Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TYPE_TEXT, "Type Davido",
                    mapOf(PlanParams.TEXT to "Davido", PlanParams.PACKAGE to AA_YTM)),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val rig = AaRig(world = AaWorld(aaOtherApp()))
        val outcome = rig.executor().executePlan(typePlan, aaReq("Type Davido"))
        aaCheckEq("launch-less plan → failed, not completed", outcome.status, PlanStatus.FAILED)
        aaCheck("launch-less plan → nothing dispatched", rig.dispatcher.calls.isEmpty())
        aaCheck("launch-less plan → type never dispatched", rig.dispatcher.calls.none { it == TaskStepKind.TYPE_TEXT })
        aaCheck("launch-less plan → summary names the launch rule", outcome.summary.contains("LAUNCH_APP"))
        aaCheck("launch-less plan → summary says NOT DONE", outcome.summary.contains("Not done"))
        // Analyzer level: the same screen is UNEXPECTED_SCREEN → REPLAN.
        val analyzer = PlanFailureAnalyzer()
        val step = typePlan.steps[0]
        val analysis = analyzer.analyze("p1", step, "unexpected screen: expected $AA_YTM but $AA_OTHER is in the foreground", aaOtherApp())
        aaCheckEq("wrong app → analyzer replans", analysis.decision, PlanFailureAnalyzer.FailureDecision.REPLAN)
    }

    // ---- 1b. unexpected screen (dialog) → no dispatch ----
    // The search step names no app (current-screen shape), so the
    // entry gate passes and the pre-step dialog refusal is what
    // stops the search — the refusal this test exists to prove.
    // The go-back recovery cannot clear the dialog in this rig
    // (GO_BACK moves nothing), so the run fails honestly with the
    // search never dispatched.
    run {
        val dialogPlan = TaskPlan(
            id = "aa-dialog",
            goal = "Search for Davido in YT Music",
            steps = listOf(
                PlanStep(0, TaskStepKind.SEARCH_IN_APP, "Search for Davido",
                    mapOf(PlanParams.QUERY to "Davido")),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val rig = AaRig(world = AaWorld(aaDialogScreen()))
        val outcome = rig.executor().executePlan(dialogPlan, aaReq("Search for Davido in YT Music"))
        aaCheckEq("dialog → failed, not completed", outcome.status, PlanStatus.FAILED)
        aaCheck("dialog → search never dispatched", rig.dispatcher.calls.none { it == TaskStepKind.SEARCH_IN_APP })
    }

    // ---- 2. TRY_ALTERNATIVE on submit failure → remaining order, then success ----
    run {
        val rig = AaRig()
        rig.dispatcher.failSearchTimes = 1
        val outcome = rig.executor().executePlan(aaSearchPlan("aa-try-alt"), aaReq("Search for Davido in YT Music"))
        aaCheckEq("try-alternative → completes", outcome.status, PlanStatus.COMPLETED)
        aaCheckEq("try-alternative → search dispatched twice", rig.dispatcher.searchSteps.size, 2)
        aaCheckEq(
            "try-alternative → first attempt used the full default order",
            rig.dispatcher.searchSteps[0].params[PlanParams.SUBMIT_ORDER],
            "IME_ENTER,KEYBOARD_ACTION_KEY,SUGGESTION_TAP,SEARCH_BUTTON",
        )
        aaCheckEq(
            "try-alternative → retry used the remaining order",
            rig.dispatcher.searchSteps[1].params[PlanParams.SUBMIT_ORDER],
            "KEYBOARD_ACTION_KEY,SUGGESTION_TAP,SEARCH_BUTTON",
        )
        // The outcome was recorded in the profile store.
        val profile = rig.profiles.profileFor(AA_YTM)
        aaCheckEq("try-alternative → one observation recorded", profile.observations, 1)
        aaCheckEq(
            "try-alternative → win recorded for the retry's first mechanism",
            profile.mechanismWins[SubmitMechanism.KEYBOARD_ACTION_KEY],
            1,
        )
    }

    // ---- 3. all mechanisms exhausted → honest failure, failure recorded ----
    // BUILT-IN RECOVERY (2026-11-09): the second submit failure
    // replans instead of rotating forever — the repair-first
    // re-issue (no clear control on this screen) gets its own
    // bounded attempts, then the step is dropped and the run fails
    // honestly. Four dispatches: two before the replan, two after it.
    run {
        val rig = AaRig()
        rig.dispatcher.failSearchTimes = 99
        val outcome = rig.executor().executePlan(aaSearchPlan("aa-exhausted"), aaReq("Search for Davido in YT Music"))
        aaCheckEq("exhausted → failed", outcome.status, PlanStatus.FAILED)
        aaCheckEq("exhausted → four attempts across the replan (2 + 2)", rig.dispatcher.searchSteps.size, 4)
        aaCheckEq(
            "exhausted → re-issue restarts from the advanced order",
            rig.dispatcher.searchSteps[2].params[PlanParams.SUBMIT_ORDER],
            "KEYBOARD_ACTION_KEY,SUGGESTION_TAP,SEARCH_BUTTON",
        )
        aaCheckEq(
            "exhausted → fourth attempt used the last remaining order",
            rig.dispatcher.searchSteps[3].params[PlanParams.SUBMIT_ORDER],
            "SUGGESTION_TAP,SEARCH_BUTTON",
        )
        val profile = rig.profiles.profileFor(AA_YTM)
        aaCheckEq("exhausted → failure recorded once", profile.observations, 1)
        aaCheck("exhausted → no win recorded", profile.mechanismWins.isEmpty())
    }

    // ---- 4. Davido end-to-end still completes; profile learned it;
    // tap lands where the pointer parked ----
    run {
        val rig = AaRig()
        val outcome = rig.executor().execute(aaReq("Open YouTube Music and play a song by Davido"))
        aaCheckEq("davido completes", outcome.status, PlanStatus.COMPLETED)
        aaCheckEq("davido verified == total", outcome.verifiedSteps, outcome.totalSteps)
        val profile = rig.profiles.profileFor(AA_YTM)
        aaCheck("davido → profile has the observation", profile.observations >= 1)
        aaCheckEq(
            "davido → win recorded for the primary mechanism",
            profile.mechanismWins[SubmitMechanism.IME_ENTER],
            1,
        )
        aaCheckEq(
            "davido → pointer parks on the search box for the search",
            rig.pointer.moves,
            listOf(TargetBounds(10, 10, 500, 90)),
        )
        aaCheck(
            "davido → no separate tap runs (the search opens the result itself)",
            TaskStepKind.TAP_TARGET !in rig.dispatcher.calls,
        )
        aaCheckEq(
            "davido → no tap dispatched",
            rig.dispatcher.tapBounds,
            emptyList<String?>(),
        )
    }

    // ---- 5. analyzer: submit failures map to TRY_ALTERNATIVE ----
    run {
        val analyzer = PlanFailureAnalyzer()
        val step = PlanStep(0, TaskStepKind.SEARCH_IN_APP, "Search for Davido in YT Music",
            mapOf(PlanParams.APP to "YT Music", PlanParams.QUERY to "Davido"), critical = true)
        // Before submit: the field is focused, "Davido" typed, keyboard open.
        val typed = ScreenModel(
            AA_YTM,
            listOf(
                aaEl("search-field", "Davido", ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music", focused = true),
            ),
            keyboardVisible = true,
        )
        // Keyboard still open + same text → KEYBOARD_STILL_OPEN.
        val keyboardClass = analyzer.classifySubmitFailure(typed, typed, "Davido")
        aaCheckEq("keyboard-open submit → KEYBOARD_STILL_OPEN", keyboardClass, PlanFailureAnalyzer.ErrorClass.KEYBOARD_STILL_OPEN)
        val analysis = analyzer.analyze("p2", step, "keyboard still open after submit — no confirming observation", typed)
        aaCheckEq(
            "keyboard-open → TRY_ALTERNATIVE",
            (analysis.decision as? PlanFailureAnalyzer.FailureDecision.TRY_ALTERNATIVE)?.alternative,
            "dismiss-and-retry",
        )
        // Not executed, keyboard gone (dismissed, same text, no
        // results) → SUBMIT_NOT_EXECUTED → next mechanism.
        val analyzer2 = PlanFailureAnalyzer()
        val closed = ScreenModel(
            AA_YTM,
            listOf(
                aaEl("search-field", "Davido", ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music", focused = true),
            ),
            keyboardVisible = false,
        )
        val notExecClass = analyzer2.classifySubmitFailure(typed, closed, "Davido")
        aaCheckEq("closed but unexecuted → SUBMIT_NOT_EXECUTED", notExecClass, PlanFailureAnalyzer.ErrorClass.SUBMIT_NOT_EXECUTED)
        val analysis2 = analyzer2.analyze("p3", step, "submit not executed — no confirming observation", closed)
        aaCheckEq(
            "not-executed → TRY_ALTERNATIVE next mechanism",
            (analysis2.decision as? PlanFailureAnalyzer.FailureDecision.TRY_ALTERNATIVE)?.alternative,
            "next-submit-mechanism",
        )
    }

    println("AppAwareExecutionTest: $aaPassed passed, $aaFailed failed")
    if (aaFailed == 0) {
        println("ALL $aaPassed TESTS PASSED")
    } else {
        throw AssertionError("$aaFailed test(s) failed")
    }
}
