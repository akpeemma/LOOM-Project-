package com.loom.assistant.execution.interaction

import com.loom.assistant.core.common.LoomStateEvent

/**
 * Stage 2C (§5) — owns the halt primitive. This is the safety net every
 * sequence runs under: interrupt is implemented FIRST (STAGE2C-PLAN
 * appendix), and the queue checks the halt flag before EVERY dispatch.
 *
 * Pure Kotlin: no android.* imports. The host (platform:services) supplies
 * the [EventSink]/[AuditLogger] adapters that bridge to LoomEventBus /
 * ActivityLog; the test fakes satisfy the same seams.
 */

/**
 * Independent stop paths (§5.2). P1 (notification) + P2 (voice) satisfy the
 * ≥2 requirement; P3 (stop window) and P4 (toggle revocation) are the
 * belt-and-braces paths the design mandates.
 */
enum class HaltSource {
    NOTIFICATION,
    VOICE,
    STOP_WINDOW,
    TOGGLE_REVOKED,
}

/** Seam for event-bus reporting — production posts to LoomEventBus. */
fun interface EventSink {
    fun post(event: LoomStateEvent)
}

/** Seam for audit logging — production writes to ActivityLog. */
fun interface AuditLogger {
    fun audit(kind: String, detail: String)
}

/**
 * The four host-side effects halt must perform (§5.1 steps 2–5). Supplied
 * by InteractionLayer from its wired delegates; faked in tests.
 */
interface HaltHooks {
    /** Cancel any in-flight gesture (observe dispatchGesture onCancelled). */
    fun cancelInFlightGesture()

    /** GuidanceRenderer.hide() — beam, ring, tooltip gone in one frame. */
    fun hideRenderer()

    /** End the GuidanceSession / drain the ActionQueue. */
    fun endSession()

    /** Cancel every pending AuthGate confirmation of this sequence. */
    fun cancelPendingConfirmations()
}

/** The halt outcome. [interrupted] is always true — a halted sequence is
 * NEVER reported "completed". */
data class HaltReport(
    val interrupted: Boolean,
    val stepIndex: Int,
    val totalSteps: Int,
    val source: HaltSource,
)

class HaltController(
    private val events: EventSink,
    private val audit: AuditLogger,
) {

    /** §5.1 step 1: the halt flag. Volatile — read on the dispatch path. */
    @Volatile
    var haltRequested: Boolean = false
        private set

    fun requestHalt() {
        haltRequested = true
    }

    /** Cleared only when a new sequence starts. */
    fun reset() {
        haltRequested = false
    }

    /** The ActionQueue calls this before every dispatch and every token
     * request — nothing new starts once the flag is set. */
    fun allowDispatch(): Boolean = !haltRequested

    /**
     * The exact §5.1 sequence:
     * 1. set halt flag (done first, above)
     * 2. cancel in-flight gesture
     * 3. renderer?.hide() immediately
     * 4. end session / drain queue
     * 5. cancel pending AuthGate confirmations
     * 6. report INTERRUPTED {stepIndex, totalSteps, source} via event bus +
     *    audit log — NEVER "completed"
     * 7. (tokens) already-consumed tokens stay consumed; unconsumed ones are
     *    abandoned by the queue, which drops its references.
     */
    fun halt(
        source: HaltSource,
        stepIndex: Int,
        totalSteps: Int,
        hooks: HaltHooks,
    ): HaltReport {
        haltRequested = true
        hooks.cancelInFlightGesture()
        hooks.hideRenderer()
        hooks.endSession()
        hooks.cancelPendingConfirmations()
        events.post(LoomStateEvent.Interrupted(stepIndex, totalSteps, source.name))
        audit.audit(
            "INTERRUPTED",
            "sequence interrupted at step $stepIndex of $totalSteps " +
                "(source=${source.name})",
        )
        return HaltReport(
            interrupted = true,
            stepIndex = stepIndex,
            totalSteps = totalSteps,
            source = source,
        )
    }
}
