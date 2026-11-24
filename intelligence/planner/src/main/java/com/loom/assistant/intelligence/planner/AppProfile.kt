package com.loom.assistant.intelligence.planner

import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * App-aware execution (2026-11-07, David's directive §2) — the
 * GENERAL app-awareness mechanism.
 *
 * LOOM recognises the app and the screen it is on, and adapts how it
 * submits — YouTube is not Instagram is not Facebook is not Discord.
 * The mechanism is general, not per-app if/else: a pure
 * [ScreenClassifier] reads the tree into a [ScreenSignature], and an
 * [AppProfileStore] + [AppProfileLearner] keep one learned profile
 * per package (preferred submit order, mechanism wins, last-seen
 * structure). The five seed profiles below are DATA — the starting
 * knowledge the learner updates from observed outcomes. Nothing in
 * the executor or the search sequence branches on a package name.
 */

/** What kind of app this is (drives the default submit order). */
enum class AppCategory {
    VIDEO, SOCIAL, MESSAGING, MUSIC, BROWSER, SYSTEM, OTHER,
}

/** What kind of screen this is, read from the tree — never invented. */
enum class ScreenKind {
    HOME, FEED, SEARCH_ENTRY, SEARCH_RESULTS, DETAIL, COMPOSER, SETTINGS, DIALOG, UNKNOWN,
}

/**
 * What the classifier saw: the package, the kind, the affordances
 * present, and a hash of the screen's structure (the sorted
 * (role, className) multiset). When the structure hash changes, the
 * app's interface changed — the learner resets the mechanism wins.
 */
data class ScreenSignature(
    val packageName: String,
    val kind: ScreenKind,
    val affordanceKeys: Set<String>,
    val elementCount: Int,
    val structureHash: Int,
)

/**
 * Pure tree reading. Rules (first match wins):
 *  - DIALOG: any DIALOG role.
 *  - COMPOSER: a focused INPUT plus a send affordance.
 *  - SEARCH_RESULTS: a results list, or ≥2 query-matching rows
 *    (query optional). A list is a results list only when its rows
 *    match the query, or when there is no navigation chrome beside
 *    it — a list WITH navigation and no matching rows is a FEED.
 *  - SEARCH_ENTRY: a focused SEARCH_FIELD / INPUT with the keyboard
 *    visible and no results list.
 *  - SETTINGS: settings-like labels.
 *  - FEED: a LIST plus navigation chrome.
 *  - HOME: a launcher-like package.
 *  - UNKNOWN: evidence too thin to name a kind — never invented.
 */
object ScreenClassifier {

    private val SETTINGS_WORDS = listOf("settings", "preferences", "options")

    fun classify(model: ScreenModel, query: String? = null): ScreenSignature {
        val elements = model.elements
        val affordances = affordanceKeys(model)
        val kind = when {
            elements.any { it.role == ElementRole.DIALOG } -> ScreenKind.DIALOG
            isComposer(model) -> ScreenKind.COMPOSER
            isSearchResults(model, query) -> ScreenKind.SEARCH_RESULTS
            isSearchEntry(model) -> ScreenKind.SEARCH_ENTRY
            AppContextProvider.isDetailScreen(model) -> ScreenKind.DETAIL
            elements.any { el ->
                SETTINGS_WORDS.any { w ->
                    (el.text?.contains(w, ignoreCase = true) == true) ||
                        (el.contentDescription?.contains(w, ignoreCase = true) == true)
                }
            } -> ScreenKind.SETTINGS
            elements.any { it.role == ElementRole.LIST } &&
                elements.any { it.role == ElementRole.NAVIGATION } -> ScreenKind.FEED
            model.packageName.contains("launcher", ignoreCase = true) -> ScreenKind.HOME
            else -> ScreenKind.UNKNOWN
        }
        return ScreenSignature(
            packageName = model.packageName,
            kind = kind,
            affordanceKeys = affordances,
            elementCount = elements.size,
            structureHash = structureHash(model),
        )
    }

    private fun isComposer(model: ScreenModel): Boolean {        val focusedInput = model.elements.any {
            it.focused && it.role == ElementRole.INPUT
        }
        if (!focusedInput) return false
        return model.elements.any { el ->
            (el.text?.equals("send", ignoreCase = true) == true) ||
                (el.contentDescription?.contains("send", ignoreCase = true) == true)
        }
    }

    private fun isSearchResults(model: ScreenModel, query: String?): Boolean {
        // MASTER Wave 3 (F8/F14a) — a suggestions screen is NOT
        // results: keyboard up, a field focused, a list present.
        // The old rule returned true on one matching row inside a
        // list, so the suggestions screen classified as results and
        // the YouTube incident falsely claimed success.
        if (com.loom.assistant.execution.screenawareness.SearchScreenPredicates
                .isSuggestionsScreen(model)
        ) return false
        val hasList = model.elements.any { it.role == ElementRole.LIST }
        val hasNav = model.elements.any { it.role == ElementRole.NAVIGATION }
        if (query.isNullOrBlank()) {
            // No query: a bare list with no navigation is a results
            // list; a list WITH navigation is a feed, not results.
            return hasList && !hasNav
        }
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        var matchingRows = 0
        for (el in model.elements) {
            if (el.role == ElementRole.INPUT || el.role == ElementRole.SEARCH_FIELD) continue
            if (!el.enabled || el.bounds.isEmpty) continue
            val matches = if (terms.isEmpty()) {
                (el.text?.contains(query, ignoreCase = true) == true) ||
                    (el.contentDescription?.contains(query, ignoreCase = true) == true)
            } else {
                terms.any { term ->
                    (el.text?.lowercase()?.contains(term) == true) ||
                        (el.contentDescription?.lowercase()?.contains(term) == true)
                }
            }
            if (matches) {
                matchingRows++
                // Two matching rows are results on their own; one
                // matching row inside a list is a results list.
                if (matchingRows >= 2) return true
                if (hasList) return true
            }
        }
        return false
    }

    private fun isSearchEntry(model: ScreenModel): Boolean {
        if (!model.keyboardVisible) return false
        if (model.elements.any { it.role == ElementRole.LIST }) return false
        return model.elements.any {
            it.focused && (it.role == ElementRole.SEARCH_FIELD || it.role == ElementRole.INPUT)
        }
    }

    /** The affordances present on this screen, as stable keys. */
    fun affordanceKeys(model: ScreenModel): Set<String> {
        val keys = mutableSetOf<String>()
        for (el in model.elements) {
            when {
                el.role == ElementRole.SEARCH_FIELD -> keys += "search-field"
                el.role == ElementRole.INPUT &&
                    ((el.hintText?.contains("search", ignoreCase = true) == true) ||
                        (el.text?.contains("search", ignoreCase = true) == true)) -> keys += "search-field"
                el.role == ElementRole.INPUT -> keys += "field"
                el.role == ElementRole.LIST -> keys += "list"
                el.role == ElementRole.NAVIGATION -> keys += "nav"
                el.role == ElementRole.DIALOG -> keys += "dialog"
            }
            if (el.text?.equals("send", ignoreCase = true) == true ||
                (el.contentDescription?.contains("send", ignoreCase = true) == true)
            ) {
                keys += "send"
            }
            if (SETTINGS_WORDS.any { w -> el.text?.contains(w, ignoreCase = true) == true }) {
                keys += "settings"
            }
        }
        return keys
    }

    /** Hash of the sorted (role, className) multiset — structure only, no content. */
    fun structureHash(model: ScreenModel): Int {
        val shape = model.elements
            .map { it.role.name to it.className }
            .sortedWith(compareBy({ it.first }, { it.second }))
        return shape.hashCode()
    }
}

/** The standard submit order when an app has no learned preference. */
val DEFAULT_SUBMIT_ORDER: List<SubmitMechanism> = listOf(
    SubmitMechanism.IME_ENTER,
    SubmitMechanism.KEYBOARD_ACTION_KEY,
    SubmitMechanism.SUGGESTION_TAP,
    SubmitMechanism.SEARCH_BUTTON,
)

/** One app's learned profile. [mechanismWins] counts verified submit wins per mechanism.
 *
 * AOIE Phase 6 — the blueprint §5 recipe fields. A profile is no
 * longer submit-orders only: [verificationStatus] records where the
 * profile sits in the learning loop (UNVERIFIED → TESTED →
 * VERIFIED, never promoted without evidence); [recipes] are the
 * verified interaction recipes (open search → enter → submit →
 * wait → verify → inspect), each validated against the live screen
 * before execution, never assumed; [lastValidatedAt] /
 * [appVersion] / [knownLimitations] are the provenance the
 * blueprint requires. Seeds start UNVERIFIED — starting knowledge,
 * not a verified recipe. */
enum class ProfileVerificationStatus { UNVERIFIED, TESTED, VERIFIED }

/** One verified interaction recipe for an app. [steps] are the
 *  step kinds in order; [preconditions] are the live-screen facts
 *  that must hold before the recipe runs (checked by the executor,
 *  never assumed). */
data class AppRecipe(
    val name: String,
    val steps: List<TaskStepKind>,
    val preconditions: List<String> = emptyList(),
    val verified: Boolean = false,
    val evidence: String = "",
)

data class AppProfile(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val preferredSubmitOrder: List<SubmitMechanism>,
    val searchEntryHints: List<String> = emptyList(),
    val observations: Int = 0,
    val mechanismWins: Map<SubmitMechanism, Int> = emptyMap(),
    val lastSignatureHash: Int? = null,
    val verificationStatus: ProfileVerificationStatus = ProfileVerificationStatus.UNVERIFIED,
    val lastValidatedAt: Long? = null,
    val appVersion: String? = null,
    val knownLimitations: List<String> = emptyList(),
    val recipes: List<AppRecipe> = emptyList(),
)

/** Persistence seam for profiles. Default is in-memory (no disk). */
interface AppProfilePersistence {
    fun load(): Map<String, AppProfile>
    fun save(profiles: Map<String, AppProfile>)
}

/** In-memory persistence: profiles live for the process only. */
class InMemoryAppProfilePersistence : AppProfilePersistence {
    private var stored: Map<String, AppProfile> = emptyMap()
    override fun load(): Map<String, AppProfile> = stored
    override fun save(profiles: Map<String, AppProfile>) {
        stored = profiles.toMap()
    }
}

/**
 * The profile store: one [AppProfile] per package, seeded with the
 * five apps David uses, updated by the learner from observed
 * outcomes. Seeded profiles are DATA, not branches — the classifier
 * + learner are the general mechanism.
 */
class AppProfileStore(
    private val persistence: AppProfilePersistence = InMemoryAppProfilePersistence(),
) {
    private val profiles: MutableMap<String, AppProfile> = LinkedHashMap()

    init {
        for (seed in SEED_PROFILES) {
            profiles[seed.packageName] = seed
        }
        // Persisted profiles overlay the seeds (a learned profile
        // outranks the starting knowledge); unknown packages load as-is.
        // KNOWLEDGE BASE fail-safe (2026-11-09): a disk backend that
        // cannot be read must never crash the store — start from
        // seeds and keep learning in memory.
        val persisted = try {
            persistence.load()
        } catch (_: Exception) {
            emptyMap()
        }
        for ((pkg, profile) in persisted) {
            profiles[pkg] = profile
        }
    }

    fun profileFor(packageName: String, label: String = ""): AppProfile {
        return profiles[packageName] ?: AppProfile(
            packageName = packageName,
            label = label.ifBlank { packageName },
            category = AppCategory.OTHER,
            preferredSubmitOrder = DEFAULT_SUBMIT_ORDER,
        ).also { profiles[packageName] = it }
    }

    /** Wins-weighted order: most wins first, ties keep the default order. */
    fun preferredOrder(packageName: String): List<SubmitMechanism> {
        val profile = profiles[packageName] ?: return DEFAULT_SUBMIT_ORDER
        val wins = profile.mechanismWins
        val base = profile.preferredSubmitOrder.ifEmpty { DEFAULT_SUBMIT_ORDER }
        return SubmitMechanism.entries.sortedWith(
            compareByDescending<SubmitMechanism> { wins[it] ?: 0 }
                .thenBy { base.indexOf(it).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE },
        )
    }

    /**
     * Records one submit outcome. A win bumps that mechanism; any
     * outcome bumps [AppProfile.observations] and stores the latest
     * structure hash. Returns the updated profile.
     */
    fun recordOutcome(
        packageName: String,
        mechanism: SubmitMechanism,
        succeeded: Boolean,
        signatureHash: Int?,
    ): AppProfile {
        val current = profiles[packageName]
            ?: AppProfile(
                packageName = packageName,
                label = packageName,
                category = AppCategory.OTHER,
                preferredSubmitOrder = DEFAULT_SUBMIT_ORDER,
            )
        val wins = current.mechanismWins.toMutableMap()
        if (succeeded) {
            wins[mechanism] = (wins[mechanism] ?: 0) + 1
        }
        val updated = current.copy(
            observations = current.observations + 1,
            mechanismWins = wins,
            lastSignatureHash = signatureHash ?: current.lastSignatureHash,
        )
        profiles[packageName] = updated
        saveQuietly()
        return updated
    }

    /**
     * Resets an app's learned mechanism wins to the default order
     * (its interface changed — old wins no longer apply). Returns the
     * reset profile.
     */
    fun resetWins(packageName: String, signatureHash: Int?): AppProfile {
        val current = profiles[packageName] ?: return profileFor(packageName)
        val updated = current.copy(
            mechanismWins = emptyMap(),
            preferredSubmitOrder = DEFAULT_SUBMIT_ORDER,
            lastSignatureHash = signatureHash,
        )
        profiles[packageName] = updated
        saveQuietly()
        return updated
    }

    fun allProfiles(): Map<String, AppProfile> = profiles.toMap()

    /**
     * KNOWLEDGE BASE fail-safe (2026-11-09): a disk backend that
     * cannot be written must never crash the run or lose the
     * session's learning — the in-memory map already holds it, and
     * the next save retries.
     */
    private fun saveQuietly() {
        try {
            persistence.save(profiles)
        } catch (_: Exception) {
        }
    }

    /**
     * AOIE Phase 6 — records one validation of a recipe against
     * the live screen. A recipe becomes verified only here, with
     * evidence and a timestamp; the profile status rises to TESTED
     * on first evidence and to VERIFIED only when every recipe on
     * the profile is verified. Nothing here promotes an unverified
     * observation — validation is an explicit, evidenced act.
     */
    fun recordValidation(
        packageName: String,
        recipeName: String,
        evidence: String,
        appVersion: String? = null,
    ): AppProfile {
        val current = profiles[packageName] ?: return profileFor(packageName)
        val updatedRecipes = current.recipes.map { recipe ->
            if (recipe.name == recipeName) {
                recipe.copy(verified = true, evidence = evidence)
            } else recipe
        }
        val status = when {
            updatedRecipes.isNotEmpty() && updatedRecipes.all { it.verified } ->
                ProfileVerificationStatus.VERIFIED
            updatedRecipes.any { it.verified } ->
                ProfileVerificationStatus.TESTED
            else -> current.verificationStatus
        }
        val updated = current.copy(
            recipes = updatedRecipes,
            verificationStatus = status,
            lastValidatedAt = System.currentTimeMillis(),
            appVersion = appVersion ?: current.appVersion,
        )
        profiles[packageName] = updated
        saveQuietly()
        return updated
    }

    companion object {
        /** The blueprint §5 search recipe every social/video/music
         *  app shares: open search → enter query → submit → wait →
         *  verify results → inspect items. Preconditions are
         *  validated against the live screen before execution. */
        val SEARCH_RECIPE = AppRecipe(
            name = "search",
            steps = listOf(
                TaskStepKind.SEARCH_IN_APP,
                TaskStepKind.VERIFY_OUTCOME,
            ),
            preconditions = listOf(
                "app is foreground",
                "a search affordance is on screen",
                "the field takes focus before typing",
                "submit is verified by results on screen, never by dispatch alone",
            ),
        )

        /** Seed profiles — starting knowledge, updated by the learner. */
        val SEED_PROFILES: List<AppProfile> = listOf(
            AppProfile(
                packageName = "com.google.android.youtube",
                label = "YouTube",
                category = AppCategory.VIDEO,
                preferredSubmitOrder = listOf(
                    SubmitMechanism.IME_ENTER,
                    SubmitMechanism.KEYBOARD_ACTION_KEY,
                    SubmitMechanism.SEARCH_BUTTON,
                ),
                searchEntryHints = listOf("Search YouTube"),
                recipes = listOf(SEARCH_RECIPE),
            ),
            AppProfile(
                packageName = "com.google.android.apps.youtube.music",
                label = "YT Music",
                category = AppCategory.MUSIC,
                preferredSubmitOrder = listOf(
                    SubmitMechanism.IME_ENTER,
                    SubmitMechanism.KEYBOARD_ACTION_KEY,
                    SubmitMechanism.SEARCH_BUTTON,
                ),
                searchEntryHints = listOf("Search YT Music"),
                recipes = listOf(SEARCH_RECIPE),
            ),
            AppProfile(
                packageName = "com.instagram.android",
                label = "Instagram",
                category = AppCategory.SOCIAL,
                preferredSubmitOrder = listOf(
                    SubmitMechanism.IME_ENTER,
                    SubmitMechanism.SUGGESTION_TAP,
                    SubmitMechanism.KEYBOARD_ACTION_KEY,
                ),
                searchEntryHints = listOf("Search"),
                recipes = listOf(SEARCH_RECIPE),
            ),
            AppProfile(
                packageName = "com.facebook.katana",
                label = "Facebook",
                category = AppCategory.SOCIAL,
                preferredSubmitOrder = listOf(
                    SubmitMechanism.IME_ENTER,
                    SubmitMechanism.SEARCH_BUTTON,
                    SubmitMechanism.KEYBOARD_ACTION_KEY,
                ),
                searchEntryHints = listOf("Search Facebook"),
                recipes = listOf(SEARCH_RECIPE),
            ),
            AppProfile(
                packageName = "com.discord",
                label = "Discord",
                category = AppCategory.MESSAGING,
                preferredSubmitOrder = listOf(
                    SubmitMechanism.IME_ENTER,
                    SubmitMechanism.KEYBOARD_ACTION_KEY,
                    SubmitMechanism.SUGGESTION_TAP,
                ),
                searchEntryHints = listOf("Search"),
                recipes = listOf(SEARCH_RECIPE),
            ),
            // Universal Console (2026-11-07): Chrome had no seed —
            // a "Search Chrome for …" plan fell back to the default
            // order with no entry hint, even though Chrome is one of
            // the three apps the console is tested against. The
            // omnibox is a search field ("Search or type URL"); its
            // keyboard key submits like any other.
            AppProfile(
                packageName = "com.android.chrome",
                label = "Chrome",
                category = AppCategory.BROWSER,
                preferredSubmitOrder = listOf(
                    SubmitMechanism.IME_ENTER,
                    SubmitMechanism.KEYBOARD_ACTION_KEY,
                    SubmitMechanism.SEARCH_BUTTON,
                ),
                searchEntryHints = listOf("Search or type URL"),
                recipes = listOf(SEARCH_RECIPE),
            ),
            // MASTER Wave 3 (F9) — Play Store. David's "Go to Google
            // Play Store" failed twice (mode-question hijack, then
            // "couldn't find an app called Google Play Store") — the
            // label is "Play Store", and there was no seed profile.
            // Package com.android.vending is the Play Store on
            // Android; the entry hint is the search box's hint text.
            // UNVERIFIED until a device run confirms it on his phone.
            AppProfile(
                packageName = "com.android.vending",
                label = "Play Store",
                category = AppCategory.SYSTEM,
                preferredSubmitOrder = listOf(
                    SubmitMechanism.IME_ENTER,
                    SubmitMechanism.KEYBOARD_ACTION_KEY,
                    SubmitMechanism.SEARCH_BUTTON,
                ),
                searchEntryHints = listOf("Search for apps & games"),
                knownLimitations = listOf(
                    "Package com.android.vending is the standard Play Store package — confirm on device.",
                    "Entry hint \"Search for apps & games\" is the Play Store search-box hint — confirm on device.",
                ),
                recipes = listOf(SEARCH_RECIPE),
            ),
            // MASTER Wave 3 (F9) — Phone dialer. "Call Benjamin"
            // failed 3x with "couldn't find a way to search contacts
            // in Phone" while the "Search contacts" box was visible
            // at the top of the Phone screen in every frame 540–620s.
            // The dialer is a SYSTEM app; its search is the contacts
            // box, not a web search — the recipe is search-then-open,
            // and the call itself stays confirmation-gated.
            AppProfile(
                packageName = "com.google.android.dialer",
                label = "Phone",
                category = AppCategory.SYSTEM,
                preferredSubmitOrder = DEFAULT_SUBMIT_ORDER,
                searchEntryHints = listOf("Search contacts"),
                knownLimitations = listOf(
                    "Package com.google.android.dialer is the Google Dialer — confirm on device (Redmi 14C may ship the MIUI dialer instead).",
                    "Calls stay confirmation-gated; this profile only seeds the search entry.",
                ),
                recipes = listOf(SEARCH_RECIPE),
            ),
            // MASTER Wave 3 (F9) — Settings. "Couldn't find a search
            // box in settings" — there was no Settings seed profile,
            // so the generic tiers were the only route. The Settings
            // search box hint is "Search settings".
            AppProfile(
                packageName = "com.android.settings",
                label = "Settings",
                category = AppCategory.SYSTEM,
                preferredSubmitOrder = DEFAULT_SUBMIT_ORDER,
                searchEntryHints = listOf("Search settings"),
                knownLimitations = listOf(
                    "Entry hint \"Search settings\" is the Settings search-box hint — confirm on device.",
                ),
                recipes = listOf(SEARCH_RECIPE),
            ),
            // AOIE Phase 6 (AT-06) — Phone Manager. SYSTEM category:
            // a settings/utility screen, NOT a social feed — its
            // recipe is inspect-only (open + verify the screen), never
            // a search/play recipe. Package is the MIUI Security
            // centre that ships as "Phone Manager" / "Security" on
            // Xiaomi phones including David's Redmi 14C — UNVERIFIED
            // until a device run confirms the package on his phone.
            // If the package is wrong the profile simply never
            // matches; nothing branches on it.
            AppProfile(
                packageName = "com.miui.securitycenter",
                label = "Phone Manager",
                category = AppCategory.SYSTEM,
                preferredSubmitOrder = DEFAULT_SUBMIT_ORDER,
                searchEntryHints = emptyList(),
                knownLimitations = listOf(
                    "Package com.miui.securitycenter is assumed from MIUI — confirm on device (AT-06).",
                    "No search recipe: this app is inspected, not searched.",
                ),
                recipes = listOf(
                    AppRecipe(
                        name = "inspect-utility-screen",
                        steps = listOf(
                            TaskStepKind.LAUNCH_APP,
                            TaskStepKind.OBSERVE_SCREEN,
                            TaskStepKind.VERIFY_OUTCOME,
                        ),
                        preconditions = listOf(
                            "app is foreground",
                            "screen is readable (non-empty tree)",
                            "no social-media pattern is assumed",
                        ),
                    ),
                ),
            ),
        )
    }
}

/** What [AppProfileLearner.learn] did, including structure resets. */
data class LearnResult(
    val profile: AppProfile,
    /** True when the screen structure changed and wins were reset. */
    val structureChanged: Boolean,
)

/**
 * Updates a profile from one observed outcome. Bumps observations,
 * and INVALIDATES the stored signature when the structure hash
 * changed: the interface changed, so the old mechanism wins no
 * longer apply — they reset to the default order and the reset is
 * reported in [LearnResult.structureChanged].
 */
class AppProfileLearner(private val store: AppProfileStore) {

    fun learn(
        model: ScreenModel,
        packageName: String,
        label: String,
        mechanism: SubmitMechanism,
        succeeded: Boolean,
    ): LearnResult {
        val signature = ScreenClassifier.classify(model)
        val before = store.profileFor(packageName, label)
        val structureChanged = before.lastSignatureHash != null &&
            before.lastSignatureHash != signature.structureHash
        if (structureChanged) {
            store.resetWins(packageName, signature.structureHash)
        }
        val updated = store.recordOutcome(
            packageName = packageName,
            mechanism = mechanism,
            succeeded = succeeded,
            signatureHash = signature.structureHash,
        )
        return LearnResult(profile = updated, structureChanged = structureChanged)
    }
}
