package com.loom.assistant.execution.tasks

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.core.common.VoiceCommand
import com.loom.assistant.core.common.VoiceCommands
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
import com.loom.assistant.intelligence.planner.PlanStatus
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PlanStopSignal
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
import com.loom.assistant.intelligence.planner.TargetBounds

/**
 * Simple-task execution fix (2026-11-07, David's Nov 7 12:38
 * directive) — acceptance + regression checks.
 *
 * The fake world is a YouTube live-tree simulator: the dispatcher
 * moves a shared mutable screen (LAUNCH → home, SEARCH → results,
 * TAP → playing) so the executor's fresh-observation verification
 * judges a live tree, not a stubbed boolean. Opening YouTube alone
 * must FAIL: the negative control (§A2) runs the same plan with the
 * search broken and asserts FAILED + no tap dispatched.
 *
 * Run (from loom-android) — see run-2c-tests.sh `simpletask`.
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

// ---------- fixtures: YouTube live-tree shapes ----------

const val YT_PKG = "com.google.android.youtube"
const val YTM_PKG = "com.google.android.apps.youtube.music"

private fun el(
    id: String,
    text: String?,
    role: ElementRole = ElementRole.TEXT,
    left: Int = 0,
    top: Int = 0,
    right: Int = 100,
    bottom: Int = 50,
    hint: String? = null,
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
)

private fun ytHome() = ScreenModel(
    packageName = YT_PKG,
    elements = listOf(
        el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YouTube"),
        el("home-row", "Home", ElementRole.TEXT, 10, 120, 500, 170),
    ),
)

private fun ytResults(query: String) = ScreenModel(
    packageName = YT_PKG,
    elements = listOf(
        el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YouTube"),
        el("row-1", "$query — Top result", ElementRole.TEXT, 10, 120, 500, 200),
        el("row-2", "$query — Second result", ElementRole.TEXT, 10, 220, 500, 300),
    ),
)

private fun ytNoResults() = ScreenModel(
    packageName = YT_PKG,
    elements = listOf(
        el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YouTube"),
        el("no-results", "No results found", ElementRole.TEXT, 10, 120, 500, 170),
    ),
)

/** Player screen WITH a "playing" label. */
private fun ytPlaying(query: String) = ScreenModel(
    packageName = YT_PKG,
    elements = listOf(
        el("now-playing", "Now playing: $query", ElementRole.TEXT, 10, 10, 500, 90),
        el("pause", "Pause", ElementRole.BUTTON, 10, 120, 120, 170),
        el("row-1", "$query — Top result", ElementRole.TEXT, 10, 220, 500, 300),
    ),
)

/**
 * Player screen with NO "playing" text — only a Pause control and
 * the query title. YouTube's player may not expose "playing" in the
 * tree; this is the screen the old verifier could never pass.
 */
private fun ytPlayingPauseOnly(query: String) = ScreenModel(
    packageName = YT_PKG,
    elements = listOf(
        el("title", query, ElementRole.TEXT, 10, 10, 500, 90),
        el("pause", "Pause", ElementRole.BUTTON, 10, 120, 120, 170),
    ),
)

private val fakeApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" -> AppResolution.Found(YTM_PKG, "YT Music")
        "youtube" -> AppResolution.Found(YT_PKG, "YouTube")
        else -> AppResolution.NotFound
    }
}

// ---------- fakes ----------

private class World(var screen: ScreenModel)

private class FakeObserver(private val world: World) : ScreenObserver {
    override fun observeNow(): ScreenModel = world.screen
}

private class FakePointer : PointerGuide {
    val moves = mutableListOf<TargetBounds>()
    val shownSteps = mutableListOf<PlanStep>()
    var cleared = 0

    override fun moveTo(bounds: TargetBounds, highlight: Boolean) {
        moves.add(bounds)
    }

    override fun showIntended(step: PlanStep) {
        shownSteps.add(step)
    }

    override fun trackTarget(targetId: String): Boolean = true
    override fun clearPlanTarget() {
        cleared++
    }

    override fun lastPointerFailure(): String? = null
    override fun glideWaitMs(bounds: TargetBounds): Long = 0L
}

private class FakeStop(var stopped: Boolean = false) : PlanStopSignal {
    override fun isStopped(): Boolean = stopped
}

private class FakeCover(private val stop: FakeStop) : PlanAuthCover {
    override fun requestPlanCover(plan: TaskPlan): CoverResult = CoverResult.GRANTED
    override fun checkCover(step: PlanStep): Boolean {
        if (stop.stopped) return false
        if (step.kind == TaskStepKind.MESSAGE_SEND || step.kind == TaskStepKind.CALL_PLACE) return false
        return true
    }
}

private class FakeAudit : PlanAuditSink {
    val events = mutableListOf<Triple<Int, String, String>>()
    override fun log(planId: String, stepIndex: Int, event: String, detail: String) {
        events.add(Triple(stepIndex, event, detail))
    }
}

private class FakeStatusSink : TaskStatusSink {
    val updates = mutableListOf<TaskStatusUpdate>()
    override fun post(update: TaskStatusUpdate) {
        updates.add(update)
    }
}

/**
 * World-simulating dispatcher. [failSearchAlways] fails every
 * SEARCH_IN_APP dispatch without moving the world (the search never
 * ran — no results appear). [failTapAlways] fails every TAP_TARGET
 * dispatch with "target moved" (the repair path's trigger).
 */
private class FakeDispatcher(
    private val world: World,
) : StepDispatcher {
    val calls = mutableListOf<TaskStepKind>()
    val searchQueries = mutableListOf<String>()
    var failSearchAlways: String? = null
    var failTapAlways: Boolean = false
    /** When set, TAP moves the world to this screen instead of ytPlaying. */
    var tapScreen: ((String) -> ScreenModel)? = null

    override fun dispatch(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        calls.add(step.kind)
        return when (step.kind) {
            TaskStepKind.LAUNCH_APP -> {
                world.screen = ytHome()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN,
            TaskStepKind.VERIFY_OUTCOME, TaskStepKind.REPORT -> StepDispatchResult.DISPATCHED
            TaskStepKind.SEARCH_IN_APP -> {
                val query = step.params[PlanParams.QUERY].orEmpty()
                searchQueries.add(query)
                val fail = failSearchAlways
                if (fail != null) {
                    return StepDispatchResult.FAILED(fail)
                }
                // Universal Console (2026-11-07): the fake world is
                // goal-aware like the real search sequence — a play
                // search (open_first) opens the first result itself,
                // so the world moves straight to the player; a plain
                // search (show_results, or no goal param as in the
                // explicit D2 plan) stops at the results list.
                val goal = step.params[PlanParams.SEARCH_GOAL].orEmpty()
                world.screen = if (goal == PlanParams.SEARCH_GOAL_OPEN_FIRST) {
                    ytPlaying(query)
                } else {
                    ytResults(query)
                }
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.TAP_TARGET -> {
                if (failTapAlways) return StepDispatchResult.FAILED("target moved")
                val target = step.params[PlanParams.TARGET].orEmpty()
                world.screen = tapScreen?.invoke(target) ?: ytPlaying(target)
                StepDispatchResult.DISPATCHED
            }
            else -> StepDispatchResult.DISPATCHED
        }
    }
}

private class Rig(
    val world: World = World(ytHome()),
    val stop: FakeStop = FakeStop(),
) {
    val observer = FakeObserver(world)
    val pointer = FakePointer()
    val cover = FakeCover(stop)
    val audit = FakeAudit()
    val dispatcher = FakeDispatcher(world)
    val statusSink = FakeStatusSink()

    fun executor(planner: TaskPlanner = TaskPlanner(fakeApps)) = TaskPlanExecutor(
        apps = fakeApps,
        planner = planner,
        dispatcher = dispatcher,
        observer = observer,
        pointer = pointer,
        cover = cover,
        stop = stop,
        audit = audit,
        statusSink = statusSink,
    )

    fun planner() = TaskPlanner(fakeApps)
}

private fun req(
    goal: String,
    origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    contextApp: String? = null,
) = PlanRequest(goal = goal, origin = origin, transparency = false, contextApp = contextApp)

fun main() {
    // ---------- A1. ACCEPTANCE 1: "Play me some music on YouTube" ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("Play me some music on YouTube"))
        checkEq("A1 outcome completed", outcome.status, PlanStatus.COMPLETED)
        checkEq("A1 verified all 6 steps", outcome.verifiedSteps, 6)
        checkEq("A1 total 6 steps", outcome.totalSteps, 6)
        check("A1 summary says done", outcome.summary.startsWith("Done"))
        check("A1 searched", TaskStepKind.SEARCH_IN_APP in rig.dispatcher.calls)
        // Universal Console (2026-11-07): the search step itself
        // opens the first result — there is NO separate tap any
        // more (the old plan tapped the result a second time on
        // the already-opened player screen).
        check("A1 no separate tap ran", TaskStepKind.TAP_TARGET !in rig.dispatcher.calls)
        // (ScreenModel carries capturedAt, so equality is by
        // content: the player screen shows "Now playing".)
        check(
            "A1 search opened the result",
            rig.world.screen.elements.any { it.text?.contains("Now playing") == true },
        )
        check("A1 pointer moved to a target", rig.pointer.moves.isNotEmpty())
        check(
            "A1 pointer narrated the search step",
            rig.pointer.shownSteps.any { it.kind == TaskStepKind.SEARCH_IN_APP },
        )
        // Device-failure fix (2026-11-13, F2): per-step chat
        // state — the start post plus one IN_PROGRESS post per
        // verified step, then the terminal COMPLETED post. The
        // user sees each step's outcome as it completes, never
        // only the terminal summary.
        checkEq(
            "A1 status sequence is start + per-step + completed",
            rig.statusSink.updates.map { it.status },
            listOf(TaskStatus.IN_PROGRESS) + List(6) { TaskStatus.IN_PROGRESS } +
                listOf(TaskStatus.COMPLETED),
        )
        check(
            "A1 start post keeps the Working-on-it wording",
            rig.statusSink.updates.first().message.startsWith("Working on it"),
        )
        check(
            "A1 every step posts its verified outcome",
            (1..6).all { n ->
                rig.statusSink.updates.any {
                    it.message.contains("Step $n of 6") && it.message.contains("verified")
                }
            },
        )
        check(
            "A1 terminal status message is the done summary",
            rig.statusSink.updates.last().message.startsWith("Done"),
        )
    }

    // ---------- A2. NEGATIVE CONTROL: opening YouTube alone FAILS ----------
    run {
        val rig = Rig()
        rig.dispatcher.failSearchAlways = "search never ran"
        val outcome = rig.executor().execute(req("Play me some music on YouTube"))
        checkEq("A2 search-broken run fails", outcome.status, PlanStatus.FAILED)
        check("A2 no tap ran without results", TaskStepKind.TAP_TARGET !in rig.dispatcher.calls)
        check("A2 summary says not done", outcome.summary.contains("not done"))
        checkEq(
            "A2 partial status (launch verified, search failed)",
            rig.statusSink.updates.last().status,
            TaskStatus.PARTIALLY_COMPLETED,
        )
        check(
            "A2 partial message says partly done",
            rig.statusSink.updates.last().message.startsWith("Partly done"),
        )
        check("A2 some steps verified", outcome.verifiedSteps > 0)
    }

    // ---------- B. ACCEPTANCE 2: a specific song ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("Play Fall by Davido on YouTube"))
        checkEq("B song completes", outcome.status, PlanStatus.COMPLETED)
        check("B searched for Davido", rig.dispatcher.searchQueries.any { it.contains("Davido") })
        checkEq("B status completed", rig.statusSink.updates.last().status, TaskStatus.COMPLETED)
    }

    // ---------- C. ACCEPTANCE 3: a specific video ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("Play the Fall video by Davido on YouTube"))
        checkEq("C video completes", outcome.status, PlanStatus.COMPLETED)
        check("C no separate tap ran", TaskStepKind.TAP_TARGET !in rig.dispatcher.calls)
        check("C query names Davido", rig.dispatcher.searchQueries.any { it.contains("Davido") })
    }

    // ---------- D. DEPENDENCY-AWARE REPLAN: TAP never runs after a failed SEARCH ----------
    run {
        // D1. Planner level: a second replan that abandons the search
        // drops the tap and the verify with it.
        val planner = TaskPlanner(fakeApps)
        val davido = (planner.plan(req("Open YouTube Music and play a song by Davido"))
            as? TaskPlanner.PlanResult.Planned)?.plan
        check("D1 davido plan built", davido != null)
        if (davido != null) {
            val first = planner.replan(davido, davido.steps[3], "timeout", null)
            check("D1 first replan repairs the search", first is TaskPlanner.PlanResult.Planned)
            if (first is TaskPlanner.PlanResult.Planned) {
                // First replan: [OBSERVE, SEARCH, OBSERVE, VERIFY].
                // A second replan that abandons the SEARCH (index 1)
                // drops its dependent VERIFY too — only a
                // look-at-the-screen step remains, so it refuses
                // honestly (the Universal Console guard).
                val second = planner.replan(first.plan, first.plan.steps[1], "timeout", null)
                check(
                    "D1 second replan drops search + verify",
                    second is TaskPlanner.PlanResult.Unsupported,
                )
                if (second is TaskPlanner.PlanResult.Unsupported) {
                    check("D1 drop reason names the dependents", second.reason.contains("depended"))
                }
            }
            checkEq("D1 tap depends on search", TaskPlanner.dependsOn(TaskStepKind.TAP_TARGET), setOf(TaskStepKind.SEARCH_IN_APP))
            check(
                "D1 verify depends on search and tap",
                TaskPlanner.dependsOn(TaskStepKind.VERIFY_OUTCOME) ==
                    // NAV-SCREEN fix (2026-11-11, N1): a verify after
                    // a navigation depends on the navigation having
                    // run too — the set gained NAVIGATE_TAB when the
                    // kind was added; the test expectation predates it.
                    setOf(TaskStepKind.SEARCH_IN_APP, TaskStepKind.TAP_TARGET, TaskStepKind.NAVIGATE_TAB),
            )
        }
        // D2. Executor level: a tap that keeps failing is repaired
        // once, then dropped with its verify — the verify NEVER runs
        // after the tap it verifies was abandoned.
        val rig = Rig()
        rig.dispatcher.failTapAlways = true
        // Device-failure fix (2026-11-13, F2): the launch-order
        // entry gate requires a launch before the app-scoped
        // steps; the world's LAUNCH lands on the YouTube home
        // screen, so the search/tap sequence runs as before.
        val launchParams = mapOf(PlanParams.APP to "YouTube", PlanParams.PACKAGE to YT_PKG)
        val plan = TaskPlan(
            id = "plan-tap-fails",
            goal = "tap a result",
            steps = listOf(
                PlanStep(0, TaskStepKind.LAUNCH_APP, "Open YouTube", launchParams),
                PlanStep(1, TaskStepKind.WAIT_FOR_APP, "Wait for YouTube", launchParams),
                PlanStep(2, TaskStepKind.SEARCH_IN_APP, "Search for Davido",
                    mapOf(PlanParams.APP to "YouTube", PlanParams.QUERY to "Davido", PlanParams.PACKAGE to YT_PKG)),
                PlanStep(3, TaskStepKind.OBSERVE_SCREEN, "Look at the search results", critical = false),
                PlanStep(4, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first", PlanParams.PACKAGE to YT_PKG)),
                PlanStep(5, TaskStepKind.VERIFY_OUTCOME, "Check that Davido is playing",
                    mapOf(PlanParams.EXPECTED to "playing Davido", PlanParams.PACKAGE to YT_PKG), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap a result"))
        checkEq("D2 tap-fails run fails", outcome.status, PlanStatus.FAILED)
        check("D2 verify never ran after the abandoned tap", TaskStepKind.VERIFY_OUTCOME !in rig.dispatcher.calls)
        check("D2 summary names the dropped tap", outcome.summary.contains("Tap the first result"))
        checkEq("D2 terminal status is partial (search verified)", rig.statusSink.updates.last().status, TaskStatus.PARTIALLY_COMPLETED)
    }

    // ---------- F. PLAYBACK VERIFICATION VARIANTS ----------
    run {
        check("F pause-only player counts as playing", expectedOnScreen(ytPlayingPauseOnly("Davido"), "playing Davido"))
        check("F now-playing text counts as playing", expectedOnScreen(ytPlaying("Davido"), "playing Davido"))
        check("F results alone are not playback", !expectedOnScreen(ytResults("Davido"), "playing Davido"))
        check("F wrong song is not this song", !expectedOnScreen(ytPlaying("Davido"), "playing Burna Boy"))
        check("F pause-only without the song fails", !expectedOnScreen(ytPlayingPauseOnly("Davido"), "playing Burna Boy"))
        // SEARCH_IN_APP verification: the box alone is never results.
        check("F two matching rows are results", searchResultsShown(ytResults("Davido"), "Davido"))
        check("F box + no-results is not results", !searchResultsShown(ytNoResults(), "Davido"))
        check("F home screen is not results", !searchResultsShown(ytHome(), "Davido"))
        check("F one matching row is a suggestion, not results", !searchResultsShown(
            ScreenModel(YT_PKG, listOf(
                el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YouTube"),
                el("row-1", "Davido — Fall", ElementRole.TEXT, 10, 120, 500, 200),
            )),
            "Davido",
        ))
    }

    // ---------- G. STATUS DELIVERY: the five states, in order ----------
    run {
        // G1. Clarification is WAITING, not FAILED-as-a-state.
        val rig = Rig()
        val outcome = rig.executor().execute(req("play music"))
        checkEq("G1 bare play with no app asks once", outcome.status, PlanStatus.FAILED)
        check("G1 summary asks the question", outcome.summary.contains("Which app"))
        checkEq(
            "G1 status is waiting for user input",
            rig.statusSink.updates.last().status,
            TaskStatus.WAITING_FOR_USER_INPUT,
        )
        check("G1 no steps dispatched", rig.dispatcher.calls.isEmpty())
        // G2. Nothing verified + failed = FAILED (not partial).
        val rig2 = Rig()
        rig2.dispatcher.failSearchAlways = "search never ran"
        val outcome2 = rig2.executor().execute(req("Play me some music on YouTube"))
        checkEq("G2 terminal failed-with-verified is partial", rig2.statusSink.updates.last().status, TaskStatus.PARTIALLY_COMPLETED)
        // G3. In-progress is posted before any step runs.
        val rig3 = Rig()
        rig3.executor().execute(req("Play me some music on YouTube"))
        checkEq("G3 first status is in-progress", rig3.statusSink.updates.first().status, TaskStatus.IN_PROGRESS)
        check("G3 in-progress names the goal", rig3.statusSink.updates.first().message.contains("Play me some music on YouTube"))
    }

    // ---------- H. INTENT ROUTING: play-on-app ----------
    run {
        checkEq(
            "H play-on-app routes to the planner",
            VoiceCommands.match("play me some music on YouTube"),
            VoiceCommand.PLAN_TASK,
        )
        checkEq(
            "H play-on-app extracts YouTube",
            VoiceCommands.extractPlanApp("play me some music on YouTube"),
            "YouTube",
        )
        checkEq(
            "H song-on-app routes to the planner",
            VoiceCommands.match("play Fall by Davido on YouTube"),
            VoiceCommand.PLAN_TASK,
        )
        checkEq(
            "H video-on-app extracts YouTube",
            VoiceCommands.extractPlanApp("play the Fall video by Davido on YouTube"),
            "YouTube",
        )
        check(
            "H bare play stays bare (no app to extract)",
            VoiceCommands.extractPlanApp("play music").isBlank(),
        )
        check(
            "H bare play is not a plan task on its own",
            VoiceCommands.match("play music") != VoiceCommand.PLAN_TASK,
        )
        // Planner: the app, the default query, and the package.
        val planner = TaskPlanner(fakeApps)
        val bare = (planner.plan(req("Play me some music on YouTube"))
            as? TaskPlanner.PlanResult.Planned)?.plan
        check("H plan built", bare != null)
        if (bare != null) {
            checkEq("H plan has 6 steps", bare.steps.size, 6)
            val search = bare.steps.first { it.kind == TaskStepKind.SEARCH_IN_APP }
            checkEq("H default query is the media kind", search.params[PlanParams.QUERY], "music")
            checkEq("H search carries the package", search.params[PlanParams.PACKAGE], YT_PKG)
            checkEq("H search carries the app", search.params[PlanParams.APP], "YouTube")
            checkEq(
                "H search goal opens the first result",
                search.params[PlanParams.SEARCH_GOAL],
                PlanParams.SEARCH_GOAL_OPEN_FIRST,
            )
            check("H plan has no separate tap", bare.steps.none { it.kind == TaskStepKind.TAP_TARGET })
            val wait = bare.steps.first { it.kind == TaskStepKind.WAIT_FOR_APP }
            checkEq("H wait carries the package", wait.params[PlanParams.PACKAGE], YT_PKG)
            checkEq("H record app is YouTube", bare.record?.app, "YouTube")
            checkEq("H record query is music", bare.record?.query, "music")
        }
        // Default-query rule, general cases (no hardcoded sentences).
        val musicCtx = (planner.plan(req("play music", contextApp = "YouTube"))
            as? TaskPlanner.PlanResult.Planned)?.plan
        checkEq(
            "H bare play with context app defaults to music",
            musicCtx?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "music",
        )
        val video = (planner.plan(req("play a video on YouTube"))
            as? TaskPlanner.PlanResult.Planned)?.plan
        checkEq(
            "H bare video request defaults to video",
            video?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "video",
        )
        val named = (planner.plan(req("play some songs by Davido on YouTube"))
            as? TaskPlanner.PlanResult.Planned)?.plan
        checkEq(
            "H named artist keeps the name",
            named?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "Davido",
        )
        // Find-words play shape: same play plan, in find-words.
        checkEq(
            "H find-and-play routes to the planner",
            VoiceCommands.match("find Fall by Davido on YouTube and play it"),
            VoiceCommand.PLAN_TASK,
        )
        checkEq(
            "H find-and-play extracts YouTube",
            VoiceCommands.extractPlanApp("find Fall by Davido on YouTube and play it"),
            "YouTube",
        )
        val found = (planner.plan(req("find Fall by Davido on YouTube and play it"))
            as? TaskPlanner.PlanResult.Planned)?.plan
        checkEq(
            "H find-and-play query names Davido",
            found?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "Fall by Davido",
        )
        checkEq(
            "H find-and-play app is YouTube",
            found?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.APP),
            "YouTube",
        )
        run {
            val rig = Rig()
            val outcome = rig.executor().execute(req("find Fall by Davido on YouTube and play it"))
            checkEq("H find-and-play completes end to end", outcome.status, PlanStatus.COMPLETED)
        }
        // The Nov 6 shape still works: composite with the app named.
        val composite = (planner.plan(req("open YouTube Music and play a song by Davido"))
            as? TaskPlanner.PlanResult.Planned)?.plan
        checkEq(
            "H composite still plans",
            composite?.steps?.firstOrNull { it.kind == TaskStepKind.SEARCH_IN_APP }?.params?.get(PlanParams.QUERY),
            "Davido",
        )
    }

    // ---------- I. UNIVERSAL CONSOLE: search goal end to end ----------
    run {
        // A plain search completes at the results list — the world
        // is results, never the player, and no tap ever runs.
        val rig = Rig()
        val outcome = rig.executor().execute(req("Search YouTube for Burna Boy"))
        checkEq("I plain search completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("I plain search verified 6 steps", outcome.verifiedSteps, 6)
        // (ScreenModel carries capturedAt: compare by content —
        // result rows present, no player screen.)
        check(
            "I plain search world is results",
            rig.world.screen.elements.any { it.text?.contains("Burna Boy") == true } &&
                rig.world.screen.elements.none { it.text?.contains("Now playing") == true },
        )
        check("I plain search ran no tap", TaskStepKind.TAP_TARGET !in rig.dispatcher.calls)
        checkEq(
            "I plain search terminal status completed",
            rig.statusSink.updates.last().status,
            TaskStatus.COMPLETED,
        )
    }
    run {
        // NEGATIVE: a results list is NOT playback. A plan that
        // searches (show_results) and then verifies "playing" must
        // FAIL — the search verified, the playback verify did not.
        val rig = Rig()
        val plan = TaskPlan(
            id = "plan-results-are-not-playback",
            goal = "search then claim playing",
            steps = listOf(
                PlanStep(0, TaskStepKind.LAUNCH_APP, "Open YouTube",
                    mapOf(PlanParams.APP to "YouTube", PlanParams.PACKAGE to YT_PKG)),
                PlanStep(1, TaskStepKind.WAIT_FOR_APP, "Wait for YouTube",
                    mapOf(PlanParams.APP to "YouTube", PlanParams.PACKAGE to YT_PKG)),
                PlanStep(2, TaskStepKind.SEARCH_IN_APP, "Search for Burna Boy",
                    mapOf(
                        PlanParams.APP to "YouTube", PlanParams.QUERY to "Burna Boy",
                        PlanParams.PACKAGE to YT_PKG,
                        PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_SHOW_RESULTS,
                    )),
                PlanStep(3, TaskStepKind.OBSERVE_SCREEN, "Look at the results", critical = false),
                PlanStep(4, TaskStepKind.VERIFY_OUTCOME, "Check that Burna Boy is playing",
                    mapOf(PlanParams.EXPECTED to "playing Burna Boy", PlanParams.PACKAGE to YT_PKG), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("search then claim playing"))
        checkEq("I results-are-not-playback fails", outcome.status, PlanStatus.FAILED)
        check("I search verified before the failed playback check", outcome.verifiedSteps == 4)
        check(
            "I terminal status is partial, never completed",
            rig.statusSink.updates.last().status == TaskStatus.PARTIALLY_COMPLETED,
        )
        check("I summary says not confirmed done", outcome.summary.contains("not confirmed done"))
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
