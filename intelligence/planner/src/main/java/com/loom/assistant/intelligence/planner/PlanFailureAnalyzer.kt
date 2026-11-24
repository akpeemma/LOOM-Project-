package com.loom.assistant.intelligence.planner

import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SubmitVerification
import com.loom.assistant.execution.screenawareness.SubmitVerifier
import com.loom.assistant.execution.screenawareness.TargetAliases

/**
 * Stage 2D — failure analysis and recovery (Worker A).
 *
 * Port of Brahma `brahma_core/error_handler.py` (`analyze_error` /
 * `generate_fix`) to LOOM steps, with the Android additions the spec
 * requires: target-moved vs target-gone is distinguished by
 * re-resolving the step's target against a FRESH observation, and
 * the attempt / replan budgets are enforced by this analyzer's own
 * counters (keyed by plan id + step index).
 *
 * Decision order (first match wins):
 *  1. Unsafe / sensitive → ABORT. Always. No confirmation overrides.
 *  2. Attempts exhausted (3 per step) → ABORT.
 *  2a. Unexpected screen (wrong app foreground, or a dialog) →
 *     REPLAN (rebuild the tail from the new screen), max 2 replans.
 *  2b. Submit never executed / keyboard still open →
 *     TRY_ALTERNATIVE (next submit mechanism, or dismiss-and-retry).
 *  3. Target gone → ABORT + ask the user (the thing to act on no
 *     longer exists; guessing a replacement is worse than stopping).
 *  4. Target moved → REPLAN (re-observe, rebuild the remaining
 *     steps around the new bounds) — unless the replan budget (2)
 *     is spent, then ABORT.
 *  5. Transient error → RETRY (same step, same params).
 *  6. Non-critical step, non-transient failure → SKIP.
 *  7. Critical step, anything else → REPLAN (different approach),
 *     or ABORT when the replan budget is spent.
 *
 * Differences from Brahma, stated plainly: Brahma's `analyze_error`
 * forces REPLAN at max attempts; LOOM aborts instead (task spec) —
 * three failed attempts on one step means the step as built does not
 * work, and a replan that re-issues it would loop. Brahma's
 * "critical step may never be skipped" rule is kept (step 6).
 *
 * The analyzer never raises: a throwing observation or an unknown
 * error string degrades to REPLAN (Brahma's no-LLM default).
 */
class PlanFailureAnalyzer {

    companion object {
        /** Brahma `AgentExecutor.MAX_STEP_ATTEMPTS`. */
        const val MAX_STEP_ATTEMPTS = 3

        /** Brahma `AgentExecutor.MAX_REPLAN_ATTEMPTS`. */
        const val MAX_REPLANS = 2

        private val TRANSIENT_PATTERNS = listOf(
            Regex("timeout", RegexOption.IGNORE_CASE),
            Regex("timed out", RegexOption.IGNORE_CASE),
            Regex("not ready", RegexOption.IGNORE_CASE),
            Regex("not foreground", RegexOption.IGNORE_CASE),
            Regex("unreadable", RegexOption.IGNORE_CASE),
            Regex("no focus", RegexOption.IGNORE_CASE),
            Regex("network", RegexOption.IGNORE_CASE),
            Regex("connection", RegexOption.IGNORE_CASE),
            Regex("try again", RegexOption.IGNORE_CASE),
            Regex("race", RegexOption.IGNORE_CASE),
            Regex("temporar", RegexOption.IGNORE_CASE),
        )

        private val UNSAFE_PATTERNS = listOf(
            Regex("\\bpay\\b", RegexOption.IGNORE_CASE),
            Regex("financial", RegexOption.IGNORE_CASE),
            Regex("sensitive", RegexOption.IGNORE_CASE),
            Regex("\\botp\\b", RegexOption.IGNORE_CASE),
            Regex("password", RegexOption.IGNORE_CASE),
            Regex("permission denied", RegexOption.IGNORE_CASE),
            Regex("refused", RegexOption.IGNORE_CASE),
            Regex("not confirmed", RegexOption.IGNORE_CASE),
            Regex("token denied", RegexOption.IGNORE_CASE),
        )

        private val MOVED_PATTERNS = listOf(
            Regex("target moved", RegexOption.IGNORE_CASE),
            Regex("moved", RegexOption.IGNORE_CASE),
            Regex("layout (changed|shift)", RegexOption.IGNORE_CASE),
            Regex("stale", RegexOption.IGNORE_CASE),
        )

        private val SUBMIT_NOT_EXECUTED_PATTERNS = listOf(
            Regex("submit (not executed|never ran|did not execute|didn't run)", RegexOption.IGNORE_CASE),
            Regex("search never ran", RegexOption.IGNORE_CASE),
            Regex("search did not (execute|run)", RegexOption.IGNORE_CASE),
        )

        private val KEYBOARD_STILL_OPEN_PATTERNS = listOf(
            Regex("keyboard still open", RegexOption.IGNORE_CASE),
        )

        private val UNEXPECTED_SCREEN_PATTERNS = listOf(
            Regex("unexpected screen", RegexOption.IGNORE_CASE),
            Regex("wrong (app|package)", RegexOption.IGNORE_CASE),
        )

        private val GONE_PATTERNS = listOf(
            Regex("target[- ]lost", RegexOption.IGNORE_CASE),
            Regex("target gone", RegexOption.IGNORE_CASE),
            Regex("no match", RegexOption.IGNORE_CASE),
            Regex("not found", RegexOption.IGNORE_CASE),
            Regex("disappeared", RegexOption.IGNORE_CASE),
            Regex("no results", RegexOption.IGNORE_CASE),
        )
    }

    /**
     * What the analyzer decided for one failed step. Sealed (not an
     * enum) so TRY_ALTERNATIVE can carry the alternative to try.
     * RETRY / SKIP / REPLAN / ABORT keep their existing identities —
     * existing callers comparing against them are unaffected.
     */
    sealed class FailureDecision {
        data object RETRY : FailureDecision()
        data object SKIP : FailureDecision()
        data object REPLAN : FailureDecision()
        data object ABORT : FailureDecision()

        /**
         * Try a different approach to the SAME step, without
         * rebuilding the plan: [alternative] names it —
         * "next-submit-mechanism" (try the next submit mechanism in
         * the order) or "dismiss-and-retry" (dismiss the keyboard
         * and retry the submit).
         */
        data class TRY_ALTERNATIVE(val alternative: String) : FailureDecision()
    }

    /** Where the step's target stands against a fresh observation. */
    enum class TargetState {
        /** Same target, same place — the failure is elsewhere. */
        PRESENT,

        /** Same target, new bounds — re-resolve, do not re-aim blind. */
        MOVED,

        /** No element matches the target any more. */
        GONE,
    }

    data class FailureAnalysis(
        val decision: FailureDecision,
        val reason: String,
        val fixSuggestion: String,
        val userMessage: String,
        /** True when the user must be asked before anything else
         *  (target gone: only David knows what the target became). */
        val askUser: Boolean = false,
        /** Attempt number this analysis was made on (1-based). */
        val attempt: Int = 1,
    )

    // ---------- counters (the budgets live here) ----------

    private val attempts: MutableMap<String, Int> = mutableMapOf()
    private val replans: MutableMap<String, Int> = mutableMapOf()

    /** Attempts used so far for this plan + step (0 before any failure). */
    fun attemptsFor(planId: String, stepIndex: Int): Int =
        attempts["$planId:$stepIndex"] ?: 0

    /** Replans used so far for this plan (0 before any replan). */
    fun replansFor(planId: String): Int = replans[planId] ?: 0

    /** Records that a replan was spent for this plan. */
    fun noteReplan(planId: String) {
        replans[planId] = replansFor(planId) + 1
    }

    /** Drops all counters for a finished / abandoned plan. */
    fun reset(planId: String) {
        attempts.keys.removeAll { it.startsWith("$planId:") }
        replans.remove(planId)
    }

    // ---------- analysis ----------

    /**
     * Analyzes one failed step. Increments this step's attempt
     * counter and decides — Worker B calls this ONCE per failure, so
     * the counter IS the attempt number. [observation] is the fresh
     * snapshot taken after the failure (null when the screen went
     * unreadable); it is used to resolve the target state.
     */
    fun analyze(
        planId: String,
        step: PlanStep,
        error: String,
        observation: ScreenModel?,
    ): FailureAnalysis {
        val key = "$planId:${step.index}"
        val attempt = (attempts[key] ?: 0) + 1
        attempts[key] = attempt
        return analyzeWithAttempt(planId, step, error, observation, attempt)
    }

    /** Stateless form for callers that track attempts themselves. */
    fun analyzeWithAttempt(
        planId: String,
        step: PlanStep,
        error: String,
        observation: ScreenModel?,
        attempt: Int,
    ): FailureAnalysis {
        val targetState = resolveTarget(step, observation)
        val errorClass = classifyError(error, targetState)

        // 1. Unsafe / sensitive — abort, always.
        if (errorClass == ErrorClass.UNSAFE) {
            return FailureAnalysis(
                decision = FailureDecision.ABORT,
                reason = "Unsafe or refused step: $error",
                fixSuggestion = "",
                userMessage = "I stopped — that step isn't safe to do.",
                attempt = attempt,
            )
        }
        // 2. Attempts exhausted — abort (LOOM rule; Brahma replans).
        if (attempt >= MAX_STEP_ATTEMPTS) {
            return FailureAnalysis(
                decision = FailureDecision.ABORT,
                reason = "Failed $attempt times: $error",
                fixSuggestion = "Try a completely different approach or ask David",
                userMessage = "I tried that step $attempt times and it didn't work.",
                attempt = attempt,
            )
        }
        // 2a. Unexpected screen — the foreground app is not the
        // step's app, or a dialog is showing: rebuild the tail from
        // the new screen (or abort when the replan budget is spent).
        if (isUnexpectedScreen(step, observation, error)) {
            return replanOrAbort(
                planId, attempt,
                reason = "Unexpected screen: ${unexpectedScreenReason(step, observation, error)}",
                fixSuggestion = "Re-observe the screen and rebuild the remaining steps from what is actually showing",
                userMessage = "That's not the screen I expected — looking again.",
            )
        }
        // 2b. Submit never executed / keyboard still open — try
        // another mechanism instead of retrying the same one.
        if (errorClass == ErrorClass.SUBMIT_NOT_EXECUTED) {
            // BUILT-IN RECOVERY (2026-11-09): the first failure
            // rotates the submit mechanism (cheap fix first). A
            // SECOND failure means rotation is not fixing it — the
            // search itself is wrong — so replan instead: the
            // planner's clear-and-retry recovery clears the field
            // and tries again. Bounded by the replan budget
            // (replanOrAbort) and the attempt cap.
            if (attempt >= 2) {
                return replanOrAbort(
                    planId, attempt,
                    reason = "Submit did not execute after trying another way: $error",
                    fixSuggestion = "Clear the search and try again",
                    userMessage = "That didn't run the search — clearing it and trying again.",
                )
            }
            return FailureAnalysis(
                decision = FailureDecision.TRY_ALTERNATIVE("next-submit-mechanism"),
                reason = "Submit did not execute: $error",
                fixSuggestion = "Try the next submit mechanism in the order",
                userMessage = "That didn't run the search — trying another way.",
                attempt = attempt,
            )
        }
        if (errorClass == ErrorClass.KEYBOARD_STILL_OPEN) {
            return FailureAnalysis(
                decision = FailureDecision.TRY_ALTERNATIVE("dismiss-and-retry"),
                reason = "Keyboard still open after submit: $error",
                fixSuggestion = "Dismiss the keyboard and retry the submit, or try the next submit mechanism",
                userMessage = "The keyboard is still open — trying another way to run the search.",
                attempt = attempt,
            )
        }
        // 3. Target gone — abort and ask the user. EXCEPT a recovery
        // step (BUILT-IN RECOVERY, 2026-11-09): the clear step's X
        // may have left the screen between replan and dispatch —
        // that is not a question for David, it is a skipped
        // recovery; the re-issued search that follows still runs.
        // (resolveTarget matches by substring, not by TargetAliases,
        // so an X labelled "Close" rather than "Clear" also reads as
        // gone here — skipping is the safe direction either way.)
        if (targetState == TargetState.GONE && step.params[PlanParams.RECOVERY] != null) {
            return FailureAnalysis(
                decision = FailureDecision.SKIP,
                reason = "Recovery target gone: ${step.params[PlanParams.TARGET] ?: step.description} " +
                    "no longer matches anything on screen — skipping the recovery",
                fixSuggestion = "",
                userMessage = "The thing I was going to clear is gone — trying the search again anyway.",
                attempt = attempt,
            )
        }
        // NAV-SCREEN fix (2026-11-11, N9): TARGET_GONE on a
        // navigation step is NOT terminal. The tab may have been
        // unresolvable by label (icon-only) — replan once so the
        // step re-issues against a fresh screen and the positional
        // tab resolver (NavigationResolver) gets its turn. Bounded
        // by the existing replan budget (max 2) via replanOrAbort;
        // the fix suggestion names the positional retry so the
        // replan log shows what changed. A TAP_TARGET on a known
        // destination with a navigation container on screen gets
        // the same treatment; every other gone target still aborts
        // and asks David.
        if (targetState == TargetState.GONE && isNavStepRetry(step, observation)) {
            return replanOrAbort(
                planId, attempt,
                reason = "Navigation target gone: ${step.params[PlanParams.TARGET] ?: step.description} " +
                    "no longer matches anything on screen",
                fixSuggestion = "Retry via the positional tab resolver — re-resolve the tab by its " +
                    "position in the navigation bar on a fresh screen",
                userMessage = "That tab moved — finding it by its position in the navigation bar.",
            )
        }
        if (targetState == TargetState.GONE) {
            return FailureAnalysis(
                decision = FailureDecision.ABORT,
                reason = "Target gone: ${step.params[PlanParams.TARGET] ?: step.description} " +
                    "no longer matches anything on screen",
                fixSuggestion = "Ask David what the target became, or pick a new one",
                userMessage = "The thing I was looking for is gone — what should I tap instead?",
                askUser = true,
                attempt = attempt,
            )
        }
        // 4. Target moved — replan (or abort when the budget is spent).
        if (targetState == TargetState.MOVED || errorClass == ErrorClass.TARGET_MOVED) {
            return replanOrAbort(
                planId, attempt,
                reason = "Target moved — re-observing and rebuilding the remaining steps",
                fixSuggestion = "Re-resolve the target from the fresh screen and continue",
                userMessage = "That moved — finding it again.",
            )
        }
        // 5. Transient — retry the same step.
        if (errorClass == ErrorClass.TRANSIENT) {
            return FailureAnalysis(
                decision = FailureDecision.RETRY,
                reason = "Transient failure: $error",
                fixSuggestion = "",
                userMessage = "That didn't go through — trying again.",
                attempt = attempt,
            )
        }
        // 6. Non-critical — skip (Brahma: critical steps never skip).
        if (!step.critical) {
            return FailureAnalysis(
                decision = FailureDecision.SKIP,
                reason = "Non-critical step failed: $error",
                fixSuggestion = "",
                userMessage = "Skipped a step that isn't essential.",
                attempt = attempt,
            )
        }
        // 7. Critical, anything else — replan (or abort when spent).
        return replanOrAbort(
            planId, attempt,
            reason = "Approach failed: $error",
            fixSuggestion = "Try a completely different approach or tool",
            userMessage = "Trying a different approach.",
        )
    }

    /**
     * NAV-SCREEN fix (2026-11-11, N9): true when a gone target is
     * worth one positional retry — the step IS a navigation step, or
     * a tap on a known destination while a navigation container is
     * on screen. Uses the nav resolver's own destination table, so
     * the analyzer and the resolver can never disagree about what
     * counts as navigation.
     */
    private fun isNavStepRetry(step: PlanStep, observation: ScreenModel?): Boolean {
        if (step.kind == TaskStepKind.NAVIGATE_TAB) return true
        if (step.kind != TaskStepKind.TAP_TARGET) return false
        val target = step.params[PlanParams.TARGET].orEmpty()
        if (target.isBlank()) return false
        val screen = observation ?: return false
        return com.loom.assistant.execution.screenawareness.NavigationResolver
            .isKnownDestination(target) &&
            com.loom.assistant.execution.screenawareness.NavigationResolver
                .navigationContainers(screen).isNotEmpty()
    }

    private fun replanOrAbort(
        planId: String,
        attempt: Int,
        reason: String,
        fixSuggestion: String,
        userMessage: String,
    ): FailureAnalysis {
        return if (replansFor(planId) >= MAX_REPLANS) {
            FailureAnalysis(
                decision = FailureDecision.ABORT,
                reason = "$reason (replan budget of $MAX_REPLANS spent)",
                fixSuggestion = "",
                userMessage = "I tried two different approaches and I'm still stuck.",
                attempt = attempt,
            )
        } else {
            FailureAnalysis(
                decision = FailureDecision.REPLAN,
                reason = reason,
                fixSuggestion = fixSuggestion,
                userMessage = userMessage,
                attempt = attempt,
            )
        }
    }

    // ---------- error classification ----------

    enum class ErrorClass {
        TRANSIENT,
        TARGET_MOVED,
        TARGET_GONE,
        UNSAFE,
        UNKNOWN,
        /**
         * App-aware execution (2026-11-07): the submit was dispatched
         * but the search never executed (no results, keyboard open).
         */
        SUBMIT_NOT_EXECUTED,
        /** The foreground screen is not the one the step expected. */
        UNEXPECTED_SCREEN,
        /** The keyboard is still open after a submit attempt. */
        KEYBOARD_STILL_OPEN,
    }

    /**
     * Classifies a submit failure from the before/after screen pair,
     * via the [SubmitVerifier] — never from the dispatch result
     * alone. Executed means the failure lies elsewhere (UNKNOWN);
     * NotExecuted with the keyboard still open is KEYBOARD_STILL_OPEN,
     * otherwise SUBMIT_NOT_EXECUTED; unreadable screens are UNKNOWN.
     */
    fun classifySubmitFailure(
        before: ScreenModel?,
        after: ScreenModel?,
        query: String,
    ): ErrorClass {
        if (before == null || after == null) return ErrorClass.UNKNOWN
        return when (SubmitVerifier.verify(before, after, query)) {
            is SubmitVerification.Executed -> ErrorClass.UNKNOWN
            is SubmitVerification.NotExecuted ->
                if (after.keyboardVisible) ErrorClass.KEYBOARD_STILL_OPEN
                else ErrorClass.SUBMIT_NOT_EXECUTED
            is SubmitVerification.Unknown -> ErrorClass.UNKNOWN
        }
    }

    /**
     * Whether the fresh [observation] is an unexpected screen for
     * [step]: the error says so, a dialog is showing, or — for the
     * input-injection kinds only (TYPE_TEXT / SUBMIT, which have no
     * app-transition path) — the foreground package is not the step's
     * expected package. TAP_TARGET may legitimately target another
     * app (out-of-scope path) and SEARCH_IN_APP launches its own app,
     * so a package mismatch is not "unexpected" for them. Null
     * observation or no package expectation means "cannot tell" —
     * never an unexpected screen on missing evidence.
     */
    fun isUnexpectedScreen(step: PlanStep, observation: ScreenModel?, error: String): Boolean {
        if (UNEXPECTED_SCREEN_PATTERNS.any { it.containsMatchIn(error) }) return true
        val screen = observation ?: return false
        if (screen.elements.any { it.role == com.loom.assistant.execution.screenawareness.ElementRole.DIALOG }) return true
        val expectedPkg = step.params[PlanParams.PACKAGE]
        if (step.kind in setOf(TaskStepKind.TYPE_TEXT, TaskStepKind.SUBMIT) &&
            !expectedPkg.isNullOrBlank() && screen.packageName != expectedPkg
        ) {
            return true
        }
        return false
    }

    private fun unexpectedScreenReason(
        step: PlanStep,
        observation: ScreenModel?,
        error: String,
    ): String {
        val expectedPkg = step.params[PlanParams.PACKAGE]
        if (observation != null && !expectedPkg.isNullOrBlank() && observation.packageName != expectedPkg) {
            return "expected $expectedPkg but ${observation.packageName} is in the foreground"
        }
        if (observation != null && observation.elements.any {
                it.role == com.loom.assistant.execution.screenawareness.ElementRole.DIALOG
            }
        ) {
            return "a dialog is showing"
        }
        return error
    }

    /**
     * Classifies a failure string. Target state from the fresh
     * observation wins over the string when they disagree — the
     * screen is ground truth, the message is a hint.
     */
    fun classifyError(error: String, targetState: TargetState): ErrorClass {
        if (UNSAFE_PATTERNS.any { it.containsMatchIn(error) }) return ErrorClass.UNSAFE
        if (SUBMIT_NOT_EXECUTED_PATTERNS.any { it.containsMatchIn(error) }) return ErrorClass.SUBMIT_NOT_EXECUTED
        if (KEYBOARD_STILL_OPEN_PATTERNS.any { it.containsMatchIn(error) }) return ErrorClass.KEYBOARD_STILL_OPEN
        if (UNEXPECTED_SCREEN_PATTERNS.any { it.containsMatchIn(error) }) return ErrorClass.UNEXPECTED_SCREEN
        if (targetState == TargetState.GONE) return ErrorClass.TARGET_GONE
        if (targetState == TargetState.MOVED) return ErrorClass.TARGET_MOVED
        if (GONE_PATTERNS.any { it.containsMatchIn(error) }) return ErrorClass.TARGET_GONE
        if (MOVED_PATTERNS.any { it.containsMatchIn(error) }) return ErrorClass.TARGET_MOVED
        if (TRANSIENT_PATTERNS.any { it.containsMatchIn(error) }) return ErrorClass.TRANSIENT
        return ErrorClass.UNKNOWN
    }

    // ---------- target re-resolution ----------

    /**
     * Re-resolves [step]'s target against a FRESH observation.
     *
     * - No target param (launch / wait / observe / verify steps) →
     *   PRESENT: there is nothing to lose.
     * - No observation (screen unreadable) → PRESENT: absence of
     *   evidence is not evidence of absence; the error string still
     *   classifies the failure.
     * - Target query matches an element with the same bounds as the
     *   step recorded → PRESENT; different bounds → MOVED; no match
     *   → GONE.
     *
     * Bounds compare uses the step's [PlanParams.BOUNDS] param
     * ("left,top,right,bottom"); without recorded bounds a match is
     * PRESENT (the executor records bounds when it first resolves).
     */
    fun resolveTarget(step: PlanStep, observation: ScreenModel?): TargetState {
        val query = step.params[PlanParams.TARGET] ?: return TargetState.PRESENT
        if (query.isBlank()) return TargetState.PRESENT
        val screen = observation ?: return TargetState.PRESENT
        // Common sense (2026-11-10): resolve through TargetAliases,
        // so a close label ("Close" for the X) and a perspective
        // label ("Your story" for "my story") resolve exactly as the
        // plan path resolves them — one resolver, one answer. A
        // voice control (the search mic) never counts as the target.
        // Enabled-only is kept: a disabled control is not tappable.
        val matches = TargetAliases.resolveElements(screen.elements, query)
            .filter { it.enabled }
        if (matches.isEmpty()) {
            // NAV-SCREEN fix (2026-11-11, N9): a navigation target
            // that no label matches may still be resolvable by the
            // tab resolver (icon-only tab, positional fallback) — it
            // is not gone, it is positional. A positionally resolved
            // tab reads as PRESENT here so the analyzer does not
            // abort a step the executor can still run.
            val navResolved = if (step.kind == TaskStepKind.NAVIGATE_TAB ||
                (step.kind == TaskStepKind.TAP_TARGET &&
                    com.loom.assistant.execution.screenawareness.NavigationResolver.isKnownDestination(query))
            ) {
                com.loom.assistant.execution.screenawareness.NavigationResolver
                    .resolveNavigationTarget(screen, query)
            } else emptyList()
            if (navResolved.isEmpty()) return TargetState.GONE
            val recorded = parseBounds(step.params[PlanParams.BOUNDS]) ?: return TargetState.PRESENT
            val samePlace = navResolved.any { nav ->
                val el = nav.element
                el.bounds.left == recorded[0] && el.bounds.top == recorded[1] &&
                    el.bounds.right == recorded[2] && el.bounds.bottom == recorded[3]
            }
            return if (samePlace) TargetState.PRESENT else TargetState.MOVED
        }
        val recorded = parseBounds(step.params[PlanParams.BOUNDS]) ?: return TargetState.PRESENT
        val samePlace = matches.any { el ->
            el.bounds.left == recorded[0] && el.bounds.top == recorded[1] &&
                el.bounds.right == recorded[2] && el.bounds.bottom == recorded[3]
        }
        return if (samePlace) TargetState.PRESENT else TargetState.MOVED
    }

    private fun parseBounds(raw: String?): IntArray? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 4) parts.toIntArray() else null
    }
}
