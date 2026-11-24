package com.loom.assistant.core.common

/**
 * Voice commands.
 *
 * Stage 2A.1 (D2): the strict "loom, …" prefix rule is relaxed for
 * NON-DESTRUCTIVE operational commands — David's Part 3 examples
 * ("Switch to Orb mode.", "Activate Orb.", "Stop listening.") are unprefixed,
 * so mode switches, stop/start listening, stop talking, pause, resume and
 * switch-to-keyboard now match prefix-optional with natural variations.
 * The DESTRUCTIVE SHUTDOWN matches prefix-optional (2026-11-10) and
 * executes IMMEDIATELY with no confirmation question (David's
 * binding rule, Nov 13 ~03:36) — only the dedicated in-app shutdown
 * BUTTON keeps a confirmation dialog. The matcher's short-command
 * and negation guards are therefore the safety boundary.
 *
 * Matched commands are handled locally by the VoiceSessionManager and never
 * reach the chat engine (anti-roleplay is structural, not prompt-based).
 *
 * Lives in core:common (not platform:services) so both interaction:voice and
 * platform:services can depend on it without a module cycle.
 *
 * Correction pass: added STOP_LISTENING ("stop listening" is distinct from
 * "stop talking"), END_VOICE (voice-session-only shutdown — never the app
 * dialog), and MODE_ORB / MODE_CHARACTER / MODE_INVISIBLE (previously these
 * fell through to the LLM, which would *say* it switched modes while changing
 * nothing — ack-without-action by construction).
 *
 * Phase 2 (plan §4.2): GO_SILENT ("go quiet"/"be quiet", narrowed exact
 * matcher — David decision 3), SPEAK_AGAIN (clears the silent flag),
 * HIDE_YOURSELF / SHOW_ASSISTANT (view-only hide/restore, session untouched),
 * DEACTIVATE_ASSISTANT_MODE (end session → mic off → TTS clear → halt →
 * AuthGate purge → queue drain → views hidden → ASSISTANT_MODE_INACTIVE).
 * Unmatched lifecycle-class phrasings route to spoken clarification templates
 * via clarificationFor() — never the LLM (mismatch #4).
 */
enum class VoiceCommand {
    SHUTDOWN,
    STOP,
    STOP_LISTENING,
    START_LISTENING,
    PAUSE,
    RESUME,
    EXIT_VOICE,
    END_VOICE,
    SWITCH_KEYBOARD,
    MODE_ORB,
    MODE_CHARACTER,
    MODE_INVISIBLE,
    // Stage 2B (D3a): pointer visibility policy commands — handled locally
    // by the VoiceSessionManager, which persists the policy and only
    // confirms from the persisted read-back (the 2A.1 truthfulness rule).
    POINTER_SHOW,
    POINTER_HIDE,
    POINTER_AUTO,
    // "fix everything" (2026-11-05): direct the pointer itself —
    // display-only nudge, never a tap (taps stay AuthGate-gated).
    POINTER_MOVE,
    // Master §2 (item 4): search inside an app — "search for <query>
    // [in <app>]". Executes through Worker 4's SearchExecutor via the
    // host; the query/app split lives in extractSearchQuery /
    // extractSearchApp, and a missing app resolves from the last-turn
    // context (ConversationContext) or a clarification.
    SEARCH_IN_APP,
    // Master §2 (item 5): move the ASSISTANT itself (the floating
    // overlay), not the pointer — region phrasings ("move to the
    // top-left corner"), "move out of the way", "move down a bit",
    // and assistant-subject directions ("move the assistant left").
    // Executes through Worker 5's AssistantMover via the host.
    ASSISTANT_MOVE,
    // Stage 2C (§5.2 P2, §2a A4, §2d D2): interrupt-first + interaction
    // layer entries — all routed deterministically by the
    // VoiceSessionManager, before the conversational generator.
    HALT_SEQUENCE,
    GUIDE_TO,
    GUIDE_NEXT,
    GUIDE_BACK,
    DEMO_TAP,
    DEMO_SEQUENCE,
    // Device-evidence fix (2026-11-05): typed "can you open my Youtube"
    // was roleplayed then denied — launch IS wired (2C), so it gets a
    // real command that routes through AuthGate, never the LLM.
    LAUNCH_APP,
    // MASTER F1b: "go to X" / "take me to X" / "switch to X" — the
    // navigation phrasing David actually used ("Go to Google Play
    // Store"). A real command wired to the same launch handler as
    // LAUNCH_APP, never the mode question (see matchAmbiguous) and
    // never the model fallback.
    // NAV-SCREEN fix (2026-11-11, N1): the handler still normalises
    // to "open $app" — catalogue-first routing lives in the
    // planner, which now emits NAVIGATE_TAB for a known in-app
    // destination ("go to Reels") and LAUNCH_APP only for a real
    // app, so navigation no longer falls through to
    // "I couldn't find an app called 'reels'".
    GO_TO_APP,
    // MASTER F1d: deterministic console follow-ups — the turns David
    // used after a search ("scroll down", "go back", "click on
    // enter") matched NO command and fell to the model, whose
    // allowlist had no tap/scroll/enter capability, so it re-proposed
    // the same search. Each routes through the planner path (see
    // VoiceSessionManager.handleConsolePlan); BACK goes to the live
    // guidance session first when one is active.
    SCROLL,
    BACK,
    TAP,
    ENTER,
    // Dictated messaging + calls (2026-11-05, fix-plan item 12):
    // "send WhatsApp to X saying Y", "text X saying Y" /
    // "send SMS to X saying Y", "call X". Separate values (mirroring
    // LAUNCH_APP / SEARCH_IN_APP — not one command with a channel
    // flag) so each has its own matcher, extractor and host path.
    // Each executes through the message/call executors via the host;
    // the exact dictated words ride verbatim from the extractors.
    SEND_WHATSAPP_MESSAGE,
    SEND_SMS,
    PLACE_CALL,
    // Stage 2D: a genuinely multi-step instruction ("open YouTube Music
    // and play a song by Davido") — routed to the task planner via
    // CapabilityApi.PlanTask, never executed as a single command.
    // Single-action commands always win over this (see isPlanTask).
    PLAN_TASK,
    // Device-test failures (2026-11-11, D-04): "confirm your screen" /
    // "what's on my screen" / "what do you see" — David's own words for
    // a screen description from Screen Awareness (which app, what is
    // on it, where the named things are). A real command, never the
    // model: the Nov 10 build sent it to DeepSeek, which invented
    // "I don't have a 'confirm screen' command."
    DESCRIBE_SCREEN,
    // Phase 2 (plan §4.2): the 7-command set. GO_SILENT is narrowed to the
    // exact utterances "go quiet"/"be quiet" (David decision 3); SHOW_ASSISTANT
    // restores only the floating presentation (never reactivates, never mics).
    GO_SILENT,
    SPEAK_AGAIN,
    HIDE_YOURSELF,
    SHOW_ASSISTANT,
    // ACTIVATE ASSISTANT MODE (state-aware activation): service stopped →
    // start + apply + verify; service running → restore via applyMode.
    // Never starts a floating voice session; requires a live session
    // (lifecycle ACTIVE); never wakes from INACTIVE/SHUT_DOWN.
    ACTIVATE_ASSISTANT_MODE,
    DEACTIVATE_ASSISTANT_MODE,
}

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12): the
 * channel of a dictated message. WhatsApp and SMS are separate
 * executors paths over ONE MessageExecutor (app + step targets as
 * parameters) — the channel only selects the app.
 */
enum class MessageChannel {
    WHATSAPP,
    SMS,
}

object VoiceCommands {

    private val MODE_COMMANDS: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.MODE_ORB to listOf("orb"),
        VoiceCommand.MODE_CHARACTER to listOf("character"),
        VoiceCommand.MODE_INVISIBLE to listOf("invisible"),
    )

    /** Natural-language mode-switch verbs (prefix-optional, Stage 2A.1). */
    private val MODE_VERBS = listOf(
        "switch to", "activate", "change to", "go to", "enable", "put on", "switch",
    )

    /**
     * Utterance fix (2026-11-11): the turn-consuming guard. Commands
     * that can destroy or consume a whole turn (STOP_LISTENING,
     * HALT_SEQUENCE, GO_SILENT, END_VOICE) only match SHORT
     * utterances (≤ 10 words). A long description that happens to
     * contain "stop listening" mid-sentence goes to the engine
     * instead — the old substring matching swallowed the whole
     * turn as the command and the description never reached LOOM.
     * SHUTDOWN is EXEMPT from this guard (fix 2, 2026-11-13,
     * F1-DEFECT-1): David's binding rule is that "shut down" and
     * variants shut down immediately, at any length — a long
     * shutdown utterance must never fall to the clarification
     * question. The negation guard and the pointer/voice/
     * assistant-mode exclusions still apply to SHUTDOWN.
     */
    private fun isShortCommand(t: String): Boolean =
        t.split(Regex("\\s+")).count { it.isNotEmpty() } <= MAX_COMMAND_WORDS

    /** Max words for a turn-consuming control command (see [isShortCommand]). */
    private const val MAX_COMMAND_WORDS = 10

    fun match(text: String): VoiceCommand? {
        val t = text.lowercase().trim()
        // Dictated messaging + calls (2026-11-05, fix-plan item 12):
        // bulk / automated messaging is refused at PARSE time — the
        // matcher itself never yields a send/call command for a bulk
        // recipient, so no caller of match() can route one to the
        // executor. The manager additionally speaks the refusal
        // before consulting match() (see handleResult), and
        // clarificationFor() answers bulk utterances with the same
        // refusal instead of routing them to the engine.
        if (isBulkMessageRequest(t)) return null
        // Exact first, fuzzy second: an exact phrase ("start listening",
        // "deactivate assistant mode") must never lose to a fuzzy
        // near-neighbour ("stop listening", "activate assistant mode").
        // matchFuzzy() only runs on otherwise-unmatched input, and picks
        // the single best-matching command (see its docs).
        return matchExact(t) ?: matchFuzzy(t)
    }

    private fun matchExact(t: String): VoiceCommand? {
        // Longest / most specific first: "stop listening" contains "stop",
        // "shut down voice session" contains "shut down", "resume listening"
        // contains "resume".
        return when {
            matchMode(t) != null -> matchMode(t)
            // Stage 2B (D3a): pointer policy — before the mode verbs so
            // "switch the pointer off" never reads as a mode switch.
            // Stage 2C fix: "turn/switch the pointer on|off" inserts words
            // between the verb and on/off, so the adjacent-pair test misses
            // them — the verb + on/off-in-any-order clauses cover that.
            // "invisible" is excluded from the SHOW "visible" substring so
            // "make the pointer invisible" stays POINTER_HIDE.
            // Accessibility fix (2026-11-05, audit §2): the Nov 5
            // transcript phrasings — "where is the pointer", "I can't see
            // the pointer", "make the pointer appear" — matched nothing and
            // fell to the engine, which claimed a pointer it never
            // rendered. They are pointer requests: "where" / "can't see" /
            // "appear" / "bring up" / "on screen" with "pointer" all show
            // it. "help me find my way" has no "pointer" word and gets its
            // own branch below (it names no destination, so it shows the
            // pointer rather than starting guidance).
            t.contains("pointer") && !t.contains("invisible") && (
                t.contains("show") || t.contains("turn on") ||
                    t.contains("switch on") ||
                    ((t.contains("turn") || t.contains("switch")) &&
                        t.contains("on") && !t.contains("off")) ||
                    t.contains("enable") || t.contains("visible") ||
                    t.contains("where") ||
                    t.contains("can't see") || t.contains("cant see") ||
                    t.contains("cannot see") || t.contains("appear") ||
                    t.contains("bring up") || t.contains("on screen")
                ) -> VoiceCommand.POINTER_SHOW
            t.contains("pointer") && (
                t.contains("hide") || t.contains("turn off") ||
                    t.contains("switch off") ||
                    ((t.contains("turn") || t.contains("switch")) &&
                        t.contains("off")) ||
                    t.contains("disable") || t.contains("invisible")
                ) -> VoiceCommand.POINTER_HIDE
            t.contains("pointer") && (
                t.contains("auto") || t.contains("automatic")
                ) -> VoiceCommand.POINTER_AUTO
            // Accessibility fix (2026-11-05, audit §2/§7.1): "help me find
            // my way" — the exact Nov 5 transcript turn ("okay help me find
            // my way then") — matched nothing and fell to the engine. It is
            // a pointer request, not guidance: it names no destination.
            // Placed before GUIDE_TO so it can never be read as a
            // guidance request; "help me do X" still routes to GUIDE_TO.
            t.contains("help me find my way") -> VoiceCommand.POINTER_SHOW
            // Master §2 (item 5): moving the ASSISTANT itself — region
            // phrasings ("move to the top-left corner"), "move out of
            // the way", "move down a bit", and assistant-subject
            // directions ("move the assistant left"). Checked BEFORE
            // POINTER_MOVE: "move down a bit" and "move the assistant
            // left" both contain a bare direction and would otherwise
            // be claimed by the pointer. Bare "move left/right/up/down"
            // stays POINTER_MOVE (existing behaviour + tests) — the
            // assistant is addressed by region or by name, never by a
            // bare direction. Pointer-subject utterances ("move the
            // pointer to the top left") are excluded inside the
            // extractor, so they still reach POINTER_MOVE.
            extractAssistantRegionName(t) != null -> VoiceCommand.ASSISTANT_MOVE
            // "fix everything": pointer movement — "move [the pointer]
            // left/right/up/down". After the policy branches so
            // "show/hide the pointer" still wins. "move" alone,
            // "move sideways" (undirected) and conflicting directions
            // stay unmatched and reach the move-class clarification.
            extractPointerDirection(t) != null -> VoiceCommand.POINTER_MOVE
            t.contains("stop listening") && isShortCommand(t) -> VoiceCommand.STOP_LISTENING
            // Phase 2 (plan §4.2-C, David decision 3): GO_SILENT matches ONLY
            // the exact utterances "go quiet" / "be quiet" (after stripping
            // the optional "loom" prefix and trailing politeness). It must
            // sit before STOP — "be quiet" previously fell into STOP here.
            isGoSilent(t) && isShortCommand(t) -> VoiceCommand.GO_SILENT
            // Phase 2 (plan §4.2-C): "speak again" clears the silent flag.
            isSpeakAgain(t) -> VoiceCommand.SPEAK_AGAIN
            // Stage 2C (§5.2 P2): halt phrases. "stop listening" keeps its
            // own branch above and NEVER maps here; bare "stop" maps here and
            // the VoiceSessionManager halts only when a sequence is active
            // (honest no-op otherwise). Guarded by isShortCommand (see the
            // utterance fix above): a long description containing
            // "stop doing that" is a description, not a halt.
            isShortCommand(t) && (t.contains("stop the demo") || t.contains("stop doing that") ||
                t.contains("stop the sequence") || isBareStop(t)) ->
                VoiceCommand.HALT_SEQUENCE
            // Stage 2D: multi-step instructions route to the task
            // planner (see isPlanTask) — placed before GUIDE_TO /
            // SEARCH_IN_APP / LAUNCH_APP so a composite ("open X and
            // play Y", "search for X in Y and open …") is never
            // misread as its first clause alone. Single-action
            // commands win by construction: isPlanTask only fires on
            // a second action clause, a process-form guide-through,
            // or a "do" + composite.
            isPlanTask(t) -> VoiceCommand.PLAN_TASK
            // Stage 2C (§2a A4): guidance entries. "fix everything":
            // natural phrasings — "guide me through X" and pointer-led
            // guidance ("I need you to use your pointer to guide me",
            // 5:37 transcript). Policy branches above already claimed
            // show/hide/auto, so a pointer+guide utterance here is a
            // guidance request, not a policy change.
            // Master §2 (item 1): synonyms — "help me do X", "walk me
            // through X", "show me the way to X". ("Walk me through"
            // was deliberately not a trigger before; the research
            // report §6 lists it as a synonym that failed.)
            t.contains("guide me to") || t.contains("guide me through") ||
                t.contains("help me do") || t.contains("walk me through") ||
                t.contains("show me the way to") ||
                (t.contains("pointer") && t.contains("guide")) -> VoiceCommand.GUIDE_TO
            // Device-test failures (2026-11-11, D-04): "confirm your
            // screen" is a real command — describe the live screen —
            // never the engine. Placed with the guidance entries: it
            // reads the same Screen Awareness the guide path does.
            isDescribeScreen(t) -> VoiceCommand.DESCRIBE_SCREEN
            t.contains("guide next") || t.contains("next step") ->
                VoiceCommand.GUIDE_NEXT
            t.contains("guide back") || t.contains("previous step") ->
                VoiceCommand.GUIDE_BACK
            // Stage 2C (§2d D2): demonstration entries.
            t.contains("tap it for me") -> VoiceCommand.DEMO_TAP
            t.contains("show me how to") -> VoiceCommand.DEMO_SEQUENCE
            // MASTER F1d: deterministic console follow-ups (see the
            // enum docs). ENTER runs before TAP so "click on enter"
            // is the Enter key, not a tap on a target called "enter".
            // Placed after DEMO_TAP so "tap it for me" still wins.
            isPressEnter(t) -> VoiceCommand.ENTER
            isScroll(t) -> VoiceCommand.SCROLL
            isGoBack(t) -> VoiceCommand.BACK
            isTap(t) -> VoiceCommand.TAP
            t.contains("start listening") || t.contains("resume listening") ->
                VoiceCommand.START_LISTENING
            t.contains("switch to keyboard") || t.contains("keyboard mode") ||
                t.contains("type instead") -> VoiceCommand.SWITCH_KEYBOARD
            isShortCommand(t) && (t.contains("shut down voice") || t.contains("end voice session") ||
                t.contains("close voice")) -> VoiceCommand.END_VOICE
            t.contains("exit voice mode") -> VoiceCommand.EXIT_VOICE
            // Phase 2 (plan §4.2-D): "hide yourself" removes the floating
            // views only — session, mic, TTS and sequences are untouched.
            // Bare "hide" stays unmatched → the hide/mode clarification.
            isHideYourself(t) -> VoiceCommand.HIDE_YOURSELF
            // ACTIVATE ASSISTANT MODE — claimed here, before SHOW_ASSISTANT,
            // so "show the assistant" activates (state-aware: start when
            // stopped, restore when running) instead of restore-only.
            isActivateAssistant(t) -> VoiceCommand.ACTIVATE_ASSISTANT_MODE
            // Phase 2 (plan §4.2-D): "show yourself" re-binds the existing
            // floating presentation only — never reactivates, never mics.
            // "show the assistant" moved to ACTIVATE (see above).
            isShowAssistant(t) -> VoiceCommand.SHOW_ASSISTANT
            // Phase 2 (plan §4.2-F): DEACTIVATE ASSISTANT MODE — end session,
            // mic off, TTS cleared, halt, AuthGate purge, queue drain, views
            // hidden. Destructive-adjacent, but the phrase names the assistant
            // mode explicitly, so no "loom" prefix is required.
            isDeactivate(t) -> VoiceCommand.DEACTIVATE_ASSISTANT_MODE
            // Shutdown recognition (2026-11-10, David's rule): plain
            // "shut down" and its natural variants — no "loom" prefix
            // required. Placed AFTER isDeactivate so "turn off
            // assistant mode" stays DEACTIVATE, after the pointer
            // branch (earlier) so "turn off the pointer" stays
            // POINTER_HIDE, and after END_VOICE so "shut down voice"
            // stays END_VOICE. A match executes the shutdown
            // IMMEDIATELY (David's binding rule, Nov 13 ~03:36 — no
            // confirmation question), at ANY length (fix 2,
            // 2026-11-13, F1-DEFECT-1: the utterance-fix length
            // guard does NOT apply to SHUTDOWN — a long shutdown
            // utterance must never fall to the clarification
            // question). The match is therefore guarded by
            // isShutdown's negation guard and its pointer/voice/
            // assistant-mode exclusions only — "don't shut down"
            // and "shut down the pointer" never match. A long
            // narrative that merely mentions shutting down will
            // also match: accepted per the binding rule, and the
            // only remaining safety is the negation guard.
            isShutdown(t) -> VoiceCommand.SHUTDOWN
            // Master §2 (item 4): in-app search — "search for <query>
            // [in <app>]". A real command now (Worker 4's executor), not
            // the old "I can't search" clarification. The query/app split
            // lives in extractSearchQuery / extractSearchApp; a missing
            // app resolves from the last-turn context (ConversationContext)
            // or the manager asks which app.
            isSearchApp(t) -> VoiceCommand.SEARCH_IN_APP
            // Device-evidence fix (2026-11-05): launch requests route to
            // the real AuthGate-gated launch path, never the LLM's
            // roleplay-then-deny. Placed after DEACTIVATE so no existing
            // exact phrase collides (launch verbs never appear in them).
            isLaunchApp(t) -> VoiceCommand.LAUNCH_APP
            // MASTER F1b: "go to X" / "take me to X" — same launch
            // handler as LAUNCH_APP (the manager maps both to
            // onLaunchRequested). Placed after LAUNCH_APP so "open …"
            // keeps its own branch; after matchMode (first branch),
            // so "go to orb mode" is still MODE_ORB, never a launch.
            isGoToApp(t) -> VoiceCommand.GO_TO_APP
            // Dictated messaging + calls (2026-11-05, fix-plan item
            // 12): "send WhatsApp to X saying Y", "text X saying Y" /
            // "send SMS to X saying Y", "call X". Placed after
            // launch/search (no existing exact phrase collides — the
            // verbs are distinct) and before STOP, so "call" can never
            // be read as a stop/pause phrase. Bulk requests never
            // reach these branches: match() returns null for them
            // before matchExact() runs.
            isSendWhatsApp(t) -> VoiceCommand.SEND_WHATSAPP_MESSAGE
            isSendSms(t) -> VoiceCommand.SEND_SMS
            isPlaceCall(t) -> VoiceCommand.PLACE_CALL
            // Stage 2A.1: bare "stop" is HALT_SEQUENCE (handled above), never
            // STOP — "stop talking" stays STOP. "be quiet" is GO_SILENT now
            // (David decision 3); the word "quiet" alone never matches.
            t.contains("stop talking") -> VoiceCommand.STOP
            t.contains("pause") -> VoiceCommand.PAUSE
            t.contains("resume") -> VoiceCommand.RESUME
            else -> null
        }
    }

    /**
     * Master §2 (item 1): the exact-phrase commands, as lists, so the
     * exact matcher and the fuzzy matcher below can never drift apart.
     * Fuzzy matching applies ONLY to these narrow exact-phrase commands
     * (plus "stop/start listening" and "shut down voice") — never to
     * broad contains-based commands, and GO_SILENT stays narrow per
     * David decision 3: only these three phrases, exact or fuzzy, match.
     */
    private val GO_SILENT_PHRASES = listOf("go quiet", "be quiet", "go silent")

    private val ACTIVATE_ASSISTANT_PHRASES = listOf(
        "activate assistant mode",
        "activate the assistant",
        "start floating assistant",
        "start the floating assistant",
        "show the assistant",
    )

    private val DEACTIVATE_PHRASES = listOf(
        "deactivate assistant mode",
        "deactivate the assistant",
        "turn off assistant mode",
        "disable assistant mode",
    )

    /**
     * Master §2 (item 1): the second matcher layer — bounded STT-error
     * tolerance. Runs ONLY when matchExact() returned null, so an exact
     * phrase always wins over a fuzzy near-neighbour: "start listening"
     * can never fuzzy-match STOP_LISTENING, and "deactivate assistant
     * mode" can never fuzzy-match ACTIVATE (the minimal pairs "start"/
     * "stop" score 0.40 and "activate"/"deactivate" 0.80, which would
     * both pass the per-token floor on their own).
     *
     * Best-match selection: every eligible command scores its best
     * phrase similarity (FuzzyMatch.phraseSimilarity); the winner must
     * beat the runner-up by at least 0.05, otherwise the utterance is
     * ambiguous and returns null (clarification / grounded fallback),
     * never a guess. Only the narrow exact-phrase commands are
     * eligible — broad contains-based commands never fuzzy-match.
     * The SHUTDOWN loom-token rule below is subsumed by [isShutdown]
     * in matchExact (2026-11-10: plain "shut down" matches there, no
     * loom token needed) and is retained only as a backstop for a
     * misheard loom-like token ("shut down low" for "shut down
     * LOOM"). There is NO confirmation question anymore (Nov 13
     * ~03:36: shutdown is immediate), so this backstop is guarded
     * by the same negation rule as the exact match — a near-miss
     * with a negation never matches.
     */
    private fun matchFuzzy(t: String): VoiceCommand? {
        val n = normalizeCommand(t)
        val short = isShortCommand(t)
        // Utterance fix (2026-11-11): the fuzzy layer never yields a
        // turn-consuming command (STOP_LISTENING, GO_SILENT,
        // END_VOICE) for a long utterance — same guard as matchExact.
        val guarded = setOf(
            VoiceCommand.STOP_LISTENING, VoiceCommand.GO_SILENT,
            VoiceCommand.END_VOICE,
        )
        val scored = FUZZY_CANDIDATES
            .filter { short || it.first !in guarded }
            .mapNotNull { (cmd, phrases) ->
            val sim = phrases.mapNotNull { FuzzyMatch.phraseSimilarity(n, it) }
                .maxOrNull()
            if (sim != null) cmd to sim else null
        }.sortedByDescending { it.second }
        if (scored.isNotEmpty()) {
            val best = scored[0]
            val second = scored.getOrNull(1)?.second ?: 0.0
            if (best.second - second >= 0.05) return best.first
            return null // ambiguous between two commands — clarify
        }
        if (short && t.contains("shut down") && !isNegated(t) && FuzzyMatch.hasLoomLikeToken(t)) {
            return VoiceCommand.SHUTDOWN
        }
        return null
    }

    /** Eligible commands for the fuzzy layer, with their exact phrases.
     * Accessibility fix (2026-11-05, audit §2): POINTER_SHOW joins the
     * list with its fixed phrases only — the broad contains-triggers
     * ("show", "where", "appear", …) never fuzzy-match, so a misheard
     * pointer request ("show the pointr") lands on POINTER_SHOW instead
     * of falling to the engine. The best-match margin (≥ 0.05 over the
     * runner-up) still applies, so a near-tie clarifies, never guesses. */
    private val FUZZY_CANDIDATES: List<Pair<VoiceCommand, List<String>>> = listOf(
        VoiceCommand.STOP_LISTENING to listOf("stop listening"),
        VoiceCommand.GO_SILENT to GO_SILENT_PHRASES,
        VoiceCommand.SPEAK_AGAIN to listOf("speak again"),
        VoiceCommand.START_LISTENING to listOf("start listening", "resume listening"),
        VoiceCommand.END_VOICE to
            listOf("shut down voice", "end voice session", "close voice"),
        VoiceCommand.HIDE_YOURSELF to listOf("hide yourself"),
        VoiceCommand.SHOW_ASSISTANT to listOf("show yourself"),
        VoiceCommand.ACTIVATE_ASSISTANT_MODE to ACTIVATE_ASSISTANT_PHRASES,
        VoiceCommand.DEACTIVATE_ASSISTANT_MODE to DEACTIVATE_PHRASES,
        VoiceCommand.POINTER_SHOW to listOf(
            "show the pointer",
            "show me the pointer",
            "show pointer",
            "turn the pointer on",
            "where is the pointer",
            "help me find my way",
            "make the pointer appear",
            "i cant see the pointer",
        ),
    )

    /**
     * Phase 2 (plan §4.2-C, David decision 3): the narrowed GO_SILENT
     * matcher. Matches ONLY when the utterance, after stripping the optional
     * "loom" prefix and trailing politeness ("please", "for now"), is exactly
     * "go quiet", "be quiet", or (device-evidence fix 2026-11-05) "go silent".
     * Mid-sentence occurrences ("be quiet for a moment, I'm thinking",
     * "go quiet over there", "I'm telling you to go silent") do NOT
     * match — they fall through to the quiet/silent-class clarification.
     */
    private fun isGoSilent(t: String): Boolean {
        val n = normalizeCommand(t)
        return n in GO_SILENT_PHRASES
    }

    /** Phase 2 (plan §4.2-C): "speak again" clears the silent flag. */
    private fun isSpeakAgain(t: String): Boolean =
        normalizeCommand(t) == "speak again"

    /** Phase 2 (plan §4.2-D): "hide yourself" — views only, nothing else. */
    private fun isHideYourself(t: String): Boolean =
        normalizeCommand(t) == "hide yourself"

    /** Phase 2 (plan §4.2-D): "show yourself" — re-bind views only. */
    private fun isShowAssistant(t: String): Boolean =
        normalizeCommand(t) == "show yourself"

    /**
     * ACTIVATE ASSISTANT MODE — exact-equality phrases (NOT contains), so
     * "deactivate …" can never collide and mid-sentence occurrences never
     * match. "show the assistant" lives here (state-aware activation),
     * not on SHOW_ASSISTANT — the notification SHOW action is a direct
     * service intent and never touches this matcher.
     */
    private fun isActivateAssistant(t: String): Boolean {
        val n = normalizeCommand(t)
        return n in ACTIVATE_ASSISTANT_PHRASES
    }

    /**
     * Phase 2 (plan §4.2-F): DEACTIVATE ASSISTANT MODE phrasings. Narrow on
     * purpose: "turn it off" / "power off" stay in the shutdown-class
     * clarification, never here.
     */
    private fun isDeactivate(t: String): Boolean {
        val n = normalizeCommand(t)
        return n in DEACTIVATE_PHRASES
    }

    /**
     * Shutdown recognition (2026-11-10, David's rule): plain "shut
     * down" and its natural variants shut LOOM down — the "loom"
     * prefix is NOT required. Matches "shut down", "shutdown",
     * "shut it down", "turn loom off", "kill loom", "power off",
     * "switch off", and bare "turn off" (including "turn it off").
     * The assistant-mode, pointer and voice forms are excluded —
     * they are claimed by earlier arms (isDeactivate, the pointer
     * branch, END_VOICE) and must never reach here. A match
     * executes the shutdown IMMEDIATELY (Nov 13 ~03:36: no
     * confirmation question) — so negated utterances are excluded
     * by [isNegated], and only the dedicated in-app shutdown
     * BUTTON keeps a dialog.
     */
    /**
     * Device-failure fix (2026-11-13, F1): shutdown is now
     * IMMEDIATE — no confirmation question stands between the
     * match and the teardown — so a NEGATED utterance must never
     * match. A negation word anywhere in the short command fails
     * closed (the turn goes to the engine, which chats, instead
     * of shutting LOOM down on a "don't shut down"). STT may
     * produce a curly apostrophe; both forms are covered.
     */
    private fun isNegated(t: String): Boolean {
        val normalized = t.replace('\u2019', '\'')
        return normalized.split(Regex("\\s+")).any {
            it.trim('.', '!', '?', ',') in setOf("don't", "dont", "not", "never")
        }
    }

    /** The shutdown phrases [isShutdown] matches (no exclusions applied). */
    private fun hasShutdownPhrase(t: String): Boolean =
        t.contains("shut down") || t.contains("shutdown") ||
            t.contains("shut it down") || t.contains("turn loom off") ||
            t.contains("kill loom") || t.contains("power off") ||
            t.contains("switch off") || t.contains("turn off") ||
            t.contains("turn it off")

    /**
     * Fix 2 (2026-11-13, F1-DEFECT-1): the negation intercept. True
     * when the text names shutting down AND is negated ("don't shut
     * down", "do not turn it off", "never shut down"). Both call
     * sites that could ask the shutdown question — the voice path
     * (VoiceSessionManager) and the typed path (CapabilityBridge
     * .resolveChatInput) — check this BEFORE the clarification
     * layer, and [clarificationFor] itself refuses the shutdown
     * branch for negated text, so a negated utterance can never be
     * answered with a shutdown question. The exclusions in
     * [isShutdown] deliberately do NOT apply here: "don't shut
     * down the pointer" is negated shutdown text too, and must
     * not be asked about either.
     */
    fun isNegatedShutdown(text: String): Boolean {
        val t = text.lowercase().trim()
        return hasShutdownPhrase(t) && isNegated(t)
    }

    private fun isShutdown(t: String): Boolean {
        if (t.contains("assistant mode") || t.contains("pointer") || t.contains("voice")) {
            return false
        }
        if (isNegated(t)) return false
        return hasShutdownPhrase(t)
    }

    /**
     * Device-test failures (2026-11-11, D-04): the screen-description
     * phrases — "confirm your screen", "what's on my screen", "what
     * do you see", "show me what you see", "describe the screen".
     * Exact containment on the normalized text; "confirm the screen"
     * and "confirm screen" are included because David used both
     * forms. Nothing here collides with a guidance phrase ("guide
     * me to …" names a destination; these name the screen itself).
     */
    fun isDescribeScreen(text: String): Boolean {
        val t = normalizeCommand(text.lowercase().trim())
        return t.contains("confirm your screen") ||
            t.contains("confirm the screen") ||
            t.contains("confirm screen") ||
            t.contains("what's on my screen") ||
            t.contains("whats on my screen") ||
            t.contains("what is on my screen") ||
            t.contains("what is on your screen") ||
            t.contains("what's on the screen") ||
            t.contains("whats on the screen") ||
            t.contains("what is on the screen") ||
            t.contains("tell me what's on my screen") ||
            t.contains("tell me whats on my screen") ||
            t.contains("tell me what's on the screen") ||
            t.contains("tell me what you see") ||
            t.contains("what do you see") ||
            t.contains("what can you see") ||
            t.contains("show me what you see") ||
            t.contains("describe your screen") ||
            t.contains("describe the screen") ||
            t.contains("describe what you see")
    }

    /**
     * Device-test failures (2026-11-11, D-05): a reference to THE
     * task — "execute the task I give to you", "do the task", "I'm
     * giving you a task, do it", "do it". This matcher only says the
     * WORDS refer to a task; the caller decides whether a task
     * exists (active record or last task) and resumes it, or falls
     * through when there is none. STT hears "task" as "tax" on
     * David's phone ("execute the tax I give to you", "I'm giving
     * you a tax do it") — the tax spellings count ONLY inside these
     * task templates, never as a bare word (a real tax question must
     * never route here).
     */
    fun isTaskReference(text: String): Boolean {
        val t = normalizeCommand(text.lowercase().trim())
        // Bare "do it" — with only "now" / "please" / "for me" after
        // it. "Do it later" is a deferral, never a resume.
        val bareDoIt = t.removeSuffix(" please").removeSuffix(" now")
            .removeSuffix(" for me").trim()
        if (bareDoIt == "do it" || t == "do the task" || t == "do the tax") return true
        val templates = listOf(
            "execute the task", "execute the tax",
            "do the task", "do the tax",
            "run the task", "run the tax",
            "carry out the task", "carry out the tax",
            "finish the task", "finish the tax",
            "complete the task", "complete the tax",
            "the task i gave you", "the tax i gave you",
            "the task i give to you", "the tax i give to you",
            "the task i give you", "the tax i give you",
            "giving you a task", "giving you a tax",
            "give you a task", "give you a tax",
            "i'm giving you a task", "i'm giving you a tax",
            "i am giving you a task", "i am giving you a tax",
        )
        return templates.any { t.contains(it) }
    }

    /**
     * Strip the optional "loom" prefix and trailing politeness so exact
     * command utterances ("loom, be quiet please") normalize to their core
     * form ("be quiet").
     */
    private fun normalizeCommand(t: String): String {
        var s = t.trim()
        if (s.startsWith("loom")) {
            s = s.removePrefix("loom").trimStart(',', ' ', '!', '.')
        }
        s = s.trimEnd('.', '!', '?').trim()
        if (s.endsWith("please")) s = s.removeSuffix("please").trim()
        if (s.endsWith("for now")) s = s.removeSuffix("for now").trim()
        return s.trimEnd('.', '!', '?').trim()
    }

    /**
     * Phase 2 (mismatch #4, plan §4.2): the clarification layer. Unmatched
     * lifecycle-class phrasings → spoken clarification templates, NEVER the
     * LLM. Returns null when the text matched a command (checked first) or
     * belongs to no lifecycle class. The clarification itself performs no
     * action — the VoiceSessionManager speaks the template and returns.
     */
    fun clarificationFor(text: String): String? {
        val t = text.lowercase().trim()
        if (match(t) != null) return null
        return when {
            // Dictated messaging + calls (2026-11-05, fix-plan item
            // 12): bulk requests are refused here too, so typed chat
            // ("send WhatsApp to everyone saying hi") gets the
            // refusal instead of the engine. match() already returns
            // null for bulk, so this branch is reachable.
            isBulkMessageRequest(t) ->
                "I don't send bulk messages — tell me one person at a time."
            // Shutdown class (2026-11-10; scoped 2026-11-13, fix
            // 2, F1-DEFECT-1): the plain variants ("power off",
            // "turn it off", "kill loom", "shut it down", "turn
            // loom off") match SHUTDOWN in match() at ANY length
            // and return null above — this branch is unreachable
            // for them. It survives ONLY for the one phrasing the
            // matcher deliberately excludes that still names the
            // pointer ("shut down the pointer"), where the honest
            // answer is still a question, not an action. Negated
            // text never reaches this branch (guarded by
            // !isNegated(t) here, and by the isNegatedShutdown
            // intercept at both call sites): "don't shut down"
            // must never be answered with a shutdown question,
            // and non-pointer forms ("shut down assistant mode")
            // fall through to the engine.
            // David's binding rule (Nov 13 ~03:36): no
            // confirmation question about shutting LOOM down.
            (t.contains("shut down") || t.contains("shutdown")) &&
                t.contains("pointer") && !isNegated(t) ->
                "Do you want me to shut down LOOM completely, or just end the voice session?"
            // Stop class ("stop it", "please stop", "stop now", "mute").
            t.contains("stop it") || t.contains("please stop") ||
                t.contains("stop now") || t.contains("mute") ->
                "Do you want me to stop the demonstration, stop listening, or stop talking?"
            // Hide / mode class ("go away", "hide", "disappear").
            t.contains("go away") || t.contains("hide") ||
                t.contains("disappear") ->
                "Do you want me to hide the floating assistant, or switch to Invisible mode?"
            // Quiet class — mid-sentence "quiet" occurrences. Exact
            // "go quiet" / "be quiet" / "go silent" are GO_SILENT and
            // never reach here.
            t.contains("quiet") ->
                "Do you want me to go silent, or stop something specific?"
            // Silent class — device-evidence fix (2026-11-05): "I'm
            // telling you to go silent" was roleplayed as GO_SILENT with
            // no execution. Exact "go silent" matches GO_SILENT above and
            // never reaches here; mid-sentence "silent" clarifies.
            t.contains("silent") ->
                "Do you want me to go silent, or stop something specific?"
            // Open class — a launch verb with no app named ("open",
            // "launch") asks which app instead of reaching the LLM.
            // (MASTER F1d/F3b: the manager guards this question with
            // the asked-once flag via isBareOpenRequest, so it is
            // asked at most once per task.)
            isBareOpenRequest(t) -> LAUNCH_APP_QUESTION
            // Search class — Master §2 (item 4): search is a real command
            // now, so only a bare "search" (no query) still clarifies.
            // "search for X" matches SEARCH_IN_APP and never reaches
            // here. "research" is safe: it contains no " search "
            // substring and never starts with "search ".
            // (MASTER F3b: the manager guards this question with the
            // asked-once flag via isBareSearchRequest.)
            isBareSearchRequest(t) -> SEARCH_QUERY_QUESTION
            // Dictated messaging + calls (2026-11-05, fix-plan item
            // 12): a message/call phrasing with a piece missing asks
            // for the missing piece, never the LLM. A full phrase
            // matches its command and never reaches here. "send
            // WhatsApp to Mum" (no text) asks what to say; bare
            // "send WhatsApp" / "text" asks who + what; bare "call"
            // asks who.
            normalizeCommand(t) == "call" || normalizeCommand(t) == "please call" ->
                "Who should I call?"
            isMessageRequest(t) && extractMessageTarget(t).isNotBlank() &&
                extractMessageText(t).isBlank() ->
                "What should I say to ${extractMessageTarget(t)}?"
            isBareMessageRequest(t) ->
                "Who should I message, and what should I say?"
            // Move class (assistant) — Master §2 (item 5): an
            // assistant-move phrasing with no usable region ("move the
            // assistant", "move yourself"). Region phrasings match
            // ASSISTANT_MOVE and never reach here.
            isAssistantMoveClarification(t) ->
                "Where should I move — top-left, top-right, bottom-left, bottom-right, or out of the way?"
            // Move class — pointer-move phrasing with no usable
            // direction. Exact POINTER_MOVE matches never reach here
            // (match() != null returns null above).
            isMoveClarification(t) ->
                "Which way should I move the pointer — left, right, up, or down?"
            else -> null
        }
    }

    private fun matchMode(t: String): VoiceCommand? {        for ((cmd, words) in MODE_COMMANDS) {
            if (words.any { t.contains(it) } &&
                (MODE_VERBS.any { t.contains(it) } || (t.contains("loom") && t.contains("mode")))
            ) {
                return cmd
            }
        }
        // Device-evidence fix (2026-11-05): a bare mode fragment —
        // "to invisible mode", "invisible mode", "invisible" — is a mode
        // command, not a question for the LLM. The pattern is exact:
        // optional "to", one mode word, optional "mode", nothing else.
        // "what is invisible mode?" and "invisible is cool" keep extra
        // words, so they stay unmatched and conversational.
        val fragment = normalizeCommand(t)
        val bare = fragment.removePrefix("to ").trim()
        val modeWord = bare.removeSuffix(" mode").trim()
        if (fragment == bare || fragment == "to $bare") {
            return when (modeWord) {
                "orb" -> VoiceCommand.MODE_ORB
                "character" -> VoiceCommand.MODE_CHARACTER
                "invisible" -> VoiceCommand.MODE_INVISIBLE
                else -> null
            }
        }
        return null
    }

    /**
     * Stage 2A.1: a mode verb with no recognizable mode ("switch it",
     * "change mode") → the manager asks a spoken clarification. Never the
     * LLM — ambiguous mode phrasing must not be roleplayed as a switch.
     *
     * MASTER F1a: the question must NEVER fire for an utterance that
     * names an app or an action — "Go to Google Play Store" and "Go to
     * my DM" were hijacked here because "go to" is a mode verb. The
     * guard below ([looksLikeAppOrAction]) runs before the verb test:
     * anything that looks like navigation to an app, or an action verb
     * with a non-mode target, falls through to launch/search/plan/guide
     * matching (or the engine) instead of the mode question. A genuinely
     * bare mode request ("switch it", "change mode", "activate it")
     * still asks — its verb has no target at all.
     */
    fun matchAmbiguous(text: String): Boolean {
        val t = text.lowercase().trim()
        if (match(t) != null) return false
        // Stage 2B (D3a): pointer phrasing ("switch the pointer off") is a
        // policy command, never a mode switch — never ask "which mode" for it.
        if (t.contains("pointer")) return false
        val hasModeWord = listOf("orb", "character", "invisible", "keyboard")
            .any { t.contains(it) }
        if (hasModeWord) return false
        if (looksLikeAppOrAction(t)) return false
        return MODE_VERBS.any { t.contains(it) } ||
            (t.contains("loom") && t.contains("switch")) ||
            t.contains("change mode")
    }

    /**
     * MASTER F1a: true when the utterance names an app or an action
     * rather than a mode. Navigation ("go to X", "take me to X") always
     * counts. A mode verb ("switch to", "activate", "enable",
     * "change to") counts only when the word after it is a real target
     * — not a mode word, not "mode" itself, and not a bare pronoun /
     * article ("it", "them", "that", "the", "a") with nothing after it.
     * "switch to YouTube" and "enable dark mode" are actions;
     * "switch it" and "activate it" stay genuinely ambiguous.
     */
    private fun looksLikeAppOrAction(t: String): Boolean {
        if (t == "go to" || t == "take me to") return true
        if (t.contains("go to ") || t.contains("take me to ")) return true
        for (verb in listOf("switch to ", "activate ", "enable ", "change to ")) {
            val idx = t.indexOf(verb)
            if (idx < 0) continue
            val first = t.substring(idx + verb.length).trim()
                .split(' ', ',', '.').firstOrNull().orEmpty()
            if (first.isNotEmpty() && first !in AMBIGUOUS_MODE_TARGETS) return true
        }
        return false
    }

    /** Words after a mode verb that carry no target — the request stays
     *  a genuinely ambiguous mode question ("switch it", "activate a …"). */
    private val AMBIGUOUS_MODE_TARGETS = setOf(
        "orb", "character", "invisible", "keyboard", "mode",
        "it", "them", "that", "the", "a", "an",
    )

    /**
     * MASTER F1c/F2a — shared normalisation for the app extractors.
     * Leading words David puts before a command ("let's go open …",
     * "please open …", "could you open …", "I want you to open …",
     * "I said you should open …") are politeness, never part of the
     * command; trailing words ("open YouTube for me / now / please /
     * right now") are filler, never part of the app name. Both lists
     * are data, not code branches; stripping loops until nothing
     * changes ("for me please" strips as one phrase, then stops).
     */
    private val LEADING_WORDS = listOf(
        "let's go ", "lets go ", "please ", "can you ", "could you ",
        "i want you to ", "i said you should ", "i said ",
    )

    private val TRAILING_FILLERS = listOf(
        "for me please", "right now", "for me", "now", "please",
    )

    /** The single user-facing slot questions, shared by the
     *  clarification layer, the voice manager's asked-once guards and
     *  the planner — one wording, not four copies that can drift. */
    const val SEARCH_QUERY_QUESTION = "What do you want me to search for?"
    const val SEARCH_APP_QUESTION = "Which app should I search in?"
    const val LAUNCH_APP_QUESTION = "Which app do you want me to open?"

    /** True when the utterance is a bare launch request with no app
     *  named ("open", "launch", "can you open") — the manager's
     *  asked-once guard for the launch question keys off this. */
    fun isBareOpenRequest(text: String): Boolean =
        normalizeCommand(text.lowercase().trim()) in
            setOf("open", "launch", "can you open")

    /** True when the utterance is a bare search request with no query
     *  ("search", "search for", "can you search", "can you search
     *  for") — the manager's asked-once guard for the query question
     *  keys off this. */
    fun isBareSearchRequest(text: String): Boolean =
        normalizeCommand(text.lowercase().trim()) in
            setOf("search", "search for", "can you search", "can you search for")

    private fun stripLeadingWords(n: String): String {
        var s = n
        var changed: Boolean
        do {
            changed = false
            for (lead in LEADING_WORDS) {
                if (s.startsWith(lead)) {
                    s = s.removePrefix(lead).trim()
                    changed = true
                    break
                }
            }
        } while (changed)
        return s
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

    /**
     * Stage 2C: bare "stop" (with optional trailing punctuation), the only
     * "stop" form that reaches HALT_SEQUENCE without an explicit phrase.
     */
    private fun isBareStop(t: String): Boolean =
        t.trimEnd('.', '!', '?') == "stop"

    /**
     * Stage 2C (§2a A4): the target of "guide me to <x>", original casing
     * preserved for display. Empty when no target was given.
     * Master §2 (item 1): also covers the synonyms "guide me through
     * <x>", "help me do <x>", "walk me through <x>" and
     * "show me the way to <x>".
     */
    fun extractGuideTarget(text: String): String {
        val direct = extractAfter(text, "guide me to")
        if (direct.isNotBlank()) return direct
        val through = extractAfter(text, "guide me through")
        if (through.isNotBlank()) return through
        val help = extractAfter(text, "help me do")
        if (help.isNotBlank()) return help
        val walk = extractAfter(text, "walk me through")
        if (walk.isNotBlank()) return walk
        return extractAfter(text, "show me the way to")
    }

    /**
     * Stage 2C (§2d D2): the goal of "show me how to <x>", original casing
     * preserved for display. Empty when no goal was given.
     */
    fun extractDemoGoal(text: String): String =
        extractAfter(text, "show me how to")

    // ---------- Stage 2D: PLAN_TASK parsing ----------

    /**
     * Stage 2D — true when the utterance is a genuinely multi-step
     * instruction that belongs to the task planner, not a single
     * command. Conservative by design: when a pattern is ambiguous
     * between PLAN_TASK and an existing command, the existing command
     * wins — the single-action branches run first in matchExact, and
     * every pattern below needs a second action clause or a process
     * form that no single command owns:
     * - "open/launch <app> and|then <action verb> …" ("open YouTube
     *   Music and play a song by Davido", "open Settings and turn on
     *   dark mode"). "open YouTube and Spotify" has no action verb,
     *   so it stays LAUNCH_APP.
     * - "search … and <action verb> …" ("search for Spider-Man in
     *   YouTube and open the first result"). A plain search has no
     *   second clause, so it stays SEARCH_IN_APP.
     * - "guide me through / walk me through <process>" — a process
     *   ("turning on dark mode") is multi-step; a named destination
     *   ("the settings") stays GUIDE_TO (existing behaviour + tests).
     * - "do <composite>" — "do" + any of the above.
     * Exact-match only, deliberately NOT in the fuzzy layer: plan
     * goals are open-ended sentences, and the fuzzy layer is reserved
     * for narrow fixed-phrase commands.
     */
    fun isPlanTask(text: String): Boolean {
        var t = normalizeCommand(text.lowercase().trim())
        // MASTER F1c: leading words are politeness, not part of the
        // command — "let's go open YouTube Music and play music" is
        // the same composite as the bare form. (The "please"/"can
        // you" strips below predate this; stripLeadingWords covers
        // them too, and runs first so every lead-in is handled once.)
        t = stripLeadingWords(t)
        if (t.startsWith("please ")) t = t.removePrefix("please ").trim()
        if (t.startsWith("can you ")) t = t.removePrefix("can you ").trim()
        return isPlanTaskCore(t) ||
            (t.startsWith("do ") && isPlanTaskCore(t.removePrefix("do ").trim()))
    }

    private fun isPlanTaskCore(t: String): Boolean {
        if (isGuideThroughProcess(t)) return true
        if (isOpenAndAction(t)) return true
        if (isSearchAndAction(t)) return true
        // Simple-task execution fix (2026-11-07): "play … on <app>"
        // is a multi-step instruction (open the app, search, tap the
        // first result, verify playback) — it belongs to the planner,
        // not to the model. The old core had no play pattern at all:
        // "play me some music on YouTube" fell through to the Gemini
        // model, which had to guess the PLAN_TASK action block, and
        // the app never reached the record.
        if (isPlayOnApp(t)) return true
        // "find <song> on <app> and play it" is the same play shape
        // in find-words — same plan (open, search, tap first result,
        // verify playback). No command owns bare "find", so this
        // used to fall through to the model and die as Unsupported.
        if (isFindAndPlay(t)) return true
        return false
    }

    /** "find <something> on <app> and play …" — the find-words play shape. */
    private fun isFindAndPlay(t: String): Boolean {
        if (!t.startsWith("find ")) return false
        return Regex("""^find\s+.+?\s+on\s+.+?\s+and\s+play\b""").containsMatchIn(t)
    }

    /**
     * "play <something> on <app>": a play verb, then a non-empty app
     * after the last " on ". "play music" alone is NOT this — with
     * no app named it stays a bare play (the planner asks once, or
     * resumes the record). The LAST " on " splits, so a title
     * containing "on" ("Lean On YouTube") still yields the app after
     * it; "play Lean On" has no " on " with a following word, so it
     * stays a bare play.
     */
    private fun isPlayOnApp(t: String): Boolean {
        if (!t.startsWith("play ")) return false
        val onIdx = t.lastIndexOf(" on ")
        if (onIdx < 0) return false
        val app = t.substring(onIdx + 4).trim().trimEnd('.', '!', '?').trim()
        if (app.isBlank()) return false
        // The play tail before " on " must name something to play.
        val tail = t.substring(5, onIdx).trim()
        if (tail.isBlank()) return false
        return true
    }

    /** Action verbs that may open the second clause of a composite. */
    private val PLAN_TAIL_VERBS = setOf(
        "play", "search", "tap", "turn", "send", "message", "text",
        "call", "find", "go", "switch", "enable", "disable", "start",
        "show", "watch", "listen", "open",
    )

    private fun isOpenAndAction(t: String): Boolean {
        val verbs = PLAN_TAIL_VERBS.joinToString("|")
        return Regex("""^(open|launch)\s+.+?\s+(and|then)\s+($verbs)\b""").containsMatchIn(t)
    }

    private fun isSearchAndAction(t: String): Boolean {
        val rest = when {
            t.startsWith("can you search") -> t.removePrefix("can you search").trim()
            t.startsWith("search ") -> t.removePrefix("search").trim()
            else -> return false
        }
        if (rest.isEmpty()) return false
        val verbs = PLAN_TAIL_VERBS.joinToString("|")
        return Regex("""\s+and\s+($verbs)\b""").containsMatchIn(rest)
    }

    /**
     * First word of a guide-through target that names a PROCESS (a
     * thing to do), not a place. "turning on dark mode" starts with
     * "turning" (a process → PLAN_TASK); "the settings" starts with
     * "the" (a destination → GUIDE_TO).
     */
    private val GUIDE_THROUGH_PROCESS_VERBS = setOf(
        "turning", "setting", "changing", "enabling", "disabling",
        "installing", "uninstalling", "creating", "adding", "removing",
        "switching", "connecting", "disconnecting", "updating",
        "resetting", "backing", "signing", "logging", "making",
        "sending", "playing", "opening", "searching", "finding",
        "fixing", "doing", "starting", "stopping", "moving",
        "sharing", "downloading", "uploading", "recording",
    )

    private fun isGuideThroughProcess(t: String): Boolean {
        for (phrase in listOf("guide me through ", "walk me through ")) {
            val idx = t.indexOf(phrase)
            if (idx < 0) continue
            val target = t.substring(idx + phrase.length).trim()
            if (target.startsWith("how to ")) return true
            val first = target.split(' ', ',', '.').firstOrNull().orEmpty()
            return first in GUIDE_THROUGH_PROCESS_VERBS
        }
        return false
    }

    /**
     * Stage 2D — the plan goal: the full instruction, verbatim
     * (original casing and wording), with only the transparency
     * request stripped (see [extractTransparency]) and stray
     * punctuation / a dangling "and"/"then" trimmed. The planner
     * receives what David actually said — never a paraphrase.
     */
    fun extractPlanGoal(text: String): String {
        var goal = stripTransparencyPhrases(text)
        goal = goal.split(Regex("\\s+")).joinToString(" ").trim()
        var changed: Boolean
        do {
            changed = false
            if (goal.endsWith(" and", ignoreCase = true)) {
                goal = goal.dropLast(4).trim()
                changed = true
            }
            if (goal.endsWith(" then", ignoreCase = true)) {
                goal = goal.dropLast(5).trim()
                changed = true
            }
            val trimmed = goal.trimEnd(',', '.', '!', '?').trim()
            if (trimmed != goal) {
                goal = trimmed
                changed = true
            }
        } while (changed)
        return goal.trimStart(',', ' ').trim()
    }

    /**
     * Stage 2D — true when the instruction asks to see the work
     * ("show me what you're doing", "show me", "let me see").
     * Transparency changes what David sees of the plan, never what
     * it may do. Bare "show me" does NOT count inside "show me how
     * to …" (DEMO_SEQUENCE) or "show me the way to …" (GUIDE_TO).
     */
    fun extractTransparency(text: String): Boolean {
        val t = text.lowercase()
        if (TRANSPARENCY_PHRASES.any { t.contains(it) }) return true
        var idx = t.indexOf("show me")
        while (idx >= 0) {
            val rest = t.substring(idx + "show me".length).trimStart()
            if (!rest.startsWith("how to") && !rest.startsWith("the way to")) return true
            idx = t.indexOf("show me", idx + 1)
        }
        return false
    }

    private val TRANSPARENCY_PHRASES = listOf(
        "show me what you're doing",
        "show me what you are doing",
        "let me see what you're doing",
        "let me see what you are doing",
        "let me see",
    )

    private fun stripTransparencyPhrases(text: String): String {
        var out = text
        for (phrase in TRANSPARENCY_PHRASES) {
            val idx = out.indexOf(phrase, ignoreCase = true)
            if (idx >= 0) out = out.removeRange(idx, idx + phrase.length)
        }
        // Bare "show me" — but never inside "show me how to …"
        // (DEMO_SEQUENCE) or "show me the way to …" (GUIDE_TO).
        var idx = out.indexOf("show me", ignoreCase = true)
        while (idx >= 0) {
            val rest = out.substring(idx + "show me".length).trimStart()
            val guarded = rest.startsWith("how to", ignoreCase = true) ||
                rest.startsWith("the way to", ignoreCase = true)
            if (!guarded) {
                out = out.removeRange(idx, idx + "show me".length)
                idx = out.indexOf("show me", ignoreCase = true)
            } else {
                idx = out.indexOf("show me", idx + "show me".length, ignoreCase = true)
            }
        }
        return out
    }

    /**
     * "fix everything" (2026-11-05): the direction of a pointer-move
     * utterance, or null when it names no single directed direction.
     * "move left", "move the pointer to the left", "move sideways left"
     * all resolve; "move", "move the pointer", bare "move sideways" and
     * conflicting directions ("move left and right", diagonal "move left
     * and up") return null and reach the move-class clarification.
     * "remove …" and "movement" never match: the utterance must be
     * exactly "move" or start with "move ".
     */
    fun extractPointerDirection(text: String): PointerDirection? {
        var n = normalizeCommand(text.lowercase().trim())
        if (n.startsWith("please ")) n = n.removePrefix("please ").trim()
        if (n != "move" && !n.startsWith("move ")) return null
        val hasLeft = n.contains("left")
        val hasRight = n.contains("right")
        val hasUp = n.contains("up")
        val hasDown = n.contains("down")
        val horizontal = listOf(hasLeft, hasRight).count { it }
        val vertical = listOf(hasUp, hasDown).count { it }
        if (horizontal + vertical != 1) return null
        return when {
            hasLeft -> PointerDirection.LEFT
            hasRight -> PointerDirection.RIGHT
            hasUp -> PointerDirection.UP
            else -> PointerDirection.DOWN
        }
    }

    /**
     * Move-class clarification: pointer-move phrasing with no usable
     * direction. Deliberately narrow — "move to a new city" is not
     * pointer talk and must keep reaching the engine, not this.
     */
    private fun isMoveClarification(t: String): Boolean {
        val n = normalizeCommand(t)
        if (extractPointerDirection(t) != null) return false
        return n == "move" || n.startsWith("move the pointer") ||
            n.startsWith("move pointer") ||
            (n.startsWith("move ") && n.contains("sideways"))
    }

    /**
     * Device-evidence fix (2026-11-05): the app name of "open <app>",
     * "launch <app>", "can you open (my) <app>". Lowercased by the
     * matcher (the launcher reports the real app label on success);
     * empty when no app was named. "start <app>" is deliberately NOT
     * a launch trigger — "start" already belongs to START_LISTENING
     * and ACTIVATE_ASSISTANT_MODE, and those exact branches run first.
     *
     * MASTER F1c: leading words ("let's go", "please", "can you",
     * "could you", "I want you to", "I said you should") are stripped
     * before the prefix test, so "Let's go open YouTube for me"
     * matches LAUNCH_APP. MASTER F2a: trailing filler ("for me",
     * "now", "please", "right now", "for me please") is stripped from
     * the extracted name, so "open YouTube for me" extracts
     * "youtube", never "youtube for me".
     */
    fun extractLaunchApp(text: String): String {
        var n = stripLeadingWords(normalizeCommand(text.lowercase().trim()))
        val prefixes = listOf(
            "can you open my ", "can you open ",
            "open my ", "open ", "launch my ", "launch ",
        )
        for (p in prefixes) {
            if (n.startsWith(p)) {
                val rest = n.removePrefix(p).trim().trimEnd('.', '!', '?').trim()
                // Task-record fix (2026-11-07): a composite's second
                // clause is NOT part of the app name — "open YouTube
                // Music and play music" names the app "youtube music",
                // never "youtube music and play music". Without this
                // cut the LAUNCH_APP fallback swallowed the whole tail
                // as the app name and the play half died at routing.
                // Only a tail VERB cuts: "Tom and Jerry" keeps its
                // "and" because "jerry" is not a tail verb.
                return stripTrailingFiller(truncateCompositeTail(rest))
            }
        }
        return ""
    }

    fun isLaunchApp(text: String): Boolean = extractLaunchApp(text).isNotBlank()

    /**
     * MASTER F1b: the app name of "go to <app>" / "take me to <app>" /
     * "switch to <app>". Lowercased, empty when no app was named or
     * when the "app" is really a mode ("go to orb mode" is MODE_ORB —
     * matchMode runs first in matchExact, and this extractor refuses
     * mode words too, so it can never claim one). Leading "my"/"the"
     * after the verb is not part of the name ("go to my DM" → "dm",
     * "go to the settings" → "settings"); trailing filler strips
     * like [extractLaunchApp].
     */
    fun extractGoToApp(text: String): String {
        var n = stripLeadingWords(normalizeCommand(text.lowercase().trim()))
        val prefixes = listOf(
            "take me to my ", "take me to ", "go to my ", "go to ",
            "switch to ",
        )
        for (p in prefixes) {
            if (n.startsWith(p)) {
                var rest = n.removePrefix(p).trim().trimEnd('.', '!', '?').trim()
                if (rest.startsWith("my ")) rest = rest.removePrefix("my ").trim()
                if (rest.startsWith("the ")) rest = rest.removePrefix("the ").trim()
                val first = rest.split(' ', ',', '.').firstOrNull().orEmpty()
                if (first in setOf("orb", "character", "invisible", "keyboard", "mode")) return ""
                if (rest.isBlank()) return ""
                return stripTrailingFiller(truncateCompositeTail(rest))
            }
        }
        return ""
    }

    fun isGoToApp(text: String): Boolean = extractGoToApp(text).isNotBlank()

    /**
     * MASTER F1d: the direction of "scroll down" / "scroll up"
     * ("scroll down a bit" counts), or null. Left/right are swipes,
     * never scrolls (see TaskPlanner.extractConsoleOp) — "scroll
     * left" returns null and reaches the swipe clarification, never
     * a guessed direction.
     */
    fun extractScrollDirection(text: String): String? {
        val n = stripLeadingWords(normalizeCommand(text.lowercase().trim()))
        return when {
            n == "scroll down" || n.startsWith("scroll down ") -> "down"
            n == "scroll up" || n.startsWith("scroll up ") -> "up"
            else -> null
        }
    }

    fun isScroll(text: String): Boolean = extractScrollDirection(text) != null

    /** MASTER F1d: "go back" — the follow-up David used mid-search.
     *  "guide back" / "previous step" still win as GUIDE_BACK (their
     *  branch runs first in matchExact). */
    fun isGoBack(text: String): Boolean {
        val n = stripLeadingWords(normalizeCommand(text.lowercase().trim()))
        return n == "go back" || n.startsWith("go back ") || n.contains(" go back ")
    }

    /**
     * MASTER F1d: the target of "tap <x>" / "click <x>" /
     * "click on <x>" / "select <x>", original casing preserved for
     * display. Empty when no target was given. "tap it for me" is
     * DEMO_TAP (its branch runs first) and never reaches here.
     */
    fun extractTapTarget(text: String): String {
        val n = stripLeadingWords(normalizeCommand(text.lowercase().trim()))
        for (p in listOf("click on ", "tap ", "click ", "select ")) {
            if (n.startsWith(p)) {
                return stripTrailingFiller(n.removePrefix(p).trim())
            }
        }
        return ""
    }

    fun isTap(text: String): Boolean = extractTapTarget(text).isNotBlank()

    /**
     * MASTER F1d: "press enter" / "hit enter" / "click on enter" /
     * "press the enter key" — the Enter key, not a tap on a target
     * called "enter" (this check runs before TAP in matchExact).
     * Bare "enter" counts; "enter the code" does not (it names
     * something to enter, not the key).
     */
    fun isPressEnter(text: String): Boolean {
        val n = stripLeadingWords(normalizeCommand(text.lowercase().trim()))
        return n == "enter" || n == "press enter" || n == "hit enter" ||
            n == "click on enter" || n == "tap enter" ||
            n == "press the enter key" ||
            n == "hit the enter key" ||
            n.startsWith("press enter ") || n.startsWith("hit enter ") ||
            n.startsWith("click on enter ") || n.startsWith("tap enter ")
    }

    /**
     * Task-record fix (2026-11-07) — the app a multi-step instruction
     * names, original casing preserved ("YouTube Music", never the
     * lowercased matcher text), or "" when it names none. This is
     * what seeds the [TaskRecord] when the instruction is understood:
     * the composite's first clause ("open YouTube Music and play …"
     * → "YouTube Music"), a search's app ("search for X in YouTube"
     * → "YouTube"), or a plain launch's first clause. A bare tail
     * ("play music", "search for Davido") names no app — "" — and
     * the caller falls back to the active record instead.
     */
    fun extractPlanApp(text: String): String {
        val verbs = PLAN_TAIL_VERBS.joinToString("|")
        // Composite, case preserved: the app is group 2, between the
        // open verb and the "and|then <verb>" join.
        Regex(
            """^(?:loom[,\s]+)?(?:please\s+)?(?:can\s+you\s+)?(?:do\s+)?(?:open|launch)\s+(.+?)\s+(?:and|then)\s+($verbs)\b""",
            RegexOption.IGNORE_CASE,
        ).find(text.trim())?.let { m ->
            return stripTrailingFiller(m.groupValues[1].trim().trimEnd('.', '!', '?').trim())
        }
        // Simple-task execution fix (2026-11-07): "play … on <app>"
        // names its app after the last " on " — "play me some music
        // on YouTube" → "YouTube". The old extractor could not see
        // it at all (no play pattern), so the record's app came out
        // "" and the planner re-asked for an app already named.
        Regex(
            """^(?:loom[,\s]+)?(?:please\s+)?(?:can\s+you\s+)?(?:do\s+)?play\s+.+\s+on\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text.trim())?.let { m ->
            val app = m.groupValues[1].trim().trimEnd('.', '!', '?').trim()
            if (app.isNotBlank()) return stripTrailingFiller(truncateCompositeTail(app))
        }
        // Find-words play shape: "find <song> on <app> and play it"
        // → the app between "on" and the "and play" join.
        Regex(
            """^(?:loom[,\s]+)?(?:please\s+)?(?:can\s+you\s+)?(?:do\s+)?find\s+.+?\s+on\s+(.+?)\s+and\s+play\b""",
            RegexOption.IGNORE_CASE,
        ).find(text.trim())?.let { m ->
            val app = m.groupValues[1].trim().trimEnd('.', '!', '?').trim()
            if (app.isNotBlank()) return stripTrailingFiller(truncateCompositeTail(app))
        }
        // Search form: the app after "in" / "on", first clause only
        // ("search for Spider-Man in YouTube and open the first
        // result" → "YouTube", never the tail), casing as spoken.
        extractSearchApp(text).takeIf { it.isNotBlank() }?.let {
            return recase(text, truncateCompositeTail(it))
        }
        // Plain launch, first clause only, casing as spoken.
        return recase(text, extractLaunchApp(text))
    }

    /**
     * Re-cases a lowercased extraction against the original text:
     * the extractors lowercase, but the record keeps what David
     * actually said ("YouTube Music", not "youtube music"). Finds
     * the lowered string in the original (case-insensitive) and
     * returns the original slice; falls back to the lowered form
     * when it cannot be found.
     */
    private fun recase(original: String, lowered: String): String {
        if (lowered.isBlank()) return ""
        val idx = original.indexOf(lowered, ignoreCase = true)
        return if (idx >= 0) original.substring(idx, idx + lowered.length) else lowered
    }

    /**
     * Cuts a launch remainder at its composite join — "youtube music
     * and play music" → "youtube music". Only a join followed by a
     * tail verb cuts, so app names containing "and" survive.
     */
    private fun truncateCompositeTail(rest: String): String {
        val verbs = PLAN_TAIL_VERBS.joinToString("|")
        val cut = Regex("""\s+(?:and|then)\s+($verbs)\b""", RegexOption.IGNORE_CASE)
            .find(rest) ?: return rest
        return rest.substring(0, cut.range.first).trim().trimEnd('.', '!', '?').trim()
    }

    // ---------- Dictated messaging + calls (2026-11-05, fix-plan item 12) ----------

    /**
     * The channel of a dictated message, or null when the utterance
     * names no channel. WhatsApp wins when both words appear
     * ("send WhatsApp to Mum saying…" never also says SMS); SMS covers
     * "sms", "text" and "send text".
     */
    fun extractMessageChannel(text: String): MessageChannel? {
        val t = text.lowercase()
        return when {
            t.contains("whatsapp") -> MessageChannel.WHATSAPP
            t.contains("sms") || t.contains("text message") ||
                normalizeCommand(t).startsWith("text ") ||
                normalizeCommand(t) == "text" ||
                t.contains("send text") -> MessageChannel.SMS
            else -> null
        }
    }

    /**
     * The recipient of "send WhatsApp to X saying Y" / "text X saying
     * Y" / "send SMS to X saying Y" (also "message X on WhatsApp
     * saying Y"). Original casing preserved. The split is on the
     * FIRST "saying" — a "saying" inside the message stays in the
     * text. Empty when no recipient was given.
     */
    fun extractMessageTarget(text: String): String {
        // No "saying" yet ("send WhatsApp to Mum") — the whole
        // utterance is the head, so the target still extracts and the
        // clarification can ask what to say to them.
        val head = textBeforeSaying(text) ?: text
        var h = head.trim()
        // Strip the introducer — longest first so "send a whatsapp
        // message to " is not truncated to "send ".
        val prefixes = listOf(
            "send a whatsapp message to ", "send whatsapp message to ",
            "send whatsapp to ", "send a whatsapp to ",
            "send a text message to ", "send text message to ",
            "send sms to ", "send a text to ", "send text to ",
            "send a message to ", "send message to ",
            "message ", "text ", "send ",
        )
        val lower = h.lowercase()
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                h = h.substring(p.length)
                break
            }
        }
        // "message X on WhatsApp" — the trailing channel tag is not
        // part of the name.
        val hLower = h.lowercase().trim()
        for (suffix in listOf(" on whatsapp", " via whatsapp", " on sms", " via sms")) {
            if (hLower.endsWith(suffix)) {
                h = h.substring(0, h.length - suffix.length)
                break
            }
        }
        val result = h.trim().trimEnd('.', '!', '?').trim()
        // A bare channel word is not a recipient: "send WhatsApp" /
        // "send SMS" / "text" name nobody.
        return if (result.lowercase() in setOf("whatsapp", "whats app", "sms", "text", "message")) "" else result
    }

    /**
     * The message text of "… saying Y" — everything after the FIRST
     * "saying", original casing and wording preserved verbatim. The
     * executor types exactly this (surrounding whitespace trimmed
     * only); a "saying" inside the message stays in the text. Empty
     * when no "saying Y" was given.
     */
    fun extractMessageText(text: String): String {
        val idx = text.indexOf(" saying ", ignoreCase = true)
        if (idx < 0) return ""
        return text.substring(idx + " saying ".length).trim()
    }

    /** The head of the utterance before the first " saying ", or null. */
    private fun textBeforeSaying(text: String): String? {
        val idx = text.indexOf(" saying ", ignoreCase = true)
        return if (idx < 0) null else text.substring(0, idx)
    }

    /** True when the utterance is a WhatsApp message request with recipient + text. */
    fun isSendWhatsApp(text: String): Boolean =
        extractMessageChannel(text) == MessageChannel.WHATSAPP &&
            extractMessageTarget(text).isNotBlank() &&
            extractMessageText(text).isNotBlank()

    /** True when the utterance is an SMS request with recipient + text. */
    fun isSendSms(text: String): Boolean =
        extractMessageChannel(text) == MessageChannel.SMS &&
            extractMessageTarget(text).isNotBlank() &&
            extractMessageText(text).isNotBlank()

    /**
     * The target of "call X" / "please call X" / "call my X".
     * Original casing preserved. A bare number ("call 0803…") parses
     * as digits, not a name — the executor routes numbers to the
     * ACTION_DIAL fallback. Empty when no target was given.
     */
    fun extractCallTarget(text: String): String {
        var n = normalizeCommand(text.lowercase().trim())
        if (n.startsWith("please ")) n = n.removePrefix("please ").trim()
        // The matcher lowercases; the manager calls this on the
        // original text for display, so re-derive from the original
        // casing: find the prefix case-insensitively in the original.
        val prefixes = listOf("please call my ", "please call ", "call my ", "call ")
        for (p in prefixes) {
            val idx = text.indexOf(p, ignoreCase = true)
            if (idx < 0) continue
            // The prefix must start the utterance, or follow only the
            // optional "loom," prefix / "please" — "recall Mum" must
            // never match ("re" is not an allowed lead-in).
            val lead = text.substring(0, idx).trim().lowercase()
                .trimStart().removePrefix("loom").trimStart(',', ' ').trim()
            if (lead.isEmpty() || lead == "please") {
                return text.substring(idx + p.length).trim().trimEnd('.', '!', '?').trim()
            }
        }
        return ""
    }

    /** True when the utterance is a call request with a target. */
    fun isPlaceCall(text: String): Boolean {
        val n = normalizeCommand(text.lowercase().trim())
        val startsCall = n == "call" || n.startsWith("call ") ||
            n.startsWith("please call ")
        return startsCall && extractCallTarget(text).isNotBlank()
    }

    /**
     * Bulk / automated messaging, refused at PARSE time — the manager
     * checks this before match(), so "send WhatsApp to everyone
     * saying hi" never becomes a send command, never reaches the
     * executor, and never reaches the engine as a capability request.
     * The executor refuses bulk recipients again as a second line of
     * defence. One recipient per invocation, always.
     *
     * Only the RECIPIENT part (before the first "saying") is checked:
     * "send WhatsApp to Mum saying tell everyone hi" is a one-person
     * message whose TEXT mentions everyone — it must not be refused.
     */
    fun isBulkMessageRequest(text: String): Boolean {
        val t = text.lowercase()
        val head = t.substringBefore(" saying ")
        val isMessageOrCall = head.contains("send") || head.contains("message") ||
            head.contains("text") || head.contains("call") || head.contains("broadcast")
        if (!isMessageOrCall) return false
        return head.contains("everyone") || head.contains("everybody") ||
            head.contains("all contacts") || head.contains("all my contacts") ||
            head.contains("all of my contacts") || head.contains("everyone in") ||
            head.contains("send to all") || head.contains("message all") ||
            head.contains("text all") || head.contains("broadcast")
    }

    /** True when the utterance is a message request of any completeness. */
    private fun isMessageRequest(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("whatsapp") || t.contains("sms") ||
            normalizeCommand(t).startsWith("text ") || normalizeCommand(t) == "text" ||
            t.contains("send text") || t.contains("send a text")
    }

    /** True when the utterance is a message request with no recipient. */
    private fun isBareMessageRequest(text: String): Boolean =
        isMessageRequest(text) && extractMessageTarget(text).isBlank()

    private fun extractAfter(text: String, phrase: String): String {
        val i = text.indexOf(phrase, ignoreCase = true)
        if (i < 0) return ""
        return text.substring(i + phrase.length).trim().trimEnd('.', '!', '?').trim()
    }

    // ---------- Master §2 (item 4): SEARCH_IN_APP parsing ----------

    /**
     * "search for <query> [in <app>]" (also "search <query> [in <app>]").
     * The app split uses the LAST " in " so a query containing "in"
     * survives ("search for hotels in Lagos in Booking" → query
     * "hotels in Lagos", app "Booking"). Lowercased by the matcher (the
     * executor reports the real outcome); empty when no query was given.
     *
     * Device-evidence fix (2026-11-05, item 6 — matcher widening ONLY;
     * no provenance change, MODEL-origin handling untouched):
     *  - "can you search for X" is a match now (searchRemainder strips
     *    the "can you search" prefix);
     *  - "search <app> for <query>" splits on the FIRST " for "
     *    ("search YouTube for Spider-Man" → query "spider-man", app
     *    "youtube") — but NOT when the utterance used the "search
     *    for …" introducer ("search for hotels for dogs" keeps its
     *    query whole; the " for " split only applies to the
     *    app-first form);
     *  - "… on <app>" splits on the LAST " on " ("search Spider-Man
     *    on YouTube" → query "spider-man", app "youtube").
     * Trade-off, stated plainly: a bare query containing " for " or
     * " on " with no introducer ("search hotels for dogs") now reads
     * as app-first and the executor answers AppNotFound honestly
     * instead of searching the whole phrase — the app-first form is
     * the one David actually uses, and the failure names the app.
     */
    fun extractSearchQuery(text: String): String {
        val rest = searchRemainder(text) ?: return ""
        val inIdx = rest.lastIndexOf(" in ")
        val query = if (inIdx >= 0) {
            rest.substring(0, inIdx)
        } else if (!hasSearchForIntroducer(text) && rest.contains(" for ")) {
            rest.substring(rest.indexOf(" for ") + 5)
        } else {
            val onIdx = rest.lastIndexOf(" on ")
            if (onIdx >= 0) rest.substring(0, onIdx) else rest
        }
        return query.trim().trimEnd('.', '!', '?').trim()
    }

    /**
     * The app of "search for <query> in <app>", or [contextApp] (the
     * last-turn app from ConversationContext) when none was named, or ""
     * when neither exists. The caller asks "which app?" when this is
     * blank. "YouTube Music" is preserved whole — it must never be
     * truncated to "YouTube" (Worker 1's AppCatalogue disambiguates the
     * two at execution time; the matcher only carries the exact string).
     */
    fun extractSearchApp(text: String, contextApp: String? = null): String {
        val rest = searchRemainder(text) ?: return contextApp ?: ""
        val inIdx = rest.lastIndexOf(" in ")
        if (inIdx >= 0) {
            val app = rest.substring(inIdx + 4).trim().trimEnd('.', '!', '?').trim()
            // MASTER F2a: trailing filler is not part of the app
            // name — "search for Davido in YouTube for me" names
            // the app "youtube", never "youtube for me".
            if (app.isNotBlank()) return stripTrailingFiller(app)
        }
        // "search <app> for <query>" — app is before the FIRST " for ".
        if (!hasSearchForIntroducer(text) && rest.contains(" for ")) {
            val app = rest.substring(0, rest.indexOf(" for ")).trim()
                .trimEnd('.', '!', '?').trim()
            if (app.isNotBlank()) return stripTrailingFiller(app)
        }
        // "… on <app>" — app is after the LAST " on ".
        val onIdx = rest.lastIndexOf(" on ")
        if (onIdx >= 0) {
            val app = rest.substring(onIdx + 4).trim().trimEnd('.', '!', '?').trim()
            if (app.isNotBlank()) return stripTrailingFiller(app)
        }
        return contextApp ?: ""
    }

    /**
     * True when the utterance used the query introducer "search for …"
     * / "can you search for …" — the " for " app-first split must not
     * fire then, or "search for hotels for dogs" would lose its query
     * to a phantom app.
     */
    private fun hasSearchForIntroducer(text: String): Boolean {
        var n = normalizeCommand(text.lowercase().trim())
        if (n.startsWith("please ")) n = n.removePrefix("please ").trim()
        return n.startsWith("search for ") || n.startsWith("can you search for ")
    }

    fun isSearchApp(text: String): Boolean = extractSearchQuery(text).isNotBlank()

    /**
     * The text after "search " (with an optional "for " stripped),
     * lowercased, or null when the utterance is not a search request.
     * "search" alone yields "" (a present-but-empty remainder) so the
     * bare-search clarification can fire.
     *
     * Device-evidence fix (2026-11-05, item 6): "can you search …"
     * is a search request too — the "can you search" prefix is
     * stripped here, so "can you search for X" matches SEARCH_IN_APP
     * instead of falling to the engine.
     */
    private fun searchRemainder(text: String): String? {
        var n = normalizeCommand(text.lowercase().trim())
        if (n.startsWith("please ")) n = n.removePrefix("please ").trim()
        val rest: String = when {
            n.startsWith("can you search") -> n.removePrefix("can you search").trim()
            n == "search" || n.startsWith("search ") -> n.removePrefix("search").trim()
            else -> return null
        }
        var r = rest
        if (r.startsWith("for ")) r = r.removePrefix("for ").trim()
        else if (r == "for") r = ""
        // "search in YouTube" / "search for in YouTube" / "search on
        // YouTube": an app with no query — the remainder is empty so
        // the bare-search clarification ("What do you want me to search
        // for?") fires instead of searching for the word "in"/"on".
        if (r.startsWith("in ") || r.startsWith("on ")) return ""
        // Device-failure fix (2026-11-13, F2): trailing behaviour
        // meta-speech is cut HERE, where the goal is first formed —
        // before the query/app split, before narration, before the
        // planner. core:common cannot import the planner's
        // TaskTextSanitizer (dependency direction), so this is the
        // same meta-speech class as a local mirror, on the already
        // lowercased remainder. Keep the two lists in step: a phrase
        // added to one must be added to the other.
        return cutMetaSpeech(r)
    }

    /**
     * Cuts behaviour meta-speech ("don't make me repeat myself",
     * "you're still quiet", "you're not doing anything", and the
     * class — see TaskTextSanitizer) from the end of a lowercased
     * search remainder. Earliest match wins; the head is the task.
     */
    private fun cutMetaSpeech(remainder: String): String = stripMetaSpeech(remainder)

    /**
     * Device-failure fix (2026-11-13, F2): cuts behaviour
     * meta-speech from ANY goal-forming text (voice remainder,
     * model-proposed query/app, typed text) at the point the goal
     * is FIRST formed — before narration and before the planner.
     * Matching is case-insensitive; the surviving head keeps its
     * original casing. The planner's TaskTextSanitizer re-cuts
     * downstream; this is the first-forming cut, not the only one.
     */
    fun stripMetaSpeech(text: String): String {
        val lower = text.lowercase()
        var cutAt = text.length
        for (pattern in META_SPEECH_PATTERNS) {
            pattern.find(lower)?.let { m ->
                if (m.range.first < cutAt) cutAt = m.range.first
            }
        }
        return text.substring(0, cutAt).trim().trimEnd('.', '!', '?').trim()
    }

    private val META_SPEECH_PATTERNS = listOf(
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

    // ---------- Master §2 (item 5): ASSISTANT_MOVE parsing ----------

    /**
     * The region of an assistant-move utterance, as a Worker 5
     * AssistantRegion enum name ("TOP_LEFT", "OUT_OF_WAY", …), or null
     * when it names no region. Hyphens normalize to spaces, so
     * "top-left corner" and "top left corner" parse identically.
     * Pointer-subject utterances ("move the pointer to the top left")
     * return null here — the pointer owns its own phrasings. Bare
     * "move left/right/up/down" also returns null: without a region or
     * the assistant as subject it is a pointer move (existing
     * behaviour). "move to the top" / "move to the bottom" name no
     * region in the enum and return null — they reach the grounded
     * fallback instead of a guessed corner.
     */
    fun extractAssistantRegionName(text: String): String? {
        var n = normalizeCommand(text.lowercase().trim()).replace('-', ' ')
        if (n.startsWith("please ")) n = n.removePrefix("please ").trim()
        if (n != "move" && !n.startsWith("move ")) return null
        if (n.contains("pointer")) return null
        if (n.contains("out of the way") || n.contains("out of my way")) {
            return "OUT_OF_WAY"
        }
        if (n.contains("top left")) return "TOP_LEFT"
        if (n.contains("top right")) return "TOP_RIGHT"
        if (n.contains("bottom left")) return "BOTTOM_LEFT"
        if (n.contains("bottom right")) return "BOTTOM_RIGHT"
        if (n.contains("left edge")) return "LEFT_EDGE"
        if (n.contains("right edge")) return "RIGHT_EDGE"
        // "move down a bit" / "move left a bit": a small nudge of the
        // assistant itself — the pointer has no "a bit" qualifier.
        // "move the assistant left" / "move yourself up": the assistant
        // as subject. Both need exactly one direction; conflicting
        // directions return null and reach the clarification.
        val assistantSubject = n.contains("assistant") || n.contains("yourself")
        val isABit = n.contains(" a bit") || n.endsWith(" bit")
        if (assistantSubject || isABit) {
            val hasLeft = n.contains("left")
            val hasRight = n.contains("right")
            val hasUp = n.contains("up")
            val hasDown = n.contains("down")
            val count = listOf(hasLeft, hasRight, hasUp, hasDown).count { it }
            if (count != 1) return null
            return when {
                hasLeft -> "LEFT"
                hasRight -> "RIGHT"
                hasUp -> "UP"
                else -> "DOWN"
            }
        }
        return null
    }

    /**
     * Assistant-move clarification: an assistant-move phrasing with no
     * usable region ("move the assistant", "move yourself"). Narrow —
     * anything that matched ASSISTANT_MOVE never reaches here, and
     * "move the pointer …" is pointer territory, not this.
     */
    private fun isAssistantMoveClarification(t: String): Boolean {
        if (extractAssistantRegionName(t) != null) return false
        val n = normalizeCommand(t)
        return n == "move the assistant" || n.startsWith("move the assistant") ||
            n == "move yourself" || n.startsWith("move yourself")
    }
}

/**
 * Master §2 (item 1): bounded, deterministic STT-error tolerance for the
 * voice matcher.
 *
 * Mechanism — token-aligned fuzzy phrase match, as the SECOND layer
 * after the exact matcher (match() tries matchExact() first, so an
 * exact phrase always beats a fuzzy near-neighbour):
 * - Both sides are tokenized on spaces; the token COUNTS must be equal,
 *   so a mid-sentence occurrence ("I'm telling you to go silent", 6
 *   tokens) can never fuzzy-match a 2-token command.
 * - Every aligned token pair must score similarity >= 0.40, where
 *   similarity = 1 − Levenshtein(a, b) / max(len(a), len(b)).
 * - The MEAN token similarity must be >= 0.50.
 * - Best-match selection: when several commands pass, the highest mean
 *   wins — but only if it beats the runner-up by >= 0.05, otherwise the
 *   utterance is ambiguous and matches nothing (clarification /
 *   grounded fallback). This is what keeps the minimal pairs apart:
 *   "start"/"stop" score 0.40 and "activate"/"deactivate" 0.80, so
 *   without best-match they would cross-fire.
 * - Only the narrow exact-phrase commands are eligible (GO_SILENT,
 *   SPEAK_AGAIN, HIDE/SHOW, ACTIVATE/DEACTIVATE, STOP/START_LISTENING,
 *   END_VOICE, plus POINTER_SHOW's fixed phrases from the 2026-11-05
 *   accessibility fix); broad contains-based commands never fuzzy-match.
 * - SHUTDOWN uses a separate rule, only when no phrase matched:
 *   "shut down" plus any token similar to "loom" at >= 0.50 ("low"
 *   scores exactly 0.50). There is NO confirmation question anymore
 *   (Nov 13 ~03:36: shutdown is immediate) — the negation guard in
 *   matchFuzzy is the backstop for negated near-misses.
 *
 * What it catches: "ghost silence" → "go silent" (0.40 + 0.71, mean
 * 0.56), "go quite" → "go quiet" (0.80), "stop listing" → "stop
 * listening" (0.78), "shut down low" → SHUTDOWN. What it refuses:
 * "go home" vs "go quiet" (0.20 on the second token), "shut down
 * slow" vs "loom" (0.25), "shut down boys" vs "shut down voice"
 * (0.20). No unbounded guessing: the vocabulary is the fixed command
 * list, the thresholds are fixed, and anything below them falls through
 * to clarification or the grounded fallback — never to execution.
 */
object FuzzyMatch {

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /** Token similarity in [0, 1]: 1 − edit distance / longer length. */
    fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - distance(a, b).toDouble() / max
    }

    /**
     * Mean token similarity of [text] against [phrase], or null when it
     * does not match (token-count mismatch, any token below 0.40, or
     * mean below 0.50). The mean is the score matchFuzzy() ranks
     * candidates by — the best-matching command wins, and a near-tie
     * clarifies instead of guessing.
     */
    fun phraseSimilarity(text: String, phrase: String): Double? {
        val tTokens = text.split(' ').filter { it.isNotEmpty() }
        val pTokens = phrase.split(' ')
        if (tTokens.size != pTokens.size) return null
        var sum = 0.0
        for (i in tTokens.indices) {
            val sim = tokenSimilarity(tTokens[i], pTokens[i])
            if (sim < 0.40) return null
            sum += sim
        }
        val mean = sum / tTokens.size
        return if (mean >= 0.50) mean else null
    }

    fun phraseMatches(text: String, phrase: String): Boolean =
        phraseSimilarity(text, phrase) != null

    /** Any whitespace/punctuation-separated token similar to "loom". */
    fun hasLoomLikeToken(text: String): Boolean =
        text.split(' ', ',', '.', '!', '?').any {
            it.isNotBlank() && tokenSimilarity(it, "loom") >= 0.50
        }
}
