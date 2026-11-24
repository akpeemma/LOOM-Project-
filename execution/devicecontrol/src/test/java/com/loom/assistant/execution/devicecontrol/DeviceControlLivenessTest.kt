package com.loom.assistant.execution.devicecontrol

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import com.loom.assistant.core.common.CapabilityId
import com.loom.assistant.core.common.CapabilityLivenessChecker
import com.loom.assistant.core.common.CapabilityState
import com.loom.assistant.core.common.HelperCause
import com.loom.assistant.core.common.HelperDownGate
import com.loom.assistant.core.common.HelperDownRecorder
import com.loom.assistant.core.common.HelperStartupRecord
import com.loom.assistant.core.common.LivenessEnv
import com.loom.assistant.core.common.repairLadder
import com.loom.assistant.core.common.runStartupSection

/**
 * MASTER Wave 4 (F10) — per-capability liveness + repair ladder +
 * startup-section guards + helper-down recording.
 *
 * Proves, without a phone:
 *  - Device Control reports open / type / tap / scroll EACH verified
 *    by its own probe — never inferred from one `isBound` boolean —
 *    with the cause and the repair step when one is dead;
 *  - the repair ladder goes beyond the toggle (which the video
 *    proves does not revive a dead helper);
 *  - a failure in one `onCreate` section is recorded and does not
 *    kill the helper (per-section guards);
 *  - helper-down events are recorded with battery state.
 *
 * Plain-main() pattern; see test-harness/run-2c-tests.sh `t10live`.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private class TestEnv(
    override val isAccessibilityEnabled: Boolean = true,
    override val isServiceBound: Boolean = true,
    override val authGateReady: Boolean = true,
    override val snapshotReadable: Boolean = true,
    override val batteryIgnoringOptimizations: Boolean? = true,
    override val autoRevokeWhitelisted: Boolean? = true,
) : LivenessEnv

private class BatteryContext(
    private val ignoring: Boolean,
) : Context() {
    private val pm = object : PowerManager() {
        override var ignoringOptimizations: Boolean = ignoring
    }

    override fun getSystemService(name: String): Any? =
        if (name == Context.POWER_SERVICE) pm else null
}

private class RevokePackageManager(
    private val whitelisted: Boolean,
) : PackageManager() {
    @Suppress("unused")
    fun isAutoRevokeWhitelisted(): Boolean = whitelisted
}

private class RevokeContext(
    private val whitelisted: Boolean,
) : Context() {
    override val packageManager: PackageManager
        get() = RevokePackageManager(whitelisted)
}

fun main() {
    // ---- per-capability liveness: all live ----
    run {
        val states = CapabilityLivenessChecker.evaluate(TestEnv())
        check("all four live when everything is up", CapabilityLivenessChecker.allLive(states))
        checkEq("helper-down gate is silent when all live", HelperDownGate.decide(states), null)
    }

    // ---- service off: everything dead, cause SERVICE_OFF ----
    run {
        val states = CapabilityLivenessChecker.evaluate(TestEnv(isAccessibilityEnabled = false))
        check("service off → nothing live", !CapabilityLivenessChecker.allLive(states))
        for (id in CapabilityId.entries) {
            val state = states[id] as? CapabilityState.Dead
            checkEq("service off → $id dead with SERVICE_OFF", state?.cause, HelperCause.SERVICE_OFF)
            checkEq("service off → $id repair starts at the toggle", state?.repair?.id, "toggle")
        }
        val refusal = HelperDownGate.decide(states)
        check("helper-down gate refuses when off", refusal != null && refusal.contains("switched off"))
    }

    // ---- on but unbound + battery optimization ON: cause is battery, not generic down ----
    run {
        val states = CapabilityLivenessChecker.evaluate(
            TestEnv(isServiceBound = false, batteryIgnoringOptimizations = false),
        )
        val dead = states[CapabilityId.TAP] as? CapabilityState.Dead
        checkEq("unbound + battery on → cause BATTERY_OPTIMIZATION", dead?.cause, HelperCause.BATTERY_OPTIMIZATION)
        checkEq("battery cause → ladder starts at battery step", dead?.repair?.id, "battery")
        check("battery ladder does not repeat the toggle first", repairLadder(HelperCause.BATTERY_OPTIMIZATION).none { it.id == "toggle" })
    }

    // ---- on but unbound + auto-revoke ON (battery fine): cause is auto-revoke ----
    run {
        val states = CapabilityLivenessChecker.evaluate(
            TestEnv(isServiceBound = false, batteryIgnoringOptimizations = true, autoRevokeWhitelisted = false),
        )
        val dead = states[CapabilityId.SCROLL] as? CapabilityState.Dead
        checkEq("unbound + auto-revoke on → cause AUTO_REVOKE", dead?.cause, HelperCause.AUTO_REVOKE)
        checkEq("auto-revoke cause → ladder starts at auto-revoke step", dead?.repair?.id, "auto-revoke")
    }

    // ---- on but unbound, battery + auto-revoke fine: plain SERVICE_DOWN ----
    run {
        val states = CapabilityLivenessChecker.evaluate(TestEnv(isServiceBound = false))
        val dead = states[CapabilityId.OPEN] as? CapabilityState.Dead
        checkEq("unbound, no named cause → SERVICE_DOWN", dead?.cause, HelperCause.SERVICE_DOWN)
        val ladder = repairLadder(HelperCause.SERVICE_DOWN)
        checkEq("service-down ladder starts at the toggle", ladder.first().id, "toggle")
        check("service-down ladder goes beyond the toggle", ladder.size > 1 && ladder.any { it.id == "rebind" })
        check("ladder ends at reinstall", ladder.last().id == "reinstall")
    }

    // ---- bound but AuthGate not ready: tap/type/scroll dead, open live ----
    run {
        val states = CapabilityLivenessChecker.evaluate(TestEnv(authGateReady = false))
        checkEq("gate not ready → open still live", states[CapabilityId.OPEN], CapabilityState.Live)
        val tap = states[CapabilityId.TAP] as? CapabilityState.Dead
        checkEq("gate not ready → tap dead AUTH_GATE_NOT_READY", tap?.cause, HelperCause.AUTH_GATE_NOT_READY)
        checkEq("gate not ready → type dead", (states[CapabilityId.TYPE] as? CapabilityState.Dead)?.cause, HelperCause.AUTH_GATE_NOT_READY)
        checkEq("gate not ready → scroll dead", (states[CapabilityId.SCROLL] as? CapabilityState.Dead)?.cause, HelperCause.AUTH_GATE_NOT_READY)
        // The gate refuses only for the dead hands.
        checkEq("gate silent for open alone", HelperDownGate.decide(states, setOf(CapabilityId.OPEN)), null)
        check("gate refuses for tap", HelperDownGate.decide(states, setOf(CapabilityId.TAP)) != null)
    }

    // ---- bound + gate ready but screen unreadable: tap/type/scroll dead, open live ----
    run {
        val states = CapabilityLivenessChecker.evaluate(TestEnv(snapshotReadable = false))
        checkEq("screen unreadable → open live", states[CapabilityId.OPEN], CapabilityState.Live)
        checkEq(
            "screen unreadable → tap dead SCREEN_UNREADABLE",
            (states[CapabilityId.TAP] as? CapabilityState.Dead)?.cause,
            HelperCause.SCREEN_UNREADABLE,
        )
    }

    // ---- per-section startup guards ----
    run {
        HelperStartupRecord.clear()
        checkEq("clean section runs", runStartupSection("ok") {}, true)
        checkEq(
            "failing section is recorded, not thrown",
            runStartupSection("search-wiring") { throw IllegalStateException("boom") },
            false,
        )
        checkEq("failing section is named", HelperStartupRecord.failedSections(), listOf("search-wiring"))
        checkEq("clean section still ran after the failure", HelperStartupRecord.failures().size, 1)
        checkEq("second failure appends", runStartupSection("message-wiring") { throw RuntimeException("x") }, false)
        checkEq("both failed sections listed", HelperStartupRecord.failedSections(), listOf("search-wiring", "message-wiring"))
        HelperStartupRecord.clear()
    }

    // ---- helper-down event recording (with battery state) ----
    run {
        HelperDownRecorder.clear()
        HelperDownRecorder.record(HelperCause.BATTERY_OPTIMIZATION, batteryIgnoringOptimizations = false, autoRevokeWhitelisted = true)
        val last = HelperDownRecorder.last()
        checkEq("event cause recorded", last?.cause, HelperCause.BATTERY_OPTIMIZATION)
        checkEq("event battery state recorded", last?.batteryIgnoringOptimizations, false)
        checkEq("event auto-revoke state recorded", last?.autoRevokeWhitelisted, true)
        HelperDownRecorder.clear()
    }

    // ---- DeviceControlCenter wiring: battery + auto-revoke reads ----
    run {
        val batteryCtx = BatteryContext(ignoring = false)
        val center = DeviceControlCenter(batteryCtx)
        checkEq("battery optimization read (ON)", center.batteryIgnoringOptimizations(), false)
        val batteryCtx2 = BatteryContext(ignoring = true)
        checkEq("battery optimization read (off)", DeviceControlCenter(batteryCtx2).batteryIgnoringOptimizations(), true)
        checkEq("auto-revoke read (not whitelisted)", DeviceControlCenter(RevokeContext(false)).autoRevokeWhitelisted(), false)
        checkEq("auto-revoke read (whitelisted)", DeviceControlCenter(RevokeContext(true)).autoRevokeWhitelisted(), true)
        // No battery service at all → unknown, never guessed.
        checkEq("no power service → battery unknown", DeviceControlCenter(Context()).batteryIgnoringOptimizations(), null)
    }

    // ---- DeviceControlCenter wiring: service-off liveness through the real center ----
    run {
        Settings.Secure.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] = ""
        try {
            val center = DeviceControlCenter(Context())
            check("center reports service off", !center.isAccessibilityEnabled())
            val states = center.capabilityLiveness()
            checkEq(
                "center liveness: service off → tap dead SERVICE_OFF",
                (states[CapabilityId.TAP] as? CapabilityState.Dead)?.cause,
                HelperCause.SERVICE_OFF,
            )
            check("center helper-down refusal names the toggle", center.helperDownRefusal()?.contains("turn LOOM off") == true || center.helperDownRefusal()?.contains("switched off") == true)
            checkEq("center repair ladder for the cause", center.repairLadderFor(HelperCause.SERVICE_OFF).first().id, "toggle")
            check("battery exemption intent names the package", center.batteryExemptionIntent().data.toString().contains("com.loom.assistant"))
            check("app-info intent names the package", center.appInfoIntent().data.toString().contains("com.loom.assistant"))
        } finally {
            Settings.Secure.values.remove(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }
    }

    println("DeviceControlLivenessTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        throw AssertionError("$failed test(s) failed")
    }
}
