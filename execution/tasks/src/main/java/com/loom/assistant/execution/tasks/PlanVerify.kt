package com.loom.assistant.execution.tasks

import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SearchScreenPredicates
import com.loom.assistant.execution.screenawareness.TargetAliases
import com.loom.assistant.execution.screenawareness.VideoPresence
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.TargetBounds

/**
 * Stage 2D (Worker B) — the shared screen-matching helpers for the plan
 * executor ([TaskPlanExecutor]) and the step dispatcher
 * ([PlanStepDispatcher], platform:services).
 *
 * Everything here reads a REDACTED [ScreenModel] only (ScreenDataPolicy
 * already stripped password / sensitive-app text before the model
 * existed). Nothing here logs element text — callers pass only step
 * names and match counts to the audit sink.
 */

/** Words that carry no identifying meaning in an EXPECTED string. */
private val EXPECTED_STOPWORDS = setOf(
    "results", "for", "the", "that", "check", "is", "was", "sent", "placed",
    "started", "appeared", "moved", "message", "call", "now", "a", "an",
    "to", "in", "on", "of", "and", "my", "it", "this", "by",
)

/**
 * Elements matching [query] the way GuidanceSession does (B1): enabled,
 * non-empty bounds, and the query (case-insensitive) in text,
 * content-description, hint, or class name. Zero matches = target gone;
 * more than one = ambiguous, never guessed.
 *
 * MASTER Wave 3 (F9) — semantic matching via [TargetAliases]:
 * "x" also matches close / clear / navigate-up / back (the X button
 * is an icon-only control whose tree text never contains "x"), and a
 * "search" query never matches the voice-search microphone (its
 * contentDescription is "Voice search" — the mic LOOM tapped instead
 * of a video result, video ~442s).
 *
 * Screen-confirmation + shutdown wave (2026-11-10, David's
 * common-sense rule) — resolution is ranked via
 * [TargetAliases.resolveElements]: exact matches first (including
 * the perspective swap, "my story" → "Your story"), and only when
 * nothing matches exactly, a single clear close match ("versus" vs
 * "vs", a typo). Several close matches still come back as several —
 * callers refuse/ask instead of guessing.
 */
fun findTargetElements(screen: ScreenModel, query: String): List<ScreenElement> {
    val q = query.lowercase()
    if (q.isBlank()) return emptyList()
    val aliasMatches = TargetAliases.resolveElements(screen.elements, query)
    if (aliasMatches.isNotEmpty()) return aliasMatches
    // NAV-SCREEN fix (2026-11-11, N2): fallback tier for a query
    // that names a known navigation destination — an icon-only tab
    // has no label for the alias tier to match, so the tab resolver
    // (label → synonym → ordinal → positional) gets its turn. Only
    // for known destinations; every other query still fails honestly.
    if (com.loom.assistant.execution.screenawareness.NavigationResolver.isKnownDestination(query) ||
        com.loom.assistant.execution.screenawareness.NavigationResolver.parseOrdinalTab(query) != null
    ) {
        return com.loom.assistant.execution.screenawareness.NavigationResolver
            .resolveNavigationTarget(screen, query).map { it.element }
    }
    return emptyList()
}

/**
 * Screen-confirmation + shutdown wave (2026-11-10) — the
 * voice-search landing: the screen LOOM lands on after tapping the
 * voice-search microphone instead of a result ("Search with your
 * voice" permission screen, video ~442s). It shows a voice-search
 * affordance, no video, and no row matching the query. A tap that
 * lands here opened nothing — it must never verify as a successful
 * open. A real player screen (Pause control / video present) or a
 * results screen (query-matching rows) is never a voice landing.
 */
fun isVoiceSearchLanding(screen: ScreenModel, query: String): Boolean {
    val labels = screen.elements.map { it.label().lowercase() }
    val voicePrompt = screen.elements.any { el ->
        val text = ((el.text ?: "") + " " + (el.contentDescription ?: "")).lowercase()
        text.contains("search with your voice") ||
            text.trim() == "voice search" ||
            text.contains("tap the microphone")
    }
    if (!voicePrompt) return false
    if (VideoPresence.videoPresent(screen)) return false
    if (SearchScreenPredicates.matchingRows(screen, query) > 0) return false
    return labels.isNotEmpty()
}

/** True when the element is a text-entry field (INPUT or SEARCH_FIELD). */
fun isFieldElement(el: ScreenElement): Boolean =
    el.role == ElementRole.INPUT || el.role == ElementRole.SEARCH_FIELD

/** The identifying words of an EXPECTED string (stopwords dropped). */
fun expectedWords(expected: String): List<String> =
    expected.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in EXPECTED_STOPWORDS }

/**
 * Whether the fresh [screen] shows the [expected] outcome.
 *
 * - Playback ("playing <query>"): requires playback evidence AND
 *   the query words on screen — a results list alone is not
 *   playback, and a "playing" label without the song is not the song.
 *   Playback evidence (simple-task execution fix, 2026-11-07): the
 *   old rule required a literal "playing" label, which YouTube's
 *   player screen may never expose in the accessibility tree — the
 *   final step could be unpassable even while music played. A
 *   visible Pause control is playback evidence too (a player that
 *   offers Pause is playing), as is "now playing". This is still a
 *   heuristic, stated honestly: it proves a player is up with the
 *   query on screen, not that audio is audible — only David's ears
 *   prove that.
 * - Anything else: at least one identifying word of [expected] appears
 *   in some element's label. An EXPECTED with no identifying words
 *   (e.g. "done") passes on a readable screen — there is nothing to
 *   match against, so readability is the only check.
 */
/**
 * Device-failure fix (2026-11-13, F2) — when [expectedPackage] is
 * given and non-blank, the observed screen's package MUST equal it;
 * a blank/unknown observed package fails, never passes. A VERIFY
 * step that names its app verifies ONLY in that app.
 */
fun expectedOnScreen(screen: ScreenModel, expected: String, expectedPackage: String? = null): Boolean {
    if (!expectedPackage.isNullOrBlank() && screen.packageName != expectedPackage) return false
    if (screen.elements.isEmpty()) return false
    val words = expectedWords(expected)
    val labels = screen.elements.map { it.label().lowercase() }
    val lower = expected.lowercase()
    if ("playing" in lower) {
        // "playing" / "now playing" text, or a Pause control: the
        // player is up. (Pause is the control a playing player
        // shows; a paused player shows Play instead.)
        val playbackShown = labels.any { it.contains("playing") || it.contains("pause") }
        if (!playbackShown) return false
        // The query words are the EXPECTED words minus "playing"
        // itself: "playing" in the label must not satisfy the
        // song check, or any playing screen would match any song.
        val queryWords = words.filter { it != "playing" }
        if (queryWords.isEmpty()) return true
        // A generic media-kind query ("playing music", "playing a
        // video") names no song: any player that is up with any
        // title satisfies it — the song title need not contain the
        // word "music". A NAMED query ("playing Davido") still
        // needs the name on screen.
        val genericMedia = setOf("music", "song", "songs", "tune", "tunes", "video", "videos")
        if (queryWords.all { it in genericMedia }) return true
        return queryWords.any { w -> labels.any { it.contains(w) } }
    }
    if (words.isEmpty()) {
        // Device-failure fix (2026-11-13, F2): an EXPECTED with no
        // identifying words passes on a readable screen ONLY outside
        // LOOM's own chat — readability of LOOM's own UI is never
        // evidence of an external effect. (LOOM's application id;
        // duplicated from TaskPlanExecutor.LOOM_PACKAGE_NAME so this
        // file keeps no executor dependency.)
        return screen.packageName != "com.loom.assistant"
    }
    return words.any { w -> labels.any { it.contains(w) } }
}

/**
 * Whether the fresh [screen] shows real search results for
 * [query] — the SEARCH_IN_APP verification (simple-task execution
 * fix, 2026-11-07). The old check passed when ANY query word
 * appeared in ANY label, which the search box's own text or hint
 * could satisfy — typed text counted as results. Now the evidence
 * is content the search PRODUCED:
 *  - the screen classifies as SEARCH_RESULTS (a results list, or
 *    ≥2 query-matching rows), or
 *  - at least two NEW content rows appeared versus [before] (the
 *    screen the search started from) — a generic query ("music")
 *    returns songs that need not contain the word "music", so
 *    matching rows alone would reject real results; new rows are
 *    the evidence the search ran and returned content. One new row
 *    is a suggestion, not results; the box alone (no new rows) is
 *    never results. Without [before] only the canonical predicate
 *    applies.
 */
/**
 * Device-failure fix (2026-11-13, F2) — [expectedPackage]: when
 * given and non-blank, the observed screen's package MUST equal it
 * (and a blank/unknown observed package fails). The [before] screen
 * is only a baseline for new rows; the package gate applies to the
 * observed screen. Callers verifying a search in a named app MUST
 * pass the step's PACKAGE.
 */
fun searchResultsShown(
    screen: ScreenModel,
    query: String,
    before: ScreenModel? = null,
    expectedPackage: String? = null,
): Boolean {
    if (!expectedPackage.isNullOrBlank() && screen.packageName != expectedPackage) return false
    if (screen.elements.isEmpty()) return false
    // MASTER Wave 3 (F8/F14a) — the canonical predicate is the whole
    // check now: a suggestions screen or a spinner is NOT results,
    // whatever the classifier's second look says. The old defensive
    // fallback (`list + one matching row`) passed on the suggestions
    // screen — the same weak arm as the SubmitVerifier's.
    if (SearchScreenPredicates.isSuggestionsScreen(screen)) return false
    if (SearchScreenPredicates.isLoadingScreen(screen, query)) return false
    // MASTER Wave 3 (F8/F14a, hardened after the search-runner
    // tests) — the classifier arm is gone: it returned true on one
    // matching row, which is a suggestion, not results. New rows
    // count only when they MATCH the query and the input is closed
    // (keyboard gone AND no field focused): suggestion rows while
    // typing match the query by definition, so an open input must
    // never let them pass as results. The canonical predicate is
    // the final word.
    val inputClosed = !screen.keyboardVisible &&
        screen.elements.none { it.focused && (it.role == ElementRole.INPUT || it.role == ElementRole.SEARCH_FIELD) }
    if (before != null) {
        val beforeIds = before.elements.map { it.id to it.label() }.toSet()
        val newRows = screen.elements.count { el ->
            SearchScreenPredicates.matchesQuery(el, query) &&
                (el.id to el.label()) !in beforeIds
        }
        if (inputClosed && newRows >= 2) return true
        if (newRows == 0) return false
    }
    return SearchScreenPredicates.resultsPresent(screen, query, expectedPackage)
}

/**
 * Device-test failures (2026-11-11, D-07) — the OPEN_FIRST bar.
 * A screen change into the same results list is NOT an opened
 * result: the search sequence may have submitted and landed back on
 * the list, or the tap may have done nothing at all. An opened
 * result is either a player up on screen ([VideoPresence.playerUp])
 * or a screen that is no longer the results list for the query
 * (navigated away from it). A results screen with no player up
 * fails — the result was not opened, whatever changed on screen.
 */
fun openFirstLanded(
    after: ScreenModel,
    query: String,
    before: ScreenModel?,
    expectedPackage: String? = null,
): Boolean {
    if (!expectedPackage.isNullOrBlank() && after.packageName != expectedPackage) return false
    if (VideoPresence.playerUp(after)) return true
    return !searchResultsShown(after, query, before, expectedPackage)
}

/** True when the screen changed between two snapshots (ids or labels). */
fun screenChanged(before: ScreenModel?, after: ScreenModel?): Boolean {
    if (before == null || after == null) return after != null
    val beforeById = before.elements.associate { it.id to it.label() }
    val afterById = after.elements.associate { it.id to it.label() }
    return beforeById != afterById
}

/** [ScreenElement.bounds] as the planner's plain-Kotlin bounds. */
fun boundsOf(el: ScreenElement): TargetBounds =
    TargetBounds(el.bounds.left, el.bounds.top, el.bounds.right, el.bounds.bottom)

/** Parses a [PlanParams.BOUNDS] "left,top,right,bottom" param, or null. */
fun parseBoundsParam(raw: String?): TargetBounds? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    return if (parts.size == 4) TargetBounds(parts[0], parts[1], parts[2], parts[3]) else null
}
