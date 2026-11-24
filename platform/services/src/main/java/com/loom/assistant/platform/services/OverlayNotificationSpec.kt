package com.loom.assistant.platform.services

/**
 * Foreground-notification variants for the overlay service (§4.2-D/F, §4.5,
 * closure decision 1).
 *
 * Android-free spec: the service maps a spec to real Notification actions.
 * Tests pin the security-critical invariant — the DEACTIVATED variant carries
 * ZERO actions and NO content intent, so no notification path can reactivate
 * LOOM or bypass launcher-icon-only reactivation (§4.4).
 */
enum class OverlayNotificationVariant {
    /** Normal floating operation: content intent opens the app, Stop halts. */
    ACTIVE,

    /**
     * HIDE YOURSELF: assistant still ACTIVE (hidden). Kept with a single
     * SHOW ASSISTANT action that restores views only — never starts the mic,
     * never transitions the lifecycle, never REACTIVATING.
     */
    HIDDEN,

    /**
     * DEACTIVATE ASSISTANT MODE: status visibility only. Strictly action-free
     * AND no content intent (mismatch #5 — both the 2C Stop action and the
     * MainActivity content intent are removed in this variant).
     */
    DEACTIVATED,
}

sealed interface OverlayNotificationAction {
    /** Halt the live sequence (ACTIVE variant only). */
    data object HaltSequence : OverlayNotificationAction

    /** Restore the hidden floating presentation (HIDDEN variant only). */
    data object ShowAssistant : OverlayNotificationAction
}

data class OverlayNotificationSpec(
    val title: String,
    val text: String,
    val hasContentIntent: Boolean,
    val actions: List<OverlayNotificationAction>,
)

/** Single source of truth for what each variant shows and offers. */
fun overlayNotificationSpec(variant: OverlayNotificationVariant): OverlayNotificationSpec =
    when (variant) {
        OverlayNotificationVariant.ACTIVE -> OverlayNotificationSpec(
            title = "LOOM",
            text = "Floating assistant active",
            hasContentIntent = true,
            actions = listOf(OverlayNotificationAction.HaltSequence),
        )
        OverlayNotificationVariant.HIDDEN -> OverlayNotificationSpec(
            title = "LOOM",
            text = "Assistant hidden — show it again anytime",
            hasContentIntent = true,
            actions = listOf(OverlayNotificationAction.ShowAssistant),
        )
        OverlayNotificationVariant.DEACTIVATED -> OverlayNotificationSpec(
            title = "LOOM",
            text = "Assistant inactive — tap the launcher icon to reactivate",
            hasContentIntent = false,
            actions = emptyList(),
        )
    }
