package com.loom.assistant.core.common

/**
 * JVM test fakes for the event bus. Member shapes mirror the real
 * core:common sources exactly (only the members the pointer tests compile
 * against).
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
}

object LoomEventBus {
    val posted = mutableListOf<LoomStateEvent>()

    fun post(event: LoomStateEvent) {
        posted.add(event)
    }

    fun clearForTest() {
        posted.clear()
    }
}
