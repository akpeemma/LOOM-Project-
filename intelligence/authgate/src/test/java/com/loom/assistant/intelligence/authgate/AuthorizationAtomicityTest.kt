package com.loom.assistant.intelligence.authgate

import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.platform.storage.ActivityLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import testutil.FakeContext

/**
 * Stage 2C (plan §8.2, §7-A/B) — AuthGate purge + authorization atomicity.
 *
 * Covers:
 *  1. purgeAll() revokes issued tokens (present-then-refused), cancels every
 *     pending confirmation (each continuation gets null, host hidden), and
 *     returns exact counts — all audit-logged.
 *  2. §7-A negative: token issued → lifecycle → SHUTTING_DOWN → dispatch
 *     attempted → REFUSED; the handoff never runs; the token survives
 *     (fail-closed, un-consumed).
 *  3. Post-reactivation stale-token refusal: a pre-deactivation token is
 *     refused after purgeAll + reactivation, while a fresh token works.
 *  4. The two-probe requestTap pattern ([tap.guidance, tap.device]) executes
 *     inside the atomic boundary (map mismatch #6): both probes are covered
 *     by the lifecycle check at consumption and the handoff re-check.
 *  5. Dispatch is refused in every non-ACTIVE lifecycle state
 *     (ASSISTANT_MODE_INACTIVE, SHUTTING_DOWN, SHUT_DOWN, REACTIVATING).
 *  6. Deterministic monitor test: purgeAll() cannot interleave inside an
 *     in-flight consume→handoff critical section.
 *  7. Racing stress: token bookkeeping stays exact (never lost, never
 *     double-consumed) when a lifecycle flip races dispatch.
 *
 * Run (from loom-android, per §6.1 — compile WITHOUT android.jar; the
 * lifecycle stub stands in for the parallel worker's LifecycleController
 * until the real module lands — see /tmp/lifecycle-stub):
 *   kotlinc -d out -jvm-target 17 <android fakes> <testutil fakes> \
 *     <platform/storage: LoomPreferences, ActivityLog, LoomJson> \
 *     /tmp/lifecycle-stub/com/loom/assistant/lifecycle/LifecycleStub.kt \
 *     AuthGate.kt AuthActionRegistry.kt AuthConfirmationHost.kt \
 *     AuthorizationAtomicityTest.kt
 *   java -cp out com.loom.assistant.intelligence.authgate.AuthorizationAtomicityTestKt
 *
 * Tokens are issued only through the public API (check() on an ALLOW
 * action), never forged — mirroring how the app would obtain them.
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

private fun setLifecycle(state: LifecycleState) {
    LifecycleController.forceStateForTest(state)
}

/** Issues a fresh single-use token for [actionKey] via the public check() path. */
private fun issueToken(gate: AuthGate, actionKey: String): String {
    val action = AuthGate.AuthAction(
        key = actionKey,
        title = "test action",
        detail = "authorization-atomicity test",
        riskClass = AuthGate.RiskClass.ALLOW,
    )
    val result = gate.check(action)
    check("token issued for $actionKey", result is AuthGate.GateResult.Allow)
    return (result as AuthGate.GateResult.Allow).token.id
}

private class RecordingHost : AuthConfirmationHost {
    var shows = 0
    var hides = 0
    override fun showConfirmation(pending: AuthGate.PendingConfirmation): Boolean {
        shows++
        return true
    }

    override fun hideConfirmation() {
        hides++
    }
}

private fun confirmAction(key: String) = AuthGate.AuthAction(
    key = key,
    title = "confirm $key",
    detail = "purge test",
    riskClass = AuthGate.RiskClass.CONFIRM,
)

fun main() {
    val ctx = FakeContext()
    val gate = AuthGate.init(ctx)
    val host = RecordingHost()
    gate.confirmationHost = host
    val tap = AuthActionRegistry.ACTION_TAP
    val guidance = AuthActionRegistry.ACTION_TAP_GUIDANCE
    val probeOrder = listOf(guidance, tap) // requestTap's exact probe order

    fun reset() {
        setLifecycle(LifecycleState.ACTIVE)
        gate.purgeAll("test-reset")
    }

    // ============ 1. purgeAll revokes issued tokens (present-then-refused) ============
    reset()
    val pt1 = issueToken(gate, tap)
    val pt2 = issueToken(gate, AuthActionRegistry.ACTION_DEMO_SEQUENCE)
    val pt3 = issueToken(gate, guidance)
    // Sanity: the tokens are live before the purge.
    check("pre-purge: token present", gate.consumeToken(pt1, tap))
    val pt1b = issueToken(gate, tap) // re-issue the consumed one
    val report = gate.purgeAll("deactivate")
    check("purgeAll: report pendingCancelled == 0", report.pendingCancelled == 0)
    check("purgeAll: report tokensRevoked == 3", report.tokensRevoked == 3)
    check("purgeAll: report auditLogged", report.auditLogged)
    for ((id, key) in listOf(pt1b to tap, pt2 to AuthActionRegistry.ACTION_DEMO_SEQUENCE, pt3 to guidance)) {
        check("post-purge: issued token $key refused by consumeToken", !gate.consumeToken(id, key))
        val r = gate.consumeAndDispatch(id, listOf(key)) { "must not run" }
        check("post-purge: issued token $key refused by consumeAndDispatch", r is AuthGate.DispatchResult.Refused)
    }
    val auditTexts = ActivityLog.get(ctx).listByKind("auth", 200).map { it.text }
    check(
        "audit: TOKEN_REVOKED per token",
        auditTexts.count { it.contains("TOKEN_REVOKED") } >= 3,
    )
    check("audit: PURGE_ALL names reason", auditTexts.any { it.contains("PURGE_ALL") && it.contains("deactivate") })

    // ============ 2. pending confirmations cancelled ============
    reset()
    var cb1: AuthGate.AuthToken? = null
    var cb1Calls = 0
    var cb2Calls = 0
    val r1 = gate.request(confirmAction("tap.device")) { cb1Calls++; cb1 = it }
    val r2 = gate.request(confirmAction("screen.snapshot")) { cb2Calls++ }
    check("two confirmations parked", r1 is AuthGate.GateResult.NeedsConfirmation &&
        r2 is AuthGate.GateResult.NeedsConfirmation)
    check("host shown twice", host.shows == 2)
    val hidesBefore = host.hides
    val preCount = gate.pendingCount()
    check("pendingCount == 2 before purge", preCount == 2)
    val report2 = gate.purgeAll("shutdown")
    check("purgeAll: pendingCancelled == 2", report2.pendingCancelled == 2)
    check("purgeAll: tokensRevoked == 0", report2.tokensRevoked == 0)
    check("cancelled continuation 1 invoked with null", cb1Calls == 1 && cb1 == null)
    check("cancelled continuation 2 invoked", cb2Calls == 1)
    check("pendingCount == 0 after purge", gate.pendingCount() == 0)
    check("host hidden after purge", host.hides > hidesBefore)
    // Resolving a purged pending is a no-op (nothing parked): the parked
    // continuation must NOT fire again and no token may materialize.
    if (r1 is AuthGate.GateResult.NeedsConfirmation) {
        gate.resolve(r1.pendingId, accepted = true)
        check("resolve after purge: parked continuation stays dead", cb1Calls == 1)
    }
    check(
        "audit: CONFIRM_CANCELLED per purged pending",
        ActivityLog.get(ctx).listByKind("auth", 200).map { it.text }
            .count { it.contains("CONFIRM_CANCELLED") && it.contains("purgeAll") } >= 2,
    )

    // ============ 3. §7-A negative: SHUTTING_DOWN → dispatch REFUSED ============
    reset()
    val nt = issueToken(gate, tap)
    setLifecycle(LifecycleState.SHUTTING_DOWN)
    var handoffRan = false
    val neg = gate.consumeAndDispatch(nt, listOf(tap)) {
        handoffRan = true
        "dispatched"
    }
    check("§7-A: dispatch refused while SHUTTING_DOWN", neg is AuthGate.DispatchResult.Refused)
    check(
        "§7-A: refusal names the lifecycle state",
        (neg as? AuthGate.DispatchResult.Refused)?.reason?.contains("SHUTTING_DOWN") == true,
    )
    check("§7-A: handoff never ran", !handoffRan)
    // consumeToken path refuses too, without consuming (fail-closed).
    check("§7-A: consumeToken refused while SHUTTING_DOWN", !gate.consumeToken(nt, tap))
    // Fail-closed: the token survives — usable again once ACTIVE is restored.
    setLifecycle(LifecycleState.ACTIVE)
    check("§7-A: token survived the refusal", gate.consumeToken(nt, tap))
    // purgeAll() alone (no flip) also refuses: the deactivation ordering.
    reset()
    val dt = issueToken(gate, tap)
    gate.purgeAll("deactivate")
    check(
        "post-purge dispatch refused even while ACTIVE",
        gate.consumeAndDispatch(dt, listOf(tap)) { "x" } is AuthGate.DispatchResult.Refused,
    )

    // ============ 4. post-reactivation stale-token refusal ============
    reset()
    val stale = issueToken(gate, tap)
    gate.purgeAll("deactivate") // deactivation destroys authorization state
    setLifecycle(LifecycleState.ACTIVE) // ... then the launcher-icon reactivation
    check("stale token refused post-reactivation (consumeToken)", !gate.consumeToken(stale, tap))
    check(
        "stale token refused post-reactivation (consumeAndDispatch)",
        gate.consumeAndDispatch(stale, listOf(tap)) { "x" } is AuthGate.DispatchResult.Refused,
    )
    // The gate is not vacuously dead: a fresh token dispatches post-reactivation.
    val fresh = issueToken(gate, tap)
    val freshRes = gate.consumeAndDispatch(fresh, listOf(tap)) { "fresh-ok" }
    check(
        "fresh token dispatches post-reactivation",
        freshRes is AuthGate.DispatchResult.Dispatched && freshRes.value == "fresh-ok",
    )

    // ============ 5. two-probe requestTap pattern inside the boundary ============
    reset()
    // A tap.guidance token dispatches on the FIRST probe (unchanged behavior).
    val g1 = issueToken(gate, guidance)
    val g1Res = gate.consumeAndDispatch(g1, probeOrder) { "tap!" }
    check(
        "two-probe: tap.guidance token dispatches on first probe",
        g1Res is AuthGate.DispatchResult.Dispatched && g1Res.value == "tap!",
    )
    // A tap.device token survives the tap.guidance probe, dispatches on the second.
    val d1 = issueToken(gate, tap)
    val d1Res = gate.consumeAndDispatch(d1, probeOrder) { "tap!" }
    check(
        "two-probe: tap.device token dispatches on second probe",
        d1Res is AuthGate.DispatchResult.Dispatched && d1Res.value == "tap!",
    )
    // Wrong-key probe inside the boundary fails closed WITHOUT destroying the token.
    val d2 = issueToken(gate, tap)
    val d2Res = gate.consumeAndDispatch(d2, listOf(guidance)) { "tap!" }
    check("two-probe: wrong-key probe refused", d2Res is AuthGate.DispatchResult.Refused)
    check("two-probe: token survived the wrong-key probe", gate.consumeToken(d2, tap))
    // The lifecycle check covers BOTH probes: flip before the call refuses the
    // whole two-probe flow, token survives.
    val d3 = issueToken(gate, tap)
    setLifecycle(LifecycleState.SHUTTING_DOWN)
    var probeHandoffRan = false
    val d3Res = gate.consumeAndDispatch(d3, probeOrder) {
        probeHandoffRan = true
        "tap!"
    }
    check("two-probe: SHUTTING_DOWN refuses the whole probe flow", d3Res is AuthGate.DispatchResult.Refused)
    check("two-probe: handoff never ran", !probeHandoffRan)
    setLifecycle(LifecycleState.ACTIVE)
    check("two-probe: token survived", gate.consumeToken(d3, tap))

    // ============ 6. every non-ACTIVE state refuses ============
    for (state in listOf(
        LifecycleState.ASSISTANT_MODE_INACTIVE,
        LifecycleState.SHUTTING_DOWN,
        LifecycleState.SHUT_DOWN,
        LifecycleState.REACTIVATING,
    )) {
        reset()
        val tok = issueToken(gate, tap)
        setLifecycle(state)
        var ran = false
        val res = gate.consumeAndDispatch(tok, listOf(tap)) {
            ran = true
            "x"
        }
        check("dispatch refused in $state", res is AuthGate.DispatchResult.Refused)
        check("handoff never ran in $state", !ran)
        check("consumeToken refused in $state", !gate.consumeToken(tok, tap))
        // Fail-closed in every state: token survives for a genuine recovery.
        setLifecycle(LifecycleState.ACTIVE)
        check("token survived refusal in $state", gate.consumeToken(tok, tap))
    }

    // ============ 7. deterministic: purgeAll serializes with in-flight dispatch ============
    reset()
    val it_tok = issueToken(gate, tap)
    val handoffStarted = CountDownLatch(1)
    val handoffMayFinish = CountDownLatch(1)
    val handoffEndNanos = AtomicLong(0)
    val purgeEndNanos = AtomicLong(0)
    var dispatchResult: AuthGate.DispatchResult<String>? = null
    val worker = Thread {
        dispatchResult = gate.consumeAndDispatch(it_tok, listOf(tap)) {
            handoffStarted.countDown()
            handoffMayFinish.await(10, TimeUnit.SECONDS)
            handoffEndNanos.set(System.nanoTime())
            "in-flight"
        }
    }
    worker.start()
    check("handoff entered the gate lock", handoffStarted.await(10, TimeUnit.SECONDS))
    val purger = Thread {
        gate.purgeAll("deactivate") // must BLOCK until the in-flight dispatch exits the monitor
        purgeEndNanos.set(System.nanoTime())
    }
    purger.start()
    Thread.sleep(200) // give the purger time to block on the gate monitor
    handoffMayFinish.countDown()
    worker.join(10000)
    purger.join(10000)
    check(
        "purgeAll could not interleave inside the consume→handoff critical section",
        purgeEndNanos.get() > handoffEndNanos.get() && handoffEndNanos.get() > 0,
    )
    check(
        "in-flight dispatch completed as Dispatched (was ACTIVE throughout)",
        dispatchResult is AuthGate.DispatchResult.Dispatched,
    )

    // ============ 8. racing stress: bookkeeping stays exact under a flipping lifecycle ============
    reset()
    var dispatched = 0
    var refused = 0
    var bookkeepingOk = true
    repeat(300) {
        setLifecycle(LifecycleState.ACTIVE)
        val tok = issueToken(gate, tap)
        val flipper = Thread {
            // Spin briefly, then flip mid-dispatch (racy by design).
            var x = 0
            repeat(50) { x += it }
            if (x == -1) println("unreachable")
            setLifecycle(LifecycleState.SHUTTING_DOWN)
        }
        flipper.start()
        val res = gate.consumeAndDispatch(tok, listOf(tap)) { "x" }
        flipper.join(10000)
        setLifecycle(LifecycleState.ACTIVE)
        when (res) {
            is AuthGate.DispatchResult.Dispatched -> {
                dispatched++
                // Consumed exactly once: a second consume must fail.
                if (gate.consumeToken(tok, tap)) bookkeepingOk = false
            }
            is AuthGate.DispatchResult.Refused -> {
                refused++
                // Fail-closed: the token survived — exactly one consume succeeds.
                if (!gate.consumeToken(tok, tap)) bookkeepingOk = false
                else if (gate.consumeToken(tok, tap)) bookkeepingOk = false
            }
        }
    }
    check("stress: 300 racing iterations completed", dispatched + refused == 300)
    check("stress: token bookkeeping exact (never lost, never double-consumed)", bookkeepingOk)
    println("stress outcomes: dispatched=$dispatched refused=$refused")

    println("AuthorizationAtomicityTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
