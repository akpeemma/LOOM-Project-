package com.loom.assistant.core.common

/**
 * ACTIVATE ASSISTANT MODE — pure planner (no Android imports).
 *
 * Decides, from caller-supplied facts, what voice activation of the
 * floating assistant may do. It performs NO action itself: no service
 * start, no mode switch, no mic touch, no lifecycle transition. The
 * caller (VoiceSessionManager, manager-direct — see
 * ACTIVATE-INTEGRATION-SPEC.md) maps the inputs from the real APIs and
 * executes the plan, then speaks ONLY what the verified outcome proves.
 *
 * Input mapping (caller-side, NOT done here — this file must stay
 * Android-free so it runs on the plain JVM harness):
 * - lifecycleActive: LifecycleController.isDispatchAllowed() — true ONLY
 *   when lifecycle == ACTIVE. A live in-app/floating voice session exists
 *   only in that state, so activation-by-voice is possible only then.
 * - overlayPermissionGranted: OverlayPermission.granted(appContext)
 *   (Settings.canDrawOverlays under the hood).
 * - serviceRunning: ModeController.isOverlayRunning(appContext) OR
 *   ModeController.boundMode() != null. The prefs flag alone is a hint
 *   (set optimistically by LoomOverlayService.start(), never cleared in
 *   onDestroy); boundMode() != null means the service backend is actually
 *   registered (onCreate) and not yet unregistered (onDestroy).
 * - lastBoundMode: ModeController.boundMode()?.name, falling back to
 *   ModeController.getMode(appContext).name when the service is not
 *   running; null when the caller has no mode at all. Mode names are the
 *   AssistantMode enum names ("ORB", "CHARACTER", "INVISIBLE") as the app
 *   already represents them in prefs ("loom_modes" / "active_mode").
 *
 * Default path (verified, not invented): ModeController.getMode() returns
 * AssistantMode.ORB when the prefs key is missing or unparseable
 * (ModeController.kt, getMode). So a null/blank lastBoundMode plans
 * StartService("ORB") — the same mode the Modes screen would show as the
 * fallback, and the same mode a cold-start service binds via
 * getMode(this) in its onStartCommand else-branch.
 *
 * Rule order is load-bearing — lifecycle first, then permission, then
 * running state:
 * 1. Lifecycle not ACTIVE -> Refused. This preserves launcher-icon-only
 *    reactivation (LifecycleController.requestReactivation): voice can
 *    never wake LOOM from ASSISTANT_MODE_INACTIVE, SHUT_DOWN,
 *    SHUTTING_DOWN or REACTIVATING when no session is live.
 * 2. Permission missing -> Refused naming Settings ("Display over other
 *    apps"). Never silent, never a claim that the assistant is active.
 * 3. Service running -> AlreadyRunning. The caller does NOT restart the
 *    service; it restores views if hidden via ModeController.applyMode
 *    (setMode posts ModeChanged synchronously, the service rebinds, and
 *    verifyAndReport proves the view is attached). Forwarding
 *    SHOW_ASSISTANT to the host is NOT the restore path: in-app hosts
 *    no-op it (ChatScreenView/HomeScreenView onCommandAction else-branch).
 * 4. Otherwise -> StartService(effective mode). The caller starts the
 *    service (LoomOverlayService.start) and then applies the mode through
 *    ModeController.applyMode, acking only from the returned ModeResult.
 *
 * Mic ownership is UNCHANGED by every plan: activation starts the
 * service/views only. It never starts a floating voice session and never
 * touches VoiceSessionOwner. A floating session still starts only by an
 * explicit orb/character tap (or the panel's Voice chat row).
 */
object AssistantActivation {

    /** ModeController.getMode() fallback when no mode is stored. */
    const val DEFAULT_MODE_NAME = "ORB"

    sealed interface ActivationPlan {
        /** Do not start, do not claim. [reason] is speakable verbatim. */
        data class Refused(val reason: String) : ActivationPlan

        /** Start the service, then apply this mode via ModeController. */
        data class StartService(val modeName: String) : ActivationPlan

        /** Service already running; restore views if hidden via applyMode. */
        data object AlreadyRunning : ActivationPlan
    }

    fun plan(
        lifecycleActive: Boolean,
        overlayPermissionGranted: Boolean,
        serviceRunning: Boolean,
        lastBoundMode: String?,
    ): ActivationPlan {
        if (!lifecycleActive) return ActivationPlan.Refused(lifecycleRefusedMessage())
        if (!overlayPermissionGranted) return ActivationPlan.Refused(permissionRefusedMessage())
        if (serviceRunning) return ActivationPlan.AlreadyRunning
        val mode = lastBoundMode?.takeIf { it.isNotBlank() } ?: DEFAULT_MODE_NAME
        return ActivationPlan.StartService(mode)
    }

    /**
     * Refusal when no live session exists. Names the only reactivation
     * route; never claims the assistant is active or will become active.
     */
    fun lifecycleRefusedMessage(): String =
        "The assistant isn't active right now — open LOOM from the launcher " +
            "to reactivate it. Voice can't wake it from here."

    /**
     * Refusal when the overlay permission is missing. Names Settings
     * ("Display over other apps") — the destination
     * OverlayPermission.settingsIntent() opens
     * (Settings.ACTION_MANAGE_OVERLAY_PERMISSION for this package).
     */
    fun permissionRefusedMessage(): String =
        "I need the Display over other apps permission first — open " +
            "Settings to grant it for LOOM, then try again."

    /** Success ack, spoken ONLY after ModeController reports Success. */
    fun successAck(shortModeLabel: String): String =
        "Floating assistant active in $shortModeLabel mode."

    /** Failure ack — states the failure, never claims active. */
    fun failureAck(reason: String): String =
        "I couldn't start the floating assistant — $reason."

    /**
     * Short spoken label for an AssistantMode enum name, mirroring
     * ModeController.shortLabel (label minus " Mode"). Unknown names pass
     * through unchanged so the caller — not this planner — decides the
     * fallback (ModeController.shortLabel on the verified enum value).
     */
    fun shortLabel(modeName: String): String = when (modeName) {
        "ORB" -> "Orb"
        "CHARACTER" -> "Character"
        "INVISIBLE" -> "Invisible"
        else -> modeName
    }
}
