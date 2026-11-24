package com.loom.assistant.execution.search

import com.loom.assistant.core.common.KeyboardAction
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SubmitVerification

/**
 * AOIE Phase 2 — keyboard / submit repair (blueprint §6, AT-02).
 *
 * Two parts:
 *
 * 1. [SubmitDiagnostic]: the specific failure reasons the blueprint
 *    requires, instead of one generic "search failed". Every submit
 *    failure is classified into one of these, and the reason travels
 *    in the failure text so David (and the device tests) can tell
 *    "field not found" apart from "submit dispatched but nothing
 *    changed" apart from "results never appeared".
 *
 * 2. [ImeActionResolver]: which keyboard action the focused field
 *    wants. PLATFORM LIMIT, stated plainly: an AccessibilityService
 *    cannot read another app's EditorInfo/imeOptions — Android
 *    exposes EditorInfo only to the input method itself, never to
 *    an accessibility service — so there is no API that returns
 *    SEARCH vs GO vs DONE vs NEXT vs SEND for a field. The resolver
 *    therefore infers the intended action from what the tree DOES
 *    expose (the focused field's hint/text and the keyboard's
 *    visible action-key label, where the keyboard exposes one) and
 *    says UNKNOWN when the evidence is thin, instead of guessing.
 *    The SubmitChain then tries mechanisms in the profile order
 *    and the verifier judges the outcome — the inference narrows
 *    the first mechanism tried, it never decides success.
 *
 * The [KeyboardAction] intents (SUBMIT / CONFIRM / DISMISS /
 * NEWLINE) are now used in production: the plan dispatcher maps a
 * SUBMIT step's `keyboardAction` param onto an intent, and
 * DISMISS is a real recovery (global Back dismisses the keyboard)
 * instead of the old "dismiss-and-retry" label with no dismiss
 * behind it.
 */
enum class SubmitDiagnostic(val label: String) {
    /** No search field could be found on screen. */
    FIELD_NOT_FOUND("search field not found"),

    /** Text was refused into the field (no focus, or the field
     *  rejected the input). */
    TEXT_ENTRY_REJECTED("text entry rejected"),

    /** No submit mechanism is available on this screen (no IME
     *  action, no keyboard key, no suggestion, no button). */
    IME_ACTION_UNAVAILABLE("IME action unavailable"),

    /** A submit mechanism dispatched but the screen did not change
     *  and no results appeared. */
    SUBMIT_DISPATCHED_NO_TRANSITION("submit dispatched but no transition"),

    /** The screen was still loading when the results wait expired. */
    LOADING_TIMEOUT("loading timeout"),

    /** Submit executed but no results were detected. */
    RESULTS_NOT_DETECTED("results not detected"),

    /** The app navigated away mid-search (wrong package). */
    APP_NAVIGATION_CHANGED("app navigation changed"),

    /** The service / gate / permission needed is unavailable. */
    PERMISSION_LIMITED("permission or service limitation"),

    /** Could not tell from the snapshots — never success. */
    UNKNOWN("unknown submit failure"),
}

object SubmitDiagnosticClassifier {

    /** Classifies a submit-chain outcome into a diagnostic. */
    fun classify(
        verification: SubmitVerification,
        before: ScreenModel?,
        after: ScreenModel?,
        dispatchedAny: Boolean,
        expectedPackage: String?,
    ): SubmitDiagnostic {
        if (before == null || after == null) return SubmitDiagnostic.PERMISSION_LIMITED
        if (!expectedPackage.isNullOrBlank() && after.packageName != expectedPackage) {
            return SubmitDiagnostic.APP_NAVIGATION_CHANGED
        }
        return when (verification) {
            is SubmitVerification.Executed -> SubmitDiagnostic.UNKNOWN
            is SubmitVerification.NotExecuted -> {
                if (!dispatchedAny) SubmitDiagnostic.IME_ACTION_UNAVAILABLE
                else if (after.keyboardVisible) SubmitDiagnostic.SUBMIT_DISPATCHED_NO_TRANSITION
                else SubmitDiagnostic.SUBMIT_DISPATCHED_NO_TRANSITION
            }
            is SubmitVerification.Unknown -> {
                if (!dispatchedAny) SubmitDiagnostic.IME_ACTION_UNAVAILABLE
                else SubmitDiagnostic.UNKNOWN
            }
        }
    }
}

/** What the focused field's keyboard action most likely is, from
 *  tree evidence only. UNKNOWN means "no evidence — do not guess". */
enum class InferredImeAction {
    SEARCH, GO, DONE, NEXT, SEND, ENTER, UNKNOWN,
}

object ImeActionResolver {

    /**
     * Infers the action from the focused field (hint/text first —
     * e.g. YouTube's "Search YouTube", Chrome's "Search or type
     * URL") and from the keyboard's own action-key label when the
     * keyboard exposes one as a node ([keyboardActionKeyLabel]).
     * The keyboard label wins when present: it is the key the user
     * would press. A search field's hint wins over a generic key
     * label only when the key label is absent.
     */
    fun resolve(
        focusedField: ScreenElement?,
        keyboardActionKeyLabel: String? = null,
    ): InferredImeAction {
        keyboardActionKeyLabel?.let { label ->
            fromWord(label)?.let { return it }
        }
        val fieldText = listOfNotNull(
            focusedField?.hintText,
            focusedField?.text,
            focusedField?.contentDescription,
        ).joinToString(" ")
        fromWord(fieldText)?.let { return it }
        return InferredImeAction.UNKNOWN
    }

    private fun fromWord(text: String): InferredImeAction? {
        val t = text.lowercase()
        return when {
            "search" in t -> InferredImeAction.SEARCH
            "go" in t.split(Regex("\\W+")) -> InferredImeAction.GO
            "done" in t -> InferredImeAction.DONE
            "next" in t -> InferredImeAction.NEXT
            "send" in t -> InferredImeAction.SEND
            "enter" in t -> InferredImeAction.ENTER
            else -> null
        }
    }

    /** The intent a SUBMIT step with this inferred action carries. */
    fun intentFor(action: InferredImeAction): KeyboardAction = when (action) {
        InferredImeAction.DONE, InferredImeAction.NEXT ->
            KeyboardAction.CONFIRM
        else -> KeyboardAction.SUBMIT
    }

    /** Which submit mechanisms fit this inferred action, most
     *  likely first. The chain still tries them in the profile
     *  order — this is the tie-break the planner uses when it
     *  builds a per-call order, never a bypass of the verifier. */
    fun preferredMechanisms(action: InferredImeAction): List<com.loom.assistant.core.common.SubmitMechanism> =
        when (action) {
            InferredImeAction.SEARCH, InferredImeAction.GO, InferredImeAction.SEND ->
                listOf(
                    com.loom.assistant.core.common.SubmitMechanism.KEYBOARD_ACTION_KEY,
                    com.loom.assistant.core.common.SubmitMechanism.IME_ENTER,
                    com.loom.assistant.core.common.SubmitMechanism.SEARCH_BUTTON,
                    com.loom.assistant.core.common.SubmitMechanism.SUGGESTION_TAP,
                )
            else -> SubmitChain.DEFAULT_ORDER
        }
}
