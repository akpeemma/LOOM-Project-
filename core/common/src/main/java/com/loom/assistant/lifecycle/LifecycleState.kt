package com.loom.assistant.lifecycle

/**
 * LOOM lifecycle — Layer 1 (plan §4.1). Written ONLY by [LifecycleController].
 *
 * Layer 2 (session: IDLE/LISTENING/PROCESSING/SPEAKING/PAUSED/ERROR/ENDING)
 * is written ONLY by VoiceSessionManager and reaches the controller as a
 * read-only mirror via [LifecycleController.reportSessionState]. Session
 * states are NEVER independently settable lifecycle states.
 */
enum class LifecycleState {
    /** Assistant available for interaction. */
    ACTIVE,

    /** Session ended, mic off, views hidden. App/services installed,
     * settings/memory preserved. */
    ASSISTANT_MODE_INACTIVE,

    /** Transitional; new commands rejected except emergency halt. */
    SHUTTING_DOWN,

    /** Verified: no session, mic released, TTS idle, no queues, overlay
     * views removed, overlay service stopped. */
    SHUT_DOWN,

    /** Transitional; restores persisted config, verifies availability. */
    REACTIVATING,
}

/** Session-state names the controller accepts as a read-only mirror. */
val SESSION_MIRROR_NAMES: Set<String> = setOf(
    "IDLE", "LISTENING", "PROCESSING", "SPEAKING", "PAUSED", "ERROR", "ENDING",
)

/**
 * Session states that mean "something is live". A mirror carrying one of
 * these while lifecycle != ACTIVE is impossible state #1 (plan §4.1):
 * dropped as stale, audited, teardown requested.
 */
val LIVE_SESSION_NAMES: Set<String> = setOf(
    "LISTENING", "PROCESSING", "SPEAKING", "PAUSED",
)
