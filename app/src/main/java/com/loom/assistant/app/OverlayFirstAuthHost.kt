package com.loom.assistant.app

import android.content.Context
import android.os.SystemClock
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.interaction.notifications.NotificationCenter
import com.loom.assistant.intelligence.authgate.AuthConfirmationHost
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.platform.services.LoomOverlayService

/**
 * Phase 6 — AuthGate confirmation host: overlay card first, notification
 * fallback (arch §2.14: "an in-overlay card (not a blocking dialog)").
 *
 * When the floating overlay service is running, the confirmation appears
 * as a card over whatever David is doing. Otherwise it arrives as an
 * AUTHORIZATIONS notification with Approve/Deny actions. Either path
 * resolves the same pending confirmation; a shown card/notification that
 * cannot be presented makes the gate deny (fail closed).
 *
 * Master §8 — silent no-card hole, fixed: the old code returned true
 * after a fire-and-forget showAuthCard() whenever the service was
 * running, but the service silently dropped the card when overlay
 * permission was missing — the gate believed a card was shown while
 * nothing was visible and the pending expired unanswered at 90 s.
 * showConfirmation() now verifies the overlay preconditions itself
 * (service running AND overlay permission granted) before claiming
 * the overlay path; when either fails it falls through to the
 * notification fallback, and only the notification's real post result
 * is returned. The service additionally cancels a pending whose card
 * attach fails, so no pending ever parks without a visible, actionable
 * interface. Device-evidence fix (2026-11-05, item 7): the host also
 * verifies the card ACTUALLY attached (the service records the real
 * outcome per pending id) instead of returning true after the
 * fire-and-forget startService — a failed or timed-out attach falls
 * through to the notification fallback.
 *
 * Master §8 — pending-aware hides: resolving one pending hides only
 * its card (shown → hide and advance the service queue; queued → drop
 * from the queue), never another pending's card.
 */
class OverlayFirstAuthHost(private val appContext: Context) : AuthConfirmationHost {

    companion object {
        /** Bounded wait for the service to record the card attach outcome. */
        private const val AUTH_ATTACH_WAIT_MS = 1_000L
        private const val AUTH_ATTACH_POLL_MS = 50L
    }

    private val fallbackPendingIds = mutableSetOf<String>()

    override fun showConfirmation(pending: AuthGate.PendingConfirmation): Boolean {
        // Primary: in-overlay card — only when the service is running AND
        // the overlay permission is actually granted. Either precondition
        // failing used to mean a silently dropped card; now it means the
        // notification fallback below.
        if (LoomOverlayService.isRunning && OverlayPermission.granted(appContext)) {
            try {
                LoomOverlayService.showAuthCard(
                    appContext,
                    pending.id,
                    pending.action.title,
                    pending.action.detail,
                    pending.grantOffer,
                )
                // Device-evidence fix (2026-11-05, item 7): verify the
                // card ACTUALLY attached instead of returning true after
                // the fire-and-forget startService. The service records
                // the real outcome (shown, queued, or attach-failed)
                // keyed by pending id. A failed attach falls through to
                // the notification below; a timeout (service died before
                // handling the intent) does the same — never a parked
                // pending with nothing visible. Bounded wait (1 s):
                // the intent is handled on the service's main thread
                // within milliseconds in the normal case, and this runs
                // off the caller's thread, never the UI thread.
                val deadline = SystemClock.uptimeMillis() + AUTH_ATTACH_WAIT_MS
                while (SystemClock.uptimeMillis() < deadline) {
                    when (LoomOverlayService.authCardShown(pending.id)) {
                        true -> return true
                        false -> break // attach failed — notification fallback
                        null -> SystemClock.sleep(AUTH_ATTACH_POLL_MS)
                    }
                }
            } catch (_: Exception) {
                // Fall through to the notification fallback.
            }
        }
        // Fallback: notification with actions. The returned value is the
        // real post result — false makes the gate deny honestly at once.
        return try {
            val ok = NotificationCenter.get(appContext).postAuthRequest(
                pending.id,
                pending.action.title,
                pending.action.detail,
                pending.grantOffer,
            )
            if (ok) {
                synchronized(fallbackPendingIds) { fallbackPendingIds.add(pending.id) }
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    override fun hideConfirmation() {
        try {
            LoomOverlayService.hideAuthCard(appContext)
        } catch (_: Exception) {
        }
        dismissFallbackNotifications(null)
    }

    override fun hideConfirmation(pendingId: String) {
        try {
            LoomOverlayService.hideAuthCard(appContext, pendingId)
        } catch (_: Exception) {
        }
        dismissFallbackNotifications(pendingId)
    }

    override fun hideAllConfirmations() {
        try {
            LoomOverlayService.hideAuthCard(appContext, clearAll = true)
        } catch (_: Exception) {
        }
        dismissFallbackNotifications(null)
    }

    /**
     * Dismiss fallback notifications: just [pendingId], or all of them
     * when null (teardown / blanket hide).
     */
    private fun dismissFallbackNotifications(pendingId: String?) {
        val ids: List<String> = synchronized(fallbackPendingIds) {
            if (pendingId == null) {
                val all = fallbackPendingIds.toList()
                fallbackPendingIds.clear()
                all
            } else {
                if (fallbackPendingIds.remove(pendingId)) listOf(pendingId) else emptyList()
            }
        }
        for (id in ids) {
            try {
                NotificationCenter.get(appContext).dismissAuthRequest(id)
            } catch (_: Exception) {
            }
        }
    }
}
