package com.loom.assistant.execution.interaction

/**
 * Stage 2C (C2/D2) — demonstration action plan model.
 *
 * An [ActionPlan] is an ordered list of tap steps built ONLY by deterministic
 * voice commands ("show me how to <X>" / "tap it for me") — never by the
 * conversational model (constraint F). It is pure data: no Android imports,
 * no dispatch logic, directly JVM-testable (T2).
 *
 * Execution semantics live in [ActionQueue]; this file only describes WHAT
 * was approved, so the sequence card, the audit log, and the queue all read
 * the same plan.
 */
enum class PlanStepKind {
    /** Tap the resolved target (the only kind the tap queue executes). */
    TAP,
    /**
     * POINTER MOUSE-VERBS (2026-11-05): long-press the resolved target
     * — the tap held down for [PlanStep.holdMs]. Executed by the tap
     * queue through the long-press sink (same `tap.guidance` key as a
     * tap — a long-press IS a tap held down, not a new authorization
     * class). No plan builder creates a LONG_PRESS step today (no voice
     * command requests one); the kind exists so a step CAN request it
     * when one does, with the hold wired end to end.
     */
    LONG_PRESS,
    /**
     * Master implementation §4 — type [PlanStep.text] into the resolved
     * input field. Executed by the search sequence (SearchExecutor), not
     * by the tap queue: the queue fails honestly on non-TAP steps.
     */
    TYPE,
    /**
     * Master implementation §4 — submit the focused field (IME Enter /
     * on-screen search button). Executed by the search sequence, not by
     * the tap queue.
     */
    SUBMIT,
}

/**
 * Confirmation class of a step (plan §3.1/§3.2).
 *
 * - [SEQUENCE]: in the demonstrated app, non-sensitive, non-financial. Runs
 *   under the per-sequence `demo.sequence` cover — no extra prompt.
 * - [PER_STEP]: out-of-scope step (target app != demonstrated app, and NOT a
 *   sensitive package). Pauses the queue for its own `tap.guidance.outofscope`
 *   CONFIRM card.
 * - [GUIDANCE_ONLY]: inside a sensitive package (banking/finance/
 *   authenticator). Highlight + instruction only — no token, no card, no
 *   dispatch, ever. No confirmation can change this.
 * - [REFUSED]: matches the financial/security detector. Refused before any
 *   token request, mapped to `tap.financial_transaction` for audit.
 */
enum class StepConfirmClass {
    SEQUENCE,
    PER_STEP,
    GUIDANCE_ONLY,
    REFUSED,
}

data class PlanStep(
    /** Stable step id, unique within the plan. */
    val id: String,
    val kind: PlanStepKind = PlanStepKind.TAP,
    /**
     * Target query resolved against the live accessibility tree at dispatch
     * time (TOCTOU-safe re-resolution in [ActionQueue]). Matched against
     * element text / content-description / class name, case-insensitive.
     */
    val targetQuery: String,
    /** Instruction shown in the tooltip and spoken ("Step N of M: ..."). */
    val instruction: String,
    /**
     * Payload for [PlanStepKind.TYPE]: the text to type into the resolved
     * input field. Must be non-blank for TYPE steps; ignored otherwise.
     */
    val text: String? = null,
    /**
     * Payload for [PlanStepKind.LONG_PRESS]: the hold duration in ms.
     * Null = the session default (700ms). Ignored for other kinds.
     */
    val holdMs: Long? = null,
    /**
     * Package the step's target lives in. Null = the demonstrated app.
     * A non-null package different from [ActionPlan.demoAppPackage] marks the
     * step out-of-scope ([StepConfirmClass.PER_STEP]) — unless it is a
     * sensitive package, which is [StepConfirmClass.GUIDANCE_ONLY] instead
     * (sensitive packages NEVER escalate, plan §4 hard rule).
     */
    val targetPackage: String? = null,
    /** Declared confirmation class at plan-build time. */
    val confirmClass: StepConfirmClass = StepConfirmClass.SEQUENCE,
    /** Human-readable description of the expected UI change (C5 landing). */
    val expectedChange: String? = null,
)

data class ActionPlan(
    /** Unique plan id, also used as the sequence-cover id. */
    val id: String,
    /** Goal text from the voice command, e.g. "open the calculator". */
    val goal: String,
    /** Package the sequence was approved for (`demo.sequence` cover). */
    val demoAppPackage: String,
    val steps: List<PlanStep>,
) {
    init {
        require(steps.isNotEmpty()) { "ActionPlan must contain at least one step." }
        require(steps.map { it.id }.toSet().size == steps.size) {
            "ActionPlan step ids must be unique."
        }
        require(steps.all { it.kind != PlanStepKind.TYPE || !it.text.isNullOrBlank() }) {
            "TYPE steps must carry the text to type."
        }
    }

    /** Reclassifies a step's confirm class against the sensitive-app policy. */
    fun effectiveConfirmClass(step: PlanStep, isSensitiveTarget: Boolean): StepConfirmClass =
        when {
            step.confirmClass == StepConfirmClass.REFUSED -> StepConfirmClass.REFUSED
            isSensitiveTarget -> StepConfirmClass.GUIDANCE_ONLY
            step.targetPackage != null && step.targetPackage != demoAppPackage ->
                StepConfirmClass.PER_STEP
            else -> StepConfirmClass.SEQUENCE
        }
}
