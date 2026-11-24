package com.loom.assistant.interaction.voice

import android.content.Context

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Voice pipeline contract (Phase 3 §2.6).
 * STT: Android SpeechRecognizer. TTS: framework TextToSpeech.
 * Device-only behavior (mic, recognizer availability) runs on the Redmi;
 * on this VM the code paths compile but cannot be exercised — the
 * implementation is real, not simulated.
 *
 * Lives in its own file (no android.speech imports) so JVM test harnesses
 * can compile the contract + VoiceSessionManager against the android.content
 * fake without stubbing the speech stack.
 */
interface VoicePipeline {
    companion object {
        /**
         * Utterance fix (2026-11-11): silence windows before the
         * recognizer ends a turn. A 1–1.5 s hesitation or correction
         * pause mid-speech used to split the turn, so the fragment
         * was processed as a complete turn and the rest arrived as
         * a new one. 3 s of complete silence / 2 s of possible
         * silence gives a mid-speech pause room to finish. Trade-off:
         * short commands take ~1 s longer to deliver — the device
         * test must confirm they still end promptly.
         */
        const val COMPLETE_SILENCE_MS = 3000L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 2000L
    }

    val state: VoiceState
    fun isSttAvailable(context: Context): Boolean
    fun startListening(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stopSpeaking()
    /**
     * Phase 2 (E.3-2, mismatch #8): drop every queued-but-unspoken TTS
     * utterance. Called on end(), pause(), GO_SILENT and DEACTIVATE so no
     * queued speech leaks past the session boundary.
     */
    fun clearPendingSpeech()
    fun shutdown()
}
