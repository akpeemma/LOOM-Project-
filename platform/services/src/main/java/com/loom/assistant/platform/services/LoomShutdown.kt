package com.loom.assistant.platform.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import com.loom.assistant.core.common.PlanStopRegistry
import com.loom.assistant.execution.interaction.HaltSource
import com.loom.assistant.execution.interaction.InteractionLayerRegistry
import com.loom.assistant.lifecycle.IntentionalStop
import com.loom.assistant.lifecycle.IntentionalStopCommand
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.platform.storage.ActivityLog

/**
 * Stage 2C lifecycle — orderly LOOM shutdown (in-app entry point).
 *
 * Thin entry point: halt-first, then the 12-step §4.3 teardown is delegated
 * to the overlay service, which owns the views, the voice pipeline and its
 * own stop. The application-scoped [LifecycleController] owns the state
 * transitions; this file never fakes them.
 *
 * Ordering (binding):
 * - Stop-first (MASTER F7c): PlanStopRegistry.requestStop() runs
 *   BEFORE the layer halt, so a plan run in flight during shutdown
 *   is stopped through the same registry as every other stop —
 *   the dispatcher and the standalone executors read it at their
 *   step boundaries and abort honestly instead of racing teardown.
 * - Halt-first: live sequences are interrupted (INTERRUPTED report) BEFORE
 *   anything else.
 * - Purge-before-flip: AuthGate.purgeAll("shutdown") runs BEFORE the
 *   lifecycle leaves ACTIVE — no issued token survives into SHUTTING_DOWN.
 *   Together with the atomic consume+dispatch boundary (§8.2), this closes
 *   the authorize-then-dispatch race: a token consumed while ACTIVE but
 *   handed to dispatch after the flip is refused at the handoff re-check.
 *
 * Android limits, stated honestly: an ordinary app cannot force-kill its own
 * process and cannot stop the system-managed AccessibilityService — shutdown
 * reports that service as "still enabled in system Settings" and never claims
 * otherwise. SHUT_DOWN is declared only from a fully-passing verification
 * report (§4.3 step 11), never from a stop request or a preference write.
 */
object LoomShutdown {

    /**
     * In-app shutdown entry — called AFTER the authorized confirmation
     * dialog. Stop-first (MASTER F7c), then halt-first, then delegates
     * the teardown to the overlay service under the
     * application-scoped controller.
     *
     * Failures are audit-logged with the component named — never swallowed.
     * The activity is NOT finished here: it finishes when the controller
     * reaches verified SHUT_DOWN (observed via LoomStateEvent.LifecycleChanged).
     */
    fun shutdown(activity: Activity, stopVoice: () -> Unit) {
        val log = ActivityLog.get(activity)

        // MASTER F7c — stop-first: set the shared stop registry
        // BEFORE the layer halt, so a plan run in flight is stopped
        // through the same registry as every other stop. (The
        // overlay service's own shutdown path already sets it;
        // this entry point must too — it is the in-app path.)
        PlanStopRegistry.requestStop()
        log.record("lifecycle", "shutdown stop-first: PlanStopRegistry set")

        // Halt-first (§4.3): interrupt live sequences BEFORE teardown.
        // InteractionLayer.halt() posts the INTERRUPTED report itself.
        val haltReport = try {
            InteractionLayerRegistry.get()?.halt(HaltSource.VOICE)?.let { "halted: $it" }
                ?: "no interaction layer registered"
        } catch (e: Exception) {
            "halt FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
        log.record("lifecycle", "shutdown halt-first: $haltReport")

        try {
            stopVoice()
        } catch (e: Exception) {
            log.record(
                "lifecycle",
                "shutdown stopVoice FAILED: ${e.javaClass.simpleName}: ${e.message}",
            )
        }

        // Automatic recovery (Nov 6): intentional stop — written BEFORE
        // any teardown runs, so a process death mid-shutdown can never
        // look unexpected and bring LOOM back on its own.
        try {
            AutomaticRecoveryStore.get(activity).writeIntentionalStop(
                IntentionalStop(IntentionalStopCommand.SHUTDOWN, System.currentTimeMillis()),
            )
        } catch (_: Exception) {
        }

        // Delegate the ordered teardown (purge → flip → 12 steps) to the
        // overlay service, which owns the views, the pipeline and its own
        // stop. Background startService on Android 8+ throws
        // IllegalStateException — caught and REPORTED, never swallowed: the
        // controller is untouched and no shutdown is claimed.
        try {
            val i = Intent(activity, LoomOverlayService::class.java)
                .setAction(LoomOverlayService.ACTION_EXECUTE_SHUTDOWN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(i)
            } else {
                activity.startService(i)
            }
            log.record("lifecycle", "shutdown: teardown delegated to LoomOverlayService")
        } catch (e: IllegalStateException) {
            log.record(
                "lifecycle",
                "shutdown DEGRADED — background startService failed (Android 8+): " +
                    "${e.message}. No teardown ran; no shutdown claimed.",
            )
        } catch (e: Exception) {
            log.record(
                "lifecycle",
                "shutdown DEGRADED — teardown delegation failed: " +
                    "${e.javaClass.simpleName}: ${e.message}. No shutdown claimed.",
            )
        }
    }

    /**
     * Real read semantics for the old shutdown flag: true only while the
     * application-scoped controller verifiably reports SHUT_DOWN. The
     * write-only pref is gone — the persisted lifecycle_state record
     * (controller-owned) is the source of truth.
     */
    fun wasShutdown(context: Context): Boolean =
        try {
            LifecycleController.currentState() == LifecycleState.SHUT_DOWN
        } catch (_: Exception) {
            false
        }

    /**
     * Deprecated: reactivation is launcher-icon-only through the controller
     * (§4.4). Kept compiling for MainActivity until the §E.7 rework lands;
     * intentionally a no-op — clearing anything here must not reactivate.
     */
    @Deprecated("Reactivation is launcher-icon-only via LifecycleController (§4.4).")
    fun clearShutdownFlag(context: Context) {
        try {
            ActivityLog.get(context).record(
                "lifecycle",
                "clearShutdownFlag called — no-op by design; reactivation goes through the launcher icon.",
            )
        } catch (_: Exception) {
        }
    }
}
