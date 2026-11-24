package com.loom.assistant.execution.search

import android.graphics.Rect
import com.loom.assistant.core.common.KeyboardAction
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.NoChangeTracker
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SearchScreenPredicates
import com.loom.assistant.execution.screenawareness.StopLimits
import com.loom.assistant.execution.screenawareness.SubmitVerification
import com.loom.assistant.execution.screenawareness.SubmitVerifier
import com.loom.assistant.execution.screenawareness.TargetAliases
import com.loom.assistant.execution.screenawareness.VideoPresence

/**
 * App-aware execution (2026-11-07, David's directive §1) — keyboard /
 * submit engine tests.
 *
 * Every test asserts an executed OUTCOME, never "a command was
 * issued": the chain must prove the search ran (results present,
 * keyboard gone) before anything claims success. Drives the
 * [SubmitChain] directly and the [SearchSequence] end to end, with
 * fake tree / gestures / tokens and a no-op sleeper.
 *
 * Run (from loom-android), see run-search-tests.sh `keyboard`:
 *   kotlinc -d out -jvm-target 17 [android fakes]
 *     [execution/screenawareness] [core/common: RequestOrigin,
 *     KeyboardAction] SearchExecutor.kt KeyboardAction.kt
 *     SearchKeyboardTest.kt
 *   java -cp out com.loom.assistant.execution.search.SearchKeyboardTestKt
 */
private var kPassed = 0
private var kFailed = 0

private fun kCheck(name: String, actual: Boolean) {
    if (actual) {
        kPassed++
    } else {
        kFailed++
        println("FAIL: $name")
    }
}

private fun kCheckContains(name: String, actual: String?, needle: String) {
    if (actual != null && actual.contains(needle, ignoreCase = true)) {
        kPassed++
    } else {
        kFailed++
        println("FAIL: $name — expected <$actual> to contain <$needle>")
    }
}

// ---------- fakes ----------

private class KFakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

private class KNoopSleeper : Sleeper {
    override fun sleep(ms: Long) {}
}

private class KFakeTree(var current: ScreenModel? = null) : SearchTreeSource {
    var focus = false
    override fun snapshot(): ScreenModel? = current
    override fun hasInputFocus(): Boolean = focus
}

private class KFakeGestures : SearchGestures {
    val taps = mutableListOf<ScreenElement>()
    val types = mutableListOf<String>()
    var submits = 0
    var keySubmits = 0
    var submitResult: GestureAcceptance = GestureAcceptance.Accepted
    var keyResult: GestureAcceptance = GestureAcceptance.Accepted
    var tapResult: GestureAcceptance = GestureAcceptance.Accepted
    var onTap: ((ScreenElement) -> Unit)? = null
    var onType: ((String) -> Unit)? = null
    var onSubmit: (() -> Unit)? = null
    var onKeySubmit: (() -> Unit)? = null

    override fun launch(packageName: String, token: String?): GestureAcceptance =
        GestureAcceptance.Accepted

    override fun tap(element: ScreenElement, token: String?): GestureAcceptance {
        taps.add(element)
        onTap?.invoke(element)
        return tapResult
    }

    override fun type(text: String, token: String?): GestureAcceptance {
        types.add(text)
        onType?.invoke(text)
        return typeResult
    }

    private var typeResult: GestureAcceptance = GestureAcceptance.Accepted

    override fun submit(token: String?): GestureAcceptance {
        submits++
        onSubmit?.invoke()
        return submitResult
    }

    override fun submitViaKey(token: String?): GestureAcceptance {
        keySubmits++
        onKeySubmit?.invoke()
        return keyResult
    }

    override fun scroll(forward: Boolean, token: String?): GestureAcceptance =
        GestureAcceptance.Accepted

    override fun back(token: String?): GestureAcceptance = GestureAcceptance.Accepted
}

private class KFakeTokens(var token: String? = "tok-1") : TokenProvider {
    val keys = mutableListOf<String>()
    override fun tokenFor(action: SearchAuthAction): String? {
        keys.add(action.key)
        return token
    }
}

private class KFakeResolver : AppResolver {
    override fun resolve(name: String): AppResolution =
        AppResolution.Found("com.google.android.youtube", "YouTube")
}

// ---------- element builders ----------

private fun kEl(
    id: String,
    text: String? = null,
    desc: String? = null,
    cls: String = "android.widget.TextView",
    role: ElementRole = ElementRole.TEXT,
    hint: String? = null,
    focused: Boolean = false,
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
    focused = focused,
)

private fun kModel(
    pkg: String,
    vararg els: ScreenElement,
    keyboardVisible: Boolean = false,
) = ScreenModel(pkg, els.toList(), keyboardVisible = keyboardVisible)

private const val KYT = "com.google.android.youtube"

private val KTIMEOUTS = SearchTimeouts(
    foregroundMs = 1_000,
    fieldAppearMs = 1_000,
    focusMs = 1_000,
    typeVerifyMs = 500,
    resultsMs = 1_000,
    landingMs = 1_000,
    pollIntervalMs = 100,
    submitSettleMs = 0,
)

private fun kRig(
    tree: KFakeTree = KFakeTree(),
    gestures: KFakeGestures = KFakeGestures(),
    tokens: KFakeTokens = KFakeTokens(),
): SearchSequence {
    val clock = KFakeClock()
    return SearchSequence(
        apps = KFakeResolver(),
        tree = tree,
        gestures = gestures,
        tokens = tokens,
        clock = clock,
        sleeper = object : Sleeper {
            override fun sleep(ms: Long) {
                clock.now += ms
            }
        },
        timeouts = KTIMEOUTS,
    )
}

/** Chain over a mutable world: snapshot reads [current], taps/submits move it. */
private fun kChain(
    world: KFakeTree,
    gestures: KFakeGestures,
    tokens: KFakeTokens = KFakeTokens(),
    tapTarget: (SubmitMechanism) -> ScreenElement? = { null },
): SubmitChain = SubmitChain(
    gestures = gestures,
    tokenFor = { tokens.tokenFor(SearchAuthAction(SearchActionKeys.DEVICE_SUBMIT, "Submit")) },
    snapshot = { world.snapshot() },
    tapTarget = tapTarget,
    sleeper = KNoopSleeper(),
    waitAfterDispatchMs = 0,
)

private fun typedWorld(
    query: String,
    keyboardVisible: Boolean = true,
    focused: Boolean = true,
): ScreenModel {
    val field = kEl(
        "field", text = query, desc = "Search", cls = "android.widget.EditText",
        role = ElementRole.INPUT, focused = focused, y = 100,
    )
    return kModel(KYT, field, keyboardVisible = keyboardVisible)
}

private fun resultsWorld(query: String): ScreenModel {
    val field = kEl(
        "field", text = query, desc = "Search", cls = "android.widget.EditText",
        role = ElementRole.INPUT, focused = false, y = 100,
    )
    val r1 = kEl("r1", text = "Cats compilation video", y = 300)
    return kModel(KYT, field, r1, keyboardVisible = false)
}

fun main() {
    // ---- (a) IME Enter accepted but screen unchanged + keyboard still
    // open + focus kept → NotExecuted, chain proceeds to mechanism 2 ----
    run {
        val world = KFakeTree(typedWorld("cats"))
        val gestures = KFakeGestures()
        val chain = kChain(world, gestures)
        val result = chain.submit(
            "cats",
            listOf(SubmitMechanism.IME_ENTER, SubmitMechanism.KEYBOARD_ACTION_KEY),
            world.current!!,
        )
        kCheck("(a) accepted-but-unchanged is NotExecuted", result.verification is SubmitVerification.NotExecuted)
        kCheck("(a) chain proceeded to mechanism 2", result.attempts.size == 2)
        kCheck("(a) first attempt was IME_ENTER", result.attempts[0].mechanism == SubmitMechanism.IME_ENTER)
        kCheck("(a) second attempt was KEYBOARD_ACTION_KEY", result.attempts[1].mechanism == SubmitMechanism.KEYBOARD_ACTION_KEY)
        kCheck("(a) no success claimed", result.verification !is SubmitVerification.Executed)
    }

    // ---- (b) IME Enter refused → mechanism 2 tried ----
    run {
        val world = KFakeTree(typedWorld("cats"))
        val gestures = KFakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        gestures.onKeySubmit = { world.current = resultsWorld("cats") }
        val chain = kChain(world, gestures)
        val result = chain.submit("cats", SubmitChain.DEFAULT_ORDER, world.current!!)
        kCheck("(b) refused IME leads to Executed via key", result.verification is SubmitVerification.Executed)
        kCheck("(b) exactly two mechanisms tried", result.attempts.size == 2)
        kCheck("(b) second attempt was the keyboard key", result.attempts[1].mechanism == SubmitMechanism.KEYBOARD_ACTION_KEY)
        kCheck("(b) key submit was actually called", gestures.keySubmits == 1)
    }

    // ---- (c) keyboard key accepted + results appear + keyboard gone →
    // Executed, chain stops, log shows exactly the mechanisms tried ----
    run {
        val world = KFakeTree(typedWorld("cats"))
        val gestures = KFakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        gestures.onKeySubmit = { world.current = resultsWorld("cats") }
        val chain = kChain(world, gestures)
        val result = chain.submit("cats", SubmitChain.DEFAULT_ORDER, world.current!!)
        kCheck("(c) key-accepted + results = Executed", result.verification is SubmitVerification.Executed)
        kCheck("(c) chain stopped after mechanism 2", result.attempts.size == 2)
        kCheck(
            "(c) log shows exactly IME_ENTER then KEYBOARD_ACTION_KEY",
            result.attempts.map { it.mechanism } == listOf(
                SubmitMechanism.IME_ENTER,
                SubmitMechanism.KEYBOARD_ACTION_KEY,
            ),
        )
        kCheck("(c) suggestion/button never attempted", gestures.taps.isEmpty())
    }

    // ---- (d) all mechanisms fail → Failed with per-mechanism reasons,
    // no success claimed (sequence level) ----
    run {
        val world = KFakeTree(typedWorld("cats", keyboardVisible = false, focused = false))
        val gestures = KFakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        gestures.keyResult = GestureAcceptance.Refused("No Search, Go or Done key found on the keyboard.")
        val chain = kChain(world, gestures)
        val result = chain.submit("cats", SubmitChain.DEFAULT_ORDER, world.current!!)
        kCheck("(d) all-fail chain never claims Executed", result.verification !is SubmitVerification.Executed)
        kCheck("(d) all four mechanisms attempted", result.attempts.size == 4)
        kCheck("(d) every attempt refused", result.attempts.all { it.dispatch is GestureAcceptance.Refused })
        kCheck(
            "(d) per-mechanism reasons recorded",
            result.attempts.map { it.mechanism } == SubmitChain.DEFAULT_ORDER,
        )
    }
    run {
        // Sequence level: submit refused, no key, no suggestion, no button.
        val tree = KFakeTree()
        val gestures = KFakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        gestures.keyResult = GestureAcceptance.Refused("No Search, Go or Done key found on the keyboard.")
        val searchBtn = kEl("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = kEl("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = kEl("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        tree.current = kModel(KYT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = kModel(KYT, field)
                "field" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = kModel(KYT, typedField) }
        val out = kRig(tree = tree, gestures = gestures).execute("YouTube", "cats")
        kCheck("(d) sequence fails honestly", out is SearchOutcome.Failed)
        val reason = (out as? SearchOutcome.Failed)?.reason
        kCheckContains("(d) reason names IME_ENTER", reason, "IME_ENTER")
        kCheckContains("(d) reason names KEYBOARD_ACTION_KEY", reason, "KEYBOARD_ACTION_KEY")
        kCheckContains("(d) reason names SUGGESTION_TAP", reason, "SUGGESTION_TAP")
        kCheckContains("(d) reason names SEARCH_BUTTON", reason, "SEARCH_BUTTON")
        kCheckContains("(d) reason carries the IME refusal", reason, "IME Enter not supported")
    }

    // ---- (e) verifyTypedText reads the FOCUSED field, not the first ----
    run {
        // First field has other text; the focused field has the query → passes.
        val tree = KFakeTree()
        val gestures = KFakeGestures()
        val searchBtn = kEl("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = kEl("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", y = 100)
        val otherField = kEl("other", text = "other text", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val focusedField = kEl("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", focused = true, y = 100)
        val result1 = kEl("r1", text = "Cats compilation video", y = 300)
        val results = kModel(KYT, focusedField.copy(focused = false), result1, keyboardVisible = false)
        val detail = kModel(KYT, kEl("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = kModel(KYT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = kModel(KYT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = kModel(KYT, otherField, focusedField) }
        gestures.onSubmit = { tree.current = results }
        val out = kRig(tree = tree, gestures = gestures).execute("YouTube", "cats")
        kCheck("(e) two fields, focused has query → succeeds", out is SearchOutcome.Success)
    }
    run {
        // Focused field text differs from the query → honest failure.
        val tree = KFakeTree()
        val gestures = KFakeGestures()
        val searchBtn = kEl("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = kEl("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", y = 100)
        val otherField = kEl("other", text = "cats", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val focusedField = kEl("field", text = "dogs", desc = "Search", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", focused = true, y = 100)
        tree.current = kModel(KYT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = kModel(KYT, field)
                "field" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = kModel(KYT, otherField, focusedField) }
        val out = kRig(tree = tree, gestures = gestures).execute("YouTube", "cats")
        kCheck("(e) focused-field text differs → fails", out is SearchOutcome.Failed)
        kCheckContains("(e) reason names the focused field", (out as? SearchOutcome.Failed)?.reason, "focused field")
        kCheck("(e) failed before any submit", gestures.submits == 0)
    }

    // ---- (f) suggestion tap is a submit mechanism, not a result tap;
    // result tap happens only after verified Executed ----
    run {
        val tree = KFakeTree()
        val gestures = KFakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        gestures.keyResult = GestureAcceptance.Refused("No Search, Go or Done key found on the keyboard.")
        val searchBtn = kEl("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = kEl("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = kEl("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val suggestion = kEl("sug", text = "cats videos", y = 200)
        val result1 = kEl("r1", text = "Cats compilation video", y = 300)
        val results = kModel(KYT, typedField.copy(focused = false), result1, keyboardVisible = false)
        val detail = kModel(KYT, kEl("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = kModel(KYT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = kModel(KYT, field)
                "field" -> tree.focus = true
                "sug" -> tree.current = results
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = kModel(KYT, typedField.copy(focused = true), suggestion, keyboardVisible = true) }
        val out = kRig(tree = tree, gestures = gestures).execute("YouTube", "cats")
        kCheck("(f) suggestion-as-submit succeeds", out is SearchOutcome.Success)
        kCheck(
            "(f) tap order: button, field, suggestion, result — suggestion was the submit, not the result",
            gestures.taps.map { it.id } == listOf("btn", "field", "sug", "r1"),
        )
    }
    run {
        // Submit never executes → no result is ever tapped. The typed
        // screen has no result rows at all (results only exist after
        // a search runs) — nothing is tapped as a pseudo-result.
        val tree = KFakeTree()
        val gestures = KFakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        gestures.keyResult = GestureAcceptance.Refused("No Search, Go or Done key found on the keyboard.")
        val searchBtn = kEl("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = kEl("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = kEl("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        tree.current = kModel(KYT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = kModel(KYT, field)
                "field" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = kModel(KYT, typedField, keyboardVisible = true) }
        val out = kRig(tree = tree, gestures = gestures).execute("YouTube", "cats")
        kCheck("(f) unverified submit → fails, nothing tapped after focus", out is SearchOutcome.Failed)
        kCheck("(f) only the focus taps happened", gestures.taps.map { it.id } == listOf("btn", "field"))
    }

    // ---- (g) KeyboardAction intents map distinctly ----
    run {
        val intents = KeyboardAction.entries.map { it.intent }
        kCheck("(g) four distinct keyboard intents", intents.toSet().size == 4)
        kCheck("(g) SUBMIT is not CONFIRM", KeyboardAction.SUBMIT.intent != KeyboardAction.CONFIRM.intent)
        kCheck("(g) SUBMIT is not DISMISS", KeyboardAction.SUBMIT.intent != KeyboardAction.DISMISS.intent)
        kCheck("(g) CONFIRM is not DISMISS", KeyboardAction.CONFIRM.intent != KeyboardAction.DISMISS.intent)
        kCheck("(g) NEWLINE is its own intent", KeyboardAction.NEWLINE.intent == "newline")
        kCheck("(g) four distinct submit mechanisms", SubmitMechanism.entries.size == 4)
        kCheck(
            "(g) default order is IME, key, suggestion, button",
            SubmitChain.DEFAULT_ORDER == listOf(
                SubmitMechanism.IME_ENTER,
                SubmitMechanism.KEYBOARD_ACTION_KEY,
                SubmitMechanism.SUGGESTION_TAP,
                SubmitMechanism.SEARCH_BUTTON,
            ),
        )
    }

    // ---- (h) denied token stops the chain — no further mechanism ----
    run {
        val world = KFakeTree(typedWorld("cats"))
        val gestures = KFakeGestures()
        val tokens = KFakeTokens(token = null)
        val chain = kChain(world, gestures, tokens)
        val result = chain.submit("cats", SubmitChain.DEFAULT_ORDER, world.current!!)
        kCheck("(h) token denied → NotExecuted", result.verification is SubmitVerification.NotExecuted)
        kCheck("(h) chain stopped after the first mechanism", result.attempts.size == 1)
        kCheck("(h) nothing was dispatched", gestures.submits == 0 && gestures.keySubmits == 0 && gestures.taps.isEmpty())
        kCheckContains("(h) reason says not confirmed", (result.verification as? SubmitVerification.NotExecuted)?.reason, "Not confirmed")
    }

    // ---- (i) Universal Console (2026-11-07): keyboards differ.
    // A keyboard that exposes its own Search / Go / Enter key (and
    // refuses IME Enter) still submits — the full sequence recovers
    // through the key, end to end, and opens the result. The chain
    // treats the key as ONE mechanism whatever label the keyboard
    // carries (Search, Go, Done, Send, Next, Enter are resolved to
    // the same mechanism by the service's key lookup); the
    // distinction that matters here is IME-refused vs key-accepted,
    // and a key-only order still executes.
    run {
        val tree = KFakeTree()
        val gestures = KFakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        gestures.onKeySubmit = { tree.current = resultsWorld("cats") }
        val searchBtn = kEl("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = kEl("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = kEl("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val detail = kModel(KYT, kEl("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = kModel(KYT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = kModel(KYT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = kModel(KYT, typedField, keyboardVisible = true) }
        val out = kRig(tree = tree, gestures = gestures).execute("YouTube", "cats")
        kCheck("(i) IME-refused sequence still succeeds via the keyboard key", out is SearchOutcome.Success)
        kCheck("(i) the keyboard key was actually pressed", gestures.keySubmits == 1)
        kCheck("(i) IME Enter was tried first and refused", gestures.submits == 1)
        kCheck("(i) the first result was opened after the key submit", gestures.taps.any { it.id == "r1" })
    }
    run {
        // Key-only order: a keyboard with no IME Enter at all.
        val world = KFakeTree(typedWorld("cats"))
        val gestures = KFakeGestures()
        gestures.onKeySubmit = { world.current = resultsWorld("cats") }
        val chain = kChain(world, gestures)
        val result = chain.submit(
            "cats",
            listOf(SubmitMechanism.KEYBOARD_ACTION_KEY),
            world.current!!,
        )
        kCheck("(i) key-only order executes", result.verification is SubmitVerification.Executed)
        kCheck("(i) key-only tried exactly one mechanism", result.attempts.size == 1)
        kCheck("(i) key-only never touched IME Enter", gestures.submits == 0)
    }

    // ---- MASTER Wave 3 (F8/F14a): a suggestions screen or a spinner
    // is NEVER success — the YouTube incident's false-success loop. ----
    run {
        val before = typedWorld("cats")
        val field = kEl("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, focused = true, y = 100)
        val list = kEl("list", cls = "android.widget.ListView", role = ElementRole.LIST, y = 200)
        val sug = kEl("sug", text = "cats videos", y = 300)
        val suggestions = kModel(KYT, field, list, sug, keyboardVisible = true)
        val v = SubmitVerifier.verify(before, suggestions, "cats")
        kCheck("(w3a) suggestions screen is NotExecuted", v is SubmitVerification.NotExecuted)
        kCheckContains("(w3a) reason names the suggestions screen", (v as? SubmitVerification.NotExecuted)?.reason, "suggestions")
        val spinner = kEl("spin", cls = "android.widget.ProgressBar", y = 200)
        val loading = kModel(KYT, field.copy(focused = false), spinner, keyboardVisible = false)
        val vl = SubmitVerifier.verify(before, loading, "cats")
        kCheck("(w3b) loading screen is NotExecuted", vl is SubmitVerification.NotExecuted)
        kCheckContains("(w3b) reason names loading", (vl as? SubmitVerification.NotExecuted)?.reason, "loading")
    }

    // ---- MASTER Wave 3 (F8/F14a): the canonical predicate — matching
    // rows while typing are suggestions, however many, list or not. ----
    run {
        val field = kEl("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, focused = true, y = 100)
        val sug1 = kEl("sug1", text = "cats videos", y = 200)
        val sug2 = kEl("sug2", text = "cats funny", y = 300)
        val typed = kModel(KYT, field, sug1, sug2, keyboardVisible = true)
        kCheck("(w3c) two suggestion rows while typing are NOT results", !SearchScreenPredicates.resultsPresent(typed, "cats"))
        val results = kModel(KYT, field.copy(focused = false), sug1, sug2, keyboardVisible = false)
        kCheck("(w3c) same rows with the input closed ARE results", SearchScreenPredicates.resultsPresent(results, "cats"))
        kCheck("(w3c) suggestions screen recognised", SearchScreenPredicates.isSuggestionsScreen(kModel(KYT, field, kEl("list", cls = "android.widget.ListView", role = ElementRole.LIST, y = 200), sug1, keyboardVisible = true)))
    }

    // ---- MASTER Wave 3 (F15a): video-present predicate. ----
    run {
        val player = kEl("player", cls = "android.widget.VideoView", role = ElementRole.OTHER, y = 0, h = 400)
        val pause = kEl("pause", text = "Pause", role = ElementRole.BUTTON, y = 420)
        val play = kEl("play", text = "Play", role = ElementRole.BUTTON, y = 420)
        val playing = kModel(KYT, player, pause)
        kCheck("(w3d) player + pause control is a playing video", VideoPresence.isPlaying(playing))
        kCheck("(w3d) playing video is present", VideoPresence.videoPresent(playing))
        val paused = kModel(KYT, player, play)
        kCheck("(w3d) player + play control is a paused video", VideoPresence.isPaused(paused))
        kCheck("(w3d) paused video is present", VideoPresence.videoPresent(paused))
        val plain = kModel(KYT, kEl("t1", text = "Cats compilation video", y = 100))
        kCheck("(w3d) a text row is not a video", !VideoPresence.videoPresent(plain))
        kCheck("(w3d) pause control found for a playing video", VideoPresence.findPlayPauseControl(playing, wantPlaying = false)?.id == "pause")
        kCheck("(w3d) play control found for a paused video", VideoPresence.findPlayPauseControl(paused, wantPlaying = true)?.id == "play")
    }

    // ---- MASTER Wave 3 (F9): target aliases + mic exclusion. ----
    run {
        val closeBtn = kEl("x", desc = "Close", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 0)
        val mic = kEl("mic", desc = "Voice search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val searchBtn = kEl("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 200)
        kCheck("(w3e) x expands to close", TargetAliases.expandQuery("x").contains("close"))
        kCheck("(w3e) x matches the close button", TargetAliases.matchesTargetQuery(closeBtn, "x"))
        kCheck("(w3e) search never matches the voice mic", !TargetAliases.matchesTargetQuery(mic, "search"))
        kCheck("(w3e) mic is a voice control", TargetAliases.isVoiceControl(mic))
        kCheck("(w3e) mic does not mention search", !TargetAliases.mentionsSearch(mic))
        kCheck("(w3e) search button mentions search", TargetAliases.mentionsSearch(searchBtn))
    }

    // ---- Common sense (2026-11-10): perspective variants + close
    // matches resolve through the same resolver. ----
    run {
        val story = kEl("story", text = "Your story", role = ElementRole.BUTTON, y = 0)
        val profile = kEl("profile", text = "Your profile", role = ElementRole.BUTTON, y = 100)
        val elements = listOf(story, profile)
        kCheck("(cs) my maps to your", TargetAliases.perspectiveVariants("my story").contains("your story"))
        kCheck("(cs) your maps back to my", TargetAliases.perspectiveVariants("your story").contains("my story"))
        kCheck("(cs) my story resolves to Your story", TargetAliases.resolveElements(elements, "my story").map { it.id } == listOf("story"))
        kCheck("(cs) my profile resolves to Your profile", TargetAliases.resolveElements(elements, "my profile").map { it.id } == listOf("profile"))
        val vsRow = kEl("vs", text = "Nigeria vs Japan", role = ElementRole.TEXT, y = 0)
        kCheck("(cs) close wording resolves", TargetAliases.resolveElements(listOf(vsRow), "Nigeria versus Japan").map { it.id } == listOf("vs"))
        kCheck("(cs) close match needs a 3+ char query", !TargetAliases.matchesClose(vsRow, "vs"))
        val dupA = kEl("a", text = "Nigeria vs Japan", role = ElementRole.TEXT, y = 0)
        val dupB = kEl("b", text = "Nigeria vs Japan", role = ElementRole.TEXT, y = 100)
        kCheck("(cs) two close matches are both returned", TargetAliases.resolveElements(listOf(dupA, dupB), "Nigeria versus Japan").size == 2)
        kCheck("(cs) exact beats close", TargetAliases.resolveElements(listOf(dupA, kEl("c", text = "Nigeria versus Japan", role = ElementRole.TEXT, y = 200)), "Nigeria versus Japan").map { it.id } == listOf("c"))
    }

    // ---- MASTER Wave 3 (F14b/d): stop limits + no-change rule. ----
    run {
        kCheck("(w3f) max scrolls per search is 10", StopLimits.MAX_SCROLLS_PER_SEARCH == 10)
        kCheck("(w3f) max no-change streak is 2", StopLimits.MAX_NO_CHANGE_STREAK == 2)
        kCheckContains("(w3f) end message", StopLimits.REACHED_END_NOT_FOUND, "reached the end")
        val tracker = NoChangeTracker()
        kCheck("(w3f) first no-change does not stop", !tracker.observe(changed = false))
        kCheck("(w3f) second no-change stops", tracker.observe(changed = false))
        kCheck("(w3f) streak is 2", tracker.currentStreak() == 2)
        kCheck("(w3f) a change resets the streak", !tracker.observe(changed = true) && tracker.currentStreak() == 0)
        kCheck("(w3f) one no-change after a reset does not stop", !tracker.observe(changed = false))
    }

    println("SearchKeyboardTest: $kPassed passed, $kFailed failed")
    if (kFailed == 0) {
        println("ALL $kPassed TESTS PASSED")
    } else {
        throw AssertionError("$kFailed test(s) failed")
    }
}
