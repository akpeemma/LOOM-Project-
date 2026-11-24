package com.loom.assistant.interaction.pointer

import com.loom.assistant.execution.screenawareness.ScreenElement

/**
 * POINTER MOUSE-VERBS (2026-11-05, David's direction 18:38): text
 * selection model — pure, no Android imports, JVM-testable.
 *
 * SELECT is the pointer's text-selection verb: the lead dart gathers on
 * a text element, the selection is made through the AccessibilityService
 * path, and COPY copies exactly that selection. Two hard rules live
 * here and in [GuidanceSession]:
 *  - password fields are NEVER selectable ([isSelectable] is false when
 *    [ScreenElement.isPassword]) — [ScreenDataPolicy] redaction applies;
 *  - one user-directed selection at a time, select-all of ONE element
 *    ("select this paragraph"); no bulk selection, no silent clipboard
 *    reads — copy only ever copies the exact selection the user asked
 *    for, and never in a sensitive app.
 *
 * SAFETY FLOOR: no AuthGate key exists for select/copy yet (proposed
 * `select.text` / `copy.selection`, CONFIRM, never grant-eligible).
 * Until David approves those keys, [GuidanceSession.requestSelect] /
 * [requestCopy] verify + preview + narrate and then refuse honestly
 * at the gate — nothing is selected, nothing reaches the clipboard.
 * This file only models WHAT a selection is, so the day the keys are
 * approved the session + service sink wire up with no model change.
 */
object PointerSelection {

    /** One user-directed selection: a range inside one element's text. */
    data class TextSelection(
        /** The element the selection belongs to (stable across snapshots). */
        val elementId: String,
        /** Human-readable label for narration ("Selecting Search…"). */
        val label: String,
        /** The exact text selected — this and only this may be copied. */
        val text: String,
        /** Selection start offset inside the element's text. */
        val start: Int,
        /** Selection end offset (exclusive) inside the element's text. */
        val end: Int,
    )

    /**
     * True when [element] may be selected at all: it is enabled, has
     * non-empty bounds, carries text, and is NOT a password field.
     * Password fields are never selected or copied — no exceptions.
     */
    fun isSelectable(element: ScreenElement): Boolean =
        element.enabled && !element.bounds.isEmpty && !element.isPassword &&
            !selectableText(element).isNullOrBlank()

    /** The text a selection would cover: text first, content-description fallback. */
    fun selectableText(element: ScreenElement): String? =
        element.text?.takeIf { it.isNotBlank() }
            ?: element.contentDescription?.takeIf { it.isNotBlank() }

    /**
     * Select-all of [element]'s text ("select this paragraph"): the
     * whole selectable text, offsets 0..length. Null when the element
     * is not selectable ([isSelectable]) — password fields, empty text,
     * disabled or boundless elements all refuse here, before any gate
     * or gesture is involved.
     */
    fun selectAll(element: ScreenElement): TextSelection? {
        if (!isSelectable(element)) return null
        val text = selectableText(element)!!
        return TextSelection(
            elementId = element.id,
            label = element.label(),
            text = text,
            start = 0,
            end = text.length,
        )
    }
}
