package com.loom.assistant.platform.services

/**
 * Shared step engine for teardown sequences (complete shutdown §4.3 and
 * deactivation §4.5).
 *
 * Every step has a `run` (perform the action) and a `verify` (independently
 * confirm the post-state). Failures are NEVER swallowed: an exception in
 * `run` or `verify`, or a `verify` returning false, is captured into the
 * result with the component named. Each failed step gets exactly one bounded
 * automatic retry ([RETRY_MAX] = 1, §4.3.1); retries are audit-logged.
 *
 * The engine itself never throws: a catastrophic step still yields a failed
 * [StepAttemptResult]. Callers decide what a failed step means (for shutdown:
 * no SHUT_DOWN, §4.3.1 degraded path).
 *
 * Android-free on purpose: the same engine is unit-tested on the JVM and
 * driven with Android-bound lambdas on device.
 */
data class TeardownStep(
    /** Component name used in reports, e.g. "overlay service: stop". */
    val name: String,
    /** §4.1 recovery rule: steps 5–10 of shutdown are destructive. */
    val destructive: Boolean,
    /** What the step was supposed to achieve (for honest reports). */
    val expected: String,
    val run: () -> Unit,
    val verify: () -> Boolean,
    /** Human-readable actual state for failure reports; "" when unknown. */
    val describeActual: () -> String = { "" },
)

data class StepAttemptResult(
    val name: String,
    val destructive: Boolean,
    val expected: String,
    val passed: Boolean,
    val attempts: Int,
    val actual: String,
)

object TeardownEngine {
    /** §4.3.1: one automatic retry per failed step, bounded and logged. */
    const val RETRY_MAX = 1

    fun execute(
        steps: List<TeardownStep>,
        audit: (String) -> Unit = {},
    ): List<StepAttemptResult> = steps.map { runWithRetry(it, audit) }

    private fun runWithRetry(step: TeardownStep, audit: (String) -> Unit): StepAttemptResult {
        var attempts = 0
        var lastError: String? = null
        repeat(1 + RETRY_MAX) { attempt ->
            attempts = attempt + 1
            lastError = null
            try {
                step.run()
            } catch (e: Exception) {
                lastError = "run threw ${e.javaClass.simpleName}: ${e.message}"
                audit("teardown step '${step.name}' attempt $attempts: $lastError")
                if (attempt < RETRY_MAX) {
                    audit("teardown step '${step.name}': retrying once")
                }
                return@repeat
            }
            val ok = try {
                step.verify()
            } catch (e: Exception) {
                lastError = "verify threw ${e.javaClass.simpleName}: ${e.message}"
                audit("teardown step '${step.name}' attempt $attempts: $lastError")
                false
            }
            if (ok) {
                if (attempts > 1) {
                    audit("teardown step '${step.name}' passed on retry (attempt $attempts)")
                }
                return StepAttemptResult(
                    name = step.name,
                    destructive = step.destructive,
                    expected = step.expected,
                    passed = true,
                    attempts = attempts,
                    actual = step.describeActual(),
                )
            }
            if (lastError == null) lastError = "verification failed"
            audit("teardown step '${step.name}' attempt $attempts: $lastError")
            if (attempt < RETRY_MAX) {
                audit("teardown step '${step.name}': retrying once")
            }
        }
        val described = step.describeActual()
        val actual = buildString {
            append(lastError ?: "failed")
            if (described.isNotBlank()) append("; observed: $described")
        }
        audit("teardown step '${step.name}' FAILED after $attempts attempts: $actual")
        return StepAttemptResult(
            name = step.name,
            destructive = step.destructive,
            expected = step.expected,
            passed = false,
            attempts = attempts,
            actual = actual,
        )
    }
}
