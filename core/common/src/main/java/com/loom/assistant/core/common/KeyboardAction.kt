package com.loom.assistant.core.common

/**
 * App-aware execution (2026-11-07, David's directive §1) — the
 * keyboard / submit vocabulary, in core:common so both
 * execution:search and intelligence:planner can use it with no new
 * module edge.
 *
 * Two different ideas live here and must never be conflated:
 *  - [SubmitMechanism]: HOW a submit is physically performed.
 *  - [KeyboardAction]: WHAT the user (or LOOM) intends the keyboard
 *    to do. Typing, submitting, confirming, dismissing and inserting
 *    a newline are different intents — a submit is not a confirm, and
 *    a dismiss is not a submit. The executor must pick the intent
 *    that matches the step, then pick a mechanism that carries it.
 */

/** How a search submit is physically performed. */
enum class SubmitMechanism {
    /** IME Enter / the keyboard's submit keypress (device.submit). */
    IME_ENTER,

    /** Tap the keyboard's own Search / Go / Done key inside the IME window. */
    KEYBOARD_ACTION_KEY,

    /** Tap a suggestion row — the tap itself submits the search. */
    SUGGESTION_TAP,

    /** Tap an on-screen search button. */
    SEARCH_BUTTON,
}

/** What the keyboard is being asked to do. Distinct intents, distinct labels. */
enum class KeyboardAction(val intent: String) {
    /** Run the search / submit the field's content. */
    SUBMIT("submit"),

    /** Confirm / accept (e.g. an OK / Done confirmation). */
    CONFIRM("confirm"),

    /** Dismiss the keyboard / close the input without submitting. */
    DISMISS("dismiss"),

    /** Insert a newline in the field (never a submit). */
    NEWLINE("newline"),
}
