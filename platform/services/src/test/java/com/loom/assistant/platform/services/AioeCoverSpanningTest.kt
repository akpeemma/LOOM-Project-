package com.loom.assistant.platform.services

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.messaging.CallOutcome
import com.loom.assistant.execution.messaging.MessageOutcome
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
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
 * AOIE Phase 1 / §3 R1–R4 — the per-sequence cover must span EVERY
 * plan step kind, not just tap.
 *
 * David's order: scroll freely, do whatever — no per-action card
 * for ordinary reversible interaction inside a covered plan.
 * Under a live cover every ordinary kind (type, submit, scroll,
 * back, swipe, long-press, focus, menu, launch) must request the
 * sequenced ALLOW token (no card, no wait); outside a cover the
 * same kinds still request CONFIRM (card); consequential kinds
 * (message, call) never consult the cover at all — they keep their
 * own fresh token per action.
 *
 * Run via test-harness/run-2c-tests.sh (runner: aioecover).
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

private const val YTM_PKG = "com.google.android.apps.youtube.music"

private fun el(
    id: String,
    text: String?,
    role: ElementRole = ElementRole.TEXT,
    hintText: String? = null,
) = ScreenElement(
    id = id,
    text = text,
    contentDescription = null,
    className = "android.widget.TextView",
    bounds = Rect(0, 0, 100, 50),
    role = role,
    enabled = true,
    isPassword = false,
    hintText = hintText,
)

private fun ytmScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        el("search-field", null, ElementRole.SEARCH_FIELD, hintText = "Search YouTube Music"),
        el("menu-btn", "More options", ElementRole.MENU_ITEM),
        el("row-fall", "Davido — Fall"),
    ),
)

private val apps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" -> AppResolution.Found(YTM_PKG, "YT Music")
        else -> AppResolution.NotFound
    }
}

private class AObserver(var screen: ScreenModel) : ScreenObserver {
    override fun observeNow(): ScreenModel = screen
}

private class ACover(var covered: Boolean) : PlanAuthCover {
    override fun requestPlanCover(plan: TaskPlan): CoverResult = CoverResult.GRANTED
    override fun checkCover(step: PlanStep): Boolean = covered
}

private class ATokens(var nextToken: String? = "tok-1") : PlanTokenProvider {
    val requests = mutableListOf<PlanTokenRequest>()
    override fun tokenFor(request: PlanTokenRequest): String? {
        requests.add(request)
        return nextToken
    }
}

private class AGestures : PlanGestures {
    val taps = mutableListOf<ScreenElement>()
    val types = mutableListOf<String>()
    val submits = mutableListOf<String?>()
    var submitRefuse = false
    var viaKeyRefuse = false
    val scrolls = mutableListOf<Boolean>()
    var backs = 0
    val longPresses = mutableListOf<ScreenElement>()
    val swipes = mutableListOf<String>()

    override fun tap(element: ScreenElement, token: String?): PlanGestureResult {
        taps.add(element)
        return PlanGestureResult.Accepted
    }

    override fun type(text: String, token: String?): PlanGestureResult {
        types.add(text)
        return PlanGestureResult.Accepted
    }

    override fun submit(token: String?): PlanGestureResult {
        submits.add(token)
        return if (submitRefuse) PlanGestureResult.Refused("ime failed")
        else PlanGestureResult.Accepted
    }

    override fun submitViaKey(token: String?): PlanGestureResult {
        submits.add(token)
        return if (viaKeyRefuse) PlanGestureResult.Refused("key failed")
        else PlanGestureResult.Accepted
    }

    override fun scroll(forward: Boolean, token: String?): PlanGestureResult {
        scrolls.add(forward)
        return PlanGestureResult.Accepted
    }

    override fun back(token: String?): PlanGestureResult {
        backs++
        return PlanGestureResult.Accepted
    }

    override fun longPress(element: ScreenElement, token: String?): PlanGestureResult {
        longPresses.add(element)
        return PlanGestureResult.Accepted
    }

    override fun swipe(direction: String, token: String?): PlanGestureResult {
        swipes.add(direction)
        return PlanGestureResult.Accepted
    }
}

private class Rig(var covered: Boolean, var outOfScope: Pair<String, String>? = null) {
    val observer = AObserver(ytmScreen())
    val cover = ACover(covered)
    val tokens = ATokens()
    val gestures = AGestures()
    var focused = true
    val launchWithTokenCalls = mutableListOf<String>()
    val plainLaunchCalls = mutableListOf<String>()
    val messageCalls = mutableListOf<String>()
    val callCalls = mutableListOf<String>()

    fun dispatcher(): PlanStepDispatcher = PlanStepDispatcher(
        origin = RequestOrigin.DIRECT_USER,
        cover = cover,
        observer = observer,
        apps = apps,
        tokens = tokens,
        gestures = gestures,
        hasInputFocus = { focused },
        launchApp = { appName, _, onResult ->
            plainLaunchCalls.add(appName)
            onResult("Opened YT Music.")
        },
        searchInApp = { _, _, _ -> SearchOutcome.Success("Searched.") },
        sendMessage = { channel, recipient, text, _ ->
            messageCalls.add("$channel:$recipient:$text")
            MessageOutcome.Success("Sent.")
        },
        placeCall = { target, _ ->
            callCalls.add(target)
            CallOutcome.Success("Called.")
        },
        guideTo = { true },
        nudgePointer = null,
        isStepCovered = { cover.checkCover(it) },
        outOfScopeInfo = { outOfScope },
        sleeper = {},
        launchAppWithToken = { appName, tokenId, onResult ->
            launchWithTokenCalls.add("$appName:$tokenId")
            onResult("Opened YT Music.")
        },
    )
}

private fun step(kind: TaskStepKind, params: Map<String, String> = emptyMap()) =
    PlanStep(index = 0, kind = kind, description = kind.name, params = params)

private fun fieldStep() = PlanStep(
    index = 0,
    kind = TaskStepKind.TYPE_TEXT,
    description = "Type",
    params = mapOf(PlanParams.TEXT to "davido"),
)

fun main() {
    // 1. Covered TYPE_TEXT → sequenced ALLOW token, no card.
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        val r = d.dispatch(fieldStep(), ytmScreen())
        check("covered type dispatches", r is StepDispatchResult.DISPATCHED)
        checkEq("covered type: one token", rig.tokens.requests.size, 1)
        check("covered type: sequenced", rig.tokens.requests[0].sequenced)
        checkEq("covered type: key", rig.tokens.requests[0].key, "device.type")
    }
    // 2. Covered SUBMIT with IME refuse → ONE token total (no
    // second card for the keyboard-key fallback), same token both.
    run {
        val rig = Rig(covered = true)
        rig.gestures.submitRefuse = true
        val d = rig.dispatcher()
        val r = d.dispatch(step(TaskStepKind.SUBMIT), ytmScreen())
        check("covered submit fallback dispatches", r is StepDispatchResult.DISPATCHED)
        checkEq("covered submit: one token total", rig.tokens.requests.size, 1)
        check("covered submit: sequenced", rig.tokens.requests[0].sequenced)
        checkEq("covered submit: both calls same token", rig.gestures.submits.distinct().size, 1)
        checkEq("covered submit: token value", rig.gestures.submits[0], "tok-1")
    }
    // 3. Covered SCROLL / GO_BACK / SWIPE → sequenced.
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        d.dispatch(step(TaskStepKind.SCROLL, mapOf(PlanParams.DIRECTION to "forward")), ytmScreen())
        d.dispatch(step(TaskStepKind.GO_BACK), ytmScreen())
        d.dispatch(step(TaskStepKind.SWIPE, mapOf(PlanParams.DIRECTION to "left")), ytmScreen())
        checkEq("covered scroll/back/swipe: three tokens", rig.tokens.requests.size, 3)
        check("covered scroll/back/swipe: all sequenced", rig.tokens.requests.all { it.sequenced })
        checkEq("covered scroll key", rig.tokens.requests[0].key, "device.scroll")
        checkEq("covered back key", rig.tokens.requests[1].key, "device.back")
        checkEq("covered swipe key", rig.tokens.requests[2].key, "device.scroll")
    }
    // 4. Covered LONG_PRESS / FOCUS_FIELD / OPEN_MENU → sequenced.
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        d.dispatch(
            PlanStep(
                index = 0,
                kind = TaskStepKind.LONG_PRESS,
                description = "Long-press Fall",
                params = mapOf(PlanParams.TARGET to "Davido — Fall"),
            ),
            ytmScreen(),
        )
        d.dispatch(
            PlanStep(
                index = 1,
                kind = TaskStepKind.FOCUS_FIELD,
                description = "Focus search field",
                params = mapOf(PlanParams.TARGET to "Search YouTube Music"),
            ),
            ytmScreen(),
        )
        d.dispatch(
            PlanStep(
                index = 2,
                kind = TaskStepKind.OPEN_MENU,
                description = "Open menu",
                params = mapOf(PlanParams.TARGET to "More options"),
            ),
            ytmScreen(),
        )
        checkEq("covered lp/focus/menu: three tokens", rig.tokens.requests.size, 3)
        check("covered lp/focus/menu: all sequenced", rig.tokens.requests.all { it.sequenced })
        // Long-press, focus and menu all ride the tap.guidance key:
        // a long-press IS a tap held down, a focus IS a tap on a
        // field, a menu open IS a tap on the menu control — same
        // authorization class, no new keys (Universal Console).
        checkEq("covered long-press key", rig.tokens.requests[0].key, "tap.guidance")
        checkEq("covered focus key", rig.tokens.requests[1].key, "tap.guidance")
        checkEq("covered menu key", rig.tokens.requests[2].key, "tap.guidance")
    }
    // 5. Covered LAUNCH_APP → sequenced token through
    // launchAppWithToken; the plain launch path is NOT used.
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        val r = d.dispatch(
            step(TaskStepKind.LAUNCH_APP, mapOf(PlanParams.APP to "YouTube Music")),
            ytmScreen(),
        )
        check("covered launch dispatches", r is StepDispatchResult.DISPATCHED)
        checkEq("covered launch: one token", rig.tokens.requests.size, 1)
        check("covered launch: sequenced", rig.tokens.requests[0].sequenced)
        checkEq("covered launch: key", rig.tokens.requests[0].key, "app.launch")
        checkEq("covered launch: token path used", rig.launchWithTokenCalls.size, 1)
        checkEq("covered launch: plain path unused", rig.plainLaunchCalls.size, 0)
    }
    // 6. Uncovered ordinary step → CONFIRM (sequenced=false, card).
    run {
        val rig = Rig(covered = false)
        val d = rig.dispatcher()
        d.dispatch(fieldStep(), ytmScreen())
        checkEq("uncovered type: one token", rig.tokens.requests.size, 1)
        check("uncovered type: NOT sequenced", !rig.tokens.requests[0].sequenced)
    }
    // 7. Out-of-scope step (different package) → CONFIRM card naming
    // both apps, even when a cover exists for the plan's app. The
    // real PlanCover refuses out-of-scope steps at checkCover; the
    // rig mirrors that (cover.covered=false here) while
    // outOfScopeInfo names the other app — the dispatcher's
    // out-of-scope branch is what is exercised.
    run {
        val rig = Rig(covered = false, outOfScope = "com.other.app" to "Other App")
        val d = rig.dispatcher()
        d.dispatch(fieldStep(), ytmScreen())
        checkEq("out-of-scope type: one token", rig.tokens.requests.size, 1)
        check("out-of-scope type: NOT sequenced", !rig.tokens.requests[0].sequenced)
        check("out-of-scope type: flagged out-of-scope", rig.tokens.requests[0].outOfScope)
        checkEq("out-of-scope type: names other app", rig.tokens.requests[0].outOfScopeOtherApp, "Other App")
    }
    // 8. MESSAGE_SEND / CALL_PLACE never consult the cover and
    // never request a plan token.
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        d.dispatch(
            step(
                TaskStepKind.MESSAGE_SEND,
                mapOf(
                    PlanParams.CHANNEL to "whatsapp",
                    PlanParams.RECIPIENT to "Mum",
                    PlanParams.TEXT to "hello",
                ),
            ),
            ytmScreen(),
        )
        d.dispatch(
            step(TaskStepKind.CALL_PLACE, mapOf(PlanParams.RECIPIENT to "Mum")),
            ytmScreen(),
        )
        checkEq("consequential: no plan tokens", rig.tokens.requests.size, 0)
        checkEq("consequential: message ran", rig.messageCalls.size, 1)
        checkEq("consequential: call ran", rig.callCalls.size, 1)
    }
    // 9. dismissKeyboard() under cover → sequenced device.back
    // token, one Back press (AOIE Phase 2 dismiss-and-retry).
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        val ok = d.dismissKeyboard()
        check("covered dismissKeyboard succeeds", ok is StepDispatchResult.DISPATCHED)
        checkEq("covered dismiss: one token", rig.tokens.requests.size, 1)
        check("covered dismiss: sequenced", rig.tokens.requests[0].sequenced)
        checkEq("covered dismiss: key", rig.tokens.requests[0].key, "device.back")
        checkEq("covered dismiss: one back press", rig.gestures.backs, 1)
    }
    // 10. SUBMIT with keyboardAction=dismiss → dismisses, never
    // submits.
    run {
        val rig = Rig(covered = true)
        val d = rig.dispatcher()
        val r = d.dispatch(
            step(TaskStepKind.SUBMIT, mapOf(PlanParams.KEYBOARD_ACTION to "dismiss")),
            ytmScreen(),
        )
        check("dismiss intent dispatches", r is StepDispatchResult.DISPATCHED)
        checkEq("dismiss intent: no submit call", rig.gestures.submits.size, 0)
        checkEq("dismiss intent: one back press", rig.gestures.backs, 1)
    }

    println("AioeCoverSpanningTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILED: $failed")
}
