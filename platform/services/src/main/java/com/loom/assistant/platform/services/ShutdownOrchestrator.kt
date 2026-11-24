package com.loom.assistant.platform.services

import com.loom.assistant.lifecycle.ShutdownCheckResult
import com.loom.assistant.lifecycle.ShutdownReport

/**
 * Complete-shutdown (§4.3) orchestration: runs the ordered teardown steps
 * through [TeardownEngine] and aggregates the per-step outcomes into the
 * canonical [ShutdownReport].
 *
 * Semantics (§4.3.1):
 * - Every check produces a named [ShutdownCheckResult]; the report names each
 *   failed component — no swallowed exceptions, no silent half-states.
 * - One bounded automatic retry per failed step ([TeardownEngine.RETRY_MAX]).
 * - `report.passed == true` is the ONLY basis for declaring SHUT_DOWN. Any
 *   single failed check → the caller must take the recoverable-shutdown path
 *   (stay in SHUTTING_DOWN with the failure record, honest degraded report,
 *   retry options) and must NEVER report SHUT_DOWN.
 * - The orchestrator performs no lifecycle transitions itself; the driver
 *   (application-scoped controller path) applies them from the report.
 */
object ShutdownOrchestrator {

    fun execute(
        steps: List<TeardownStep>,
        audit: (String) -> Unit = {},
    ): ShutdownReport {
        val results = TeardownEngine.execute(steps, audit).map { r ->
            ShutdownCheckResult(
                component = r.name,
                expected = r.expected,
                actual = r.actual,
                passed = r.passed,
            )
        }
        return ShutdownReport(results)
    }
}
