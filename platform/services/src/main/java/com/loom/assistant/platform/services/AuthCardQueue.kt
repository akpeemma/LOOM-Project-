package com.loom.assistant.platform.services

/**
 * Master §8 — ordering state for the in-overlay AuthGate cards.
 *
 * Pure Kotlin, no Android imports, so the ordering is unit-testable in
 * the JVM harness (AuthCardQueueTest).
 *
 * The defect it fixes (card clobbering): the gate can park several
 * pendings at once, but the service showed one card and the old
 * showAuthCard() called hideAuthCard() first — which only removed the
 * view without resolving or cancelling the previous pending. The parked
 * pending then sat unanswerable until its 90 s expiry.
 *
 * Invariant: every parked pending is either the CURRENT card (visible
 * and answerable) or QUEUED behind it (answerable as soon as the
 * current card resolves). No pending is ever dropped silently:
 * - [show]: a card becomes current when nothing is shown, otherwise it
 *   queues behind the current one.
 * - [hide]: hiding the current card advances the queue — the next card
 *   becomes current and must be attached. Hiding a queued card removes
 *   it from the queue (its pending was resolved/cancelled elsewhere)
 *   and leaves the current card untouched. Hiding an unknown id is a
 *   no-op, so a stale hide can never kill a live card.
 * - [clearAll]: teardown only (service dying) — drops the current card
 *   and the whole queue. The gate's in-memory pendings die with the
 *   process in that path; in the ordered shutdown [AuthGate.purgeAll]
 *   cancels them first.
 *
 * The queue itself never resolves or cancels a pending — that stays
 * the gate's job. The service maps queue events to gate calls:
 * attach failure of a current card → AuthGate.cancelPending(id), so
 * the request fails honestly at once instead of parking unanswerable.
 */
class AuthCardQueue {

    /** One card waiting for (or occupying) the screen. */
    data class Card(
        val pendingId: String,
        val title: String,
        val detail: String,
        val grantOffer: String? = null,
    )

    /** What [show] did with the card. */
    enum class ShowResult { SHOWN, QUEUED }

    private var current: Card? = null
    private val queued = ArrayDeque<Card>()

    /** The card on screen right now, or null. */
    fun current(): Card? = current

    /** Number of cards parked behind the current one. */
    fun queuedCount(): Int = queued.size

    /** Total cards held (current + queued). */
    fun size(): Int = (if (current == null) 0 else 1) + queued.size

    /**
     * Offer a card. Returns [ShowResult.SHOWN] when it becomes the
     * current card (the caller attaches it now) or [ShowResult.QUEUED]
     * when it waits behind the current one.
     */
    fun show(card: Card): ShowResult {
        if (current == null) {
            current = card
            return ShowResult.SHOWN
        }
        queued.addLast(card)
        return ShowResult.QUEUED
    }

    /**
     * Hide the card for [pendingId]. Returns the card that becomes
     * current as a result (the caller attaches it), or null when no
     * card takes the screen:
     * - id == current → current is dropped and the next queued card
     *   (if any) becomes current.
     * - id is queued → it is removed; the current card is untouched.
     * - id unknown → nothing changes (stale hide is a no-op).
     */
    fun hide(pendingId: String): Card? {
        if (current?.pendingId == pendingId) {
            current = null
            return advance()
        }
        queued.removeAll { it.pendingId == pendingId }
        return null
    }

    /** Drop the current card and the whole queue (teardown only). */
    fun clearAll() {
        current = null
        queued.clear()
    }

    private fun advance(): Card? {
        if (queued.isEmpty()) return null
        val next = queued.removeFirst()
        current = next
        return next
    }
}
