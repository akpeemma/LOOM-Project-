package com.loom.assistant.platform.services

/**
 * Deactivation (§4.2-F / §4.5) orchestration: end session → mic off → TTS
 * stopped + queue cleared → halt live sequences (INTERRUPTED) →
 * AuthGate.purgeAll() → drain the action queue → hide floating views →
 * ASSISTANT_MODE_INACTIVE.
 *
 * App, services installation, settings, memory and preferences are preserved:
 * deactivation is not an uninstall, not a data wipe, not a process kill.
 * The persistent notification is kept for status visibility only — no actions,
 * no content intent (closure decision 1); the driver switches the variant
 * after a successful run.
 *
 * Like shutdown, no step failure is swallowed: [DeactivationReport] names
 * every failed component and `reachedInactive` is true only when every step
 * passed. The driver must NOT transition to ASSISTANT_MODE_INACTIVE on a
 * failed report — the lifecycle stays where it verifiably is.
 */
data class DeactivationReport(val steps: List<StepAttemptResult>) {
    val reachedInactive: Boolean get() = steps.all { it.passed }
    val failed: List<StepAttemptResult> get() = steps.filter { !it.passed }

    fun summary(): String = buildString {
        appendLine("DeactivationReport: ${steps.count { it.passed }}/${steps.size} steps passed")
        for (s in steps) {
            appendLine("- ${s.name}: ${if (s.passed) "OK" else "FAILED (${s.actual})"}")
        }
    }
}

object DeactivationOrchestrator {

    fun execute(
        steps: List<TeardownStep>,
        audit: (String) -> Unit = {},
    ): DeactivationReport = DeactivationReport(TeardownEngine.execute(steps, audit))
}
