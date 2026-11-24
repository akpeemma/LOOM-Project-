package com.loom.assistant.core.common

import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 2 (E.4) — mutual exclusion between in-app and floating voice
 * sessions. Only one may own the microphone at a time.
 *
 * Replaces the two independent `@Volatile` booleans (TOCTOU windows: the
 * service's `onDestroy` could clear `floatingActive` while a session was
 * starting, and check-then-set races let both sides believe they owned the
 * mic). Ownership is now a single [AtomicReference]: claim and release are
 * one CAS each, so exactly one owner can hold the mic at any instant.
 *
 * Division of labor (unchanged): hosts claim around their session —
 * in-app hosts on session-state transitions, the overlay service around its
 * floating session. The manager never claims; it only reads.
 */
object VoiceSessionOwner {

    /** Who currently owns the microphone. Exactly one owner at a time. */
    enum class Owner { NONE, IN_APP, FLOATING }

    private val owner = AtomicReference(Owner.NONE)

    /** Single-atomic-read ownership queries (replaces the volatile booleans). */
    val inAppActive: Boolean get() = owner.get() == Owner.IN_APP
    val floatingActive: Boolean get() = owner.get() == Owner.FLOATING
    val isOwned: Boolean get() = owner.get() != Owner.NONE

    /** Current owner — diagnostics and the §7-D mic-grounding desync test. */
    fun currentOwner(): Owner = owner.get()

    /**
     * Atomic claim. Succeeds only when the mic is free (NONE → owner in one
     * CAS) — the check-then-set race is closed: two racers, exactly one
     * winner. Returns false when another session already owns the mic; the
     * caller must NOT start in that case.
     */
    fun claimInApp(): Boolean = owner.compareAndSet(Owner.NONE, Owner.IN_APP)
    fun claimFloating(): Boolean = owner.compareAndSet(Owner.NONE, Owner.FLOATING)

    /**
     * Atomic release. Succeeds only when the caller is the current owner —
     * a stale `onDestroy` (or a late IDLE callback) can never clear another
     * session's live ownership. Safe to call redundantly; returns false when
     * there is nothing to release.
     */
    fun releaseInApp(): Boolean = owner.compareAndSet(Owner.IN_APP, Owner.NONE)
    fun releaseFloating(): Boolean = owner.compareAndSet(Owner.FLOATING, Owner.NONE)
}
