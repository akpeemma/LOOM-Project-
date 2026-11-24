package com.loom.assistant.intelligence.authgate

/**
 * Phase 6 — presentation contract for CONFIRM decisions (arch §2.14).
 *
 * The gate itself never touches UI: it asks its [AuthConfirmationHost] to
 * present the pending confirmation, and the host calls
 * [AuthGate.resolve] when David taps Approve/Deny. Two hosts exist:
 * an in-overlay card (primary, per the architecture) and a notification
 * fallback when the overlay is not running.
 *
 * Master §8 — pending-aware hides: the gate can park several pendings
 * (one per request), and the overlay shows them one at a time from a
 * queue. A blanket hide would orphan the queued pendings' cards, so the
 * gate hides by id: [hideConfirmation] with a pending id hides only
 * that pending's card (shown → hide and advance the queue; queued →
 * drop from the queue). [hideAllConfirmations] clears every card at
 * once and is used only by [AuthGate.purgeAll] on deactivation/shutdown.
 * Both new methods default to the old blanket [hideConfirmation] so
 * existing hosts keep compiling; the production host overrides them.
 */
interface AuthConfirmationHost {
    /**
     * Present [pending] to David. Return true when it was actually shown;
     * false makes the gate deny the action (fail closed).
     */
    fun showConfirmation(pending: AuthGate.PendingConfirmation): Boolean

    /** Hide any visible confirmation card. */
    fun hideConfirmation()

    /**
     * Hide the card for one pending only. Default: the blanket hide —
     * hosts that queue cards override this with the pending-aware
     * behaviour so resolving one pending never orphans another's card.
     */
    fun hideConfirmation(pendingId: String) {
        hideConfirmation()
    }

    /**
     * Hide every confirmation card (shown + queued). Used only by
     * [AuthGate.purgeAll] on deactivation/shutdown. Default: the blanket
     * hide — hosts that queue cards override this to also drop the queue.
     */
    fun hideAllConfirmations() {
        hideConfirmation()
    }
}
