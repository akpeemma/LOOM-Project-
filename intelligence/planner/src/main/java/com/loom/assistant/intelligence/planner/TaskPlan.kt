package com.loom.assistant.intelligence.planner

import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.TaskRecord
import java.util.UUID

/**
 * Stage 2D — planner core data model.
 *
 * Pure Kotlin: no android.* imports, no dispatch logic, no AuthGate
 * coupling. The planner only PRODUCES plans; Worker B (executor)
 * consumes them. Port of the Brahma `brahma_core` planner/executor
 * semantics (goal → ordered steps; max 3 attempts per step; max 2
 * replans; honest summary) to LOOM's on-device step vocabulary.
 *
 * NOTE (2026-11-06, coordinator correction): [TaskStepKind] is the
 * planner's OWN enum. It deliberately does NOT extend or reuse
 * `execution:interaction`'s `PlanStepKind` (TAP, LONG_PRESS, TYPE,
 * SUBMIT) — that enum's semantics belong to the tap queue, which
 * fails honestly on non-TAP steps. The hand-off mapping lives in
 * [TaskStepKind.queueKindName] (KDoc table below) and the actual
 * translation is Worker B's job, in the module that already depends
 * on both sides.
 */

/**
 * One step's kind. Every kind maps to at most one hand-off:
 *
 * - [LAUNCH_APP] → executor launches the resolved package
 *   (`app.launch`); no `PlanStepKind` equivalent.
 * - [WAIT_FOR_APP] → executor polls foreground + readable tree; no
 *   `PlanStepKind` equivalent.
 * - [OBSERVE_SCREEN] → executor takes a fresh snapshot (read-only);
 *   no `PlanStepKind` equivalent.
 * - [SEARCH_IN_APP] → executor runs the SearchExecutor sequence
 *   (affordance → TYPE → SUBMIT internally); no single
 *   `PlanStepKind` equivalent.
 * - [TAP_TARGET] → `PlanStepKind.TAP` on the re-resolved target.
 * - [TYPE_TEXT] → `PlanStepKind.TYPE` with `params["text"]`.
 * - [SUBMIT] → `PlanStepKind.SUBMIT`.
 * - [SCROLL] / [GO_BACK] → executor's `device.scroll` /
 *   `device.back` sinks; no `PlanStepKind` equivalent.
 * - [VERIFY_OUTCOME] → read-only check against a fresh snapshot;
 *   no `PlanStepKind` equivalent.
 * - [GUIDE_TO] → pointer guidance only (highlight + instruction);
 *   no `PlanStepKind` equivalent.
 * - [MESSAGE_SEND] / [CALL_PLACE] → executor's `message.send` /
 *   `call.place` sinks (per-message / per-call confirmation);
 *   no `PlanStepKind` equivalent.
 * - [POINTER_MOVE] → display-only pointer nudge; no dispatch.
 * - [REPORT] → speak / show the outcome text; no dispatch.
 */
enum class TaskStepKind {
    LAUNCH_APP,
    WAIT_FOR_APP,
    OBSERVE_SCREEN,
    SEARCH_IN_APP,
    TAP_TARGET,
    TYPE_TEXT,
    SUBMIT,
    SCROLL,
    GO_BACK,
    VERIFY_OUTCOME,
    GUIDE_TO,
    MESSAGE_SEND,
    CALL_PLACE,
    POINTER_MOVE,
    REPORT,
    /**
     * Universal Console (2026-11-07, David's directive): the console
     * operations the dispatcher could not express before. LONG_PRESS
     * reuses the tap authorization (a tap held down — the service
     * sink already runs under `tap.guidance` / `tap.device`); SWIPE
     * reuses `device.scroll` (a swipe IS the gesture form of
     * scrolling); FOCUS_FIELD and OPEN_MENU reuse `tap.guidance`
     * (tap the field / the menu control). No new action key is
     * introduced by any of them — see PlanStepDispatcher.
     */
    LONG_PRESS,
    SWIPE,
    FOCUS_FIELD,
    OPEN_MENU,
    /**
     * MASTER Wave 3 (F15b) — play / pause the video on screen:
     * tap the video or its play/pause control on request ("play
     * this", "pause the video") and verify the player state changed
     * (playing ↔ paused) — a tap alone is not success. The control
     * is resolved from the live tree (a Pause control when playing,
     * a Play control when paused); the step's [PlanParams.TARGET]
     * names it explicitly when the planner knows it, and
     * [PlanParams.PLAY_STATE] ("play" | "pause") names the
     * requested state when the utterance names it — absent, the
     * step toggles to the opposite of the current state.
     */
    PLAY_PAUSE,
    /**
     * NAV-SCREEN fix (2026-11-11, N1) — navigate to a destination
     * INSIDE the current app ("open Reels", "go to Reels", "switch
     * to Notifications"): resolve the destination in the app's
     * navigation chrome (NavigationResolver — label → synonym →
     * ordinal → positional), tap it, and verify the destination
     * screen (NavigationVerification — tab selected / destination
     * content present + package unchanged). This is NOT a launch:
     * a destination that is not an installed app must never again
     * fall through to "I couldn't find an app called 'reels'".
     * Reuses the tap authorization (`tap.guidance`) — a navigation
     * tap IS a tap — and the executor's pointer drive.
     * [PlanParams.TARGET] names the destination.
     */
    NAVIGATE_TAB,
}

/** Lifecycle of a whole plan (Brahma `TaskStatus` + approval states). */
enum class PlanStatus {
    PLANNING,
    AWAITING_APPROVAL,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/** Lifecycle of one step. UNVERIFIED is the honest default — a step
 *  is VERIFIED only when a fresh observation confirms its effect;
 *  dispatch alone never verifies. */
enum class StepStatus {
    PENDING,
    RUNNING,
    VERIFIED,
    FAILED,
    SKIPPED,
    UNVERIFIED,
}

/**
 * One ordered step. [params] carries only plain strings, keyed by
 * [PlanParams]; every value the executor needs (app label, resolved
 * package, query, target text, direction) travels here so steps stay
 * independent — no step references another step's result
 * (Brahma planner rule).
 *
 * AOIE Phase 1 — the typed contract fields (blueprint §4). [actionId]
 * is the stable per-action identifier carried from plan → dispatch
 * → gesture → verification → audit; it survives replan (a re-issued
 * step keeps its id). [timeoutMs] is the per-step deadline (0 =
 * the executor's default for the kind). [riskLevel] and
 * [verificationMethod] are the planner's declarations of what the
 * step may do and how its effect is proven; the dispatcher and
 * executor read them instead of guessing from the kind alone.
 */
data class PlanStep(
    val index: Int,
    val kind: TaskStepKind,
    val description: String,
    val params: Map<String, String> = emptyMap(),
    val critical: Boolean = true,
    val status: StepStatus = StepStatus.PENDING,
    val actionId: String = "act-" + UUID.randomUUID().toString().take(8),
    /** Per-step deadline in ms (0 = executor default for the kind). */
    val timeoutMs: Long = 0L,
    val riskLevel: RiskLevel = if (kind == TaskStepKind.MESSAGE_SEND || kind == TaskStepKind.CALL_PLACE) {
        RiskLevel.CONSEQUENTIAL
    } else if (kind in setOf(
            TaskStepKind.OBSERVE_SCREEN, TaskStepKind.VERIFY_OUTCOME,
            TaskStepKind.GUIDE_TO, TaskStepKind.REPORT, TaskStepKind.POINTER_MOVE,
        )
    ) {
        RiskLevel.LOW
    } else {
        RiskLevel.ORDINARY
    },
    val verificationMethod: VerificationMethod = when (kind) {
        TaskStepKind.FOCUS_FIELD -> VerificationMethod.FIELD_FOCUSED
        TaskStepKind.LAUNCH_APP, TaskStepKind.WAIT_FOR_APP ->
            VerificationMethod.FOREGROUND_PACKAGE
        TaskStepKind.SUBMIT -> VerificationMethod.SUBMIT_EXECUTED
        TaskStepKind.VERIFY_OUTCOME -> VerificationMethod.SCREEN_CONTAINS
        // NAV-SCREEN fix (2026-11-11, N1/N4): a navigation step is
        // verified by its destination, never by a bare screen change.
        TaskStepKind.NAVIGATE_TAB -> VerificationMethod.NAVIGATION_DESTINATION
        TaskStepKind.POINTER_MOVE, TaskStepKind.GUIDE_TO, TaskStepKind.REPORT ->
            VerificationMethod.UNVERIFIABLE
        else -> VerificationMethod.SCREEN_CHANGED
    },
    /** The observable effect the step must produce, in plain words
     *  (mirrors params[EXPECTED] where the planner sets one). */
    val expectedPostcondition: String = "",
)

/**
 * An ordered plan for one goal. [replanCount] counts rebuilds of the
 * remaining work after failure (Brahma `MAX_REPLAN_ATTEMPTS = 2`).
 * [record] is the task record for this plan (task-record fix,
 * 2026-11-07): the planner builds it once from the instruction and
 * attaches it here; the executor carries this SAME record unchanged
 * through approval (approval flips only its status) and through
 * replans ([TaskPlanner.replan] copies the plan, record included —
 * a replan never rebuilds from the bare goal, so it can never lose
 * the app). Null only for plans built outside the planner (tests,
 * the LLM validator path before it attaches the request's record).
 */
data class TaskPlan(
    val id: String,
    val goal: String,
    val steps: List<PlanStep>,
    val origin: RequestOrigin,
    val status: PlanStatus = PlanStatus.PLANNING,
    val replanCount: Int = 0,
    val record: TaskRecord? = null,
)

/**
 * The final report for a plan. [summary] is honest by construction:
 * it never claims success for steps that were not verified — see
 * [buildOutcome]. [totalSteps] counts every step in the plan as
 * built; [verifiedSteps] counts only VERIFIED ones.
 */
data class PlanOutcome(
    val status: PlanStatus,
    val verifiedSteps: Int,
    val totalSteps: Int,
    val failedStep: PlanStep?,
    val summary: String,
)

/** Well-known [PlanStep.params] keys. The validator allowlists
 *  per-kind keys from this set; the executor reads them from it. */
object PlanParams {
    /** Human-readable app label, e.g. "YouTube Music". */
    const val APP = "app"

    /** Resolved package name, e.g. "com.google.android.apps.youtube.music". */
    const val PACKAGE = "package"

    /** Search / play query, e.g. "Davido". */
    const val QUERY = "query"

    /** Target element query (text / content-description to match). */
    const val TARGET = "target"

    /**
     * How the target is picked. MASTER Wave 3 (F9): "first" is a
     * USER-DIRECTED first selector — the planner emits it only when
     * the user explicitly said "first" ("the first result", "first
     * one"); the dispatcher/executor then tap the first match. A
     * bare tap carries no MATCH, so several matches refuse honestly
     * ("N matches — be more specific") instead of tapping the first
     * one. The old planner emitted MATCH="first" on EVERY tap/select
     * step — that default was the blind pick, and it is gone. Result
     * selection after verified results is the search sequence's job
     * ([SearchGoal.OPEN_FIRST_RESULT]), never a blind TAP_TARGET.
     */
    const val MATCH = "match"

    /**
     * MASTER Wave 3 (F15b) — the requested player state for a
     * PLAY_PAUSE step: "play" or "pause". Absent = toggle to the
     * opposite of the current player state.
     */
    const val PLAY_STATE = "playState"

    /** Text to type (TYPE_TEXT) or the dictated message (MESSAGE_SEND). */
    const val TEXT = "text"

    /** Message recipient (display name as David said it). */
    const val RECIPIENT = "recipient"

    /** Dictated message body (MESSAGE_SEND). */
    const val MESSAGE = "message"

    /** Message channel: "whatsapp" or "sms". */
    const val CHANNEL = "channel"

    /** Pointer direction: "left" | "right" | "up" | "down". */
    const val DIRECTION = "direction"

    /** Guidance destination ("guide me to <x>"). */
    const val GUIDE_TARGET = "guideTarget"

    /** Expected observable effect, e.g. "playback started". */
    const val EXPECTED = "expected"

    /** Last-known bounds "left,top,right,bottom" (TOCTOU re-resolution). */
    const val BOUNDS = "bounds"

    /**
     * App-aware execution (2026-11-07): the remaining submit
     * mechanisms for a SEARCH_IN_APP retry, comma-separated
     * (e.g. "KEYBOARD_ACTION_KEY,SUGGESTION_TAP,SEARCH_BUTTON").
     * Set by the executor on a TRY_ALTERNATIVE retry; read by the
     * dispatcher, which passes it to the search sequence.
     */
    const val SUBMIT_ORDER = "submitOrder"

    /**
     * Universal Console (2026-11-07): what a SEARCH_IN_APP step is
     * FOR — the goal the search must stop at. "show_results" stops
     * at the verified results list (a plain search: "Search YouTube
     * for Burna Boy", "Search Chrome for the best Android phones");
     * "open_first" opens the first result after the results are
     * verified (a play: the search sequence itself opens it, so no
     * separate TAP_TARGET follows). Before this param existed the
     * search sequence ALWAYS opened the first result — a plain
     * search opened a video it was never asked to open, and a play
     * plan then tapped the result a second time.
     */
    const val SEARCH_GOAL = "searchGoal"
    const val SEARCH_GOAL_SHOW_RESULTS = "show_results"
    const val SEARCH_GOAL_OPEN_FIRST = "open_first"

    /** Re-resolution hint set by replan: "true" when the target moved. */
    const val RERESOLVE = "reresolve"

    /**
     * BUILT-IN RECOVERY (2026-11-09): marks a step the planner's
     * replan inserted as a recovery action, so the executor and the
     * analyzer can treat it as one. Values: [RECOVERY_GO_BACK]
     * ("go back to where I was" after a wrong page) and
     * [RECOVERY_CLEAR_RETRY] ("clear the search and try again"
     * after a wrong search). A recovery step runs under the same
     * covered plan path as every other step — the mark changes how
     * failures are handled (a gone recovery target skips instead
     * of aborting to ask David), never how it is authorized.
     */
    const val RECOVERY = "recovery"
    const val RECOVERY_GO_BACK = "go-back"
    const val RECOVERY_CLEAR_RETRY = "clear-retry"

    /**
     * AOIE Phase 2 — the keyboard intent for a SUBMIT step
     * ("submit" | "confirm" | "dismiss" | "newline", see
     * [com.loom.assistant.core.common.KeyboardAction]). The
     * dispatcher reads it instead of assuming every submit is the
     * same intent. Absent = "submit".
     */
    const val KEYBOARD_ACTION = "keyboardAction"

    /**
     * AOIE Phase 1 — per-step deadline in ms, as a string (params
     * stay strings). Honoured by the dispatcher for WAIT_FOR_APP
     * (clamped 1 s–120 s); stored on every other kind for the
     * executor's timeout work. Absent = the executor's default.
     */
    const val TIMEOUT_MS = "timeoutMs"
}

/**
 * Builds the honest end-of-plan report (Brahma executor `_summarize`,
 * with the LOOM truthfulness rule: dispatch alone is never success).
 *
 * - All steps VERIFIED → COMPLETED, "Done — ...".
 * - Otherwise → the caller's [status] (FAILED / CANCELLED /
 *   INTERRUPTED), naming how many steps were verified out of the
 *   total and which step stopped the plan. Unverified steps are
 *   never described as done.
 */
fun buildOutcome(
    plan: TaskPlan,
    verifiedSteps: List<PlanStep>,
    failedStep: PlanStep?,
    status: PlanStatus,
): PlanOutcome {
    val total = plan.steps.size
    val verified = verifiedSteps.size
    val summary = when {
        failedStep == null && verified == total ->
            "Done — completed all $total step(s) for: ${plan.goal}."
        failedStep != null ->
            "Stopped at step ${failedStep.index + 1} (${failedStep.description}). " +
                "Verified $verified of $total step(s); " +
                "${failedStep.description} was not completed, so the task is not done."
        else ->
            "Finished with $verified of $total step(s) verified for: ${plan.goal}. " +
                "The remaining step(s) were not verified, so the task is not confirmed done."
    }
    return PlanOutcome(
        status = status,
        verifiedSteps = verified,
        totalSteps = total,
        failedStep = failedStep,
        summary = summary,
    )
}
