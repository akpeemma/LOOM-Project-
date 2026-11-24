package com.loom.assistant.intelligence.planner

import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.TaskRecord

/**
 * Stage 2D — the handoff type Worker C (voice / chat entry) produces
 * and Worker B (executor) consumes. The planner turns a [PlanRequest]
 * into a [PlanResult] (see [TaskPlanner.plan]); nothing in here
 * executes.
 */
data class PlanRequest(
    /** David's instruction, verbatim as spoken / typed. */
    val goal: String,
    /**
     * Who actually asked: [RequestOrigin.DIRECT_USER] for David's own
     * words, [RequestOrigin.MODEL] for a model-proposed action. Grants
     * apply only to DIRECT_USER (see RequestOrigin); the planner
     * carries the origin onto the [TaskPlan] so the executor and the
     * AuthGate cover see the same provenance.
     */
    val origin: RequestOrigin,
    /**
     * True = narrate / show each step to David as it runs (pointer +
     * spoken step, as it happens); false = run quietly and report
     * only the [PlanOutcome]. The plan itself is NEVER shown and
     * NEVER approved — transparency changes only what David sees of
     * the run, never what the plan is allowed to do.
     */
    val transparency: Boolean,
    /**
     * The app of the last turn (VoiceCommands' ConversationContext),
     * used only to resolve a search with no app named
     * ("search for Davido" after a YouTube Music turn). Null when
     * there is no last-turn app. Never guessed from anything else.
     */
    val contextApp: String? = null,
    /**
     * Task-record fix (2026-11-07) — the ONE structured task record
     * for this task, created when the instruction was understood
     * ([TaskRecord]). Null on a fresh instruction: the planner
     * builds the record from the goal then. Non-null on a resumed
     * turn (a bare follow-up like "play music", or David's answer
     * to the one app question): the planner ADOPTS this record —
     * its filled app, its original instruction, its provenance —
     * instead of re-deriving anything from the bare goal. The
     * record travels unchanged through approval and replan; the
     * plan's [origin] still comes from [origin] above, never from
     * the record, so a model-guessed task never inherits blanket
     * approval from a DIRECT_USER record.
     */
    val taskRecord: TaskRecord? = null,
)
