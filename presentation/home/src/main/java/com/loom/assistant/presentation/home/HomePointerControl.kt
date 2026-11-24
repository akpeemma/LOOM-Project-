package com.loom.assistant.presentation.home

import com.loom.assistant.platform.services.ShowPointerFailure
import com.loom.assistant.presentation.designsystem.LoomDestination

/**
 * Home one-tap pointer control — accessibility fix (Nov 5, David's rule:
 * usable by someone never told any command).
 *
 * Pure Kotlin, no Android imports, so the harness can test it on the JVM.
 * The Android work (service start, accessibility check, system settings
 * intent) lives in [HomeScreenView]; this object owns only the decision:
 * given what happened, what plain sentence does Home show, and does it
 * offer the one-tap link to the system accessibility settings.
 *
 * Plain-words rule: no user-facing string here may contain
 * attached / detached / anchored / overlay / guidance window.
 *
 * Pointer failure fix (2026-11-05, device evidence: Home "Show pointer"
 * on build cfdb8fb failed with the one collapsed "couldn't show it"
 * sentence while the a11y flag read on): the outcome now takes the
 * failing step ([ShowPointerFailure]) and says a distinct plain sentence
 * per cause. The old three-argument [outcome] is kept and still returns
 * the generic sentence when no reason is known.
 */
object HomePointerControl {

    /** Shown only after [LoomOverlayService.showPointerNow] returned true. */
    const val POINTER_ON_TEXT = "Pointer is on — 3 cursors, follow me."

    /** Accessibility service off — the one plain sentence + settings link. */
    const val A11Y_OFF_TEXT =
        "LOOM needs to be turned on in Settings, under Accessibility. Tap below to open it."

    /** The floating assistant itself could not be started. */
    const val START_FAILED_TEXT =
        "The pointer isn't on screen right now — LOOM couldn't start. Try again in a moment."

    /** Service started, but no verified render came back (no reason known). */
    const val SHOW_FAILED_TEXT =
        "The pointer isn't on screen right now — LOOM couldn't show it. Try again in a moment."

    /** No live helper service: the Settings flag reads on but the
     * service itself is not running. Settings link shown — the fix is
     * turning LOOM off and on again in Accessibility. */
    const val NO_HOST_TEXT =
        "LOOM's helper service isn't running right now — open Settings, Accessibility, " +
            "and turn LOOM off and on again."

    /**
     * Binding survival (2026-11-06): Android has no API for the app
     * to switch its own helper service back on — only the user can,
     * in Settings. So when the helper is not live, Home first WAITS
     * for Android's own rebind (opening the app gives the system a
     * live process to bind into) for up to [REBIND_WAIT_MS],
     * polling every [REBIND_POLL_MS]; only if it is still not live
     * does Home send David to the toggle. 3 s is long enough for a
     * system rebind after a fresh app start and short enough that a
     * tap never feels stuck; the wait is logged, never claimed as a
     * rebind the app performed.
     */
    const val REBIND_WAIT_MS: Long = 3_000L
    const val REBIND_POLL_MS: Long = 250L

    /**
     * The exact steps shown on Home itself when the helper is on in
     * Settings but not actually running. The link below opens the
     * Accessibility list; these steps are the rest of the trip — no
     * guessing, no maze. Shown only with the NO_HOST outcome.
     */
    const val TURN_BACK_ON_STEPS =
        "1. Tap LOOM in the list.\n" +
            "2. Turn LOOM off, then turn it on again.\n" +
            "3. Come back here and tap Show pointer."

    /** The exact steps shown on Home when LOOM is switched off entirely. */
    const val TURN_ON_STEPS =
        "1. Tap LOOM in the list.\n" +
            "2. Turn LOOM on.\n" +
            "3. Come back here and tap Show pointer."

    /** The full retry window passed with no render at all. */
    const val TIMEOUT_TEXT =
        "The pointer is still starting — tap Show pointer again in a moment."

    /** The SHOW pointer setting could not be saved. */
    const val POLICY_FAILED_TEXT =
        "The pointer isn't on screen right now — LOOM couldn't save its pointer setting. " +
            "Try again in a moment."

    /** A live helper service exists but would not show the pointer. */
    const val HOST_REFUSED_TEXT =
        "The pointer isn't on screen right now — LOOM's helper service wouldn't show it. " +
            "Try again in a moment."

    /** A pointer was resolved but the screen it was drawing on went away. */
    const val NOT_ATTACHED_TEXT =
        "The pointer isn't on screen right now — LOOM lost the screen it was drawing on. " +
            "Try again in a moment."

    /** The pointer controls are not ready — nothing to draw through. */
    const val NO_INTERACTION_TEXT =
        "The pointer isn't on screen right now — LOOM's pointer controls aren't ready. " +
            "Try again in a moment."

    /** The draw itself was refused this time. */
    const val GATE_REFUSED_TEXT =
        "The pointer isn't on screen right now — LOOM couldn't draw it this time. " +
            "Try again in a moment."

    /**
     * Automatic recovery (Nov 6, refused-start fix): LOOM was stopped
     * by the user's own choice (SHUT DOWN / DEACTIVATE), so Show
     * pointer cannot bring it back — coming back is launcher-only
     * (§4.4). No settings link: this is not a settings problem, and
     * "try again" would fail identically, so the sentence says what
     * to do instead. Plain words: no banned word appears here.
     */
    const val STOPPED_BY_CHOICE_TEXT =
        "LOOM was turned off by your choice, so Show pointer can't bring it back. " +
            "Open the app again from the launcher to turn it back on."

    data class PointerTapOutcome(
        val statusText: String,
        val showAccessibilitySettings: Boolean,
        /** Exact plain-words steps shown under the settings link, if any. */
        val stepsText: String? = null,
    )

    /**
     * Live liveness of the helper service, decided from two pure
     * inputs so the harness can test it on the JVM: the Settings
     * flag ([a11yFlagOn]) and the live binding signal
     * ([hostLive], `LoomAccessibilityService.isLive()` — the same
     * signal DeviceControlCenter.isServiceBound() reads). The flag
     * alone lies after a HyperOS kill; only both together mean the
     * pointer can possibly show.
     */
    enum class PointerLiveness {
        /** Flag on and host live — a render attempt can succeed. */
        LIVE,

        /** Flag off — the service is not even enabled. */
        FLAG_OFF,

        /** Flag on but host not live — the service was killed or
         * never connected; a render attempt cannot win. */
        FLAG_ON_BUT_NOT_LIVE,
    }

    fun decideLiveness(a11yFlagOn: Boolean, hostLive: Boolean): PointerLiveness = when {
        !a11yFlagOn -> PointerLiveness.FLAG_OFF
        hostLive -> PointerLiveness.LIVE
        else -> PointerLiveness.FLAG_ON_BUT_NOT_LIVE
    }

    /**
     * @param a11yOn whether LOOM's accessibility service is enabled.
     * @param serviceStarted whether the floating-assistant start was issued
     *   without throwing (fire-and-forget start, never proof of running).
     * @param rendered the verified render result from
     *   `LoomOverlayService.showPointerNow()` — true ONLY on a verified
     *   render; null when no render was even attempted (a11y off).
     */
    fun outcome(
        a11yOn: Boolean,
        serviceStarted: Boolean,
        rendered: Boolean?,
    ): PointerTapOutcome = outcome(a11yOn, serviceStarted, rendered, failure = null)

    /**
     * Reason-carrying overload: [failure] is the first failing step
     * from `LoomOverlayService.showPointerNowDetailed()` (or TIMEOUT
     * when Home's retry window expired with no seam result). Order:
     * a11y-off wins over everything (even a stale true); a verified
     * render wins over any failure; then the failure's own sentence;
     * then start failure; then the generic sentence when no reason
     * is known. Only NO_HOST offers the settings link — it is the
     * one cause the user fixes by toggling LOOM in Accessibility.
     */
    fun outcome(
        a11yOn: Boolean,
        serviceStarted: Boolean,
        rendered: Boolean?,
        failure: ShowPointerFailure?,
    ): PointerTapOutcome = when {
        !a11yOn -> PointerTapOutcome(
            A11Y_OFF_TEXT,
            showAccessibilitySettings = true,
            stepsText = TURN_ON_STEPS,
        )
        rendered == true -> PointerTapOutcome(POINTER_ON_TEXT, showAccessibilitySettings = false)
        failure != null -> failureOutcome(failure)
        !serviceStarted -> PointerTapOutcome(START_FAILED_TEXT, showAccessibilitySettings = false)
        else -> PointerTapOutcome(SHOW_FAILED_TEXT, showAccessibilitySettings = false)
    }

    /**
     * Automatic recovery (Nov 6, refused-start fix): pure decision
     * for the stopped-by-choice refusal — an intentional-stop marker
     * is present AND the lifecycle is not ACTIVE. Either half alone
     * is not a refusal: a marker with an ACTIVE lifecycle is the
     * plain STOP case, where a manual start is allowed, and no marker
     * means nothing was stopped on purpose. The service still refuses
     * the start itself; this is what Home says instead of the generic
     * "try again" text.
     */
    fun isStoppedByChoice(markerPresent: Boolean, lifecycleActive: Boolean): Boolean =
        markerPresent && !lifecycleActive

    /** The stopped-by-choice outcome: the plain sentence, no settings link. */
    fun stoppedByChoiceOutcome(): PointerTapOutcome =
        PointerTapOutcome(STOPPED_BY_CHOICE_TEXT, showAccessibilitySettings = false)

    /**
     * Pure decision for Home's poll loop (pointer failure fix):
     * which failure (if any) the final message carries, from the
     * poll's own signals — so the harness can test it on the JVM.
     * [rendered] true → no failure; the service never started →
     * no failure either (the start-failure sentence covers it);
     * the seam never ran ([attemptedSeam] false — the overlay
     * instance and/or the live host never appeared inside the
     * window) → TIMEOUT; otherwise the LAST seam reason, or TIMEOUT
     * if somehow none was recorded.
     */
    fun resolvePollFailure(
        rendered: Boolean,
        serviceStarted: Boolean,
        attemptedSeam: Boolean,
        lastFailure: ShowPointerFailure?,
    ): ShowPointerFailure? = when {
        rendered -> null
        !serviceStarted -> null
        !attemptedSeam -> ShowPointerFailure.TIMEOUT
        else -> lastFailure ?: ShowPointerFailure.TIMEOUT
    }

    private fun failureOutcome(failure: ShowPointerFailure): PointerTapOutcome = when (failure) {
        ShowPointerFailure.NO_HOST ->
            PointerTapOutcome(
                NO_HOST_TEXT,
                showAccessibilitySettings = true,
                stepsText = TURN_BACK_ON_STEPS,
            )
        ShowPointerFailure.TIMEOUT ->
            PointerTapOutcome(TIMEOUT_TEXT, showAccessibilitySettings = false)
        ShowPointerFailure.POLICY_FAILED ->
            PointerTapOutcome(POLICY_FAILED_TEXT, showAccessibilitySettings = false)
        ShowPointerFailure.HOST_REFUSED ->
            PointerTapOutcome(HOST_REFUSED_TEXT, showAccessibilitySettings = false)
        ShowPointerFailure.NOT_ATTACHED ->
            PointerTapOutcome(NOT_ATTACHED_TEXT, showAccessibilitySettings = false)
        ShowPointerFailure.NO_INTERACTION ->
            PointerTapOutcome(NO_INTERACTION_TEXT, showAccessibilitySettings = false)
        ShowPointerFailure.GATE_REFUSED ->
            PointerTapOutcome(GATE_REFUSED_TEXT, showAccessibilitySettings = false)
    }

    /**
     * The Home "See what LOOM sees" entry opens the EXISTING view — the
     * same destination Device Control navigates to
     * (`DeviceControlScreenView` → `LoomDestination.WhatLoomSees`). No
     * rebuild, no new screen; Home just reaches it in one tap.
     */
    fun seeWhatLoomSeesDestination(): LoomDestination = LoomDestination.WhatLoomSees
}
