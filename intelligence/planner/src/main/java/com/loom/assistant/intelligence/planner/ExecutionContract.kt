package com.loom.assistant.intelligence.planner

/**
 * AOIE Phase 1 — the typed execution contract (blueprint §4).
 *
 * The Phase 0 audit (§D.2) found the contract between reasoning and
 * device control was stringly-typed: PlanStep.params is
 * Map<String, String>, step identity is positional (re-indexed on
 * every replan), verification returns a Boolean, and execution
 * results are booleans + free-text reasons. These types close those
 * gaps WITHOUT replacing PlanStep (the executor, dispatcher,
 * validator and all tests already speak it): PlanStep now carries a
 * stable [PlanStep.actionId] plus per-step timeout / risk /
 * verification-method fields, and these types are what the executor
 * records per step in the trace.
 *
 * Pure Kotlin, no android.* imports.
 */

/** Risk of one step, decided by the planner — not by the gate. */
enum class RiskLevel {
    /** Read-only or display-only: observe, verify, guide, report. */
    LOW,

    /** Ordinary reversible interaction in a non-sensitive app. */
    ORDINARY,

    /** Sends, publishes, purchases, account changes, deletion,
     *  form submits with real-world consequences. Always needs its
     *  own explicit confirmation at the point of action, never the
     *  plan cover, never a grant. */
    CONSEQUENTIAL,
}

/** How a step's effect is proven — the verification method the
 *  executor must use, instead of the old per-kind if/else guessing. */
enum class VerificationMethod {
    /** Fresh snapshot shows the expected text / element on screen. */
    SCREEN_CONTAINS,

    /** Fresh snapshot differs from the pre-step snapshot. */
    SCREEN_CHANGED,

    /** The named field holds input focus after the step. */
    FIELD_FOCUSED,

    /** The foreground package is the step's package. */
    FOREGROUND_PACKAGE,

    /** The keyboard is gone and results are present. */
    SUBMIT_EXECUTED,

    /**
     * NAV-SCREEN fix (2026-11-11, N4): the navigation destination
     * is reached — the destination tab is selected, or destination
     * content is present, and the package is unchanged. A bare
     * screen change never satisfies this method.
     */
    NAVIGATION_DESTINATION,

    /** No observation can prove this step — the executor must record
     *  UNVERIFIED, never VERIFIED, when this is the method. */
    UNVERIFIABLE,
}

/** What the reasoning layer asks the execution layer to do — one
 *  typed request per action, with a stable id carried from plan →
 *  dispatch → gesture → verification → audit. */
data class ExecutionRequest(
    /** Stable id for this action (PlanStep.actionId). Survives
     *  replan: a re-issued step keeps its id, so the trace can join
     *  attempts of the same action across rebuilds. */
    val actionId: String,
    val kind: TaskStepKind,
    /** Text query identifying the target (never coordinates from the
     *  model — bounds are re-resolved from the live tree). */
    val targetSelector: String = "",
    val inputPayload: String = "",
    /** ALLOW / CONFIRM decision already taken for this action
     *  (cover, grant, or fresh card) — the token that proves it. */
    val policyApproval: String = "",
    /** Deadline for the whole action (dispatch + verify), ms. */
    val deadlineMs: Long = 0L,
)

/** What the execution layer reports back — structured, with the
 *  Android/API error, the target actually acted upon, and the time. */
sealed class ExecutionResult {
    abstract val actionId: String
    abstract val timestampMs: Long

    /** The sink accepted the step for dispatch (NOT success). */
    data class Dispatched(
        override val actionId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        /** What was actually acted upon, where observable
         *  (element id / package / field label). */
        val targetActedUpon: String? = null,
    ) : ExecutionResult()

    /** Dispatch completed AND the expected state was observed. */
    data class Completed(
        override val actionId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val targetActedUpon: String? = null,
    ) : ExecutionResult()

    /** The sink refused or the dispatch failed. */
    data class Failed(
        override val actionId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        /** Structured Android/API error, not prose. */
        val error: String,
        val targetActedUpon: String? = null,
    ) : ExecutionResult()

    /** The gate / policy refused before any dispatch. */
    data class Refused(
        override val actionId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val reason: String,
    ) : ExecutionResult()

    /** The platform cannot perform this action (no capability). */
    data class Unsupported(
        override val actionId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val reason: String,
    ) : ExecutionResult()
}

/** The typed verification verdict (blueprint §4). The executor's
 *  verifyStepTyped returns this; the old Boolean verifyStep is kept
 *  as a thin wrapper for existing callers/tests. */
data class VerificationResult(
    val actionId: String,
    val expected: String,
    /** What was actually observed (screen kind, package, field
     *  state, keyboard state) — never screen content. */
    val observed: String,
    /** Evidence for the verdict: screen-kind + package + the
     *  matching element id where one exists. */
    val evidence: String,
    /** 0.0–1.0. 1.0 = the expected state was directly observed;
     *  0.0 = the expected state is absent; anything between is a
     *  partial / heuristic match and must be reported as UNCERTAIN,
     *  never as a pass. */
    val confidence: Double,
    val verdict: Verdict,
    /** What should happen next on this verdict. */
    val nextStep: String,
) {
    enum class Verdict { PASS, FAIL, UNCERTAIN }

    val passed: Boolean get() = verdict == Verdict.PASS
}

/** One structured, privacy-conscious trace entry (blueprint §14,
 *  ExecutionTraceStore). Recorded per step attempt: action id,
 *  kind, dispatch result, verification verdict + confidence.
 *  Never carries screen content, passwords, OTPs, or sensitive-app
 *  text — the same redaction rule as ScreenDataPolicy. */
data class ExecutionTraceEntry(
    val planId: String,
    val actionId: String,
    val stepIndex: Int,
    val kind: TaskStepKind,
    val dispatch: String,
    val verification: VerificationResult.Verdict,
    val confidence: Double,
    val detail: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** One audit-log line (ActivityLog "plan" kind). */
    fun toAuditLine(): String =
        "plan=$planId step=$stepIndex action=$actionId kind=$kind " +
            "dispatch=$dispatch verify=$verification confidence=$confidence $detail"
}

/** In-memory typed trace for one plan run. The executor appends one
 *  entry per step attempt and flushes each line to its audit sink,
 *  so the trace survives in ActivityLog even though this store is
 *  per-run. */
class ExecutionTraceStore {
    private val entries = mutableListOf<ExecutionTraceEntry>()

    fun record(entry: ExecutionTraceEntry) {
        entries += entry
    }

    fun entries(): List<ExecutionTraceEntry> = entries.toList()

    fun verifiedCount(): Int =
        entries.count { it.verification == VerificationResult.Verdict.PASS }

    /** The distinct action ids that verified at least once. */
    fun verifiedActionIds(): Set<String> =
        entries.filter { it.verification == VerificationResult.Verdict.PASS }
            .map { it.actionId }.toSet()
}

/**
 * AOIE Phase 7 — the sink a verified workflow is offered to. The
 * executor calls it once per COMPLETED plan (all steps verified);
 * the SkillIntegrationAdapter turns the plan into a promotion
 * CANDIDATE (unverified → tested → verified with evidence) and
 * never installs anything without David's approval. Null in
 * tests / callers that do not promote workflows.
 */
fun interface VerifiedWorkflowSink {
    fun onVerifiedWorkflow(plan: TaskPlan)
}
