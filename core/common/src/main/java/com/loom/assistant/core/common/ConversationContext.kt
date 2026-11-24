package com.loom.assistant.core.common

/**
 * Master §2 (item 6): minimal conversational context — last-turn intent.
 *
 * What it keeps: the app the last launch/search used, and the last
 * command. That is the whole of it: one turn of memory, enough for a
 * follow-up like "search for X" after opening YouTube to resolve the
 * app from context instead of asking again.
 *
 * Rules:
 * - Single-turn only: every launch/search overwrites the stored app.
 *   An explicit app in the new utterance always wins over context.
 * - Never persisted: an in-memory field on the session (voice manager /
 *   chat screen). A new session starts with no context.
 * - The app is a display name as the user said it ("YouTube"), never a
 *   package — resolution to a package happens at execution time via
 *   Worker 1's AppCatalogue, which disambiguates "YouTube" from
 *   "YouTube Music".
 * - Clarification only when genuinely necessary: context fills the app
 *   silently; the user is asked "which app?" only when neither the
 *   utterance nor the context names one.
 *
 * Task-record fix (2026-11-07): this class also holds the session's
 * ONE active [TaskRecord] ([activeTask]). The record is the same idea
 * grown up — the last-turn app plus the verbatim instruction, the
 * query, the provenance and the approval status — so there is still
 * only one context system, not two. [startTask] is the only writer
 * (besides [approveActiveTask] / [answerActiveTaskApp] /
 * [clearTask]); it syncs [lastAppName] from the record, so
 * [appForFollowUpSearch] keeps working exactly as before. A record
 * is created when a multi-step instruction is understood and stays
 * until the task ends or a new instruction supersedes it; a bare
 * follow-up ("play music") resumes it instead of starting from
 * scratch. In-memory only, never persisted — a new session starts
 * with no record.
 *
 * MASTER F3a: the context itself is now SHARED, not per-session.
 * [SharedConversationContext] holds the ONE application-scoped
 * instance every [VoiceSessionManager] uses, so a new floating voice
 * session (recreated on every orb tap) inherits the active task and
 * the last-turn app instead of starting with no memory. Process-
 * scoped by design (the smallest change that achieves cross-session
 * inheritance): a process death still loses the task, exactly as
 * before — disk persistence of the active task is a later step, not
 * this one. Tests reset it via [SharedConversationContext.resetForTest].
 *
 * MASTER F3b: the asked-once flags for the single-command paths
 * ([askedSearchQuery] / [askedSearchApp] / [askedLaunchApp]). The
 * planner's [TaskRecord.askedApp] guard already asked once on the
 * plan path; these are the same rule for the single-command search /
 * launch questions, kept here so they survive across turns of the
 * same task and reset when the task changes (a new query/app, or a
 * completed search/launch).
 *
 * MASTER F3c: a launch is recorded as ATTEMPTED ([rememberLaunchAttempt]),
 * never as the last-turn app, until its outcome is known. Only a
 * confirmed success ([confirmLaunch] / [noteLaunchOutcome]) promotes
 * it to [lastAppName]; a failure ([failLaunch]) drops the attempt
 * with nothing recorded. A failed launch must never become the
 * "last-turn app" a follow-up search resolves from.
 */
object SharedConversationContext {
    private val shared = ConversationContext()

    /** The one application-scoped context. Every voice session shares it. */
    fun instance(): ConversationContext = shared

    /** Test seam only: drop all shared state between tests. */
    fun resetForTest() {
        shared.clear()
    }
}

class ConversationContext {
    var lastAppName: String? = null
        private set

    var lastCommand: VoiceCommand? = null
        private set

    /**
     * The session's one active task record, or null when no task is
     * in flight. See [TaskRecord]: created once when the instruction
     * is understood, passed unchanged through approval, checked
     * before any question. Null after [clear] or [clearTask].
     */
    var activeTask: TaskRecord? = null
        private set

    /**
     * Device-test failures (2026-11-11, D-05): the LAST task, kept
     * after the active record ends. "Execute the task I give to
     * you" / "I'm giving you a task, do it" refer to the task David
     * already gave — usually the one that just failed (open my
     * story). [clearTask] drops the ACTIVE record but keeps this
     * one, so a reference after a failure still resolves; a new
     * [startTask] replaces both. Never persisted: a process death
     * still loses it, exactly like the active record.
     */
    var lastTask: TaskRecord? = null
        private set

    /** The task a "the task" / "do it" reference resolves to, if any. */
    fun referencedTask(): TaskRecord? = activeTask ?: lastTask

    /**
     * Device-test failures (2026-11-11, D-05, refined): remember a
     * task as the LAST task only — never the active task, never the
     * last-turn app. A launch ("open my story") is an ATTEMPT until
     * its outcome is known (MASTER F3c): recording it via [startTask]
     * would sync [lastAppName] from the record's app and make a
     * follow-up search resolve from an unconfirmed app, and would
     * make a bare follow-up resume it as the active task. As the
     * last task it stays referenceable ("execute the task", "do it")
     * without any of that. The record carries no app for the same
     * reason — a launch names no task app until it is confirmed.
     */
    fun noteLastTask(record: TaskRecord) {
        lastTask = record
    }

    /**
     * Record the terminal outcome of the active task (called from
     * the plan hosts' outcome ack: "Done —" = completed, anything
     * else = failed). The status rides on BOTH records, so a resume
     * can say "that one is already done" instead of re-running it,
     * and a failed one can be retried by name.
     */
    fun noteTaskOutcome(completed: Boolean) {
        val status = if (completed) TaskRecordStatus.COMPLETED else TaskRecordStatus.FAILED
        activeTask = activeTask?.copy(status = status)
        lastTask = (activeTask ?: lastTask)?.copy(status = status)
    }

    /**
     * MASTER F3c: the app of the most recent launch ATTEMPT — a
     * launch that was dispatched but whose outcome is not yet known.
     * Never used as the follow-up app (see [appForFollowUpSearch]);
     * only [confirmLaunch] promotes it to [lastAppName].
     */
    var attemptedAppName: String? = null
        private set

    /** Generation of the [attemptedAppName] — a newer remember*
     *  call bumps [generation] and voids the pending attempt, so a
     *  late "Opened …" outcome can never promote a launch the user
     *  has already moved on from. */
    private var attemptGeneration: Int = -1

    /** Monotonic turn counter: bumped by every remember, startTask
     *  and clear call. Only used to void stale launch attempts (above). */
    private var generation: Int = 0

    /**
     * MASTER F3b: asked-once flags for the single-command paths.
     * True once LOOM has asked the question in this task; a second
     * identical question fails honestly instead of re-asking.
     */
    var askedSearchQuery: Boolean = false
        private set
    var askedSearchApp: Boolean = false
        private set
    var askedLaunchApp: Boolean = false
        private set

    /** The query/app the pending single-command search carries — a
     *  NEW query or app resets the other slot's asked flag (new task),
     *  while a repeat of the SAME query/app keeps it (same task). */
    var pendingSearchQuery: String? = null
        private set
    var pendingSearchApp: String? = null
        private set

    fun rememberLaunch(appName: String) {
        if (appName.isBlank()) return
        lastAppName = appName
        lastCommand = VoiceCommand.LAUNCH_APP
        attemptedAppName = null
        attemptGeneration = -1
        askedLaunchApp = false
        generation++
    }

    /**
     * MASTER F3c: record a launch ATTEMPT. Sets the last command (so
     * the session knows a launch is in flight) but deliberately does
     * NOT touch [lastAppName] — the app becomes the last-turn app
     * only when [confirmLaunch] hears the launch succeeded.
     */
    fun rememberLaunchAttempt(appName: String) {
        if (appName.isBlank()) return
        attemptedAppName = appName
        lastCommand = VoiceCommand.LAUNCH_APP
        generation++
        attemptGeneration = generation
    }

    /**
     * MASTER F3c: promote the pending attempt to the last-turn app —
     * call only when the launch is known to have succeeded (the
     * host's outcome, or the manager's "Opened <label>." ack). A
     * non-matching app, or an attempt already voided by a newer
     * turn, is a no-op — never a guess.
     */
    fun confirmLaunch(appName: String) {
        val attempt = attemptedAppName ?: return
        if (!attempt.equals(appName, ignoreCase = true)) return
        if (attemptGeneration != generation) return
        lastAppName = attempt
        lastCommand = VoiceCommand.LAUNCH_APP
        attemptedAppName = null
        attemptGeneration = -1
        askedLaunchApp = false
        generation++
    }

    /** MASTER F3c: drop a failed launch attempt — nothing is recorded. */
    fun failLaunch(appName: String) {
        val attempt = attemptedAppName ?: return
        if (!attempt.equals(appName, ignoreCase = true)) return
        attemptedAppName = null
        attemptGeneration = -1
    }

    fun rememberSearch(appName: String) {
        if (appName.isBlank()) return
        lastAppName = appName
        lastCommand = VoiceCommand.SEARCH_IN_APP
        clearSearchAsked()
        generation++
    }

    fun rememberCommand(cmd: VoiceCommand) {
        lastCommand = cmd
    }

    /** MASTER F3b: mark the search-query question asked for this task. */
    fun markSearchQueryAsked() {
        askedSearchQuery = true
    }

    /** MASTER F3b: mark the search-app question asked for this task. */
    fun markSearchAppAsked() {
        askedSearchApp = true
    }

    /** MASTER F3b: mark the launch-app question asked for this task. */
    fun markLaunchAppAsked() {
        askedLaunchApp = true
    }

    /** MASTER F3b: clear the launch-app question flag (a matched
     *  command is a new instruction, so the question is live again). */
    fun clearLaunchAsked() {
        askedLaunchApp = false
    }

    /**
     * MASTER F3b: note the query of the pending single-command
     * search. A NEW query resets the app-question flag (new task);
     * the SAME query keeps it (same task — ask once, then fail).
     */
    fun noteSearchQuery(query: String) {
        if (pendingSearchQuery != query) {
            pendingSearchQuery = query
            askedSearchApp = false
        }
    }

    /**
     * MASTER F3b: note the app of the pending single-command search
     * (a query-blank turn that names an app). A NEW app resets the
     * query-question flag; the SAME app keeps it.
     */
    fun noteSearchApp(app: String) {
        if (pendingSearchApp != app) {
            pendingSearchApp = app
            askedSearchQuery = false
        }
    }

    /** MASTER F3b: a completed search resets the search guards. */
    fun clearSearchAsked() {
        askedSearchQuery = false
        askedSearchApp = false
        pendingSearchQuery = null
        pendingSearchApp = null
    }

    /**
     * Start (or replace) the session's task record. The record's app,
     * when filled, becomes the last-turn app, so follow-up searches
     * resolve from it exactly as they did from a launch. A new
     * instruction supersedes the old record — there is ever one.
     */
    fun startTask(record: TaskRecord) {
        activeTask = record
        // D-05: the last task is the task just started — a later
        // "the task" reference resolves to it even after it ends.
        lastTask = record
        record.app?.takeIf { it.isNotBlank() }?.let { lastAppName = it }
        // A new task resets the single-command guards — the plan
        // path carries its own askedApp on the record.
        clearSearchAsked()
        generation++
    }

    /**
     * Approval flips ONLY the record's status (see
     * [TaskRecord.approve]): the instruction, app, package and query
     * come back unchanged. Returns the record, or null when none.
     */
    fun approveActiveTask(): TaskRecord? {
        activeTask = activeTask?.approve()
        return activeTask
    }

    /**
     * Fill the active record's app slot from David's answer to the
     * one app question (see [TaskRecord.withApp]). Returns the
     * record, or null when none is active.
     */
    fun answerActiveTaskApp(appLabel: String): TaskRecord? {
        if (appLabel.isBlank()) return activeTask
        activeTask = activeTask?.withApp(appLabel)
        lastAppName = appLabel
        return activeTask
    }

    /** Drop the active record (task ended, or superseded). */
    fun clearTask() {
        activeTask = null
        clearSearchAsked()
        generation++
    }

    /** The app a follow-up search should run in, or null when unknown. */
    fun appForFollowUpSearch(): String? = lastAppName

    fun clear() {
        lastAppName = null
        lastCommand = null
        activeTask = null
        lastTask = null
        attemptedAppName = null
        attemptGeneration = -1
        clearSearchAsked()
        askedLaunchApp = false
        generation = 0
    }
}
