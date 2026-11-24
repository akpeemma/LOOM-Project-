package com.loom.assistant.core.common

/**
 * Shared intent contract — the single source of truth for cross-module
 * intent actions/extras, so no module needs to import another's classes.
 */
object LoomIntents {
    /** Extra: open MainActivity straight into a voice chat. */
    const val EXTRA_START_VOICE = "com.loom.assistant.extra.START_VOICE"

    /** Overlay service actions. */
    const val ACTION_START_OVERLAY = "com.loom.assistant.overlay.START"
    const val ACTION_STOP_OVERLAY = "com.loom.assistant.overlay.STOP"
    const val ACTION_REFRESH_OVERLAY = "com.loom.assistant.overlay.REFRESH"

    /** MainActivity class name for explicit intents from services. */
    const val MAIN_ACTIVITY = "com.loom.assistant.app.MainActivity"

    /** AuthGate confirmation fallback: notification action intents. */
    const val ACTION_AUTH_APPROVE = "com.loom.assistant.auth.APPROVE"
    const val ACTION_AUTH_DENY = "com.loom.assistant.auth.DENY"
    /**
     * Device-evidence fix (2026-11-05, item 10): approve AND store a
     * scoped always-allow grant — the notification-fallback equivalent
     * of ticking "Always allow…" on the overlay card. Offered only
     * when the gate offered a grant for this pending; the gate still
     * re-validates origin/key/scope/strictness in resolve().
     */
    const val ACTION_AUTH_APPROVE_ALWAYS = "com.loom.assistant.auth.APPROVE_ALWAYS"
    const val EXTRA_AUTH_PENDING_ID = "com.loom.assistant.auth.PENDING_ID"

    /** AuthActionReceiver class name (declared in the app manifest). */
    const val AUTH_RECEIVER = "com.loom.assistant.app.AuthActionReceiver"

    /**
     * Stage 2C (§5.2 P1): notification Stop action → service intent handled
     * in LoomOverlayService.onStartCommand → layer.halt(SOURCE_NOTIFICATION).
     * Worker F wires the PendingIntent.
     */
    const val ACTION_HALT_SEQUENCE = "com.loom.assistant.sequence.HALT"
}
