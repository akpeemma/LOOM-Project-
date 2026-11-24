package com.loom.assistant.execution.messaging

import android.graphics.Rect
import com.loom.assistant.core.common.MessageChannel
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) —
 * MessageExecutor tests.
 *
 * Drives MessageSequence on the JVM with fake tree/gestures/tokens and
 * a fake clock (poll timeouts are deterministic: the fake sleeper
 * advances the fake clock). Covers: WhatsApp + SMS happy paths
 * (launch, contact resolution, compose + send, send verification),
 * exact-words enforcement (verbatim type, verifyTypedText, card
 * action carrying recipient + text), the send tap gated by a FRESH
 * message.send token (decline → nothing sent), ambiguous / missing
 * contacts, bulk refusal, no-submit discipline (there is no submit
 * sink to call — the send tap is the only send path), and honest
 * partial verification (field-cleared vs bubble sighting).
 *
 * Run (from loom-android, via test-harness/run-messaging-tests.sh):
 *   kotlinc -d out -jvm-target 17 [android fakes]
 *     [core/common: RequestOrigin, MessageChannel] [execution/screenawareness]
 *     MessageExecutor.kt MessageExecutorTest.kt
 *   java -cp out com.loom.assistant.execution.messaging.MessageExecutorTestKt
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

private class FakeGestures : MessageGestures {
    val taps = mutableListOf<ScreenElement>()
    val sendTaps = mutableListOf<ScreenElement>()
    var launches = 0
    val types = mutableListOf<String>()
    var scrolls = 0
    var backs = 0
    var launchResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var tapResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var typeResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var scrollResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var backResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var sendTapResult: MessageGestureAcceptance = MessageGestureAcceptance.Accepted
    var onTap: ((ScreenElement) -> Unit)? = null
    var onType: ((String) -> Unit)? = null
    var onSendTap: ((ScreenElement) -> Unit)? = null

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

    override fun back(token: String?): MessageGestureAcceptance {
        backs++
        return backResult
    }

    override fun sendTap(element: ScreenElement, token: String?): MessageGestureAcceptance {
        sendTaps.add(element)
        onSendTap?.invoke(element)
        return sendTapResult
    }
}

private class FakeTokens(var token: String? = "tok-1") : MessageTokenProvider {
    var calls = 0
    val keys = mutableListOf<String>()
    /** Full actions, in request order — the send-card test reads recipient/text off these. */
    val actions = mutableListOf<MessageAuthAction>()
    /** Keys that must be denied (null token) — the gate-decline tests use this. */
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
    var calls = 0
    override fun resolve(name: String): MessageAppResolution {
        calls++
        return resolution
    }
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
        "com.whatsapp", "WhatsApp",
    ),
    tree: FakeTree = FakeTree(),
    gestures: FakeGestures = FakeGestures(),
    tokens: MessageTokenProvider = FakeTokens(),
    // MASTER F7b — the stop flag the sequence reads.
    isStopped: () -> Boolean = { false },
): MessageSequence {
    val clock = FakeClock()
    return MessageSequence(
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

private const val WA = "com.whatsapp"
private const val SMS_PKG = "com.google.android.apps.messaging"

// Happy-path WhatsApp screen flow: chat list (search icon) → search
// field → typed name → row → chat (compose field) → typed message →
// send button → sent (field cleared + bubble).
private fun whatsappRig(
    tokens: MessageTokenProvider = FakeTokens(),
    // MASTER F7b — the stop flag the sequence reads.
    isStopped: () -> Boolean = { false },
): Triple<MessageSequence, FakeTree, FakeGestures> {
    val tree = FakeTree()
    val gestures = FakeGestures()
    val searchIcon = el("searchIcon", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 100)
    val searchField = el("searchField", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
    val typedNameField = el("searchField", text = "Mum", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
    val row = el("row", text = "Mum", y = 300)
    val rowsModel = model(WA, typedNameField, row)
    val compose = el("compose", hint = "Message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900)
    val chatModel = model(WA, compose)
    val typedCompose = el("compose", text = "hello there", hint = "Message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900)
    val sendBtn = el("send", desc = "Send", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 900)
    val typedModel = model(WA, typedCompose, sendBtn)
    val clearedCompose = el("compose", hint = "Message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900)
    val bubble = el("bubble", text = "hello there", y = 500)
    val sentModel = model(WA, clearedCompose, bubble)
    tree.current = model(WA, searchIcon)
    gestures.onTap = { tapped ->
        when (tapped.id) {
            "searchIcon" -> tree.current = model(WA, searchField)
            "searchField" -> tree.focus = true
            "row" -> tree.current = chatModel
            "compose" -> tree.focus = true
        }
    }
    gestures.onType = { typed ->
        tree.current = if (typed == "Mum") rowsModel else typedModel
    }
    gestures.onSendTap = { tree.current = sentModel }
    return Triple(rig(tree = tree, gestures = gestures, tokens = tokens, isStopped = isStopped), tree, gestures)
}

fun main() {
    // ---- 1. WhatsApp happy path: resolve → launch → search icon →
    // field → focus → type name → row → chat → compose → type exact
    // text → verify → send tap (message.send) → bubble verified ----
    run {
        val (seq, tree, gestures) = whatsappRig()
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("whatsapp happy path succeeds", out is MessageOutcome.Success)
        checkContains(
            "happy path detail names recipient + exact text",
            (out as? MessageOutcome.Success)?.detail,
            "hello there",
        )
        checkContains(
            "happy path detail names recipient",
            (out as? MessageOutcome.Success)?.detail,
            "Mum",
        )
        checkContains(
            "happy path detail says the message is visible in the chat",
            (out as? MessageOutcome.Success)?.detail,
            "visible in the chat",
        )
        check("launch called once", gestures.launches == 1)
        check("types are verbatim: name then exact text", gestures.types == listOf("Mum", "hello there"))
        check("send tap called once", gestures.sendTaps.size == 1)
        check("send tap hit the send button", gestures.sendTaps[0].id == "send")
        check("tree was re-inspected", tree.snapshotCalls > 5)
    }

    // ---- 2. The message.send token request carries the recipient +
    // the exact text (the card factory input) and is requested once. ----
    run {
        val tokens = FakeTokens()
        val (seq, _, _) = whatsappRig(tokens)
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("send-card run succeeds", out is MessageOutcome.Success)
        val sendActions = tokens.actions.filter { it.key == MessageActionKeys.MESSAGE_SEND }
        check("exactly one message.send token requested", sendActions.size == 1)
        check("message.send action carries the recipient", sendActions[0].recipient == "Mum")
        check("message.send action carries the exact text", sendActions[0].text == "hello there")
        check("message.send key requested", tokens.keys.contains(MessageActionKeys.MESSAGE_SEND))
    }

    // ---- 3. SMS happy path via the "Start chat" tier (Messages):
    // chat list (Start chat button) → To field → row → compose
    // (hint "Text message") → send. One executor, app swapped. ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val startChat = el("startChat", text = "Start chat", cls = "android.widget.Button", role = ElementRole.BUTTON, y = 100)
        val toField = el("toField", hint = "To", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedTo = el("toField", text = "Mum", hint = "To", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val row = el("row", text = "Mum", y = 300)
        val rowsModel = model(SMS_PKG, typedTo, row)
        val compose = el("compose", hint = "Text message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900)
        val chatModel = model(SMS_PKG, compose)
        val typedCompose = el("compose", text = "on my way", hint = "Text message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900)
        val sendBtn = el("send", desc = "Send SMS", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 900)
        val typedModel = model(SMS_PKG, typedCompose, sendBtn)
        val clearedCompose = el("compose", hint = "Text message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900)
        val bubble = el("bubble", text = "on my way", y = 500)
        val sentModel = model(SMS_PKG, clearedCompose, bubble)
        tree.current = model(SMS_PKG, startChat)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "startChat" -> tree.current = model(SMS_PKG, toField)
                "toField" -> tree.focus = true
                "row" -> tree.current = chatModel
                "compose" -> tree.focus = true
            }
        }
        gestures.onType = { typed ->
            tree.current = if (typed == "Mum") rowsModel else typedModel
        }
        gestures.onSendTap = { tree.current = sentModel }
        val seq = rig(
            resolution = MessageAppResolution.Found(SMS_PKG, "Messages"),
            tree = tree,
            gestures = gestures,
        )
        val out = seq.execute(MessageChannel.SMS, "Mum", "on my way")
        check("sms happy path succeeds", out is MessageOutcome.Success)
        check("sms send tap called once", gestures.sendTaps.size == 1)
        check("sms types are verbatim", gestures.types == listOf("Mum", "on my way"))
    }

    // ---- 4. Exact-words: the field exposes DIFFERENT text after
    // typing → honest failure, and the send tap never happens. ----
    run {
        val (seq, tree, gestures) = whatsappRig()
        gestures.onType = { typed ->
            tree.current = if (typed == "Mum") {
                model(WA, el("searchField", text = "Mum", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100), el("row", text = "Mum", y = 300))
            } else {
                // The field garbled the text — "hello there" typed, "hello ther" landed.
                model(
                    WA,
                    el("compose", text = "hello ther", hint = "Message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900),
                    el("send", desc = "Send", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 900),
                )
            }
        }
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("mistyped text fails honestly", out is MessageOutcome.Failed)
        checkContains(
            "mistyped reason names the field",
            (out as? MessageOutcome.Failed)?.reason,
            "didn't appear in the field",
        )
        check("mistyped text: no send tap", gestures.sendTaps.isEmpty())
    }

    // ---- 5. Ambiguous contact: two distinct names match, no exact
    // match exists → AmbiguousContact, nothing opened, nothing sent. ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchIcon = el("searchIcon", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 100)
        val searchField = el("searchField", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("searchField", text = "Alex", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val row1 = el("row1", text = "Alex Johnson", y = 300)
        val row2 = el("row2", text = "Alex Smith", y = 400)
        tree.current = model(WA, searchIcon)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "searchIcon" -> tree.current = model(WA, searchField)
                "searchField" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = model(WA, typedField, row1, row2) }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute(MessageChannel.WHATSAPP, "Alex", "hi")
        check("ambiguous contact → AmbiguousContact", out is MessageOutcome.AmbiguousContact)
        check(
            "ambiguous contact lists both candidates",
            (out as? MessageOutcome.AmbiguousContact)?.candidates?.toSet() ==
                setOf("Alex Johnson", "Alex Smith"),
        )
        check("ambiguous contact: no chat opened, no send", gestures.sendTaps.isEmpty() && gestures.taps.none { it.id == "row1" || it.id == "row2" })
    }

    // ---- 6. Contact not found → honest failure naming the name. ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchIcon = el("searchIcon", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.ICON, y = 100)
        val searchField = el("searchField", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("searchField", text = "Zebedee", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        tree.current = model(WA, searchIcon)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "searchIcon" -> tree.current = model(WA, searchField)
                "searchField" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = model(WA, typedField) }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute(MessageChannel.WHATSAPP, "Zebedee", "hi")
        check("missing contact fails honestly", out is MessageOutcome.Failed)
        checkContains(
            "missing contact reason names the name",
            (out as? MessageOutcome.Failed)?.reason,
            "Zebedee",
        )
        check("missing contact: no send tap", gestures.sendTaps.isEmpty())
    }

    // ---- 7. Gate honesty: the message.send card is DECLINED → the
    // message is typed but NEVER sent, and the chat is left un-sent. ----
    run {
        val tokens = FakeTokens().apply { deniedKeys = setOf(MessageActionKeys.MESSAGE_SEND) }
        val (seq, _, gestures) = whatsappRig(tokens)
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("declined send card fails honestly", out is MessageOutcome.Failed)
        checkContains(
            "declined send reason says not confirmed",
            (out as? MessageOutcome.Failed)?.reason,
            "Not confirmed",
        )
        check("declined send card: send tap never dispatched", gestures.sendTaps.isEmpty())
        check("declined send card: text WAS typed (left un-sent)", gestures.types.contains("hello there"))
    }

    // ---- 8. Bulk refusal (executor second line of defence): the
    // parse-time refusal is first, but the executor also refuses
    // "everyone" before resolving or launching anything. ----
    run {
        val (seq, _, gestures) = whatsappRig()
        val out = seq.execute(MessageChannel.WHATSAPP, "everyone", "hi")
        check("bulk recipient refused", out is MessageOutcome.Failed)
        checkContains(
            "bulk refusal names one-person rule",
            (out as? MessageOutcome.Failed)?.reason,
            "one person",
        )
        check("bulk refusal: nothing launched", gestures.launches == 0)
        check("bulk refusal: nothing typed", gestures.types.isEmpty())
    }

    // ---- 9. Blank text / blank recipient → honest failure, no launch. ----
    run {
        val (seq, _, gestures) = whatsappRig()
        val outBlankText = seq.execute(MessageChannel.WHATSAPP, "Mum", "   ")
        check("blank text fails honestly", outBlankText is MessageOutcome.Failed)
        val outBlankTo = seq.execute(MessageChannel.WHATSAPP, "   ", "hi")
        check("blank recipient fails honestly", outBlankTo is MessageOutcome.Failed)
        check("blank inputs: nothing launched", gestures.launches == 0)
    }

    // ---- 10. Send button missing after typing → honest failure, no send. ----
    run {
        val (seq, tree, gestures) = whatsappRig()
        gestures.onType = { typed ->
            tree.current = if (typed == "Mum") {
                model(WA, el("searchField", text = "Mum", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100), el("row", text = "Mum", y = 300))
            } else {
                // Typed, but no send button ever appears.
                model(WA, el("compose", text = "hello there", hint = "Message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900))
            }
        }
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("missing send button fails honestly", out is MessageOutcome.Failed)
        checkContains(
            "missing send button reason names it",
            (out as? MessageOutcome.Failed)?.reason,
            "send button",
        )
        check("missing send button: no send tap", gestures.sendTaps.isEmpty())
    }

    // ---- 11. Honest PARTIAL verification: the field cleared but the
    // sent bubble is not readable in the tree → Success that says so
    // plainly (field-cleared check, not a bubble sighting). ----
    run {
        val (seq, tree, gestures) = whatsappRig()
        gestures.onSendTap = {
            tree.current = model(
                WA,
                el("compose", hint = "Message", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 900),
            )
        }
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("field-cleared still succeeds", out is MessageOutcome.Success)
        checkContains(
            "field-cleared detail says so plainly",
            (out as? MessageOutcome.Success)?.detail,
            "field-cleared",
        )
        check("field-cleared detail does NOT claim a bubble sighting", (out as? MessageOutcome.Success)?.detail?.contains("visible in the chat") != true)
    }

    // ---- 12. Send tap refused by the sink → honest failure. ----
    run {
        val (seq, _, gestures) = whatsappRig()
        gestures.sendTapResult = MessageGestureAcceptance.Refused("gate closed")
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("refused send tap fails honestly", out is MessageOutcome.Failed)
        checkContains(
            "refused send tap reason names the refusal",
            (out as? MessageOutcome.Failed)?.reason,
            "Send refused",
        )
    }

    // ---- 13. App not found / ambiguous app. ----
    run {
        val seqNotFound = rig(resolution = MessageAppResolution.NotFound)
        val outNotFound = seqNotFound.execute(MessageChannel.WHATSAPP, "Mum", "hi")
        check("missing app → AppNotFound", outNotFound is MessageOutcome.AppNotFound)
        val seqAmb = rig(
            resolution = MessageAppResolution.Ambiguous(listOf("WhatsApp", "WhatsApp Business")),
        )
        val outAmb = seqAmb.execute(MessageChannel.WHATSAPP, "Mum", "hi")
        check("ambiguous app → AmbiguousApp", outAmb is MessageOutcome.AmbiguousApp)
        check(
            "ambiguous app lists candidates",
            (outAmb as? MessageOutcome.AmbiguousApp)?.candidates ==
                listOf("WhatsApp", "WhatsApp Business"),
        )
    }

    // ---- 14. No-submit discipline: the executor has no submit sink —
    // the only send path is the message.send-gated send tap, and the
    // token log proves the send step requested message.send, never
    // device.submit. ----
    run {
        val tokens = FakeTokens()
        val (seq, _, gestures) = whatsappRig(tokens)
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello there")
        check("no-submit run succeeds", out is MessageOutcome.Success)
        check("no device.submit token was ever requested", !tokens.keys.contains("device.submit"))
        check("send step requested message.send", tokens.keys.contains(MessageActionKeys.MESSAGE_SEND))
        check("exactly one send tap, zero submits (no such sink)", gestures.sendTaps.size == 1)
    }

    // ---- MASTER F7b: a stop aborts the sequence honestly ----
    run {
        val tokens = FakeTokens()
        val gestures = FakeGestures()
        val seq = rig(tokens = tokens, gestures = gestures, isStopped = { true })
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello mum")
        check("stop before launch fails stopped", out == MessageOutcome.Failed("Stopped."))
        check("stop before launch requests no token", tokens.calls == 0)
        check("stop before launch never launches", gestures.launches == 0)
    }
    run {
        // Stop landing right after the launch token: the launch
        // already happened, but the chat-search focus never fires
        // and the run reports Stopped — the message is never typed
        // or sent.
        var stopped = false
        val inner = FakeTokens()
        val flipping = object : MessageTokenProvider {
            override fun tokenFor(action: MessageAuthAction): String? {
                val t = inner.tokenFor(action)
                if (action.key == MessageActionKeys.APP_LAUNCH) stopped = true
                return t
            }
        }
        val (seq, _, gestures) = whatsappRig(tokens = flipping, isStopped = { stopped })
        val out = seq.execute(MessageChannel.WHATSAPP, "Mum", "hello mum")
        check("stop after launch fails stopped", out == MessageOutcome.Failed("Stopped."))
        check("stop after launch requested only the launch token", inner.calls == 1)
        check("stop after launch launched once", gestures.launches == 1)
        check("stop after launch never tapped", gestures.taps.isEmpty())
        check("stop after launch never typed", gestures.types.isEmpty())
        check("stop after launch never sent", gestures.sendTaps.isEmpty())
    }

    println("passed=$passed failed=$failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        println("$failed TESTS FAILED")
    }
}
