package com.loom.assistant.platform.services

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.messaging.CallOutcome
import com.loom.assistant.execution.messaging.MessageOutcome
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.firstResultRow
import com.loom.assistant.execution.screenawareness.isBareFirstTarget
import com.loom.assistant.execution.search.SearchOutcome
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.CoverResult
import com.loom.assistant.intelligence.planner.PlanAuthCover
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskStepKind

/**
 * Stage 2D (Worker B) — PlanStepDispatcher checks.
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
 *     $R/core/common/src/main/java/com/loom/assistant/core/common/PointerMove.kt \
 *     $R/core/common/src/main/java/com/loom/assistant/core/common/VoiceCommands.kt \
 *     $R/execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     $R/execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenDataPolicy.kt \
 *     $R/execution/interaction/src/main/java/com/loom/assistant/execution/interaction/FinancialActionDetector.kt \
 *     $R/execution/search/src/main/java/com/loom/assistant/execution/search/SearchExecutor.kt \
 *     $R/execution/messaging/src/main/java/com/loom/assistant/execution/messaging/MessageExecutor.kt \
 *     $R/execution/messaging/src/main/java/com/loom/assistant/execution/messaging/CallExecutor.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanContracts.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/TaskPlan.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanRequest.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanValidator.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/TaskPlanner.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanFailureAnalyzer.kt \
 *     $R/execution/tasks/src/main/java/com/loom/assistant/execution/tasks/PlanVerify.kt \
 *     $R/platform/services/src/main/java/com/loom/assistant/platform/services/PlanStepDispatcher.kt \
 *     $R/platform/services/src/test/java/com/loom/assistant/platform/services/PlanStepDispatcherTest.kt \
 *   && $JAVA -cp out:$STDLIB com.loom.assistant.platform.services.PlanStepDispatcherTestKt
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

private const val YTM_PKG = "com.google.android.apps.youtube.music"
private const val SENSITIVE_PKG = "com.kuda.app"

private fun dEl(id: String, text: String?, role: ElementRole = ElementRole.TEXT) =
    ScreenElement(
        id = id,
        text = text,
        contentDescription = null,
        className = "android.widget.TextView",
        bounds = Rect(0, 0, 100, 50),
        role = role,
        enabled = true,
        isPassword = false,
    )

private fun ytmScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        dEl("search-field", null, ElementRole.SEARCH_FIELD),
        dEl("row-fall", "Davido — Fall"),
        dEl("row-if", "Davido — If"),
    ),
)

private fun playingScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        dEl("now-playing", "Now playing: Davido — Fall"),
        dEl("pause", "Pause", ElementRole.BUTTON),
    ),
)

private fun sensitiveScreen() = ScreenModel(
    packageName = SENSITIVE_PKG,
    elements = listOf(dEl("pay-btn", "Pay now", ElementRole.BUTTON)),
)

private val dApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" -> AppResolution.Found(YTM_PKG, "YT Music")
        else -> AppResolution.NotFound
    }
}

private class DObserver(var screen: ScreenModel) : ScreenObserver {
    var calls = 0
    override fun observeNow(): ScreenModel {
        calls++
        return screen
    }
}

private class DCover(var covered: Boolean = false) : PlanAuthCover {
    override fun requestPlanCover(plan: TaskPlan): CoverResult = CoverResult.GRANTED
    override fun checkCover(step: PlanStep): Boolean = covered
}

private class DTokenProvider(var nextToken: String? = "tok-1") : PlanTokenProvider {
    val requests = mutableListOf<PlanTokenRequest>()
    override fun tokenFor(request: PlanTokenRequest): String? {
        requests.add(request)
        return nextToken
    }
}

/**
 * MASTER F7a — a token provider that parks until the test releases
 * it: a card sitting on screen while David says "stop". [entered]
 * fires when the token wait starts; [release] simulates David
 * approving the card AFTER the stop.
 */
private class ParkingTokens : PlanTokenProvider {
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    override fun tokenFor(request: PlanTokenRequest): String? {
        entered.countDown()
        release.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return "tok-approved"
    }
}

private class DGestures : PlanGestures {
    val taps = mutableListOf<ScreenElement>()
    val types = mutableListOf<String>()
    var submits = 0
    val scrolls = mutableListOf<Boolean>()
    var backs = 0
    var refuse: String? = null
    // Universal Console (2026-11-07): the new console sinks.
    val longPresses = mutableListOf<ScreenElement>()
    val swipes = mutableListOf<String>()

    private fun result(): PlanGestureResult =
        refuse?.let { PlanGestureResult.Refused(it) } ?: PlanGestureResult.Accepted

    override fun tap(element: ScreenElement, token: String?): PlanGestureResult {
        taps.add(element)
        return result()
    }

    override fun type(text: String, token: String?): PlanGestureResult {
        types.add(text)
        return result()
    }

    override fun submit(token: String?): PlanGestureResult {
        submits++
        return result()
    }

    override fun scroll(forward: Boolean, token: String?): PlanGestureResult {
        scrolls.add(forward)
        return result()
    }

    override fun back(token: String?): PlanGestureResult {
        backs++
        return result()
    }

    override fun longPress(element: ScreenElement, token: String?): PlanGestureResult {
        longPresses.add(element)
        return result()
    }

    override fun swipe(direction: String, token: String?): PlanGestureResult {
        swipes.add(direction)
        return result()
    }
}

private class Rig(
    var screen: ScreenModel = ytmScreen(),
    var covered: Boolean = false,
    var outOfScope: Pair<String, String>? = null,
) {
    val observer = DObserver(screen)
    val cover = DCover(covered)
    val tokens = DTokenProvider()
    val gestures = DGestures()
    var focused = true
    var launchReport = "Opened YT Music."
    val launchCalls = mutableListOf<Pair<String, RequestOrigin>>()
    var searchOutcome: SearchOutcome = SearchOutcome.Success("Searched.")
    val searchCalls = mutableListOf<Pair<String, String>>()
    var messageOutcome: MessageOutcome = MessageOutcome.Success("Sent.")
    val messageCalls = mutableListOf<Triple<String, String, String>>()
    var callOutcome: CallOutcome = CallOutcome.Success("Called.")
    val callCalls = mutableListOf<String>()
    var guideResult = true
    val guideCalls = mutableListOf<String>()
    var nudgeResult: Boolean? = true
    val nudgeCalls = mutableListOf<String>()
    // Universal Console (2026-11-07): the goal-aware search hand-off.
    // Off by default so the existing plain-hand-off tests keep
    // exercising searchInApp; new tests opt in.
    var useFullSearch: Boolean = false
    var fullSearchOutcome: SearchOutcome = SearchOutcome.Success("Searched.")
    val fullSearchCalls = mutableListOf<Triple<String, String, com.loom.assistant.execution.search.SearchGoal>>()
    // MASTER F7a — the stop flag the dispatcher reads, and an
    // optional token-provider override (a parking provider for the
    // stop-during-card test).
    var stopped: Boolean = false
    var tokenProvider: PlanTokenProvider? = null
    // MASTER F7a — a parking launch: the launchApp sink blocks
    // until the test releases it, simulating the launcher's own
    // card wait (the plain launch path requests no dispatcher
    // token — the card lives inside the launcher).
    var launchBlocker: java.util.concurrent.CountDownLatch? = null
    val launchEntered = java.util.concurrent.CountDownLatch(1)

    fun dispatcher(): PlanStepDispatcher = PlanStepDispatcher(
        origin = RequestOrigin.DIRECT_USER,
        cover = cover,
        observer = observer,
        apps = dApps,
        tokens = tokenProvider ?: tokens,
        gestures = gestures,
        hasInputFocus = { focused },
        launchApp = { appName, origin, onResult ->
            launchCalls.add(appName to origin)
            launchEntered.countDown()
            launchBlocker?.await(30, java.util.concurrent.TimeUnit.SECONDS)
            onResult(launchReport)
        },
        searchInApp = { app, query, _ ->
            searchCalls.add(app to query)
            searchOutcome
        },
        sendMessage = { channel, recipient, text, _ ->
            messageCalls.add(Triple(channel, recipient, text))
            messageOutcome
        },
        placeCall = { target, _ ->
            callCalls.add(target)
            callOutcome
        },
        guideTo = { target ->
            guideCalls.add(target)
            guideResult
        },
        nudgePointer = if (nudgeResult == null) null else { direction ->
            nudgeCalls.add(direction)
            nudgeResult == true
        },
        isStepCovered = { cover.checkCover(it) },
        outOfScopeInfo = { outOfScope },
        sleeper = {},
        searchInAppFull = if (!useFullSearch) null else { app, query, _, goal, _ ->
            fullSearchCalls.add(Triple(app, query, goal))
            fullSearchOutcome
        },
        isStopped = { stopped },
    )
}

private fun fieldScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        ScreenElement(
            id = "search-field", text = "Search YouTube", contentDescription = null,
            className = "android.widget.EditText", bounds = Rect(0, 0, 100, 50),
            role = ElementRole.SEARCH_FIELD, enabled = true, isPassword = false,
        ),
        dEl("row-fall", "Davido — Fall"),
    ),
)

private fun menuScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        ScreenElement(
            id = "menu-btn", text = null, contentDescription = "More options",
            className = "android.widget.ImageButton", bounds = Rect(0, 0, 100, 50),
            role = ElementRole.MENU_ITEM, enabled = true, isPassword = false,
        ),
        dEl("row-fall", "Davido — Fall"),
    ),
)

private fun twoMenuScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        ScreenElement(
            id = "menu-1", text = null, contentDescription = "More options",
            className = "android.widget.ImageButton", bounds = Rect(0, 0, 100, 50),
            role = ElementRole.MENU_ITEM, enabled = true, isPassword = false,
        ),
        ScreenElement(
            id = "menu-2", text = "Menu",
            contentDescription = null,
            className = "android.widget.Button", bounds = Rect(0, 60, 100, 110),
            role = ElementRole.BUTTON, enabled = true, isPassword = false,
        ),
    ),
)

private fun tapStep(target: String, matchFirst: Boolean = true) = PlanStep(
    0, TaskStepKind.TAP_TARGET, "Tap $target",
    mapOf(PlanParams.TARGET to target) +
        if (matchFirst) mapOf(PlanParams.MATCH to "first") else emptyMap(),
)

/**
 * NAV-SCREEN fix (2026-11-11) — David's Facebook feed (720x1640):
 * bottom nav, six icon-only tabs, Home selected. Same shape as the
 * executor test's fixture.
 */
private fun facebookFeed(selectedIndex: Int = 0): ScreenModel {
    fun tab(i: Int, bounds: Rect) = ScreenElement(
        id = "tab$i", text = null, contentDescription = null,
        className = "android.widget.ImageView", bounds = bounds,
        role = ElementRole.ICON, enabled = true, isPassword = false,
        selected = i == selectedIndex,
    )
    val nav = ScreenElement(
        id = "nav", text = null, contentDescription = null,
        className = "com.google.android.material.bottomnavigation.BottomNavigationView",
        bounds = Rect(0, 1520, 720, 1640),
        role = ElementRole.NAVIGATION, enabled = true, isPassword = false,
    )
    val tabBounds = listOf(
        Rect(0, 1540, 120, 1580),
        Rect(120, 1540, 240, 1580),
        Rect(240, 1540, 360, 1580),
        Rect(360, 1540, 480, 1580),
        Rect(480, 1540, 600, 1580),
        Rect(600, 1540, 720, 1580),
    )
    val feed = ScreenElement(
        id = "feed", text = null, contentDescription = null,
        className = "androidx.recyclerview.widget.RecyclerView",
        bounds = Rect(0, 200, 720, 1520),
        role = ElementRole.LIST, enabled = true, isPassword = false,
    )
    return ScreenModel(
        packageName = "com.facebook.katana",
        elements = listOf(nav) + tabBounds.mapIndexed { i, b -> tab(i, b) } + listOf(feed),
        screenBounds = Rect(0, 0, 720, 1640),
    )
}

private fun navStep(target: String) = PlanStep(
    0, TaskStepKind.NAVIGATE_TAB, "Go to $target",
    mapOf(PlanParams.TARGET to target, PlanParams.PACKAGE to "com.facebook.katana"),
)

fun main() {
    // ---------- 1. covered tap: sequenced token, first result ----------
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        val result = d.dispatch(tapStep("Davido"), ytmScreen())
        checkEq("covered tap dispatches", result, StepDispatchResult.DISPATCHED)
        checkEq("covered tap hits the first row", rig.gestures.taps.singleOrNull()?.id, "row-fall")
        checkEq("covered tap requests one token", rig.tokens.requests.size, 1)
        val req = rig.tokens.requests[0]
        checkEq("covered tap token key", req.key, PlanActionKeys.TAP_GUIDANCE)
        check("covered tap token sequenced", req.sequenced)
        check("covered tap token not out-of-scope", !req.outOfScope)
    }

    // ---------- 2. out-of-scope tap: its own check ----------
    run {
        val rig = Rig(covered = false, outOfScope = "YT Music" to "com.other.app")
        val d = rig.dispatcher()
        val result = d.dispatch(tapStep("Davido"), ytmScreen())
        checkEq("out-of-scope tap dispatches", result, StepDispatchResult.DISPATCHED)
        val req = rig.tokens.requests.single()
        check("out-of-scope tap is not sequenced", !req.sequenced)
        check("out-of-scope tap requests its own check", req.outOfScope)
        checkEq("out-of-scope names the covered app", req.outOfScopeApp, "YT Music")
        checkEq("out-of-scope names the other app", req.outOfScopeOtherApp, "com.other.app")
    }

    // ---------- 3. uncovered tap: plain per-step token ----------
    run {
        val rig = Rig(covered = false)
        val d = rig.dispatcher()
        val result = d.dispatch(tapStep("Davido"), ytmScreen())
        checkEq("uncovered tap dispatches", result, StepDispatchResult.DISPATCHED)
        val req = rig.tokens.requests.single()
        check("uncovered tap is not sequenced", !req.sequenced)
        check("uncovered tap is not out-of-scope", !req.outOfScope)
    }

    // ---------- 4. tap target gone / ambiguous / declined ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val gone = d.dispatch(tapStep("Missing"), ytmScreen())
        check("gone tap fails", gone is StepDispatchResult.FAILED)
        checkEq("gone tap requests no token", rig.tokens.requests.size, 0)
        checkEq("gone tap never gestures", rig.gestures.taps.size, 0)
        val ambiguous = d.dispatch(tapStep("Davido", matchFirst = false), ytmScreen())
        check("ambiguous tap fails", ambiguous is StepDispatchResult.FAILED)
        rig.tokens.nextToken = null
        val declined = d.dispatch(tapStep("Davido"), ytmScreen())
        checkEq("declined tap is refused", declined, StepDispatchResult.REFUSED("Not confirmed — the tap of \"Davido\" was not approved."))
        checkEq("declined tap never gestures", rig.gestures.taps.size, 0)
    }

    // ---------- 5. financial target refused before any token ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val result = d.dispatch(
            PlanStep(0, TaskStepKind.TAP_TARGET, "Tap Pay now", mapOf(PlanParams.TARGET to "Pay now")),
            ytmScreen(),
        )
        check("financial tap refused", result is StepDispatchResult.REFUSED)
        checkEq("financial tap requests no token", rig.tokens.requests.size, 0)
    }

    // ---------- 6. sensitive app: action refused, guidance allowed ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val tap = d.dispatch(tapStep("Pay now"), sensitiveScreen())
        check("sensitive tap refused", tap is StepDispatchResult.REFUSED)
        checkEq("sensitive tap requests no token", rig.tokens.requests.size, 0)
        val guide = d.dispatch(
            PlanStep(0, TaskStepKind.GUIDE_TO, "Guide", mapOf(PlanParams.GUIDE_TARGET to "Pay now")),
            sensitiveScreen(),
        )
        checkEq("sensitive guide allowed", guide, StepDispatchResult.DISPATCHED)
        val verify = d.dispatch(
            PlanStep(0, TaskStepKind.VERIFY_OUTCOME, "Check", mapOf(PlanParams.EXPECTED to "Pay now")),
            sensitiveScreen(),
        )
        checkEq("sensitive verify allowed", verify, StepDispatchResult.DISPATCHED)
        val observe = d.dispatch(
            PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look", critical = false),
            sensitiveScreen(),
        )
        checkEq("sensitive observe allowed", observe, StepDispatchResult.DISPATCHED)
    }

    // ---------- 7. verify outcome: match vs mismatch ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val match = d.dispatch(
            PlanStep(0, TaskStepKind.VERIFY_OUTCOME, "Check playing", mapOf(PlanParams.EXPECTED to "playing Davido")),
            playingScreen(),
        )
        checkEq("verify match dispatches", match, StepDispatchResult.DISPATCHED)
        val mismatch = d.dispatch(
            PlanStep(0, TaskStepKind.VERIFY_OUTCOME, "Check playing", mapOf(PlanParams.EXPECTED to "playing Davido")),
            ytmScreen(),
        )
        check("verify mismatch fails", mismatch is StepDispatchResult.FAILED)
    }

    // ---------- 8. wait-for-app: flips, then times out ----------
    run {
        val rig = Rig()
        rig.observer.screen = ytmScreen()
        val d = rig.dispatcher()
        // Foreground is already YTM: immediate success.
        val ok = d.dispatch(
            PlanStep(0, TaskStepKind.WAIT_FOR_APP, "Wait", mapOf(PlanParams.PACKAGE to YTM_PKG)),
            ytmScreen(),
        )
        checkEq("wait-for-app foreground dispatches", ok, StepDispatchResult.DISPATCHED)
        rig.observer.screen = ScreenModel("com.other.app", listOf(dEl("other", "Other")))
        val timeout = d.dispatch(
            PlanStep(0, TaskStepKind.WAIT_FOR_APP, "Wait", mapOf(PlanParams.PACKAGE to YTM_PKG)),
            ScreenModel("com.other.app", listOf(dEl("other", "Other"))),
        )
        check("wait-for-app timeout fails", timeout is StepDispatchResult.FAILED)
    }

    // ---------- 8b. wait-for-app: foreground with a lagging (empty) tree ----------
    // Simple-task execution fix (2026-11-07): the old wait demanded a
    // non-empty tree, so a slow cold start failed the step even though
    // the app was up. A stable foreground (same package across two
    // polls) passes even with an empty tree.
    run {
        val rig = Rig()
        rig.observer.screen = ScreenModel(YTM_PKG, emptyList())
        val d = rig.dispatcher()
        val callsBefore = rig.observer.calls
        val ok = d.dispatch(
            PlanStep(0, TaskStepKind.WAIT_FOR_APP, "Wait", mapOf(PlanParams.PACKAGE to YTM_PKG)),
            ScreenModel(YTM_PKG, emptyList()),
        )
        checkEq("wait-for-app empty-tree foreground dispatches", ok, StepDispatchResult.DISPATCHED)
        check("wait-for-app needed two stable polls", rig.observer.calls - callsBefore >= 2)
    }
    run {
        val rig = Rig()
        rig.focused = false
        val d = rig.dispatcher()
        val noFocus = d.dispatch(
            PlanStep(0, TaskStepKind.TYPE_TEXT, "Type", mapOf(PlanParams.TEXT to "Davido")),
            ytmScreen(),
        )
        check("type without focus fails", noFocus is StepDispatchResult.FAILED)
        checkEq("type without focus requests no token", rig.tokens.requests.size, 0)
        rig.focused = true
        val typed = d.dispatch(
            PlanStep(0, TaskStepKind.TYPE_TEXT, "Type", mapOf(PlanParams.TEXT to "Davido")),
            ytmScreen(),
        )
        checkEq("type with focus dispatches", typed, StepDispatchResult.DISPATCHED)
        checkEq("type typed the exact text", rig.gestures.types.singleOrNull(), "Davido")
        checkEq("type token key", rig.tokens.requests.single().key, PlanActionKeys.DEVICE_TYPE)
    }

    // ---------- 10. submit / scroll / back ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        checkEq("submit dispatches", d.dispatch(PlanStep(0, TaskStepKind.SUBMIT, "Submit"), ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("submit token key", rig.tokens.requests[0].key, PlanActionKeys.DEVICE_SUBMIT)
        checkEq("submit called once", rig.gestures.submits, 1)
        checkEq("scroll down dispatches", d.dispatch(PlanStep(0, TaskStepKind.SCROLL, "Scroll", mapOf(PlanParams.DIRECTION to "down")), ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("scroll down is forward", rig.gestures.scrolls.singleOrNull(), true)
        checkEq("scroll up dispatches", d.dispatch(PlanStep(0, TaskStepKind.SCROLL, "Scroll", mapOf(PlanParams.DIRECTION to "up")), ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("scroll up is backward", rig.gestures.scrolls[1], false)
        checkEq("back dispatches", d.dispatch(PlanStep(0, TaskStepKind.GO_BACK, "Back"), ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("back called once", rig.gestures.backs, 1)
    }

    // ---------- 11. search outcome mapping ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val searchStep = PlanStep(0, TaskStepKind.SEARCH_IN_APP, "Search", mapOf(PlanParams.APP to "YouTube Music", PlanParams.QUERY to "Davido"))
        rig.searchOutcome = SearchOutcome.Success("Searched.")
        checkEq("search success dispatches", d.dispatch(searchStep, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("search called with app+query", rig.searchCalls.singleOrNull(), "YouTube Music" to "Davido")
        rig.searchOutcome = SearchOutcome.Failed("No results.")
        checkEq("search failure fails", d.dispatch(searchStep, ytmScreen()), StepDispatchResult.FAILED("No results."))
        rig.searchOutcome = SearchOutcome.AppNotFound
        val notFound = d.dispatch(searchStep, ytmScreen())
        check("search app-not-found fails naming the app", notFound is StepDispatchResult.FAILED && (notFound as StepDispatchResult.FAILED).reason.contains("YouTube Music"))
        rig.searchOutcome = SearchOutcome.Ambiguous(listOf("YT Music", "Music Player"))
        val ambiguous = d.dispatch(searchStep, ytmScreen())
        check("search ambiguous fails naming candidates", ambiguous is StepDispatchResult.FAILED && (ambiguous as StepDispatchResult.FAILED).reason.contains("YT Music"))
    }

    // ---------- 12. message / call route the exact words ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val messageStep = PlanStep(
            0, TaskStepKind.MESSAGE_SEND, "Send",
            mapOf(
                PlanParams.RECIPIENT to "Mum",
                PlanParams.MESSAGE to "hello mum",
                PlanParams.CHANNEL to "whatsapp",
            ),
        )
        checkEq("message dispatches", d.dispatch(messageStep, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("message carries exact words", rig.messageCalls.singleOrNull(), Triple("whatsapp", "Mum", "hello mum"))
        val callStep = PlanStep(0, TaskStepKind.CALL_PLACE, "Call", mapOf(PlanParams.RECIPIENT to "Mum"))
        checkEq("call dispatches", d.dispatch(callStep, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("call carries the target", rig.callCalls.singleOrNull(), "Mum")
    }

    // ---------- 13. launch mapping ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val launchStep = PlanStep(0, TaskStepKind.LAUNCH_APP, "Open", mapOf(PlanParams.APP to "YouTube Music"))
        // Session/state awareness (2026-11-11): launching an app
        // that is ALREADY in the foreground is a skip, not a launch
        // — the step succeeds as already-there and the launcher is
        // never invoked (a re-launch restarts the task).
        checkEq(
            "launch of the foreground app dispatches as already-there",
            d.dispatch(launchStep, ytmScreen()), StepDispatchResult.DISPATCHED,
        )
        check(
            "launch of the foreground app never invokes the launcher",
            rig.launchCalls.isEmpty(),
        )
        checkEq(
            "launch from another app dispatches",
            d.dispatch(launchStep, facebookFeed()), StepDispatchResult.DISPATCHED,
        )
        checkEq("launch passes origin through", rig.launchCalls.singleOrNull()?.second, RequestOrigin.DIRECT_USER)
        rig.launchReport = "I couldn't find an app called 'Nope' on this phone."
        val failed = d.dispatch(PlanStep(0, TaskStepKind.LAUNCH_APP, "Open", mapOf(PlanParams.APP to "Nope")), ytmScreen())
        check("launch failure fails with the report", failed is StepDispatchResult.FAILED)
    }

    // ---------- 15. Universal Console: long-press ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val step = PlanStep(
            0, TaskStepKind.LONG_PRESS, "Long-press Davido",
            mapOf(PlanParams.TARGET to "Davido", PlanParams.MATCH to "first"),
        )
        checkEq("long-press dispatches", d.dispatch(step, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("long-press hits the first row", rig.gestures.longPresses.singleOrNull()?.id, "row-fall")
        checkEq("long-press reuses the tap key", rig.tokens.requests.single().key, PlanActionKeys.TAP_GUIDANCE)
        checkEq("long-press never taps", rig.gestures.taps.size, 0)
        val gone = d.dispatch(
            PlanStep(0, TaskStepKind.LONG_PRESS, "Long-press", mapOf(PlanParams.TARGET to "Missing")),
            ytmScreen(),
        )
        check("long-press target gone fails", gone is StepDispatchResult.FAILED)
        val financial = d.dispatch(
            PlanStep(0, TaskStepKind.LONG_PRESS, "Long-press Pay now", mapOf(PlanParams.TARGET to "Pay now")),
            ytmScreen(),
        )
        check("long-press financial refused", financial is StepDispatchResult.REFUSED)
        // A host without the long-press sink refuses honestly —
        // the interface default, never a silent success.
        val bareGestures = object : PlanGestures {
            override fun tap(element: ScreenElement, token: String?) = PlanGestureResult.Accepted
            override fun type(text: String, token: String?) = PlanGestureResult.Accepted
            override fun submit(token: String?) = PlanGestureResult.Accepted
            override fun scroll(forward: Boolean, token: String?) = PlanGestureResult.Accepted
            override fun back(token: String?) = PlanGestureResult.Accepted
        }
        val rig2 = Rig()
        val d2 = PlanStepDispatcher(
            origin = RequestOrigin.DIRECT_USER, cover = rig2.cover, observer = rig2.observer,
            apps = dApps, tokens = rig2.tokens, gestures = bareGestures,
            hasInputFocus = { true },
            launchApp = { _, _, onResult -> onResult("Opened X.") },
            searchInApp = { _, _, _ -> SearchOutcome.Success("ok") },
            sendMessage = { _, _, _, _ -> MessageOutcome.Success("ok") },
            placeCall = { _, _ -> CallOutcome.Success("ok") },
            guideTo = { true },
        )
        val unwired = d2.dispatch(step, ytmScreen())
        check("long-press without a sink fails honestly", unwired is StepDispatchResult.FAILED)
        check(
            "long-press without a sink names the gap",
            (unwired as StepDispatchResult.FAILED).reason.contains("Long-press not available"),
        )
    }

    // ---------- 16. Universal Console: swipe ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        for (direction in listOf("up", "down", "left", "right")) {
            val step = PlanStep(0, TaskStepKind.SWIPE, "Swipe $direction", mapOf(PlanParams.DIRECTION to direction))
            checkEq("swipe $direction dispatches", d.dispatch(step, ytmScreen()), StepDispatchResult.DISPATCHED)
        }
        checkEq("swipe recorded all four directions", rig.gestures.swipes, listOf("up", "down", "left", "right"))
        check("swipe reuses the scroll key", rig.tokens.requests.all { it.key == PlanActionKeys.DEVICE_SCROLL })
        val bad = d.dispatch(
            PlanStep(0, TaskStepKind.SWIPE, "Swipe", mapOf(PlanParams.DIRECTION to "sideways")),
            ytmScreen(),
        )
        check("swipe without a valid direction fails", bad is StepDispatchResult.FAILED)
        checkEq("bad swipe requests no token", rig.tokens.requests.size, 4)
        checkEq("bad swipe never gestures", rig.gestures.swipes.size, 4)
    }

    // ---------- 17. Universal Console: focus field ----------
    run {
        val rig = Rig()
        rig.focused = true
        val d = rig.dispatcher()
        val step = PlanStep(0, TaskStepKind.FOCUS_FIELD, "Focus search", mapOf(PlanParams.TARGET to "search"))
        checkEq("focus field dispatches when focus lands", d.dispatch(step, fieldScreen()), StepDispatchResult.DISPATCHED)
        checkEq("focus field tapped the field element", rig.gestures.taps.singleOrNull()?.id, "search-field")
        // The tap landed but the field never took focus: FAILED,
        // never DISPATCHED — dispatch alone is not focus.
        val rig2 = Rig()
        rig2.focused = false
        val d2 = rig2.dispatcher()
        val unfocused = d2.dispatch(step, fieldScreen())
        check("focus field without focus fails", unfocused is StepDispatchResult.FAILED)
        checkEq("unfocused field still tapped once", rig2.gestures.taps.size, 1)
        val gone = d.dispatch(
            PlanStep(0, TaskStepKind.FOCUS_FIELD, "Focus", mapOf(PlanParams.TARGET to "Missing")),
            fieldScreen(),
        )
        check("focus field target gone fails", gone is StepDispatchResult.FAILED)
    }

    // ---------- 18. Universal Console: open menu ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val step = PlanStep(0, TaskStepKind.OPEN_MENU, "Open the menu")
        checkEq("open menu dispatches", d.dispatch(step, menuScreen()), StepDispatchResult.DISPATCHED)
        checkEq("open menu tapped the menu control", rig.gestures.taps.singleOrNull()?.id, "menu-btn")
        check("open menu with no control fails", d.dispatch(step, ytmScreen()) is StepDispatchResult.FAILED)
        val ambiguous = d.dispatch(step, twoMenuScreen())
        check("open menu with two controls fails, never guesses", ambiguous is StepDispatchResult.FAILED)
        checkEq("ambiguous menu never tapped", rig.gestures.taps.size, 1)
    }

    // ---------- 19. Universal Console: search goal forwarding ----------
    run {
        val rig = Rig()
        rig.useFullSearch = true
        val d = rig.dispatcher()
        val showStep = PlanStep(
            0, TaskStepKind.SEARCH_IN_APP, "Search",
            mapOf(
                PlanParams.APP to "YouTube Music", PlanParams.QUERY to "Davido",
                PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_SHOW_RESULTS,
            ),
        )
        checkEq("show-results search dispatches", d.dispatch(showStep, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq(
            "show-results goal reaches the search",
            rig.fullSearchCalls.singleOrNull(),
            Triple("YouTube Music", "Davido", com.loom.assistant.execution.search.SearchGoal.SHOW_RESULTS),
        )
        checkEq("show-results never used the plain hand-off", rig.searchCalls.size, 0)
        val openStep = PlanStep(
            0, TaskStepKind.SEARCH_IN_APP, "Search",
            mapOf(
                PlanParams.APP to "YouTube Music", PlanParams.QUERY to "Davido",
                PlanParams.SEARCH_GOAL to PlanParams.SEARCH_GOAL_OPEN_FIRST,
            ),
        )
        checkEq("open-first search dispatches", d.dispatch(openStep, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq(
            "open-first goal reaches the search",
            rig.fullSearchCalls[1].third,
            com.loom.assistant.execution.search.SearchGoal.OPEN_FIRST_RESULT,
        )
        // No goal-aware hand-off: a show-results search FAILS
        // honestly instead of opening a result through the plain
        // hand-off (which always opens the first result).
        val rig2 = Rig()
        val d2 = rig2.dispatcher()
        val refused = d2.dispatch(showStep, ytmScreen())
        check("show-results without a goal-aware hand-off fails", refused is StepDispatchResult.FAILED)
        checkEq("failed show-results never searched", rig2.searchCalls.size, 0)
        check(
            "failed show-results names the reason",
            (refused as StepDispatchResult.FAILED).reason.contains("results list"),
        )
        val unknown = d.dispatch(
            PlanStep(
                0, TaskStepKind.SEARCH_IN_APP, "Search",
                mapOf(PlanParams.APP to "YouTube Music", PlanParams.QUERY to "Davido", PlanParams.SEARCH_GOAL to "teleport"),
            ),
            ytmScreen(),
        )
        check("unknown search goal fails", unknown is StepDispatchResult.FAILED)
    }

    // ---------- 20. Universal Console: sensitive-app refusal ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val longPress = d.dispatch(
            PlanStep(0, TaskStepKind.LONG_PRESS, "Long-press", mapOf(PlanParams.TARGET to "Pay now")),
            sensitiveScreen(),
        )
        check("sensitive long-press refused", longPress is StepDispatchResult.REFUSED)
        val swipe = d.dispatch(
            PlanStep(0, TaskStepKind.SWIPE, "Swipe", mapOf(PlanParams.DIRECTION to "left")),
            sensitiveScreen(),
        )
        check("sensitive swipe refused", swipe is StepDispatchResult.REFUSED)
        val focus = d.dispatch(
            PlanStep(0, TaskStepKind.FOCUS_FIELD, "Focus", mapOf(PlanParams.TARGET to "search")),
            sensitiveScreen(),
        )
        check("sensitive focus refused", focus is StepDispatchResult.REFUSED)
        val menu = d.dispatch(PlanStep(0, TaskStepKind.OPEN_MENU, "Open the menu"), sensitiveScreen())
        check("sensitive menu refused", menu is StepDispatchResult.REFUSED)
        checkEq("sensitive console steps request no token", rig.tokens.requests.size, 0)
        checkEq("sensitive console steps never gesture", rig.gestures.longPresses.size + rig.gestures.swipes.size + rig.gestures.taps.size, 0)
    }

    // ---------- 14. guide / pointer-move / report / observe ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val guideStep = PlanStep(0, TaskStepKind.GUIDE_TO, "Guide", mapOf(PlanParams.GUIDE_TARGET to "Search"))
        checkEq("guide dispatches", d.dispatch(guideStep, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("guide carries the target", rig.guideCalls.singleOrNull(), "Search")
        rig.guideResult = false
        check("guide unavailable fails", d.dispatch(guideStep, ytmScreen()) is StepDispatchResult.FAILED)
        val moveStep = PlanStep(0, TaskStepKind.POINTER_MOVE, "Move", mapOf(PlanParams.DIRECTION to "left"))
        checkEq("pointer move dispatches", d.dispatch(moveStep, ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("pointer move carries direction", rig.nudgeCalls.singleOrNull(), "left")
        val rig2 = Rig()
        rig2.nudgeResult = null
        val d2 = rig2.dispatcher()
        check("pointer move without pointer fails", d2.dispatch(moveStep, ytmScreen()) is StepDispatchResult.FAILED)
        checkEq("report no-ops", d.dispatch(PlanStep(0, TaskStepKind.REPORT, "Report", critical = false), ytmScreen()), StepDispatchResult.DISPATCHED)
        checkEq("observe no-ops", d.dispatch(PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look", critical = false), ytmScreen()), StepDispatchResult.DISPATCHED)
    }

    // ---------- 21. MASTER F7a: a stop prevents dispatch ----------
    run {
        // Stop set before dispatch: no token, no gesture.
        val rig = Rig()
        rig.stopped = true
        val d = rig.dispatcher()
        val result = d.dispatch(tapStep("Davido"), ytmScreen())
        checkEq("stop before dispatch fails stopped", result, StepDispatchResult.FAILED("Stopped."))
        checkEq("stop before dispatch requests no token", rig.tokens.requests.size, 0)
        checkEq("stop before dispatch never gestures", rig.gestures.taps.size, 0)
    }
    run {
        // The regression test: a card parks on the token wait, David
        // says "stop", THEN approves the card — the step must NOT
        // execute. The approval's token expires unconsumed.
        val rig = Rig()
        val parking = ParkingTokens()
        rig.tokenProvider = parking
        val d = rig.dispatcher()
        var result: StepDispatchResult? = null
        val t = Thread({ result = d.dispatch(tapStep("Davido"), ytmScreen()) }, "disp-stop-test").apply { isDaemon = true }
        t.start()
        check("parked card entered the token wait", parking.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        rig.stopped = true
        parking.release.countDown()
        t.join(5_000)
        check("dispatch thread finished after stop+approve", !t.isAlive)
        checkEq("stop during parked card fails stopped", result, StepDispatchResult.FAILED("Stopped."))
        checkEq("stop during parked card never gestures", rig.gestures.taps.size, 0)
    }
    run {
        // Stop during the launch card wait: same rule — the launch
        // sink was invoked and its report says "Opened", but the
        // stop landed during the wait, so the report is abandoned
        // and the step is NOT counted as dispatched. Dispatched
        // from the Facebook feed (not the YTM screen) so the
        // already-foreground guard does not skip the launch.
        val rig = Rig()
        rig.launchBlocker = java.util.concurrent.CountDownLatch(1)
        rig.launchReport = "Opened YT Music."
        val d = rig.dispatcher()
        var result: StepDispatchResult? = null
        val t = Thread({
            result = d.dispatch(
                PlanStep(0, TaskStepKind.LAUNCH_APP, "Open", mapOf(PlanParams.APP to "YouTube Music")),
                facebookFeed(),
            )
        }, "disp-stop-launch-test").apply { isDaemon = true }
        t.start()
        check("launch entered the card wait", rig.launchEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        rig.stopped = true
        rig.launchBlocker?.countDown()
        t.join(5_000)
        check("launch dispatch thread finished after stop+approve", !t.isAlive)
        checkEq("stop during launch card fails stopped", result, StepDispatchResult.FAILED("Stopped."))
        checkEq("stop during launch card invoked the sink once", rig.launchCalls.size, 1)
    }

    // ---------- 22. MASTER F5: "Already in" launch reports succeed ----------
    // Dispatched from the Facebook feed (not the YTM screen) so the
    // already-foreground guard does not skip the launcher — this
    // test exercises the LAUNCHER's "Already in" report path.
    run {
        val rig = Rig()
        rig.launchReport = "Already in YT Music."
        val d = rig.dispatcher()
        val result = d.dispatch(
            PlanStep(0, TaskStepKind.LAUNCH_APP, "Open", mapOf(PlanParams.APP to "YouTube Music")),
            facebookFeed(),
        )
        checkEq("already-in launch dispatches", result, StepDispatchResult.DISPATCHED)
    }

    // ---------- Screen confirmation (2026-11-10): an unconfirmed
    // launch ("I tried to open X but it didn't come to the
    // foreground") must NEVER read as success — the launcher only
    // says "Opened X." once the app is seen in the foreground. ----
    // Dispatched from the Facebook feed (not the YTM screen) so the
    // already-foreground guard does not skip the launcher — this
    // test exercises the launcher's unconfirmed report.
    run {
        val rig = Rig()
        rig.launchReport = "I tried to open YT Music but it didn't come to the foreground — I couldn't confirm it opened."
        val d = rig.dispatcher()
        val result = d.dispatch(
            PlanStep(0, TaskStepKind.LAUNCH_APP, "Open", mapOf(PlanParams.APP to "YouTube Music")),
            facebookFeed(),
        )
        check("unconfirmed launch fails honestly", result is StepDispatchResult.FAILED)
        check(
            "unconfirmed launch names the gap",
            (result as StepDispatchResult.FAILED).reason.contains("didn't come to the foreground"),
        )
    }

    // ---------- MASTER Wave 3 (F15b): PLAY_PAUSE dispatches the
    // play/pause control — a tap alone is never success, and the
    // step fails honestly when no control is on screen. ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val pause = d.dispatch(
            PlanStep(0, TaskStepKind.PLAY_PAUSE, "Pause the video", mapOf(PlanParams.PLAY_STATE to "pause")),
            playingScreen(),
        )
        checkEq("pause dispatches on a playing screen", pause, StepDispatchResult.DISPATCHED)
        checkEq("pause tapped the pause control", rig.gestures.taps.singleOrNull()?.id, "pause")
        val paused = ScreenModel(
            packageName = YTM_PKG,
            elements = listOf(
                ScreenElement(
                    id = "player", text = null, contentDescription = null,
                    className = "android.widget.VideoView", bounds = Rect(0, 0, 100, 200),
                    role = ElementRole.OTHER, enabled = true, isPassword = false,
                ),
                dEl("play", "Play", ElementRole.BUTTON),
            ),
        )
        val rig2 = Rig()
        val d2 = rig2.dispatcher()
        val play = d2.dispatch(
            PlanStep(0, TaskStepKind.PLAY_PAUSE, "Play the video", mapOf(PlanParams.PLAY_STATE to "play")),
            paused,
        )
        checkEq("play dispatches on a paused screen", play, StepDispatchResult.DISPATCHED)
        checkEq("play tapped the play control", rig2.gestures.taps.singleOrNull()?.id, "play")
        val rig3 = Rig()
        val d3 = rig3.dispatcher()
        val toggle = d3.dispatch(
            PlanStep(0, TaskStepKind.PLAY_PAUSE, "Play or pause", emptyMap()),
            playingScreen(),
        )
        checkEq("toggle pauses a playing video", toggle, StepDispatchResult.DISPATCHED)
        checkEq("toggle tapped the pause control", rig3.gestures.taps.singleOrNull()?.id, "pause")
        val rig4 = Rig()
        val d4 = rig4.dispatcher()
        val none = d4.dispatch(
            PlanStep(0, TaskStepKind.PLAY_PAUSE, "Pause the video", mapOf(PlanParams.PLAY_STATE to "pause")),
            ytmScreen(),
        )
        check("pause with no video fails", none is StepDispatchResult.FAILED)
        checkEq("pause with no video never taps", rig4.gestures.taps.size, 0)
    }

    // ---------- MASTER Wave 3 (F9): MATCH=first is user-directed —
    // it taps the first match; a bare tap on several matches refuses.
    // (The blind pick was the planner emitting MATCH=first by default;
    // the dispatcher honors it only when the step carries it.) ----------
    run {
        val rig = Rig()
        val d = rig.dispatcher()
        val first = d.dispatch(tapStep("Davido", matchFirst = true), ytmScreen())
        checkEq("explicit-first tap dispatches", first, StepDispatchResult.DISPATCHED)
        checkEq("explicit-first tap hits the first row", rig.gestures.taps.singleOrNull()?.id, "row-fall")
        val rig2 = Rig()
        val d2 = rig2.dispatcher()
        val bare = d2.dispatch(tapStep("Davido", matchFirst = false), ytmScreen())
        check("bare tap on two matches fails", bare is StepDispatchResult.FAILED)
        checkEq("bare tap never gestures", rig2.gestures.taps.size, 0)
    }

    // ---- MASTER Wave 4 (Wave 3 residual): bare "the first result" ----
    // A bare first-result target names no text to substring-match:
    // the first result is positional — the topmost row of the first
    // list on screen. The voice-search mic is never the first result.
    run {
        val resultsScreen = ScreenModel(
            packageName = YTM_PKG,
            elements = listOf(
                ScreenElement(
                    id = "results-list", text = null, contentDescription = null,
                    className = "androidx.recyclerview.widget.RecyclerView",
                    bounds = Rect(0, 300, 1080, 2000), role = ElementRole.LIST,
                    enabled = true, isPassword = false,
                ),
                ScreenElement(
                    id = "row-fall", text = "Davido — Fall", contentDescription = null,
                    className = "android.widget.TextView",
                    bounds = Rect(40, 320, 900, 400), role = ElementRole.TEXT,
                    enabled = true, isPassword = false,
                ),
                ScreenElement(
                    id = "row-if", text = "Davido — If", contentDescription = null,
                    className = "android.widget.TextView",
                    bounds = Rect(40, 500, 900, 580), role = ElementRole.TEXT,
                    enabled = true, isPassword = false,
                ),
                ScreenElement(
                    id = "mic", text = "Voice search", contentDescription = null,
                    className = "android.widget.ImageButton",
                    bounds = Rect(980, 320, 1060, 400), role = ElementRole.BUTTON,
                    enabled = true, isPassword = false,
                ),
            ),
        )
        check("bare selector recognised", isBareFirstTarget("the first result"))
        check("bare selector recognised without 'the'", isBareFirstTarget("first video"))
        check("named target is not a bare selector", !isBareFirstTarget("Davido"))
        check("first + extra text is not a bare selector", !isBareFirstTarget("first Davido song"))
        checkEq(
            "first row is the topmost list row, not the mic",
            firstResultRow(resultsScreen)?.id,
            "row-fall",
        )
        val rig = Rig(screen = resultsScreen)
        val d = rig.dispatcher()
        val result = d.dispatch(tapStep("the first result", matchFirst = false), resultsScreen)
        checkEq("bare first-result tap dispatches", result, StepDispatchResult.DISPATCHED)
        checkEq("bare first-result tap hits the first row", rig.gestures.taps.singleOrNull()?.id, "row-fall")
        check("mic never tapped as the first result", rig.gestures.taps.none { it.id == "mic" })

        // No results on screen: fails honestly, never taps.
        val rig2 = Rig()
        val d2 = rig2.dispatcher()
        val empty = d2.dispatch(
            tapStep("the first result", matchFirst = false),
            ScreenModel(packageName = YTM_PKG, elements = emptyList()),
        )
        check("bare first with no results fails", empty is StepDispatchResult.FAILED)
        checkEq("bare first with no results never gestures", rig2.gestures.taps.size, 0)
        if (empty is StepDispatchResult.FAILED) {
            check("bare first failure names the empty screen", empty.reason.contains("No results on screen"))
        }
    }

    // ---------- NAV-SCREEN (2026-11-11): NAVIGATE_TAB dispatch ----------
    run {
        // The reference case: Facebook feed, "Go to Reels" — the
        // icon-only 3rd tab resolves positionally and is tapped
        // (fresh re-resolution against the same screen succeeds).
        val rig = Rig(screen = facebookFeed())
        val result = rig.dispatcher().dispatch(navStep("Reels"), facebookFeed())
        checkEq("nav dispatch: Reels dispatches", result, StepDispatchResult.DISPATCHED)
        checkEq("nav dispatch: one tap", rig.gestures.taps.size, 1)
        checkEq("nav dispatch: tapped the Reels tab (3rd)", rig.gestures.taps.singleOrNull()?.id, "tab2")
        checkEq(
            "nav dispatch: tapped the Reels tab bounds",
            rig.gestures.taps.singleOrNull()?.bounds, Rect(240, 1540, 360, 1580),
        )
    }
    run {
        // No nav bar on screen: fails honestly as target gone,
        // never taps.
        val rig = Rig(screen = ytmScreen())
        val result = rig.dispatcher().dispatch(navStep("Reels"), ytmScreen())
        check("nav dispatch: no tab on screen fails", result is StepDispatchResult.FAILED)
        checkEq("nav dispatch: no tab on screen never taps", rig.gestures.taps.size, 0)
        if (result is StepDispatchResult.FAILED) {
            check("nav dispatch: failure says target gone", result.reason.contains("target gone"))
        }
    }
    run {
        // Sensitive app: navigation is refused, never tapped.
        val rig = Rig(screen = sensitiveScreen())
        val result = rig.dispatcher().dispatch(navStep("Reels"), sensitiveScreen())
        check("nav dispatch: sensitive app refuses", result is StepDispatchResult.REFUSED)
        checkEq("nav dispatch: sensitive app never taps", rig.gestures.taps.size, 0)
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
