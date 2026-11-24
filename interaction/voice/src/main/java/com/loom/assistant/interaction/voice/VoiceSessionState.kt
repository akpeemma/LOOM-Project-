package com.loom.assistant.interaction.voice

/**
 * Stage 2A — the 7 voice session states (David's §3). Moved to their own
 * file (no Android imports) so the Phase 2 mic-grounding derivation and JVM
 * test harnesses can reference them without the speech stack.
 *
 * The states always reflect the real system condition: LISTENING is entered
 * only around an actual recognizer session, SPEAKING only while a TTS
 * utterance is playing, PAUSED only when the user paused. Nothing is faked:
 * no wake-word, no always-on listening, no barge-in (TTS and capture cannot
 * overlap on Android), capture is activity-bound.
 */
enum class VoiceSessionState {
    IDLE, LISTENING, PROCESSING, SPEAKING, PAUSED, ERROR, ENDING
}
