package com.loom.assistant.platform.services

import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.CoverResult
import com.loom.assistant.intelligence.planner.PlanAuthCover
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.TaskPlan
import com.loom.assistant.intelligence.planner.TaskStepKind

/**
 * Stage 2D (Worker B) — the per-sequence plan cover, reusing the
 * demo.sequence mechanism (the ActionQueue precedent, no new keys).
 *
 * Corrected model (David, 2026-11-06 14:39 — supersedes the
 * plan-approval card): the planner is LOOM's INTERNAL mechanism. The
 * brain interprets his instruction, creates the plan, and executes
 * it; the plan is for LOOM, not for David. He approves sequence plans
 * IN ADVANCE, in blanket form — he never sees a plan and never
 * approves one. So:
 *
 *  1. DIRECT_USER plans (his own words) are covered by that blanket
 *     advance approval: NO card, NO token, NO plan shown. The grant
 *     is audit-logged as blanket approval, not a card approval. A stop
 *     that already fired still refuses the grant — nothing runs.
 *  2. MODEL plans (a model-proposed sequence) are NEVER covered by
 *     his blanket approval: [requestPlanCover] asks AuthGate for
 *     `AuthActionRegistry.demoSequence(goal, appLabel, stepCount)`
 *     with the plan's own [RequestOrigin] passed through. AuthGate
 *     already enforces the provenance rule (a stored grant skips
 *     the card only for DIRECT_USER on a grant-eligible key).
 *     Device-failure fix (2026-11-13, F4): the request is SCOPED
 *     with the plan's resolved package (resolved BEFORE the
 *     request), so the gate's pseudo-control check can allow it
 *     without a card when the grant is active and the app is
 *     approved — the cover decision lives in
 *     [PseudoControlPolicy], not here. An unscoped plan (no
 *     resolvable package) always cards, and a revoked grant
 *     always cards. This class never overrides the origin and
 *     holds no grant logic of its own.
 *  3. The issued token (MODEL path only) is consumed EXACTLY ONCE,
 *     immediately, at sequence start (single-use, 90 s). The live cover
 *     is then the [SequenceCover]-shaped record (planId, appPackage,
 *     stepHash) — the token itself is never stored or re-used.
 *  4. [checkCover] is true only when the cover is live, the step's
 *     package matches the covered package (steps without a PACKAGE
 *     param inherit the plan's app), the step is not a message/call
 *     step (those always need their own fresh token, never the cover),
 *     and neither stop has fired.
 *  5. DENIED carries a plain reason when David declines, the card
 *     times out, the gate denies, or a stop lands while the card is
 *     up. A stop during the card wait ABANDONS the token: it is never
 *     consumed and never used (it expires unused in 90 s).
 *
 * The gate is reached only through the injected [CoverTokenRequest] /
 * [CoverTokenConsumer] seams, so this file compiles on the JVM with
 * fakes; production binds them to AuthGate in PlanWiring.
 */

/** Blocking demo.sequence token acquisition. Null = declined / timed out / no gate.
 *  [scopePackage] is the plan's resolved target package ("" when
 *  unresolvable): the gate needs it for the pseudo-control cover
 *  decision — an unscoped request always cards. */
fun interface CoverTokenRequest {
    fun request(
        goal: String,
        appLabel: String,
        stepCount: Int,
        origin: RequestOrigin,
        scopePackage: String,
    ): String?
}

/** Single-use consumption of a demo.sequence token. */
fun interface CoverTokenConsumer {
    fun consume(tokenId: String): Boolean
}

class PlanCover(
    private val requestToken: CoverTokenRequest,
    private val consumeToken: CoverTokenConsumer,
    private val stop: PlanStopSignal,
    private val apps: AppResolver? = null,
    private val onEvent: ((String) -> Unit)? = null,
) : PlanAuthCover {

    /** The live cover, mirroring ActionQueue.SequenceCover. */
    data class LiveCover(
        val planId: String,
        val appPackage: String,
        val appLabel: String,
        val stepHash: Int,
    )

    @Volatile
    private var live: LiveCover? = null

    /** The covered package, or null when no cover is live. */
    val coveredPackage: String? get() = live?.appPackage

    /** The covered app's label, or null when no cover is live. */
    val coveredAppLabel: String? get() = live?.appLabel

    override fun requestPlanCover(plan: TaskPlan): CoverResult {
        live = null
        val appLabel = planAppLabel(plan)
        if (plan.origin == RequestOrigin.DIRECT_USER) {
            // Corrected model (David, 2026-11-06 14:39): the plan is
            // for LOOM, not for David. His own words carry his blanket
            // advance approval for sequence plans — NO card, NO token,
            // NO plan shown, NO plan approved. The grant is audit-logged
            // as blanket approval, never as a card approval. A stop
            // that already fired still refuses the grant — nothing runs.
            if (stop.isStopped()) {
                onEvent?.invoke("cover denied (blanket approval): stopped before starting")
                return CoverResult.DENIED(
                    "Stopped before starting — I didn't start \"${plan.goal}\".",
                )
            }
            val pkg = resolvePlanPackage(plan)
            live = LiveCover(
                planId = plan.id,
                appPackage = pkg,
                appLabel = appLabel,
                stepHash = plan.steps.hashCode(),
            )
            onEvent?.invoke("cover granted by blanket advance approval for ${plan.id} in $pkg")
            return CoverResult.GRANTED
        }
        // MODEL origin only: a model-proposed plan is NEVER covered by
        // his blanket approval — it goes through the gate below.
        // Device-failure fix (2026-11-13, F4): the plan package is
        // resolved BEFORE the request and passed as the scope, so
        // a grant-covered plan does not card.
        val pkg = resolvePlanPackage(plan)
        val tokenId = try {
            requestToken.request(plan.goal, appLabel, plan.steps.size, plan.origin, pkg)
        } catch (e: Exception) {
            onEvent?.invoke("cover request threw: ${e.message}")
            return CoverResult.DENIED(
                "Could not ask for approval: ${e.message ?: "unknown error"}",
            )
        }
        if (tokenId.isNullOrBlank()) {
            onEvent?.invoke("cover denied (no token)")
            return CoverResult.DENIED(
                "Not confirmed — I didn't start \"${plan.goal}\".",
            )
        }
        // Stop landed while the card was up: abandon the token — never
        // consumed, never used.
        if (stop.isStopped()) {
            onEvent?.invoke("cover abandoned: stopped during the card wait")
            return CoverResult.DENIED(
                "Stopped before starting — the approval was abandoned, nothing was done.",
            )
        }
        // Consumed EXACTLY ONCE, immediately, at sequence start.
        val consumed = try {
            consumeToken.consume(tokenId)
        } catch (_: Exception) {
            false
        }
        if (!consumed) {
            onEvent?.invoke("cover token could not be consumed")
            return CoverResult.DENIED(
                "The approval could not be used — I didn't start \"${plan.goal}\".",
            )
        }
        live = LiveCover(
            planId = plan.id,
            appPackage = pkg,
            appLabel = appLabel,
            stepHash = plan.steps.hashCode(),
        )
        onEvent?.invoke("cover granted for ${plan.id} in $pkg")
        return CoverResult.GRANTED
    }

    override fun checkCover(step: PlanStep): Boolean {
        val cover = live ?: return false
        if (stop.isStopped()) return false
        // Message/call steps are NEVER covered by the plan cover — each
        // send/call needs its own fresh confirmation (David's per-message
        // / per-call rule; message.send / call.place are never
        // grant-eligible).
        if (step.kind == TaskStepKind.MESSAGE_SEND || step.kind == TaskStepKind.CALL_PLACE) {
            return false
        }
        val stepPackage = step.params[PlanParams.PACKAGE]
        // Steps without a PACKAGE param inherit the plan's app.
        if (stepPackage != null && cover.appPackage.isNotBlank() && stepPackage != cover.appPackage) {
            return false
        }
        if (stepPackage != null && cover.appPackage.isBlank()) {
            return false
        }
        return true
    }

    /**
     * AOIE order (a) — the cover-aware search token provider asks
     * this, not checkCover: is the cover live for this package?
     * Same rules as checkCover minus the step-kind exclusion (the
     * search sequence never sends messages or places calls, and its
     * own token flow keeps message/call keys out of reach).
     * Blank package = "no package to check against" → false: an
     * unclassified target never inherits cover.
     */
    fun isCoveredPackage(packageName: String): Boolean {
        val cover = live ?: return false
        if (stop.isStopped()) return false
        if (packageName.isBlank()) return false
        if (cover.appPackage.isBlank()) return false
        return packageName == cover.appPackage
    }

    /** Clears the live cover (plan finished / abandoned). */
    fun clear() {
        live = null
    }

    private fun planAppLabel(plan: TaskPlan): String {
        for (step in plan.steps) {
            step.params[PlanParams.APP]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return "this app"
    }

    private fun resolvePlanPackage(plan: TaskPlan): String {
        for (step in plan.steps) {
            step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val label = planAppLabel(plan)
        val resolver = apps ?: return ""
        return try {
            when (val r = resolver.resolve(label)) {
                is AppResolution.Found -> r.packageName
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
