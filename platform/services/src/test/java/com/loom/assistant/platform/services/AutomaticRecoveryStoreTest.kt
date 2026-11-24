package com.loom.assistant.platform.services

import com.loom.assistant.lifecycle.IntentionalStop
import com.loom.assistant.lifecycle.IntentionalStopCommand
import testutil.FakeContext

/**
 * Automatic recovery (Nov 6) — JVM checks for the REAL
 * [AutomaticRecoveryStore] against the in-memory harness prefs.
 *
 * Plain-main() harness (PASS = exit 0 + "ALL N TESTS PASSED" line).
 * Run: test-harness/run-recovery-tests.sh (store runner).
 *
 * This is the half the pure-decision test cannot cover: the Android
 * store is the code that makes condition 4 real — the opt-in setting
 * defaults OFF, the marker survives as command + time, a corrupt
 * marker never reads as a stop, and the failure count / report
 * round-trip through the same prefs the runner and Home read.
 *
 * Raw-key cases below use the store's own key strings
 * ("intentional_stop_command" / "intentional_stop_at_ms") to plant a
 * corrupt marker the store API could never write.
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

fun main() {
    val ctx = FakeContext()
    val store = AutomaticRecoveryStore.get(ctx)

    // ---- condition 4: default OFF, fresh store ----
    check("fresh store: auto-recover defaults OFF", !store.isAutoRecoverEnabled())
    check("fresh store: failure count defaults 0", store.failureCount() == 0)
    check("fresh store: no intentional-stop marker", store.readIntentionalStop() == null)
    check("fresh store: no last report", store.lastReport() == null)
    check("fresh store: no last report kind", store.lastReportKind() == null)
    check("fresh store: last report time is 0", store.lastReportAtMs() == 0L)

    // ---- setting round-trip ----
    store.setAutoRecoverEnabled(true)
    check("setting turns on", store.isAutoRecoverEnabled())
    store.setAutoRecoverEnabled(false)
    check("setting turns off again", !store.isAutoRecoverEnabled())

    // ---- marker round-trip, all three commands ----
    for (command in IntentionalStopCommand.values()) {
        val commitsBefore = ctx.prefs.commitCalls
        store.writeIntentionalStop(IntentionalStop(command, 1_700_000_000_000L))
        val read = store.readIntentionalStop()
        check("marker $command reads back", read?.command == command)
        check("marker $command keeps its time", read?.atMs == 1_700_000_000_000L)
        // W2 fix (Nov 6): the marker write must commit synchronously
        // — the ACTION_STOP path stops the process right after, so an
        // async apply() could lose the marker.
        check("marker $command write commits synchronously", ctx.prefs.commitCalls == commitsBefore + 1)
    }
    val clearCommitsBefore = ctx.prefs.commitCalls
    store.clearIntentionalStop()
    check("marker clears", store.readIntentionalStop() == null)
    check("marker clear commits synchronously", ctx.prefs.commitCalls == clearCommitsBefore + 1)

    // ---- corrupt marker never reads as a stop ----
    ctx.prefs.map["intentional_stop_command"] = "BOGUS"
    ctx.prefs.map["intentional_stop_at_ms"] = 1_700_000_000_000L
    check("unknown command name reads as no marker", store.readIntentionalStop() == null)
    ctx.prefs.map["intentional_stop_command"] = "SHUTDOWN"
    ctx.prefs.map["intentional_stop_at_ms"] = 0L
    check("marker with no time reads as no marker", store.readIntentionalStop() == null)
    store.clearIntentionalStop()

    // ---- failure count round-trip + floor ----
    store.setFailureCount(2)
    check("failure count stores 2", store.failureCount() == 2)
    store.setFailureCount(-5)
    check("negative failure count clamps to 0", store.failureCount() == 0)
    store.setFailureCount(3)
    check("failure count stores the cap 3", store.failureCount() == 3)
    store.setFailureCount(0)

    // ---- report round-trip + clear ----
    store.setLastReport("LOOM came back by itself.", "success")
    check("report reads back", store.lastReport() == "LOOM came back by itself.")
    check("report kind reads back", store.lastReportKind() == "success")
    check("report time is stamped", store.lastReportAtMs() > 0L)
    store.setLastReport(null, null)
    check("report clears", store.lastReport() == null)
    check("report kind clears", store.lastReportKind() == null)
    check("report time clears to 0", store.lastReportAtMs() == 0L)

    println("AutomaticRecoveryStoreTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        kotlin.system.exitProcess(1)
    }
}
