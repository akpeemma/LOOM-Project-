package com.loom.assistant.execution.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.loom.assistant.core.common.CapabilityId
import com.loom.assistant.core.common.CapabilityLivenessChecker
import com.loom.assistant.core.common.CapabilityState
import com.loom.assistant.core.common.HelperCause
import com.loom.assistant.core.common.HelperDownGate
import com.loom.assistant.core.common.HelperDownRecorder
import com.loom.assistant.core.common.LivenessEnv
import com.loom.assistant.core.common.RepairStep
import com.loom.assistant.core.common.repairLadder
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.platform.services.LoomAccessibilityService

/**
 * The control surface for the Phase 4 accessibility dispatcher.
 * Real interface + real logic:
 * - reads the actual accessibility-service binding state,
 * - exposes the real action catalog with honest per-action availability,
 * - marks advertised capabilities that have no working implementation as
 *   UNAVAILABLE (never as ready), with the exact missing piece named,
 * - opens the real system settings screens.
 *
 * Device execution itself is device-gated: tap dispatch needs the
 * accessibility service ENABLED and an AuthGate token (Phase 6). Until
 * then every execution request returns an honest [ActionResult.Refused]
 * naming the exact missing piece — never a fake success.
 */
class DeviceControlCenter(private val appContext: Context) {

    data class ControlAction(
        val id: String,
        val title: String,
        val description: String,
    )

    sealed class ActionResult {
        data object Ready : ActionResult()
        data class Refused(val reason: String) : ActionResult()

        /**
         * Advertised capability with no working implementation. Distinct from
         * [Refused]: a refused action could run once a permission/token is
         * granted; an unavailable one has no code to run at all (Stage 2C
         * wiring). The reason names exactly what is missing.
         */
        data class Unavailable(val reason: String) : ActionResult()
    }

    companion object {
        /** The real catalog of device-control capabilities. */
        val ACTIONS: List<ControlAction> = listOf(
            ControlAction("launch", "Launch app", "Open any installed app by name"),
            ControlAction("tap", "Tap element", "Tap a highlighted screen element"),
            ControlAction("type", "Type text", "Enter text into the focused field"),
            ControlAction("scroll", "Scroll", "Scroll the current screen"),
            ControlAction("back", "Go back", "Press the system back action"),
            ControlAction("screenshot-context", "Read screen", "Snapshot the accessibility tree (redacted)"),
        )
    }

    /** True when the user has enabled LOOM's accessibility service. */
    fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(
                appContext,
                "com.loom.assistant.platform.services.LoomAccessibilityService",
            ).flattenToString()
            enabled.split(':').any { it.equals(me, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    /** Whether gesture dispatch could run right now (service + auth token). */
    fun canDispatch(): ActionResult {
        if (!isAccessibilityEnabled()) {
            return ActionResult.Refused(helperDownReason())
        }
        // Blank-reply fix (2026-11-06): tap used to skip the bound check
        // entirely — with the switch on but the helper not running it
        // reported the approval check (or Ready) while no tap could
        // possibly dispatch. Bound is a precondition, checked here.
        if (!isServiceBound()) {
            return ActionResult.Refused(helperDownReason())
        }
        if (!LoomAccessibilityService.authGateReady) {
            return ActionResult.Refused(
                "LOOM's approval check isn't ready — close LOOM completely " +
                    "and open it again, then try once more.",
            )
        }
        return ActionResult.Ready
    }

    /**
     * Blank-reply fix (2026-11-06): the plain-words reason when the
     * helper is down. The two states need different fixes, and the old
     * single sentence hid the difference — the switch reads on while
     * the helper is not actually running, which is exactly what David
     * hit on Nov 6 (switch on, helper not running).
     * Off: turn it on in Settings. On-but-not-running: turn LOOM off
     * and on again in Settings, under Accessibility.
     */
    fun helperDownReason(): String = if (!isAccessibilityEnabled()) {
        "Turn on LOOM in Settings, under Accessibility — the helper is off."
    } else {
        "The helper is switched on but not running — open Settings, " +
            "under Accessibility, and turn LOOM off and on again."
    }

    /**
     * Stage 2C (§4.1.4) — the foreground package, best-effort. Null when the
     * tree is unavailable (fail-open for the catalog: without a readable
     * tree we cannot know the package, so we report the non-sensitive path).
     */
    fun foregroundPackage(): String? = try {
        ScreenAwarenessHolder.current.snapshot()?.packageName
    } catch (_: Exception) {
        null
    }

    /** Stage 2C (§4.1.4): the exact refusal reason for sensitive packages. */
    fun sensitiveRefusalReason(): String =
        "Inside banking and finance apps, LOOM only explains the steps — " +
            "it never taps, types, scrolls or opens things there."

    /** True when the foreground package is a sensitive (banking/finance/authenticator) app. */
    fun isForegroundSensitive(): Boolean {
        val pkg = foregroundPackage() ?: return false
        return ScreenDataPolicy.isSensitivePackage(pkg)
    }

    /**
     * Per-action availability, computed from real device state (decision 4 —
     * catalog honesty).
     *
     * Stage 2C wiring: launch / type / scroll / back each have a real
     * implementation in LoomAccessibilityGestures.kt (package-intent launch,
     * ACTION_SET_TEXT, node scroll + gesture-swipe fallback, GLOBAL_ACTION_BACK)
     * and report Ready when their runtime preconditions hold. Anything not
     * truly wired stays Refused/Unavailable with the exact missing piece named.
     *
     * Sensitive-app policy (§4.1.4): tap/type/scroll/launch report Refused
     * with the honest reason whenever the foreground package is sensitive.
     * Back is OS-level navigation (leaving the app), not automation inside
     * it, so it is intentionally not gated; screenshot-context is a redacted
     * read, which guidance needs.
     */
    fun availability(action: ControlAction): ActionResult {
        if (isForegroundSensitive()) {
            when (action.id) {
                "tap", "type", "scroll", "launch" ->
                    return ActionResult.Refused(sensitiveRefusalReason())
            }
        }
        return when (action.id) {
            "launch" -> when {
                !isServiceBound() -> ActionResult.Refused(helperDownReason())
                !LoomAccessibilityService.authGateReady -> ActionResult.Refused(
                    "LOOM's approval check isn't ready — close LOOM completely " +
                        "and open it again, then try once more.",
                )
                else -> ActionResult.Ready
            }
            "type" -> when {
                !isServiceBound() -> ActionResult.Refused(helperDownReason())
                !LoomAccessibilityService.authGateReady -> ActionResult.Refused(
                    "LOOM's approval check isn't ready — close LOOM completely " +
                        "and open it again, then try once more.",
                )
                else -> ActionResult.Ready
            }
            "scroll" -> when {
                !isServiceBound() -> ActionResult.Refused(helperDownReason())
                !LoomAccessibilityService.authGateReady -> ActionResult.Refused(
                    "LOOM's approval check isn't ready — close LOOM completely " +
                        "and open it again, then try once more.",
                )
                else -> ActionResult.Ready
            }
            "back" -> when {
                !isServiceBound() -> ActionResult.Refused(helperDownReason())
                !LoomAccessibilityService.authGateReady -> ActionResult.Refused(
                    "LOOM's approval check isn't ready — close LOOM completely " +
                        "and open it again, then try once more.",
                )
                else -> ActionResult.Ready
            }
            "screenshot-context" ->
                if (isAccessibilityEnabled()) ActionResult.Ready
                else ActionResult.Refused(helperDownReason())
            "tap" -> canDispatch()
            else -> ActionResult.Refused("Unknown action.")
        }
    }

    /** Real intent: system accessibility settings. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** True when the a11y service class is bound (Phase 4 holder). */
    fun isServiceBound(): Boolean =
        try {
            com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder.isBound
        } catch (_: Exception) {
            false
        }

    // ---------- MASTER Wave 4 (F10) — per-capability liveness ----------

    /**
     * MASTER Wave 4 (F10 strengthening, C-04): battery state. True
     * when the system is ignoring battery optimizations for LOOM
     * (the helper cannot be dozed/killed for power saving). Null
     * when the state cannot be read — never guessed. The video shows
     * "Pause app activity if unused" ON and no exemption ever
     * requested; this is the first time Device Control reports the
     * battery side of a dead helper at all.
     *
     * NOT added: no battery-exemption request is ever MADE here —
     * requesting it is a user-visible system dialog that must be
     * David's tap, so this only reports state and offers the intent
     * (see [batteryExemptionIntent]). No boot receiver exists either
     * (nothing re-registers the helper after a reboot); both are
     * recorded honestly in the Wave 4 report as not-added.
     */
    fun batteryIgnoringOptimizations(): Boolean? = try {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        pm.isIgnoringBatteryOptimizations(appContext.packageName)
    } catch (_: Exception) {
        null
    }

    /**
     * MASTER Wave 4 (F10): auto-revoke state — whether Android's
     * "Pause app activity if unused" can revoke LOOM's permissions
     * after a quiet period. True = whitelisted (safe), false = NOT
     * whitelisted (auto-revoke can kill the helper). Null when
     * unreadable. Read by reflection: `isAutoRevokeWhitelisted`
     * exists only on API 30+, and a direct call would break older
     * compile targets — reflection keeps this safe everywhere.
     */
    fun autoRevokeWhitelisted(): Boolean? = try {
        val pm = appContext.packageManager ?: return null
        val method = pm.javaClass.getMethod("isAutoRevokeWhitelisted")
        method.invoke(pm) as? Boolean
    } catch (_: Exception) {
        null
    }

    /** The live probe environment for [CapabilityLivenessChecker]. */
    private fun livenessEnv(): LivenessEnv {
        val enabled = isAccessibilityEnabled()
        val bound = isServiceBound()
        val gateReady = try {
            LoomAccessibilityService.authGateReady
        } catch (_: Exception) {
            false
        }
        val snapshotReadable = try {
            ScreenAwarenessHolder.current.snapshot() != null
        } catch (_: Exception) {
            false
        }
        val battery = batteryIgnoringOptimizations()
        val autoRevoke = autoRevokeWhitelisted()
        return object : LivenessEnv {
            override val isAccessibilityEnabled: Boolean = enabled
            override val isServiceBound: Boolean = bound
            override val authGateReady: Boolean = gateReady
            override val snapshotReadable: Boolean = snapshotReadable
            override val batteryIgnoringOptimizations: Boolean? = battery
            override val autoRevokeWhitelisted: Boolean? = autoRevoke
        }
    }

    /**
     * Per-capability liveness (open / type / tap / scroll), each
     * verified by its own probe — NOT inferred from the one
     * `isBound` boolean. A dead capability names its cause and the
     * first repair step; see [repairLadder] for the full ladder.
     */
    fun capabilityLiveness(): Map<CapabilityId, CapabilityState> =
        CapabilityLivenessChecker.evaluate(livenessEnv())

    /** True when every capability is live. */
    fun allCapabilitiesLive(): Boolean =
        CapabilityLivenessChecker.allLive(capabilityLiveness())

    /**
     * The helper-down refusal for a task that needs the helper
     * (F10 strengthening): the plain-words reason, or null when the
     * needed capabilities are live. LOOM must say this BEFORE
     * attempting the task — never claim success for an action it
     * could not perform. [needed] defaults to all four hands.
     */
    fun helperDownRefusal(needed: Set<CapabilityId> = CapabilityId.entries.toSet()): String? =
        HelperDownGate.decide(capabilityLiveness(), needed)

    /** The cause of the first dead capability, or null when all are live (for event recording). */
    fun helperDownCause(): HelperCause? {
        val dead = CapabilityLivenessChecker.firstDead(capabilityLiveness())
        return dead?.second?.cause
    }

    /** The full repair ladder for [cause], starting at the first applicable step. */
    fun repairLadderFor(cause: HelperCause): List<RepairStep> = repairLadder(cause)

    /**
     * Records a helper-down event with the battery/auto-revoke
     * state at the time (F10). Called by the hosts when a task is
     * refused for a dead helper, and by Device Control when it
     * observes the helper down — so a dead helper leaves a record
     * instead of a vanishing Toast.
     */
    fun recordHelperDown(cause: HelperCause) {
        try {
            HelperDownRecorder.record(
                cause,
                batteryIgnoringOptimizations(),
                autoRevokeWhitelisted(),
            )
        } catch (_: Exception) {
        }
    }

    /** The recorded helper-down events, newest last. */
    fun helperDownEvents() = HelperDownRecorder.events()

    /**
     * The last helper-down event's plain-words summary for Device
     * Control, or null when no helper-down event has been recorded.
     */
    fun lastHelperDownSummary(): String? {
        val e = HelperDownRecorder.last() ?: return null
        val battery = when (e.batteryIgnoringOptimizations) {
            true -> "battery optimization off"
            false -> "battery optimization ON"
            null -> "battery state unknown"
        }
        val revoke = when (e.autoRevokeWhitelisted) {
            true -> "auto-revoke off"
            false -> "auto-revoke ON"
            null -> "auto-revoke unknown"
        }
        return "Helper down (${e.cause}) — $battery, $revoke."
    }

    /** System battery-optimization settings for LOOM (the exemption request lives there). */
    fun batteryExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** LOOM's app-info screen (auto-revoke toggle + permissions live there). */
    fun appInfoIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Device-evidence fix (2026-11-05, item 1) + accessibility fix
     * (2026-11-05, audit §7.2): the pointer's live state, for the
     * Device Control "Pointer on screen" row — plain words only, never
     * the internal state words. "On screen" means the pointer can
     * actually draw; "Not on screen" means a window is tracked but not
     * on screen (the stale-renderer symptom); "Off" means no pointer
     * window exists at all.
     */
    fun guidanceWindowState(): String = try {
        when (LoomAccessibilityService.currentGuidanceOverlayState()) {
            LoomAccessibilityService.GuidanceOverlayState.ATTACHED -> "On screen"
            LoomAccessibilityService.GuidanceOverlayState.DETACHED -> "Not on screen"
            LoomAccessibilityService.GuidanceOverlayState.NONE -> "Off"
        }
    } catch (_: Exception) {
        "Off"
    }
}
