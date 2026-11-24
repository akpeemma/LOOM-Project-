package com.loom.assistant.core.common

/**
 * AOIE Phase 5 (2026-11-07) — the process-wide plan stop flag.
 *
 * The Phase 0 audit's open question (b): the two Stop mechanisms
 * (voice "stop" and the Stop window) reached plan runs ONLY in the
 * floating host, which passes a combined halt+lifecycle signal to
 * LoomPlanRunner. In-app plan runs (typed chat, Home) pass no
 * stopSignal, so PlanWiring fell back to a lifecycle-only signal and
 * a stop said mid-plan did nothing. Worse, the voice-stop handler
 * only halts when an InteractionLayer sequence is active — a plan
 * run is not a layer sequence, so "stop" during a plan answered
 * "Nothing is running" and set no flag anywhere.
 *
 * This registry is the shared flag both mechanisms set and every
 * plan run reads:
 *  - VoiceSessionManager.handleHaltSequence sets it on every voice
 *    stop (whether or not a layer sequence is active).
 *  - The Stop window's tap handler sets it.
 *  - PlanWiring's fallback stopSignal (used by in-app runs) is
 *    lifecycle OR this flag; the floating host's explicit signal
 *    ORs it too.
 *  - LoomPlanRunner clears it when a new plan starts, so a stop
 *    from a previous run can never kill the next run.
 *
 * Pure Kotlin, no Android imports, so every module (voice, pointer,
 * platform:services, planner) can use it with no new module edge.
 * It carries no task content — only a boolean + a timestamp.
 */
object PlanStopRegistry {

    @Volatile
    private var stoppedAtMs: Long? = null

    /** A stop was requested (voice "stop" or Stop window tap). */
    fun requestStop() {
        stoppedAtMs = System.currentTimeMillis()
    }

    /** True when a stop has been requested since the last [clear]. */
    fun isStopped(): Boolean = stoppedAtMs != null

    /** Milliseconds since the stop was requested, or null. */
    fun stoppedAgoMs(nowMs: Long = System.currentTimeMillis()): Long? =
        stoppedAtMs?.let { nowMs - it }

    /** Clears the flag — called when a new plan run starts. */
    fun clear() {
        stoppedAtMs = null
    }
}
