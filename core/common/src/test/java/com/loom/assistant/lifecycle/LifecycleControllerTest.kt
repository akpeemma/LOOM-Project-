package com.loom.assistant.lifecycle

import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent

/**
 * Lifecycle plan §7-B / map E1 — LifecycleController checks.
 *
 * Plain-main() harness (PASS = exit 0 + summary line; exact counts).
 * Run: kotlinc <lifecycle sources> <LoomStateEvent.kt> <LoomEventBus.kt>
 *   <android fakes> LifecycleControllerTest.kt -d out
 *   && java -cp out com.loom.assistant.lifecycle.LifecycleControllerTestKt
 *
 * Covers: transition matrix (allowed/refused per §4.1), SHUT_DOWN guards
 * (no SHUT_DOWN while mic/session/service/views live), audit log, bus
 * publication, persistence, impossible-state prevention/repair, launch
 * recovery, reactivation, shutdown recovery (closure decision 2).
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

private class MemPersistence : LifecyclePersistence {
    var stored: LifecycleState? = null
    val writes = mutableListOf<LifecycleState>()
    override fun read(): LifecycleState? = stored
    override fun write(state: LifecycleState) {
        stored = state
        writes.add(state)
    }
}

private fun passCheck(name: String) = object : ReactivationCheck {
    override val name: String = name
    override fun verify() = ReactivationCheckResult.Pass
}

private fun failCheck(name: String, reason: String) = object : ReactivationCheck {
    override val name: String = name
    override fun verify() = ReactivationCheckResult.Fail(reason)
}

private val busEvents = mutableListOf<LoomStateEvent>()
private val busListener: (LoomStateEvent) -> Unit = { busEvents.add(it) }

private fun reset() {
    LifecycleController.resetForTest()
    busEvents.clear()
}

private fun lastChanged(): LoomStateEvent.LifecycleChanged? =
    busEvents.filterIsInstance<LoomStateEvent.LifecycleChanged>().lastOrNull()

private fun auditKinds(): List<String> = LifecycleController.auditLog().map { it.kind }

fun main() {
    LoomEventBus.subscribe(busListener)
    try {
        matrixAllowed()
        matrixRefused()
        shutDownGuards()
        sessionMirror()
        impossibleStates()
        shutdownRecovery()
        reactivation()
        launchRecovery()
        persistenceAndBus()
        auditAndReads()
        shutdownReactivationRestoresVoice()
    } finally {
        LoomEventBus.unsubscribe(busListener)
    }

    println("LifecycleControllerTest: passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

// ---- §4.1 transition matrix: allowed ----

private fun matrixAllowed() {
    reset()
    check("initial state ACTIVE", LifecycleController.currentState() == LifecycleState.ACTIVE)
    check("dispatch allowed when ACTIVE", LifecycleController.isDispatchAllowed())

    check("ACTIVE→INACTIVE", LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate"))
    check("state INACTIVE", LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE)
    check("dispatch refused when INACTIVE", !LifecycleController.isDispatchAllowed())

    reset()
    check("ACTIVE→SHUTTING_DOWN", LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown"))
    check("dispatch refused when SHUTTING_DOWN", !LifecycleController.isDispatchAllowed())

    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    check("INACTIVE→REACTIVATING", LifecycleController.transitionTo(LifecycleState.REACTIVATING, "launcher"))
    check("dispatch refused when REACTIVATING", !LifecycleController.isDispatchAllowed())

    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    LifecycleController.transitionTo(LifecycleState.REACTIVATING, "launcher")
    check("REACTIVATING→ACTIVE", LifecycleController.transitionTo(LifecycleState.ACTIVE, "verified"))
    check("dispatch allowed after reactivation", LifecycleController.isDispatchAllowed())

    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    LifecycleController.transitionTo(LifecycleState.REACTIVATING, "launcher")
    check("REACTIVATING→INACTIVE (failed verification)", LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "verify failed"))

    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    check("INACTIVE→SHUTTING_DOWN", LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown"))

    reset()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    check("SHUTTING_DOWN→SHUT_DOWN (all down)", LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "verified"))
    check("state SHUT_DOWN", LifecycleController.currentState() == LifecycleState.SHUT_DOWN)
    check("dispatch refused when SHUT_DOWN", !LifecycleController.isDispatchAllowed())
    check("SHUT_DOWN→REACTIVATING", LifecycleController.transitionTo(LifecycleState.REACTIVATING, "launcher"))

    reset()
    check("same-state ACTIVE→ACTIVE is no-op success", LifecycleController.transitionTo(LifecycleState.ACTIVE))
}

// ---- §4.1 transition matrix: refused ----

private fun matrixRefused() {
    reset()
    check("ACTIVE→SHUT_DOWN refused", !LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "skip"))
    check("ACTIVE→REACTIVATING refused", !LifecycleController.transitionTo(LifecycleState.REACTIVATING, "skip"))
    check("state still ACTIVE", LifecycleController.currentState() == LifecycleState.ACTIVE)

    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    check("INACTIVE→ACTIVE refused (must reactivate)", !LifecycleController.transitionTo(LifecycleState.ACTIVE, "skip"))
    check("INACTIVE→SHUT_DOWN refused", !LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "skip"))

    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    LifecycleController.transitionTo(LifecycleState.REACTIVATING, "launcher")
    check("REACTIVATING→SHUTTING_DOWN refused", !LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "x"))
    check("REACTIVATING→SHUT_DOWN refused", !LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "x"))

    reset()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "verified")
    check("SHUT_DOWN→ACTIVE refused", !LifecycleController.transitionTo(LifecycleState.ACTIVE, "x"))
    check("SHUT_DOWN→SHUTTING_DOWN refused", !LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "x"))
    check("SHUT_DOWN→INACTIVE refused", !LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "x"))
    check("state still SHUT_DOWN", LifecycleController.currentState() == LifecycleState.SHUT_DOWN)

    reset()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    check("SHUTTING_DOWN→INACTIVE refused", !LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "x"))
    check("SHUTTING_DOWN→REACTIVATING refused", !LifecycleController.transitionTo(LifecycleState.REACTIVATING, "x"))
    check("SHUTTING_DOWN→ACTIVE via transitionTo refused (use recovery)", !LifecycleController.transitionTo(LifecycleState.ACTIVE, "x"))
    check("state still SHUTTING_DOWN", LifecycleController.currentState() == LifecycleState.SHUTTING_DOWN)
}

// ---- SHUT_DOWN guards: no SHUT_DOWN while anything live ----

private fun shutDownGuards() {
    reset()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")

    LifecycleController.reportMicLive(true)
    check("SHUT_DOWN refused while mic live", !LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "gate"))
    LifecycleController.reportMicLive(false)

    LifecycleController.reportServiceLive(true)
    check("SHUT_DOWN refused while service live", !LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "gate"))
    LifecycleController.reportServiceLive(false)

    LifecycleController.reportViewsAttached(true)
    check("SHUT_DOWN refused while views attached", !LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "gate"))
    LifecycleController.reportViewsAttached(false)

    // Session mirror live while SHUTTING_DOWN is impossible state #1:
    // reportSessionState drops it as stale — so drive the guard via a
    // direct live-mirror report BEFORE entering SHUTTING_DOWN instead.
    reset()
    LifecycleController.reportSessionState("SPEAKING")
    check("mirror SPEAKING while ACTIVE", LifecycleController.currentObservable().sessionMirror == "SPEAKING")
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    check("leaving ACTIVE resets live mirror to IDLE", LifecycleController.currentObservable().sessionMirror == "IDLE")
    check("SHUT_DOWN allowed once all down", LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "verified"))
}

// ---- session mirror: read-only, never drives lifecycle ----

private fun sessionMirror() {
    reset()
    LifecycleController.reportSessionState("LISTENING")
    val obs = LifecycleController.currentObservable()
    check("observable computed live (ACTIVE+LISTENING)", obs.lifecycle == LifecycleState.ACTIVE && obs.sessionMirror == "LISTENING")
    check("mirror never changes lifecycle state", LifecycleController.currentState() == LifecycleState.ACTIVE)
    check("dispatch still allowed with live mirror", LifecycleController.isDispatchAllowed())

    LifecycleController.reportSessionState("FROBNICATE")
    check("unknown session name ignored", LifecycleController.currentObservable().sessionMirror == "LISTENING")
    check("unknown name audited", auditKinds().contains("SESSION_MIRROR_UNKNOWN"))

    LifecycleController.reportSessionState("idle") // case-insensitive
    check("mirror IDLE accepted", LifecycleController.currentObservable().sessionMirror == "IDLE")

    // Mirror updates while INACTIVE: terminal states accepted silently.
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    LifecycleController.reportSessionState("ENDING")
    check("ENDING mirror accepted while INACTIVE", LifecycleController.currentObservable().sessionMirror == "ENDING")
}

// ---- impossible states: prevented, repaired, audited ----

private fun impossibleStates() {
    // #1: live session ∧ lifecycle != ACTIVE → dropped, teardown requested, audited.
    reset()
    var teardownFired = false
    LifecycleController.onSessionTeardownRequested { teardownFired = true }
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    LifecycleController.reportSessionState("LISTENING")
    check("impossible #1: live mirror dropped while INACTIVE", LifecycleController.currentObservable().sessionMirror == "IDLE")
    check("impossible #1: teardown requested", teardownFired)
    check("impossible #1: lifecycle kept (still INACTIVE)", LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE)
    check("impossible #1: anomaly audited", auditKinds().contains("IMPOSSIBLE_STATE"))

    // #1 variant while SHUT_DOWN.
    reset()
    teardownFired = false
    LifecycleController.onSessionTeardownRequested { teardownFired = true }
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "verified")
    LifecycleController.reportSessionState("PAUSED")
    check("impossible #1: PAUSED mirror dropped while SHUT_DOWN", LifecycleController.currentObservable().sessionMirror == "IDLE")
    check("impossible #1 (SHUT_DOWN): teardown requested", teardownFired)

    // #5: observable is computed live — two reads with a mirror change
    // between them can never disagree.
    reset()
    LifecycleController.reportSessionState("PROCESSING")
    val a = LifecycleController.currentObservable()
    LifecycleController.reportSessionState("IDLE")
    val b = LifecycleController.currentObservable()
    check("impossible #5: observable never stale", a.sessionMirror == "PROCESSING" && b.sessionMirror == "IDLE")

    // Session states are not lifecycle states: the enum carries exactly the
    // five Layer-1 states — LISTENING/PROCESSING/SPEAKING can never be set.
    val names = LifecycleState.values().map { it.name }.toSet()
    check(
        "enum holds exactly the 5 lifecycle states",
        names == setOf("ACTIVE", "ASSISTANT_MODE_INACTIVE", "SHUTTING_DOWN", "SHUT_DOWN", "REACTIVATING"),
    )
}

// ---- SHUTTING_DOWN recovery (closure decision 2) ----

private fun shutdownRecovery() {
    // Pre-destructive: user-initiated recovery → ACTIVE, session IDLE.
    reset()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    check("pre-destructive recovery allowed", LifecycleController.requestShutdownRecovery())
    check("recovered to ACTIVE", LifecycleController.currentState() == LifecycleState.ACTIVE)
    check("session mirror IDLE after recovery", LifecycleController.currentObservable().sessionMirror == "IDLE")
    check("recovery bus-published", lastChanged()?.let { it.from == LifecycleState.SHUTTING_DOWN && it.to == LifecycleState.ACTIVE } == true)

    // Post-destructive: refused, stays SHUTTING_DOWN with failure record.
    reset()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    LifecycleController.noteDestructiveStep("authgate-purge")
    check("post-destructive recovery refused", !LifecycleController.requestShutdownRecovery())
    check("stays SHUTTING_DOWN", LifecycleController.currentState() == LifecycleState.SHUTTING_DOWN)
    check("refusal audited", auditKinds().contains("RECOVERY_REFUSED"))

    // Recovery from a non-SHUTTING_DOWN state is refused.
    reset()
    check("recovery from ACTIVE refused", !LifecycleController.requestShutdownRecovery())
}

// ---- deliberate reactivation (§4.4/§4.6) ----

private fun reactivation() {
    // Wrong-state request refused.
    reset()
    val refused = LifecycleController.requestReactivation(listOf(passCheck("any")))
    check("reactivation from ACTIVE refused", refused is ReactivationResult.Refused)

    // Happy path: INACTIVE → REACTIVATING → ACTIVE.
    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    val ok = LifecycleController.requestReactivation(listOf(passCheck("mic"), passCheck("session")))
    check("reactivation success", ok is ReactivationResult.Success)
    check("state ACTIVE after reactivation", LifecycleController.currentState() == LifecycleState.ACTIVE)
    check("REACTIVATING bus-published", busEvents.filterIsInstance<LoomStateEvent.LifecycleChanged>().any {
        it.to == LifecycleState.REACTIVATING
    })

    // Failed verification → safe fallback INACTIVE, never half-active.
    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    val failedResult = LifecycleController.requestReactivation(listOf(passCheck("mic"), failCheck("pipeline", "no engine")))
    check("reactivation failure reported", failedResult is ReactivationResult.Failed)
    check(
        "failure names the check",
        (failedResult as? ReactivationResult.Failed)?.failures?.any { it.contains("pipeline") } == true,
    )
    check("failed reactivation falls back to INACTIVE", LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE)

    // After complete shutdown: SHUT_DOWN → reactivation → ACTIVE.
    reset()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "verified")
    val ok2 = LifecycleController.requestReactivation(listOf(passCheck("mic")))
    check("reactivation after SHUT_DOWN succeeds", ok2 is ReactivationResult.Success)
    check("ACTIVE after post-shutdown reactivation", LifecycleController.currentState() == LifecycleState.ACTIVE)
}

// ---- launch recovery (persisted record read) ----

private fun launchRecovery() {
    // Interrupted SHUTTING_DOWN: forced SHUTTING_DOWN + interrupted flag.
    reset()
    val mem1 = MemPersistence().also { it.stored = LifecycleState.SHUTTING_DOWN }
    LifecycleController.init(mem1)
    val d1 = LifecycleController.onAppLaunch()
    check("persisted SHUTTING_DOWN → InterruptedShutdown", d1 is LaunchDecision.InterruptedShutdown)
    check("state forced SHUTTING_DOWN", LifecycleController.currentState() == LifecycleState.SHUTTING_DOWN)
    check("interrupted flag set", LifecycleController.wasShutdownInterrupted())

    // Interrupted REACTIVATING → safe fallback INACTIVE.
    reset()
    val mem2 = MemPersistence().also { it.stored = LifecycleState.REACTIVATING }
    LifecycleController.init(mem2)
    val d2 = LifecycleController.onAppLaunch()
    check("persisted REACTIVATING → RecoveredToInactive", d2 is LaunchDecision.RecoveredToInactive)
    check("fallback state INACTIVE", LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE)
    check("fallback persisted", mem2.stored == LifecycleState.ASSISTANT_MODE_INACTIVE)

    // Persisted INACTIVE / SHUT_DOWN → deliberate reactivation path.
    reset()
    val mem3 = MemPersistence().also { it.stored = LifecycleState.ASSISTANT_MODE_INACTIVE }
    LifecycleController.init(mem3)
    check("persisted INACTIVE → ReactivationRequired", LifecycleController.onAppLaunch() is LaunchDecision.ReactivationRequired)
    check("state INACTIVE", LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE)

    reset()
    val mem4 = MemPersistence().also { it.stored = LifecycleState.SHUT_DOWN }
    LifecycleController.init(mem4)
    check("persisted SHUT_DOWN → ReactivationRequired", LifecycleController.onAppLaunch() is LaunchDecision.ReactivationRequired)
    check("state SHUT_DOWN", LifecycleController.currentState() == LifecycleState.SHUT_DOWN)

    // No record → fresh start, ACTIVE.
    reset()
    LifecycleController.init(MemPersistence())
    check("no record → FreshStart", LifecycleController.onAppLaunch() is LaunchDecision.FreshStart)
    check("fresh state ACTIVE", LifecycleController.currentState() == LifecycleState.ACTIVE)

    // Corrupt record → treated as absent.
    reset()
    val mem5 = MemPersistence() // stored=null simulates unreadable
    LifecycleController.init(mem5)
    check("unreadable record → FreshStart", LifecycleController.onAppLaunch() is LaunchDecision.FreshStart)
}

// ---- persistence, bus, audit ----

private fun persistenceAndBus() {
    reset()
    val mem = MemPersistence()
    LifecycleController.init(mem)
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    check("transition persisted atomically", mem.writes == listOf(LifecycleState.ASSISTANT_MODE_INACTIVE))
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    check(
        "every verified transition persisted",
        mem.writes == listOf(LifecycleState.ASSISTANT_MODE_INACTIVE, LifecycleState.SHUTTING_DOWN),
    )

    val changed = lastChanged()
    check(
        "bus carries from/to/reason",
        changed?.from == LifecycleState.ASSISTANT_MODE_INACTIVE &&
            changed?.to == LifecycleState.SHUTTING_DOWN &&
            changed?.reason == "shutdown",
    )

    val before = busEvents.size
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "again") // no-op
    check("no-op same-state posts no bus event", busEvents.size == before)
}

// ---- Device-test failures (2026-11-11, D-10): shutdown must not
// kill voice on reactivation. The controller half: a persisted
// SHUT_DOWN routes to reactivation, and reactivation with an IDLE
// session mirror + a granted mic restores ACTIVE — the state the
// voice session's start gate (isDispatchAllowed) needs. The
// MainActivity half (route on resume, finish on SHUT_DOWN, overlay
// restart) is Android-only and is covered by device test D-10, not
// by this JVM run.
private fun shutdownReactivationRestoresVoice() {
    // Persisted SHUT_DOWN → reactivation required, dispatch refused.
    reset()
    val persistence = MemPersistence()
    persistence.stored = LifecycleState.SHUT_DOWN
    LifecycleController.init(persistence)
    check(
        "d10 persisted SHUT_DOWN routes to reactivation",
        LifecycleController.onAppLaunch() is LaunchDecision.ReactivationRequired,
    )
    check("d10 dispatch refused while SHUT_DOWN", !LifecycleController.isDispatchAllowed())
    check("d10 state is SHUT_DOWN", LifecycleController.currentState() == LifecycleState.SHUT_DOWN)

    // IDLE mirror + granted mic → ACTIVE, dispatch allowed.
    LifecycleController.reportSessionState("IDLE")
    val ok = LifecycleController.requestReactivation(listOf(passCheck("mic permission")))
    check("d10 reactivation succeeds", ok is ReactivationResult.Success)
    check("d10 state is ACTIVE after reactivation", LifecycleController.currentState() == LifecycleState.ACTIVE)
    check("d10 dispatch allowed after reactivation", LifecycleController.isDispatchAllowed())

    // A failed verification check refuses ACTIVE and falls back to
    // INACTIVE — never a half-active state.
    reset()
    val persistence2 = MemPersistence()
    persistence2.stored = LifecycleState.SHUT_DOWN
    LifecycleController.init(persistence2)
    LifecycleController.onAppLaunch()
    LifecycleController.reportSessionState("IDLE")
    val refused = LifecycleController.requestReactivation(listOf(failCheck("mic permission", "mic permission not granted")))
    check("d10 failed check refuses ACTIVE", refused is ReactivationResult.Failed)
    check(
        "d10 failed reactivation falls back to INACTIVE, never half-active",
        LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE,
    )
    check("d10 dispatch refused after failed reactivation", !LifecycleController.isDispatchAllowed())
}

private fun auditAndReads() {
    reset()
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE, "deactivate")
    LifecycleController.transitionTo(LifecycleState.ACTIVE, "nope") // refused
    val kinds = auditKinds()
    check("transitions audited", kinds.contains("TRANSITION"))
    check("refusals audited", kinds.contains("TRANSITION_REFUSED"))
    val seqs = LifecycleController.auditLog().map { it.seq }
    check("audit seq increasing", seqs == seqs.sorted() && seqs.toSet().size == seqs.size)
    check("audit entries carry detail", LifecycleController.auditLog().all { it.detail.isNotBlank() })

    LifecycleController.noteModeVerified("ORB")
    check("mode note is read-only (no state change)", LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE)
    check("last verified mode readable", LifecycleController.lastVerifiedMode() == "ORB")
}
