package com.loom.assistant.intelligence.planner

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.MeasuredControl
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.platform.services.ScreenKnowledgeStoreHolder

/**
 * App-aware execution (2026-11-07, David's directive §2) — app
 * profiles, screen classification and the learner.
 *
 * The classifier is pure tree reading; the store is wins-weighted
 * ordering over seed DATA; the learner resets wins when the screen
 * structure changes. No test here branches on a package name — the
 * mechanism is general, the seeds are data.
 *
 * Run (from loom-android), see run-2c-tests.sh `appprof`:
 *   kotlinc -d out -jvm-target 17 [Rect fake] [RequestOrigin]
 *     [ScreenModel] [planner main + AppProfile.kt] AppProfileTest.kt
 *   java -cp out com.loom.assistant.intelligence.planner.AppProfileTestKt
 */
private var apPassed = 0
private var apFailed = 0

private fun apCheck(name: String, actual: Boolean) {
    if (actual) {
        apPassed++
    } else {
        apFailed++
        println("FAIL: $name")
    }
}

private fun apCheckEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        apPassed++
    } else {
        apFailed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun apEl(
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

private fun apModel(pkg: String, vararg els: ScreenElement, keyboardVisible: Boolean = false) =
    ScreenModel(pkg, els.toList(), keyboardVisible = keyboardVisible)

private const val YT = "com.google.android.youtube"
private const val YTM = "com.google.android.apps.youtube.music"
private const val IG = "com.instagram.android"
private const val FB = "com.facebook.katana"
private const val DISCORD = "com.discord"

/**
 * KNOWLEDGE BASE tests (2026-11-09, David: a durable knowledge
 * base, not relearning) — a fake SharedPreferences over a shared
 * backing map, so a "process restart" is a new store instance over
 * the same map.
 */
private class MapPrefs(private val backing: MutableMap<String, String>) : SharedPreferences {
    override val all: Map<String, *> get() = backing.toMap()
    override fun contains(key: String) = backing.containsKey(key)
    override fun getString(key: String, defValue: String?) = backing[key] ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun edit(): SharedPreferences.Editor = MapEditor(backing)

    private class MapEditor(private val backing: MutableMap<String, String>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = this
        override fun putInt(key: String, value: Int) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putLong(key: String, value: Long) = this
        override fun remove(key: String) = apply { pending[key] = null }
        override fun commit(): Boolean {
            applyPending()
            return true
        }
        override fun apply() = applyPending()
        private fun applyPending() {
            for ((k, v) in pending) {
                if (v == null) backing.remove(k) else backing[k] = v
            }
            pending.clear()
        }
    }
}

private class MapContext(private val backing: MutableMap<String, String>) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = MapPrefs(backing)
}

private class ThrowingKnowledgePersistence : ScreenKnowledgePersistence {
    override fun load(): Map<String, ScreenKnowledge> = throw IllegalStateException("disk gone")
    override fun save(knowledge: Map<String, ScreenKnowledge>) {
        throw IllegalStateException("disk gone")
    }
}

private class ThrowingAppProfilePersistence : AppProfilePersistence {
    override fun load(): Map<String, AppProfile> = throw IllegalStateException("disk gone")
    override fun save(profiles: Map<String, AppProfile>) {
        throw IllegalStateException("disk gone")
    }
}

/** The YouTube Music search-entry screen used by the KB tests. */
private fun kbSearchEntryModel(backButton: MeasuredControl? = MeasuredControl(
    Rect(0, 2256, 360, 2400),
    "Back",
    "android.widget.ImageButton",
)) = ScreenModel(
    YTM,
    listOf(
        apEl(
            "ytm-search-field", hint = "Search YouTube Music",
            role = ElementRole.SEARCH_FIELD, focused = true,
            cls = "android.widget.EditText",
        ),
        apEl(
            "ytm-clear", desc = "Clear search",
            role = ElementRole.BUTTON, cls = "android.widget.ImageButton",
        ),
        apEl("ytm-search-btn", text = "Search", role = ElementRole.BUTTON),
    ),
    keyboardVisible = true,
    backButton = backButton,
)

fun main() {
    // ---------- classifier kinds ----------
    run {
        // YouTube search entry: focused search field + keyboard, no list.
        val entry = apModel(
            YT,
            apEl("field", hint = "Search YouTube", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, focused = true, y = 100),
            keyboardVisible = true,
        )
        apCheckEq("youtube search entry", ScreenClassifier.classify(entry).kind, ScreenKind.SEARCH_ENTRY)
        // YouTube search results: a list + query rows.
        val results = apModel(
            YT,
            apEl("list", cls = "androidx.recyclerview.widget.RecyclerView", role = ElementRole.LIST, y = 100, h = 800),
            apEl("r1", text = "Davido — Fall", y = 120),
            apEl("r2", text = "Davido — If", y = 220),
        )
        apCheckEq("youtube search results (list)", ScreenClassifier.classify(results, "Davido").kind, ScreenKind.SEARCH_RESULTS)
        // Results without a list: ≥2 query-matching rows.
        val rows = apModel(
            YT,
            apEl("r1", text = "Davido — Fall", y = 120),
            apEl("r2", text = "Davido — If", y = 220),
        )
        apCheckEq("youtube search results (rows)", ScreenClassifier.classify(rows, "Davido").kind, ScreenKind.SEARCH_RESULTS)
        // One matching row is NOT results — it is a suggestion.
        val oneRow = apModel(YT, apEl("r1", text = "Davido — Fall", y = 120))
        apCheckEq("one row is not results", ScreenClassifier.classify(oneRow, "Davido").kind, ScreenKind.UNKNOWN)
        // MASTER Wave 3 (F8/F14a): a SUGGESTIONS screen (keyboard up,
        // field focused, list of query-echoing rows) is NOT results —
        // the old rule passed it as results and the YouTube incident
        // falsely claimed success.
        val suggestions = apModel(
            YT,
            apEl("field", hint = "Search YouTube", cls = "android.widget.EditText", role = ElementRole.SEARCH_FIELD, focused = true, y = 100),
            apEl("list", cls = "androidx.recyclerview.widget.RecyclerView", role = ElementRole.LIST, y = 200, h = 600),
            apEl("s1", text = "Davido songs", y = 220),
            apEl("s2", text = "Davido live", y = 320),
            keyboardVisible = true,
        )
        apCheck(
            "suggestions screen is not results",
            ScreenClassifier.classify(suggestions, "Davido").kind != ScreenKind.SEARCH_RESULTS,
        )
        // Instagram search entry: focused input with a search hint.
        val igEntry = apModel(
            IG,
            apEl("field", hint = "Search", cls = "android.widget.EditText", role = ElementRole.INPUT, focused = true, y = 100),
            keyboardVisible = true,
        )
        apCheckEq("instagram search entry", ScreenClassifier.classify(igEntry).kind, ScreenKind.SEARCH_ENTRY)
        // Dialog wins over everything else on screen.
        val dialog = apModel(
            YT,
            apEl("dlg", text = "Allow?", cls = "android.app.AlertDialog", role = ElementRole.DIALOG, y = 100),
            apEl("r1", text = "Davido — Fall", y = 300),
        )
        apCheckEq("dialog", ScreenClassifier.classify(dialog).kind, ScreenKind.DIALOG)
        // Feed: list + navigation.
        val feed = apModel(
            IG,
            apEl("list", cls = "androidx.recyclerview.widget.RecyclerView", role = ElementRole.LIST, y = 100, h = 800),
            apEl("nav", cls = "com.google.android.material.bottomnavigation.BottomNavigationView", role = ElementRole.NAVIGATION, y = 900),
        )
        apCheckEq("feed", ScreenClassifier.classify(feed).kind, ScreenKind.FEED)
        // Composer: focused input + send.
        val composer = apModel(
            DISCORD,
            apEl("field", hint = "Message", cls = "android.widget.EditText", role = ElementRole.INPUT, focused = true, y = 100),
            apEl("send", text = "Send", cls = "android.widget.Button", role = ElementRole.BUTTON, y = 200),
            keyboardVisible = true,
        )
        apCheckEq("composer", ScreenClassifier.classify(composer).kind, ScreenKind.COMPOSER)
        // Settings: settings-like labels.
        val settings = apModel(FB, apEl("s1", text = "Settings", y = 100))
        apCheckEq("settings", ScreenClassifier.classify(settings).kind, ScreenKind.SETTINGS)
        // Home: launcher-like package.
        val home = apModel("com.google.android.apps.nexuslauncher", apEl("icon", text = "YouTube", y = 100))
        apCheckEq("home", ScreenClassifier.classify(home).kind, ScreenKind.HOME)
        // Unknown: thin evidence is never invented into a kind.
        val thin = apModel(YT, apEl("t1", text = "Home", y = 100))
        apCheckEq("unknown on thin evidence", ScreenClassifier.classify(thin).kind, ScreenKind.UNKNOWN)
    }

    // ---------- structure hash ----------
    run {
        val a = apModel(YT, apEl("r1", text = "Davido — Fall", y = 120), apEl("r2", text = "Davido — If", y = 220))
        val b = apModel(YT, apEl("r1", text = "Wizkid — Essence", y = 120), apEl("r2", text = "Burna Boy — Ye", y = 220))
        apCheckEq("same structure, different text → same hash", ScreenClassifier.structureHash(a), ScreenClassifier.structureHash(b))
        val c = apModel(YT, apEl("r1", text = "Davido — Fall", cls = "android.widget.Button", role = ElementRole.BUTTON, y = 120))
        apCheck("different structure → different hash", ScreenClassifier.structureHash(a) != ScreenClassifier.structureHash(c))
    }

    // ---------- seeds present for all 5 apps ----------
    run {
        val store = AppProfileStore()
        val yt = store.profileFor(YT, "YouTube")
        apCheckEq("youtube seed category", yt.category, AppCategory.VIDEO)
        apCheckEq(
            "youtube seed order",
            yt.preferredSubmitOrder,
            listOf(SubmitMechanism.IME_ENTER, SubmitMechanism.KEYBOARD_ACTION_KEY, SubmitMechanism.SEARCH_BUTTON),
        )
        val ytm = store.profileFor(YTM, "YT Music")
        apCheckEq("yt music seed category", ytm.category, AppCategory.MUSIC)
        apCheckEq(
            "yt music seed order",
            ytm.preferredSubmitOrder,
            listOf(SubmitMechanism.IME_ENTER, SubmitMechanism.KEYBOARD_ACTION_KEY, SubmitMechanism.SEARCH_BUTTON),
        )
        val ig = store.profileFor(IG, "Instagram")
        apCheckEq("instagram seed category", ig.category, AppCategory.SOCIAL)
        apCheckEq(
            "instagram seed order",
            ig.preferredSubmitOrder,
            listOf(SubmitMechanism.IME_ENTER, SubmitMechanism.SUGGESTION_TAP, SubmitMechanism.KEYBOARD_ACTION_KEY),
        )
        val fb = store.profileFor(FB, "Facebook")
        apCheckEq("facebook seed category", fb.category, AppCategory.SOCIAL)
        apCheckEq(
            "facebook seed order",
            fb.preferredSubmitOrder,
            listOf(SubmitMechanism.IME_ENTER, SubmitMechanism.SEARCH_BUTTON, SubmitMechanism.KEYBOARD_ACTION_KEY),
        )
        val dc = store.profileFor(DISCORD, "Discord")
        apCheckEq("discord seed category", dc.category, AppCategory.MESSAGING)
        apCheckEq(
            "discord seed order",
            dc.preferredSubmitOrder,
            listOf(SubmitMechanism.IME_ENTER, SubmitMechanism.KEYBOARD_ACTION_KEY, SubmitMechanism.SUGGESTION_TAP),
        )
        // AOIE Phase 6: a seventh seed — Phone Manager (SYSTEM,
        // inspect-only recipe, UNVERIFIED). The count is the seed
        // list, not a magic number.
        // MASTER Wave 3 (F9): three more UNVERIFIED seeds — Play
        // Store, Phone dialer, Settings — for the three "couldn't
        // find" failures in the video. Package + hint still need
        // device confirmation on David's Redmi 14C.
        val play = store.profileFor("com.android.vending", "Play Store")
        apCheckEq("play store seed category", play.category, AppCategory.SYSTEM)
        apCheck("play store seed hint", play.searchEntryHints.contains("Search for apps & games"))
        val phone = store.profileFor("com.google.android.dialer", "Phone")
        apCheckEq("phone seed category", phone.category, AppCategory.SYSTEM)
        apCheck("phone seed hint", phone.searchEntryHints.contains("Search contacts"))
        val settings = store.profileFor("com.android.settings", "Settings")
        apCheckEq("settings seed category", settings.category, AppCategory.SYSTEM)
        apCheck("settings seed hint", settings.searchEntryHints.contains("Search settings"))
        apCheckEq("ten seeds", AppProfileStore.SEED_PROFILES.size, 10)
    }

    // ---------- store ordering: wins shift the order ----------
    run {
        val store = AppProfileStore()
        apCheckEq(
            "unknown app → default order",
            store.preferredOrder("com.example.unknown"),
            DEFAULT_SUBMIT_ORDER,
        )
        // Two wins for SEARCH_BUTTON move it first for YouTube.
        store.recordOutcome(YT, SubmitMechanism.SEARCH_BUTTON, succeeded = true, signatureHash = 1)
        store.recordOutcome(YT, SubmitMechanism.SEARCH_BUTTON, succeeded = true, signatureHash = 1)
        store.recordOutcome(YT, SubmitMechanism.IME_ENTER, succeeded = true, signatureHash = 1)
        val order = store.preferredOrder(YT)
        apCheckEq("wins shift the order", order.first(), SubmitMechanism.SEARCH_BUTTON)
        apCheckEq("observations counted", store.profileFor(YT).observations, 3)
        apCheckEq("wins recorded", store.profileFor(YT).mechanismWins[SubmitMechanism.SEARCH_BUTTON], 2)
        // A failure bumps observations but not wins.
        store.recordOutcome(YT, SubmitMechanism.SEARCH_BUTTON, succeeded = false, signatureHash = 1)
        apCheckEq("failure keeps wins", store.profileFor(YT).mechanismWins[SubmitMechanism.SEARCH_BUTTON], 2)
        apCheckEq("failure bumps observations", store.profileFor(YT).observations, 4)
    }

    // ---------- learner invalidation on structure change ----------
    run {
        val store = AppProfileStore()
        val learner = AppProfileLearner(store)
        val v1 = apModel(YT, apEl("r1", text = "Davido — Fall", y = 120))
        val first = learner.learn(v1, YT, "YouTube", SubmitMechanism.IME_ENTER, succeeded = true)
        apCheckEq("first learn: no structure change", first.structureChanged, false)
        apCheckEq("first learn: one win", first.profile.mechanismWins[SubmitMechanism.IME_ENTER], 1)
        // Same structure again: no reset, wins accumulate.
        val same = learner.learn(v1, YT, "YouTube", SubmitMechanism.IME_ENTER, succeeded = true)
        apCheckEq("same structure: no reset", same.structureChanged, false)
        apCheckEq("same structure: wins accumulate", same.profile.mechanismWins[SubmitMechanism.IME_ENTER], 2)
        // Interface changed (different class/role multiset): wins reset.
        val v2 = apModel(
            YT,
            apEl("r1", text = "Davido — Fall", cls = "android.widget.Button", role = ElementRole.BUTTON, y = 120),
            apEl("r2", text = "Davido — If", cls = "android.widget.Button", role = ElementRole.BUTTON, y = 220),
        )
        val changed = learner.learn(v2, YT, "YouTube", SubmitMechanism.KEYBOARD_ACTION_KEY, succeeded = true)
        apCheckEq("changed structure: flagged", changed.structureChanged, true)
        apCheckEq("changed structure: old wins gone", changed.profile.mechanismWins[SubmitMechanism.IME_ENTER], null)
        apCheckEq("changed structure: new win recorded", changed.profile.mechanismWins[SubmitMechanism.KEYBOARD_ACTION_KEY], 1)
        apCheckEq("changed structure: order back to default-first", store.preferredOrder(YT).first(), SubmitMechanism.KEYBOARD_ACTION_KEY)
    }

    // ---------- KNOWLEDGE BASE (2026-11-09) ----------
    run {
        val store = ScreenKnowledgeStore()
        val entry = store.recordObservation(kbSearchEntryModel())
        apCheckEq("kb: kind is SEARCH_ENTRY", entry.screenKind, ScreenKind.SEARCH_ENTRY)
        apCheck("kb: affordances name the search field", "search-field" in entry.affordances)
        apCheckEq("kb: search entry hint kept", entry.searchEntryHint, "Search YouTube Music")
        apCheck("kb: clear control found", entry.clearControl != null)
        apCheckEq("kb: clear control label", entry.clearControl?.label, "Clear search")
        apCheckEq("kb: clear control operated by tap", entry.clearControl?.howOperated, "tap")
        apCheckEq("kb: back behaviour is BUTTON on positive measurement", entry.backBehaviour, BackBehaviour.BUTTON)
        apCheck("kb: back button bounds kept", entry.backButtonBounds != null)
        apCheckEq("kb: no winning mechanism yet", entry.winningSubmitMechanism, null)
        apCheckEq("kb: first observation counted", entry.observations, 1)
        apCheck("kb: controls recorded", entry.controls.isNotEmpty())
    }
    run {
        val store = ScreenKnowledgeStore()
        val entry = store.recordObservation(kbSearchEntryModel(backButton = null))
        apCheckEq("kb: back stays UNKNOWN without a measurement", entry.backBehaviour, BackBehaviour.UNKNOWN)
        apCheckEq("kb: no back bounds without a measurement", entry.backButtonBounds, null)
    }
    run {
        // A known BUTTON back is never flipped by a later
        // observation that simply did not measure it.
        val store = ScreenKnowledgeStore()
        store.recordObservation(kbSearchEntryModel())
        val again = store.recordObservation(kbSearchEntryModel(backButton = null))
        apCheckEq("kb: missing measurement never un-records BUTTON", again.backBehaviour, BackBehaviour.BUTTON)
        apCheck("kb: back bounds survive the unmeasured observation", again.backButtonBounds != null)
    }
    run {
        // PERSISTENCE ACROSS RESTART: record a screen + a win, drop
        // the holder, build a new store over the SAME backing map.
        val backing = mutableMapOf<String, String>()
        val ctx = MapContext(backing)
        val store1 = ScreenKnowledgeStoreHolder.getOrCreate(ctx)
        val model = kbSearchEntryModel()
        store1.recordObservation(model)
        store1.recordSubmitWin(model, SubmitMechanism.KEYBOARD_ACTION_KEY, succeeded = true)
        ScreenKnowledgeStoreHolder.setForTest(null)
        val store2 = ScreenKnowledgeStoreHolder.getOrCreate(ctx)
        val entry = store2.knowledgeForScreen(model)
        apCheck("kb: knowledge survives restart", entry != null)
        apCheckEq("kb: winning mechanism survives restart", entry?.winningSubmitMechanism, SubmitMechanism.KEYBOARD_ACTION_KEY)
        apCheckEq("kb: clear label survives restart", entry?.clearControl?.label, "Clear search")
        apCheckEq("kb: back behaviour survives restart", entry?.backBehaviour, BackBehaviour.BUTTON)
        apCheckEq("kb: kind survives restart", entry?.screenKind, ScreenKind.SEARCH_ENTRY)
        apCheck("kb: backing map actually holds the entry", backing.keys.any { it.startsWith("screen|") })
        ScreenKnowledgeStoreHolder.setForTest(null)
    }
    run {
        // CONSULTATION ON THE NEXT PLAN: a known screen's winner
        // goes first; an unknown screen keeps the fallback order.
        val store = ScreenKnowledgeStore()
        val model = kbSearchEntryModel()
        store.recordObservation(model)
        store.recordSubmitWin(model, SubmitMechanism.SEARCH_BUTTON, succeeded = true)
        val fallback = listOf(
            SubmitMechanism.IME_ENTER, SubmitMechanism.KEYBOARD_ACTION_KEY,
            SubmitMechanism.SEARCH_BUTTON,
        )
        val order = store.preferredSubmitOrderForScreen(model, fallback)
        apCheckEq("kb: winner first on a known screen", order.first(), SubmitMechanism.SEARCH_BUTTON)
        apCheckEq("kb: order keeps every mechanism", order.size, fallback.size)
        apCheckEq(
            "kb: unknown screen keeps fallback order",
            store.preferredSubmitOrderForScreen(apModel(IG, apEl("ig-x")), fallback),
            fallback,
        )
    }
    run {
        // A failed submit records no win — one failure does not
        // disprove the winner.
        val store = ScreenKnowledgeStore()
        val model = kbSearchEntryModel()
        store.recordObservation(model)
        store.recordSubmitWin(model, SubmitMechanism.IME_ENTER, succeeded = false)
        apCheckEq("kb: failed submit records no win", store.knowledgeForScreen(model)?.winningSubmitMechanism, null)
    }
    run {
        // FAIL-SAFE: a disk backend that cannot be read or written
        // never crashes the store and never loses the session's
        // learning.
        val store = ScreenKnowledgeStore(ThrowingKnowledgePersistence())
        val model = kbSearchEntryModel()
        val entry = store.recordObservation(model)
        apCheckEq("kb: throwing persistence still records in-session", entry.screenKind, ScreenKind.SEARCH_ENTRY)
        store.recordSubmitWin(model, SubmitMechanism.IME_ENTER, succeeded = true)
        apCheckEq(
            "kb: throwing persistence keeps the win in-session",
            store.knowledgeForScreen(model)?.winningSubmitMechanism,
            SubmitMechanism.IME_ENTER,
        )
    }
    run {
        // A corrupt persisted entry is skipped; a valid entry next
        // to it still loads.
        val backing = mutableMapOf<String, String>()
        backing["screen|com.example|123"] = "not|enough|fields"
        val ctx = MapContext(backing)
        val store1 = ScreenKnowledgeStoreHolder.getOrCreate(ctx)
        val model = kbSearchEntryModel()
        store1.recordObservation(model)
        ScreenKnowledgeStoreHolder.setForTest(null)
        val store2 = ScreenKnowledgeStoreHolder.getOrCreate(ctx)
        apCheck("kb: corrupt entry skipped, valid entry loads", store2.knowledgeForScreen(model) != null)
        ScreenKnowledgeStoreHolder.setForTest(null)
    }
    run {
        // Back-recovery flag: attempted by default, remembered when
        // it fails, cleared when it verifies. Clear control is
        // consultable for a known screen, null for an unknown one.
        val store = ScreenKnowledgeStore()
        val model = kbSearchEntryModel()
        store.recordObservation(model)
        apCheck("kb: back recovery attempted by default", store.shouldAttemptBackRecovery(model))
        store.recordBackRecovery(model, succeeded = false)
        apCheck("kb: failed back recovery is not repeated", !store.shouldAttemptBackRecovery(model))
        store.recordBackRecovery(model, succeeded = true)
        apCheck("kb: verified back recovery clears the flag", store.shouldAttemptBackRecovery(model))
        apCheck("kb: clear control consultable", store.clearControlFor(model) != null)
        apCheck(
            "kb: clear control unknown on unknown screen",
            store.clearControlFor(apModel(IG, apEl("ig-x"))) == null,
        )
    }
    run {
        // AppProfileStore fail-safe: a disk backend that throws
        // never crashes the store and never loses the session's
        // learning.
        val store = AppProfileStore(ThrowingAppProfilePersistence())
        store.recordOutcome(YT, SubmitMechanism.SEARCH_BUTTON, succeeded = true, signatureHash = 1)
        apCheckEq(
            "appprof: throwing persistence keeps learning in-session",
            store.profileFor(YT).mechanismWins[SubmitMechanism.SEARCH_BUTTON],
            1,
        )
    }

    println("AppProfileTest: $apPassed passed, $apFailed failed")
    if (apFailed == 0) {
        println("ALL $apPassed TESTS PASSED")
    } else {
        throw AssertionError("$apFailed test(s) failed")
    }
}
