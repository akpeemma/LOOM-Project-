package com.loom.assistant.lifecycle

/**
 * Persisted `lifecycle_state` record (plan §4.1 authoritative-source table).
 *
 * Written atomically after every verified transition; read on every launch
 * for recovery. It gates recovery-path selection — it is never the sole
 * evidence for SHUT_DOWN (only the §4.3 step-11 verification gate produces
 * that state). Replaces the old write-only `shutdown` pref.
 */
interface LifecyclePersistence {
    /** Last verified lifecycle state, or null when never written. */
    fun read(): LifecycleState?

    /** Atomically persist a verified state. Implementations must not
     * return until the write is durable. */
    fun write(state: LifecycleState)
}
