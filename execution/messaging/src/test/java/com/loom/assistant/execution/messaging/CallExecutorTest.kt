package com.loom.assistant.execution.messaging

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) —
 * CallExecutor tests.
 *
 * Drives CallSequence on the JVM with fake tree/gestures/tokens and
 * a fake clock. Covers: Phone-app happy path (launch, contact
 * resolution, call-button tap gated by a FRESH call.place token,
 * landing verification), the ACTION_DIAL fallback for dictated
 * numbers (no launch, tel: URI, call.place card carrying the
 * number), ambiguous / missing contacts, gate declines (nothing
 * dialled), missing call button, and the screen-unchanged honest
 * failure. There is deliberately NO direct-call (ACTION_CALL) path
 * to test — it is not built.
 *
 * Run (from loom-android, via test-harness/run-messaging-tests.sh):
 *   kotlinc -d out -jvm-target 17 [android fakes]
 *     [core/common: RequestOrigin] [execution/screenawareness]
 *     MessageExecutor.kt CallExecutor.kt CallExecutorTest.kt
 *   java -cp out com.loom.assistant.execution.messaging.CallExecutorTestKt
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

private fun checkContains(name: String, actual: String?, needle: String) {
    if (actual != null && actual.contains(needle, ignoreCase = true)) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$actual> to contain <$needle>")
    }
}

// ---------- fakes ----------

private class FakeClock(var now: Long = 0L) : MessageClock {
    override fun nowMs(): Long = now
}

private class FakeSleeper(private val clock: FakeClock) : MessageSleeper {
    override fun sleep(ms: Long) {
        clock.now += ms
    }
}

private class FakeTree(var current: ScreenModel? = null) : MessageTreeSource {
    var focus = false
    var snapshotCalls = 0
    override fun snapshot(): ScreenModel? {
        snapshotCalls++
        return current
    }

    override fun hasInputFocus(): Boolean = focus
}

private class FakeCallGestures : CallGestures {
    val taps = mutableListOf<ScreenElement>()
    val callTaps = mutableListOf<ScreenElement>()
    val dials = mutableListOf<String>()
    var launches = 0
    val types = mutableListOf<String>()
    var scrolls = 0
    var launchResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var tapResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var typeResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var scrollResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var callTapResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var dialResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var onTap: ((ScreenElement) -> Unit)? = null
    var onType: ((String) -> Unit)? = null
    var onCallTap: ((ScreenElement) -> Unit)? = null
    var onDial: ((String) -> Unit)? = null

    override fun launch(packageName: String, token: String?): MessageGestureAcceptance {
        launches++
        return launchResult
    }

    override fun tap(element: ScreenElement, token: String?): MessageGestureAcceptance {
        taps.add(element)
        onTap?.invoke(element)
        return tapResult
    }

    override fun type(text: String, token: String?): MessageGestureAcceptance {
        types.add(text)
        onType?.invoke(text)
        return typeResult
    }

    override fun scroll(forward: Boolean, token: String?): MessageGestureAcceptance {
        scrolls++
        return scrollResult
    }

    override fun back(token: String?): MessageGestureAcceptance =
        MessageGestureAcceptance.Accepted

    override fun callTap(element: ScreenElement, token: String?): MessageGestureAcceptance {
        callTaps.add(element)
        onCallTap?.invoke(element)
        return callTapResult
    }

    override fun dial(telUri: String, token: String?): MessageGestureAcceptance {
        dials.add(telUri)
        onDial?.invoke(telUri)
        return dialResult
    }
}

private class FakeTokens(var token: String? = "tok-1") : MessageTokenProvider {
    var calls = 0
    val keys = mutableListOf<String>()
    val actions = mutableListOf<MessageAuthAction>()
    var deniedKeys: Set<String> = emptySet()
    override fun tokenFor(action: MessageAuthAction): String? {
        calls++
        keys.add(action.key)
        actions.add(action)
        return if (action.key in deniedKeys) null else token
    }

    override fun tokenFor(action: MessageAuthAction, origin: RequestOrigin): String? =
        tokenFor(action)
}

private class FakeResolver(private val resolution: MessageAppResolution) : MessageAppResolver {
    override fun resolve(name: String): MessageAppResolution = resolution
}

// ---------- element builders ----------

private fun el(
    id: String,
    text: String? = null,
    desc: String? = null,
    cls: String = "android.widget.TextView",
    role: ElementRole = ElementRole.TEXT,
    hint: String? = null,
    x: Int = 0,
    y: Int = 0,
    w: Int = 200,
    h: Int = 80,
): ScreenElement = ScreenElement(
    id = id,
    text = text,
    contentDescription = desc,
    className = cls,
    bounds = Rect(x, y, x + w, y + h),
    role = role,
    enabled = true,
    isPassword = false,
    hintText = hint,
)

private fun model(pkg: String, vararg els: ScreenElement) = ScreenModel(pkg, els.toList())

private val TIMEOUTS = MessageTimeouts(
    foregroundMs = 1_000,
    fieldAppearMs = 1_000,
    focusMs = 1_000,
    typeVerifyMs = 500,
    rowsMs = 1_000,
    chatOpenMs = 1_000,
    sentMs = 1_000,
    landingMs = 1_000,
    pollIntervalMs = 100,
)

private fun rig(
    resolution: MessageAppResolution = MessageAppResolution.Found(
        "com.google.android.dialer", "Phone",
    ),
    tree: FakeTree = FakeTree(),
    gestures: FakeCallGestures = FakeCallGestures(),
    tokens: MessageTokenProvider = FakeTokens(),
    // MASTER F7b — the stop flag the sequence reads.
    isStopped: () -> Boolean = { false },
): CallSequence {
    val clock = FakeClock()
    return CallSequence(
        apps = FakeResolver(resolution),
        tree = tree,
        gestures = gestures,
        tokens = tokens,
        clock = clock,
        sleeper = FakeSleeper(clock),
        timeouts = TIMEOUTS,
        isStopped = isStopped,
    )
}

private const val PHONE = "com.google.android.dialer"

// Happy-path Phone screen flow: contacts (search icon) → search field
// → typed name → row → contact screen (call button) → in-call screen.
private fun phoneRig(
    tokens: MessageTokenProvider = FakeTokens(),
    // MASTER F7b — the stop flag the sequence reads.
    isStopped: () -> Boolean = { false },
): Triple<CallSequence, FakeTree, FakeCallGestures> {
    val tree = FakeTree()
    val gestures = FakeCallGestures()
    val searchIcon = el("searchIcon", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 100)
    val searchField = el("searchField", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
    val typedField = el("searchField", text = "Mum", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
    val row = el("row", text = "Mum", y = 300)
    val rowsModel = model(PHONE, typedField, row)
    val callBtn = el("callBtn", desc = "Call", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 300)
    val contactModel = model(PHONE, el("name", text = "Mum", y = 100), callBtn)
    val inCallModel = model(PHONE, el("incall", text = "Calling…", y = 100), el("endBtn", desc = "End call", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 900))
    tree.current = model(PHONE, searchIcon)
    gestures.onTap = { tapped ->
        when (tapped.id) {
            "searchIcon" -> tree.current = model(PHONE, searchField)
            "searchField" -> tree.focus = true
            "row" -> tree.current = contactModel
        }
    }
    gestures.onType = { tree.current = rowsModel }
    gestures.onCallTap = { tree.current = inCallModel }
    return Triple(rig(tree = tree, gestures = gestures, tokens = tokens, isStopped = isStopped), tree, gestures)
}

fun main() {
    // ---- 1. Phone-app happy path: resolve → launch → search → type
    // name → row → contact screen → call button (call.place) → the
    // screen changes to the in-call UI. ----
    run {
        val (seq, tree, gestures) = phoneRig()
        val out = seq.execute("Mum")
        check("phone happy path succeeds", out is CallOutcome.Success)
        checkContains(
            "phone happy path detail names the contact",
            (out as? CallOutcome.Success)?.detail,
            "Mum",
        )
        check("launch called once", gestures.launches == 1)
        check("name typed verbatim", gestures.types == listOf("Mum"))
        check("call tap called once", gestures.callTaps.size == 1)
        check("call tap hit the call button", gestures.callTaps[0].id == "callBtn")
        check("dial fallback NOT used for a name", gestures.dials.isEmpty())
        check("tree was re-inspected", tree.snapshotCalls > 5)
    }

    // ---- 2. The call.place token request carries the contact (the
    // card factory input) and is requested once for the call tap. ----
    run {
        val tokens = FakeTokens()
        val (seq, _, _) = phoneRig(tokens)
        val out = seq.execute("Mum")
        check("call-card run succeeds", out is CallOutcome.Success)
        val callActions = tokens.actions.filter { it.key == MessageActionKeys.CALL_PLACE }
        check("exactly one call.place token requested", callActions.size == 1)
        check("call.place action carries the contact", callActions[0].recipient == "Mum")
        check("call.place key requested", tokens.keys.contains(MessageActionKeys.CALL_PLACE))
    }

    // ---- 3. ACTION_DIAL fallback: a dictated number opens the
    // dialer pre-filled — no app launch, no contact lookup, tel: URI. ----
    run {
        val (seq, _, gestures) = phoneRig()
        val out = seq.execute("08031234567")
        check("dial fallback succeeds", out is CallOutcome.Success)
        checkContains(
            "dial fallback detail says the dialer opened",
            (out as? CallOutcome.Success)?.detail,
            "dialer",
        )
        check("dial fallback: no app launched", gestures.launches == 0)
        check("dial fallback: dial called once with the tel: URI", gestures.dials == listOf("tel:08031234567"))
        check("dial fallback: no call tap", gestures.callTaps.isEmpty())
    }

    // ---- 4. Dictated number with spaces/dashes normalizes to digits. ----
    run {
        val tokens = FakeTokens()
        val (seq, _, gestures) = phoneRig(tokens)
        val out = seq.execute("0803 123 4567")
        check("spaced number dials", out is CallOutcome.Success)
        check("spaced number normalizes to digits", gestures.dials == listOf("tel:08031234567"))
        val callActions = tokens.actions.filter { it.key == MessageActionKeys.CALL_PLACE }
        check("dial card carries the number", callActions.size == 1 && callActions[0].text == "08031234567")
    }

    // ---- 5. Ambiguous contact → AmbiguousContact, nothing dialled. ----
    run {
        val tree = FakeTree()
        val gestures = FakeCallGestures()
        val searchIcon = el("searchIcon", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 100)
        val searchField = el("searchField", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("searchField", text = "Alex", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val row1 = el("row1", text = "Alex Johnson", y = 300)
        val row2 = el("row2", text = "Alex Smith", y = 400)
        tree.current = model(PHONE, searchIcon)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "searchIcon" -> tree.current = model(PHONE, searchField)
                "searchField" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = model(PHONE, typedField, row1, row2) }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("Alex")
        check("ambiguous contact → AmbiguousContact", out is CallOutcome.AmbiguousContact)
        check(
            "ambiguous contact lists both candidates",
            (out as? CallOutcome.AmbiguousContact)?.candidates?.toSet() ==
                setOf("Alex Johnson", "Alex Smith"),
        )
        check("ambiguous contact: nothing dialled", gestures.callTaps.isEmpty() && gestures.dials.isEmpty())
    }

    // ---- 6. Gate honesty: the call.place card is DECLINED → nothing
    // is dialled (Phone route) and nothing opens (DIAL route). ----
    run {
        val tokens = FakeTokens().apply { deniedKeys = setOf(MessageActionKeys.CALL_PLACE) }
        val (seq, _, gestures) = phoneRig(tokens)
        val out = seq.execute("Mum")
        check("declined call card fails honestly", out is CallOutcome.Failed)
        checkContains(
            "declined call reason says not confirmed",
            (out as? CallOutcome.Failed)?.reason,
            "Not confirmed",
        )
        check("declined call card: call tap never dispatched", gestures.callTaps.isEmpty())
    }
    run {
        val tokens = FakeTokens().apply { deniedKeys = setOf(MessageActionKeys.CALL_PLACE) }
        val (seq, _, gestures) = phoneRig(tokens)
        val out = seq.execute("08031234567")
        check("declined dial card fails honestly", out is CallOutcome.Failed)
        check("declined dial card: dial never fired", gestures.dials.isEmpty())
    }

    // ---- 7. No call button on the contact screen → honest failure. ----
    run {
        val (seq, tree, gestures) = phoneRig()
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "searchIcon" -> tree.current = model(
                    PHONE,
                    el("searchField", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100),
                )
                "searchField" -> tree.focus = true
                // Contact screen with NO call button.
                "row" -> tree.current = model(PHONE, el("name", text = "Mum", y = 100))
            }
        }
        val out = seq.execute("Mum")
        check("missing call button fails honestly", out is CallOutcome.Failed)
        checkContains(
            "missing call button reason names it",
            (out as? CallOutcome.Failed)?.reason,
            "call button",
        )
        check("missing call button: no call tap", gestures.callTaps.isEmpty())
    }

    // ---- 8. The screen does NOT change after the call tap → honest
    // failure, never "Calling". ----
    run {
        val (seq, _, gestures) = phoneRig()
        gestures.onCallTap = { /* screen stays on the contact screen */ }
        val out = seq.execute("Mum")
        check("unchanged screen after call tap fails honestly", out is CallOutcome.Failed)
        checkContains(
            "unchanged screen reason names the screen",
            (out as? CallOutcome.Failed)?.reason,
            "didn't change",
        )
    }

    // ---- 9. App not found / ambiguous app / blank target. ----
    run {
        val seqNotFound = rig(resolution = MessageAppResolution.NotFound)
        check("missing app → AppNotFound", seqNotFound.execute("Mum") is CallOutcome.AppNotFound)
        val seqAmb = rig(
            resolution = MessageAppResolution.Ambiguous(listOf("Phone", "Phone Business")),
        )
        val outAmb = seqAmb.execute("Mum")
        check("ambiguous app → AmbiguousApp", outAmb is CallOutcome.AmbiguousApp)
        val (seq, _, gestures) = phoneRig()
        check("blank target fails honestly", seq.execute("   ") is CallOutcome.Failed)
        check("blank target: nothing launched", gestures.launches == 0)
    }

    // ---- 10. Bulk refusal at the executor (second line of defence). ----
    run {
        val (seq, _, gestures) = phoneRig()
        val out = seq.execute("everyone")
        check("bulk call refused", out is CallOutcome.Failed)
        checkContains(
            "bulk call refusal names one-person rule",
            (out as? CallOutcome.Failed)?.reason,
            "one person",
        )
        check("bulk call: nothing launched or dialled", gestures.launches == 0 && gestures.dials.isEmpty())
    }

    // ---- MASTER F7b: a stop aborts the sequence honestly ----
    run {
        // Dictated number: stop before the dial token — no dial.
        val tokens = FakeTokens()
        val gestures = FakeCallGestures()
        val seq = rig(tokens = tokens, gestures = gestures, isStopped = { true })
        val out = seq.execute("+2348012345678")
        check("stop before dial fails stopped", out == CallOutcome.Failed("Stopped."))
        check("stop before dial requests no token", tokens.calls == 0)
        check("stop before dial never dialled", gestures.dials.isEmpty())
    }
    run {
        // Named contact: stop before the launch token — no launch.
        val tokens = FakeTokens()
        val gestures = FakeCallGestures()
        val seq = rig(tokens = tokens, gestures = gestures, isStopped = { true })
        val out = seq.execute("Mum")
        check("stop before call launch fails stopped", out == CallOutcome.Failed("Stopped."))
        check("stop before call launch requests no token", tokens.calls == 0)
        check("stop before call launch never launches", gestures.launches == 0)
    }
    run {
        // Stop landing right after the launch token: the launch
        // already happened, but the contact-search focus never fires
        // and the run reports Stopped — the call is never placed.
        var stopped = false
        val inner = FakeTokens()
        val flipping = object : MessageTokenProvider {
            override fun tokenFor(action: MessageAuthAction): String? {
                val t = inner.tokenFor(action)
                if (action.key == MessageActionKeys.APP_LAUNCH) stopped = true
                return t
            }
        }
        val (seq, _, gestures) = phoneRig(tokens = flipping, isStopped = { stopped })
        val out = seq.execute("Mum")
        check("stop after call launch fails stopped", out == CallOutcome.Failed("Stopped."))
        check("stop after call launch requested only the launch token", inner.calls == 1)
        check("stop after call launch launched once", gestures.launches == 1)
        check("stop after call launch never tapped", gestures.taps.isEmpty())
        check("stop after call launch never placed the call", gestures.callTaps.isEmpty())
    }

    // ---- MASTER Wave 3 (F9): tier H — the Phone dialer's own
    // "Search contacts" box is found exactly, even with the
    // voice-search mic on the same screen (video frames 540–620s:
    // the box was visible every frame, LOOM said 3x it could not
    // find a way to search contacts). ----
    run {
        val tree = FakeTree()
        val gestures = FakeCallGestures()
        val box = el("searchBox", text = "Search contacts", role = ElementRole.TEXT, y = 100)
        val mic = el("mic", desc = "Voice search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val searchField = el("searchField", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("searchField", text = "Benjamin", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val row = el("row", text = "Benjamin", y = 300)
        val rowsModel = model(PHONE, typedField, row)
        val callBtn = el("callBtn", desc = "Call", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 300)
        val contactModel = model(PHONE, el("name", text = "Benjamin", y = 100), callBtn)
        val inCallModel = model(PHONE, el("incall", text = "Calling…", y = 100), el("endBtn", desc = "End call", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 900))
        tree.current = model(PHONE, box, mic)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "searchBox" -> tree.current = model(PHONE, searchField)
                "searchField" -> tree.focus = true
                "row" -> tree.current = contactModel
            }
        }
        gestures.onType = { tree.current = rowsModel }
        gestures.onCallTap = { tree.current = inCallModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("Benjamin")
        check("search-contacts box path succeeds", out is CallOutcome.Success)
        check("search-contacts box was tapped", gestures.taps.any { it.id == "searchBox" })
        check("voice mic was never tapped", gestures.taps.none { it.id == "mic" })
    }

    // ---- MASTER Wave 3 (F14b): bounded scroll — a contact that is
    // not in the list stops at the end of the list, honestly, after
    // one scroll that changes nothing (never scrolls indefinitely). ----
    run {
        val tree = FakeTree()
        val gestures = FakeCallGestures()
        val searchIcon = el("searchIcon", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 100)
        val searchField = el("searchField", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("searchField", text = "Zed", hint = "Search contacts", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val rowsModel = model(PHONE, typedField, el("amy", text = "Amy", y = 300), el("bob", text = "Bob", y = 400))
        tree.current = model(PHONE, searchIcon)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "searchIcon" -> tree.current = model(PHONE, searchField)
                "searchField" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = rowsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("Zed")
        check("missing contact fails honestly", out is CallOutcome.Failed)
        checkContains(
            "missing contact names the end of the list",
            (out as? CallOutcome.Failed)?.reason,
            "reached the end",
        )
        check("missing contact scrolled once", gestures.scrolls == 1)
    }

    println("passed=$passed failed=$failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        println("$failed TESTS FAILED")
    }
}
