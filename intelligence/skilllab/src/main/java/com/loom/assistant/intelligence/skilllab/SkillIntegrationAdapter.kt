package com.loom.assistant.intelligence.skilllab

import android.content.Context
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.VerifiedWorkflowSink

/**
 * AOIE Phase 7 — the learner → catalog bridge (blueprint §7).
 *
 * The missing piece the audit found: AppProfileLearner recorded
 * wins and SkillCatalog ran a full lifecycle (register / testing /
 * approve / install), but nothing connected them. This adapter is
 * the connection, and it is deliberately conservative:
 *
 *  observe → candidate (UNVERIFIED) → tested (evidence recorded)
 *  → promoted (VERIFIED) → installed — ONLY with David's approval.
 *
 * A COMPLETED plan (every step verified, offered by the executor)
 * becomes a CANDIDATE, nothing more: a candidate is an unverified
 * observation with evidence attached, never a permanent fact. A
 * candidate is promoted to a catalog skill only through
 * [promote], which requires [approvedByUser] — there is no
 * auto-install path anywhere in this file. [recordEvidence]
 * moves a candidate to TESTED once the same workflow verifies
 * again on a later run.
 *
 * Candidates live in memory for the process; promotion writes the
 * real skill into SkillCatalog (DISCOVERED status — still OFF by
 * default, installed only through the catalog's own approval flow).
 */
enum class CandidateStatus { UNVERIFIED, TESTED, PROMOTED }

data class WorkflowCandidate(
    val id: String,
    val name: String,
    val goal: String,
    val appPackage: String?,
    val stepKinds: List<String>,
    val evidence: List<String>,
    val status: CandidateStatus = CandidateStatus.UNVERIFIED,
    val runsObserved: Int = 1,
)

class SkillIntegrationAdapter private constructor(
    private val catalog: SkillCatalog?,
) : VerifiedWorkflowSink {

    companion object {
        fun get(context: Context): SkillIntegrationAdapter =
            SkillIntegrationAdapter(SkillCatalog.get(context))

        /** Test seam: an adapter with no catalog (candidates only). */
        fun withoutCatalog(): SkillIntegrationAdapter =
            SkillIntegrationAdapter(null)
    }

    private val candidates = LinkedHashMap<String, WorkflowCandidate>()

    /**
     * Called by the executor for a COMPLETED plan. Records (or
     * refreshes) the candidate and attaches the run as evidence.
     * A repeat verification of the same workflow moves it to
     * TESTED. Never installs, never promotes, never writes to the
     * catalog.
     */
    override fun onVerifiedWorkflow(plan: TaskPlan) {
        val key = workflowKey(plan)
        val evidence = "verified run of \"${plan.goal}\" " +
            "(${plan.steps.size} steps, all verified)"
        val existing = candidates[key]
        if (existing == null) {
            candidates[key] = WorkflowCandidate(
                id = key,
                name = candidateName(plan),
                goal = plan.goal,
                appPackage = plan.steps.firstNotNullOfOrNull {
                    it.params[com.loom.assistant.intelligence.planner.PlanParams.PACKAGE]
                },
                stepKinds = plan.steps.map { it.kind.name },
                evidence = listOf(evidence),
            )
        } else {
            candidates[key] = existing.copy(
                evidence = (existing.evidence + evidence).takeLast(5),
                runsObserved = existing.runsObserved + 1,
                status = if (existing.runsObserved + 1 >= 2) {
                    CandidateStatus.TESTED
                } else {
                    existing.status
                },
            )
        }
    }

    /** One candidate, by its key, or null. */
    fun candidate(key: String): WorkflowCandidate? = candidates[key]

    /** All candidates, most-observed first. */
    fun candidates(): List<WorkflowCandidate> =
        candidates.values.sortedByDescending { it.runsObserved }

    /**
     * Promotes a TESTED candidate into the catalog as a DISCOVERED
     * skill (default OFF). Requires David's explicit approval for
     * THIS candidate — [approvedByUser] false refuses outright.
     * An UNVERIFIED candidate can never be promoted: one verified
     * run is an observation, not a reusable skill.
     */
    fun promote(key: String, approvedByUser: Boolean): WorkflowCandidate? {
        val candidate = candidates[key] ?: return null
        if (!approvedByUser) return null
        if (candidate.status != CandidateStatus.TESTED) return null
        val catalog = catalog ?: return null
        catalog.register(
            name = candidate.name,
            version = "1.0.0",
            description = "Verified workflow: ${candidate.goal} " +
                "(${candidate.runsObserved} verified runs). " +
                "Steps: ${candidate.stepKinds.joinToString(", ")}.",
            source = "loom-verified-workflow",
        )
        val promoted = candidate.copy(status = CandidateStatus.PROMOTED)
        candidates[key] = promoted
        return promoted
    }

    private fun workflowKey(plan: TaskPlan): String {
        val kinds = plan.steps.joinToString(",") { it.kind.name }
        val app = plan.steps.firstNotNullOfOrNull {
            it.params[com.loom.assistant.intelligence.planner.PlanParams.PACKAGE]
        }.orEmpty()
        return "workflow|$app|$kinds"
    }

    private fun candidateName(plan: TaskPlan): String {
        val app = plan.record?.app ?: plan.steps.firstNotNullOfOrNull {
            it.params[com.loom.assistant.intelligence.planner.PlanParams.APP]
        } ?: "task"
        return "Workflow: ${plan.goal.take(48)} in $app"
    }
}
