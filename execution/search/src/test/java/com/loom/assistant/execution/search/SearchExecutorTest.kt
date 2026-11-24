package com.loom.assistant.execution.search

import android.graphics.Rect
import com.loom.assistant.execution.interaction.ActionPlan
import com.loom.assistant.execution.interaction.PlanStep
import com.loom.assistant.execution.interaction.PlanStepKind
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Master implementation §4 + §5 — SearchExecutor tests.
 *
 * Drives SearchSequence on the JVM with fake tree/gestures/tokens and a
 * fake clock (poll timeouts are deterministic: the fake sleeper advances
 * the fake clock). Covers: search happy path (fake tree/gestures),
 * focus-wait timeout → honest failure, submit + fallback, result
 * verification, stale-coordinate re-identification (layout change
 * mid-sequence), failure recovery + honest failure, TYPE/SUBMIT plan
 * steps.
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 [android fakes] [testutil fakes]
 *     [platform/storage] [core/common: LoomEventBus, LoomStateEvent]
 *     [execution/screenawareness] [intelligence/authgate] [lifecycle]
 *     SearchExecutor.kt ActionPlan.kt SearchExecutorTest.kt
 *   java -cp out com.loom.assistant.execution.search.SearchExecutorTestKt
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

private class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

private class FakeSleeper(private val clock: FakeClock) : Sleeper {
    override fun sleep(ms: Long) {
        clock.now += ms
    }
}

private class FakeTree(var current: ScreenModel? = null) : SearchTreeSource {
    var focus = false
    var snapshotFn: (() -> ScreenModel?)? = null
    var snapshotCalls = 0
    override fun snapshot(): ScreenModel? {
        snapshotCalls++
        return snapshotFn?.invoke() ?: current
    }

    override fun hasInputFocus(): Boolean = focus
}

private class FakeGestures : SearchGestures {
    val taps = mutableListOf<ScreenElement>()
    var launches = 0
    val types = mutableListOf<String>()
    var submits = 0
    var scrolls = 0
    var backs = 0
    var launchResult: GestureAcceptance = GestureAcceptance.Accepted
    var tapResult: GestureAcceptance = GestureAcceptance.Accepted
    var typeResult: GestureAcceptance = GestureAcceptance.Accepted
    var submitResult: GestureAcceptance = GestureAcceptance.Accepted
    var scrollResult: GestureAcceptance = GestureAcceptance.Accepted
    var backResult: GestureAcceptance = GestureAcceptance.Accepted
    var onTap: ((ScreenElement) -> Unit)? = null
    var onType: ((String) -> Unit)? = null
    var onSubmit: (() -> Unit)? = null
    var onScroll: (() -> Unit)? = null

    override fun launch(packageName: String, token: String?): GestureAcceptance {
        launches++
        return launchResult
    }

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

    override fun submit(token: String?): GestureAcceptance {
        submits++
        onSubmit?.invoke()
        return submitResult
    }

    override fun scroll(forward: Boolean, token: String?): GestureAcceptance {
        scrolls++
        onScroll?.invoke()
        return scrollResult
    }

    override fun back(token: String?): GestureAcceptance {
        backs++
        return backResult
    }
}

private class FakeTokens(var token: String? = "tok-1") : TokenProvider {
    var calls = 0
    val keys = mutableListOf<String>()
    /** Full actions, in request order — the scoped-action test reads scope/appLabel off these. */
    val actions = mutableListOf<SearchAuthAction>()
    override fun tokenFor(action: SearchAuthAction): String? {
        calls++
        keys.add(action.key)
        actions.add(action)
        return token
    }
}

private class FakeResolver(private val resolution: AppResolution) : AppResolver {
    var calls = 0
    override fun resolve(name: String): AppResolution {
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

private val TIMEOUTS = SearchTimeouts(
    foregroundMs = 1_000,
    fieldAppearMs = 1_000,
    focusMs = 1_000,
    typeVerifyMs = 500,
    resultsMs = 1_000,
    landingMs = 1_000,
    pollIntervalMs = 100,
)

private fun rig(
    resolution: AppResolution = AppResolution.Found("com.google.android.youtube", "YouTube"),
    tree: FakeTree = FakeTree(),
    gestures: FakeGestures = FakeGestures(),
    tokens: TokenProvider = FakeTokens(),
    // MASTER F7b — the stop flag the sequence reads.
    isStopped: () -> Boolean = { false },
): SearchSequence {
    val clock = FakeClock()
    return SearchSequence(
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

private const val YT = "com.google.android.youtube"

// Happy-path screen flow: home (search button) → field → typed → results → detail.
private fun happyRig(
    tokens: TokenProvider = FakeTokens(),
    // MASTER F7b — the stop flag the sequence reads.
    isStopped: () -> Boolean = { false },
): Triple<SearchSequence, FakeTree, FakeGestures> {
    val tree = FakeTree()
    val gestures = FakeGestures()
    val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
    val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
    val home = model(YT, searchBtn)
    val fieldModel = model(YT, field)
    val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
    val typedModel = model(YT, typedField)
    val result1 = el("r1", text = "Cats compilation video", y = 300)
    val result2 = el("r2", text = "Cats vs dogs", y = 400)
    val resultsModel = model(YT, typedField, result1, result2)
    val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
    tree.current = home
    gestures.onTap = { tapped ->
        when (tapped.id) {
            "btn" -> tree.current = fieldModel
            "field" -> tree.focus = true
            "r1" -> tree.current = detail
        }
    }
    gestures.onType = { tree.current = typedModel }
    gestures.onSubmit = { tree.current = resultsModel }
    return Triple(rig(tree = tree, gestures = gestures, tokens = tokens, isStopped = isStopped), tree, gestures)
}

fun main() {
    // ---- 1. happy path: resolve → launch → button → field → focus → type → submit → result ----
    run {
        val (seq, tree, gestures) = happyRig()
        val out = seq.execute("YouTube", "cats")
        check("happy path succeeds", out is SearchOutcome.Success)
        checkContains(
            "happy path detail names query + result",
            (out as? SearchOutcome.Success)?.detail,
            "Cats compilation video",
        )
        check("launch called once", gestures.launches == 1)
        check("type called with the query", gestures.types == listOf("cats"))
        check("submit called once", gestures.submits == 1)
        check("three taps: button, field, result", gestures.taps.map { it.id } == listOf("btn", "field", "r1"))
        check("tree was re-inspected", tree.snapshotCalls > 5)
    }

    // ---- 1b. Device-evidence fix (2026-11-05, items 4+6): every token
    // request in the sequence carries the searched app's package as
    // scope and its label — the wiring needs both to build the scoped
    // factory action (blank scope can never offer or apply a grant).
    run {
        val tokens = FakeTokens()
        val (seq, _, _) = happyRig(tokens)
        val out = seq.execute("YouTube", "cats")
        check("scoped-actions run succeeds", out is SearchOutcome.Success)
        check(
            "launch action is scoped to the app package",
            tokens.actions.firstOrNull { it.key == SearchActionKeys.APP_LAUNCH }?.scope == YT,
        )
        check(
            "launch action carries the app label",
            tokens.actions.firstOrNull { it.key == SearchActionKeys.APP_LAUNCH }?.appLabel == "YouTube",
        )
        val taps = tokens.actions.filter { it.key == SearchActionKeys.TAP_GUIDANCE }
        check("three tap actions requested", taps.size == 3)
        check("every tap action is scoped to the app package", taps.all { it.scope == YT })
        check("every tap action carries the app label", taps.all { it.appLabel == "YouTube" })
        check(
            "type action is scoped to the app package",
            tokens.actions.firstOrNull { it.key == SearchActionKeys.DEVICE_TYPE }?.scope == YT,
        )
        check(
            "submit action is scoped to the app package",
            tokens.actions.firstOrNull { it.key == SearchActionKeys.DEVICE_SUBMIT }?.scope == YT,
        )
        check(
            "no token request has a blank scope on a grant-eligible key",
            tokens.actions.filter {
                it.key in setOf(
                    SearchActionKeys.APP_LAUNCH,
                    SearchActionKeys.TAP_GUIDANCE,
                    SearchActionKeys.DEVICE_TYPE,
                    SearchActionKeys.DEVICE_SUBMIT,
                )
            }.all { it.scope.isNotBlank() },
        )
    }

    // ---- 2. SearchExecutor object entry point ----
    run {
        val (seq, _, _) = happyRig()
        SearchExecutor.configureSequence(seq)
        val out = SearchExecutor.execute("YouTube", "cats")
        check("SearchExecutor.execute succeeds", out is SearchOutcome.Success)
    }

    // ---- 2b. Universal Console (2026-11-07): search GOALS.
    // SHOW_RESULTS stops at the verified results list and never
    // taps a result; OPEN_FIRST_RESULT (the default) opens the
    // first result, as before. The goal is the difference between
    // "search for X" and "find X and play it" — the sequence must
    // not open a video a plain search never asked to open.
    run {
        val (seq, _, gestures) = happyRig()
        val out = seq.execute("YouTube", "cats", goal = SearchGoal.SHOW_RESULTS)
        check("show-results succeeds", out is SearchOutcome.Success)
        checkContains(
            "show-results detail says results were found",
            (out as? SearchOutcome.Success)?.detail,
            "Found results",
        )
        check("show-results typed the query", gestures.types == listOf("cats"))
        check("show-results submitted once", gestures.submits == 1)
        check(
            "show-results never tapped a result",
            gestures.taps.map { it.id } == listOf("btn", "field"),
        )
    }
    run {
        val (seq, _, gestures) = happyRig()
        val out = seq.execute("YouTube", "cats", goal = SearchGoal.OPEN_FIRST_RESULT)
        check("open-first succeeds", out is SearchOutcome.Success)
        check(
            "open-first tapped the first result",
            gestures.taps.map { it.id } == listOf("btn", "field", "r1"),
        )
    }
    run {
        // Default goal is open-first (existing callers unchanged).
        val (seq, _, gestures) = happyRig()
        val out = seq.execute("YouTube", "cats")
        check("default goal opens the first result", gestures.taps.any { it.id == "r1" })
        check("default goal succeeds", out is SearchOutcome.Success)
    }
    run {
        // SHOW_RESULTS with no results: fails honestly, opens nothing.
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val home = model(YT, searchBtn)
        val fieldModel = model(YT, field)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedModel = model(YT, typedField)
        tree.current = home
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = fieldModel
                "field" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = typedModel }
        // onSubmit unset: the search never produces results.
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats", goal = SearchGoal.SHOW_RESULTS)
        check("show-results with no results fails", out is SearchOutcome.Failed)
        check("show-results with no results opens nothing", gestures.taps.none { it.id == "r1" })
    }
    run {
        // Chrome: the omnibox is a SEARCH_FIELD ("Search or type
        // URL"); a Chrome search stops at the web results page and
        // never opens the first link.
        val chromePkg = "com.android.chrome"
        val tree = FakeTree()
        val gestures = FakeGestures()
        val omnibox = el("omnibox", hint = "Search or type URL", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, y = 100)
        val home = model(chromePkg, omnibox)
        val typedBox = el("omnibox", text = "best Android phones", hint = "Search or type URL", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, y = 100)
        val typedModel = model(chromePkg, typedBox)
        val list = el("results-list", cls = "android.widget.ListView", role = ElementRole.LIST, y = 200)
        val link1 = el("link-1", text = "Best Android phones 2026 — review", y = 300)
        val link2 = el("link-2", text = "Best Android phones — buying guide", y = 400)
        val resultsModel = model(chromePkg, typedBox, list, link1, link2)
        tree.current = home
        gestures.onTap = { tapped ->
            if (tapped.id == "omnibox") tree.focus = true
        }
        gestures.onType = { tree.current = typedModel }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(
            resolution = AppResolution.Found(chromePkg, "Chrome"),
            tree = tree,
            gestures = gestures,
        )
        val out = seq.execute("Chrome", "best Android phones", goal = SearchGoal.SHOW_RESULTS)
        check("chrome show-results succeeds", out is SearchOutcome.Success)
        check("chrome search typed the query", gestures.types == listOf("best Android phones"))
        check("chrome show-results opened no link", gestures.taps.none { it.id.startsWith("link-") })
        check("chrome show-results tapped only the omnibox", gestures.taps.map { it.id } == listOf("omnibox"))
    }

    // ---- 3. app not found / ambiguous ----
    run {
        val seq = rig(resolution = AppResolution.NotFound)
        check("unknown app -> AppNotFound", seq.execute("NoSuchApp", "cats") is SearchOutcome.AppNotFound)
    }
    run {
        val seq = rig(resolution = AppResolution.Ambiguous(listOf("YouTube", "YouTube Music")))
        val out = seq.execute("YouTube", "cats")
        check("ambiguous app -> Ambiguous with candidates", out is SearchOutcome.Ambiguous)
        check(
            "ambiguous candidates listed",
            (out as? SearchOutcome.Ambiguous)?.candidates == listOf("YouTube", "YouTube Music"),
        )
    }

    // ---- 4. sensitive app: refused BEFORE any token or dispatch ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val tokens = FakeTokens()
        val seq = rig(
            resolution = AppResolution.Found("com.example.bankapp", "Bank"),
            tree = tree,
            gestures = gestures,
            tokens = tokens,
        )
        val out = seq.execute("Bank", "cats")
        check("sensitive app -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "sensitive failure names guidance-only",
            (out as? SearchOutcome.Failed)?.reason,
            "guide",
        )
        check("sensitive app: no token requested", tokens.calls == 0)
        check("sensitive app: nothing launched", gestures.launches == 0)
    }

    // ---- 5. focus-wait timeout → honest failure, type never called ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        tree.current = model(YT, field)
        // Focus NEVER becomes true: the wait must time out, retry once, then fail.
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("focus timeout -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "focus timeout reason names focus",
            (out as? SearchOutcome.Failed)?.reason,
            "focus",
        )
        check("focus timeout: type never called", gestures.types.isEmpty())
        check("focus timeout: submit never called", gestures.submits == 0)
        check("focus timeout: field tapped twice (one retry)", gestures.taps.size == 2)
    }

    // ---- 6. token denied → honest failure, no dispatch ----
    run {
        val gestures = FakeGestures()
        val seq = rig(tree = FakeTree(model(YT)), gestures = gestures, tokens = FakeTokens(token = null))
        val out = seq.execute("YouTube", "cats")
        check("token denied -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "token denial reason names confirmation",
            (out as? SearchOutcome.Failed)?.reason,
            "confirm",
        )
        check("token denied: nothing launched", gestures.launches == 0)
    }

    // ---- 7. launch refused → honest failure ----
    run {
        val gestures = FakeGestures()
        gestures.launchResult = GestureAcceptance.Refused("No launch intent for package.")
        val seq = rig(tree = FakeTree(model(YT)), gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("launch refused -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "launch refusal reason carried",
            (out as? SearchOutcome.Failed)?.reason,
            "No launch intent",
        )
    }

    // ---- 8. submit refused → on-screen search button fallback succeeds ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val submitBtn = el("go", text = "Search", cls = "android.widget.Button", role = ElementRole.BUTTON, y = 200)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "go" -> tree.current = resultsModel
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField, submitBtn) }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("submit fallback succeeds", out is SearchOutcome.Success)
        check("submit was attempted once", gestures.submits == 1)
        check("fallback tapped the on-screen search button", gestures.taps.any { it.id == "go" })
    }

    // ---- 9. submit refused + no button → honest failure ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("submit refused, no button -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "submit refusal reason carried",
            (out as? SearchOutcome.Failed)?.reason,
            "IME Enter not supported",
        )
    }

    // ---- 10. no results matching → honest failure (scroll tried once) ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val noMatch = model(YT, typedField, el("x1", text = "Try a different search"))
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = noMatch }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("no results -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "no-results reason names the query",
            (out as? SearchOutcome.Failed)?.reason,
            "cats",
        )
        check("no-results: scroll tried once for more results", gestures.scrolls == 1)
    }

    // ---- 11. result tapped but screen unchanged → honest failure, never success ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedField, result1)
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                // "r1": deliberately no screen change — landing must fail.
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("unchanged landing -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "unchanged landing reason names the screen",
            (out as? SearchOutcome.Failed)?.reason,
            "didn't change",
        )
    }

    // ---- 12. stale coordinates: layout shift WITHIN tolerance → tap the fresh element ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val btnA = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val btnB = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 110)
        val modelA = model(YT, btnA)
        val modelB = model(YT, btnB)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing", y = 0, h = 200))
        var calls = 0
        tree.snapshotFn = {
            calls++
            when {
                calls <= 2 -> modelA // foreground poll + affordance find see A
                calls == 3 -> modelB // tapFresh re-resolve sees B (shifted 10px)
                else -> tree.current
            }
        }
        tree.current = model(YT, field)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("within-tolerance shift still succeeds", out is SearchOutcome.Success)
        check(
            "within-tolerance shift tapped the FRESH bounds",
            gestures.taps.firstOrNull()?.bounds?.top == 110,
        )
    }

    // ---- 13. stale coordinates: layout shift BEYOND tolerance → re-identify + recover once ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val btnA = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val btnB = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 300)
        val modelA = model(YT, btnA)
        val modelB = model(YT, btnB)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 400)
        val resultsModel = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing", y = 0, h = 200))
        var calls = 0
        tree.snapshotFn = {
            calls++
            when {
                calls <= 2 -> modelA // foreground poll + affordance find see A
                else -> tree.current // everything after sees B (or later states)
            }
        }
        tree.current = modelB
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("beyond-tolerance shift recovers and succeeds", out is SearchOutcome.Success)
        check(
            "beyond-tolerance shift tapped the NEW position, never the stale one",
            gestures.taps.firstOrNull()?.bounds?.top == 300,
        )
        check("beyond-tolerance shift tapped once for the button", gestures.taps.count { it.id == "btn" } == 1)
    }

    // ---- 14. target lost mid-sequence → honest failure, no stale tap ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val btnA = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val modelA = model(YT, btnA)
        val empty = model(YT)
        var calls = 0
        tree.snapshotFn = {
            calls++
            when {
                calls <= 2 -> modelA
                else -> empty // the button is gone from the live tree
            }
        }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("target lost -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "target-lost reason names the search box",
            (out as? SearchOutcome.Failed)?.reason,
            "search box",
        )
        check("target lost: no tap was ever dispatched", gestures.taps.isEmpty())
    }

    // ---- 15. scroll reveals the affordance below the fold ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing", y = 0, h = 200))
        val noButton = model(YT, el("x1", text = "Home feed"))
        val withButton = model(YT, searchBtn)
        tree.current = noButton
        gestures.onScroll = { tree.current = withButton }
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("scroll reveals affordance -> Success", out is SearchOutcome.Success)
        check("scroll was used once to find the box", gestures.scrolls == 1)
    }

    // ---- 16. type refused → honest failure, no retry on gate refusal ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        gestures.typeResult = GestureAcceptance.Refused("The focused field rejected the text.")
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
            }
        }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("type refused -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "type refusal reason carried",
            (out as? SearchOutcome.Failed)?.reason,
            "rejected the text",
        )
        check("type refused: submit never called", gestures.submits == 0)
    }

    // ---- 17. typed text does not appear in the field → honest failure ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val wrongField = el("field", text = "dogs", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
            }
        }
        gestures.onType = { tree.current = model(YT, wrongField) }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("typed text missing -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "typed-text failure reason names the field",
            (out as? SearchOutcome.Failed)?.reason,
            "didn't appear",
        )
        check("typed-text failure: submit never called", gestures.submits == 0)
    }

    // ---- 18. blank inputs → honest failure, nothing resolved ----
    run {
        val resolver = FakeResolver(AppResolution.Found(YT, "YouTube"))
        val clock = FakeClock()
        val seq = SearchSequence(
            apps = resolver,
            tree = FakeTree(),
            gestures = FakeGestures(),
            tokens = FakeTokens(),
            clock = clock,
            sleeper = FakeSleeper(clock),
            timeouts = TIMEOUTS,
        )
        check("blank app -> Failed", seq.execute("  ", "cats") is SearchOutcome.Failed)
        check("blank query -> Failed", seq.execute("YouTube", "  ") is SearchOutcome.Failed)
        check("blank inputs never resolve", resolver.calls == 0)
    }

    // ---- 19. ActionPlan: TYPE + SUBMIT step kinds ----
    run {
        val plan = ActionPlan(
            id = "p-search",
            goal = "search for cats in YouTube",
            demoAppPackage = YT,
            steps = listOf(
                PlanStep(id = "s1", kind = PlanStepKind.TAP, targetQuery = "Search", instruction = "Tap the search box"),
                PlanStep(id = "s2", kind = PlanStepKind.TYPE, targetQuery = "Search", instruction = "Type the query", text = "cats"),
                PlanStep(id = "s3", kind = PlanStepKind.SUBMIT, targetQuery = "Search", instruction = "Submit the search"),
            ),
        )
        check("plan with TAP+TYPE+SUBMIT builds", plan.steps.size == 3)
        check("TYPE step carries its text", plan.steps[1].text == "cats")
        check("SUBMIT kind retained", plan.steps[2].kind == PlanStepKind.SUBMIT)
        check("TAP-only plan still builds", ActionPlan(
            id = "p-tap",
            goal = "tap",
            demoAppPackage = YT,
            steps = listOf(PlanStep(id = "s1", targetQuery = "x", instruction = "Tap x")),
        ).steps.all { it.kind == PlanStepKind.TAP })
        var threw = false
        try {
            ActionPlan(
                id = "p-bad",
                goal = "bad",
                demoAppPackage = YT,
                steps = listOf(PlanStep(id = "s1", kind = PlanStepKind.TYPE, targetQuery = "x", instruction = "Type nothing")),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        check("TYPE step without text is rejected", threw)
    }

    // ---- 20. Device-evidence fix (2026-11-05, H1): YouTube-style
    // SearchView happy path — the field is a SEARCH_FIELD (SearchView
    // class + "Search YouTube" hint), never a bare INPUT. Live-tree
    // shape: magnifier button -> SearchView field -> typed -> results.
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", cls = "android.widget.SearchView", role = ElementRole.SEARCH_FIELD, hint = "Search YouTube", y = 100)
        val typedField = el("field", text = "cats", cls = "android.widget.SearchView", role = ElementRole.SEARCH_FIELD, hint = "Search YouTube", y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("SearchView happy path succeeds", out is SearchOutcome.Success)
        check(
            "SearchView path taps button, field, result",
            gestures.taps.map { it.id } == listOf("btn", "field", "r1"),
        )
        check("SearchView path typed the query", gestures.types == listOf("cats"))
    }

    // ---- 21. H1: Chrome-style omnibox — a hinted EditText that IS the
    // search field on the home screen (no button to tap first). Tier A
    // must match it through hintText, where "Search or type URL" lives.
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val omnibox = el("omni", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search or type URL", y = 100)
        val typedOmni = el("omni", text = "cats", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search or type URL", y = 100)
        val result1 = el("r1", text = "Cats - Wikipedia", y = 300)
        val resultsModel = model(YT, typedOmni, result1)
        val detail = model(YT, el("d1", text = "Cats - Wikipedia page", y = 0, h = 200))
        tree.current = model(YT, omnibox, el("t1", text = "Top sites", y = 200))
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "omni" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedOmni) }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("hinted-EditText happy path succeeds", out is SearchOutcome.Success)
        check(
            "omnibox path taps field then result (no button)",
            gestures.taps.map { it.id } == listOf("omni", "r1"),
        )
    }

    // ---- 22. Latent H6: the typed query sitting in the SEARCH_FIELD's
    // text must NEVER be picked as the "first result" — the field is
    // first in tree order and contains the query term. WhatsApp shape:
    // search icon -> hinted field -> typed -> results.
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", y = 100)
        val typedField = el("field", text = "cats", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", y = 100)
        val result1 = el("r1", text = "Cats group chat", y = 300)
        val result2 = el("r2", text = "Cats stickers", y = 400)
        // The typed field is FIRST in tree order and holds the query.
        val resultsModel = model(YT, typedField, result1, result2)
        val detail = model(YT, el("d1", text = "Cats group chat opened", y = 0, h = 200))
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("typed field is never picked as a result", out is SearchOutcome.Success)
        check(
            "taps went button, field, real result — never the field as a result",
            gestures.taps.map { it.id } == listOf("btn", "field", "r1"),
        )
    }

    // ---- 23. Latent H6 (submit fallback): a SEARCH_FIELD whose desc
    // mentions "search" is NOT an on-screen search button. With IME
    // Enter refused, the fallback must tap the real button, not the field.
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        gestures.submitResult = GestureAcceptance.Refused("IME Enter not supported.")
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search…", y = 100)
        val submitBtn = el("go", text = "Search", cls = "android.widget.Button", role = ElementRole.BUTTON, y = 200)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "go" -> tree.current = resultsModel
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField, submitBtn) }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("submit fallback skips the search field", out is SearchOutcome.Success)
        check(
            "fallback taps button, field, search button, result — never the field as a button",
            gestures.taps.map { it.id } == listOf("btn", "field", "go", "r1"),
        )
    }

    // ---- 24. H1: two SEARCH_FIELDs on screen refuse honestly (tier A
    // ambiguity) instead of guessing which one is the search box.
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val f1 = el("f1", cls = "android.widget.SearchView", role = ElementRole.SEARCH_FIELD, hint = "Search YouTube", y = 100)
        val f2 = el("f2", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, hint = "Search settings", y = 200)
        tree.current = model(YT, f1, f2)
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("two search fields -> Failed", out is SearchOutcome.Failed)
        checkContains(
            "two search fields reason names the count",
            (out as? SearchOutcome.Failed)?.reason,
            "2 matches",
        )
        check("two search fields: nothing tapped", gestures.taps.isEmpty())
    }

    // ---- 25. Item 1: per-step search logging — the logger sees the
    // start, the step milestones and the final outcome.
    run {
        val logged = mutableListOf<String>()
        val clock = FakeClock()
        val tree2 = FakeTree()
        val gestures2 = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree2.current = model(YT, searchBtn)
        gestures2.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree2.current = model(YT, field)
                "field" -> tree2.focus = true
                "r1" -> tree2.current = detail
            }
        }
        gestures2.onType = { tree2.current = model(YT, typedField) }
        gestures2.onSubmit = { tree2.current = resultsModel }
        val seq = SearchSequence(
            apps = FakeResolver(AppResolution.Found(YT, "YouTube")),
            tree = tree2,
            gestures = gestures2,
            tokens = FakeTokens(),
            clock = clock,
            sleeper = FakeSleeper(clock),
            timeouts = TIMEOUTS,
            log = SearchLogger { logged.add(it) },
        )
        val out = seq.execute("YouTube", "cats")
        check("logged run succeeds", out is SearchOutcome.Success)
        check("logger saw the start", logged.any { it.contains("search: start") })
        check("logger saw the launch", logged.any { it.contains("search: launched") })
        check("logger saw the focus step", logged.any { it.contains("search: search field focused") })
        check("logger saw the submit", logged.any { it.contains("search: submitted") })
        check("logger saw the final outcome", logged.any { it.contains("search: done") })
    }

    // ---- MASTER F7b: a stop aborts the sequence honestly ----
    run {
        val tokens = FakeTokens()
        val gestures = FakeGestures()
        val seq = rig(tokens = tokens, gestures = gestures, isStopped = { true })
        val out = seq.execute("YouTube", "cats")
        check("stop before launch fails stopped", out == SearchOutcome.Failed("Stopped."))
        check("stop before launch requests no token", tokens.calls == 0)
        check("stop before launch never launches", gestures.launches == 0)
    }
    run {
        // Stop landing right after the launch token: the launch
        // already happened (honest — it is not undone), but the
        // focus tap never fires and the run reports Stopped.
        var stopped = false
        val inner = FakeTokens()
        val flipping = object : TokenProvider {
            override fun tokenFor(action: SearchAuthAction): String? {
                val t = inner.tokenFor(action)
                if (action.key == SearchActionKeys.APP_LAUNCH) stopped = true
                return t
            }
        }
        val (seq, _, gestures) = happyRig(tokens = flipping, isStopped = { stopped })
        val out = seq.execute("YouTube", "cats")
        check("stop after launch fails stopped", out == SearchOutcome.Failed("Stopped."))
        check("stop after launch requested only the launch token", inner.calls == 1)
        check("stop after launch launched once", gestures.launches == 1)
        check("stop after launch never tapped", gestures.taps.isEmpty())
    }

    // ---- MASTER Wave 3 (F8/F14a): a submit that leaves the
    // suggestions screen is NEVER success — the YouTube incident
    // (search sat un-submitted ~90s while the outcome claimed
    // "opened Nigeria versus Japan" 5 times). ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100).copy(focused = true)
        val list = el("list", cls = "android.widget.ListView", role = ElementRole.LIST, y = 200)
        val sug = el("sug", text = "cats videos", y = 300)
        val suggestions = ScreenModel(YT, listOf(typedField, list, sug), keyboardVisible = true)
        tree.current = model(YT, searchBtn)
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                // "sug": tapping the suggestion runs nothing — the search stays un-submitted.
            }
        }
        gestures.onType = { tree.current = suggestions }
        gestures.onSubmit = { tree.current = suggestions }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("suggestions-screen submit fails honestly", out is SearchOutcome.Failed)
        checkContains(
            "suggestions failure names the suggestions screen",
            (out as? SearchOutcome.Failed)?.reason,
            "suggestions",
        )
        check("suggestions submit never tapped a result", gestures.taps.none { it.id == "r1" })
    }

    // ---- MASTER Wave 3 (F8): the type step retries (re-focus +
    // re-type) when the text does not land in the focused field. ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedWrong = el("field", text = "cat", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedRight = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val resultsModel = model(YT, typedRight, result1)
        val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = model(YT, searchBtn)
        var types = 0
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = {
            types++
            tree.current = if (types == 1) model(YT, typedWrong) else model(YT, typedRight)
        }
        gestures.onSubmit = { tree.current = resultsModel }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("type retry succeeds after re-type", out is SearchOutcome.Success)
        check("type retry typed twice", gestures.types == listOf("cats", "cats"))
    }

    // ---- MASTER Wave 3 (F14a): results already showing — the search
    // step is COMPLETE, never re-type / re-tap / re-submit. ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val result2 = el("r2", text = "Cats vs dogs", y = 400)
        tree.current = model(YT, typedField, result1, result2)
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats", goal = SearchGoal.SHOW_RESULTS)
        check("already-showing returns success", out is SearchOutcome.Success)
        checkContains(
            "already-showing says so",
            (out as? SearchOutcome.Success)?.detail,
            "already showing",
        )
        check("already-showing never typed", gestures.types.isEmpty())
        check("already-showing never submitted", gestures.submits == 0)
        check("already-showing never tapped", gestures.taps.isEmpty())
    }
    run {
        // Same screen, OPEN_FIRST: skip straight to selecting a result.
        val tree = FakeTree()
        val gestures = FakeGestures()
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val result2 = el("r2", text = "Cats vs dogs", y = 400)
        val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = model(YT, typedField, result1, result2)
        gestures.onTap = { tapped ->
            if (tapped.id == "r1") tree.current = detail
        }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("already-showing open-first succeeds", out is SearchOutcome.Success)
        check("already-showing open-first tapped only the result", gestures.taps.map { it.id } == listOf("r1"))
        check("already-showing open-first never typed", gestures.types.isEmpty())
    }

    // ---- MASTER Wave 3 (F9): "search" NEVER resolves to the
    // voice-search mic — a screen with only the mic fails honestly. ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val mic = el("mic", desc = "Voice search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        tree.current = model(YT, mic)
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("mic-only screen fails honestly", out is SearchOutcome.Failed)
        check("mic was never tapped", gestures.taps.none { it.id == "mic" })
        checkContains(
            "mic failure names the search box",
            (out as? SearchOutcome.Failed)?.reason,
            "search",
        )
    }

    // ---- MASTER Wave 3 (F14b/c): bounded scroll hunt — scan each
    // newly revealed row BEFORE the next scroll; stop at the target. ----
    run {
        val tree = FakeTree()
        val gestures = FakeGestures()
        val searchBtn = el("btn", desc = "Search", cls = "android.widget.ImageButton", role = ElementRole.BUTTON, y = 100)
        val field = el("field", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val typedField = el("field", text = "cats", desc = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, y = 100)
        val page0 = model(YT, typedField, el("x1", text = "Try a different search"))
        val page1 = model(YT, typedField, el("x2", text = "No more results here"))
        val result1 = el("r1", text = "Cats compilation video", y = 300)
        val page2 = model(YT, typedField, result1)
        val detail = model(YT, el("d1", text = "Now playing: Cats compilation video", y = 0, h = 200))
        tree.current = model(YT, searchBtn)
        var page = 0
        gestures.onTap = { tapped ->
            when (tapped.id) {
                "btn" -> tree.current = model(YT, field)
                "field" -> tree.focus = true
                "r1" -> tree.current = detail
            }
        }
        gestures.onType = { tree.current = model(YT, typedField) }
        gestures.onSubmit = { tree.current = page0; page = 0 }
        gestures.onScroll = {
            page++
            tree.current = when (page) {
                1 -> page1
                else -> page2
            }
        }
        val seq = rig(tree = tree, gestures = gestures)
        val out = seq.execute("YouTube", "cats")
        check("scroll hunt finds the result", out is SearchOutcome.Success)
        check("scroll hunt scrolled twice", gestures.scrolls == 2)
        check("scroll hunt tapped the result once", gestures.taps.count { it.id == "r1" } == 1)
    }

    println("passed=$passed failed=$failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        println("$failed TESTS FAILED")
    }
}
