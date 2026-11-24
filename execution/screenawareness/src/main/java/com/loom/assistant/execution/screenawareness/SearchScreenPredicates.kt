package com.loom.assistant.execution.screenawareness

/**
 * MASTER Wave 3 (F8 / F14a) — the results-vs-suggestions distinction,
 * in one place, so the submit verifier, the search sequence, the plan
 * verifier and the screen classifier can never drift apart again.
 *
 * The YouTube incident (video 2026-11-08, corrected Nov 9): the
 * search sat UN-submitted on the suggestions screen for ~90 s
 * (frames 346/367/397 identical) while the outcome falsely claimed
 * "opened Nigeria versus Japan" 5 times. The mechanism, confirmed
 * from code: every results gate passed on a suggestions screen —
 * [SubmitVerifier.resultsPresent] returned true for ANY `LIST` role
 * (the suggestions screen IS a list), `ScreenClassifier.
 * isSearchResults` returned true on one matching row inside a list,
 * and `searchResultsShown`'s defensive fallback accepted
 * `list + one matching row`. A suggestions list is NOT results, a
 * spinner is NOT results, and typed text in the box is NOT results.
 *
 * The distinction, from what the tree actually exposes:
 *  - SUGGESTIONS screen: the keyboard is up AND a search field
 *    holds focus AND a list is present. Suggestions only exist
 *    while the user is typing.
 *  - RESULTS screen: not a suggestions screen, and the screen
 *    shows content the search PRODUCED — at least two
 *    query-matching non-field rows, or one matching row inside a
 *    results LIST — and only once the input is CLOSED (keyboard
 *    gone AND no field focused). While the user is still typing,
 *    matching rows are suggestions — however many there are, and
 *    whether or not the list carries a LIST role. One lone row
 *    with no list is a suggestion, not results, even with the
 *    input closed: a single snapshot carries no evidence a search
 *    ran (an empty search box and one row is a home screen, not
 *    results — the SimpleTaskExecutionTest F fixture). The search
 *    sequence's result SELECTION is a separate, weaker gate (the
 *    submit was already verified there, so one verified row is
 *    selectable); this predicate is the results gate, and it stays
 *    strict.
 *  - LOADING screen: a progress indicator with no list and no
 *    matching rows — the search is still running, never results.
 */
object SearchScreenPredicates {

    /** Query terms shorter than this are ignored for result matching. */
    private const val MIN_TERM_LENGTH = 3

    /**
     * Device-failure fix (2026-11-13, F2) — words that carry no
     * identifying meaning in a query: bottom-nav / chrome labels
     * ("home", "menu", "watch"), filler words, and meta-speech
     * residue. A term in this set NEVER satisfies a row match on
     * its own: "home" / "no" alone must not match LOOM's own chat
     * screen or a bottom nav. A query made ONLY of such words
     * falls back to the full-phrase match (the whole phrase must
     * appear in the row).
     */
    private val GENERIC_TERMS = setOf(
        "home", "way", "man", "menu", "search", "notifications",
        "notification", "watch", "reels", "profile", "settings",
        "back", "close", "more", "the", "and", "for", "you", "your",
        "with", "from", "this", "that", "are", "not", "doing",
        "anything", "still", "quiet", "quite",
    )

    /**
     * True when the screen is a suggestions screen: keyboard visible,
     * a field holds focus, and a list is present. While this holds the
     * search has NOT left the entry screen — it has not succeeded,
     * whatever the list contains.
     */
    fun isSuggestionsScreen(model: ScreenModel): Boolean {
        if (!model.keyboardVisible) return false
        if (model.elements.none { it.focused && isField(it) }) return false
        return model.elements.any { it.role == ElementRole.LIST }
    }

    /**
     * True when the screen shows a loading spinner (a progress
     * indicator) with no list and no query-matching rows. A spinner
     * is NOT results — the search is still running.
     */
    fun isLoadingScreen(model: ScreenModel, query: String): Boolean {
        val hasProgress = model.elements.any {
            it.className.contains("ProgressBar", ignoreCase = true) ||
                it.className.contains("LoadingSpinner", ignoreCase = true) ||
                it.contentDescription?.contains("loading", ignoreCase = true) == true
        }
        if (!hasProgress) return false
        if (model.elements.any { it.role == ElementRole.LIST }) return false
        return matchingRows(model, query) == 0
    }

    /**
     * Results present: the screen shows content the search produced.
     * NEVER true on a suggestions screen or a loading screen, and
     * NEVER true while the input is open. A list alone is not
     * results; matching rows while the user is still typing are
     * suggestions, not results — the input is closed only when the
     * keyboard is gone AND no field holds focus. (Either half alone
     * is not enough: a tree can leave the field unmarked while the
     * keyboard is up, and focus can linger on a stale element
     * after the keyboard dismisses.) And one row alone is a
     * suggestion, not results, unless it sits in a results LIST:
     * a single snapshot carries no evidence a search ran, so a lone
     * row with no list never counts here — result SELECTION after a
     * verified submit is a separate gate (see the search sequence),
     * and it is the only place one row is selectable.
     */
    /**
     * Device-failure fix (2026-11-13, F2) — when [expectedPackage]
     * is given, the observed screen's package MUST equal it: results
     * "shown" on any other screen (including LOOM's own chat) are
     * not results. A blank/unknown observed package fails, never
     * passes. Callers that know the target app MUST pass it; the
     * null default keeps the predicate usable where no app is
     * named (and those callers' own foreground polls own the
     * package check — see SearchExecutor).
     */
    fun resultsPresent(model: ScreenModel, query: String, expectedPackage: String? = null): Boolean {
        if (!expectedPackage.isNullOrBlank() && model.packageName != expectedPackage) return false
        if (model.elements.isEmpty()) return false
        if (isSuggestionsScreen(model)) return false
        if (isLoadingScreen(model, query)) return false
        // One matching row counts only once the input is closed:
        // keyboard gone AND no field focused. On the suggestions
        // screen the input is open, so this never fires there (and
        // the guard above already excludes that screen).
        val inputClosed = !model.keyboardVisible &&
            model.elements.none { it.focused && isField(it) }
        if (!inputClosed) return false
        val matching = matchingRows(model, query)
        if (matching >= 2) return true
        if (matching == 1) {
            return model.elements.any { it.role == ElementRole.LIST }
        }
        return false
    }

    /** Non-field, enabled, visible elements whose text/desc matches the query. */
    fun matchingRows(model: ScreenModel, query: String): Int =
        model.elements.count { matchesQuery(it, query) }

    /**
     * True when one element is a query-matching result row: a
     * non-field, enabled, visible element whose text or description
     * matches the query. Shared by [matchingRows] and by the
     * new-rows arms of the search/plan verifiers, so a row can never
     * count as a result in one place and not in another.
     */
    fun matchesQuery(el: ScreenElement, query: String): Boolean {
        if (isField(el) || !el.enabled || el.bounds.isEmpty) return false
        // Device-failure fix (2026-11-13, F2): navigation chrome is
        // NEVER a result row — bottom-nav labels ("Home",
        // "Notifications") must not satisfy a query match.
        if (el.role == ElementRole.NAVIGATION) return false
        if (el.text?.isBlank() != false && el.contentDescription?.isBlank() != false) return false
        val normalizedQuery = query.lowercase().trim().replace(Regex("\\s+"), " ")
        if (normalizedQuery.isBlank()) return false
        val terms = normalizedQuery.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= MIN_TERM_LENGTH && it !in GENERIC_TERMS }
        val haystacks = listOfNotNull(
            el.text?.lowercase(),
            el.contentDescription?.lowercase(),
        )
        return if (terms.isEmpty()) {
            // No meaningful term (a query of only generic / short
            // words): the WHOLE phrase must appear in the row —
            // never a single generic word.
            haystacks.any { it.contains(normalizedQuery) }
        } else {
            terms.any { term -> haystacks.any { it.contains(term) } }
        }
    }

    /**
     * Viewport signature: the (id, label) set of the screen. Two
     * snapshots with the same signature show the same viewport — a
     * scroll that leaves it unchanged hit the end of the list
     * (F14b). Labels are included so a content change without an id
     * change still counts as a change.
     */
    fun viewportSignature(model: ScreenModel?): Set<Pair<String, String>> =
        model?.elements?.map { it.id to it.label() }?.toSet() ?: emptySet()

    /** True when the viewport changed between two snapshots. */
    fun viewportChanged(before: ScreenModel?, after: ScreenModel?): Boolean =
        viewportSignature(before) != viewportSignature(after)

    private fun isField(el: ScreenElement): Boolean =
        el.role == ElementRole.INPUT || el.role == ElementRole.SEARCH_FIELD
}
