package com.loom.assistant.core.common

/**
 * MASTER Wave 4 (F16 follow-up) — the STT hypothesis selector.
 * Proves LOOM reads ALL hypotheses + confidence scores instead of
 * trusting the recognizer's first guess blindly.
 *
 * Plain-main() pattern; see test-harness/run-voice-tests.sh `stt`:
 *   kotlinc -d out -jvm-target 17 SttHypotheses.kt SttHypothesesTest.kt
 *   java -cp out com.loom.assistant.core.common.SttHypothesesTestKt
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

fun main() {
    // Highest confidence wins, even when it is not first.
    run {
        val s = SttHypothesisSelector.select(
            listOf("open utube for me", "open youtube for me", "open you tube for me"),
            listOf(0.42f, 0.91f, 0.30f),
        )
        checkEq("highest-confidence hypothesis wins", s.best, "open youtube for me")
        checkEq("best index recorded", s.bestIndex, 1)
        checkEq("best confidence recorded", s.bestConfidence, 0.91f)
    }

    // The video's lost command, ranked second: the good text must
    // survive a garbled first guess.
    run {
        val s = SttHypothesisSelector.select(
            listOf("open utube for me", "open youtube for me", "open youtube"),
            listOf(0.55f, 0.88f, 0.40f),
        )
        checkEq("good second hypothesis beats garbled first", s.best, "open youtube for me")
        checkEq("alternatives kept in recognizer order", s.alternatives.size, 3)
    }

    // No scores: the recognizer's first hypothesis wins (old behaviour).
    run {
        val s = SttHypothesisSelector.select(
            listOf("open youtube", "open you tube"),
            null,
        )
        checkEq("no scores → first hypothesis", s.best, "open youtube")
        checkEq("no scores → best index 0", s.bestIndex, 0)
        checkEq("no scores → confidence null", s.bestConfidence, null)
    }

    // Misaligned scores are never trusted — fall back to first.
    run {
        val s = SttHypothesisSelector.select(
            listOf("open youtube", "open you tube"),
            listOf(0.9f),
        )
        checkEq("length-mismatched scores ignored", s.best, "open youtube")
        checkEq("mismatched scores → confidence null", s.bestConfidence, null)
    }

    // Ties keep the earliest (the recognizer's own ranking breaks them).
    run {
        val s = SttHypothesisSelector.select(
            listOf("first guess", "second guess"),
            listOf(0.7f, 0.7f),
        )
        checkEq("confidence tie keeps earliest", s.bestIndex, 0)
    }

    // Empty list: empty best text — the caller treats it as the
    // blank-result lost-command path, never as a command.
    run {
        val s = SttHypothesisSelector.select(emptyList(), null)
        checkEq("empty hypotheses → empty best", s.best, "")
        checkEq("empty hypotheses → no alternatives", s.alternatives.size, 0)
    }

    // Heard-transcript formatting.
    run {
        val chosenFirst = SttSelection("open youtube", 0, 0.9f, listOf("open youtube", "open you tube"))
        checkEq("heard transcript, first chosen", heardTranscript(chosenFirst), "heard: \"open youtube\"")
        val chosenSecond = SttSelection("open youtube for me", 1, 0.88f, listOf("open utube for me", "open youtube for me"))
        check(
            "heard transcript names the discarded first guess",
            heardTranscript(chosenSecond).contains("open utube for me"),
        )
    }

    println("SttHypothesesTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        throw AssertionError("$failed test(s) failed")
    }
}
