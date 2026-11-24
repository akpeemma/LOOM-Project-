package com.loom.assistant.execution.tasks

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.MeasuredControl
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
import com.loom.assistant.intelligence.planner.ScreenKnowledgeStore
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.StepDispatcher
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskPlanner
import com.loom.assistant.intelligence.planner.TaskStepKind
import com.loom.assistant.intelligence.planner.TargetBounds
import com.loom.assistant.intelligence.planner.VerificationResult
import com.loom.assistant.platform.services.CoverTokenConsumer
import com.loom.assistant.platform.services.CoverTokenRequest
import com.loom.assistant.platform.services.PlanCover

/**
 * Stage 2D (Worker B) — TaskPlanExecutor checks.
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
 *     $R/execution/tasks/src/main/java/com/loom/assistant/execution/tasks/PlanVerify.kt \
 *     $R/execution/tasks/src/main/java/com/loom/assistant/execution/tasks/TaskPlanExecutor.kt \
 *     $R/execution/tasks/src/test/java/com/loom/assistant/execution/tasks/TaskPlanExecutorTest.kt \
 *   && $JAVA -cp out:$STDLIB com.loom.assistant.execution.tasks.TaskPlanExecutorTestKt
 *
 * The fake dispatcher is a world simulator: it moves a shared mutable
 * screen (LAUNCH → home, SEARCH → results, TAP → playing) so the
 * executor's fresh-observation verification judges a live tree, not a
 * stubbed boolean.
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

// ---------- fixtures: YT Music live-tree shapes ----------

const val YTM_PKG = "com.google.android.apps.youtube.music"
const val OTHER_PKG = "com.other.app"

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

private fun ytmHome() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
        el("home-row", "Home", ElementRole.TEXT, 10, 120, 500, 170),
    ),
)

private fun ytmResults(withSongs: Boolean = true) = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
    ) + if (withSongs) {
        listOf(
            el("row-fall", "Davido — Fall", ElementRole.TEXT, 10, 120, 500, 200),
            el("row-if", "Davido — If", ElementRole.TEXT, 10, 220, 500, 300),
        )
    } else {
        listOf(el("no-results", "No results found", ElementRole.TEXT, 10, 120, 500, 170))
    },
)

private fun ytmPlaying() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        el("now-playing", "Now playing: Davido — Fall", ElementRole.TEXT, 10, 10, 500, 90),
        el("pause", "Pause", ElementRole.BUTTON, 10, 120, 120, 170),
        el("row-fall", "Davido — Fall", ElementRole.TEXT, 10, 220, 500, 300),
    ),
)

private fun customScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        el("row-alpha", "Alpha song", ElementRole.TEXT, 10, 10, 500, 90),
        el("row-beta", "Beta song", ElementRole.TEXT, 10, 120, 500, 200),
        el("row-gamma", "Gamma song", ElementRole.TEXT, 10, 230, 500, 310),
    ),
)

/**
 * BUILT-IN RECOVERY tests (2026-11-09): a dialog screen in
 * another app — the wrong page. The pre-step check refuses action
 * steps on it, so a search step fails with "unexpected screen".
 */
private fun dialogScreen() = ScreenModel(
    packageName = "com.instagram.android",
    elements = listOf(
        el("dlg", "Something went wrong", ElementRole.DIALOG, 100, 800, 980, 1200),
        el("dlg-ok", "OK", ElementRole.BUTTON, 400, 1100, 680, 1200),
    ),
    screenBounds = Rect(0, 0, 1080, 2400),
)

/** A search screen holding a wrong query and a visible clear control. */
private fun searchWithClear() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
        el("clear", "Clear", ElementRole.BUTTON, 900, 10, 1080, 90),
        el("row-fall", "Davido — Fall", ElementRole.TEXT, 10, 120, 500, 200),
    ),
    keyboardVisible = true,
)

/** A screen whose navigation control sits at the very bottom. */
private fun bottomNavScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        el("top-row", "Davido — Fall", ElementRole.TEXT, 10, 120, 500, 200),
        el("nav-home", "Home", ElementRole.NAVIGATION, 100, 2200, 300, 2400),
    ),
    screenBounds = Rect(0, 0, 1080, 2400),
)

/**
 * NAV-SCREEN fix (2026-11-11) — David's Facebook feed, from his
 * screenshot (720x1640): bottom nav y≈1540–1580, six icon-only
 * tabs, no text labels — Home (selected), Watch, Reels (3rd),
 * megaphone (unidentified), bell, profile avatar (Menu) — plus a
 * scrollable feed above it. [selectedIndex] is the selected tab.
 */
private fun fbEl(
    id: String,
    className: String,
    bounds: Rect,
    role: ElementRole = ElementRole.ICON,
    selected: Boolean = false,
) = ScreenElement(
    id = id,
    text = null,
    contentDescription = null,
    className = className,
    bounds = bounds,
    role = role,
    enabled = true,
    isPassword = false,
    selected = selected,
)

private fun facebookFeed(selectedIndex: Int = 0): ScreenModel {
    val nav = fbEl(
        "nav", "com.google.android.material.bottomnavigation.BottomNavigationView",
        Rect(0, 1520, 720, 1640), role = ElementRole.NAVIGATION,
    )
    val tabBounds = listOf(
        Rect(0, 1540, 120, 1580),
        Rect(120, 1540, 240, 1580),
        Rect(240, 1540, 360, 1580),
        Rect(360, 1540, 480, 1580),
        Rect(480, 1540, 600, 1580),
        Rect(600, 1540, 720, 1580),
    )
    val tabs = tabBounds.mapIndexed { i, b ->
        fbEl("tab$i", "android.widget.ImageView", b, selected = i == selectedIndex)
    }
    val feed = fbEl(
        "feed", "androidx.recyclerview.widget.RecyclerView",
        Rect(0, 200, 720, 1520), role = ElementRole.LIST,
    )
    val post = ScreenElement(
        id = "post1", text = "A friend posted a photo", contentDescription = null,
        className = "android.widget.TextView", bounds = Rect(0, 300, 720, 600),
        role = ElementRole.TEXT, enabled = true, isPassword = false,
    )
    return ScreenModel(
        packageName = "com.facebook.katana",
        elements = listOf(nav) + tabs + listOf(feed, post),
        screenBounds = Rect(0, 0, 720, 1640),
    )
}

private fun facebookReels() = facebookFeed(selectedIndex = 2)
private fun facebookWatch() = facebookFeed(selectedIndex = 1)

/**
 * Device-failure fix (2026-11-13, F2): the recovery tests'
 * search step names NO app on purpose. These are current-screen
 * recovery tests (wrong page, clear retry): the world starts on
 * the dialog / the stale search screen, and a launch prefix
 * would move the world off the very setup the test exists to
 * exercise. An unscoped SEARCH_IN_APP passes the launch-order
 * entry gate (it names no app) and the recovery itself — the
 * refusal, the go-back, the clear tap, the re-issue — runs
 * exactly as before. The app-scoped shape (PACKAGE + launch)
 * is covered by DeviceFailureTest §B–§E.
 */
private fun searchStep(index: Int) = PlanStep(
    index, TaskStepKind.SEARCH_IN_APP, "Search for Davido on the current screen",
    mapOf(
        PlanParams.QUERY to "Davido",
        PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_SHOW_RESULTS,
    ),
)

private fun verifyDavidoStep(index: Int) = PlanStep(
    index, TaskStepKind.VERIFY_OUTCOME, "Check that Davido is playing",
    mapOf(PlanParams.EXPECTED to "Davido"), critical = false,
)

private val fakeApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" -> AppResolution.Found(YTM_PKG, "YT Music")
        "youtube" -> AppResolution.Found("com.google.android.youtube", "YouTube")
        "whatsapp" -> AppResolution.Found("com.whatsapp", "WhatsApp")
        "messages" -> AppResolution.Found("com.google.android.apps.messaging", "Messages")
        "music" -> AppResolution.Ambiguous(listOf("YT Music", "Music Player"))
        else -> AppResolution.NotFound
    }
}

// ---------- fakes ----------

/** The shared mutable world: observer reads it, dispatcher moves it. */
private class World(var screen: ScreenModel)

private class FakeObserver(private val world: World) : ScreenObserver {
    var calls = 0
    override fun observeNow(): ScreenModel {
        calls++
        return world.screen
    }
}

private class FakePointer : PointerGuide {
    val moves = mutableListOf<TargetBounds>()
    val shownSteps = mutableListOf<PlanStep>()
    var trackResult = true
    val tracked = mutableListOf<String>()
    var cleared = 0

    /**
     * Pointer-drive fix (2026-11-06): when non-null, every pointer
     * call leaves this as the last failure (a down renderer / HIDE /
     * marshal timeout); a successful call clears it. Mirrors the real
     * guides' lastPointerFailure contract.
     */
    var failAllWith: String? = null

    /** When true, moveTo throws (a draw that throws) and records it. */
    var throwOnMove = false

    /** Glide wait the fake reports; calls recorded so tests can assert the executor consulted it. */
    var glideWaitMsValue: Long = 0L
    val glideWaitCalls = mutableListOf<TargetBounds>()

    private var lastFailure: String? = null

    override fun moveTo(bounds: TargetBounds, highlight: Boolean) {
        if (throwOnMove) {
            lastFailure = "the pointer could not be shown (IllegalStateException)"
            throw IllegalStateException("fake draw threw")
        }
        moves.add(bounds)
        lastFailure = failAllWith
    }

    override fun showIntended(step: PlanStep) {
        shownSteps.add(step)
        lastFailure = failAllWith
    }

    override fun trackTarget(targetId: String): Boolean {
        tracked.add(targetId)
        lastFailure = failAllWith
        return trackResult
    }

    override fun clearPlanTarget() {
        cleared++
    }

    override fun lastPointerFailure(): String? = lastFailure

    override fun glideWaitMs(bounds: TargetBounds): Long {
        glideWaitCalls.add(bounds)
        return glideWaitMsValue
    }
}

private class FakeStop(var stopped: Boolean = false) : PlanStopSignal {
    override fun isStopped(): Boolean = stopped
}

/**
 * Fake cover. [checkCover] is in-scope for steps without a PACKAGE
 * param or with the YTM package, out-of-scope otherwise; message/call
 * steps are never covered.
 */
private class FakeCover(private val stop: FakeStop) : PlanAuthCover {
    var requestCount = 0
    var lastOrigin: RequestOrigin? = null
    var lastPlan: TaskPlan? = null
    var cardShown = false
    var mode: CoverResult = CoverResult.GRANTED
    /** When true, the stop fires inside requestPlanCover (card-wait stop). */
    var flipStopDuringRequest = false

    override fun requestPlanCover(plan: TaskPlan): CoverResult {
        requestCount++
        lastOrigin = plan.origin
        lastPlan = plan
        cardShown = true
        if (flipStopDuringRequest) stop.stopped = true
        return mode
    }

    override fun checkCover(step: PlanStep): Boolean {
        if (stop.stopped) return false
        if (step.kind == TaskStepKind.MESSAGE_SEND || step.kind == TaskStepKind.CALL_PLACE) return false
        val pkg = step.params[PlanParams.PACKAGE] ?: return true
        return pkg == YTM_PKG
    }
}

private class FakeAudit : PlanAuditSink {
    val events = mutableListOf<Triple<Int, String, String>>()
    override fun log(planId: String, stepIndex: Int, event: String, detail: String) {
        events.add(Triple(stepIndex, event, detail))
    }
}

/**
 * World-simulating dispatcher. [failSearchOnceWith] fails the first
 * SEARCH_IN_APP dispatch with that reason (the world still moves to
 * results — the search ran, the report was stale). [failTapWith]
 * fails every TAP dispatch. [tapChangesWorld] false = the tap is
 * accepted but the screen never changes (no confirming observation).
 * [refuseTap] returns REFUSED instead of dispatching the tap.
 * [stopAfterSearch] flips the stop after a SEARCH dispatch.
 */
private class FakeDispatcher(
    private val world: World,
    private val stop: FakeStop,
) : StepDispatcher {
    val calls = mutableListOf<TaskStepKind>()
    /** Every step actually dispatched, in order (for recovery-param assertions). */
    val dispatchedSteps = mutableListOf<PlanStep>()
    /** The SUBMIT_ORDER each SEARCH_IN_APP step ran with. */
    val searchOrders = mutableListOf<String>()
    /** Apps the SEARCH_IN_APP steps actually dispatched with. */
    val searchApps = mutableListOf<String>()
    var failSearchOnceWith: String? = null
    /** Fails the next [failSearchTimes] SEARCH_IN_APP dispatches with [failSearchWith]. */
    var failSearchWith: String? = null
    var failSearchTimes: Int = 0
    var failTapWith: String? = null
    var failTapTargets: Set<String> = emptySet()
    var tapChangesWorld = true
    var refuseTap = false
    var stopAfterSearch = false
    /** NAV-SCREEN fix (2026-11-11): the next NAVIGATE_TAB lands on
     *  the WRONG tab (Watch) instead of the requested one — the
     *  wrong-tab negative: verification must fail, never pass. */
    var navLandsOnWatch = false
    /** D-07: an open-first search that lands on the results list
     *  instead of a player — the world stays on results so the
     *  strengthened open-first verification can fail honestly. */
    var openFirstStaysOnResults = false

    override fun dispatch(step: PlanStep, screen: ScreenModel): StepDispatchResult {
        calls.add(step.kind)
        dispatchedSteps.add(step)
        return when (step.kind) {
            TaskStepKind.LAUNCH_APP -> {
                world.screen = ytmHome()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN,
            TaskStepKind.VERIFY_OUTCOME, TaskStepKind.REPORT -> StepDispatchResult.DISPATCHED
            TaskStepKind.SEARCH_IN_APP -> {
                searchApps.add(step.params[PlanParams.APP].orEmpty())
                searchOrders.add(step.params[PlanParams.SUBMIT_ORDER].orEmpty())
                if (failSearchTimes > 0 && failSearchWith != null) {
                    failSearchTimes--
                    StepDispatchResult.FAILED(failSearchWith!!)
                } else {
                    val fail = failSearchOnceWith
                    if (fail != null) {
                        // A failed search moves nothing: the world only
                        // changes when the search actually ran, so a
                        // repair re-issue still sees a real screen change.
                        failSearchOnceWith = null
                        StepDispatchResult.FAILED(fail)
                    } else {
                        // Universal Console (2026-11-07): the fake world is
                        // goal-aware like the real search sequence — a play
                        // search (open_first) opens the first result itself,
                        // so the world moves straight to the player; a plain
                        // search (show_results, or no goal param as in the
                        // explicit plans) stops at the results list, and the
                        // plan's own TAP_TARGET moves it to playing.
                        val goal = step.params[PlanParams.SEARCH_GOAL].orEmpty()
                        world.screen = if (goal == PlanParams.SEARCH_GOAL_OPEN_FIRST && !openFirstStaysOnResults) {
                            ytmPlaying()
                        } else {
                            ytmResults()
                        }
                        if (stopAfterSearch) stop.stopped = true
                        StepDispatchResult.DISPATCHED
                    }
                }
            }
            TaskStepKind.TAP_TARGET -> {
                val target = step.params[PlanParams.TARGET].orEmpty()
                if (refuseTap) return StepDispatchResult.REFUSED("Guidance only in this app.")
                val fail = failTapWith ?: if (target in failTapTargets) "target moved" else null
                if (fail != null) return StepDispatchResult.FAILED(fail)
                // BUILT-IN RECOVERY (2026-11-09): tapping the clear
                // control empties the field — the world returns to
                // the search-entry screen with no results showing, so
                // the re-issued search has something to do.
                if (tapChangesWorld) world.screen = if (target == "clear") ytmHome() else ytmPlaying()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.SUBMIT -> {
                world.screen = ytmResults()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.SCROLL -> {
                world.screen = ytmResults()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.SWIPE -> {
                // POINTER EVERYWHERE: a swipe moves the world so the
                // executor's screen-changed verification can pass.
                world.screen = ytmResults()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.GO_BACK -> {
                world.screen = ytmHome()
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.TYPE_TEXT -> {
                // Screen confirmation (2026-11-10): the fake world
                // types like the real search sequence — the field
                // shows the typed text afterwards, so verification
                // can see it. Without this the field stays unreadable
                // and the step honestly fails (and retries).
                val text = step.params[PlanParams.TEXT].orEmpty()
                if (text.isNotBlank()) {
                    world.screen = world.screen.copy(
                        elements = world.screen.elements.map { el ->
                            if (isFieldElement(el)) el.copy(text = text) else el
                        },
                    )
                }
                StepDispatchResult.DISPATCHED
            }
            TaskStepKind.GUIDE_TO -> StepDispatchResult.DISPATCHED
            TaskStepKind.NAVIGATE_TAB -> {
                // NAV-SCREEN fix (2026-11-11): the world moves to
                // the requested tab — the tap lands on the tab the
                // resolver found, so the verifier judges a real
                // selection change, not a stubbed boolean.
                val target = step.params[PlanParams.TARGET].orEmpty()
                world.screen = when {
                    navLandsOnWatch -> facebookWatch()
                    target.lowercase() == "reels" -> facebookReels()
                    target.lowercase() == "watch" -> facebookWatch()
                    else -> world.screen
                }
                StepDispatchResult.DISPATCHED
            }
            else -> StepDispatchResult.DISPATCHED
        }
    }
}

private class Rig(
    val world: World = World(ytmHome()),
    val stop: FakeStop = FakeStop(),
    /**
     * KNOWLEDGE BASE (2026-11-09): the one shared store the
     * planner consults and the executor reads/writes — the same
     * instance on both sides, as production wiring provides.
     */
    val knowledge: ScreenKnowledgeStore = ScreenKnowledgeStore(),
) {
    val observer = FakeObserver(world)
    val pointer = FakePointer()
    val cover = FakeCover(stop)
    val audit = FakeAudit()
    val dispatcher = FakeDispatcher(world, stop)

    fun executor() = TaskPlanExecutor(
        apps = fakeApps,
        planner = TaskPlanner(fakeApps, knowledge = knowledge),
        dispatcher = dispatcher,
        observer = observer,
        pointer = pointer,
        cover = cover,
        stop = stop,
        audit = audit,
        knowledge = knowledge,
    )
}

private fun req(goal: String, origin: RequestOrigin = RequestOrigin.DIRECT_USER) =
    PlanRequest(goal = goal, origin = origin, transparency = true)

private fun tapStep(index: Int, target: String, pkg: String? = null): PlanStep {
    val params = mutableMapOf(PlanParams.TARGET to target, PlanParams.MATCH to "first")
    if (pkg != null) params[PlanParams.PACKAGE] = pkg
    return PlanStep(index, TaskStepKind.TAP_TARGET, "Tap $target", params)
}

fun main() {
    // ---------- 1. Davido end-to-end ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("davido completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("davido verified == total", outcome.verifiedSteps, outcome.totalSteps)
        // Universal Console (2026-11-07): the play plan is 6 steps —
        // the search step itself opens the first result, so there is
        // no separate TAP_TARGET any more.
        checkEq("davido total steps", outcome.totalSteps, 6)
        check("davido summary says done", outcome.summary.startsWith("Done"))
        checkEq("davido cover requested once", rig.cover.requestCount, 1)
        checkEq(
            "davido dispatch order",
            rig.dispatcher.calls,
            listOf(
                TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP,
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.SEARCH_IN_APP,
                TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME,
            ),
        )
        check("davido pointer parks on the search box", rig.pointer.moves.any { it.top == 10 })
        check("davido no separate tap ran", TaskStepKind.TAP_TARGET !in rig.dispatcher.calls)
        check("davido audit logged every step", rig.audit.events.count { it.second == "step-verified" } == 6)
    }

    // ---------- 2. search-in-app step ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("search for Davido in YouTube Music"))
        checkEq("search completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("search total steps", outcome.totalSteps, 6)
        check("search dispatched a search step", TaskStepKind.SEARCH_IN_APP in rig.dispatcher.calls)
    }

    // ---------- 3. target-moved → replan and succeed ----------
    // Simple-task execution fix (2026-11-07): the replan REPAIRS
    // the failed search (re-issues it after a fresh observe) instead
    // of dropping it — so the search runs twice (failed attempt +
    // repair) and the run verifies the fresh observe too.
    run {
        val rig = Rig()
        rig.dispatcher.failSearchOnceWith = "target moved"
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("moved replans and completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("moved total counts the fresh observe", outcome.totalSteps, 7)
        checkEq("moved verified counts all resolved work", outcome.verifiedSteps, 7)
        checkEq("moved launch ran once (completed steps never repeat)", rig.dispatcher.calls.count { it == TaskStepKind.LAUNCH_APP }, 1)
        checkEq("moved search ran twice (failed attempt + repair re-issue)", rig.dispatcher.calls.count { it == TaskStepKind.SEARCH_IN_APP }, 2)
        // Universal Console (2026-11-07): the repair re-issues the
        // search, which opens the result itself — no tap ever runs.
        checkEq("moved tap never runs", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 0)
        check("moved replan audited", rig.audit.events.any { it.second == "replan" })
    }

    // ---------- 4. target-gone → abort + ask user ----------
    run {
        val rig2 = Rig(world = World(ytmResults(withSongs = false)))
        val plan = TaskPlan(
            id = "plan-gone",
            goal = "tap Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first")),
                PlanStep(1, TaskStepKind.VERIFY_OUTCOME, "Check that Davido is playing",
                    mapOf(PlanParams.EXPECTED to "playing Davido"), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome2 = rig2.executor().executePlan(plan, req("tap Davido"))
        checkEq("gone aborts", outcome2.status, PlanStatus.FAILED)
        checkEq("gone failed step is the tap", outcome2.failedStep?.kind, TaskStepKind.TAP_TARGET)
        check("gone asks the user", outcome2.summary.contains("what should I tap instead"))
        checkEq("gone tap never dispatched", rig2.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 0)
    }

    // ---------- 5. interruption mid-plan ----------
    run {
        val rig = Rig()
        rig.dispatcher.stopAfterSearch = true
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("interrupted outcome", outcome.status, PlanStatus.INTERRUPTED)
        check("interrupted tap never ran", TaskStepKind.TAP_TARGET !in rig.dispatcher.calls)
        check("interrupted leaves steps unverified", outcome.verifiedSteps < outcome.totalSteps)
        check("interrupted summary honest", outcome.summary.startsWith("Stopped."))
    }

    // ---------- 6. sensitive-app step refused → abort ----------
    // Universal Console (2026-11-07): the planner's play plan no
    // longer carries a TAP_TARGET, so the refusal is exercised with
    // an explicit tap plan — the tap is the step the gate refuses.
    run {
        val rig = Rig(world = World(ytmResults()))
        rig.dispatcher.refuseTap = true
        val plan = TaskPlan(
            id = "plan-refused",
            goal = "tap Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first")),
                PlanStep(1, TaskStepKind.VERIFY_OUTCOME, "Check that Davido is playing",
                    mapOf(PlanParams.EXPECTED to "playing Davido"), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap Davido"))
        checkEq("refused aborts", outcome.status, PlanStatus.FAILED)
        checkEq("refused tap attempted once", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 1)
        check("refused verify never ran", TaskStepKind.VERIFY_OUTCOME !in rig.dispatcher.calls)
    }

    // ---------- 7. MODEL origin never inherits a stored grant ----------
    run {
        val rig = Rig()
        rig.executor().execute(req("Open YouTube Music and play a song by Davido", RequestOrigin.MODEL))
        checkEq("model origin passed through", rig.cover.lastOrigin, RequestOrigin.MODEL)
        check("model still cards", rig.cover.cardShown)
        val rig2 = Rig()
        rig2.executor().execute(req("Open YouTube Music and play a song by Davido", RequestOrigin.DIRECT_USER))
        checkEq("direct origin passed through", rig2.cover.lastOrigin, RequestOrigin.DIRECT_USER)
        check("direct sequence still cards", rig2.cover.cardShown)
    }

    // ---------- 8. per-sequence cover: one approval, out-of-scope gets its own check ----------
    // Device-test failures (2026-11-11, D-01): a tap whose package
    // is not the foreground app never dispatches — the old
    // expectation here (tap dispatched, plan completes) was the bug
    // the wrong-app guard fixes: on device that tap landed on LOOM's
    // own chat echo. The cover behaviour this test exists for is
    // unchanged: one approval still covers the whole plan.
    // Device-failure fix (2026-11-13, F2): the launch-order entry
    // gate rejects this plan outright — a tap naming OTHER_PKG
    // after a launch for YTM names no launch for ITS app — so the
    // cover is never requested (0, not 1) and nothing dispatches.
    // The tap still never dispatches and the run still fails.
    run {
        val rig = Rig()
        rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("one approval covers the plan", rig.cover.requestCount, 1)
        val rig2 = Rig()
        val plan = TaskPlan(
            id = "plan-oos",
            goal = "tap elsewhere",
            steps = listOf(
                PlanStep(0, TaskStepKind.LAUNCH_APP, "Open YouTube Music",
                    mapOf(PlanParams.APP to "YouTube Music", PlanParams.PACKAGE to YTM_PKG)),
                PlanStep(1, TaskStepKind.TAP_TARGET, "Tap something elsewhere",
                    mapOf(PlanParams.TARGET to "Home", PlanParams.PACKAGE to OTHER_PKG)),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome2 = rig2.executor().executePlan(plan, req("tap elsewhere"))
        checkEq("out-of-scope plan rejected before cover", rig2.cover.requestCount, 0)
        check("out-of-scope rejection names the launch rule", outcome2.summary.contains("LAUNCH_APP"))
        check("out-of-scope tap never dispatched", TaskStepKind.TAP_TARGET !in rig2.dispatcher.calls)
        checkEq("out-of-scope tap aborts", outcome2.status, PlanStatus.FAILED)
        checkEq("out-of-scope failed step is the tap", outcome2.failedStep?.kind, TaskStepKind.TAP_TARGET)
    }

    // ---------- 9. stop during the card wait abandons the token ----------
    run {
        val rig = Rig()
        rig.cover.flipStopDuringRequest = true
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("card-wait stop interrupts", outcome.status, PlanStatus.INTERRUPTED)
        checkEq("card-wait stop dispatches nothing", rig.dispatcher.calls.size, 0)
        checkEq("card-wait stop moves no pointer", rig.pointer.moves.size, 0)
    }

    // ---------- 10. verify-after-dispatch: accepted tap, no confirming observation ----------
    // Simple-task execution fix (2026-11-07): the failed tap is
    // repaired once (re-issued after a fresh observe), then
    // abandoned with its dependent verify — so the tap is dispatched
    // twice and the outcome names the stuck tap step, never "done".
    // Universal Console (2026-11-07): the planner's play plan no
    // longer carries a TAP_TARGET, so this is exercised with an
    // explicit tap plan on a results screen.
    run {
        val rig = Rig(world = World(ytmResults()))
        rig.dispatcher.tapChangesWorld = false
        val plan = TaskPlan(
            id = "plan-unverified-tap",
            goal = "tap Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first")),
                PlanStep(1, TaskStepKind.VERIFY_OUTCOME, "Check that Davido is playing",
                    mapOf(PlanParams.EXPECTED to "playing Davido"), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap Davido"))
        checkEq("unverified dispatch fails the plan", outcome.status, PlanStatus.FAILED)
        check("unverified never claims done", !outcome.summary.startsWith("Done"))
        check("unverified summary names the stuck tap", outcome.summary.contains("Tap the first result for Davido"))
        check("unverified summary says not done", outcome.summary.contains("not done"))
        checkEq("unverified tap dispatched twice (attempt + repair)", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 2)
    }

    // ---------- 11. stop rule: two identical no-change failures → stop ----------
    // Universal Console (2026-11-07): explicit tap plan (see 10).
    // Device-failure fix (2026-11-13, F2): David's stop rule,
    // widened — the same tap failing twice with no observable
    // change stops the run at 2 attempts, before MAX_STEP_ATTEMPTS
    // (3) is reached. Attempts that change the screen, or steps
    // the analyzer changes (mechanism rotation), still get the
    // full attempt budget.
    run {
        val rig = Rig(world = World(ytmResults()))
        rig.dispatcher.failTapWith = "timeout"
        val plan = TaskPlan(
            id = "plan-max-attempts",
            goal = "tap Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first")),
                PlanStep(1, TaskStepKind.VERIFY_OUTCOME, "Check that Davido is playing",
                    mapOf(PlanParams.EXPECTED to "playing Davido"), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap Davido"))
        checkEq("max attempts aborts", outcome.status, PlanStatus.FAILED)
        checkEq("stop rule tries twice, then stops", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 2)
        check("stop rule names the no-change stop", outcome.summary.contains("no change after two tries"))
    }

    // ---------- 12. cover DENIED → nothing dispatches ----------
    run {
        val rig = Rig()
        rig.cover.mode = CoverResult.DENIED("Not confirmed — I didn't start.")
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("denied fails", outcome.status, PlanStatus.FAILED)
        checkEq("denied dispatches nothing", rig.dispatcher.calls.size, 0)
        check("denied reason is the summary", outcome.summary.contains("Not confirmed"))
    }

    // ---------- 13. clarification → honest FAILED, no dispatch ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("open"))
        checkEq("clarification fails honestly", outcome.status, PlanStatus.FAILED)
        check("clarification asks", outcome.summary.contains("Which app"))
        checkEq("clarification dispatches nothing", rig.dispatcher.calls.size, 0)
    }

    // ---------- 14. replan budget spent → ABORT ----------
    run {
        val rig = Rig(world = World(customScreen()))
        rig.dispatcher.failTapTargets = setOf("Alpha", "Beta", "Gamma")
        val plan = TaskPlan(
            id = "plan-budget",
            goal = "tap three songs",
            steps = listOf(
                tapStep(0, "Alpha"),
                tapStep(1, "Beta"),
                tapStep(2, "Gamma"),
                PlanStep(3, TaskStepKind.VERIFY_OUTCOME, "Check that Gamma is playing",
                    mapOf(PlanParams.EXPECTED to "playing Gamma"), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap three songs"))
        checkEq("budget spent aborts", outcome.status, PlanStatus.FAILED)
        // Simple-task execution fix (2026-11-07): the failed Alpha tap
        // is repaired once, then abandoned — Beta never runs, because
        // the plan was abandoned, not continued blind. Two tap calls
        // total: the failed attempt + its repair re-issue.
        checkEq("tap calls total two (alpha + repair alpha)", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 2)
        // Simple-task execution fix (2026-11-07): the second replan
        // abandoned the critical Alpha tap, so the run fails naming
        // the dropped critical step — never a later step, never "done".
        check("budget summary names the dropped critical step", outcome.summary.contains("Alpha"))
    }

    // ---------- 15. plan target cleared when the plan ends (success) ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("cleared on success", outcome.status, PlanStatus.COMPLETED)
        checkEq("pointer target cleared once on success", rig.pointer.cleared, 1)
    }

    // ---------- 16. plan target cleared when the plan ends (failure) ----------
    // Universal Console (2026-11-07): explicit tap plan (see 10).
    run {
        val rig = Rig(world = World(ytmResults()))
        rig.dispatcher.failTapWith = "timeout"
        val plan = TaskPlan(
            id = "plan-cleared-failure",
            goal = "tap Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first")),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap Davido"))
        checkEq("cleared on failure", outcome.status, PlanStatus.FAILED)
        checkEq("pointer target cleared once on failure", rig.pointer.cleared, 1)
    }

    // ---------- 17. DIRECT_USER + real PlanCover: NO plan-approval card ----------
    // Corrected model (David, 2026-11-06): the plan is for LOOM, not
    // for David — his own words run under blanket advance approval.
    // The gate is never asked, no token is consumed, the grant is
    // audit-logged as blanket approval, and the plan still completes.
    run {
        val rig = Rig()
        var gateRequests = 0
        val consumed = mutableListOf<String>()
        val events = mutableListOf<String>()
        val cover = PlanCover(
            requestToken = CoverTokenRequest { _, _, _, _, _ ->
                gateRequests++
                "tok-should-never-be-asked-for"
            },
            consumeToken = CoverTokenConsumer { tokenId ->
                consumed.add(tokenId)
                true
            },
            stop = rig.stop,
            apps = fakeApps,
            onEvent = { events.add(it) },
        )
        val executor = TaskPlanExecutor(
            apps = fakeApps,
            dispatcher = rig.dispatcher,
            observer = rig.observer,
            pointer = rig.pointer,
            cover = cover,
            stop = rig.stop,
            audit = rig.audit,
        )
        val outcome = executor.execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("direct-user plan completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("direct-user plan asks the gate never", gateRequests, 0)
        checkEq("direct-user plan consumes no token", consumed.size, 0)
        check(
            "direct-user grant logged as blanket approval",
            events.any { it.contains("blanket advance approval") },
        )
        check(
            "direct-user grant never logged as a card approval",
            events.none { it.contains("card") },
        )
    }

    // ---------- 18. MODEL + real PlanCover: the card still stands ----------
    run {
        val rig = Rig()
        var gateRequests = 0
        var lastOrigin: RequestOrigin? = null
        val consumed = mutableListOf<String>()
        val cover = PlanCover(
            requestToken = CoverTokenRequest { _, _, _, origin, _ ->
                gateRequests++
                lastOrigin = origin
                "tok-model-1"
            },
            consumeToken = CoverTokenConsumer { tokenId ->
                consumed.add(tokenId)
                true
            },
            stop = rig.stop,
            apps = fakeApps,
        )
        val executor = TaskPlanExecutor(
            apps = fakeApps,
            dispatcher = rig.dispatcher,
            observer = rig.observer,
            pointer = rig.pointer,
            cover = cover,
            stop = rig.stop,
            audit = rig.audit,
        )
        val outcome = executor.execute(
            req("Open YouTube Music and play a song by Davido", RequestOrigin.MODEL),
        )
        checkEq("model plan completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("model plan asks the gate once", gateRequests, 1)
        checkEq("model plan origin is MODEL", lastOrigin, RequestOrigin.MODEL)
        checkEq("model plan token consumed once", consumed, listOf("tok-model-1"))
    }

    // ---------- 19. pointer-drive fix: the pointer drives every target step ----------
    // The Davido plan moves the pointer to the search box — the
    // search step itself opens the first result, so there is no
    // second target (no song-row tap) any more — and showIntended
    // names each step in the pill before dispatch. LAUNCH_APP has
    // no on-screen target, so it narrates without moving the
    // pointer to a fake target.
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("drive plan completes", outcome.status, PlanStatus.COMPLETED)
        checkEq(
            "pointer moves to the search box for the search",
            rig.pointer.moves,
            listOf(TargetBounds(10, 10, 500, 90)),
        )
        checkEq(
            "showIntended names launch and search before dispatch",
            rig.pointer.shownSteps.map { it.kind },
            listOf(TaskStepKind.LAUNCH_APP, TaskStepKind.SEARCH_IN_APP),
        )
        checkEq(
            "pointer waits for the glide before the search dispatch",
            rig.pointer.glideWaitCalls,
            listOf(TargetBounds(10, 10, 500, 90)),
        )
    }

    // ---------- 20. TYPE_TEXT parks on the field ----------
    run {
        val rig = Rig()
        val plan = TaskPlan(
            id = "plan-type",
            goal = "type Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TYPE_TEXT, "Type Davido",
                    mapOf(PlanParams.TEXT to "Davido")),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("type Davido"))
        checkEq("type completes", outcome.status, PlanStatus.COMPLETED)
        checkEq(
            "type parks on the field",
            rig.pointer.moves,
            listOf(TargetBounds(10, 10, 500, 90)),
        )
        checkEq("type showIntended names the step", rig.pointer.shownSteps.map { it.kind }, listOf(TaskStepKind.TYPE_TEXT))
    }

    // ---------- 21. SUBMIT parks on the search button ----------
    run {
        val submitScreen = ScreenModel(
            packageName = YTM_PKG,
            elements = listOf(
                el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
                el("search-btn", "Search", ElementRole.BUTTON, 520, 10, 640, 90),
            ),
        )
        val rig = Rig(world = World(submitScreen))
        val plan = TaskPlan(
            id = "plan-submit",
            goal = "submit the search",
            steps = listOf(
                PlanStep(0, TaskStepKind.SUBMIT, "Submit the search"),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("submit the search"))
        checkEq("submit completes", outcome.status, PlanStatus.COMPLETED)
        checkEq(
            "submit parks on the search button",
            rig.pointer.moves,
            listOf(TargetBounds(520, 10, 640, 90)),
        )
    }

    // ---------- 22. SCROLL parks on the list container ----------
    run {
        val listScreen = ScreenModel(
            packageName = YTM_PKG,
            elements = listOf(
                el("results-list", null, ElementRole.LIST, 10, 100, 500, 800),
                el("row-fall", "Davido — Fall", ElementRole.TEXT, 10, 120, 500, 200),
            ),
        )
        val rig = Rig(world = World(listScreen))
        val plan = TaskPlan(
            id = "plan-scroll",
            goal = "scroll down",
            steps = listOf(
                PlanStep(0, TaskStepKind.SCROLL, "Scroll down",
                    mapOf(PlanParams.DIRECTION to "down")),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("scroll down"))
        checkEq("scroll completes", outcome.status, PlanStatus.COMPLETED)
        checkEq(
            "scroll parks on the list container",
            rig.pointer.moves,
            listOf(TargetBounds(10, 100, 500, 800)),
        )
    }

    // ---------- 23. GUIDE_TO leads step by step (pointer moves, then waits) ----------
    run {
        val rig = Rig(world = World(ytmResults()))
        val plan = TaskPlan(
            id = "plan-guide",
            goal = "guide to Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.GUIDE_TO, "Guide you to Davido",
                    mapOf(PlanParams.GUIDE_TARGET to "Davido")),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("guide to Davido"))
        checkEq("guide completes", outcome.status, PlanStatus.COMPLETED)
        checkEq(
            "guide moves the pointer to the guide target",
            rig.pointer.moves,
            listOf(TargetBounds(10, 120, 500, 200)),
        )
        checkEq("guide showIntended names the step", rig.pointer.shownSteps.map { it.kind }, listOf(TaskStepKind.GUIDE_TO))
        checkEq(
            "guide waits for the glide before dispatch",
            rig.pointer.glideWaitCalls,
            listOf(TargetBounds(10, 120, 500, 200)),
        )
    }

    // ---------- 24. GO_BACK narrates without moving (no fake target) ----------
    run {
        val rig = Rig(world = World(ytmResults()))
        val plan = TaskPlan(
            id = "plan-back",
            goal = "go back",
            steps = listOf(
                PlanStep(0, TaskStepKind.GO_BACK, "Go back"),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("go back"))
        checkEq("back completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("back moves the pointer nowhere", rig.pointer.moves, emptyList<TargetBounds>())
        checkEq("back showIntended narrates the step", rig.pointer.shownSteps.map { it.kind }, listOf(TaskStepKind.GO_BACK))
    }

    // ---------- 25. a throwing pointer draw never fails the step ----------
    run {
        val rig = Rig()
        rig.pointer.throwOnMove = true
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("throwing draw still completes", outcome.status, PlanStatus.COMPLETED)
        check("throwing draw is named in the summary", outcome.summary.contains("could not show the pointer"))
        // Universal Console (2026-11-07): only the search step moves
        // the pointer now (step 4); the tap step is gone.
        check("throwing draw names the steps", outcome.summary.contains("step 4"))
    }

    // ---------- 26. trackTarget false + pointer draw failure → step UNVERIFIED ----------
    // Device-failure fix (2026-11-13, F2): the target is there and
    // the tap dispatches, but the pointer never displayed — so the
    // step is UNVERIFIED and the run FAILS honestly, never
    // completes. The old expectation (tap proceeds, plan
    // completes, summary names the gap) was the invisible-tap
    // defect: a tap David cannot see is not a verified tap. The
    // outcome still names the pointer gap instead of "target
    // gone", and retries still dispatch — none of them verifies.
    run {
        val rig = Rig(world = World(ytmResults()))
        rig.pointer.trackResult = false
        rig.pointer.failAllWith = "the pointer draw timed out waiting for the main thread"
        val plan = TaskPlan(
            id = "plan-trackfail",
            goal = "tap Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                    mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first")),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap Davido"))
        checkEq("draw failure never verifies the tap", outcome.status, PlanStatus.FAILED)
        check("draw failure dispatched the tap (unverified, not unrun)", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET } >= 1)
        checkEq("draw failure failed step is the tap", outcome.failedStep?.kind, TaskStepKind.TAP_TARGET)
        check("draw failure never claims done", !outcome.summary.startsWith("Done"))
        check("draw failure is named, not target-gone", outcome.summary.contains("could not show the pointer"))
        check("draw failure never says target gone", !outcome.summary.contains("target gone"))
    }

    // ---------- 27. honest pointer-unavailable outcome ----------
    // No renderer anywhere: the plan still completes, and the summary
    // names the pointer gap for every step it hit.
    run {
        val rig = Rig()
        rig.pointer.failAllWith = "the pointer has nowhere to draw — the floating assistant isn't running"
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("pointer-unavailable still completes", outcome.status, PlanStatus.COMPLETED)
        // Universal Console (2026-11-07): pointer-drive steps are
        // the launch narration (step 1) and the search (step 4);
        // the tap step is gone.
        check("pointer-unavailable names the gap", outcome.summary.contains("could not show the pointer for steps 1, 4"))
        check("pointer-unavailable names the reason", outcome.summary.contains("the floating assistant isn't running"))
    }

    // ---------- 28. task record (2026-11-07): the exact Nov 6
    // failure, replayed end to end. "open YouTube Music and play
    // music", DIRECT_USER blanket cover (no card in production —
    // the fake cover stands in for the grant), executor receives
    // app YT Music with no re-question at any step. ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("open YouTube Music and play music"))
        checkEq("record nov6 completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("record nov6 cover requested once", rig.cover.requestCount, 1)
        checkEq("record nov6 cover saw DIRECT_USER", rig.cover.lastOrigin, RequestOrigin.DIRECT_USER)
        checkEq("record nov6 cover saw YT Music", rig.cover.lastPlan?.record?.app, "YouTube Music")
        checkEq(
            "record nov6 cover saw YT Music package",
            rig.cover.lastPlan?.record?.appPackage, YTM_PKG,
        )
        check(
            "record nov6 approval audited with the app",
            rig.audit.events.any {
                it.second == "record-approved" &&
                    it.third.contains("app=YouTube Music") &&
                    it.third.contains("APPROVED")
            },
        )
        checkEq("record nov6 search ran in YT Music", rig.dispatcher.searchApps, listOf("YouTube Music"))
        check("record nov6 never re-asks", !outcome.summary.contains("Which app should I play"))
        check("record nov6 summary says done", outcome.summary.startsWith("Done"))
    }

    // ---------- 29. task record: replan carries the record. The
    // search fails once (target moved), the replan rebuilds the tail,
    // and the run still completes with the recorded app — the replan
    // audit names it. ----------
    run {
        val rig = Rig()
        rig.dispatcher.failSearchOnceWith = "target moved"
        val outcome = rig.executor().execute(req("open YouTube Music and play a song by Davido"))
        checkEq("record replan completes", outcome.status, PlanStatus.COMPLETED)
        check(
            "record replan audit names the app",
            rig.audit.events.any {
                it.second == "replan" && it.third.contains("record app=YouTube Music")
            },
        )
        check("record replan searched YT Music", rig.dispatcher.searchApps.all { it == "YouTube Music" })
        check("record replan searched at least once", rig.dispatcher.searchApps.isNotEmpty())
    }

    // ---------- 30. task record: MODEL bare tail with a DIRECT_USER
    // record. The model guessed "play a song by Davido" with no app;
    // the record's app fills the slot (no question), but the plan
    // keeps MODEL origin and the cover is still consulted once — a
    // model guess never inherits blanket approval. ----------
    run {
        val rig = Rig()
        val record = TaskRecord(
            instruction = "open YouTube Music and play music",
            app = "YouTube Music",
            appPackage = YTM_PKG,
            query = "music",
            origin = RequestOrigin.DIRECT_USER,
        )
        val request = PlanRequest(
            goal = "play a song by Davido",
            origin = RequestOrigin.MODEL,
            transparency = true,
            taskRecord = record,
        )
        val outcome = rig.executor().execute(request)
        checkEq("record model bare completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("record model cover consulted once", rig.cover.requestCount, 1)
        checkEq("record model cover saw MODEL", rig.cover.lastOrigin, RequestOrigin.MODEL)
        checkEq("record model search ran in YT Music", rig.dispatcher.searchApps, listOf("YouTube Music"))
        check("record model never re-asks", !outcome.summary.contains("Which app should I play"))
    }

    // ---------- 31. task record: genuinely missing app still asks —
    // once, and the run reports the question honestly. ----------
    run {
        val rig = Rig()
        val outcome = rig.executor().execute(req("play a song by Davido"))
        checkEq("record missing app fails honestly", outcome.status, PlanStatus.FAILED)
        check(
            "record missing app asks the one question",
            outcome.summary.contains("Which app should I play that in"),
        )
        checkEq("record missing app never dispatches", rig.dispatcher.calls.size, 0)
        checkEq("record missing app never covers", rig.cover.requestCount, 0)
    }

    // ---- Universal Console (2026-11-07): verifyStep for the new
    // kinds, and goal-aware SEARCH_IN_APP verification ----
    run {
        val executor = Rig().executor()
        val showResults = PlanStep(
            0, TaskStepKind.SEARCH_IN_APP, "Search for Davido",
            mapOf(
                PlanParams.APP to "YouTube Music", PlanParams.QUERY to "Davido",
                PlanParams.PACKAGE to YTM_PKG,
                PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_SHOW_RESULTS,
            ),
        )
        check("verify show-results passes on real results", executor.verifyStep(showResults, ytmHome(), ytmResults()))
        check("verify show-results fails on the home screen", !executor.verifyStep(showResults, ytmHome(), ytmHome()))
        check("verify show-results fails on no-results", !executor.verifyStep(showResults, ytmHome(), ytmResults(withSongs = false)))
        val openFirst = PlanStep(
            0, TaskStepKind.SEARCH_IN_APP, "Search for Davido",
            mapOf(
                PlanParams.APP to "YouTube Music", PlanParams.QUERY to "Davido",
                PlanParams.PACKAGE to YTM_PKG,
                PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_OPEN_FIRST,
            ),
        )
        check("verify open-first passes on the player screen", executor.verifyStep(openFirst, ytmHome(), ytmPlaying()))
        check("verify open-first fails when nothing changed", !executor.verifyStep(openFirst, ytmHome(), ytmHome()))
        check(
            "verify open-first fails in the wrong app",
            !executor.verifyStep(openFirst, ytmHome(), ScreenModel(OTHER_PKG, listOf(el("x", "Now playing: Davido")))),
        )
        val longPress = PlanStep(0, TaskStepKind.LONG_PRESS, "Long-press", mapOf(PlanParams.TARGET to "Davido"))
        check("verify long-press passes on a changed screen", executor.verifyStep(longPress, ytmHome(), ytmResults()))
        check("verify long-press fails on an unchanged screen", !executor.verifyStep(longPress, ytmHome(), ytmHome()))
        val swipe = PlanStep(0, TaskStepKind.SWIPE, "Swipe", mapOf(PlanParams.DIRECTION to "left"))
        check("verify swipe passes on a changed screen", executor.verifyStep(swipe, ytmHome(), ytmResults()))
        check("verify swipe fails on an unchanged screen", !executor.verifyStep(swipe, ytmHome(), ytmHome()))
        val menu = PlanStep(0, TaskStepKind.OPEN_MENU, "Open the menu")
        check("verify open-menu passes on a changed screen", executor.verifyStep(menu, ytmHome(), ytmResults()))
        check("verify open-menu fails on an unchanged screen", !executor.verifyStep(menu, ytmHome(), ytmHome()))
        val focusStep = PlanStep(0, TaskStepKind.FOCUS_FIELD, "Focus", mapOf(PlanParams.TARGET to "search"))
        val focusedScreen = ScreenModel(
            YTM_PKG,
            listOf(
                ScreenElement(
                    id = "search-field", text = null, contentDescription = null,
                    className = "android.widget.EditText", bounds = Rect(10, 10, 500, 90),
                    role = ElementRole.SEARCH_FIELD, enabled = true, isPassword = false,
                    hintText = "Search YT Music", focused = true,
                ),
            ),
        )
        check("verify focus passes when a field holds focus", executor.verifyStep(focusStep, ytmHome(), focusedScreen))
        check("verify focus fails when no field holds focus", !executor.verifyStep(focusStep, ytmHome(), ytmHome()))
        check("verify focus fails on a null screen", !executor.verifyStep(focusStep, ytmHome(), null))

        // ---- MASTER Wave 3 (F15b): PLAY_PAUSE verifies the player
        // state flipped (playing ↔ paused) — a tap alone is never
        // success. David had to pause the Facebook video himself. ----
        val pausedScreen = ScreenModel(
            YTM_PKG,
            listOf(
                el("now-playing", "Davido — Fall", ElementRole.TEXT, 10, 10, 500, 90),
                el("play", "Play", ElementRole.BUTTON, 10, 120, 120, 170),
            ),
        )
        val pauseStep = PlanStep(0, TaskStepKind.PLAY_PAUSE, "Pause the video", mapOf(PlanParams.PLAY_STATE to "pause"))
        check("verify pause passes when playback stopped", executor.verifyStep(pauseStep, ytmPlaying(), pausedScreen))
        check("verify pause fails when still playing", !executor.verifyStep(pauseStep, ytmPlaying(), ytmPlaying()))
        check("verify pause fails when nothing was playing", !executor.verifyStep(pauseStep, pausedScreen, pausedScreen))
        val playStep = PlanStep(0, TaskStepKind.PLAY_PAUSE, "Play the video", mapOf(PlanParams.PLAY_STATE to "play"))
        check("verify play passes when playback started", executor.verifyStep(playStep, pausedScreen, ytmPlaying()))
        check("verify play fails when still paused", !executor.verifyStep(playStep, pausedScreen, pausedScreen))
        val toggleStep = PlanStep(0, TaskStepKind.PLAY_PAUSE, "Play or pause", emptyMap())
        check("verify toggle passes on a flip", executor.verifyStep(toggleStep, ytmPlaying(), pausedScreen))
        check("verify toggle fails with no flip", !executor.verifyStep(toggleStep, ytmPlaying(), ytmPlaying()))

        // ---- MASTER Wave 3 (F9): X resolves to the close button;
        // "search" never resolves to the voice-search mic. ----
        val closeBtn = el("x-btn", null, ElementRole.BUTTON).copy(contentDescription = "Close")
        val mic = el("mic", null, ElementRole.BUTTON).copy(contentDescription = "Voice search")
        val targetScreen = ScreenModel(YTM_PKG, listOf(closeBtn, mic, el("row-fall", "Davido — Fall")))
        check("x resolves to the close button", findTargetElements(targetScreen, "x").map { it.id } == listOf("x-btn"))
        check("close resolves to the close button", findTargetElements(targetScreen, "close").map { it.id } == listOf("x-btn"))
        check("search never resolves to the mic", findTargetElements(targetScreen, "search").none { it.id == "mic" })

        // ---- MASTER Wave 3 (F8/F14a): a suggestions screen is NOT
        // results, even with two query-echoing rows. ----
        val suggestionsScreen = ScreenModel(
            YTM_PKG,
            listOf(
                el("field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music").copy(focused = true),
                el("list", null, ElementRole.LIST, 10, 120, 500, 600),
                el("s1", "Davido songs", ElementRole.TEXT, 10, 130, 500, 180),
                el("s2", "Davido live", ElementRole.TEXT, 10, 190, 500, 240),
            ),
            keyboardVisible = true,
        )
        check("suggestions screen is not results", !searchResultsShown(suggestionsScreen, "Davido", ytmHome()))
        check("real results are results", searchResultsShown(ytmResults(), "Davido", ytmHome()))
    }

    // ---------- Screen confirmation + common sense (2026-11-10,
    // David's rules) ----------
    run {
        val executor = Rig().executor()
        val openFirst = PlanStep(
            0, TaskStepKind.SEARCH_IN_APP, "Search for Davido",
            mapOf(
                PlanParams.APP to "YouTube Music", PlanParams.QUERY to "Davido",
                PlanParams.PACKAGE to YTM_PKG,
                PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_OPEN_FIRST,
            ),
        )
        // A voice-search landing is not an opened result (Nov 8
        // video: the mic tap landed on the permission screen and
        // counted as success).
        val voiceLanding = ScreenModel(
            YTM_PKG,
            listOf(
                el("prompt", "Search with your voice", ElementRole.TEXT, 10, 10, 500, 90),
                el("mic-hint", "Tap the microphone to speak", ElementRole.TEXT, 10, 120, 500, 170),
            ),
        )
        check("verify open-first fails on a voice-search landing", !executor.verifyStep(openFirst, ytmHome(), voiceLanding))
        check(
            "verify open-first (typed) fails on a voice-search landing",
            executor.verifyStepTyped(openFirst, ytmHome(), voiceLanding).verdict == VerificationResult.Verdict.FAIL,
        )

        // TYPE_TEXT on a field that exposes no text is unverified —
        // never counted as done (Boolean and typed agree).
        val unreadableField = ScreenModel(
            YTM_PKG,
            listOf(
                ScreenElement(
                    id = "field", text = null, contentDescription = null,
                    className = "android.widget.EditText", bounds = Rect(10, 10, 500, 90),
                    role = ElementRole.INPUT, enabled = true, isPassword = false,
                    hintText = "Search", focused = true,
                ),
            ),
        )
        val typeStep = PlanStep(0, TaskStepKind.TYPE_TEXT, "Type Davido", mapOf(PlanParams.TEXT to "Davido"))
        check("verify type fails on an unreadable field", !executor.verifyStep(typeStep, ytmHome(), unreadableField))
        check(
            "verify type (typed) fails on an unreadable field",
            executor.verifyStepTyped(typeStep, ytmHome(), unreadableField).verdict == VerificationResult.Verdict.FAIL,
        )

        // GUIDE_TO counts only when the target is on screen and the
        // pointer drew (confirm AND perform).
        val guideStep = PlanStep(0, TaskStepKind.GUIDE_TO, "Guide to Davido", mapOf(PlanParams.GUIDE_TARGET to "Davido"))
        check("verify guide passes when target on screen and pointer drew", executor.verifyStep(guideStep, ytmHome(), ytmResults()))
        check("verify guide fails when target missing", !executor.verifyStep(guideStep, ytmHome(), ytmHome()))
        check(
            "verify guide (typed) passes when target on screen",
            executor.verifyStepTyped(guideStep, ytmHome(), ytmResults()).verdict == VerificationResult.Verdict.PASS,
        )

        // Common sense (2026-11-10): perspective and close matches
        // resolve through the same resolver the plan path uses.
        val storyScreen = ScreenModel(
            YTM_PKG,
            listOf(
                el("your-story", "Your story", ElementRole.BUTTON, 10, 10, 200, 90),
                el("your-profile", "Your profile", ElementRole.BUTTON, 10, 120, 200, 200),
            ),
        )
        check("my story resolves to Your story", findTargetElements(storyScreen, "my story").map { it.id } == listOf("your-story"))
        check("my profile resolves to Your profile", findTargetElements(storyScreen, "my profile").map { it.id } == listOf("your-profile"))
        val myStoryScreen = ScreenModel(YTM_PKG, listOf(el("my-story", "My story", ElementRole.BUTTON, 10, 10, 200, 90)))
        check("your story resolves to My story (reverse)", findTargetElements(myStoryScreen, "your story").map { it.id } == listOf("my-story"))
        val versusScreen = ScreenModel(YTM_PKG, listOf(el("row-vs", "Nigeria vs Japan", ElementRole.TEXT, 10, 10, 500, 90)))
        check("close wording resolves (versus to vs)", findTargetElements(versusScreen, "Nigeria versus Japan").map { it.id } == listOf("row-vs"))
        val ambiguousScreen = ScreenModel(
            YTM_PKG,
            listOf(
                el("row-a", "Nigeria vs Japan", ElementRole.TEXT, 10, 10, 500, 90),
                el("row-b", "Nigeria vs Japan", ElementRole.TEXT, 10, 120, 500, 200),
            ),
        )
        check(
            "ambiguous close matches are both returned (callers refuse)",
            findTargetElements(ambiguousScreen, "Nigeria versus Japan").map { it.id } == listOf("row-a", "row-b"),
        )
    }

    // ---------- Screen navigation (2026-11-10): a TAP_TARGET below
    // the fold is found by scrolling, not reported as gone. The
    // fake world reveals the results list on the first scroll. ----
    run {
        val scrollableHome = ScreenModel(
            YTM_PKG,
            listOf(
                el("search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
                el("list", null, ElementRole.LIST, 10, 120, 500, 600),
                el("row-top", "Home", ElementRole.TEXT, 10, 130, 500, 180),
            ),
        )
        val rig = Rig(world = World(scrollableHome))
        val plan = TaskPlan(
            id = "plan-scroll-hunt",
            goal = "tap Davido",
            steps = listOf(
                tapStep(0, "Davido"),
                PlanStep(1, TaskStepKind.VERIFY_OUTCOME, "Check that Davido is playing",
                    mapOf(PlanParams.EXPECTED to "Davido"), critical = false),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap Davido"))
        checkEq("scroll hunt completes", outcome.status, PlanStatus.COMPLETED)
        check("scroll hunt scrolled before tapping", rig.dispatcher.calls.indexOf(TaskStepKind.SCROLL) < rig.dispatcher.calls.indexOf(TaskStepKind.TAP_TARGET))
        check("scroll hunt audited the find", rig.audit.events.any { it.second == "scroll-hunt-found" })
    }

    // ---------- GUIDE_TO end to end (2026-11-10): a guide step whose
    // target is missing, or whose pointer could not draw, fails
    // honestly instead of counting as done. ----
    run {
        // Target missing from the screen.
        val rig = Rig(world = World(ytmHome()))
        val plan = TaskPlan(
            id = "plan-guide-missing",
            goal = "guide to Davido",
            steps = listOf(
                PlanStep(0, TaskStepKind.GUIDE_TO, "Guide to Davido", mapOf(PlanParams.GUIDE_TARGET to "Davido")),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("guide to Davido"))
        checkEq("guide to a missing target fails", outcome.status, PlanStatus.FAILED)
        checkEq("guide to a missing target names the step", outcome.failedStep?.kind, TaskStepKind.GUIDE_TO)
        // Target present but the pointer could not draw.
        val rig2 = Rig(world = World(ytmResults()))
        rig2.pointer.failAllWith = "the pointer could not be shown"
        val outcome2 = rig2.executor().executePlan(plan, req("guide to Davido"))
        checkEq("guide with a failed pointer draw fails", outcome2.status, PlanStatus.FAILED)
        checkEq("guide with a failed pointer draw names the step", outcome2.failedStep?.kind, TaskStepKind.GUIDE_TO)
        check("guide with a failed pointer draw names it in the summary", outcome2.summary.contains("could not show the pointer"))
    }

    // ---------- POINTER EVERYWHERE (2026-11-09, David's direction) ----------
    // The pointer is LOOM's hand: it must travel to wherever a control
    // is — the Back button, the keyboard's Enter key, the swipe start
    // — so LOOM operates through it. Every check below runs one step
    // through the real executor + fake pointer and asserts the move.
    run {
        // Phone-shaped screen: 1080x2400, the Redmi 14C class size.
        // Swipe start points follow the service's swipeHandoff
        // formula exactly (up: centre-x, bottom-25%; down: centre-x,
        // top+25%; left: right-25%, centre-y; right: left+25%,
        // centre-y), marked with a 64x64 ring (half-size 32).
        fun phoneScreen(
            backButton: MeasuredControl? = null,
            keyboardActionKey: MeasuredControl? = null,
            keyboardVisible: Boolean = false,
            extra: List<ScreenElement> = emptyList(),
        ) = ScreenModel(
            packageName = YTM_PKG,
            elements = listOf(
                el("phone-search-field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music"),
                el("phone-home-row", "Phone home", ElementRole.TEXT, 10, 120, 500, 170),
            ) + extra,
            keyboardVisible = keyboardVisible,
            screenBounds = Rect(0, 0, 1080, 2400),
            backButton = backButton,
            keyboardActionKey = keyboardActionKey,
        )

        fun oneStepPlan(id: String, goal: String, step: PlanStep) = TaskPlan(
            id = id, goal = goal, steps = listOf(step),
            origin = RequestOrigin.DIRECT_USER,
        )

        fun markerAt(cx: Int, cy: Int) = TargetBounds(cx - 32, cy - 32, cx + 32, cy + 32)

        // ---- GO_BACK with a visible Back button (3-button nav):
        // the pointer drives to the button's measured bounds. ----
        run {
            val back = MeasuredControl(Rect(330, 2250, 420, 2340), "Back", "android.widget.ImageButton")
            val rig = Rig(world = World(phoneScreen(backButton = back)))
            val outcome = rig.executor().executePlan(
                oneStepPlan("plan-back-btn", "go back", PlanStep(0, TaskStepKind.GO_BACK, "Go back")),
                req("go back"),
            )
            checkEq("back button completes", outcome.status, PlanStatus.COMPLETED)
            checkEq("back button drives to the button", rig.pointer.moves, listOf(TargetBounds(330, 2250, 420, 2340)))
            check("back button pill names Back", rig.pointer.shownSteps.any { it.description.contains("Back", ignoreCase = true) })
        }

        // ---- GO_BACK with gesture nav (no Back node): the pointer
        // drives to the left edge where the back swipe starts. ----
        run {
            val rig = Rig(world = World(phoneScreen()))
            val outcome = rig.executor().executePlan(
                oneStepPlan("plan-back-edge", "go back", PlanStep(0, TaskStepKind.GO_BACK, "Go back")),
                req("go back"),
            )
            checkEq("back edge completes", outcome.status, PlanStatus.COMPLETED)
            checkEq("back edge drives to the left edge", rig.pointer.moves, listOf(markerAt(32, 1200)))
            check("back edge pill names the left edge", rig.pointer.shownSteps.any { it.description.contains("left edge", ignoreCase = true) })
        }

        // ---- SWIPE drives to the start point, per direction, and
        // the pill names the swipe. ----
        for ((direction, start) in listOf(
            "up" to (540 to 1800),
            "down" to (540 to 600),
            "left" to (810 to 1200),
            "right" to (270 to 1200),
        )) {
            run {
                val rig = Rig(world = World(phoneScreen()))
                val outcome = rig.executor().executePlan(
                    oneStepPlan(
                        "plan-swipe-$direction", "swipe $direction",
                        PlanStep(0, TaskStepKind.SWIPE, "Swipe $direction", mapOf(PlanParams.DIRECTION to direction)),
                    ),
                    req("swipe $direction"),
                )
                checkEq("swipe $direction completes", outcome.status, PlanStatus.COMPLETED)
                checkEq("swipe $direction drives to its start point", rig.pointer.moves, listOf(markerAt(start.first, start.second)))
                check("swipe $direction pill names the direction", rig.pointer.shownSteps.any { it.description.contains(direction, ignoreCase = true) })
            }
        }

        // ---- SWIPE with no screen bounds: narrate only, never a
        // fake start point (no-fake-target invariant). ----
        run {
            val rig = Rig(world = World(ytmHome()))
            rig.executor().executePlan(
                oneStepPlan(
                    "plan-swipe-nobounds", "swipe up",
                    PlanStep(0, TaskStepKind.SWIPE, "Swipe up", mapOf(PlanParams.DIRECTION to "up")),
                ),
                req("swipe up"),
            )
            checkEq("swipe without bounds moves nowhere", rig.pointer.moves, emptyList<TargetBounds>())
            check("swipe without bounds still narrates", rig.pointer.shownSteps.any { it.kind == TaskStepKind.SWIPE })
        }

        // ---- SUBMIT with the keyboard up: the pointer drives to
        // the keyboard's action key before the press. ----
        run {
            val key = MeasuredControl(Rect(830, 1950, 1060, 2100), "Search", "android.widget.Button")
            val field = el("field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music").copy(focused = true)
            val rig = Rig(world = World(phoneScreen(keyboardActionKey = key, keyboardVisible = true, extra = listOf(field))))
            val outcome = rig.executor().executePlan(
                oneStepPlan("plan-submit-key", "submit", PlanStep(0, TaskStepKind.SUBMIT, "Submit")),
                req("submit"),
            )
            checkEq("submit key completes", outcome.status, PlanStatus.COMPLETED)
            checkEq("submit drives to the keyboard key", rig.pointer.moves, listOf(TargetBounds(830, 1950, 1060, 2100)))
            check("submit pill names the key", rig.pointer.shownSteps.any { it.description.contains("Search", ignoreCase = true) })
        }

        // ---- SUBMIT with the keyboard up but no key exposed: the
        // pointer parks on the on-screen search button instead. ----
        run {
            val button = el("search-btn", "Search", ElementRole.BUTTON, 600, 1950, 800, 2100)
            val field = el("field", null, ElementRole.SEARCH_FIELD, 10, 10, 500, 90, hint = "Search YT Music").copy(focused = true)
            val rig = Rig(world = World(phoneScreen(keyboardVisible = true, extra = listOf(button, field))))
            val outcome = rig.executor().executePlan(
                oneStepPlan("plan-submit-btn", "submit", PlanStep(0, TaskStepKind.SUBMIT, "Submit")),
                req("submit"),
            )
            checkEq("submit button completes", outcome.status, PlanStatus.COMPLETED)
            checkEq("submit without key parks on the search button", rig.pointer.moves, listOf(TargetBounds(600, 1950, 800, 2100)))
        }

        // ---- SUBMIT with no key and no button: the pointer parks
        // on the focused field (where the submit acts). ----
        run {
            val field = el("field", null, ElementRole.SEARCH_FIELD, 10, 200, 500, 290, hint = "Search YT Music").copy(focused = true)
            val rig = Rig(world = World(phoneScreen(keyboardVisible = true, extra = listOf(field))))
            val outcome = rig.executor().executePlan(
                oneStepPlan("plan-submit-field", "submit", PlanStep(0, TaskStepKind.SUBMIT, "Submit")),
                req("submit"),
            )
            checkEq("submit field completes", outcome.status, PlanStatus.COMPLETED)
            checkEq("submit without key or button parks on the field", rig.pointer.moves, listOf(TargetBounds(10, 200, 500, 290)))
        }

        // ---- SUBMIT ignores a STALE key: keyboard hidden means the
        // key bounds in the model cannot be live, so the pointer
        // must not park on them. ----
        run {
            val key = MeasuredControl(Rect(830, 1950, 1060, 2100), "Search", "android.widget.Button")
            val button = el("search-btn", "Search", ElementRole.BUTTON, 600, 1950, 800, 2100)
            val rig = Rig(world = World(phoneScreen(keyboardActionKey = key, keyboardVisible = false, extra = listOf(button))))
            rig.executor().executePlan(
                oneStepPlan("plan-submit-stale", "submit", PlanStep(0, TaskStepKind.SUBMIT, "Submit")),
                req("submit"),
            )
            checkEq("submit never parks on a stale key", rig.pointer.moves, listOf(TargetBounds(600, 1950, 800, 2100)))
        }

        // ---- LAUNCH_APP audit: the app icon may be visible on the
        // home screen, but the launch is by intent, not by tapping
        // the icon — driving there would misrepresent the mechanism,
        // so the pointer narrates only. ----
        run {
            val icon = el("ytm-icon", "YouTube Music", ElementRole.ICON, 100, 500, 300, 700)
            val rig = Rig(world = World(phoneScreen(extra = listOf(icon))))
            val outcome = rig.executor().executePlan(
                oneStepPlan(
                    "plan-launch", "open YouTube Music",
                    PlanStep(0, TaskStepKind.LAUNCH_APP, "Open YouTube Music", mapOf(PlanParams.PACKAGE to YTM_PKG)),
                ),
                req("open YouTube Music"),
            )
            checkEq("launch completes", outcome.status, PlanStatus.COMPLETED)
            checkEq("launch never drives to the icon", rig.pointer.moves, emptyList<TargetBounds>())
            check("launch still narrates", rig.pointer.shownSteps.any { it.kind == TaskStepKind.LAUNCH_APP })
        }

        // ---- Audit: every step kind has a decided pointer behaviour.
        // Kinds the pointer drives for (element, measured control, or
        // real gesture point); everything else is read-only, intent
        // -based, or resolved fresh inside its own executor — never
        // a visible control the pointer should have shown but didn't.
        run {
            val driven = TaskPlanExecutor.POINTER_DRIVE_KINDS
            val notDriven = TaskStepKind.values().toSet() - driven
            checkEq(
                "undriven kinds are exactly the decided set",
                notDriven,
                setOf(
                    TaskStepKind.WAIT_FOR_APP, TaskStepKind.OBSERVE_SCREEN,
                    TaskStepKind.VERIFY_OUTCOME, TaskStepKind.MESSAGE_SEND,
                    TaskStepKind.CALL_PLACE, TaskStepKind.POINTER_MOVE,
                    TaskStepKind.REPORT,
                ),
            )
            check("GO_BACK is a driven kind", TaskStepKind.GO_BACK in driven)
            check("SWIPE is a driven kind", TaskStepKind.SWIPE in driven)
            check("SUBMIT is a driven kind", TaskStepKind.SUBMIT in driven)
            check("MESSAGE_SEND stays undriven (send button resolves inside MessageExecutor)", TaskStepKind.MESSAGE_SEND !in driven)
            check("CALL_PLACE stays undriven (ACTION_DIAL intent, no tap)", TaskStepKind.CALL_PLACE !in driven)
        }
    }

    // ---------- KNOWLEDGE BASE + BUILT-IN RECOVERY (2026-11-09) ----------
    run {
        // R1: the search for a KNOWN screen runs with the winning
        // mechanism first, and the run records what it observes.
        val knowledge = ScreenKnowledgeStore()
        knowledge.recordObservation(ytmHome())
        knowledge.recordSubmitWin(ytmHome(), SubmitMechanism.SEARCH_BUTTON, succeeded = true)
        val rig = Rig(knowledge = knowledge)
        val outcome = rig.executor().execute(req("Open YouTube Music and play a song by Davido"))
        checkEq("kb: plan with a known screen completes", outcome.status, PlanStatus.COMPLETED)
        check(
            "kb: search ran with the known winning mechanism first",
            rig.dispatcher.searchOrders.any { it.startsWith(SubmitMechanism.SEARCH_BUTTON.name) },
        )
        check("kb: home screen recorded", knowledge.knowledgeForScreen(ytmHome()) != null)
        check("kb: player screen recorded", knowledge.knowledgeForScreen(ytmPlaying()) != null)
        check(
            "kb: submit win recorded for the home screen",
            knowledge.knowledgeForScreen(ytmHome())?.winningSubmitMechanism != null,
        )
    }
    run {
        // B1 end to end: a dialog (the wrong page) -> the search
        // fails "unexpected screen" -> the replan goes back first
        // (pointer to the left edge, no Back button on this screen)
        // -> the search re-issues and the run completes.
        val rig = Rig(world = World(dialogScreen()))
        val plan = TaskPlan(
            id = "plan-wrong-page",
            goal = "play Davido",
            steps = listOf(
                searchStep(0),
                PlanStep(1, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
                verifyDavidoStep(2),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("play Davido"))
        checkEq("wrong page: recovery completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("wrong page: go-back ran once", rig.dispatcher.calls.count { it == TaskStepKind.GO_BACK }, 1)
        check(
            "wrong page: go-back is a recovery step",
            rig.dispatcher.dispatchedSteps.any {
                it.kind == TaskStepKind.GO_BACK &&
                    it.params[PlanParams.RECOVERY] == PlanParams.RECOVERY_GO_BACK
            },
        )
        check(
            "wrong page: pointer drove to the left edge before going back",
            rig.pointer.moves.any { it == TargetBounds(0, 1168, 64, 1232) },
        )
        checkEq(
            "wrong page: search ran once, after the recovery",
            rig.dispatcher.calls.count { it == TaskStepKind.SEARCH_IN_APP },
            1,
        )
        check("wrong page: replan audited", rig.audit.events.any { it.second == "replan" })
    }
    run {
        // B1 skipped end to end: the knowledge base records that a
        // back recovery already failed on this screen, so the replan
        // does NOT repeat it — the run stops honestly instead.
        val knowledge = ScreenKnowledgeStore()
        knowledge.recordObservation(dialogScreen())
        knowledge.recordBackRecovery(dialogScreen(), succeeded = false)
        val rig = Rig(world = World(dialogScreen()), knowledge = knowledge)
        val plan = TaskPlan(
            id = "plan-wrong-page-skip",
            goal = "play Davido",
            steps = listOf(
                searchStep(0),
                PlanStep(1, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
                verifyDavidoStep(2),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("play Davido"))
        checkEq("back-failed: no go-back runs", rig.dispatcher.calls.count { it == TaskStepKind.GO_BACK }, 0)
        checkEq("back-failed: run stops honestly", outcome.status, PlanStatus.FAILED)
    }
    run {
        // B2 end to end: the search's submit fails twice (dispatch
        // level: the search never ran) -> the first failure rotates
        // the mechanism, the second replans -> the clear step taps
        // the X (pointer on it) -> the search re-issues and the run
        // completes.
        val rig = Rig(world = World(searchWithClear()))
        rig.dispatcher.failSearchWith = "submit not executed — the search never ran"
        rig.dispatcher.failSearchTimes = 2
        val plan = TaskPlan(
            id = "plan-clear-retry",
            goal = "play Davido",
            steps = listOf(
                searchStep(0),
                PlanStep(1, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
                verifyDavidoStep(2),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("play Davido"))
        checkEq("clear retry: recovery completes", outcome.status, PlanStatus.COMPLETED)
        checkEq(
            "clear retry: search ran three times (2 failed + re-issue)",
            rig.dispatcher.calls.count { it == TaskStepKind.SEARCH_IN_APP },
            3,
        )
        checkEq("clear retry: clear tap ran once", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 1)
        val clearTap = rig.dispatcher.dispatchedSteps.first { it.kind == TaskStepKind.TAP_TARGET }
        checkEq("clear retry: tap is a recovery step", clearTap.params[PlanParams.RECOVERY], PlanParams.RECOVERY_CLEAR_RETRY)
        checkEq("clear retry: tap targets clear", clearTap.params[PlanParams.TARGET], "clear")
        check(
            "clear retry: pointer drove to the clear control",
            rig.pointer.moves.any { it == TargetBounds(900, 10, 1080, 90) },
        )
        check("clear retry: replan audited", rig.audit.events.any { it.second == "replan" })
    }
    run {
        // POINTER EVERYWHERE: a bottom navigation control is driven
        // to, not narrated — the pointer travels to the bottom of
        // the screen where the control actually is.
        val rig = Rig(world = World(bottomNavScreen()))
        val plan = TaskPlan(
            id = "plan-bottom-nav",
            goal = "tap Home",
            steps = listOf(
                tapStep(0, "Home"),
                verifyDavidoStep(1),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("tap Home"))
        checkEq("bottom nav: tap completes", outcome.status, PlanStatus.COMPLETED)
        check(
            "bottom nav: pointer drove to the bottom control",
            rig.pointer.moves.any { it == TargetBounds(100, 2200, 300, 2400) },
        )
        check(
            "bottom nav: the move is in the bottom region",
            rig.pointer.moves.any { it.top >= 2000 },
        )
    }

    // ---------- D-01: a tap whose app is not on screen never dispatches ----------
    // The Nov 11 screenshots: the plan tapped "my story" while LOOM's
    // own chat was in front — the chat bubbles echo the words, so the
    // pointer parked on the echo. The step carries the Facebook
    // package; the foreground is LOOM's chat; the guard fails the step
    // as "target gone" BEFORE any gesture is dispatched.
    run {
        val loomChat = ScreenModel(
            packageName = "com.loom.assistant",
            elements = listOf(el("bubble", "tap my story")),
        )
        val rig = Rig(world = World(loomChat))
        val plan = TaskPlan(
            id = "plan-wrong-app-tap",
            goal = "open my story",
            steps = listOf(
                PlanStep(
                    0, TaskStepKind.TAP_TARGET, "Tap my story",
                    mapOf(PlanParams.TARGET to "my story", PlanParams.PACKAGE to "com.facebook.katana"),
                ),
                PlanStep(
                    1, TaskStepKind.VERIFY_OUTCOME, "Check that the story opened",
                    mapOf(PlanParams.EXPECTED to "story open"), critical = false,
                ),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("open my story"))
        checkEq("wrong-app tap aborts", outcome.status, PlanStatus.FAILED)
        checkEq("wrong-app failed step is the tap", outcome.failedStep?.kind, TaskStepKind.TAP_TARGET)
        checkEq("wrong-app tap never dispatched", rig.dispatcher.calls.count { it == TaskStepKind.TAP_TARGET }, 0)
    }

    // ---------- D-07: open-first must land on a player, not the results list ----------
    run {
        val rig = Rig()
        rig.dispatcher.openFirstStaysOnResults = true
        val plan = TaskPlan(
            id = "plan-open-first-stuck",
            goal = "search for Davido in YouTube Music and open the first result",
            steps = listOf(
                PlanStep(
                    0, TaskStepKind.SEARCH_IN_APP,
                    "Search for Davido in YouTube Music and open the first result",
                    mapOf(
                        PlanParams.QUERY to "Davido",
                        PlanParams.APP to "YouTube Music",
                        PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_OPEN_FIRST,
                    ),
                ),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("search for Davido in YouTube Music and open the first result"))
        checkEq("open-first stuck on results fails", outcome.status, PlanStatus.FAILED)
        checkEq("open-first stuck failed step is the search", outcome.failedStep?.kind, TaskStepKind.SEARCH_IN_APP)
    }
    run {
        check("openFirstLanded: player up passes", openFirstLanded(ytmPlaying(), "Davido", ytmHome()))
        check("openFirstLanded: results with no player fails", !openFirstLanded(ytmResults(), "Davido", ytmHome()))
        check("openFirstLanded: navigated away from results passes", openFirstLanded(ytmHome(), "Davido", ytmHome()))
    }

    // ---------- NAV-SCREEN (2026-11-11): navigate the Facebook bottom nav ----------
    // Device-failure fix (2026-11-13, F2): the navigate steps name
    // no package — the world starts ON the Facebook feed, so these
    // are current-screen navigations (same shape as the N8 test
    // below); a launch prefix would move the world off the feed
    // the tests exist to navigate.
    run {
        val rig = Rig(world = World(facebookFeed()))
        val plan = TaskPlan(
            id = "plan-nav-reels",
            goal = "open Reels on Facebook",
            steps = listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
                PlanStep(
                    1, TaskStepKind.NAVIGATE_TAB, "Go to Reels",
                    mapOf(PlanParams.TARGET to "Reels"),
                ),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("open Reels on Facebook"))
        checkEq("nav reels completes", outcome.status, PlanStatus.COMPLETED)
        checkEq("nav reels verified == total", outcome.verifiedSteps, outcome.totalSteps)
        check(
            "nav reels dispatched the navigate step",
            rig.dispatcher.calls.count { it == TaskStepKind.NAVIGATE_TAB } == 1,
        )
        check("nav reels never scrolled the feed", TaskStepKind.SCROLL !in rig.dispatcher.calls)
        check("nav reels world landed on Reels", rig.world.screen.elements.any { it.id == "tab2" && it.selected })
    }
    run {
        // Wrong-tab negative: the tap lands on Watch, not Reels —
        // the navigation verifier must fail the step, and the run
        // must never report success.
        val rig = Rig(world = World(facebookFeed()))
        rig.dispatcher.navLandsOnWatch = true
        val plan = TaskPlan(
            id = "plan-nav-wrong-tab",
            goal = "open Reels on Facebook",
            steps = listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
                PlanStep(
                    1, TaskStepKind.NAVIGATE_TAB, "Go to Reels",
                    mapOf(PlanParams.TARGET to "Reels"),
                ),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("open Reels on Facebook"))
        checkEq("nav wrong tab fails", outcome.status, PlanStatus.FAILED)
        check("nav wrong tab never claims done", !outcome.summary.startsWith("Done"))
        checkEq("nav wrong tab failed step is the navigate", outcome.failedStep?.kind, TaskStepKind.NAVIGATE_TAB)
        check(
            "nav wrong tab attempted the navigate",
            rig.dispatcher.calls.count { it == TaskStepKind.NAVIGATE_TAB } >= 1,
        )
    }
    run {
        // Chrome skip (N8): no nav bar on screen — the scroll hunt
        // must never run for a navigation target, and no SCROLL step
        // may be dispatched looking for it.
        val rig = Rig(world = World(ytmHome()))
        val plan = TaskPlan(
            id = "plan-nav-no-chrome",
            goal = "open Reels",
            steps = listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
                PlanStep(
                    1, TaskStepKind.NAVIGATE_TAB, "Go to Reels",
                    mapOf(PlanParams.TARGET to "Reels"),
                ),
            ),
            origin = RequestOrigin.DIRECT_USER,
        )
        val outcome = rig.executor().executePlan(plan, req("open Reels"))
        checkEq("nav no-chrome fails honestly", outcome.status, PlanStatus.FAILED)
        check("nav no-chrome never scrolled", TaskStepKind.SCROLL !in rig.dispatcher.calls)
        check(
            "nav no-chrome audited the chrome skip",
            rig.audit.events.any { it.second == "scroll-hunt-skipped-chrome" },
        )
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
