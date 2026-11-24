package com.loom.assistant.core.common

/**
 * MASTER Wave 4 (F16 follow-up) — read ALL STT hypotheses + confidence
 * scores, not just the first hypothesis.
 *
 * Wave 3 found the pipeline requests EXTRA_MAX_RESULTS=3 but reads
 * only `list.firstOrNull()`, and reads no confidence scores at all —
 * so a garbled first hypothesis discards a good second one, and a
 * low-confidence guess is trusted exactly as much as a certain one.
 * The pipeline now selects through [SttHypothesisSelector] and logs
 * the full list; the manager displays the chosen text as the heard
 * transcript (F12).
 *
 * Pure Kotlin, no android.* imports — the selection rule is JVM-provable.
 * The Android `SpeechRecognizer.CONFIDENCE_SCORES` array lines up with
 * the `RESULTS_RECOGNITION` list by index; scores are 0.0–1.0, and a
 * missing/empty scores array means "no confidence info".
 */
data class SttSelection(
    /** The hypothesis LOOM acts on. */
    val best: String,
    /** Its index in the recognizer's list (0 = the recognizer's first choice). */
    val bestIndex: Int,
    /** Its confidence, or null when the recognizer gave no scores. */
    val bestConfidence: Float?,
    /** Every hypothesis, in recognizer order. */
    val alternatives: List<String>,
)

/** The "heard: …" transcript shown for a turn (F12). */
fun heardTranscript(selection: SttSelection): String =
    if (selection.alternatives.size > 1 && selection.bestIndex != 0) {
        "heard: \"${selection.best}\" (the recognizer's first guess was \"${selection.alternatives[0]}\")"
    } else {
        "heard: \"${selection.best}\""
    }

object SttHypothesisSelector {
    /**
     * Picks the hypothesis to act on:
     *  - With confidence scores of matching length, the highest
     *    confidence wins; ties keep the earliest (the recognizer's
     *    own ranking breaks them).
     *  - Without scores (or a length mismatch — never trust a
     *    misaligned array), the recognizer's first hypothesis wins.
     *  - An empty list yields an empty best text (the caller treats
     *    it as the blank-result lost-command path, never as a command).
     */
    fun select(hypotheses: List<String>, confidences: List<Float>?): SttSelection {
        if (hypotheses.isEmpty()) {
            return SttSelection(best = "", bestIndex = 0, bestConfidence = null, alternatives = emptyList())
        }
        val scores = confidences?.takeIf { it.size == hypotheses.size }
        if (scores == null) {
            return SttSelection(
                best = hypotheses[0],
                bestIndex = 0,
                bestConfidence = null,
                alternatives = hypotheses,
            )
        }
        var bestIndex = 0
        var bestScore = scores[0]
        for (i in 1 until scores.size) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIndex = i
            }
        }
        return SttSelection(
            best = hypotheses[bestIndex],
            bestIndex = bestIndex,
            bestConfidence = bestScore,
            alternatives = hypotheses,
        )
    }
}
