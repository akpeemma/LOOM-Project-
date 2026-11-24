package com.loom.assistant.intelligence.authgate

import com.loom.assistant.platform.storage.ActivityLog
import testutil.FakeContext

/**
 * Stage 2C (F, plan §4.1.3) — gate-level DENY hardening.
 *
 * `request()` on `automation.sensitive_app` or `tap.financial_transaction`
 * must refuse IMMEDIATELY with no confirmation surface and an audit entry —
 * even when the caller bypasses the queue and forges an AuthAction with a
 * milder declared riskClass (the constructor is public, so the gate enforces
 * by KEY, not by the caller's declaration).
 *
 * Refusal without surface: the confirmation host is bound and functional
 * (a normal CONFIRM action still parks), but the DENY keys never reach it.
 *
 * Run (from loom-android, per §6.1 — compile WITHOUT android.jar):
 *   kotlinc -d out -jvm-target 17 <android fakes> <testutil fakes> \
 *     <platform/storage: LoomPreferences, ActivityLog, LoomJson> \
 *     AuthGate.kt AuthActionRegistry.kt AuthConfirmationHost.kt \
 *     AuthGateDenyTest.kt
 *   java -cp out com.loom.assistant.intelligence.authgate.AuthGateDenyTestKt
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

/** A forged action: a DENY key wearing a milder declared risk class. */
private fun forged(key: String, risk: AuthGate.RiskClass) = AuthGate.AuthAction(
    key = key,
    title = "forged $key",
    detail = "bypass attempt",
    riskClass = risk,
)

private class RecordingHost : AuthConfirmationHost {
    var shows = 0
    override fun showConfirmation(pending: AuthGate.PendingConfirmation): Boolean {
        shows++
        return true
    }

    override fun hideConfirmation() {}
}

fun main() {
    val ctx = FakeContext()
    val gate = AuthGate.init(ctx)
    val host = RecordingHost()
    gate.confirmationHost = host

    // Sanity: the gate + host are functional — a normal CONFIRM parks.
    val normal = AuthGate.AuthAction(
        key = "tap.device",
        title = "tap",
        detail = "normal confirm",
        riskClass = AuthGate.RiskClass.CONFIRM,
    )
    val parked = gate.request(normal) {}
    check("sanity: normal CONFIRM parks with host bound", parked is AuthGate.GateResult.NeedsConfirmation)
    check("sanity: normal CONFIRM reached the surface", host.shows == 1)
    // Clean up the parked confirmation so later counts are exact.
    if (parked is AuthGate.GateResult.NeedsConfirmation) gate.cancelPending(parked.pendingId)

    // Sanity: a normal ALLOW still allows (the gate is not vacuously denying).
    val allowAction = AuthGate.AuthAction(
        key = "screen.snapshot",
        title = "read",
        detail = "pure read",
        riskClass = AuthGate.RiskClass.ALLOW,
        isPureRead = true,
    )
    check("sanity: pure-read ALLOW still allows", gate.check(allowAction) is AuthGate.GateResult.Allow)

    val denyKeys = listOf(
        AuthActionRegistry.ACTION_SENSITIVE_APP,
        AuthActionRegistry.ACTION_TAP_FINANCIAL,
    )
    val forgedRisks = listOf(AuthGate.RiskClass.ALLOW, AuthGate.RiskClass.CONFIRM)

    for (key in denyKeys) {
        for (risk in forgedRisks) {
            // request(): refused immediately, no surface.
            val before = host.shows
            val result = gate.request(forged(key, risk)) { token ->
                check("request($key as $risk): continuation gets no token", token == null)
            }
            check(
                "request($key as $risk): refused",
                result is AuthGate.GateResult.Deny,
            )
            check(
                "request($key as $risk): no confirmation surface",
                host.shows == before,
            )
            // check(): same refusal, pure decision path.
            val decided = gate.check(forged(key, risk))
            check("check($key as $risk): refused", decided is AuthGate.GateResult.Deny)
            check(
                "check($key as $risk): no confirmation surface",
                host.shows == before,
            )
        }
        // The genuine registry DENY actions refuse too.
        val genuine = if (key == AuthActionRegistry.ACTION_SENSITIVE_APP) {
            AuthActionRegistry.SENSITIVE_APP_AUTOMATION
        } else {
            AuthActionRegistry.FINANCIAL_TRANSACTION
        }
        check(
            "request(genuine $key): refused",
            gate.request(genuine) {} is AuthGate.GateResult.Deny,
        )
    }

    // Audit: every refusal left a DENY entry naming the key.
    val audit = ActivityLog.get(ctx).listByKind("auth", 100).map { it.text }
    for (key in denyKeys) {
        check(
            "audit: DENY entry names $key",
            audit.any { it.contains("DENY") && it.contains(key) },
        )
    }

    println("AuthGateDenyTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
