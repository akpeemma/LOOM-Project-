package com.loom.assistant.intelligence.planner

/**
 * Device-test failures (2026-11-11, defects D-01/D-07) — task-text
 * sanitizer. Pure Kotlin, no Android imports, so the harness proves it
 * without a device.
 *
 * What David actually says is a sentence, not a step: "open my story
 * now you're on Facebook that's what I've told you to do so tap it
 * and open it", "search for Spider-Man no way home on YouTube now and
 * stop being stupid". The Nov 10 build carried those whole sentences
 * into the plan — the step name, the tap target, the task name — and
 * the target resolver then looked for a screen element containing the
 * whole sentence, which can never match. The common-sense resolver
 * (perspective + close match) never got a chance: it only works on a
 * short target ("my story" → "Your story").
 *
 * Rules (deterministic, no model call):
 * - A plan step is action + target, never the whole sentence. Everything
 *   after the first clause boundary that starts a new instruction, a
 *   context remark, or a filler/insult is cut: "now you're on …",
 *   "you're on …", "that's what …", "I've told you …", "so tap/click/
 *   open …", "and stop being …", and a trailing repeat ("and open it",
 *   "and tap it") at the end of the sentence.
 * - Genuine composites are NEVER cut: "open YouTube and play a song",
 *   "search for X in YouTube and open the first result" keep their
 *   second clause, because the cut patterns only match repeats and
 *   remarks, never a fresh action verb (play/search/open-the-first).
 * - [sanitizeTarget] additionally strips the leading action verb
 *   ("open", "tap", "click", "select") so a tap target is the thing,
 *   not the verb + thing.
 * - Cutting is by earliest match: the first boundary wins, so a
 *   sentence with several remarks keeps only its head.
 */
object TaskTextSanitizer {

    /**
     * Clause boundaries that end the task: the text from the match
     * onward is a remark, a repeat, or a filler — never part of the
     * action + target. Each pattern is a lowercased regex searched
     * against the lowercased input; the earliest match wins.
     */
    private val CUT_PATTERNS = listOf(
        // Context remarks: "now you're on Facebook", "you're on Facebook".
        Regex("""\s+now\s+you'?re\s+on\s+"""),
        Regex("""\s+you'?re\s+on\s+"""),
        Regex("""\s+you\s+are\s+on\s+"""),
        // Meta remarks: "that's what I've told you to do", "I've told you".
        Regex("""\s+that'?s\s+what\s+"""),
        Regex("""\s+i'?ve\s+told\s+you\s+"""),
        Regex("""\s+i\s+told\s+you\s+"""),
        Regex("""\s+what\s+i'?ve\s+told\s+you\s+"""),
        // Repeated instruction: "so tap it and open it", "so open it".
        Regex("""\s+so\s+(tap|click|open|press)\s+"""),
        // Filler / insult tail: "and stop being stupid".
        Regex("""\s+and\s+stop\s+being\s+"""),
        Regex("""\s+stop\s+being\s+stupid\b"""),
        // Trailing repeat verb at the very end: "and open it", "and tap it".
        Regex("""\s+and\s+(open|tap|click)\s+it\s*$"""),
        Regex("""\s+then\s+(open|tap|click)\s+it\s*$"""),
        // Behaviour meta-speech (device failure, 2026-11-13, F2):
        // remarks ABOUT LOOM — repeats, silence, inaction — that
        // David appends to a command ("search for Spider-Man No Way
        // Home don't make me repeat myself again", "search for it
        // you're still quiet you're not doing anything"). The whole
        // sentence used to become the task text AND the query. The
        // class, not just the three quoted phrases: repeat-myself
        // remarks, still-quiet / not-doing-anything remarks, and
        // didn't-hear / never-listen remarks — including STT
        // mishearings ("alarm" for "make me", "quite" for "quiet").
        // A bare trailing "again" is NEVER cut on its own ("play it
        // again" is a real task); it goes only with the remark that
        // introduces it.
        Regex("""\s+don'?t\s+(make|alarm|ask|have|force)\s+(me\s+)?(repeat|say\s+it|tell\s+you)\b"""),
        Regex("""\s+do\s+not\s+(make|ask|have|force)\s+(me\s+)?(repeat|say\s+it|tell\s+you)\b"""),
        Regex("""\s+repeat\s+myself\b"""),
        Regex("""\s+i\s+already\s+told\s+you\b"""),
        Regex("""\s+how\s+many\s+times\b"""),
        Regex("""\s+you'?re\s+still\s+(quiet|quite|not\s+doing|doing\s+nothing|not\s+listening)\b"""),
        Regex("""\s+you\s+are\s+still\s+(quiet|quite|not\s+doing|doing\s+nothing|not\s+listening)\b"""),
        Regex("""\s+you'?re\s+(not\s+doing\s+anything|doing\s+nothing|not\s+listening|quiet|quite)\b"""),
        Regex("""\s+you\s+are\s+(not\s+doing\s+anything|doing\s+nothing|not\s+listening|quiet|quite)\b"""),
        Regex("""\s+you\s+didn'?t\s+(do|hear|listen|understand)\b"""),
        Regex("""\s+you\s+never\s+(listen|do|understand)\b"""),
        Regex("""\s+are\s+you\s+(even\s+)?(listening|doing\s+anything|there)\b"""),
    )

    private val TRAILING_FILLERS = listOf("now", "please", "for me", "right now")

    private val LEADING_VERBS = listOf(
        "open ", "launch ", "tap ", "click on ", "click ", "select ",
        "press ", "show me ", "guide me to ", "go to ",
    )

    /**
     * The query head of [text]: remarks cut, WITHOUT the leading
     * "now" strip [sanitizeGoal] applies to whole goals. A query
     * may legitimately start with "now" ("now you see me"); a goal
     * never does. Used by the search-query/app extractors so
     * trailing meta-speech can never land in a query, and by
     * [sanitizePlan] for QUERY params.
     */
    fun sanitizeQuery(text: String): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        val lower = s.lowercase()
        var cutAt = s.length
        for (pattern in CUT_PATTERNS) {
            pattern.find(lower)?.let { m ->
                if (m.range.first < cutAt) cutAt = m.range.first
            }
        }
        s = s.substring(0, cutAt).trim()
        return s.trim().trimEnd('.', '!', '?').trim()
    }

    /** The task head of [text]: action + target, remarks cut. */
    fun sanitizeGoal(text: String): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        // Leading discourse filler: "now on tap on my story" is
        // David saying "now, tap on my story" — "now" / "now on" at
        // the very start is never part of the action or the target,
        // and without the strip no branch recognises the tap at all.
        var stripped: Boolean
        do {
            stripped = false
            for (lead in listOf("now on ", "now ")) {
                if (s.lowercase().startsWith(lead)) {
                    s = s.substring(lead.length).trim()
                    stripped = true
                    break
                }
            }
        } while (stripped)
        if (s.isEmpty()) return s
        val lower = s.lowercase()
        var cutAt = s.length
        for (pattern in CUT_PATTERNS) {
            pattern.find(lower)?.let { m ->
                if (m.range.first < cutAt) cutAt = m.range.first
            }
        }
        s = s.substring(0, cutAt).trim()
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
        return s.trim().trimEnd('.', '!', '?').trim()
    }

    /**
     * The tap/guide/search target inside [text]: the sanitized head
     * with its leading action verb stripped ("open my story" →
     * "my story"; "tap the first result" → "the first result").
     * A lone pronoun ("it", "that", "this") left after stripping is
     * not a target — it means the sentence named nothing.
     */
    fun sanitizeTarget(text: String): String {
        var s = sanitizeGoal(text).trim()
        val lower = s.lowercase()
        for (verb in LEADING_VERBS) {
            if (lower.startsWith(verb)) {
                s = s.substring(verb.length).trim()
                break
            }
        }
        s = s.trim().trimEnd('.', '!', '?').trim()
        if (s.lowercase() in setOf("it", "that", "this", "them")) return ""
        return s
    }

    /**
     * Sanitize a whole plan: the goal, every step description, and
     * every text param that names a target, a query, or expected
     * text (TARGET, GUIDE_TARGET, QUERY, TEXT, EXPECTED). Params
     * that name apps/packages/directions are left untouched — the
     * sanitizer only cuts remarks out of free text, never app names.
     * A step whose target sanitizes to blank keeps its original
     * param: a blank target fails later with "no target", never with
     * a silently invented one.
     */
    fun sanitizePlan(plan: TaskPlan): TaskPlan {
        val steps = plan.steps.map { step ->
            val params = step.params.toMutableMap()
            for (key in listOf(
                PlanParams.TARGET, PlanParams.GUIDE_TARGET,
                PlanParams.QUERY, PlanParams.TEXT, PlanParams.EXPECTED,
            )) {
                val value = params[key] ?: continue
                val clean = if (key == PlanParams.TARGET || key == PlanParams.GUIDE_TARGET) {
                    sanitizeTarget(value)
                } else if (key == PlanParams.QUERY) {
                    sanitizeQuery(value)
                } else {
                    sanitizeGoal(value)
                }
                if (clean.isNotBlank()) params[key] = clean
            }
            val description = sanitizeGoal(step.description)
            step.copy(
                description = description.ifBlank { step.description },
                params = params,
            )
        }
        val goal = sanitizeGoal(plan.goal)
        return plan.copy(
            goal = goal.ifBlank { plan.goal },
            steps = steps,
        )
    }
}
