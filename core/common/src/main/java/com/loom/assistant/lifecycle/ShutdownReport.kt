package com.loom.assistant.lifecycle

/**
 * §4.3 step 11 / §4.3.1 — per-check verification results for the complete
 * shutdown, aggregated into a [ShutdownReport].
 *
 * Contract (binding):
 * - Every verification check produces a named [ShutdownCheckResult]; failures
 *   are never swallowed — the failed component is named with its actual
 *   observed state.
 * - `ShutdownReport.passed == true` (every check passed) is the ONLY basis
 *   for declaring SHUT_DOWN. Any single failed check → the recoverable-
 *   shutdown path (§4.3.1): the controller stays out of SHUT_DOWN, the
 *   report names what stopped and what remains, one bounded automatic retry
 *   per failed step, then user-facing recovery options. A test asserting
 *   SHUT_DOWN after an injected failure fails the stage.
 */
data class ShutdownCheckResult(
    /** Component name, e.g. "overlay service: stop". */
    val component: String,
    /** What the check required, e.g. "service not bound (probe)". */
    val expected: String,
    /** What was actually observed, e.g. "service still bound after stop". */
    val actual: String,
    val passed: Boolean,
)

class ShutdownReport(val results: List<ShutdownCheckResult>) {
    /** The ONLY basis for declaring SHUT_DOWN: every check passed. */
    val passed: Boolean get() = results.all { it.passed }

    val failed: List<ShutdownCheckResult> get() = results.filter { !it.passed }

    /** Plain-language degraded-state report (§4.3.1 rule 3): what stopped,
     * what is still running. */
    fun degradedSummary(): String = buildString {
        val ok = results.filter { it.passed }
        val bad = failed
        appendLine("Shutdown did not complete: ${bad.size} check(s) failed, ${ok.size} passed.")
        if (bad.isNotEmpty()) {
            appendLine("Still running / unconfirmed:")
            for (r in bad) appendLine("- ${r.component}: ${r.actual}")
        }
        if (ok.isNotEmpty()) {
            appendLine("Stopped as expected:")
            for (r in ok) appendLine("- ${r.component}")
        }
    }

    fun summary(): String = buildString {
        appendLine("ShutdownReport: ${results.count { it.passed }}/${results.size} checks passed")
        for (r in results) {
            appendLine("- ${r.component}: ${if (r.passed) "OK" else "FAILED — actual: ${r.actual}"}")
        }
    }
}
