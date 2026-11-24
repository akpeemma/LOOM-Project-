package com.loom.assistant.intelligence.authgate

/**
 * Stage 2B (P1) — token-lifecycle checks for the consumeToken ordering fix.
 *
 * Covers: a tap.device token survives the tap.guidance probe in requestTap's
 * dual-key flow and then dispatches; a wrong key fails closed WITHOUT
 * destroying the token; double-consume is rejected; unknown/null/blank
 * token ids are rejected.
 *
 * Run pattern (same as VoiceCommandsTest/ModeControllerTest): AuthGate.kt
 * carries android.* imports, so a plain kotlinc run needs stub sources for
 * android.content.Context, android.os.Handler/Looper, and the platform
 * storage fakes (ActivityLog, LoomPreferences). Compile the fakes, the three
 * main sources, and this file together:
 *
 *   kotlinc \
 *     <fakes>/android/content/Context.kt \
 *     <fakes>/android/os/Looper.kt <fakes>/android/os/Handler.kt \
 *     <fakes>/com/loom/assistant/platform/storage/StorageFakes.kt \
 *     AuthGate.kt AuthActionRegistry.kt AuthConfirmationHost.kt \
 *     AuthTokenLifecycleTest.kt -d out
 *   java -cp out com.loom.assistant.intelligence.authgate.AuthTokenLifecycleTestKt
 *
 * (The fakes must live outside the repo — android.* stubs in src/test would
 * collide with the real Android Gradle build. A reference set was written
 * alongside this task at /tmp/stage2b-p1-fakes/.)
 *
 * Tokens are issued only through the public API (check() on an ALLOW
 * action), never forged — mirroring how the app would obtain them.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean, expected: Boolean) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected $expected, got $actual")
    }
}

/** Issues a fresh single-use token for [actionKey] via the public check() path. */
private fun issueToken(gate: AuthGate, actionKey: String): String {
    val action = AuthGate.AuthAction(
        key = actionKey,
        title = "test action",
        detail = "token-lifecycle test",
        riskClass = AuthGate.RiskClass.ALLOW,
    )
    val result = gate.check(action)
    check("token issued for $actionKey", result is AuthGate.GateResult.Allow, true)
    return (result as AuthGate.GateResult.Allow).token.id
}

fun main() {
    val gate = AuthGate.init(testutil.FakeContext())
    val tap = AuthActionRegistry.ACTION_TAP // "tap.device"
    val guidance = AuthActionRegistry.ACTION_TAP_GUIDANCE // "tap.guidance"

    // ---- P1 core: tap.device token survives the tap.guidance probe ----
    // Mirrors LoomAccessibilityService.requestTap's dual-key flow exactly:
    // probe tap.guidance first (must fail WITHOUT destroying the token),
    // then tap.device (must succeed and consume it).
    val t1 = issueToken(gate, tap)
    check("tap.device token: tap.guidance probe fails closed", gate.consumeToken(t1, guidance), false)
    check("tap.device token: tap.device then dispatches", gate.consumeToken(t1, tap), true)

    // ---- tap.guidance token still works on the first probe (unchanged) ----
    val t2 = issueToken(gate, guidance)
    check("tap.guidance token: first probe succeeds", gate.consumeToken(t2, guidance), true)

    // ---- wrong key fails closed without destroying the token ----
    val t3 = issueToken(gate, tap)
    check("wrong key rejected", gate.consumeToken(t3, "bogus.key"), false)
    check("token survives wrong-key probe", gate.consumeToken(t3, tap), true)

    // ---- double-consume rejected (single-use preserved) ----
    val t4 = issueToken(gate, tap)
    check("first consume succeeds", gate.consumeToken(t4, tap), true)
    check("second consume rejected", gate.consumeToken(t4, tap), false)

    // ---- unknown / null / blank ids fail closed ----
    check("unknown id rejected", gate.consumeToken("no-such-token", tap), false)
    check("null id rejected", gate.consumeToken(null, tap), false)
    check("blank id rejected", gate.consumeToken("   ", tap), false)

    // ---- Stage 2C: demo.sequence key binding (the P1 regression, generalized) ----
    // A demo.sequence token survives probes for every other tap-family key and
    // dispatches only for demo.sequence.
    val seq = issueToken(gate, AuthActionRegistry.ACTION_DEMO_SEQUENCE)
    check(
        "demo.sequence token: tap.guidance probe fails closed",
        gate.consumeToken(seq, guidance), false,
    )
    check(
        "demo.sequence token: tap.guidance.outofscope probe fails closed",
        gate.consumeToken(seq, AuthActionRegistry.ACTION_TAP_GUIDANCE_OUTOFSCOPE), false,
    )
    check(
        "demo.sequence token: tap.device probe fails closed",
        gate.consumeToken(seq, tap), false,
    )
    check(
        "demo.sequence token: demo.sequence then dispatches",
        gate.consumeToken(seq, AuthActionRegistry.ACTION_DEMO_SEQUENCE), true,
    )

    // ---- Stage 2C: DENY-class keys never issue tokens ----
    check(
        "tap.financial_transaction denied outright",
        gate.check(AuthActionRegistry.FINANCIAL_TRANSACTION) is AuthGate.GateResult.Deny, true,
    )
    check(
        "automation.sensitive_app denied outright",
        gate.check(AuthActionRegistry.SENSITIVE_APP_AUTOMATION) is AuthGate.GateResult.Deny, true,
    )

    // ---- repeated wrong-key probes never destroy the token ----
    val m = issueToken(gate, tap)
    repeat(3) { i ->
        check("wrong-key probe ${i + 1} rejected", gate.consumeToken(m, "bogus.$i"), false)
    }
    check("token survives 3 wrong-key probes", gate.consumeToken(m, tap), true)

    println("AuthTokenLifecycleTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
