package com.loom.assistant.execution.screenawareness

/**
 * NAV-SCREEN fix (2026-11-11, David's order ~19:29 WAT) — N2, the
 * bottom-nav / tab resolver (blueprint §5.5 applied to navigation
 * chrome).
 *
 * The audit's Cause A2: "tap Reels" is text matching, and an
 * icon-only tab has no text to match. The resolver below reads the
 * NAVIGATION-role container and enumerates its tabs in reading
 * order (top, then left), matching in tiers:
 *   (a) label — text / content-description / hint via TargetAliases
 *       (perspective swap and close-match included, voice-mic
 *       exclusion included);
 *   (b) synonym — a small DATA table of destination names
 *       ("reels" ↔ "video clips", "notifications" ↔ "bell", ...),
 *       matched the same way;
 *   (c) ordinal — "the third tab" / "3rd tab" / "tab 3" resolves
 *       positionally inside the container;
 *   (d) positional fallback — a KNOWN destination name whose label
 *       tier failed resolves by its known index in a known per-app
 *       layout, and only when the container's child count matches
 *       that layout exactly. Coordinates are never invented: a tab
 *       with no label, no synonym, no ordinal and no positional
 *       reading fails honestly (empty list).
 *
 * Positional layouts are data taken from David's own screenshot
 * (233839_0_rkwt.jpg, Facebook feed, 720×1640, Nov 11 19:13 WAT):
 * six icon-only tabs — Home, Watch, Reels (3rd), an unidentified
 * megaphone icon (index 3, NOT mapped — its destination is unknown,
 * so it is never a positional answer), Notifications bell, profile
 * avatar (Menu). Index 3 is deliberately absent from the table:
 * guessing it would tap the wrong destination. These layouts are
 * UNPROVEN on device (no tree dump yet — audit open item E-1); the
 * label tier wins whenever the tree exposes a label, so a layout
 * change can only break the fallback, never a labelled tab.
 *
 * The resolver is pure Kotlin over [ScreenModel]; no Android service
 * coupling. It is consulted by findTargetElements (fallback tier),
 * the executor's pointer drive, the dispatcher's NAVIGATE_TAB
 * branch, and GuidanceSession.resolveTarget.
 */

/** One tab inside a navigation container, in reading order. */
data class NavTab(
    val element: ScreenElement,
    /** 0-based position inside its container. */
    val index: Int,
    /** Best label the tree gives (text → description → hint → class). */
    val label: String,
)

/** How a tab was resolved — the trace names the tier, never just "found". */
enum class NavResolutionTier {
    LABEL,
    SYNONYM,
    ORDINAL,
    POSITIONAL,
}

data class NavResolution(
    val element: ScreenElement,
    val tier: NavResolutionTier,
    val index: Int,
)

object NavigationResolver {

    /**
     * Known in-app destinations, as DATA (not code branches).
     * The key is the canonical destination; the values are the
     * words David may say for it. "shorts" is YouTube's short-video
     * tab; "reels" is Facebook/Instagram's. Both are kept — the
     * resolver matches the word, the screen decides which tab it is.
     */
    val DESTINATION_SYNONYMS: Map<String, List<String>> = mapOf(
        "reels" to listOf("reels", "reel", "video clips", "clips", "short video", "short videos"),
        "shorts" to listOf("shorts", "short video", "short videos"),
        "watch" to listOf("watch", "videos", "video"),
        "home" to listOf("home", "feed", "news feed"),
        "notifications" to listOf("notifications", "notification", "bell", "alerts"),
        "menu" to listOf("menu", "profile", "account", "my profile", "your profile"),
        "search" to listOf("search"),
        "library" to listOf("library", "saved", "collections"),
        "subscriptions" to listOf("subscriptions", "subscription", "subs"),
        "friends" to listOf("friends"),
        "marketplace" to listOf("marketplace", "shop"),
        "groups" to listOf("groups"),
        "messages" to listOf("messages", "messenger", "inbox", "chats"),
        "settings" to listOf("settings"),
    )

    /**
     * Per-app positional layouts: package → (expected tab count →
     * destination → 0-based index). Screenshot-derived (see file
     * header); index 3 on Facebook is deliberately unmapped.
     */
    val POSITIONAL_LAYOUTS: Map<String, Map<String, Int>> = mapOf(
        "com.facebook.katana" to mapOf(
            "home" to 0,
            "watch" to 1,
            "reels" to 2,
            "notifications" to 4,
            "menu" to 5,
        ),
    )

    /** Expected tab count per positional layout package. */
    private val POSITIONAL_TAB_COUNTS: Map<String, Int> = mapOf(
        "com.facebook.katana" to 6,
    )

    /**
     * Normalizes a destination phrase to the bare destination
     * name, for exact matching only. Strips leading verbs
     * ("open/go to/tap/..."), a leading "the", a trailing " tab",
     * and a trailing " on/in <app>" mention — so "open Reels on
     * Facebook", "go to the Reels tab" and "Reels" all normalize
     * to "reels".
     *
     * Mark 57 transfer (2026-11-11, M6) — this replaces the old
     * `endsWith(" $synonym")` breadth in [isKnownDestination]:
     * a trailing-word match made "a cat video" route to Watch
     * (it ends with " video", a Watch synonym) and "watch the
     * cat video" likewise. A destination is now matched ONLY by
     * its exact normalized name — a phrase that merely ENDS in a
     * destination word is a different thing (a video to watch,
     * not the Watch tab), and must not navigate.
     */
    fun normalizeDestinationQuery(query: String): String {
        var q = query.trim().lowercase()
            .trimEnd('.', '!', '?').trim()
        if (q.isBlank()) return q
        val verbs = listOf(
            "take me to ", "switch to ", "go to ", "open ",
            "show ", "tap ", "click on ", "click ", "select ", "go ",
        )
        var changed = true
        while (changed) {
            changed = false
            for (v in verbs) {
                if (q.startsWith(v)) {
                    q = q.removePrefix(v).trim()
                    changed = true
                }
            }
            if (q.startsWith("the ")) {
                q = q.removePrefix("the ").trim()
                changed = true
            }
        }
        if (q.endsWith(" tab")) q = q.removeSuffix(" tab").trim()
        // Trailing " on <app>" / " in <app>" mention.
        val appMention = Regex("""\s+(on|in)\s+\S+$""").find(q)
        if (appMention != null) q = q.substring(0, appMention.range.first).trim()
        if (q.startsWith("the ")) q = q.removePrefix("the ").trim()
        return q
    }

    /** True when [query] names a known in-app destination (any synonym, case-insensitive). */
    fun isKnownDestination(query: String): Boolean {
        val q = normalizeDestinationQuery(query)
        if (q.isBlank()) return false
        return DESTINATION_SYNONYMS.any { (dest, synonyms) ->
            q == dest || synonyms.contains(q)
        }
    }

    /** Canonical destination for [query], or null when it names none. */
    fun canonicalDestination(query: String): String? {
        val q = normalizeDestinationQuery(query)
        if (q.isBlank()) return null
        for ((dest, synonyms) in DESTINATION_SYNONYMS) {
            if (q == dest || synonyms.contains(q)) return dest
        }
        return null
    }

    /** True when [query] is an ordinal tab phrasing ("third tab", "3rd tab", "tab 3"). */
    fun parseOrdinalTab(query: String): Int? {
        val q = normalizeDestinationQuery(query)
        if (q.isBlank()) return null
        val words = mapOf(
            "first" to 0, "second" to 1, "third" to 2, "fourth" to 3,
            "fifth" to 4, "sixth" to 5, "seventh" to 6, "eighth" to 7,
        )
        for ((word, idx) in words) {
            if (q == "$word tab" || q == word) return idx
        }
        // "tab 3" survives normalization (no " tab" suffix to
        // strip — it is a suffix, not the word "tab" at the end…
        // actually "tab 3" ends in "3", so it is intact here).
        val digits = Regex("""^(\d+)(st|nd|rd|th)?\s*tab$|^tab\s*(\d+)$|^(\d+)(st|nd|rd|th)?$""").find(q)
        if (digits != null) {
            val n = (digits.groupValues[1].ifBlank { digits.groupValues[3] }.ifBlank { digits.groupValues[4] }).toIntOrNull()
            if (n != null && n >= 1) return n - 1
        }
        return null
    }

    /** True when [query] is chrome — a known destination or an ordinal tab. Scroll hunts must never run for it (N8). */
    fun isChromeQuery(query: String): Boolean =
        isKnownDestination(query) || parseOrdinalTab(query) != null

    /** The NAVIGATION-role containers on [screen], in reading order. */
    fun navigationContainers(screen: ScreenModel): List<ScreenElement> =
        screen.elements.filter { it.role == ElementRole.NAVIGATION && !it.bounds.isEmpty }

    /**
     * The tabs of the first navigation container: its enabled
     * children with non-empty bounds, in reading order (top, then
     * left). Children are found by containment inside the
     * container's bounds; when no child is contained (flat tree —
     * some apps expose tabs as siblings), the fallback is the
     * container's own enabled siblings in the same bottom band.
     * An empty list means "no tabs enumerable", never "no navigation".
     */
    fun enumerateTabs(screen: ScreenModel): List<NavTab> {
        val container = navigationContainers(screen).minByOrNull { it.bounds.top } ?: return emptyList()
        val children = screen.elements.filter { el ->
            el !== container && el.enabled && !el.bounds.isEmpty &&
                el.bounds.left >= container.bounds.left &&
                el.bounds.top >= container.bounds.top &&
                el.bounds.right <= container.bounds.right &&
                el.bounds.bottom <= container.bounds.bottom
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        if (children.isNotEmpty()) {
            return children.mapIndexed { i, el -> NavTab(el, i, el.label()) }
        }
        // Flat tree: tabs as siblings in the container's bottom band.
        val band = screen.elements.filter { el ->
            el !== container && el.enabled && !el.bounds.isEmpty &&
                el.bounds.top >= container.bounds.top &&
                el.bounds.bottom <= container.bounds.bottom + 4
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        return band.mapIndexed { i, el -> NavTab(el, i, el.label()) }
    }

    /**
     * Resolves [query] to tabs, tier by tier. Knowledge first,
     * then label, synonym, ordinal, positional fallback. Returns
     * an empty list when nothing resolves — never a guessed
     * coordinate. Several matches at the label/synonym tier are
     * all returned (callers refuse on ambiguity, as with every
     * other resolver); ordinal/positional tiers return at most one.
     *
     * [preferredPosition] / [preferredLabel] are the knowledge
     * base's reading ([ScreenKnowledge.preferredNavTabFor] — the
     * tab this screen showed for this destination before, by
     * label or by position in a known layout). It is consulted
     * FIRST, but the LIVE tree wins: the tab at the preferred
     * position is used only when its live label still matches
     * the destination — stale knowledge (a layout that moved)
     * falls through to the normal tiers instead of tapping a
     * tab that is no longer the destination. The resolver takes
     * primitives, not the knowledge type: this module cannot
     * depend on the planner (dependency direction).
     */
    fun resolveNavigationTarget(
        screen: ScreenModel,
        query: String,
        preferredPosition: Int? = null,
        preferredLabel: String? = null,
    ): List<NavResolution> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val tabs = enumerateTabs(screen)
        if (tabs.isEmpty()) return emptyList()
        // (0) knowledge tier — the tab this screen showed for this
        // destination before, validated against the LIVE tab at
        // that position. Stale knowledge falls through.
        if (preferredPosition != null) {
            val known = tabs.getOrNull(preferredPosition)
            if (known != null &&
                (TargetAliases.matchesTargetQuery(known.element, q) ||
                    (preferredLabel != null &&
                        TargetAliases.matchesTargetQuery(known.element, preferredLabel)))
            ) {
                return listOf(
                    NavResolution(known.element, NavResolutionTier.POSITIONAL, known.index),
                )
            }
        }
        // (a) label tier — TargetAliases over the tab elements.
        val labelMatches = TargetAliases.resolveElements(tabs.map { it.element }, q)
        if (labelMatches.isNotEmpty()) {
            return labelMatches.mapNotNull { el ->
                tabs.firstOrNull { it.element.id == el.id }
                    ?.let { NavResolution(el, NavResolutionTier.LABEL, it.index) }
            }
        }
        // (b) synonym tier — the destination's other names.
        val dest = canonicalDestination(q)
        if (dest != null) {
            val synonymMatches = DESTINATION_SYNONYMS[dest].orEmpty()
                .flatMap { synonym -> TargetAliases.resolveElements(tabs.map { it.element }, synonym) }
                .distinctBy { it.id }
            if (synonymMatches.isNotEmpty()) {
                return synonymMatches.mapNotNull { el ->
                    tabs.firstOrNull { it.element.id == el.id }
                        ?.let { NavResolution(el, NavResolutionTier.SYNONYM, it.index) }
                }
            }
        }
        // (c) ordinal tier — "the third tab".
        val ordinal = parseOrdinalTab(q)
        if (ordinal != null) {
            val tab = tabs.getOrNull(ordinal) ?: return emptyList()
            return listOf(NavResolution(tab.element, NavResolutionTier.ORDINAL, tab.index))
        }
        // (d) positional fallback — known destination, known layout,
        // exact tab count. Anything else fails honestly.
        if (dest != null) {
            val layout = POSITIONAL_LAYOUTS[screen.packageName] ?: return emptyList()
            val expectedCount = POSITIONAL_TAB_COUNTS[screen.packageName] ?: return emptyList()
            if (tabs.size != expectedCount) return emptyList()
            val index = layout[dest] ?: return emptyList()
            val tab = tabs.getOrNull(index) ?: return emptyList()
            return listOf(NavResolution(tab.element, NavResolutionTier.POSITIONAL, tab.index))
        }
        return emptyList()
    }

    /** The currently selected tab, if the tree reports one. Null when selection state is unknown. */
    fun selectedTab(screen: ScreenModel): NavTab? =
        enumerateTabs(screen).firstOrNull { it.element.selected }

    /** True when [destination] is the selected tab on [screen]. */
    fun isDestinationSelected(screen: ScreenModel, destination: String): Boolean {
        val selected = selectedTab(screen) ?: return false
        val resolved = resolveNavigationTarget(screen, destination)
        return resolved.any { it.element.id == selected.element.id }
    }
}
