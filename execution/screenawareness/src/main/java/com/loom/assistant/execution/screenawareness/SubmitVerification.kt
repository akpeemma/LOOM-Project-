package com.loom.assistant.execution.screenawareness

/**
 * App-aware execution (2026-11-07, David's directive §1) — did a
 * search actually execute?
 *
 * The failure in David's screenshot was "typed, keyboard still open,
 * no results": the text was in the field and the pointer was drawn,
 * but the search never ran. Typed text is never completion. This
 * verifier judges ONLY the before/after screen pair, conservatively:
 *
 *  - [SubmitVerification.Executed] ONLY when results are actually
 *    present (query-matching rows once the input is closed —
 *    keyboard gone AND no field focused — a single matching row
 *    while typing is a suggestion, not results) AND the keyboard
 *    is gone — or focus moved on AND the screen changed; OR the
 *    screen changed substantially AND the keyboard is gone AND no
 *    field holds focus any more.
 *  - [SubmitVerification.NotExecuted] when the keyboard is still
 *    visible AND the same field is still focused with the same text
 *    (David's screenshot case).
 *  - [SubmitVerification.Unknown] otherwise — the caller must not
 *    treat it as success.
 */
sealed class SubmitVerification {
    /** Results are present and the input is closed / focus moved on. */
    data object Executed : SubmitVerification()

    /** The search provably did not run; [reason] names the evidence. */
    data class NotExecuted(val reason: String) : SubmitVerification()

    /** Cannot tell from these two snapshots — never success. */
    data object Unknown : SubmitVerification()
}

object SubmitVerifier {

    fun verify(before: ScreenModel, after: ScreenModel, query: String): SubmitVerification {
        val beforeField = focusedField(before)
        val afterField = focusedField(after)
        // Focus lost: no field holds focus any more, or the focused
        // field is a different one. (When no field held focus before
        // either, "no focus after" still counts — the input is closed.)
        val focusLost = afterField == null ||
            (beforeField != null && afterField.id != beforeField.id)
        val keyboardGone = !after.keyboardVisible
        val results = resultsPresent(after, query)
        val changed = screenChanged(before, after)
        // MASTER Wave 3 (F8/F14a) — the suggestions screen is NOT
        // success, however it got here: a submitted search leaves
        // the suggestions screen and shows results. A spinner or a
        // suggestions screen is NOT success. When still on the
        // suggestions screen, say so plainly — never Executed.
        if (SearchScreenPredicates.isSuggestionsScreen(after)) {
            return SubmitVerification.NotExecuted(
                "still on the suggestions screen — the search never ran; " +
                    "results have not appeared yet",
            )
        }
        if (SearchScreenPredicates.isLoadingScreen(after, query)) {
            return SubmitVerification.NotExecuted(
                "the search is still loading — no results have appeared yet",
            )
        }

        // (a) Results present AND the keyboard is gone — or focus
        // moved on AND the screen changed. Focus-lost alone is not
        // enough: a suggestion row also matches the query, and with
        // an unchanged screen it must never read as results.
        if (results && (keyboardGone || (focusLost && changed))) {
            return SubmitVerification.Executed
        }
        // (b) Screen changed substantially AND keyboard gone AND no
        // field holds focus any more.
        if (changed && keyboardGone && focusLost) {
            return SubmitVerification.Executed
        }
        // The search never ran: no results, nothing changed, and the
        // field still shows the same text (keyboard open or closed).
        // This is the keyboard-dismissed case — the keyboard closed
        // without the search running.
        val afterFieldSame = beforeField?.let { bf ->
            after.elements.firstOrNull { it.id == bf.id }
        }
        if (!results && !changed && beforeField != null && afterFieldSame != null &&
            afterFieldSame.text == beforeField.text
        ) {
            return SubmitVerification.NotExecuted(
                "the field still shows the same text and no results appeared — the search never ran",
            )
        }
        // David's screenshot case: keyboard still open, same field
        // still focused with the same text — typed, never submitted.
        if (after.keyboardVisible && beforeField != null && afterField != null &&
            afterField.id == beforeField.id && afterField.text == beforeField.text
        ) {
            return SubmitVerification.NotExecuted(
                "keyboard still open and the same field is still focused " +
                    "with the same text — the search never ran",
            )
        }
        return SubmitVerification.Unknown
    }

    /** The field the tree reports as focused, if any. */
    fun focusedField(model: ScreenModel): ScreenElement? =
        model.elements.firstOrNull { it.focused }

    /**
     * Results present: the canonical predicate
     * ([SearchScreenPredicates.resultsPresent]) — content the search
     * PRODUCED, never a bare list. The old rule returned true for
     * ANY `LIST` role, and the suggestions screen IS a list: the
     * verifier passed a failed submit and the outcome falsely
     * claimed "opened Nigeria versus Japan" 5 times while nothing
     * was opened (video 2026-11-08, corrected Nov 9). A suggestions
     * list or a spinner is NOT results.
     */
    fun resultsPresent(model: ScreenModel, query: String): Boolean =
        SearchScreenPredicates.resultsPresent(model, query)

    /** True when the element set changed between two snapshots (ids or labels). */
    fun screenChanged(before: ScreenModel, after: ScreenModel): Boolean {
        val beforeById = before.elements.associate { it.id to it.label() }
        val afterById = after.elements.associate { it.id to it.label() }
        return beforeById != afterById
    }
}
