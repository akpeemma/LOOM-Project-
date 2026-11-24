package com.loom.assistant.intelligence.planner

import com.loom.assistant.core.common.MediaControl
import com.loom.assistant.core.common.MediaControlPhrases
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.execution.screenawareness.NavigationResolver
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.TargetAliases
import java.util.UUID

/**
 * Stage 2D — rule-based task planner (Worker A).
 *
 * Port of Brahma `brahma_core/planner.py` semantics to LOOM's
 * on-device vocabulary: goal → ordered [PlanStep]s (kind + params +
 * description + critical flag); rule-based decomposition first; an
 * optional LLM-proposed plan (raw JSON) is accepted ONLY through
 * [PlanValidator] — the model may PROPOSE, the app DISPOSES — and
 * any model failure falls back to the rules silently, never raising
 * (Brahma `create_plan` contract).
 *
 * General pipeline, NOT a hardcoded script: the Davido case
 * ("Open YouTube Music and play a song by Davido") falls out of the
 * general composite pattern (open-clause + play-clause), the same
 * patterns that handle any other app / artist / query.
 *
 * Pattern parity: the extractors mirror `core:common`'s
 * [VoiceCommands] shapes (same phrases, same split rules — last
 * " in ", first " for " for the app-first form, last " on ") but
 * preserve original casing, because plan params carry display names
 * ("YouTube Music", "Mum"), not lowercased matcher text.
 *
 * The planner only PRODUCES plans. It never dispatches, never taps,
 * never types, never launches — no `dispatchGesture`, no execution
 * call of any kind lives in this file.
 */
class TaskPlanner(
    private val apps: AppResolver,
    /**
     * Optional LLM plan source: given the goal, returns raw plan JSON.
     * Null (the default) means rule-based only. Any throw, empty
     * reply, bad JSON, or validation failure falls back to the rules.
     */
    private val llmPlanJson: ((String) -> String)? = null,
    private val validator: PlanValidator = PlanValidator(),
    /**
     * KNOWLEDGE BASE (2026-11-09): the durable screen knowledge
     * the replan consults for its built-in recoveries (whether a
     * clear control is known for the failed search; whether a back
     * recovery already failed on this screen). Default is an empty
     * in-memory store — no knowledge means the recoveries fall back
     * to the live screen only, exactly as before. Production wiring
     * passes the shared disk-backed store.
     */
    private val knowledge: ScreenKnowledgeStore = ScreenKnowledgeStore(),
) {

    companion object {
        /**
         * Device-failure fix (2026-11-13, F2/F4): kinds whose steps
         * may receive the plan package via [populateStepScope].
         * PLAY_PAUSE excluded by design (current-screen control).
         */
        private val SCOPE_STAMP_KINDS: Set<TaskStepKind> = setOf(
            TaskStepKind.SEARCH_IN_APP,
            TaskStepKind.TAP_TARGET,
            TaskStepKind.TYPE_TEXT,
            TaskStepKind.SUBMIT,
            TaskStepKind.SCROLL,
            TaskStepKind.NAVIGATE_TAB,
            TaskStepKind.LONG_PRESS,
            TaskStepKind.SWIPE,
            TaskStepKind.FOCUS_FIELD,
            TaskStepKind.OPEN_MENU,
            TaskStepKind.VERIFY_OUTCOME,
        )

        /** Brahma `AgentExecutor.MAX_REPLAN_ATTEMPTS`. */
        const val MAX_REPLANS = 2

        /** Brahma `AgentExecutor.MAX_STEP_ATTEMPTS`. */
        const val MAX_STEP_ATTEMPTS = 3

        /**
         * Simple-task execution fix (2026-11-07): the step kinds a
         * step DEPENDS on — its precondition steps. A step may only
         * run once every kind it depends on has verified (or was
         * never in the plan). The replan drops a failed step's
         * dependents with it; the executor never runs a step whose
         * precondition step failed. OBSERVE_SCREEN / LAUNCH_APP /
         * WAIT_FOR_APP / REPORT depend on nothing.
         */
        fun dependsOn(kind: TaskStepKind): Set<TaskStepKind> = when (kind) {
            // A tap needs results to tap.
            TaskStepKind.TAP_TARGET -> setOf(TaskStepKind.SEARCH_IN_APP)
            // Typing / submitting only mean anything inside a search
            // the plan already ran.
            TaskStepKind.TYPE_TEXT, TaskStepKind.SUBMIT ->
                setOf(TaskStepKind.SEARCH_IN_APP)
            // Scrolling only means anything over a results list.
            TaskStepKind.SCROLL -> setOf(TaskStepKind.SEARCH_IN_APP)
            // Verification needs the thing it verifies to have run.
            TaskStepKind.VERIFY_OUTCOME ->
                setOf(TaskStepKind.SEARCH_IN_APP, TaskStepKind.TAP_TARGET, TaskStepKind.NAVIGATE_TAB)
            else -> emptySet()
        }

        /**
         * Universal Console (2026-11-07): the step kinds that ADVANCE
         * a goal — an action inside an app. Used by [replan]: after a
         * step is abandoned, a rebuilt plan that kept only
         * observation steps (OBSERVE_SCREEN, REPORT) can "complete"
         * without doing anything — the executor verifies readable
         * screens and reports Done for a task that never happened. A
         * kept VERIFY_OUTCOME is exempt (it re-checks an outcome
         * whose search already verified in this run). Mirrors
         * TaskPlanExecutor.ACTION_KINDS + the console kinds.
         */
        val GOAL_ADVANCING_KINDS: Set<TaskStepKind> = setOf(
            TaskStepKind.LAUNCH_APP,
            TaskStepKind.SEARCH_IN_APP,
            TaskStepKind.TAP_TARGET,
            TaskStepKind.TYPE_TEXT,
            TaskStepKind.SUBMIT,
            TaskStepKind.SCROLL,
            TaskStepKind.GO_BACK,
            TaskStepKind.LONG_PRESS,
            TaskStepKind.SWIPE,
            TaskStepKind.FOCUS_FIELD,
            TaskStepKind.OPEN_MENU,
            TaskStepKind.MESSAGE_SEND,
            TaskStepKind.CALL_PLACE,
            // NAV-SCREEN fix (2026-11-11, N1): navigation advances a goal.
            TaskStepKind.NAVIGATE_TAB,
        )

        private val FINANCIAL_WORDS = listOf(
            Regex("\\bpay\\b", RegexOption.IGNORE_CASE),
            Regex("\\bsend\\s+money\\b", RegexOption.IGNORE_CASE),
            Regex("\\btransfer\\b", RegexOption.IGNORE_CASE),
            Regex("\\bwithdraw\\b", RegexOption.IGNORE_CASE),
            Regex("\\bdeposit\\b", RegexOption.IGNORE_CASE),
            Regex("\\bbuy\\b", RegexOption.IGNORE_CASE),
            Regex("\\bcheckout\\b", RegexOption.IGNORE_CASE),
            Regex("\\bsubscri(be|ption)\\b", RegexOption.IGNORE_CASE),
            Regex("\\botp\\b", RegexOption.IGNORE_CASE),
            Regex("\\bpassword\\b", RegexOption.IGNORE_CASE),
        )

        private val COMPOSITE_REGEX =
            Regex("^(open|launch)\\s+(.+?)\\s+(?:and|then)\\s+(play|search)\\s+(.+)$", RegexOption.IGNORE_CASE)

        private val GUIDE_PHRASES = listOf(
            "guide me to ",
            "guide me through ",
            "help me do ",
            "walk me through ",
            "show me the way to ",
        )

        /**
         * Simple-task execution fix (2026-11-07): the find-words
         * play shape — "find <song> on <app> and play it". Group 1
         * is the query, group 2 the app.
         */
        private val FIND_AND_PLAY_REGEX =
            Regex("^find\\s+(.+?)\\s+on\\s+(.+?)\\s+and\\s+play\\b", RegexOption.IGNORE_CASE)

        private val MESSAGE_PREFIXES = listOf(
            "send a whatsapp message to ",
            "send whatsapp message to ",
            "send whatsapp to ",
            "send a whatsapp to ",
            "send a text message to ",
            "send text message to ",
            "send sms to ",
            "send a text to ",
            "send text to ",
            "send a message to ",
            "send message to ",
            "message ",
            "text ",
            "send ",
        )

        private val BULK_WORDS = listOf(
            "everyone", "everybody", "all contacts", "all my contacts",
            "all of my contacts", "broadcast",
        )

        /**
         * MASTER F1c/F2a (planner twin of the VoiceCommands lists —
         * same words, same order; see extractLaunchApp). Leading
         * words before a command are politeness; trailing words after
         * an app name are filler.
         */
        private val LEADING_WORDS = listOf(
            "let's go ", "lets go ", "please ", "can you ", "could you ",
            "i want you to ", "i said you should ", "i said ",
        )

        private val TRAILING_FILLERS = listOf(
            "for me please", "right now", "for me", "now", "please",
        )
    }

    // ---------- result ----------

    sealed class PlanResult {
        /** A buildable plan, ready for approval + execution. */
        data class Planned(val plan: TaskPlan) : PlanResult()

        /** Essential info is genuinely missing; [missing] names it
         *  ("app", "query", "recipient", "messageText", "target",
         *  "direction", "channel", "goal"). Only returned when the
         *  instruction cannot be resolved without it. */
        data class ClarificationNeeded(val question: String, val missing: String) : PlanResult()

        /**
         * The goal targets a sensitive app (banking / financial /
         * authenticator) or a financial action. No tap / type / submit
         * was planned inside it. [guidancePlan] is the guidance-only
         * alternative (OBSERVE + GUIDE_TO + REPORT) — the pointer may
         * show David the way; it may never act inside the app.
         */
        data class RefusedSensitive(
            val reason: String,
            val appName: String?,
            val guidancePlan: TaskPlan?,
        ) : PlanResult()

        /** Policy refusal that is not a sensitive-app refusal, e.g.
         *  bulk messaging (one recipient per invocation, always). */
        data class Refused(val reason: String) : PlanResult()

        /** No pattern covers this goal and no LLM plan validated.
         *  The planner says so instead of inventing steps. */
        data class Unsupported(val reason: String) : PlanResult()
    }

    // ---------- entry ----------

    fun plan(request: PlanRequest): PlanResult {
        val goal = request.goal.trim()
        if (goal.isBlank()) {
            return PlanResult.ClarificationNeeded("What should I do?", "goal")
        }
        // Device-test failures (2026-11-11, D-01/D-07): the goal is
        // David's sentence, not a step — remarks, repeats and filler
        // ("now you're on Facebook…", "and stop being stupid") are
        // cut BEFORE any branch reads it, so no step, target, query
        // or task name ever carries the whole sentence again. Genuine
        // composites ("open YouTube and play…") survive the cut (see
        // TaskTextSanitizer): only remarks/repeats/filler are cut.
        val text = TaskTextSanitizer.sanitizeGoal(clean(goal))
        // Task-record fix (2026-11-07): the record is resolved ONCE,
        // here, before any branch runs — and every branch below reads
        // the app from it instead of re-parsing the goal. An app the
        // goal names explicitly always wins; otherwise the carried
        // record (a resumed bare follow-up, or David's answer to the
        // one app question) is adopted unchanged; otherwise the
        // last-turn context app fills the slot. The record is what
        // the plan carries forward — see buildPlan.
        val record = resolveRecord(request, text)

        if (isBulkMessageRequest(text)) {
            return PlanResult.Refused(
                "I don't send bulk messages — tell me one person at a time.",
            )
        }
        // The dictated message text is never scanned: "text Mum saying
        // pay me back" is a message, not a payment. Only the action
        // part (before " saying ") is checked.
        financialHit(text.lowercase().substringBefore(" saying "))?.let { phrase ->
            return PlanResult.RefusedSensitive(
                reason = "That looks like a financial action ($phrase), " +
                    "so I can't do it for you — I can only guide you to it.",
                appName = null,
                guidancePlan = guidancePlan(request, record, goal),
            )
        }

        // Optimization (2026-11-11, David's authorization): "open my
        // <thing>" is deterministic — the rule-based in-app tap is the
        // correct plan, and the LLM call for it is pure latency (and
        // the LLM was the source of the whole-sentence steps in D-01).
        // The fast path is gated on the exact "open my " prefix: no
        // earlier rule-based branch (message / call / guide / console /
        // composite / search) can match that shape, so skipping the
        // model round-trip changes no outcome — it only starts the
        // plan faster. Anything else still goes to the LLM first.
        if (text.lowercase().startsWith("open my ")) {
            planInAppTap(request, record, text)?.let { return it }
        }

        // Session/state awareness (2026-11-11, David's device
        // evidence ~21:43 WAT + clarification ~21:44 WAT): a media
        // control command is deterministic — OBSERVE first, then
        // PLAY_PAUSE on the current screen. It NEVER reaches the LLM:
        // the model cannot see the screen, so it re-plans a fresh
        // play-search (launch + search + open the first result) and
        // restarts the song — the exact defect David reported, when
        // "pause" / "play" re-opened YouTube Music instead of
        // pausing/resuming. The OBSERVE_FIRST rule lives here at plan
        // time (this branch) and at dispatch time (the router taps
        // the control on the live tree).
        MediaControlPhrases.match(text)?.let { control ->
            return PlanResult.Planned(buildPlan(request, record, listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is playing",
                    critical = false),
                PlanStep(0, TaskStepKind.PLAY_PAUSE, "${if (control == MediaControl.PAUSE) "Pause" else "Play"} the video",
                    mapOf(PlanParams.PLAY_STATE to if (control == MediaControl.PAUSE) "pause" else "play")),
            )))
        }

        // LLM proposal first (Brahma: model plans, rules are the
        // fallback). Any failure → rule-based, never a raise.
        llmPlanJson?.let { source ->
            try {
                val raw = source(goal).trim()
                if (raw.isNotEmpty()) {
                    when (val parsed = validator.parsePlanJson(raw, request)) {
                        is PlanValidator.ParseOutcome.Valid -> {
                            val check = validator.validate(parsed.plan)
                            if (check is PlanValidator.ValidationResult.Valid) {
                                // The LLM's steps are used — but the
                                // model proposes sentences, not steps:
                                // a TAP target / description carrying
                                // David's whole sentence is sanitized
                                // to action + target here, before the
                                // plan can run (D-01/D-07). The RECORD
                                // stays the one resolved above — the
                                // model never rewrites what was
                                // understood from David's words.
                                // Device-failure fix (2026-11-13,
                                // F2) — the launch rule, enforced on
                                // the model path: scope-populate the
                                // plan (target package on the steps
                                // that act in it), prepend a launch
                                // whenever the plan is app-scoped,
                                // and REJECT the model plan —
                                // falling through to the rule-based
                                // planner, which asks "which app?"
                                // — when no launch can be proven
                                // before an app-scoped step. A model
                                // plan never runs against the wrong
                                // screen.
                                val repaired = maybePrependLaunch(
                                    populateStepScope(
                                        rewriteNavLaunchSteps(
                                            TaskTextSanitizer.sanitizePlan(
                                                check.plan.copy(record = record),
                                            ),
                                        ),
                                        record,
                                        goal,
                                    ),
                                    // Raw goal, not the sanitized
                                    // text: the app mention ("on
                                    // Facebook") is cut by the
                                    // sanitizer, and the launch
                                    // prepend needs to see it.
                                    goal,
                                    record,
                                )
                                val searchWithoutApp = repaired.steps.any {
                                    it.kind == TaskStepKind.SEARCH_IN_APP &&
                                        it.params[PlanParams.APP].isNullOrBlank() &&
                                        it.params[PlanParams.PACKAGE].isNullOrBlank()
                                } && record.app.isNullOrBlank() &&
                                    extractMentionedApp(goal).isNullOrBlank()
                                if (!searchWithoutApp &&
                                    validator.validateLaunchOrder(repaired)
                                        is PlanValidator.ValidationResult.Valid
                                ) {
                                    return PlanResult.Planned(repaired)
                                }
                                // else: fall through to rule-based.
                            }
                        }
                        is PlanValidator.ParseOutcome.Invalid -> Unit // fall through
                    }
                }
            } catch (_: Exception) {
                // Model failure → rule-based fallback, never raise.
            }
        }

        return ruleBasedPlan(request, text, record)
    }

    /**
     * Task-record fix (2026-11-07) — resolves the ONE record for this
     * planning call, in strict priority order:
     * 1. An app THIS goal names explicitly (composite first clause,
     *    search app, launch app) — the fresh instruction wins over
     *    any carried record. A new instruction supersedes the old.
     * 2. The carried record ([PlanRequest.taskRecord]) — a resumed
     *    bare follow-up ("play music") or the answer to the one app
     *    question. Adopted unchanged except the package is resolved
     *    when still missing; its instruction, app, query and
     *    provenance are never rewritten.
     * 3. The last-turn context app ([PlanRequest.contextApp]).
     * 4. No app anywhere — the record is created with app null, and
     *    the bare-play / bare-search branches ask exactly once.
     * The record's origin is the origin of the INSTRUCTION it was
     * created from — a resumed MODEL call keeps the DIRECT_USER
     * provenance of David's original words (the record describes
     * where the task came from). The cover gates on the PLAN's
     * origin (the request's origin), never the record's — so a
     * model-triggered resume of a user task still goes through its
     * card. See [PlanRequest.taskRecord].
     */
    private fun resolveRecord(request: PlanRequest, text: String): TaskRecord {
        val explicitApp = extractGoalApp(text)
        if (!explicitApp.isNullOrBlank()) {
            // D-01: "open my story" is a TAP on a thing, not a launch
            // of an app called "story" — a launch-app name that
            // resolves to no installed app after an "open my/the"
            // prefix is the thing to tap, never the record's app.
            // Letting it stand would also poison follow-ups ("story"
            // as the last-turn app). Fall through to the carried /
            // context app instead; the rule branches plan the tap.
            val openMyShape = text.lowercase().let {
                it.startsWith("open my ") || it.startsWith("open the ")
            }
            if (!(openMyShape && safeResolve(explicitApp) !is AppResolution.Found)) {
                return TaskRecord(
                    instruction = request.goal,
                    app = explicitApp,
                    appPackage = resolvePackage(explicitApp),
                    query = extractGoalQuery(text),
                    origin = request.origin,
                )
            }
            // D-01: the goal still names an app in a remark clause
            // ("now you're on Facebook") — that app is the record's
            // app: it is where the tap must land, what the replan
            // LAUNCH recovery needs, and what follow-ups resolve
            // from. Only a candidate that resolves counts; otherwise
            // fall through to the carried / context app as before.
            extractMentionedApp(request.goal)?.let { mentioned ->
                return TaskRecord(
                    instruction = request.goal,
                    app = mentioned,
                    appPackage = resolvePackage(mentioned),
                    query = extractGoalQuery(text),
                    origin = request.origin,
                )
            }
        }
        request.taskRecord?.let { carried ->
            // Adopted unchanged — except an app the carried record
            // lacks is filled from the last-turn context, so the
            // record always names the app the plan will actually use.
            val app = carried.app?.takeIf { it.isNotBlank() }
                ?: request.contextApp?.takeIf { it.isNotBlank() }
            val pkg = carried.appPackage
                ?: app?.let { resolvePackage(it) }
            return carried.copy(app = app, appPackage = pkg)
        }
        val ctxApp = request.contextApp?.takeIf { it.isNotBlank() }
        return TaskRecord(
            instruction = request.goal,
            app = ctxApp,
            appPackage = ctxApp?.let { resolvePackage(it) },
            query = extractGoalQuery(text),
            origin = request.origin,
        )
    }

    /** The app this goal text names explicitly, or null. */
    private fun extractGoalApp(text: String): String? {
        COMPOSITE_REGEX.find(text)?.let { m ->
            return m.groupValues[2].trim().ifBlank { null }
        }
        // Simple-task execution fix (2026-11-07): "play … on <app>"
        // names its app after "on" — "play me some music on YouTube"
        // → "YouTube". The old extractors could not see it: the
        // composite needs open/launch, extractSearchApp needs a
        // search utterance, extractLaunchApp needs an open prefix —
        // so the app came out blank and the bare-play branch asked
        // a question David had already answered in the sentence.
        extractPlayApp(text)?.takeIf { it.isNotBlank() }?.let { return it }
        // Find-words play shape: "find <song> on <app> and play it".
        extractFindApp(text)?.takeIf { it.isNotBlank() }?.let { return it }
        extractSearchApp(text).takeIf { it.isNotBlank() }?.let { return it }
        extractLaunchApp(text)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /**
     * The app of a "play … on <app>" goal, original casing, or null.
     * Only the LAST " on " splits — a song title containing "on"
     * ("Lean On") keeps its "on" because the app is what follows
     * the last one. A trailing composite join is cut the same way
     * as search apps ("YouTube and open the first result" →
     * "YouTube").
     */
    private fun extractPlayApp(text: String): String? {
        if (!isPlay(text)) return null
        val onIdx = text.lastIndexOfIc(" on ")
        if (onIdx < 0) return null
        val app = text.substring(onIdx + 4).trim().trimEnd('.', '!', '?').trim()
        if (app.isBlank()) return null
        return truncateCompositeTail(app).ifBlank { null }
    }

    /** The app of a "find <song> on <app> and play …" goal, or null. */
    private fun extractFindApp(text: String): String? {
        val m = FIND_AND_PLAY_REGEX.find(text) ?: return null
        return m.groupValues[2].trim().trimEnd('.', '!', '?').trim()
            .let { truncateCompositeTail(it) }.ifBlank { null }
    }

    /** The query of a "find <song> on <app> and play …" goal, or null. */
    private fun extractFindQuery(text: String): String? {
        val m = FIND_AND_PLAY_REGEX.find(text) ?: return null
        return m.groupValues[1].trim().trimEnd('.', '!', '?').trim().ifBlank { null }
    }

    /** The play/search query this goal text names, or null. */
    private fun extractGoalQuery(text: String): String? {
        COMPOSITE_REGEX.find(text)?.let { m ->
            if (m.groupValues[3].lowercase() == "play") {
                return extractPlayQuery(m.groupValues[4].trim())
            }
            return extractSearchQuery("search ${m.groupValues[4].trim()}")
                .takeIf { it.isNotBlank() }
        }
        if (isSearch(text)) {
            return extractSearchQuery(text).takeIf { it.isNotBlank() }
        }
        // Find-words play shape: the query is the "find <q>" part.
        extractFindQuery(text)?.let { return it }
        if (isPlay(text)) {
            val tail = text.substring(5).trim()
            return extractPlayQuery(tail)
        }
        return null
    }

    private fun resolvePackage(appName: String): String? =
        when (val r = safeResolve(appName)) {
            is AppResolution.Found -> r.packageName
            else -> null
        }

    /**
     * Rebuilds the plan for the REMAINING work only after a failure
     * (Brahma `planner.replan`): completed steps are never repeated.
     *
     * Simple-task execution fix (2026-11-07): the old shape DROPPED
     * the failed step and ran the tail blind — a TAP_TARGET after a
     * failed SEARCH_IN_APP could never succeed, a VERIFY_OUTCOME
     * after a failed TAP could never pass, and the run burned its
     * 2-replan budget stranding the plan. The rebuild is now
     * dependency-aware, in two passes:
     *  - First replan (replanCount == 0): the failed step is
     *    REPAIRED, not dropped — the rebuilt plan starts with a
     *    fresh OBSERVE_SCREEN, then the failed step itself (re-issued
     *    with its original params, so it gets fresh attempts with a
     *    fresh screen), then the remaining steps. A step's dependents
     *    only run after the step they depend on has had its repair.
     *  - Second replan (replanCount >= 1): the failed step has
     *    already had its repair and failed again, so it is abandoned
     *    — and every step that DEPENDS on it (see [dependsOn]) is
     *    dropped with it, transitively. A TAP_TARGET never runs after
     *    its SEARCH_IN_APP was abandoned; a VERIFY_OUTCOME never
     *    runs after the step it verifies was abandoned. When nothing
     *    independent remains, the replan refuses honestly instead of
     *    returning a plan that cannot complete.
     * Returns [PlanResult.Unsupported] when the replan budget
     * ([MAX_REPLANS]) is spent or nothing remains to do.
     */
    fun replan(
        plan: TaskPlan,
        failedStep: PlanStep,
        error: String,
        observation: ScreenModel?,
    ): PlanResult {
        if (plan.replanCount >= MAX_REPLANS) {
            return PlanResult.Unsupported(
                "I tried two different approaches and still got stuck at " +
                    "${failedStep.description}. I need your help to continue.",
            )
        }
        val remaining = plan.steps.filter { it.index > failedStep.index }
        val rebuilt = mutableListOf<PlanStep>()
        rebuilt.add(
            PlanStep(
                index = 0,
                kind = TaskStepKind.OBSERVE_SCREEN,
                description = "Look at the screen again to see where things are now",
                critical = false,
            ),
        )
        // BUILT-IN RECOVERY (2026-11-09, David's direction) — the
        // planner prefers a built-in recovery first and only falls
        // back to a model-planned replan (there is no model call
        // here; the fallback is the existing repair-first / drop
        // logic below). Both recoveries run on the FIRST replan
        // only (replanCount == 0): a recovery that fails is never
        // repeated — the second replan drops the step honestly
        // instead of looping forever. Both are ordinary plan steps
        // in the replanned plan, so they run under the same covered
        // plan path and AuthGate as every other step — no side path.
        //  - Wrong page (unexpected screen): GO_BACK to where I was,
        //    then re-issue the failed step. Skipped when the
        //    knowledge base records that a back recovery already
        //    failed on this screen.
        //  - Wrong search (a SEARCH_IN_APP that submitted but showed
        //    nothing, or whose submit never executed): tap the
        //    X / clear control, then re-issue the search (the search
        //    sequence re-types the query itself — ACTION_SET_TEXT
        //    replaces the field, so nothing stale survives). The
        //    clear step is included only when a clear control is
        //    actually known — on the failure-time screen, or in the
        //    knowledge base when that screen is unavailable — and it
        //    is non-critical, so a gone/ambiguous X skips instead of
        //    aborting the run (see the analyzer's recovery rule).
        val unexpectedScreen = error.contains("unexpected screen", ignoreCase = true)
        if (plan.replanCount == 0 && unexpectedScreen) {
            val backOk = observation?.let { knowledge.shouldAttemptBackRecovery(it) } ?: true
            if (backOk) {
                rebuilt.add(
                    PlanStep(
                        index = 0,
                        kind = TaskStepKind.GO_BACK,
                        description = "Go back to where I was — that's not the right screen",
                        critical = true,
                        params = mapOf(PlanParams.RECOVERY to PlanParams.RECOVERY_GO_BACK),
                    ),
                )
                rebuilt.add(failedStep)
                rebuilt.addAll(remaining)
                return plannedRebuild(plan, rebuilt)
            }
            // A back recovery already failed here: fall through to
            // the existing drop logic — do not repeat it.
        }
        if (plan.replanCount == 0 && failedStep.kind == TaskStepKind.SEARCH_IN_APP &&
            isWrongSearch(error)
        ) {
            if (clearControlKnown(observation)) {
                rebuilt.add(
                    PlanStep(
                        index = 0,
                        kind = TaskStepKind.TAP_TARGET,
                        description = "Clear the search and try again",
                        critical = false,
                        params = mapOf(
                            PlanParams.TARGET to "clear",
                            PlanParams.RECOVERY to PlanParams.RECOVERY_CLEAR_RETRY,
                        ),
                    ),
                )
                rebuilt.add(failedStep)
                rebuilt.addAll(remaining)
                return plannedRebuild(plan, rebuilt)
            }
            // No clear control known: fall through to the plain
            // repair-first retry below.
        }
        // Device-test failures (2026-11-11, D-01): a TAP_TARGET that
        // failed "target gone" while its app was not on screen ran on
        // the WRONG app's screen (LOOM's own chat, whose bubbles echo
        // the task words). First replan only: bring the recorded app
        // to the foreground, then re-issue the tap — re-issuing the
        // same tap on the same wrong screen would fail identically.
        // The guard is the executor's wrong-app failure (see
        // TaskPlanExecutor.drivePointerForStep): its reason carries
        // "target gone", so this branch fires exactly for it.
        if (plan.replanCount == 0 && failedStep.kind == TaskStepKind.TAP_TARGET &&
            error.contains("target gone", ignoreCase = true)
        ) {
            val pkg = failedStep.params[PlanParams.PACKAGE]
                ?: plan.record?.appPackage
            val appName = plan.record?.app
            if (!pkg.isNullOrBlank() && !appName.isNullOrBlank() &&
                observation?.packageName != pkg
            ) {
                rebuilt.add(
                    PlanStep(
                        index = 0,
                        kind = TaskStepKind.LAUNCH_APP,
                        description = "Open $appName first — the tap ran on the wrong screen",
                        critical = true,
                        params = mapOf(
                            PlanParams.APP to appName,
                            PlanParams.PACKAGE to pkg,
                        ),
                    ),
                )
                rebuilt.add(
                    PlanStep(
                        index = 0,
                        kind = TaskStepKind.WAIT_FOR_APP,
                        description = "Wait for $appName to come to the foreground",
                        critical = true,
                        params = mapOf(
                            PlanParams.APP to appName,
                            PlanParams.PACKAGE to pkg,
                        ),
                    ),
                )
                rebuilt.add(failedStep)
                rebuilt.addAll(remaining)
                return plannedRebuild(plan, rebuilt)
            }
            // No recorded app, or already on it: fall through to the
            // plain repair-first retry below.
        }
        // No repair for an unexpected screen: the world is not what
        // the step assumed (wrong app, a dialog showing), so
        // re-issuing the same step against the same screen would fail
        // identically — the tail is rebuilt from the new screen
        // instead, with the failed step and its dependents dropped.
        // Every other failure repairs first (see below).
        if (plan.replanCount == 0 && !unexpectedScreen) {
            // Repair-first: re-issue the failed step itself, then the tail.
            rebuilt.add(failedStep)
            rebuilt.addAll(remaining)
        } else {
            // Abandon the failed step AND its dependents (transitive):
            // no step runs whose precondition step was abandoned.
            val abandoned = mutableSetOf(failedStep.kind)
            var changed: Boolean
            do {
                changed = false
                for (step in remaining) {
                    if (step.kind !in abandoned &&
                        dependsOn(step.kind).any { it in abandoned }
                    ) {
                        abandoned.add(step.kind)
                        changed = true
                    }
                }
            } while (changed)
            val kept = remaining.filter { it.kind !in abandoned }
            if (kept.isEmpty()) {
                return PlanResult.Unsupported(
                    "There is nothing left to try after ${failedStep.description} failed: $error " +
                        "The steps after it depended on it, so they were dropped too.",
                )
            }
            // Universal Console (2026-11-07): a rebuild that kept
            // only observation steps refuses honestly — an
            // OBSERVE-only plan would "complete" on readable screens
            // without doing the task. A kept VERIFY_OUTCOME is real
            // remaining work (it re-checks an outcome whose search
            // already verified in this run), so it passes the guard.
            // The dropped step's dependents are named in the reason,
            // as above.
            if (kept.none { it.kind in GOAL_ADVANCING_KINDS || it.kind == TaskStepKind.VERIFY_OUTCOME }) {
                return PlanResult.Unsupported(
                    "There is nothing left to try after ${failedStep.description} failed: $error " +
                        "The steps after it depended on it, so they were dropped too, " +
                        "and only looking-at-the-screen steps remain — looking is not doing the task.",
                )
            }
            rebuilt.addAll(kept)
        }
        return plannedRebuild(plan, rebuilt)
    }

    /**
     * Re-indexes a rebuilt step list and returns it as the replanned
     * plan (same id, same record, replanCount + 1).
     */
    private fun plannedRebuild(plan: TaskPlan, rebuilt: List<PlanStep>): PlanResult.Planned {
        val steps = rebuilt.mapIndexed { i, s -> s.copy(index = i) }
        // Task-record fix: plan.copy carries the SAME record forward
        // (instruction, app, package, query, origin, status) — a
        // replan rebuilds the remaining STEPS, never the task. The
        // record survives here by construction; TaskPlannerTest §record
        // proves it, and the executor test proves the replanned run
        // still dispatches with the recorded app.
        return PlanResult.Planned(
            plan.copy(steps = steps, status = PlanStatus.PLANNING, replanCount = plan.replanCount + 1),
        )
    }

    /**
     * Whether [error] is a wrong-search failure: the submit never
     * executed, or the search ran but showed nothing ("no
     * confirming observation" from verification). A keyboard that
     * is still open is NOT a wrong search — the field is focused
     * and the dismiss-and-retry path owns that failure.
     */
    private fun isWrongSearch(error: String): Boolean {
        if (error.contains("keyboard still open", ignoreCase = true)) return false
        return error.contains("submit not executed", ignoreCase = true) ||
            error.contains("no confirming observation", ignoreCase = true)
    }

    /**
     * Whether a clear control is known for the failed search: on
     * the failure-time screen when one is available (exactly one
     * X / clear match — an ambiguous screen never grounds a tap),
     * otherwise in the knowledge base (a past observation of the
     * same screen — the field may have lost focus since, so the X
     * is not on this screen but the search box still holds the
     * wrong query; the TAP_TARGET re-resolves fresh at dispatch
     * and skips honestly if the X is really gone). No observation
     * at all means no key to consult the base with — plain retry.
     */
    private fun clearControlKnown(observation: ScreenModel?): Boolean {
        if (observation == null) return false
        val matches = observation.elements.filter {
            TargetAliases.matchesTargetQuery(it, "clear")
        }
        if (matches.size == 1) return true
        if (matches.size > 1) return false
        return knowledge.clearControlFor(observation) != null
    }

    // ---------- rule-based decomposition ----------

    private fun ruleBasedPlan(request: PlanRequest, text: String, record: TaskRecord): PlanResult {
        // Dictated messaging — before open/search (distinct verbs).
        messageShape(text)?.let { shape ->
            return planMessage(request, record, shape)
        }
        // Message-shaped but no channel named ("send Mum a message
        // saying hi"): genuinely ambiguous, ask which app.
        if (isChannelLessMessage(text)) {
            return PlanResult.ClarificationNeeded(
                "Which app should I send that in — WhatsApp or SMS?", "channel",
            )
        }
        // Calls.
        if (isCall(text)) {
            val target = extractCallTarget(request.goal)
            if (target.isBlank()) {
                return PlanResult.ClarificationNeeded("Who should I call?", "target")
            }
            return PlanResult.Planned(
                buildPlan(
                    request,
                    record,
                    listOf(
                        PlanStep(0, TaskStepKind.CALL_PLACE, "Call $target",
                            mapOf(PlanParams.RECIPIENT to target)),
                        PlanStep(0, TaskStepKind.VERIFY_OUTCOME, "Check that the call to $target was placed",
                            mapOf(PlanParams.EXPECTED to "call placed to $target"), critical = false),
                    ),
                ),
            )
        }
        // Guidance.
        extractGuideTarget(text)?.let { target ->
            if (target.isBlank()) {
                return PlanResult.ClarificationNeeded("What should I guide you to?", "target")
            }
            return PlanResult.Planned(
                buildPlan(
                    request,
                    record,
                    listOf(
                        PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see where you are",
                            critical = false),
                        PlanStep(0, TaskStepKind.GUIDE_TO, "Guide you to $target",
                            mapOf(PlanParams.GUIDE_TARGET to target)),
                        PlanStep(0, TaskStepKind.REPORT, "Report that the guidance to $target is done",
                            critical = false),
                    ),
                ),
            )
        }
        if (isGuidePhraseWithoutTarget(text)) {
            return PlanResult.ClarificationNeeded("What should I guide you to?", "target")
        }
        // Pointer move.
        if (isPointerMoveUtterance(text)) {
            val direction = extractPointerDirection(text)
                ?: return PlanResult.ClarificationNeeded(
                    "Which way should I move the pointer — left, right, up, or down?",
                    "direction",
                )
            return PlanResult.Planned(
                buildPlan(
                    request,
                    record,
                    listOf(
                        PlanStep(0, TaskStepKind.POINTER_MOVE, "Move the pointer $direction",
                            mapOf(PlanParams.DIRECTION to direction)),
                        PlanStep(0, TaskStepKind.VERIFY_OUTCOME, "Check that the pointer moved $direction",
                            mapOf(PlanParams.EXPECTED to "pointer moved $direction"), critical = false),
                    ),
                ),
            )
        }
        // Universal Console (2026-11-07) — direct console
        // operations on the current screen. These run before the
        // open/search branches: "open the menu" must reach OPEN_MENU,
        // never the launch branch (which would try to open an app
        // called "the menu"), and "swipe left" is a gesture, never a
        // scroll or a pointer move. "select <x>" / "tap <x>" is the
        // plan-level select: TAP_TARGET — the same tap the search
        // sequence uses for a result, exposed as a step. An explicit
        // "first" in the utterance adds MATCH=first (user-directed);
        // a bare tap carries no match hint and refuses on ambiguity.
        extractConsoleOp(text)?.let { op ->
            return PlanResult.Planned(buildPlan(request, record, op))
        }
        // A swipe with no direction asks which way — never guesses.
        if (text.lowercase().trim() == "swipe" || text.lowercase().startsWith("swipe ")) {
            return PlanResult.ClarificationNeeded(
                "Which way should I swipe — left, right, up, or down?", "direction",
            )
        }
        // Composite: "open <app> and play/search ..." — the Davido
        // case falls out of this general pattern.
        COMPOSITE_REGEX.find(text)?.let { m ->
            val appName = m.groupValues[2].trim()
            val tailVerb = m.groupValues[3].lowercase()
            val tail = m.groupValues[4].trim()
            checkApp(appName, request, record)?.let { return it }
            val openSteps = openSteps(appName)
            val tailSteps = if (tailVerb == "play") {
                playSteps(tail, appName) ?: return PlanResult.ClarificationNeeded(
                    "What should I play?", "query",
                )
            } else {
                searchSteps("search $tail", request, record, appOverride = appName, includeOpen = false)
                    ?: return PlanResult.ClarificationNeeded(
                        "What should I search for?", "query",
                    )
            }
            return PlanResult.Planned(buildPlan(request, record, openSteps + tailSteps))
        }
        // In-app search.
        if (isSearch(text)) {
            return planSearch(request, text, record)
        }
        // Device-test failures (2026-11-11, D-01): "open my story"
        // is a TAP on the thing named, not a launch of an app called
        // "story". The launch branch below would carry the whole tail
        // as the app name and the tap would never happen. This branch
        // runs before it: when the named thing is not a resolvable
        // app, plan a tap on it — bringing the mentioned app
        // (Facebook) to the foreground first when one is named.
        planInAppTap(request, record, text)?.let { return it }
        // NAV-SCREEN fix (2026-11-11, N1): "go to / take me to /
        // switch to <destination>" — catalogue-first in-app
        // navigation, before the launch branch can misroute it.
        planNavigateTab(request, record, text)?.let { return it }
        // Open / launch.
        extractLaunchApp(text, goTo = true)?.let { appName ->
            if (appName.isBlank()) {
                return PlanResult.ClarificationNeeded("Which app do you want me to open?", "app")
            }
            checkApp(appName, request, record)?.let { return it }
            return PlanResult.Planned(buildPlan(request, record, openSteps(appName)))
        }
        if (isBareOpen(text)) {
            return PlanResult.ClarificationNeeded("Which app do you want me to open?", "app")
        }
        // Find-words play shape: "find <song> on <app> and play it"
        // — the same play plan as "play <song> on <app>", in
        // find-words. The app comes from the record (extractGoalApp
        // above already found it); without an app it asks once.
        extractFindQuery(text)?.let { query ->
            val app = record.app?.takeIf { it.isNotBlank() }
                ?: request.contextApp?.takeIf { it.isNotBlank() }
            if (app.isNullOrBlank()) {
                if (record.askedApp) {
                    return PlanResult.Unsupported(
                        "I still don't know which app to play that in — " +
                            "I asked once already. Say the app name and " +
                            "I'll carry on from there.",
                    )
                }
                return PlanResult.ClarificationNeeded(
                    "Which app should I play that in — YouTube Music or YouTube?", "app",
                )
            }
            checkApp(app, request, record)?.let { return it }
            val steps = openSteps(app) +
                (playSteps(query, app)
                    ?: return PlanResult.ClarificationNeeded(
                        "What should I play?", "query",
                    ))
            return PlanResult.Planned(buildPlan(request, record, steps))
        }
        // Bare play with no app in the text: YouTube vs YouTube Music
        // is a real choice — ask, never guess — BUT ONLY when the
        // record has no app either. Task-record fix (2026-11-07):
        // the Nov 6 failure asked this question AFTER the app was
        // already named, because the plan was re-derived from the
        // bare tail with no record. The check below reads the record
        // FIRST: a filled slot is used silently, exactly like the
        // composite above (open the recorded app, then play).
        // Genuinely missing (no record, no context) still asks —
        // exactly once: a record that already asked ([TaskRecord.
        // askedApp]) fails honestly instead of asking twice.
        if (isPlay(text)) {
            val app = record.app?.takeIf { it.isNotBlank() }
                ?: request.contextApp?.takeIf { it.isNotBlank() }
            if (!app.isNullOrBlank()) {
                val tail = text.substring(5).trim()
                val steps = openSteps(app) +
                    (playSteps(tail, app)
                        ?: return PlanResult.ClarificationNeeded(
                            "What should I play?", "query",
                        ))
                return PlanResult.Planned(buildPlan(request, record, steps))
            }
            if (record.askedApp) {
                return PlanResult.Unsupported(
                    "I still don't know which app to play that in — " +
                        "I asked once already. Say the app name and " +
                        "I'll carry on from there.",
                )
            }
            return PlanResult.ClarificationNeeded(
                "Which app should I play that in — YouTube Music or YouTube?", "app",
            )
        }
        return PlanResult.Unsupported(
            "I don't know how to do that yet: ${request.goal}",
        )
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N1) — catalogue-first repair for
     * MODEL-proposed plans. The model may propose a LAUNCH_APP step
     * for a destination ("open Reels" → launch "Reels"); the model
     * proposes, the app disposes. A launch step whose app is a known
     * in-app destination and NOT a resolvable installed app is
     * rewritten to NAVIGATE_TAB (TARGET = the destination) before
     * the plan can run — so the model path can never again fail as
     * "I couldn't find an app called 'reels'". A real app launch is
     * untouched. Runs after sanitizing, before the launch prepend
     * (which then brings the mentioned app up for the nav step).
     */
    private fun rewriteNavLaunchSteps(plan: TaskPlan): TaskPlan {
        if (plan.steps.none { it.kind == TaskStepKind.LAUNCH_APP }) return plan
        var changed = false
        val steps = plan.steps.map { step ->
            if (step.kind != TaskStepKind.LAUNCH_APP) return@map step
            val app = step.params[PlanParams.APP].orEmpty()
            if (app.isBlank()) return@map step
            if (!NavigationResolver.isKnownDestination(app)) return@map step
            if (safeResolve(app) is AppResolution.Found) return@map step
            changed = true
            step.copy(
                kind = TaskStepKind.NAVIGATE_TAB,
                description = "Go to $app",
                params = mapOf(PlanParams.TARGET to app) +
                    (step.params[PlanParams.PACKAGE]?.let { mapOf(PlanParams.PACKAGE to it) }.orEmpty()),
            )
        }
        return if (changed) plan.copy(steps = steps) else plan
    }

    /**
     * Device-test failures (2026-11-11, D-01) — "open my story":
     * the model proposes a tap in an app that is not on screen, with
     * no LAUNCH step to bring it up (the plan ran while LOOM's own
     * chat was foreground, so the tap resolved against chat bubbles).
     * When the goal names an app and the plan has a tap/guide step
     * but no launch, prepend the open sequence for that app. A
     * sensitive app is never launched — the plan falls back to rules
     * instead, where the sensitive gate refuses it properly.
     */
    /**
     * Device-failure fix (2026-11-13, F2) — widened: a launch is
     * prepended whenever the plan is app-scoped (any step acts in
     * a named app, or a SEARCH step exists and an app can be
     * determined from the goal/record), REGARDLESS of step kinds.
     * The old tap/guide/nav-only trigger let a model SEARCH_IN_APP
     * plan run against whatever screen was foreground and verify
     * on it. Plans that act on the current screen (media controls,
     * follow-ups naming no app) are left untouched. Sensitive apps
     * never get a launch prepended (guidance-only, absolute).
     */
    private fun maybePrependLaunch(plan: TaskPlan, rawGoal: String, record: TaskRecord?): TaskPlan {
        if (plan.steps.any { it.kind == TaskStepKind.LAUNCH_APP }) return plan
        val appScoped = plan.steps.any { step ->
            step.kind in PlanValidator.APP_SCOPED_KINDS &&
                (!step.params[PlanParams.APP].isNullOrBlank() ||
                    !step.params[PlanParams.PACKAGE].isNullOrBlank())
        } || plan.steps.any { step ->
            step.kind == TaskStepKind.SEARCH_IN_APP &&
                (!record?.app.isNullOrBlank() || extractMentionedApp(rawGoal) != null)
        }
        if (!appScoped) return plan
        val app = extractMentionedApp(rawGoal)
            ?: record?.app?.takeIf { it.isNotBlank() }
            ?: plan.steps.firstNotNullOfOrNull {
                it.params[PlanParams.APP]?.takeIf { a -> a.isNotBlank() }
            }
            ?: return plan
        if (ScreenDataPolicy.isSensitivePackage(app)) return plan
        val steps = (openSteps(app) + plan.steps).mapIndexed { i, s -> s.copy(index = i) }
        return plan.copy(steps = steps)
    }

    /**
     * Device-failure fix (2026-11-13, F2/F4) — populates step
     * scope: steps that act inside the target app carry its
     * PACKAGE (and SEARCH_IN_APP also its APP label) when the
     * model left them blank, so package-gated verification and
     * scoped token evaluation can work. The package comes from
     * the record, an existing step param, or the named app's
     * resolution — never invented. PLAY_PAUSE is deliberately
     * excluded: media controls act on the current screen, and
     * stamping a package onto them would force a relaunch of the
     * app that is already playing. Steps that already carry a
     * PACKAGE are never overwritten.
     */
    private fun populateStepScope(plan: TaskPlan, record: TaskRecord?, rawGoal: String): TaskPlan {
        val appLabel = record?.app?.takeIf { it.isNotBlank() }
            ?: extractMentionedApp(rawGoal)
            ?: plan.steps.firstNotNullOfOrNull {
                it.params[PlanParams.APP]?.takeIf { a -> a.isNotBlank() }
            }
        val pkg = record?.appPackage?.takeIf { it.isNotBlank() }
            ?: plan.steps.firstNotNullOfOrNull {
                it.params[PlanParams.PACKAGE]?.takeIf { p -> p.isNotBlank() }
            }
            ?: appLabel?.let { resolvePackage(it) }
        if (pkg.isNullOrBlank() && appLabel.isNullOrBlank()) return plan
        val scoped = plan.steps.map { step ->
            if (step.kind !in SCOPE_STAMP_KINDS) return@map step
            var params = step.params
            if (!pkg.isNullOrBlank() && params[PlanParams.PACKAGE].isNullOrBlank() &&
                PlanValidator.ALLOWED_PARAMS[step.kind]?.contains(PlanParams.PACKAGE) == true
            ) {
                params = params + (PlanParams.PACKAGE to pkg)
            }
            if (step.kind == TaskStepKind.SEARCH_IN_APP && !appLabel.isNullOrBlank() &&
                params[PlanParams.APP].isNullOrBlank()
            ) {
                params = params + (PlanParams.APP to appLabel)
            }
            if (params == step.params) step else step.copy(params = params)
        }
        return if (scoped == plan.steps) plan else plan.copy(steps = scoped)
    }

    /**
     * Device-test failures (2026-11-11, D-01) — "open my story" /
     * "open my profile": the thing after "open my/the" is a target
     * on the current screen, not an app to launch. Returns a plan
     * (look, then open the mentioned app if one is named, then tap
     * the thing) when the named thing is NOT a resolvable app, else
     * null so the launch branch owns it ("open my Youtube" stays a
     * launch). The tap step carries the app's PACKAGE so the
     * executor's wrong-app guard can refuse a tap that would run on
     * the wrong screen instead of tapping chat echoes. No
     * VERIFY_OUTCOME step: a story viewer need not contain the word
     * "story" afterwards — the tap's own screen-change verification
     * is the proof, and a weak verify would fail a good tap.
     */
    private fun planInAppTap(
        request: PlanRequest,
        record: TaskRecord,
        text: String,
    ): PlanResult? {
        val lower = text.lowercase()
        if (!lower.startsWith("open my ") && !lower.startsWith("open the ") &&
            !lower.startsWith("open ")
        ) return null
        // The app mention is read from the RAW goal: the sanitized
        // text already cut the "now you're on Facebook" clause that
        // names it.
        val app = extractMentionedApp(request.goal)
        // Keep "my story" as the target, not "story": the perspective
        // rule matches "my story" to the "Your story" tile, and the
        // bare word is less specific. The verb prefix stripped here
        // is only "open " — "my "/"the " stay on the target.
        val rawTarget = text.substring("open ".length).trim()
        var target = TaskTextSanitizer.sanitizeTarget(rawTarget)
        // NAV-SCREEN fix (2026-11-11, N1): "open Reels on Facebook"
        // names the app AFTER the target — strip the " on <app>" /
        // " in <app>" tail so the target is the destination
        // ("Reels"), not "Reels on Facebook", and catalogue-first
        // routing can see it.
        target = stripAppMention(target, app)
        if (target.isBlank()) return null
        // A resolvable app name is a launch, never a tap — checked
        // both with and without the possessive/article, because the
        // resolver does not match "my Youtube" to YouTube (whole-word
        // phrase match), while the bare "Youtube" resolves.
        if (safeResolve(target) is AppResolution.Found) return null
        val bareTarget = target.removePrefix("my ").removePrefix("the ").trim()
        if (bareTarget != target && safeResolve(bareTarget) is AppResolution.Found) return null
        // A name that is ambiguous ("Music") or sensitive ("GTBank")
        // is an APP, not a thing to tap — let the launch branch below
        // ask which one (ambiguous) or refuse it properly (sensitive).
        // Without this the tap branch swallows both: "open Music"
        // planned a tap instead of clarifying, "open GTBank" planned
        // a tap instead of refusing. The resolver is the authority
        // for ambiguity; the shared sensitive classifier is the
        // authority for sensitivity.
        if (safeResolve(target) is AppResolution.Ambiguous) return null
        if (bareTarget != target && safeResolve(bareTarget) is AppResolution.Ambiguous) return null
        if (ScreenDataPolicy.isSensitivePackage(target) ||
            ScreenDataPolicy.isSensitivePackage(bareTarget)
        ) return null
        if (app != null) {
            checkApp(app, request, record)?.let { return it }
        }
        val tapParams = mutableMapOf(PlanParams.TARGET to target)
        app?.let { resolvePackage(it)?.let { pkg -> tapParams[PlanParams.PACKAGE] = pkg } }
        val open = if (app != null) openSteps(app) else emptyList()
        // NAV-SCREEN fix (2026-11-11, N1): catalogue-first routing.
        // A destination that names in-app navigation ("Reels",
        // "Notifications", "the menu") is a NAVIGATE_TAB step, never
        // a launch and never a bare TAP_TARGET — the nav resolver
        // (label → synonym → ordinal → positional) and the nav
        // verifier own it. Anything else stays the tap it was.
        val isNavDestination = NavigationResolver.isKnownDestination(target)
        val actionKind = if (isNavDestination) TaskStepKind.NAVIGATE_TAB else TaskStepKind.TAP_TARGET
        val actionDescription = if (isNavDestination) "Go to $target" else "Tap $target"
        val steps = listOf(
            PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                critical = false),
        ) + open + listOf(
            PlanStep(0, actionKind, actionDescription, tapParams),
        )
        return PlanResult.Planned(buildPlan(request, record, steps))
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N1) — "go to / take me to /
     * switch to <destination>": catalogue-first routing for the
     * go-to phrasing. The destination is an installed app → null,
     * so the launch branch owns it ("go to YouTube" stays a launch).
     * Otherwise, when the destination is a known in-app destination
     * or the record/context names the app it lives in, the plan is
     * OBSERVE + NAVIGATE_TAB (with the app opened first when one is
     * named) — "go to Reels" never again becomes "open reels" and
     * never fails as "couldn't find an app called 'reels'".
     */
    private fun planNavigateTab(
        request: PlanRequest,
        record: TaskRecord,
        text: String,
    ): PlanResult? {
        val lower = text.lowercase()
        val prefix = listOf("take me to ", "go to ", "switch to ").firstOrNull { lower.startsWith(it) }
            ?: return null
        val rawTarget = text.substring(prefix.length).trim().trimEnd('.', '!', '?').trim()
        var target = TaskTextSanitizer.sanitizeTarget(rawTarget)
        if (target.isBlank()) return null
        // An installed app is a launch, never navigation.
        if (safeResolve(target) is AppResolution.Found) return null
        val bareTarget = target.removePrefix("my ").removePrefix("the ").trim()
        if (bareTarget != target && safeResolve(bareTarget) is AppResolution.Found) return null
        if (safeResolve(target) is AppResolution.Ambiguous) return null
        if (bareTarget != target && safeResolve(bareTarget) is AppResolution.Ambiguous) return null
        if (ScreenDataPolicy.isSensitivePackage(target) ||
            ScreenDataPolicy.isSensitivePackage(bareTarget)
        ) return null
        val app = extractMentionedApp(request.goal)
            ?: record.app?.takeIf { it.isNotBlank() }
            ?: request.contextApp?.takeIf { it.isNotBlank() }
        // NAV-SCREEN fix (2026-11-11, N1): same app-suffix strip
        // as planInAppTap — "go to Reels on Facebook" navigates to
        // "Reels" in Facebook, not to a target called
        // "Reels on Facebook" that no resolver can match.
        target = stripAppMention(target, app)
        if (target.isBlank()) return null
        // Without an app and without a known destination there is
        // nothing to navigate inside — let the launch branch ask.
        if (app == null && !NavigationResolver.isKnownDestination(target)) return null
        if (app != null) {
            checkApp(app, request, record)?.let { return it }
        }
        val params = mutableMapOf(PlanParams.TARGET to target)
        app?.let { resolvePackage(it)?.let { pkg -> params[PlanParams.PACKAGE] = pkg } }
        val open = if (app != null) openSteps(app) else emptyList()
        val steps = listOf(
            PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                critical = false),
        ) + open + listOf(
            PlanStep(0, TaskStepKind.NAVIGATE_TAB, "Go to $target", params),
        )
        return PlanResult.Planned(buildPlan(request, record, steps))
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N1) — strips a trailing app
     * mention from a target: "Reels on Facebook" → "Reels" when
     * [app] is "Facebook" (likewise " in <app>"). Case-insensitive
     * on the marker; the target's own casing is preserved. Returns
     * the target unchanged when there is no app or no such suffix.
     */
    private fun stripAppMention(target: String, app: String?): String {
        if (app.isNullOrBlank()) return target
        val lower = target.lowercase()
        for (marker in listOf(" on ${app.lowercase()}", " in ${app.lowercase()}")) {
            if (lower.endsWith(marker)) {
                return target.substring(0, target.length - marker.length).trim()
            }
        }
        return target
    }

    /**
     * The app a sentence MENTIONS anywhere ("now you're on Facebook",
     * "search for X on YouTube", "open my story on Facebook") — the
     * resolver is the authority: a candidate only counts when it
     * resolves to an installed app. Candidates come from "on/in"
     * phrases first, then bare app names. Never a guess: null when
     * nothing resolves.
     */
    private fun extractMentionedApp(text: String): String? {
        val lower = text.lowercase()
        val candidates = mutableListOf<String>()
        for (marker in listOf("you're on ", "you are on ", " on ", " in ")) {
            var idx = lower.indexOf(marker)
            while (idx >= 0) {
                val tail = text.substring(idx + marker.length).trim()
                    .trimEnd('.', '!', '?').trim()
                if (tail.isNotBlank()) {
                    val words = tail.split(Regex("\\s+"))
                    candidates.add(words.take(1).joinToString(" "))
                    if (words.size >= 2) candidates.add(words.take(2).joinToString(" "))
                }
                idx = lower.indexOf(marker, idx + 1)
            }
        }
        for (name in listOf(
            "facebook", "youtube music", "youtube", "whatsapp",
            "chrome", "instagram", "settings", "gmail", "maps",
        )) {
            if (lower.contains(name)) candidates.add(name)
        }
        for (candidate in candidates) {
            if (safeResolve(candidate) is AppResolution.Found) return candidate
        }
        return null
    }

    // ---------- per-pattern builders ----------

    private data class MessageShape(val channel: String, val target: String, val text: String)

    /** A message request of any completeness, or null when this is not
     *  a message utterance at all. */
    private fun messageShape(text: String): MessageShape? {
        val lower = text.lowercase()
        val channel = when {
            lower.contains("whatsapp") -> "whatsapp"
            lower.contains("sms") || lower.contains("text message") ||
                clean(lower).startsWith("text ") -> "sms"
            else -> null
        } ?: return null
        val messageText = extractMessageText(text)
        val target = extractMessageTarget(text)
        return MessageShape(channel, target, messageText)
    }

    private fun planMessage(request: PlanRequest, record: TaskRecord, shape: MessageShape): PlanResult {
        if (shape.target.isBlank()) {
            return PlanResult.ClarificationNeeded(
                "Who should I message, and what should I say?", "recipient",
            )
        }
        if (shape.text.isBlank()) {
            return PlanResult.ClarificationNeeded(
                "What should I say to ${shape.target}?", "messageText",
            )
        }
        val appLabel = if (shape.channel == "whatsapp") "WhatsApp" else "Messages"
        checkApp(appLabel, request, record)?.let { return it }
        return PlanResult.Planned(
            buildPlan(
                request,
                record,
                openSteps(appLabel) + listOf(
                    PlanStep(
                        0, TaskStepKind.MESSAGE_SEND,
                        "Send the message to ${shape.target}",
                        mapOf(
                            PlanParams.RECIPIENT to shape.target,
                            PlanParams.MESSAGE to shape.text,
                            PlanParams.CHANNEL to shape.channel,
                        ),
                    ),
                    PlanStep(
                        0, TaskStepKind.VERIFY_OUTCOME,
                        "Check that the message to ${shape.target} was sent",
                        mapOf(PlanParams.EXPECTED to "message sent to ${shape.target}"),
                        critical = false,
                    ),
                ),
            ),
        )
    }

    private fun planSearch(request: PlanRequest, text: String, record: TaskRecord): PlanResult {
        val query = extractSearchQuery(text)
        if (query.isBlank()) {
            return PlanResult.ClarificationNeeded("What do you want me to search for?", "query")
        }
        // Task-record fix: the record's app wins over the context app
        // (same slot, one authority); the text's own app already won
        // inside resolveRecord. Only a genuinely missing app asks.
        val app = record.app?.takeIf { it.isNotBlank() }
            ?: extractSearchApp(text).ifBlank { request.contextApp ?: "" }
        if (app.isBlank()) {
            if (record.askedApp) {
                return PlanResult.Unsupported(
                    "I still don't know which app to search in — " +
                        "I asked once already. Say the app name and " +
                        "I'll carry on from there.",
                )
            }
            return PlanResult.ClarificationNeeded("Which app should I search in?", "app")
        }
        checkApp(app, request, record)?.let { return it }
        val steps = searchSteps(text, request, record)
            ?: return PlanResult.ClarificationNeeded("What do you want me to search for?", "query")
        return PlanResult.Planned(buildPlan(request, record, steps))
    }

    /** Full in-app search sequence: launch → wait → observe → search
     *  → observe → verify. The SEARCH_IN_APP step hands off to the
     *  SearchExecutor sequence, which does its own affordance / type /
     *  submit / select / verify internally. [appOverride] is the app
     *  the composite already opened — the search text need not name
     *  it again. */
    private fun searchSteps(
        text: String,
        request: PlanRequest,
        record: TaskRecord,
        appOverride: String? = null,
        includeOpen: Boolean = true,
    ): List<PlanStep>? {
        val query = extractSearchQuery(text)
        if (query.isBlank()) return null
        val app = appOverride?.ifBlank { null }
            ?: record.app?.takeIf { it.isNotBlank() }
            ?: extractSearchApp(text).ifBlank { request.contextApp ?: "" }
        if (app.isBlank()) return null
        val open = if (includeOpen) openSteps(app) else emptyList()
        // Simple-task execution fix (2026-11-07): the package travels
        // with the search and verify steps (see playSteps) — the
        // executor's submit-order lookup and the WAIT/VERIFY package
        // checks read it from the step, never from a stale context.
        val pkg = resolvePackage(app)
        val searchParams = mutableMapOf(PlanParams.APP to app, PlanParams.QUERY to query)
        pkg?.let { searchParams[PlanParams.PACKAGE] = it }
        // Universal Console (2026-11-07): a plain search STOPS at
        // the verified results list — it never opens a result.
        searchParams[PlanParams.SEARCH_GOAL] = PlanParams.SEARCH_GOAL_SHOW_RESULTS
        val verifyParams = mutableMapOf(PlanParams.EXPECTED to "results for $query")
        pkg?.let { verifyParams[PlanParams.PACKAGE] = it }
        return open + listOf(
            PlanStep(
                0, TaskStepKind.SEARCH_IN_APP, "Search for $query in $app",
                searchParams,
            ),
            PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the search results",
                critical = false),
            PlanStep(
                0, TaskStepKind.VERIFY_OUTCOME, "Check that results for $query appeared",
                verifyParams, critical = false,
            ),
        )
    }

    /** "play ..." tail: search the query, open the first result,
     *  verify playback.
     *
     *  Universal Console (2026-11-07): the search sequence itself
     *  opens the first result (SEARCH_GOAL open_first, verified
     *  landing inside the sequence) — there is deliberately NO
     *  TAP_TARGET step after it any more. The old plan tapped the
     *  query text a SECOND time on the already-opened player/detail
     *  screen: a duplicate action that could pause, re-open, or tap
     *  the wrong row. The search step's goal param is what carries
     *  "open the result" now, not a tap. */
    private fun playSteps(tail: String, app: String? = null): List<PlanStep>? {
        val query = extractPlayQuery(tail) ?: return null
        if (query.isBlank()) return null
        val searchParams = mutableMapOf(PlanParams.QUERY to query)
        if (!app.isNullOrBlank()) {
            searchParams[PlanParams.APP] = app
            // Simple-task execution fix (2026-11-07): the package
            // travels with the step — without it the executor's
            // submit-order resolution fell back to the default order
            // and the learner recorded wins against a blank package.
            resolvePackage(app)?.let { searchParams[PlanParams.PACKAGE] = it }
        }
        searchParams[PlanParams.SEARCH_GOAL] = PlanParams.SEARCH_GOAL_OPEN_FIRST
        val verifyParams = mutableMapOf(PlanParams.EXPECTED to "playing $query")
        if (!app.isNullOrBlank()) {
            resolvePackage(app)?.let { verifyParams[PlanParams.PACKAGE] = it }
        }
        return listOf(
            PlanStep(
                0, TaskStepKind.SEARCH_IN_APP, "Search for $query and open the first result",
                searchParams,
            ),
            PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the opened result",
                critical = false),
            PlanStep(
                0, TaskStepKind.VERIFY_OUTCOME, "Check that $query is playing",
                verifyParams, critical = false,
            ),
        )
    }

    /** "open <app>": launch → wait → observe. */
    private fun openSteps(appName: String): List<PlanStep> {
        val params = mutableMapOf(PlanParams.APP to appName)
        when (val r = safeResolve(appName)) {
            is AppResolution.Found -> params[PlanParams.PACKAGE] = r.packageName
            else -> Unit // unresolved: executor reports AppNotFound honestly
        }
        // Simple-task execution fix (2026-11-07): WAIT_FOR_APP
        // carries the package too — its verification checks the
        // foreground package, and without the param it could only
        // pass on "the screen is non-empty", which is not "the app
        // is up".
        val waitParams = mutableMapOf(PlanParams.APP to appName)
        params[PlanParams.PACKAGE]?.let { waitParams[PlanParams.PACKAGE] = it }
        return listOf(
            PlanStep(0, TaskStepKind.LAUNCH_APP, "Open $appName", params),
            PlanStep(0, TaskStepKind.WAIT_FOR_APP, "Wait for $appName to come to the foreground",
                waitParams),
            PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is open",
                critical = false),
        )
    }

    /** Guidance-only alternative for a refused goal: observe, guide,
     *  report. Never an action step — allowed inside sensitive apps. */
    private fun guidancePlan(request: PlanRequest, record: TaskRecord, appName: String?): TaskPlan {
        val target = appName ?: request.goal
        return buildPlan(
            request,
            record,
            listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see where you are",
                    critical = false),
                PlanStep(0, TaskStepKind.GUIDE_TO, "Guide you to $target",
                    mapOf(PlanParams.GUIDE_TARGET to target)),
                PlanStep(0, TaskStepKind.REPORT, "Report the guidance for $target",
                    critical = false),
            ),
        )
    }

    // ---------- app gate ----------

    /**
     * Stops planning when [appName] is sensitive or ambiguous:
     * returns the refusal / clarification, or null when planning may
     * proceed. Sensitivity is checked on the spoken name AND on the
     * resolved package, both through the shared
     * [ScreenDataPolicy.isSensitivePackage] fragment classifier — the
     * same classifier the execution gates use, so the planner and the
     * gate can never disagree about an app.
     */
    private fun checkApp(appName: String, request: PlanRequest, record: TaskRecord): PlanResult? {
        val resolved = safeResolve(appName)
        if (resolved is AppResolution.Ambiguous) {
            return PlanResult.ClarificationNeeded(
                "Which ${appName} did you mean — ${resolved.candidates.joinToString(", ")}?",
                "app",
            )
        }
        val pkg = (resolved as? AppResolution.Found)?.packageName
        if (ScreenDataPolicy.isSensitivePackage(appName) ||
            (pkg != null && ScreenDataPolicy.isSensitivePackage(pkg))
        ) {
            return PlanResult.RefusedSensitive(
                reason = "$appName is a banking, financial, or authenticator app, " +
                    "so I can only guide you through it — I can't act inside it.",
                appName = appName,
                guidancePlan = guidancePlan(request, record, appName),
            )
        }
        return null
    }

    private fun safeResolve(name: String): AppResolution = try {
        apps.resolve(name)
    } catch (_: Exception) {
        AppResolution.NotFound // a broken resolver never breaks planning
    }

    // ---------- plan assembly ----------

    private fun buildPlan(request: PlanRequest, record: TaskRecord, steps: List<PlanStep>): TaskPlan {
        val indexed = steps.mapIndexed { i, s -> s.copy(index = i) }
        return TaskPlan(
            id = "plan-" + UUID.randomUUID().toString().take(8),
            // D-01/D-07: the plan's displayed goal is the sanitized
            // task (action + target), never David's whole sentence —
            // the "Done — … for: <goal>" line reads as a task, not a
            // paragraph. The record keeps the verbatim instruction
            // (it is re-sanitized on any resume, in plan()).
            goal = TaskTextSanitizer.sanitizeGoal(request.goal),
            steps = indexed,
            origin = request.origin,
            status = PlanStatus.PLANNING,
            replanCount = 0,
            // Task-record fix: the plan carries the SAME record the
            // planner resolved above — the executor and the replan
            // carry this copy forward, never a re-derivation.
            record = record,
        )
    }

    // ---------- text helpers ----------

    /** Strips the optional "loom" prefix, leading "please", and
     *  trailing punctuation; collapses whitespace. */
    private fun clean(text: String): String {
        var s = text.trim()
        if (s.startsWith("loom,", ignoreCase = true) || s.startsWith("loom ", ignoreCase = true)) {
            s = s.substring(4).trimStart(',', ' ', '!', '.').trim()
        }
        if (s.startsWith("please ", ignoreCase = true)) s = s.substring(7).trim()
        s = s.trimEnd('.', '!', '?').trim()
        return s.split(Regex("\\s+")).joinToString(" ")
    }

    private fun isBulkMessageRequest(text: String): Boolean {
        val lower = text.lowercase()
        val head = lower.substringBefore(" saying ")
        val isMessage = head.contains("send") || head.contains("message") ||
            head.contains("text") || head.contains("broadcast")
        if (!isMessage) return false
        return BULK_WORDS.any { head.contains(it) }
    }

    /** First financial phrase matched in the goal, or null. */
    private fun financialHit(text: String): String? =
        FINANCIAL_WORDS.firstOrNull { it.containsMatchIn(text) }
            ?.find(text)?.value?.trim()

    private fun isCall(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "call" || lower.startsWith("call ") ||
            lower.startsWith("please call ")
    }

    private fun extractCallTarget(original: String): String {
        val prefixes = listOf("please call my ", "please call ", "call my ", "call ")
        for (p in prefixes) {
            val idx = original.indexOf(p, ignoreCase = true)
            if (idx < 0) continue
            val lead = original.substring(0, idx).trim().lowercase()
                .removePrefix("loom").trimStart(',', ' ').trim()
            if (lead.isEmpty() || lead == "please") {
                return original.substring(idx + p.length).trim().trimEnd('.', '!', '?').trim()
            }
        }
        return ""
    }

    private fun extractGuideTarget(text: String): String? {
        for (phrase in GUIDE_PHRASES) {
            val idx = text.indexOf(phrase, ignoreCase = true)
            if (idx >= 0) {
                return text.substring(idx + phrase.length).trim().trimEnd('.', '!', '?').trim()
            }
        }
        return null
    }

    private fun isGuidePhraseWithoutTarget(text: String): Boolean {
        val lower = text.lowercase().trimEnd('.', '!', '?').trim()
        return GUIDE_PHRASES.any { lower == it.trim() } ||
            lower == "guide me" || lower == "guide me to" || lower == "guide me through"
    }

    /**
     * Universal Console (2026-11-07) — one direct console operation
     * on the current screen, or null when the utterance is not one.
     * Every op is a single action step (plus a non-critical OBSERVE
     * first, so the executor dispatches against a fresh screen);
     * verification is the executor's per-kind check (screen changed
     * for long-press / swipe / menu, focus held for focus-field),
     * never a separate VERIFY_OUTCOME step naming text that may not
     * be on screen. A long-press / swipe / focus / menu with no
     * target or direction asks once, like every other branch.
     */
    private fun extractConsoleOp(text: String): List<PlanStep>? {
        val lower = text.lowercase()
        // Long press: "long press <target>" / "press and hold <target>".
        for (p in listOf("long press ", "long-press ", "press and hold ")) {
            if (lower.startsWith(p)) {
                val target = text.substring(p.length).trim().trimEnd('.', '!', '?').trim()
                if (target.isBlank()) return null
                return listOf(
                    PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                        critical = false),
                    PlanStep(0, TaskStepKind.LONG_PRESS, "Long-press $target",
                        mapOf(PlanParams.TARGET to target)),
                )
            }
        }
        // Swipe: "swipe left/right/up/down" (scroll is vertical-only,
        // so left/right are swipes, never scrolls). No direction →
        // null, and the caller asks which way instead of guessing.
        if (lower.startsWith("swipe ")) {
            val direction = extractPointerDirection(text) ?: return null
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                    critical = false),
                PlanStep(0, TaskStepKind.SWIPE, "Swipe $direction",
                    mapOf(PlanParams.DIRECTION to direction)),
            )
        }
        if (lower == "swipe") return null // asked by the caller below
        // Focus a field: "focus the search field" / "focus <target>".
        if (lower.startsWith("focus ")) {
            val target = text.substring("focus ".length).trim().trimEnd('.', '!', '?').trim()
            if (target.isBlank()) return null
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                    critical = false),
                PlanStep(0, TaskStepKind.FOCUS_FIELD, "Focus $target",
                    mapOf(PlanParams.TARGET to target)),
            )
        }
        // Open the menu: "open the menu" / "show the menu" / "open menu".
        if (lower in setOf("open the menu", "open menu", "show the menu", "show menu")) {
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                    critical = false),
                PlanStep(0, TaskStepKind.OPEN_MENU, "Open the menu"),
            )
        }
        // MASTER F1d: bare console follow-ups — the turns David used
        // after a search. Scroll (vertical only — left/right are
        // swipes, see above), go back, and the Enter key. Each is a
        // single action step on the current screen, planned like the
        // other console ops (OBSERVE first, then the step).
        if (lower == "scroll down" || lower.startsWith("scroll down ")) {
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                    critical = false),
                PlanStep(0, TaskStepKind.SCROLL, "Scroll down",
                    mapOf(PlanParams.DIRECTION to "down")),
            )
        }
        if (lower == "scroll up" || lower.startsWith("scroll up ")) {
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                    critical = false),
                PlanStep(0, TaskStepKind.SCROLL, "Scroll up",
                    mapOf(PlanParams.DIRECTION to "up")),
            )
        }
        if (lower == "go back" || lower.startsWith("go back ")) {
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                    critical = false),
                PlanStep(0, TaskStepKind.GO_BACK, "Go back"),
            )
        }
        if (lower in setOf(
                "press enter", "hit enter", "click on enter", "tap enter",
                "press the enter key", "hit the enter key",
            ) || lower.startsWith("press enter ") || lower.startsWith("hit enter ") ||
            lower.startsWith("click on enter ") || lower.startsWith("tap enter ")
        ) {
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                    critical = false),
                PlanStep(0, TaskStepKind.SUBMIT, "Press enter",
                    mapOf(PlanParams.KEYBOARD_ACTION to "submit")),
            )
        }
        // MASTER Wave 3 (F15b) — play / pause the media on screen.
        // "pause the video" is a player command, never a search for
        // a song called "the video" — it reaches the PLAY_PAUSE step
        // kind, which taps the play/pause control and verifies the
        // player state flipped. Bare "play" / "pause" toggle the
        // player on screen; a named song ("play Davido") is not
        // this branch — it falls through to the play-search plan.
        // The phrase table is shared (MediaControlPhrases) with the
        // early deterministic branch above, so the two can never
        // drift apart: this is the same rule as a fallback.
        val playPause = MediaControlPhrases.match(lower)?.let {
            if (it == MediaControl.PAUSE) "pause" else "play"
        }
        if (playPause != null) {
            return listOf(
                PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is playing",
                    critical = false),
                PlanStep(0, TaskStepKind.PLAY_PAUSE, "${if (playPause == "pause") "Pause" else "Play"} the video",
                    mapOf(PlanParams.PLAY_STATE to playPause)),
            )
        }
        // Select / tap a named target on the current screen.
        // MASTER F1d: "click on <x>" / "click <x>" are the same tap
        // David used ("click on enter" is ENTER above, never here).
        // MASTER Wave 3 (F9) — MATCH="first" is emitted ONLY when
        // the user explicitly selects the first ("the first result",
        // "first one", "first Davido"): that is the user's choice,
        // not a blind pick. A bare "tap <x>" carries NO match hint,
        // so several matches refuse honestly at dispatch instead of
        // blind-tapping the first one (the voice-search mic was
        // tapped exactly that way). The old code emitted MATCH="first"
        // on EVERY tap/select step — that default was the blind pick.
        for (p in listOf("select ", "tap ", "click on ", "click ")) {
            if (lower.startsWith(p)) {
                val rawTarget = text.substring(p.length).trim().trimEnd('.', '!', '?').trim()
                // "tap on my story" — the "on" is the phrasing, not
                // the target ("click on <x>" already consumes its
                // "on" in the prefix; "tap on <x>" must do the same).
                val target = if (rawTarget.lowercase().startsWith("on ")) {
                    rawTarget.substring(3).trim()
                } else {
                    rawTarget
                }
                if (target.isBlank()) return null
                val firstSelector = target.lowercase().removePrefix("the ").trim().let {
                    it == "first" || it.startsWith("first ")
                }
                val params = if (firstSelector) {
                    mapOf(PlanParams.TARGET to target, PlanParams.MATCH to "first")
                } else {
                    mapOf(PlanParams.TARGET to target)
                }
                return listOf(
                    PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen to see what is there",
                        critical = false),
                    PlanStep(0, TaskStepKind.TAP_TARGET, "Tap $target", params),
                )
            }
        }
        return null
    }

    private fun isPointerMoveUtterance(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "move" || lower.startsWith("move ") ||
            lower.startsWith("move the pointer") || lower.startsWith("move pointer")
    }

    /** Single direction, or null when none / conflicting (VoiceCommands parity). */
    private fun extractPointerDirection(text: String): String? {
        val lower = text.lowercase()
        val hasLeft = lower.contains("left")
        val hasRight = lower.contains("right")
        val hasUp = lower.contains("up")
        val hasDown = lower.contains("down")
        val found = listOf(
            "left" to hasLeft, "right" to hasRight,
            "up" to hasUp, "down" to hasDown,
        ).filter { it.second }
        return if (found.size == 1) found[0].first else null
    }

    private fun isChannelLessMessage(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains(" saying ") &&
            (lower.contains("send ") || lower.contains("message ")) &&
            !lower.contains("whatsapp") && !lower.contains("sms") &&
            !clean(lower).startsWith("text ")
    }

    private fun isSearch(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "search" || lower.startsWith("search ") ||
            lower.startsWith("can you search")
    }

    private fun isBareOpen(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "open" || lower == "launch" || lower == "can you open"
    }

    private fun isPlay(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("play ")
    }

    private fun extractLaunchApp(text: String, goTo: Boolean = false): String? {
        val lower = text.lowercase()
        // MASTER F1c: leading words are politeness, never part of the
        // command — "let's go open YouTube" / "I want you to open
        // YouTube" must match like the bare form. The prefix is found
        // in the lowered text, but the name is sliced from the
        // ORIGINAL text so casing is preserved ("YouTube", never
        // "youtube"). (VoiceCommands.extractLaunchApp is the twin
        // copy — the duplication remains by design: this copy keeps
        // casing, the voice copy lowercases. Both apply the same
        // lead-in and trailing-filler lists.)
        val leadLen = leadingWordsLength(lower)
        val rest = lower.substring(leadLen)
        val prefixes = listOf(
            "can you open my ", "can you open ",
            "open my ", "open ", "launch my ", "launch ",
        ) + if (goTo) {
            // NAV-SCREEN fix (2026-11-11, N1): "go to / take me to /
            // switch to <app>" is a launch at planner level too —
            // the voice layer normalises GO_TO_APP to "open $app"
            // today, but the planner must own the routing: the
            // navigation branch returns null for a resolvable app,
            // so without these prefixes "go to YouTube" had no
            // launch path at all. Navigation still wins for a known
            // destination (planNavigateTab runs first). These
            // prefixes are goTo-only: extractGoalApp must NOT treat
            // "go to Reels" as naming an app called "Reels" (it
            // would poison record.app and prepend a bogus launch).
            listOf("go to ", "take me to ", "switch to ")
        } else {
            emptyList()
        }
        for (p in prefixes) {
            if (rest.startsWith(p)) {
                val raw = text.substring(leadLen + p.length).trim()
                    .trimEnd('.', '!', '?').trim()
                // MASTER F2a: trailing filler is not part of the app
                // name — "open YouTube for me" extracts "YouTube".
                return stripTrailingFiller(raw).ifBlank { null }
            }
        }
        return null
    }

    /**
     * MASTER F1c/F2a (planner twin of the VoiceCommands lists — same
     * words, same order). The length of the leading-words run at the
     * start of [lower], or 0 when there is none.
     */
    private fun leadingWordsLength(lower: String): Int {
        var len = 0
        var changed: Boolean
        do {
            changed = false
            for (lead in LEADING_WORDS) {
                if (lower.substring(len).startsWith(lead)) {
                    len += lead.length
                    changed = true
                    break
                }
            }
        } while (changed)
        return len
    }

    private fun stripTrailingFiller(name: String): String {
        var s = name.trim().trimEnd('.', '!', '?').trim()
        var changed: Boolean
        do {
            changed = false
            for (f in TRAILING_FILLERS) {
                if (s.equals(f, ignoreCase = true) || s.endsWith(" $f", ignoreCase = true)) {
                    s = s.substring(0, s.length - f.length).trim()
                        .trimEnd('.', '!', '?').trim()
                    changed = true
                    break
                }
            }
        } while (changed)
        return s
    }

    /** "search for <q> [in <app>]" / "search <app> for <q>" /
     *  "<q> on <app>" — same split rules as VoiceCommands. */
    private fun extractSearchQuery(text: String): String {
        val rest = searchRemainder(text) ?: return ""
        val inIdx = rest.lastIndexOfIc(" in ")
        val query = if (inIdx >= 0) {
            rest.substring(0, inIdx)
        } else if (!hasSearchForIntroducer(text) && rest.containsIc(" for ")) {
            rest.substring(rest.indexOfIc(" for ") + 5)
        } else {
            val onIdx = rest.lastIndexOfIc(" on ")
            if (onIdx >= 0) rest.substring(0, onIdx) else rest
        }
        // Device-failure fix (2026-11-13, F2): trailing
        // meta-speech can never land in the query — the cut runs
        // here too, not only on the goal, so a query extracted from
        // any path (rule-based, replan, record) carries the task
        // words only. "search for Spider-Man No Way Home don't make
        // me repeat myself again" → exactly "Spider-Man No Way Home".
        return TaskTextSanitizer.sanitizeQuery(query)
    }

    private fun extractSearchApp(text: String): String {
        val rest = searchRemainder(text) ?: return ""
        val inIdx = rest.lastIndexOfIc(" in ")
        if (inIdx >= 0) {
            val app = rest.substring(inIdx + 4).trim().trimEnd('.', '!', '?').trim()
            // MASTER F2a: trailing filler is not part of the app name.
            if (app.isNotBlank()) return stripTrailingFiller(truncateCompositeTail(TaskTextSanitizer.sanitizeQuery(app)))
        }
        if (!hasSearchForIntroducer(text) && rest.containsIc(" for ")) {
            val app = rest.substring(0, rest.indexOfIc(" for ")).trim()
                .trimEnd('.', '!', '?').trim()
            if (app.isNotBlank()) return stripTrailingFiller(truncateCompositeTail(TaskTextSanitizer.sanitizeQuery(app)))
        }
        val onIdx = rest.lastIndexOfIc(" on ")
        if (onIdx >= 0) {
            val app = rest.substring(onIdx + 4).trim().trimEnd('.', '!', '?').trim()
            if (app.isNotBlank()) return stripTrailingFiller(truncateCompositeTail(TaskTextSanitizer.sanitizeQuery(app)))
        }
        return ""
    }

    /**
     * Task-record fix (2026-11-07): cuts a search app at its
     * composite join — "YouTube and open the first result" →
     * "YouTube". Only a join followed by a tail verb cuts, so app
     * names containing "and" survive. Without this the record's app
     * for "search … in YouTube and open …" carried the tail with it.
     */
    private fun truncateCompositeTail(app: String): String {
        val verbs = listOf(
            "play", "search", "tap", "turn", "send", "message", "text",
            "call", "find", "go", "switch", "enable", "disable", "start",
            "show", "watch", "listen", "open",
        ).joinToString("|")
        val cut = Regex("""\s+(?:and|then)\s+($verbs)\b""", RegexOption.IGNORE_CASE)
            .find(app) ?: return app
        return app.substring(0, cut.range.first).trim().trimEnd('.', '!', '?').trim()
    }

    private fun hasSearchForIntroducer(text: String): Boolean {
        val lower = clean(text.lowercase())
        return lower.startsWith("search for ") || lower.startsWith("can you search for ")
    }

    private fun searchRemainder(text: String): String? {
        // Original casing preserved (the executor matches
        // case-insensitively, but the plan carries what David said).
        val n = clean(text)
        val lower = n.lowercase()
        val rest: String = when {
            lower.startsWith("can you search") -> n.substring("can you search".length).trim()
            lower == "search" || lower.startsWith("search ") -> n.substring("search".length).trim()
            else -> return null
        }
        var r = rest
        if (r.startsWith("for ", ignoreCase = true)) r = r.substring(4).trim()
        else if (r.equals("for", ignoreCase = true)) r = ""
        if (r.startsWith("in ", ignoreCase = true) || r.startsWith("on ", ignoreCase = true)) return ""
        return r
    }

    /**
     * "play ..." tail → the query ("a song by Davido" → "Davido").
     *
     * Simple-task execution fix (2026-11-07) — the bare-play default
     * query rule (general, no hardcoded sentences): a PLAY with no
     * named song, artist or video derives its query from the MEDIA
     * KIND the request names, so the search always has something
     * real to search for instead of the garbage the old extractor
     * returned ("me some music on youtube" — the filler words AND
     * the app name as a search query). "play me some music (on
     * YouTube)" → "music"; "play a video (on YouTube)" → "video";
     * "play something" → "music". Anything naming a song/artist
     * ("Davido", "Fall by Davido") keeps that name untouched.
     */
    private fun extractPlayQuery(tail: String): String? {
        var t = tail.trim()
        if (t.isBlank()) return null
        // The app suffix is not part of the query: "… on YouTube"
        // names where to play, never what to play.
        val onIdx = t.lastIndexOfIc(" on ")
        if (onIdx >= 0) t = t.substring(0, onIdx).trim()
        for (p in listOf(
            "a song by ", "song by ", "songs by ", "music by ",
            "some music by ", "some songs by ", "some song by ",
            "the song ", "a video by ", "video by ",
        )) {
            if (t.startsWith(p, ignoreCase = true)) {
                t = t.substring(p.length).trim()
                break
            }
        }
        // Filler words around a bare media request: "me some music",
        // "some music", "me music", "a song", "something".
        var core = t.trimEnd('.', '!', '?').trim()
        val lower = core.lowercase()
        val fillerStripped = lower
            .removePrefix("me some ").removePrefix("me ")
            .removePrefix("some ").removePrefix("a ").removePrefix("an ")
            .removePrefix("the ").trim()
        val genericMedia = setOf(
            "music", "song", "songs", "tune", "tunes",
            "something", "anything", "stuff",
        )
        if (fillerStripped in genericMedia || fillerStripped.isBlank()) {
            return if (fillerStripped.contains("video")) "video"
            else if (lower.contains("video")) "video"
            else "music"
        }
        if (lower in setOf("a video", "video", "videos", "some videos", "me a video")) {
            return "video"
        }
        return core.ifBlank { null }
    }

    private fun extractMessageText(text: String): String {
        val idx = text.indexOf(" saying ", ignoreCase = true)
        if (idx < 0) return ""
        return text.substring(idx + " saying ".length).trim()
    }

    private fun extractMessageTarget(text: String): String {
        val idx = text.indexOf(" saying ", ignoreCase = true)
        val head = if (idx < 0) text else text.substring(0, idx)
        var h = head.trim()
        val lower = h.lowercase()
        for (p in MESSAGE_PREFIXES) {
            if (lower.startsWith(p)) {
                h = h.substring(p.length)
                break
            }
        }
        val hLower = h.lowercase().trim()
        for (suffix in listOf(" on whatsapp", " via whatsapp", " on sms", " via sms")) {
            if (hLower.endsWith(suffix)) {
                h = h.substring(0, h.length - suffix.length)
                break
            }
        }
        val result = h.trim().trimEnd('.', '!', '?').trim()
        return if (result.lowercase() in setOf("whatsapp", "whats app", "sms", "text", "message")) "" else result
    }

    // Case-insensitive index helpers (original casing preserved).
    private fun String.indexOfIc(needle: String, startIndex: Int = 0): Int =
        lowercase().indexOf(needle.lowercase(), startIndex)

    private fun String.lastIndexOfIc(needle: String): Int =
        lowercase().lastIndexOf(needle.lowercase())

    private fun String.containsIc(needle: String): Boolean =
        lowercase().contains(needle.lowercase())
}
