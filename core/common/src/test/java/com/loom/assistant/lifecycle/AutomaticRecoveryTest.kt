package com.loom.assistant.lifecycle

/**
 * Automatic recovery (Nov 6) — JVM checks for [AutomaticRecovery] and
 * the explicit-reactivation hook on [LifecycleController].
 *
 * Plain-main() harness (PASS = exit 0 + "ALL N TESTS PASSED" line).
 * Run: test-harness/run-recovery-tests.sh
 *
 * Covers the six mandatory conditions:
 * (1) SHUTDOWN / DEACTIVATE / STOP markers all refuse recovery;
 * (2) the marker-clearing hook fires on explicit reactivation (and on
 *     pre-destructive shutdown recovery) and never on a refusal;
 * (3) a recovery attempt restores the last look and nothing else;
 * (4) helper-off / draw-permission-missing refuse with a plain report
 *     that names the Settings fix — never a re-enable;
 * (5) bounded retries: attempts 1→2→3 with 1 s → 2 s → 4 s, then give
 *     up — no 4th attempt, failure count persisted, reset on success;
 * (6) every user-facing string is plain words (extended jargon grep
 *     replicated here, so the check runs even without the shell grep).
 *
 * The Android half (SharedPreferences store, the runner's start +
 * wait + verify, the Settings toggle, the Home line) lives in
 * :platform:services / :presentation:settings / :presentation:home
 * and is verified on device.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

/** In-memory RecoveryStore — the Android store is the same contract. */
private class MemRecoveryStore : RecoveryStore {
    var enabled: Boolean = false
    var marker: IntentionalStop? = null
    var failures: Int = 0
    var report: String? = null
    var reportKind: String? = null
    var reportAtMs: Long = 0L

    override fun isAutoRecoverEnabled(): Boolean = enabled
    override fun setAutoRecoverEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun readIntentionalStop(): IntentionalStop? = marker
    override fun writeIntentionalStop(stop: IntentionalStop) {
        marker = stop
    }

    override fun clearIntentionalStop() {
        marker = null
    }

    override fun failureCount(): Int = failures
    override fun setFailureCount(count: Int) {
        failures = count
    }

    override fun lastReport(): String? = report
    override fun lastReportKind(): String? = reportKind
    override fun lastReportAtMs(): Long = reportAtMs
    override fun setLastReport(report: String?, kind: String?) {
        this.report = report
        this.reportKind = kind
        this.reportAtMs = if (report == null) 0L else 123456789L
    }
}

/** The unexpected-death signals: enabled, no marker, everything permitted, nothing running. */
private fun unexpectedDeathSignals(
    failureCount: Int = 0,
    lastModeName: String = "ORB",
): RecoverySignals = RecoverySignals(
    enabled = true,
    intentionalStop = null,
    failureCount = failureCount,
    a11yFlagOn = true,
    overlayPermissionGranted = true,
    lifecycleActive = true,
    assistantRunning = false,
    helperLive = false,
    lastModeName = lastModeName,
)

private fun passCheck(name: String) = object : ReactivationCheck {
    override val name: String = name
    override fun verify() = ReactivationCheckResult.Pass
}

fun main() {
    intentionalStopsRefuse()
    reactivationHook()
    restoresLastModeOnly()
    permissionRefusals()
    boundedRetries()
    plainWords()
    decisionOrder()

    println("AutomaticRecoveryTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        kotlin.system.exitProcess(1)
    }
}

// ---- (1) three negatives: SHUTDOWN / DEACTIVATE / STOP ----

private fun intentionalStopsRefuse() {
    for (command in IntentionalStopCommand.values()) {
        val signals = unexpectedDeathSignals().copy(
            intentionalStop = IntentionalStop(command, 1_700_000_000_000L),
        )
        val decision = AutomaticRecovery.decide(signals)
        check(
            "marker $command refuses recovery",
            decision is RecoveryDecision.Refused &&
                decision.reason == RecoveryRefusal.INTENTIONAL_STOP,
        )
        check("marker $command is never an Attempt", decision !is RecoveryDecision.Attempt)
        check(
            "marker $command carries no Home report (nothing happened)",
            AutomaticRecovery.refusalReport(RecoveryRefusal.INTENTIONAL_STOP) == null,
        )
    }
    // The marker records which command and when.
    val store = MemRecoveryStore()
    store.writeIntentionalStop(IntentionalStop(IntentionalStopCommand.SHUTDOWN, 1_700_000_000_000L))
    val read = store.readIntentionalStop()
    check("marker records the command", read?.command == IntentionalStopCommand.SHUTDOWN)
    check("marker records when", read?.atMs == 1_700_000_000_000L)
    store.clearIntentionalStop()
    check("marker clears", store.readIntentionalStop() == null)
    // Without a marker, the same signals attempt.
    check(
        "no marker → Attempt",
        AutomaticRecovery.decide(unexpectedDeathSignals()) is RecoveryDecision.Attempt,
    )
}

// ---- (2) marker cleared on explicit reactivation ----

private fun reactivationHook() {
    // Reactivation success fires the hook exactly once.
    LifecycleController.resetForTest()
    var fired = 0
    LifecycleController.onExplicitReactivation { fired++ }
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    val result = LifecycleController.requestReactivation(listOf(passCheck("session idle")))
    check("reactivation succeeds", result is ReactivationResult.Success)
    check("hook fires on reactivation success", fired == 1)

    // A refused reactivation never fires it.
    LifecycleController.resetForTest()
    fired = 0
    LifecycleController.onExplicitReactivation { fired++ }
    val refused = LifecycleController.requestReactivation(listOf(passCheck("session idle")))
    check("reactivation from ACTIVE is refused", refused is ReactivationResult.Refused)
    check("hook does not fire on refusal", fired == 0)

    // Pre-destructive shutdown recovery fires it too.
    LifecycleController.resetForTest()
    fired = 0
    LifecycleController.onExplicitReactivation { fired++ }
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    check("shutdown recovery succeeds", LifecycleController.requestShutdownRecovery())
    check("hook fires on shutdown recovery", fired == 1)

    // resetForTest clears the hook: a later reactivation fires nothing.
    LifecycleController.resetForTest()
    fired = 0
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    LifecycleController.requestReactivation(listOf(passCheck("session idle")))
    check("hook cleared by resetForTest", fired == 0)

    LifecycleController.resetForTest()
    LifecycleController.onExplicitReactivation(null)
}

// ---- (3) recovery restores the last look and nothing else ----

private fun restoresLastModeOnly() {
    for (mode in listOf("ORB", "CHARACTER", "INVISIBLE")) {
        val decision = AutomaticRecovery.decide(unexpectedDeathSignals(lastModeName = mode))
        check(
            "attempt restores last look $mode",
            decision is RecoveryDecision.Attempt && decision.restoreModeName == mode,
        )
    }
    // The attempt names the look and nothing else: no second mode, no
    // permission change, no other state rides along.
    val decision = AutomaticRecovery.decide(unexpectedDeathSignals(lastModeName = "ORB"))
    check(
        "attempt carries exactly the last look",
        decision is RecoveryDecision.Attempt &&
            decision.restoreModeName == "ORB" &&
            decision.attemptNumber == 1,
    )
}

// ---- (4) helper-off / permission-missing: refuse, plain report ----

private fun permissionRefusals() {
    val a11yOff = AutomaticRecovery.decide(unexpectedDeathSignals().copy(a11yFlagOn = false))
    check(
        "helper off refuses",
        a11yOff is RecoveryDecision.Refused && a11yOff.reason == RecoveryRefusal.A11Y_OFF,
    )
    val a11yReport = AutomaticRecovery.refusalReport(RecoveryRefusal.A11Y_OFF)
    check("helper-off report exists", a11yReport != null)
    check("helper-off report names Settings", a11yReport?.second?.contains("Settings") == true)
    check(
        "helper-off report names Accessibility",
        a11yReport?.second?.contains("Accessibility") == true,
    )
    check(
        "helper-off report kind is a11y_off",
        a11yReport?.first == AutomaticRecovery.REPORT_KIND_A11Y_OFF,
    )

    val permOff = AutomaticRecovery.decide(
        unexpectedDeathSignals().copy(overlayPermissionGranted = false),
    )
    check(
        "draw permission missing refuses",
        permOff is RecoveryDecision.Refused &&
            permOff.reason == RecoveryRefusal.OVERLAY_PERMISSION_MISSING,
    )
    val permReport = AutomaticRecovery.refusalReport(RecoveryRefusal.OVERLAY_PERMISSION_MISSING)
    check("permission report exists", permReport != null)
    check(
        "permission report names drawing over other apps",
        permReport?.second?.contains("draw over other apps") == true,
    )

    // Setting off refuses with no report and no attempt.
    val off = AutomaticRecovery.decide(unexpectedDeathSignals().copy(enabled = false))
    check(
        "setting off refuses",
        off is RecoveryDecision.Refused && off.reason == RecoveryRefusal.SETTING_OFF,
    )
    check(
        "setting-off refusal carries no report",
        AutomaticRecovery.refusalReport(RecoveryRefusal.SETTING_OFF) == null,
    )

    // Not ACTIVE refuses (a stop the lifecycle already recorded).
    val inactive = AutomaticRecovery.decide(unexpectedDeathSignals().copy(lifecycleActive = false))
    check(
        "lifecycle not ACTIVE refuses",
        inactive is RecoveryDecision.Refused &&
            inactive.reason == RecoveryRefusal.LIFECYCLE_NOT_ACTIVE,
    )
}

// ---- (5) bounded retries ----

private fun boundedRetries() {
    // Delays: 1 s → 2 s → 4 s, one per attempt number.
    val first = AutomaticRecovery.decide(unexpectedDeathSignals(failureCount = 0))
    check(
        "attempt 1 waits 1 s",
        first is RecoveryDecision.Attempt &&
            first.attemptNumber == 1 && first.delayMs == 1_000L,
    )
    val second = AutomaticRecovery.decide(unexpectedDeathSignals(failureCount = 1))
    check(
        "attempt 2 waits 2 s",
        second is RecoveryDecision.Attempt &&
            second.attemptNumber == 2 && second.delayMs == 2_000L,
    )
    val third = AutomaticRecovery.decide(unexpectedDeathSignals(failureCount = 2))
    check(
        "attempt 3 waits 4 s",
        third is RecoveryDecision.Attempt &&
            third.attemptNumber == 3 && third.delayMs == 4_000L,
    )
    // No 4th attempt: the cap gives up.
    val fourth = AutomaticRecovery.decide(unexpectedDeathSignals(failureCount = 3))
    check("failure count 3 gives up", fourth is RecoveryDecision.GiveUp)
    check("failure count 4 gives up too", AutomaticRecovery.decide(unexpectedDeathSignals(failureCount = 4)) is RecoveryDecision.GiveUp)

    // The count persists in the store across launches and drives the cap.
    val store = MemRecoveryStore()
    store.enabled = true
    check("first failure leaves another attempt", AutomaticRecovery.noteAttemptFailed(store))
    check("failure count persisted at 1", store.failureCount() == 1)
    check("second failure leaves another attempt", AutomaticRecovery.noteAttemptFailed(store))
    check("failure count persisted at 2", store.failureCount() == 2)
    check("third failure leaves no attempt", !AutomaticRecovery.noteAttemptFailed(store))
    check("failure count persisted at 3", store.failureCount() == 3)
    check(
        "a fresh decision with the persisted count gives up",
        AutomaticRecovery.decide(unexpectedDeathSignals(failureCount = store.failureCount())) is RecoveryDecision.GiveUp,
    )
    // Success resets the count: the next stop starts fresh.
    AutomaticRecovery.noteRecovered(store)
    check("success resets failure count", store.failureCount() == 0)
    check(
        "after reset the next stop attempts again",
        AutomaticRecovery.decide(unexpectedDeathSignals(failureCount = store.failureCount())) is RecoveryDecision.Attempt,
    )
    // The give-up report names the 3 tries and the manual fix.
    val giveUp = AutomaticRecovery.giveUpReport()
    check("give-up report names 3 times", giveUp.contains("3 times"))
    check("give-up report names Show pointer", giveUp.contains("Show pointer"))
}

// ---- (6) plain words in every user-facing string ----

private fun plainWords() {
    val banned = listOf(
        "attached", "detached", "anchored",
        "guidance window", "guidance overlay",
        "bound", "service", "renderer", "seam", "host",
        "foreground", "token", "dispatch", "overlay",
    )
    val strings = mapOf(
        "setting title" to AutomaticRecovery.SETTING_TITLE,
        "setting explain" to AutomaticRecovery.SETTING_EXPLAIN,
        "success" to AutomaticRecovery.successReport("Orb"),
        "success character" to AutomaticRecovery.successReport("Character"),
        "success invisible" to AutomaticRecovery.successReport("Invisible"),
        "partial" to AutomaticRecovery.partialReport("Orb"),
        "a11y off" to AutomaticRecovery.a11yOffReport(),
        "permission missing" to AutomaticRecovery.permissionMissingReport(),
        "give up" to AutomaticRecovery.giveUpReport(),
    )
    for ((name, text) in strings) {
        val lower = text.lowercase()
        for (word in banned) {
            check("plain words: $name has no '$word'", !lower.contains(word))
        }
    }
    // The report kinds are stored keys, never shown — but they must
    // still carry no banned word, so a kind can never leak into UI.
    val kinds = listOf(
        AutomaticRecovery.REPORT_KIND_SUCCESS,
        AutomaticRecovery.REPORT_KIND_PARTIAL,
        AutomaticRecovery.REPORT_KIND_A11Y_OFF,
        AutomaticRecovery.REPORT_KIND_PERMISSION_MISSING,
        AutomaticRecovery.REPORT_KIND_GAVE_UP,
    )
    for (kind in kinds) {
        for (word in banned) {
            check("plain words: kind '$kind' has no '$word'", !kind.contains(word))
        }
    }
}

// ---- decision order: nothing to do, helper loss still attempts ----

private fun decisionOrder() {
    // Everything up: nothing to do, no attempt, no report.
    val fine = AutomaticRecovery.decide(
        unexpectedDeathSignals().copy(assistantRunning = true, helperLive = true),
    )
    check("all up → NothingToDo", fine is RecoveryDecision.NothingToDo)

    // Floating assistant up but helper lost: still attempt — the wait
    // is for the helper to reconnect on its own, then report.
    val helperLost = AutomaticRecovery.decide(
        unexpectedDeathSignals().copy(assistantRunning = true, helperLive = false),
    )
    check("helper lost → Attempt", helperLost is RecoveryDecision.Attempt)

    // Marker wins over everything, even a dead assistant.
    val markerWins = AutomaticRecovery.decide(
        unexpectedDeathSignals().copy(
            intentionalStop = IntentionalStop(IntentionalStopCommand.STOP, 1L),
            a11yFlagOn = false,
            overlayPermissionGranted = false,
            failureCount = 3,
        ),
    )
    check(
        "marker beats permission + cap refusals",
        markerWins is RecoveryDecision.Refused &&
            markerWins.reason == RecoveryRefusal.INTENTIONAL_STOP,
    )

    // Setting off wins over a live problem too.
    val settingWins = AutomaticRecovery.decide(
        unexpectedDeathSignals().copy(enabled = false, a11yFlagOn = false),
    )
    check(
        "setting off beats permission refusals",
        settingWins is RecoveryDecision.Refused &&
            settingWins.reason == RecoveryRefusal.SETTING_OFF,
    )
}
