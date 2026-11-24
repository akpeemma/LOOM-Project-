package com.loom.assistant.core.common

/**
 * Task-record fix (2026-11-07) — the ONE structured task record.
 *
 * Root cause it closes (device-proven Nov 6 17:48, planner build
 * 83dcd3a): David said "open YouTube Music and play music", approved,
 * and LOOM asked "should I play the music in YouTube Music or
 * YouTube?" — the app, named in the first sentence, was lost across
 * the approval step. There was no record of the task anywhere: the
 * instruction lived only inside one in-flight [PlanRequest], and
 * every re-entry — an engine-fallback turn, a model action that
 * re-derived the goal from a bare tail ("play music"), a follow-up
 * turn, a bare approval — started from scratch with whatever text
 * that turn carried. The planner's bare-play branch then re-asked
 * for an app that was already filled.
 *
 * The rule, from the Siri-context research (§2.3 dialogue state
 * tracking): one task record, created ONCE when the instruction is
 * understood, passed unchanged through approval — "yes" only flips
 * its status to approved, never re-derives, re-parses, or re-asks
 * anything — and checked before EVERY question: a filled slot is
 * used silently, never asked twice.
 *
 * Where it lives: here, in core:common, as pure data — so the
 * planner (intelligence:planner), the executor (execution:tasks)
 * and the voice session (interaction:voice) can all share the same
 * type with no new module edge. [ConversationContext] holds the
 * session's active record (it already held the last-turn app; the
 * record is the same idea, grown up — no duplicate context system).
 * The planner builds the authoritative copy (resolving the spoken
 * label to a package) and attaches it to [com.loom.assistant.
 * intelligence.planner.TaskPlan]; the executor carries that copy
 * unchanged through the cover (approval) and through replans.
 *
 * What it deliberately does NOT hold: the plan itself (steps live
 * on TaskPlan), screen content, or any credential. The record is
 * in-memory only and dies with the session — a new session starts
 * with no record, like ConversationContext before it.
 *
 * Provenance: [origin] is who asked for THIS task. A record created
 * from David's own words is DIRECT_USER; one created from a model
 * proposal is MODEL. Adopting a record's filled APP slot (a fact
 * from David's words) never changes the plan's origin — the plan's
 * origin always comes from the incoming request, so a model-guessed
 * task still goes through its cover card and never inherits blanket
 * approval. See TaskPlannerTest §record and TaskPlanExecutorTest §record.
 */
enum class TaskRecordStatus {
    /** Understood, not yet approved or run. */
    UNDERSTOOD,
    /** Approved (blanket advance approval for DIRECT_USER, or the
     *  cover card for MODEL) — approval flips ONLY this status. */
    APPROVED,
    /** Currently running. */
    RUNNING,
    /** Finished, every step verified (or superseded by a replan). */
    COMPLETED,
    /** Failed, interrupted, or refused. */
    FAILED,
}

data class TaskRecord(
    /** David's instruction, verbatim as first understood. Never
     *  rewritten: a bare follow-up ("play music") re-issues this
     *  instruction with the record attached, it does not paraphrase it. */
    val instruction: String,
    /** The app, as spoken ("YouTube Music"). Null when genuinely
     *  unnamed — the one case where asking is legitimate, once. */
    val app: String?,
    /** Resolved package ("com.google.android.apps.youtube.music"),
     *  or null until the planner resolves the label. The spoken
     *  label is the display name; the package is what executes.
     *  "YouTube Music" resolves to the real launcher label
     *  "YT Music" through AppCatalogue's alias — the record keeps
     *  the spoken label, the step params carry the package. */
    val appPackage: String?,
    /** The play/search query ("Davido", "music"), or null. */
    val query: String?,
    /** Who asked — DIRECT_USER for David's own words, MODEL for a
     *  model-proposed task. Never inherited across origins: the
     *  plan's origin always comes from the incoming request. */
    val origin: RequestOrigin,
    /** Lifecycle of this record. */
    val status: TaskRecordStatus = TaskRecordStatus.UNDERSTOOD,
    /** True while LOOM has asked David which app and is waiting for
     *  the answer. The voice session uses this to recognise the
     *  answer turn and fill the slot instead of starting a new task. */
    val awaitingApp: Boolean = false,
    /** True once LOOM has asked for the app in this task. A second
     *  question for the same slot is never asked: when this is true
     *  and the app is still blank, the task fails honestly instead
     *  of re-asking. */
    val askedApp: Boolean = false,
) {
    /** True when the app slot is already filled — use it, never ask. */
    fun hasApp(): Boolean = !app.isNullOrBlank()

    /**
     * Approval flips ONLY the status, UNDERSTOOD → APPROVED. It
     * never re-derives, re-parses, or re-asks anything — the
     * instruction, app, package and query come back byte-identical.
     * Any other status is left untouched (approving a finished or
     * failed record is a no-op, never a resurrection).
     */
    fun approve(): TaskRecord =
        if (status == TaskRecordStatus.UNDERSTOOD) copy(status = TaskRecordStatus.APPROVED)
        else this

    /** Fill the app slot from David's answer. Clears the wait. */
    fun withApp(appLabel: String, pkg: String? = appPackage): TaskRecord =
        copy(app = appLabel, appPackage = pkg ?: appPackage, awaitingApp = false)

    /** Fill the query slot. */
    fun withQuery(q: String): TaskRecord = copy(query = q)

    /** Mark that LOOM has asked for the app once and is waiting. */
    fun markAppAsked(): TaskRecord = copy(askedApp = true, awaitingApp = true)
}
