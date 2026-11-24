package com.loom.assistant.platform.services

/**
 * JVM tests for ShutdownOrchestrator + TeardownEngine (§4.3, §4.3.1, §7-A).
 *
 * Proves: per-step failure reporting (no swallowed exceptions), exactly one
 * bounded retry per failed step, named failed components, and that ANY failed
 * check yields report.passed == false — the driver must never declare
 * SHUT_DOWN from such a report. Includes the §7-A failure-injection cases
 * (mic refuses release, service won't stop, view won't detach) at the engine
 * level; the service wiring maps them to the real probes.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name $detail")
    }
}

private fun step(
    name: String,
    run: () -> Unit = {},
    verify: () -> Boolean = { true },
    destructive: Boolean = false,
    expected: String = "done",
    describeActual: () -> String = { "" },
) = TeardownStep(name, destructive, expected, run, verify, describeActual)

fun main() {
    // 1. All steps pass → report passed, every component named OK.
    run {
        var runs = 0
        val report = ShutdownOrchestrator.execute(
            listOf(
                step("halt live sequences", run = { runs++ }),
                step("AuthGate purgeAll", run = { runs++ }),
            ),
        )
        check("all-pass: report.passed", report.passed)
        check("all-pass: 2 results", report.results.size == 2, "got ${report.results.size}")
        check("all-pass: no failures", report.failed.isEmpty())
        check("all-pass: steps ran once each", runs == 2, "runs=$runs")
        check(
            "all-pass: components named",
            report.results.map { it.component } == listOf("halt live sequences", "AuthGate purgeAll"),
        )
    }

    // 2. Verify fails → exactly one retry, then a named failed result.
    run {
        var runs = 0
        var verifies = 0
        val report = ShutdownOrchestrator.execute(
            listOf(step("remove floating views", run = { runs++ }, verify = { verifies++; false })),
        )
        check("fail: report not passed", !report.passed)
        check("fail: one retry only (2 attempts)", runs == 2 && verifies == 2, "runs=$runs verifies=$verifies")
        check("fail: exactly one failed result", report.failed.size == 1)
        check(
            "fail: component named",
            report.failed.single().component == "remove floating views",
            report.failed.single().component,
        )
        check(
            "fail: actual describes failure",
            report.failed.single().actual.contains("verification failed"),
            report.failed.single().actual,
        )
    }

    // 3. Retry succeeds → passed (recoverable path works).
    run {
        var verifies = 0
        val report = ShutdownOrchestrator.execute(
            listOf(step("stop overlay service", verify = { verifies++; verifies >= 2 })),
        )
        check("retry-success: report.passed", report.passed)
        check("retry-success: 2 attempts", verifies == 2, "verifies=$verifies")
    }

    // 4. run() throws → captured, never propagates; one retry; named.
    run {
        var runs = 0
        val report = ShutdownOrchestrator.execute(
            listOf(step("stop microphone capture", run = {
                runs++
                throw IllegalStateException("recognizer stuck")
            })),
        )
        check("throw: report not passed", !report.passed)
        check("throw: retried once", runs == 2, "runs=$runs")
        val f = report.failed.single()
        check("throw: component named", f.component == "stop microphone capture")
        check(
            "throw: exception captured in actual",
            f.actual.contains("IllegalStateException") && f.actual.contains("recognizer stuck"),
            f.actual,
        )
    }

    // 5. verify() throws → captured, not propagated.
    run {
        val report = ShutdownOrchestrator.execute(
            listOf(step("verify: TTS idle", verify = { throw RuntimeException("probe blew up") })),
        )
        check("verify-throw: report not passed", !report.passed)
        check(
            "verify-throw: captured",
            report.failed.single().actual.contains("verify threw"),
            report.failed.single().actual,
        )
    }

    // 6. Failure injection: mic refuses to release → honest degraded report,
    //    NEVER SHUT_DOWN.
    run {
        val report = ShutdownOrchestrator.execute(
            listOf(
                step("halt live sequences"),
                step(
                    "stop microphone capture",
                    run = { /* recognizer refuses */ },
                    verify = { false },
                    expected = "recognizer destroyed, every session IDLE",
                    describeActual = { "recognizer still alive" },
                ),
                step("stop overlay service"),
            ),
        )
        check("inject-mic: report not passed", !report.passed)
        val mic = report.failed.single()
        check("inject-mic: names the microphone step", mic.component == "stop microphone capture", mic.component)
        check(
            "inject-mic: actual names remaining state",
            mic.actual.contains("recognizer still alive"),
            mic.actual,
        )
        check("inject-mic: other steps still ran", report.results.size == 3)
        // The driver contract: SHUT_DOWN is declared ONLY from a passing report.
        val driverWouldDeclareShutdown = report.passed
        check("inject-mic: driver must NOT declare SHUT_DOWN", !driverWouldDeclareShutdown)
    }

    // 7. Failure injection: service won't stop → named, one retry, no SHUT_DOWN.
    run {
        var stopRequests = 0
        val report = ShutdownOrchestrator.execute(
            listOf(
                step(
                    "stop overlay service",
                    run = { stopRequests++ },
                    verify = { false }, // independent probe: still bound
                    expected = "service not bound (probe), onDestroy marker seen",
                    describeActual = { "service still bound after stop request" },
                ),
            ),
        )
        check("inject-service: not passed", !report.passed)
        check("inject-service: stop re-issued once", stopRequests == 2, "stopRequests=$stopRequests")
        check(
            "inject-service: named",
            report.failed.single().actual.contains("still bound"),
            report.failed.single().actual,
        )
    }

    // 8. Failure injection: view won't detach → named component.
    run {
        val report = ShutdownOrchestrator.execute(
            listOf(
                step(
                    "remove floating views",
                    verify = { false },
                    expected = "character/orb/edge views detached from WindowManager",
                    describeActual = { "orb view still attached" },
                ),
            ),
        )
        check("inject-view: not passed", !report.passed)
        check(
            "inject-view: names the view",
            report.failed.single().actual.contains("orb view still attached"),
            report.failed.single().actual,
        )
    }

    // 9. Destructive flags are carried into the report path (recovery rule:
    //    user-initiated SHUTTING_DOWN → ACTIVE only before destructive steps).
    run {
        val results = TeardownEngine.execute(
            listOf(
                step("halt live sequences", destructive = false),
                step("AuthGate purgeAll", destructive = true),
            ),
        )
        check("destructive: halt not destructive", !results[0].destructive)
        check("destructive: purge destructive", results[1].destructive)
    }

    // 10. The engine never throws, even for catastrophic steps.
    run {
        var threw = false
        try {
            ShutdownOrchestrator.execute(
                listOf(step("catastrophic", run = { throw OutOfMemoryError("boom") })),
            )
        } catch (_: Throwable) {
            threw = true
        }
        // OutOfMemoryError is an Error, not an Exception — documents the boundary:
        // Exception-subtypes are captured; Errors propagate (honest, not hidden).
        check("engine: Error propagates (documented boundary)", threw)
        val report = ShutdownOrchestrator.execute(
            listOf(step("plain-exception", run = { throw IllegalArgumentException("x") })),
        )
        check("engine: Exception captured, not thrown", !report.passed)
    }

    println("shutdown-orchestrator: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILURES: $failed")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
