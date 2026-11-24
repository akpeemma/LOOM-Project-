package com.loom.assistant.lifecycle

/**
 * Automatic recovery (Nov 6, David-approved, opt-in, default OFF).
 *
 * When enabled, LOOM may bring its floating assistant back on its own
 * after the phone stops it unexpectedly (process killed, helper
 * connection lost overnight). It NEVER brings LOOM back after David
 * stopped it himself — that is what the intentional-stop marker is for.
 *
 * Pure Kotlin, no Android imports, so the harness tests it on the JVM.
 * The Android half (SharedPreferences store, the runner that starts
 * the floating assistant and waits for it) lives in :platform:services
 * ([AutomaticRecoveryStore], [AutomaticRecoveryRunner] — same package
 * name, different module); this object owns only the decision: given
 * the signals, is an attempt allowed, which attempt is it, how long to
 * wait first, and what plain sentence does Home show.
 *
 * Plain-words rule: no user-facing string here may contain attached /
 * detached / anchored / guidance window / guidance overlay / bound /
 * service / renderer / seam / host / foreground / token / dispatch /
 * overlay. The harness test enforces it (test 6).
 */

/** Which explicit user command stopped LOOM. Recorded in the marker. */
enum class IntentionalStopCommand {
    SHUTDOWN,
    DEACTIVATE,
    STOP,
}

/**
 * The durable intentional-stop marker. Written BEFORE teardown in the
 * shutdown / deactivation / stop paths, cleared only on an explicit
 * user return to ACTIVE (launcher reactivation, shutdown recovery).
 * Process death leaves no marker — that is exactly how an unexpected
 * stop is told apart from an intentional one.
 */
data class IntentionalStop(
    val command: IntentionalStopCommand,
    val atMs: Long,
)

/** Durable recovery state. Android implementation: SharedPreferences. */
interface RecoveryStore {
    /** The opt-in setting. Default OFF. */
    fun isAutoRecoverEnabled(): Boolean
    fun setAutoRecoverEnabled(enabled: Boolean)

    /** The intentional-stop marker, or null when LOOM was never stopped on purpose. */
    fun readIntentionalStop(): IntentionalStop?
    fun writeIntentionalStop(stop: IntentionalStop)
    fun clearIntentionalStop()

    /** Consecutive failed recovery attempts. Resets on success or explicit user action. */
    fun failureCount(): Int
    fun setFailureCount(count: Int)

    /** Last plain-words report for Home, with its kind and write time. */
    fun lastReport(): String?
    fun lastReportKind(): String?
    fun lastReportAtMs(): Long
    fun setLastReport(report: String?, kind: String?)
}

/** Everything the decision needs, read fresh at each attempt. */
data class RecoverySignals(
    val enabled: Boolean,
    val intentionalStop: IntentionalStop?,
    val failureCount: Int,
    val a11yFlagOn: Boolean,
    val overlayPermissionGranted: Boolean,
    val lifecycleActive: Boolean,
    /** Floating assistant running in the last look (running + that look showing). */
    val assistantRunning: Boolean,
    /** Helper connection live right now. */
    val helperLive: Boolean,
    /** Last look, by name — the ONLY thing a recovery restores. */
    val lastModeName: String,
)

sealed interface RecoveryDecision {
    /** An attempt may run: which one, how long to wait first, what to restore. */
    data class Attempt(
        val attemptNumber: Int,
        val delayMs: Long,
        val restoreModeName: String,
    ) : RecoveryDecision

    /** No attempt — the reason names why, and whether Home should say so. */
    data class Refused(val reason: RecoveryRefusal) : RecoveryDecision

    /** Nothing is missing — floating assistant and helper are both up. */
    data object NothingToDo : RecoveryDecision

    /** Failure count reached the cap: stop trying until the user acts. */
    data object GiveUp : RecoveryDecision
}

enum class RecoveryRefusal {
    SETTING_OFF,
    INTENTIONAL_STOP,
    LIFECYCLE_NOT_ACTIVE,
    A11Y_OFF,
    OVERLAY_PERMISSION_MISSING,
}

object AutomaticRecovery {

    /** Bounded retries: never more than this many attempts per stop. */
    const val MAX_ATTEMPTS = 3

    /**
     * Wait before each attempt, in order: 1 s, then 2 s, then 4 s.
     * Indexed by attemptNumber - 1.
     */
    val DELAY_BEFORE_ATTEMPT_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    /** A Home report counts as fresh for this long after it was written. */
    const val REPORT_FRESH_MS: Long = 15L * 60L * 1000L

    // ---- report kinds (stored with the report; Home reads the kind) ----
    const val REPORT_KIND_SUCCESS = "success"
    const val REPORT_KIND_PARTIAL = "partial"
    const val REPORT_KIND_A11Y_OFF = "a11y_off"
    const val REPORT_KIND_PERMISSION_MISSING = "permission_missing"
    const val REPORT_KIND_GAVE_UP = "gave_up"

    // ---- Settings strings ----
    const val SETTING_TITLE = "Recover LOOM automatically if it stops unexpectedly"
    const val SETTING_EXPLAIN =
        "If LOOM closes by itself — for example if your phone stops it " +
            "overnight — LOOM will try to bring itself back, in the same " +
            "look you left it. It never comes back after you shut it down " +
            "or turn it off yourself."

    // ---- report strings (modeShortLabel is "Orb" / "Character" / "Invisible") ----
    fun successReport(modeShortLabel: String): String =
        "LOOM came back by itself — your $modeShortLabel look is showing again."

    fun partialReport(modeShortLabel: String): String =
        "LOOM came back by itself — your $modeShortLabel look is showing again. " +
            "The helper still isn't running, so the pointer and typing won't " +
            "work until you turn LOOM off and on again in Settings, under Accessibility."

    fun a11yOffReport(): String =
        "LOOM couldn't come back by itself — it needs to be turned on in " +
            "Settings, under Accessibility. Tap below to open it."

    fun permissionMissingReport(): String =
        "LOOM couldn't come back by itself — it needs permission to draw " +
            "over other apps. Open Settings to allow it."

    fun giveUpReport(): String =
        "LOOM tried to come back 3 times and couldn't. Open the app and " +
            "tap Show pointer to bring it back."

    /**
     * The decision, in order. The marker is checked FIRST, before any
     * start attempt — an intentional stop is never recovered, whatever
     * else is true. Permissions are checked before the failure cap, so
     * a switched-off helper reports the fix instead of "tried 3 times".
     * A live helper with a missing floating assistant still attempts:
     * the wait is for the floating assistant, and the helper is
     * re-checked inside the attempt.
     */
    fun decide(signals: RecoverySignals): RecoveryDecision {
        if (!signals.enabled) return RecoveryDecision.Refused(RecoveryRefusal.SETTING_OFF)
        if (signals.intentionalStop != null) {
            return RecoveryDecision.Refused(RecoveryRefusal.INTENTIONAL_STOP)
        }
        if (!signals.lifecycleActive) {
            return RecoveryDecision.Refused(RecoveryRefusal.LIFECYCLE_NOT_ACTIVE)
        }
        if (!signals.a11yFlagOn) return RecoveryDecision.Refused(RecoveryRefusal.A11Y_OFF)
        if (!signals.overlayPermissionGranted) {
            return RecoveryDecision.Refused(RecoveryRefusal.OVERLAY_PERMISSION_MISSING)
        }
        if (signals.assistantRunning && signals.helperLive) {
            return RecoveryDecision.NothingToDo
        }
        if (signals.failureCount >= MAX_ATTEMPTS) return RecoveryDecision.GiveUp
        val attemptNumber = signals.failureCount + 1
        return RecoveryDecision.Attempt(
            attemptNumber = attemptNumber,
            delayMs = DELAY_BEFORE_ATTEMPT_MS[attemptNumber - 1],
            restoreModeName = signals.lastModeName,
        )
    }

    /**
     * The Home report for a refusal, or null when nothing should be
     * shown (setting off, intentional stop, not active: nothing
     * happened, so there is nothing to say). Only the two permission
     * refusals name a fix for the user.
     */
    fun refusalReport(reason: RecoveryRefusal): Pair<String, String>? = when (reason) {
        RecoveryRefusal.A11Y_OFF -> REPORT_KIND_A11Y_OFF to a11yOffReport()
        RecoveryRefusal.OVERLAY_PERMISSION_MISSING ->
            REPORT_KIND_PERMISSION_MISSING to permissionMissingReport()
        else -> null
    }

    fun giveUpReportPair(): Pair<String, String> = REPORT_KIND_GAVE_UP to giveUpReport()

    fun successReportPair(modeShortLabel: String): Pair<String, String> =
        REPORT_KIND_SUCCESS to successReport(modeShortLabel)

    fun partialReportPair(modeShortLabel: String): Pair<String, String> =
        REPORT_KIND_PARTIAL to partialReport(modeShortLabel)

    /**
     * Record one failed attempt. Returns true when another attempt
     * remains; false means the cap is reached — give up, never a 4th
     * attempt. Recovery never re-triggers itself from its own failure:
     * the caller schedules the next attempt only when this returns true.
     */
    fun noteAttemptFailed(store: RecoveryStore): Boolean {
        val count = (store.failureCount() + 1).coerceAtMost(MAX_ATTEMPTS)
        store.setFailureCount(count)
        return count < MAX_ATTEMPTS
    }

    /** A verified recovery resets the count — the next stop starts fresh. */
    fun noteRecovered(store: RecoveryStore) {
        store.setFailureCount(0)
    }
}
