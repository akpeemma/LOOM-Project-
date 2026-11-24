package com.loom.assistant.core.common

import com.loom.assistant.lifecycle.LifecycleState

/**
 * Cross-module state events (Phase 3 architecture §1).
 * Publishers: voice pipeline, response engine, pointer module.
 * Consumers: orb/character views, edge-light, dashboard, notifications.
 */
sealed class LoomStateEvent {
    data object Idle : LoomStateEvent()
    data object Listening : LoomStateEvent()
    data object Thinking : LoomStateEvent()
    data object Analyzing : LoomStateEvent()
    data object Speaking : LoomStateEvent()
    data object Paused : LoomStateEvent()
    data object Executing : LoomStateEvent()
    data object Completed : LoomStateEvent()

    /**
     * Stage 2C (§5.1): a guidance/demonstration sequence was interrupted.
     * Carries the step position and the stop path. A halted sequence is
     * NEVER reported as [Completed].
     */
    data class Interrupted(
        val stepIndex: Int,
        val totalSteps: Int,
        val source: String,
    ) : LoomStateEvent()
    data class Error(val message: String) : LoomStateEvent()
    data class ModeChanged(val mode: String) : LoomStateEvent()

    /**
     * Automatic recovery (Nov 6): the recovery runner finished an
     * attempt and wrote a plain-words report for Home. Carries no
     * text — Home reads the report from the recovery store, so the
     * event can never go stale.
     */
    data object RecoveryReported : LoomStateEvent()

    /**
     * Lifecycle plan §4.1: Layer-1 lifecycle state change, published by
     * [com.loom.assistant.lifecycle.LifecycleController] on every verified
     * transition. Carries from/to plus the human-readable reason.
     */
    data class LifecycleChanged(
        val from: LifecycleState,
        val to: LifecycleState,
        val reason: String,
    ) : LoomStateEvent()
}
