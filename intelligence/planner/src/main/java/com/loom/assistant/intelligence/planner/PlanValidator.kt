package com.loom.assistant.intelligence.planner

import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import java.util.UUID

/**
 * Stage 2D — the grounded-fallback gate for LLM-proposed plans.
 *
 * The model may PROPOSE a plan (raw JSON); the app DISPOSES. A
 * proposed plan runs ONLY when it passes every check here. Anything
 * invalid is rejected with reasons and never executed — the planner
 * then falls back to its rule-based decomposition.
 *
 * Pure Kotlin, no android.* imports. The JSON reader is a small
 * hand-rolled parser for the plan shape (objects, arrays, strings
 * with escapes, numbers, booleans, null) so the validator needs no
 * JSON library on device.
 */
class PlanValidator {

    companion object {
        /** Hard cap on steps per plan (Brahma planner: max 5; LOOM
         *  allows more for multi-step sequences, but never unbounded). */
        const val MAX_STEPS = 20

        /** Hard cap on any single param value. */
        const val MAX_PARAM_VALUE_LENGTH = 1000

        /** Hard cap on a step description. */
        const val MAX_DESCRIPTION_LENGTH = 500

        /** Kinds that ACT inside an app. Inside a sensitive app these
         *  are rejected outright; only guidance-only kinds
         *  (OBSERVE_SCREEN, WAIT_FOR_APP, GUIDE_TO, REPORT,
         *  VERIFY_OUTCOME) may name a sensitive target. */
        val ACTION_KINDS: Set<TaskStepKind> = setOf(
            TaskStepKind.LAUNCH_APP,
            TaskStepKind.SEARCH_IN_APP,
            TaskStepKind.TAP_TARGET,
            TaskStepKind.TYPE_TEXT,
            TaskStepKind.SUBMIT,
            TaskStepKind.SCROLL,
            TaskStepKind.GO_BACK,
            TaskStepKind.MESSAGE_SEND,
            TaskStepKind.CALL_PLACE,
            TaskStepKind.POINTER_MOVE,
            // Universal Console (2026-11-07): console operations act
            // inside an app, so they are rejected outright inside a
            // sensitive app, like every other action kind here.
            TaskStepKind.LONG_PRESS,
            TaskStepKind.SWIPE,
            TaskStepKind.FOCUS_FIELD,
            TaskStepKind.OPEN_MENU,
            // MASTER Wave 3 (F15b): play/pause acts inside an app.
            TaskStepKind.PLAY_PAUSE,
            // NAV-SCREEN fix (2026-11-11, N1): navigation taps act
            // inside an app — rejected outright inside a sensitive app.
            TaskStepKind.NAVIGATE_TAB,
        )

        /**
         * Device-failure fix (2026-11-13, F2) — kinds that ACT inside
         * an app and therefore need that app open first. A step of
         * one of these kinds that NAMES its app (APP or PACKAGE
         * param) is app-scoped: a LAUNCH_APP for the same app must
         * precede it in the plan (see [validateLaunchOrder]).
         * Deliberately NOT here: LAUNCH_APP / WAIT_FOR_APP /
         * OBSERVE_SCREEN / REPORT (they bring up or read the screen),
         * VERIFY_OUTCOME (a check, package-gated at verify time),
         * GUIDE_TO (display-only guidance on the current screen),
         * MESSAGE_SEND / CALL_PLACE (their executors own the launch),
         * POINTER_MOVE (display-only), GO_BACK (leaves a screen).
         * A step of these kinds that names NO app acts on the
         * current screen (media controls, follow-up scrolls) and is
         * not app-scoped — the rule never demands a launch for it.
         */
        val APP_SCOPED_KINDS: Set<TaskStepKind> = setOf(
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
            TaskStepKind.PLAY_PAUSE,
        )

        /** Required param keys per kind; empty = none required. */
        val REQUIRED_PARAMS: Map<TaskStepKind, List<String>> = mapOf(
            TaskStepKind.LAUNCH_APP to listOf(PlanParams.APP),
            TaskStepKind.WAIT_FOR_APP to listOf(PlanParams.APP),
            TaskStepKind.SEARCH_IN_APP to listOf(PlanParams.QUERY),
            TaskStepKind.TAP_TARGET to listOf(PlanParams.TARGET),
            TaskStepKind.TYPE_TEXT to listOf(PlanParams.TEXT),
            TaskStepKind.GUIDE_TO to listOf(PlanParams.GUIDE_TARGET),
            TaskStepKind.MESSAGE_SEND to
                listOf(PlanParams.RECIPIENT, PlanParams.MESSAGE, PlanParams.CHANNEL),
            TaskStepKind.CALL_PLACE to listOf(PlanParams.RECIPIENT),
            TaskStepKind.POINTER_MOVE to listOf(PlanParams.DIRECTION),
            TaskStepKind.LONG_PRESS to listOf(PlanParams.TARGET),
            TaskStepKind.SWIPE to listOf(PlanParams.DIRECTION),
            TaskStepKind.FOCUS_FIELD to listOf(PlanParams.TARGET),
            // NAV-SCREEN fix (2026-11-11, N1): a navigation step names its destination.
            TaskStepKind.NAVIGATE_TAB to listOf(PlanParams.TARGET),
        )

        /** Allowed param keys per kind (required + optional). Any
         *  other key is rejected — the gate is strict on purpose. */
        val ALLOWED_PARAMS: Map<TaskStepKind, Set<String>> = mapOf(
            TaskStepKind.LAUNCH_APP to setOf(PlanParams.APP, PlanParams.PACKAGE),
            // AOIE Phase 1: per-step deadline in ms.
            TaskStepKind.WAIT_FOR_APP to setOf(PlanParams.APP, PlanParams.PACKAGE, PlanParams.TIMEOUT_MS),
            TaskStepKind.OBSERVE_SCREEN to setOf(PlanParams.APP, PlanParams.PACKAGE),
            TaskStepKind.SEARCH_IN_APP to
                setOf(PlanParams.APP, PlanParams.PACKAGE, PlanParams.QUERY,
                    PlanParams.SUBMIT_ORDER, PlanParams.SEARCH_GOAL, PlanParams.TIMEOUT_MS),
            TaskStepKind.TAP_TARGET to
                setOf(PlanParams.TARGET, PlanParams.MATCH, PlanParams.BOUNDS, PlanParams.RERESOLVE, PlanParams.PACKAGE),
            TaskStepKind.TYPE_TEXT to setOf(PlanParams.TEXT, PlanParams.TARGET, PlanParams.PACKAGE),
            // AOIE Phase 2: the keyboard intent for a SUBMIT step
            // ("submit" | "confirm" | "dismiss" | "newline").
            TaskStepKind.SUBMIT to setOf(PlanParams.TARGET, PlanParams.KEYBOARD_ACTION, PlanParams.PACKAGE),
            TaskStepKind.SCROLL to setOf(PlanParams.DIRECTION, PlanParams.TARGET, PlanParams.PACKAGE),
            TaskStepKind.GO_BACK to emptySet(),
            // Universal Console (2026-11-07): the console kinds'
            // params. OPEN_MENU needs none — the menu control is
            // resolved from the live tree, never named in the plan.
            TaskStepKind.LONG_PRESS to
                setOf(PlanParams.TARGET, PlanParams.MATCH, PlanParams.BOUNDS, PlanParams.PACKAGE),
            TaskStepKind.SWIPE to
                setOf(PlanParams.DIRECTION, PlanParams.TARGET, PlanParams.PACKAGE),
            TaskStepKind.FOCUS_FIELD to
                setOf(PlanParams.TARGET, PlanParams.BOUNDS, PlanParams.PACKAGE),
            TaskStepKind.OPEN_MENU to setOf(PlanParams.TARGET, PlanParams.PACKAGE),
            // MASTER Wave 3 (F15b): PLAY_PAUSE names the control
            // (TARGET, optional — the dispatcher resolves the
            // play/pause control from the live tree when unnamed)
            // and the requested state (PLAY_STATE, optional).
            TaskStepKind.PLAY_PAUSE to
                setOf(PlanParams.TARGET, PlanParams.PLAY_STATE, PlanParams.PACKAGE),
            // NAV-SCREEN fix (2026-11-11, N1): NAVIGATE_TAB names
            // the destination (TARGET, required) and rides the same
            // optional params as TAP_TARGET (MATCH / BOUNDS /
            // RERESOLVE / PACKAGE) — the dispatcher re-resolves it
            // live, exactly like a tap.
            TaskStepKind.NAVIGATE_TAB to
                setOf(PlanParams.TARGET, PlanParams.MATCH, PlanParams.BOUNDS, PlanParams.RERESOLVE, PlanParams.PACKAGE),
            // Simple-task execution fix (2026-11-07): PACKAGE rides
            // on TAP_TARGET / VERIFY_OUTCOME too now (the planner
            // carries the resolved package through every step), so
            // the allowlist accepts it — the executor's cover and
            // pre-step checks read it from the step.
            TaskStepKind.VERIFY_OUTCOME to setOf(PlanParams.EXPECTED, PlanParams.TARGET, PlanParams.PACKAGE),
            TaskStepKind.GUIDE_TO to setOf(PlanParams.GUIDE_TARGET, PlanParams.PACKAGE),
            TaskStepKind.MESSAGE_SEND to
                setOf(PlanParams.RECIPIENT, PlanParams.MESSAGE, PlanParams.CHANNEL),
            TaskStepKind.CALL_PLACE to setOf(PlanParams.RECIPIENT),
            TaskStepKind.POINTER_MOVE to setOf(PlanParams.DIRECTION),
            TaskStepKind.REPORT to setOf(PlanParams.TEXT, PlanParams.EXPECTED),
        )
    }

    sealed class ParseOutcome {
        data class Valid(val plan: TaskPlan) : ParseOutcome()
        data class Invalid(val reasons: List<String>) : ParseOutcome()
    }

    sealed class ValidationResult {
        data class Valid(val plan: TaskPlan) : ValidationResult()
        data class Invalid(val reasons: List<String>) : ValidationResult()
    }

    /**
     * Parses LLM-proposed plan JSON into a [TaskPlan] and validates
     * it in one call. [request] stamps the goal + origin (the model's
     * own goal/origin fields, if any, are ignored — provenance comes
     * from the entry point, never from model output). Never throws:
     * malformed JSON is an [ParseOutcome.Invalid].
     *
     * Expected shape:
     * {"goal": "...", "steps": [{"kind": "LAUNCH_APP",
     *   "description": "...", "params": {"app": "..."},
     *   "critical": true}]}
     */
    fun parsePlanJson(json: String, request: PlanRequest): ParseOutcome {
        val value = try {
            JsonParser(json).parse()
        } catch (e: Exception) {
            return ParseOutcome.Invalid(listOf("plan JSON does not parse: ${e.message}"))
        }
        val obj = value as? JsonValue.Obj
            ?: return ParseOutcome.Invalid(listOf("plan JSON must be an object"))
        val stepsRaw = obj.fields["steps"] as? JsonValue.Arr
            ?: return ParseOutcome.Invalid(listOf("plan has no steps array"))
        val reasons = mutableListOf<String>()
        if (stepsRaw.items.isEmpty()) reasons.add("plan has no steps")
        if (stepsRaw.items.size > MAX_STEPS) {
            reasons.add("plan has ${stepsRaw.items.size} steps, max is $MAX_STEPS")
        }
        val steps = mutableListOf<PlanStep>()
        for ((i, raw) in stepsRaw.items.withIndex()) {
            val stepObj = raw as? JsonValue.Obj
            if (stepObj == null) {
                reasons.add("step ${i + 1} is not an object")
                continue
            }
            val kindRaw = (stepObj.fields["kind"] as? JsonValue.Str)?.value
                ?: (stepObj.fields["tool"] as? JsonValue.Str)?.value
            if (kindRaw == null) {
                reasons.add("step ${i + 1} has no kind")
                continue
            }
            val kind = try {
                TaskStepKind.valueOf(kindRaw.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                reasons.add("step ${i + 1} has unknown kind: $kindRaw")
                continue
            }
            val description = (stepObj.fields["description"] as? JsonValue.Str)?.value ?: ""
            val params = mutableMapOf<String, String>()
            val paramsRaw = stepObj.fields["params"] as? JsonValue.Obj
            if (paramsRaw != null) {
                for ((k, v) in paramsRaw.fields) {
                    // AOIE Phase 5: model-supplied bounds are never
                    // trusted — coordinates are re-resolved from the
                    // live tree at dispatch time. Dropped silently
                    // (the step still runs; only the stale coordinate
                    // is refused).
                    if (k == PlanParams.BOUNDS) continue
                    val s = (v as? JsonValue.Str)?.value
                        ?: (v as? JsonValue.Num)?.raw
                        ?: ((v as? JsonValue.Bool)?.value?.toString())
                        ?: continue
                    params[k] = s
                }
            }
            val critical = (stepObj.fields["critical"] as? JsonValue.Bool)?.value ?: true
            steps.add(PlanStep(index = i, kind = kind, description = description, params = params, critical = critical))
        }
        if (reasons.isNotEmpty()) return ParseOutcome.Invalid(reasons)
        val plan = TaskPlan(
            id = "plan-llm-" + UUID.randomUUID().toString().take(8),
            goal = request.goal,
            steps = steps,
            origin = request.origin,
            status = PlanStatus.PLANNING,
            replanCount = 0,
            // Task-record fix: an LLM-proposed plan carries the same
            // record the request arrived with (the planner overwrites
            // it with the resolved record after validation — the model
            // never rewrites what was understood from David's words).
            record = request.taskRecord,
        )
        return when (val check = validate(plan)) {
            is ValidationResult.Valid -> ParseOutcome.Valid(check.plan)
            is ValidationResult.Invalid -> ParseOutcome.Invalid(check.reasons)
        }
    }

    /**
     * Validates an already-built [TaskPlan] (LLM-proposed or
     * rule-built). Checks, in order: non-empty steps, step cap,
     * allowlisted kinds (enforced by the type itself — an unknown
     * kind cannot be constructed, so this is the parse path's job),
     * descriptions present and bounded, required params present and
     * non-blank, no unknown param keys, no absurd param lengths,
     * and no action kind targeting a sensitive app.
     */
    fun validate(plan: TaskPlan): ValidationResult {
        val reasons = mutableListOf<String>()
        if (plan.goal.isBlank()) reasons.add("plan goal is blank")
        if (plan.steps.isEmpty()) reasons.add("plan has no steps")
        if (plan.steps.size > MAX_STEPS) {
            reasons.add("plan has ${plan.steps.size} steps, max is $MAX_STEPS")
        }
        for ((i, step) in plan.steps.withIndex()) {
            val n = i + 1
            if (step.description.isBlank()) reasons.add("step $n has no description")
            if (step.description.length > MAX_DESCRIPTION_LENGTH) {
                reasons.add("step $n description is ${step.description.length} chars, max is $MAX_DESCRIPTION_LENGTH")
            }
            val allowed = ALLOWED_PARAMS[step.kind] ?: emptySet()
            for (key in step.params.keys) {
                if (key !in allowed) reasons.add("step $n (${step.kind}) has unknown param: $key")
            }
            for (key in REQUIRED_PARAMS[step.kind].orEmpty()) {
                val value = step.params[key]
                if (value.isNullOrBlank()) {
                    reasons.add("step $n (${step.kind}) is missing required param: $key")
                }
            }
            for ((key, value) in step.params) {
                if (value.length > MAX_PARAM_VALUE_LENGTH) {
                    reasons.add("step $n param $key is ${value.length} chars, max is $MAX_PARAM_VALUE_LENGTH")
                }
            }
            if (step.kind == TaskStepKind.POINTER_MOVE) {
                val dir = step.params[PlanParams.DIRECTION]?.lowercase()
                if (dir != null && dir !in setOf("left", "right", "up", "down")) {
                    reasons.add("step $n has invalid direction: $dir")
                }
            }
            // Universal Console (2026-11-07): a swipe without a valid
            // direction is never dispatched — the same check as a
            // pointer move, for the same reason (no guessing).
            if (step.kind == TaskStepKind.SWIPE) {
                val dir = step.params[PlanParams.DIRECTION]?.lowercase()
                if (dir.isNullOrBlank() || dir !in setOf("left", "right", "up", "down")) {
                    reasons.add("step $n has invalid direction: $dir")
                }
            }
            if (step.kind == TaskStepKind.MESSAGE_SEND) {
                val channel = step.params[PlanParams.CHANNEL]?.lowercase()
                if (channel != null && channel !in setOf("whatsapp", "sms")) {
                    reasons.add("step $n has invalid channel: $channel")
                }
            }
            // Sensitive-app gate: no action kind may target one.
            // Only the APP / PACKAGE params name an app — a TAP
            // target query names an element, not an app, and is
            // judged by the execution gates instead.
            if (step.kind in ACTION_KINDS) {
                val appTarget = step.params[PlanParams.APP]
                    ?: step.params[PlanParams.PACKAGE]
                if (appTarget != null && ScreenDataPolicy.isSensitivePackage(appTarget)) {
                    reasons.add(
                        "step $n (${step.kind}) targets a sensitive app " +
                            "($appTarget) — guidance-only, never an action",
                    )
                }
            }
            // MASTER Wave 4 (validator hardening) — MATCH="first" is
            // a USER-DIRECTED first selector only. The model planner
            // can still emit it in JSON; the validator now allows it
            // ONLY when the goal itself names "first" ("open the
            // first result", "tap the first one"). A model-proposed
            // first pick with no "first" in David's words is rejected
            // (the plan falls back to rules), so the model can never
            // blind-pick the first match on its own — the voice-search
            // mic was tapped exactly that way.
            if ((step.kind == TaskStepKind.TAP_TARGET || step.kind == TaskStepKind.LONG_PRESS) &&
                step.params[PlanParams.MATCH] == "first"
            ) {
                val goalNamesFirst =
                    Regex("\\bfirst\\b", RegexOption.IGNORE_CASE).containsMatchIn(plan.goal)
                if (!goalNamesFirst) {
                    reasons.add(
                        "step $n (${step.kind}) uses MATCH=first but the goal " +
                            "does not ask for the first — a model-proposed first pick is never trusted",
                    )
                }
            }
        }
        return if (reasons.isEmpty()) ValidationResult.Valid(plan)
        else ValidationResult.Invalid(reasons)
    }

    /**
     * Device-failure fix (2026-11-13, F2) — the launch-order rule,
     * enforced on BOTH plan paths (the planner repairs LLM plans
     * with a prepended launch when the app is known and rejects
     * them when it is not; the executor re-checks at the entry
     * gate and fails honestly before any dispatch).
     *
     * A plan is INVALID when an app-scoped step (see
     * [APP_SCOPED_KINDS]) that names its app appears before any
     * LAUNCH_APP for the same app (matched by PACKAGE, else by APP
     * label, case-insensitive). Such a plan would run against
     * whatever screen happens to be foreground — on Nov 13 it ran
     * against LOOM's own chat and reported "Done". An app-scoped
     * step with no launch anywhere is invalid; a plan with no
     * app-scoped step naming an app is not app-scoped and passes.
     */
    fun validateLaunchOrder(plan: TaskPlan): ValidationResult {
        val launchIndices = plan.steps.withIndex()
            .filter { it.value.kind == TaskStepKind.LAUNCH_APP }
            .map { it.index }
        for ((i, step) in plan.steps.withIndex()) {
            if (step.kind !in APP_SCOPED_KINDS) continue
            val stepPkg = step.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() }
            val stepApp = step.params[PlanParams.APP]?.takeIf { it.isNotBlank() }
            if (stepPkg == null && stepApp == null) continue
            val launched = launchIndices.any { li ->
                li < i && launchMatches(plan.steps[li], stepPkg, stepApp)
            }
            if (!launched) {
                return ValidationResult.Invalid(
                    listOf(
                        "step ${i + 1} (${step.kind}) acts in " +
                            "${stepApp ?: stepPkg} but no LAUNCH_APP for that app " +
                            "precedes it — the plan would run against the wrong screen",
                    ),
                )
            }
        }
        return ValidationResult.Valid(plan)
    }

    private fun launchMatches(launch: PlanStep, stepPkg: String?, stepApp: String?): Boolean {
        val launchPkg = launch.params[PlanParams.PACKAGE]?.takeIf { it.isNotBlank() }
        val launchApp = launch.params[PlanParams.APP]?.takeIf { it.isNotBlank() }
        if (stepPkg != null && launchPkg != null) return launchPkg == stepPkg
        if (stepApp != null && launchApp != null) {
            return launchApp.equals(stepApp, ignoreCase = true)
        }
        // One side names only a package, the other only a label:
        // unprovable match — never assume it.
        return false
    }

    // ---------- minimal JSON reader (plan shape only) ----------

    sealed class JsonValue {
        data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
        data class Arr(val items: List<JsonValue>) : JsonValue()
        data class Str(val value: String) : JsonValue()
        data class Num(val raw: String) : JsonValue()
        data class Bool(val value: Boolean) : JsonValue()
        data object Null : JsonValue()
    }

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parse(): JsonValue {
            val value = parseValue()
            skipWs()
            if (pos != text.length) throw IllegalArgumentException("trailing characters at $pos")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWs()
            if (pos >= text.length) throw IllegalArgumentException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> {
                    if (c == '-' || c.isDigit()) parseNum() else {
                        throw IllegalArgumentException("unexpected character '$c' at $pos")
                    }
                }
            }
        }

        private fun parseObj(): JsonValue.Obj {
            pos++ // {
            val fields = linkedMapOf<String, JsonValue>()
            skipWs()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWs()
                if (pos >= text.length || text[pos] != '"') {
                    throw IllegalArgumentException("expected string key at $pos")
                }
                val key = parseString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') {
                    throw IllegalArgumentException("expected ':' at $pos")
                }
                pos++
                fields[key] = parseValue()
                skipWs()
                when {
                    pos < text.length && text[pos] == ',' -> pos++
                    pos < text.length && text[pos] == '}' -> {
                        pos++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArr(): JsonValue.Arr {
            pos++ // [
            val items = mutableListOf<JsonValue>()
            skipWs()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWs()
                when {
                    pos < text.length && text[pos] == ',' -> pos++
                    pos < text.length && text[pos] == ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) break
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = text.substring(pos, (pos + 4).coerceAtMost(text.length))
                                pos += 4
                                out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun parseNum(): JsonValue.Num {
            val start = pos
            while (pos < text.length &&
                (text[pos].isDigit() || text[pos] in "-+.eE")
            ) pos++
            if (pos == start) throw IllegalArgumentException("bad number at $pos")
            return JsonValue.Num(text.substring(start, pos))
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) {
                throw IllegalArgumentException("expected $word at $pos")
            }
            pos += word.length
            return value
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
