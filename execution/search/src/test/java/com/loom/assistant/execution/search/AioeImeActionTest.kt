package com.loom.assistant.execution.search

import android.graphics.Rect
import com.loom.assistant.core.common.KeyboardAction
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SubmitVerification

/**
 * AOIE Phase 2 — keyboard/search repair: IME action inference from
 * tree evidence + specific submit diagnostics.
 *
 * Platform limit (documented, not hidden): an AccessibilityService
 * cannot read another app's EditorInfo / imeOptions — Android
 * exposes that only to the input method itself. So inference is
 * hint / label based, and the verifier + mechanism rotation carry
 * the rest. UNKNOWN means "no evidence — do not guess".
 *
 * Run via test-harness/run-2c-tests.sh (runner: aioeime).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) passed++ else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passed++ else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun field(hint: String? = null, text: String? = null, desc: String? = null) =
    ScreenElement(
        id = "field",
        text = text,
        contentDescription = desc,
        className = "android.widget.EditText",
        bounds = Rect(0, 0, 400, 100),
        role = ElementRole.SEARCH_FIELD,
        enabled = true,
        isPassword = false,
        hintText = hint,
        focused = true,
    )

private fun screen(pkg: String, keyboardVisible: Boolean = false) =
    ScreenModel(packageName = pkg, elements = listOf(field()), keyboardVisible = keyboardVisible)

fun main() {
    // ---- ImeActionResolver: hint-first inference ----
    run {
        checkEq(
            "YouTube hint → SEARCH",
            ImeActionResolver.resolve(field(hint = "Search YouTube")),
            InferredImeAction.SEARCH,
        )
        checkEq(
            "Chrome hint → SEARCH",
            ImeActionResolver.resolve(field(hint = "Search or type URL")),
            InferredImeAction.SEARCH,
        )
        checkEq(
            "Go hint → GO",
            ImeActionResolver.resolve(field(hint = "Go to a site")),
            InferredImeAction.GO,
        )
        checkEq(
            "Done hint → DONE",
            ImeActionResolver.resolve(field(hint = "Done")),
            InferredImeAction.DONE,
        )
        checkEq(
            "Send in text → SEND",
            ImeActionResolver.resolve(field(text = "Send a message")),
            InferredImeAction.SEND,
        )
        checkEq(
            "no evidence → UNKNOWN",
            ImeActionResolver.resolve(field(hint = "Type here")),
            InferredImeAction.UNKNOWN,
        )
        checkEq(
            "null field → UNKNOWN",
            ImeActionResolver.resolve(null),
            InferredImeAction.UNKNOWN,
        )
        checkEq(
            "keyboard key label wins over hint",
            ImeActionResolver.resolve(field(hint = "Search YouTube"), keyboardActionKeyLabel = "Go"),
            InferredImeAction.GO,
        )
        checkEq(
            "unreadable key label falls back to hint",
            ImeActionResolver.resolve(field(hint = "Search YouTube"), keyboardActionKeyLabel = "⏎"),
            InferredImeAction.SEARCH,
        )
    }

    // ---- ImeActionResolver: intents + mechanism order ----
    run {
        checkEq(
            "DONE → CONFIRM intent",
            ImeActionResolver.intentFor(InferredImeAction.DONE),
            KeyboardAction.CONFIRM,
        )
        checkEq(
            "NEXT → CONFIRM intent",
            ImeActionResolver.intentFor(InferredImeAction.NEXT),
            KeyboardAction.CONFIRM,
        )
        checkEq(
            "SEARCH → SUBMIT intent",
            ImeActionResolver.intentFor(InferredImeAction.SEARCH),
            KeyboardAction.SUBMIT,
        )
        checkEq(
            "UNKNOWN → SUBMIT intent",
            ImeActionResolver.intentFor(InferredImeAction.UNKNOWN),
            KeyboardAction.SUBMIT,
        )
        val searchFirst = ImeActionResolver.preferredMechanisms(InferredImeAction.SEARCH)
        check("SEARCH mechanisms non-empty", searchFirst.isNotEmpty())
        check(
            "SEARCH mechanisms start with the keyboard action key",
            searchFirst.first() == SubmitMechanism.KEYBOARD_ACTION_KEY,
        )
    }

    // ---- SubmitDiagnosticClassifier: specific reasons ----
    run {
        val before = screen("com.google.android.apps.youtube.music", keyboardVisible = true)
        val after = screen("com.google.android.apps.youtube.music", keyboardVisible = true)
        checkEq(
            "no snapshots → PERMISSION_LIMITED",
            SubmitDiagnosticClassifier.classify(
                SubmitVerification.Unknown, null, after, false, "com.google.android.apps.youtube.music",
            ),
            SubmitDiagnostic.PERMISSION_LIMITED,
        )
        checkEq(
            "package changed → APP_NAVIGATION_CHANGED",
            SubmitDiagnosticClassifier.classify(
                SubmitVerification.NotExecuted("still on entry"),
                before,
                screen("com.other.app"),
                true,
                "com.google.android.apps.youtube.music",
            ),
            SubmitDiagnostic.APP_NAVIGATION_CHANGED,
        )
        checkEq(
            "nothing dispatched → IME_ACTION_UNAVAILABLE",
            SubmitDiagnosticClassifier.classify(
                SubmitVerification.NotExecuted("no submit"),
                before,
                after,
                false,
                "com.google.android.apps.youtube.music",
            ),
            SubmitDiagnostic.IME_ACTION_UNAVAILABLE,
        )
        checkEq(
            "dispatched, no transition → SUBMIT_DISPATCHED_NO_TRANSITION",
            SubmitDiagnosticClassifier.classify(
                SubmitVerification.NotExecuted("keyboard still up"),
                before,
                after,
                true,
                "com.google.android.apps.youtube.music",
            ),
            SubmitDiagnostic.SUBMIT_DISPATCHED_NO_TRANSITION,
        )
        checkEq(
            "executed → UNKNOWN (never a false pass)",
            SubmitDiagnosticClassifier.classify(
                SubmitVerification.Executed,
                before,
                after,
                true,
                "com.google.android.apps.youtube.music",
            ),
            SubmitDiagnostic.UNKNOWN,
        )
        check(
            "every diagnostic has a non-blank label",
            SubmitDiagnostic.entries.all { it.label.isNotBlank() },
        )
    }

    println("AioeImeActionTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILED: $failed")
}
