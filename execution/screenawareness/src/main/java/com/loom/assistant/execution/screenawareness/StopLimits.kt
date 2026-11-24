package com.loom.assistant.execution.screenawareness

/**
 * MASTER Wave 3 (F14) — the when-to-stop bounds, as single named
 * constants, not magic numbers in each caller.
 *
 * David's rule (Nov 9 ~00:28): a stop condition on every loop —
 * target-found, no-change detection, max attempts. A step that
 * produces no observable change twice in a row must stop and report,
 * never retry forever.
 */
object StopLimits {
    /** Bounded max scroll count per search / per scroll hunt. */
    const val MAX_SCROLLS_PER_SEARCH = 10

    /** No-change streak that stops a repeated action (F14d). */
    const val MAX_NO_CHANGE_STREAK = 2

    /** Honest terminal messages (F14e) — spoken AND written via TaskStatus. */
    const val REACHED_END_NOT_FOUND = "reached the end, not found"
    const val RESULTS_ALREADY_SHOWING = "results already showing"
    const val NO_CHANGE_STOPPING = "no change after two tries, stopping"
    const val MAX_SCROLLS_STOPPING = "stopped after 10 scrolls without finding the target"
}

/**
 * The no-change rule (F14d): counts consecutive actions that
 * produced no observable change. [observe] returns true when the
 * caller must STOP — two no-change actions in a row. Any observable
 * change resets the counter.
 */
class NoChangeTracker(
    private val maxStreak: Int = StopLimits.MAX_NO_CHANGE_STREAK,
) {
    private var streak = 0

    /** Records one action's outcome; true when the rule says stop. */
    fun observe(changed: Boolean): Boolean {
        if (changed) {
            streak = 0
            return false
        }
        streak++
        return streak >= maxStreak
    }

    fun reset() {
        streak = 0
    }

    fun currentStreak(): Int = streak
}
