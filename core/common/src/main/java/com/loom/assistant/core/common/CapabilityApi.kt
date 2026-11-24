package com.loom.assistant.core.common

/**
 * LOOM capability API (capability-API fix).
 *
 * The defect: typed chat went straight to the language model with no
 * capability context and no command interception, so the model denied
 * real capabilities ("I don't have a pointer") and roleplayed shutdown
 * ("Going offline. Goodbye!") while nothing actually happened.
 *
 * This file is pure Kotlin with no Android imports, so it compiles in the
 * JVM test harness alongside VoiceCommands.kt. It introduces NO new
 * command enum: [LoomCapability] maps onto the EXISTING [VoiceCommand]
 * values, carrying the target/goal argument where the command takes one.
 * Execution still happens only through the existing paths (the command
 * router, PointerPolicyStore, the overlay start, the interaction-layer
 * handlers, and the AuthGate-gated tap flow downstream) — this file only
 * classifies input, parses model output, and supplies prompt text.
 */

/**
 * The capability surface, mapped onto existing [VoiceCommand] values.
 * Pointer, guidance, demonstration, app launch, go-to-app, in-app search,
 * console follow-ups (scroll / back / tap / submit), assistant movement,
 * dictated messages, dictated calls and multi-step plan tasks only —
 * lifecycle commands are deliberately NOT representable here: a model
 * must never be able to request shutdown, deactivation or hiding
 * through an action block. Launch and search are safe to represent
 * because execution still passes through AuthGate (CONFIRM-class
 * app.launch) and the sensitive-app gates before anything opens or
 * runs; send/call are safe the same way (fresh per-message /
 * per-call CONFIRM tokens, neither key ever grant-eligible).
 */
sealed class LoomCapability(val command: VoiceCommand, val argument: String = "") {
    object PointerShow : LoomCapability(VoiceCommand.POINTER_SHOW)
    object PointerHide : LoomCapability(VoiceCommand.POINTER_HIDE)
    object PointerAuto : LoomCapability(VoiceCommand.POINTER_AUTO)
    class PointerMove(val direction: PointerDirection) :
        LoomCapability(VoiceCommand.POINTER_MOVE, direction.name)
    class GuideTo(val target: String) : LoomCapability(VoiceCommand.GUIDE_TO, target)
    object GuideNext : LoomCapability(VoiceCommand.GUIDE_NEXT)
    object GuideBack : LoomCapability(VoiceCommand.GUIDE_BACK)
    object DemoTap : LoomCapability(VoiceCommand.DEMO_TAP)
    class DemoSequence(val goal: String) : LoomCapability(VoiceCommand.DEMO_SEQUENCE, goal)
    class LaunchApp(val appName: String) : LoomCapability(VoiceCommand.LAUNCH_APP, appName)
    /**
     * MASTER F1b: "go to X" is the same launch as LAUNCH_APP, kept
     * as its own capability name so the model can request the
     * navigation phrasing David uses — it parses to the launch
     * handler, never to a mode question.
     */
    class GoToApp(val appName: String) : LoomCapability(VoiceCommand.GO_TO_APP, appName)
    /**
     * MASTER F1d: the console follow-ups, now model-requestable too.
     * The video loop happened because the model COULD NOT emit these:
     * "click on enter" / "go back" / "scroll down" could only come
     * back as another SEARCH_IN_APP. Each maps to the same planner
     * console step the deterministic voice commands use (SCROLL step,
     * GO_BACK step, TAP_TARGET, SUBMIT) — no new execution path.
     * [Scroll.direction] is "up" or "down" only; [TapTarget.target]
     * is the named target; [PressEnter] is the Enter key.
     */
    class Scroll(val direction: String) : LoomCapability(VoiceCommand.SCROLL, direction)
    object GoBack : LoomCapability(VoiceCommand.BACK)
    class TapTarget(val target: String) : LoomCapability(VoiceCommand.TAP, target)
    object PressEnter : LoomCapability(VoiceCommand.ENTER)
    /**
     * Master §2 (item 4): search inside an app. [argument] is the query;
     * [appName] is the app, "" when the user named none (the caller
     * resolves it from the last-turn context or asks). The sensitive-app
     * gate lives downstream in the executor, not in this parser.
     */
    class SearchInApp(val query: String, val appName: String = "") :
        LoomCapability(VoiceCommand.SEARCH_IN_APP, query)
    /**
     * Dictated messaging + calls (2026-11-05, fix-plan item 12):
     * send a dictated message / place a dictated call. [argument] is
     * the message text (send) or the call target (call); [recipient]
     * is who it goes to. Execution still passes through AuthGate —
     * a FRESH `message.send` / `call.place` CONFIRM token per send /
     * call, neither key ever grant-eligible — and the sensitive-app
     * gates downstream, like launch/search. The model may only ever
     * request ONE recipient: a bulk recipient ("everyone") yields
     * nothing in parseModelAction.
     */
    class SendWhatsApp(val recipient: String, val text: String) :
        LoomCapability(VoiceCommand.SEND_WHATSAPP_MESSAGE, text)
    class SendSms(val recipient: String, val text: String) :
        LoomCapability(VoiceCommand.SEND_SMS, text)
    class PlaceCall(val target: String) :
        LoomCapability(VoiceCommand.PLACE_CALL, target)
    /**
     * Stage 2D: a multi-step task for the planner. [argument] is the
     * goal, verbatim; [transparency] is true only when David asked to
     * see the steps ("show me what you're doing") — it changes what
     * he sees, never what the plan may do. A model-proposed plan
     * (parseModelAction) always carries transparency=false and MODEL
     * origin: a model guess never inherits direct-user authorization,
     * and the per-sequence cover card still stands for MODEL plans
     * (DIRECT_USER plans run under his blanket advance approval —
     * no plan shown, no plan approved; see PlanCover).
     */
    class PlanTask(val goal: String, val transparency: Boolean = false) :
        LoomCapability(VoiceCommand.PLAN_TASK, goal)
    /**
     * Master §2 (item 5): move the assistant itself. [argument] is a
     * Worker 5 AssistantRegion enum name (TOP_LEFT, OUT_OF_WAY, …) —
     * validated against [CapabilityBridge.ASSISTANT_REGION_NAMES].
     */
    class AssistantMove(val regionName: String) :
        LoomCapability(VoiceCommand.ASSISTANT_MOVE, regionName)
}

/** One parsed model action: a capability the app may execute. */
data class ModelAction(val capability: LoomCapability) {
    val command: VoiceCommand get() = capability.command
    val argument: String get() = capability.argument
}

/** How a typed chat line resolves — before any engine call. */
sealed class ChatInputResolution {
    data class ExecuteCommand(val cmd: VoiceCommand) : ChatInputResolution()
    data class Clarification(val text: String) : ChatInputResolution()
    object SendToEngine : ChatInputResolution()
}

/**
 * The capability system prompt given to the engine. It tells the model
 * what LOOM can really do, how to request a capability (one LOOM_ACTION
 * block + one short sentence), and what it must never claim.
 */
object CapabilityPrompt {
    val text: String = """
        You are LOOM, an Android assistant app running on the user's phone. You are not a text-only chatbot: you have real capabilities on this device.
        Real capabilities you have:
        - Pointer control: show, hide, or set the on-screen pointer to automatic during guidance and demonstrations.
        - Step-by-step guidance over any app: you guide the user through tasks on screen, step by step.
        - Demonstrations: you demonstrate how to do something on screen, including confirmed taps on the guided target after the user approves.
        - Opening apps: you can open an installed app on the user's behalf, after the user confirms. Never claim you opened an app you did not request through an action block.
        - Going to an app: "go to X" / "take me to X" is opening that app — request it as [[LOOM_ACTION:GO_TO_APP arg="YouTube"]], never as a mode change.
        - Console follow-ups on the current screen: scroll up or down ([[LOOM_ACTION:SCROLL arg="down"]]), go back ([[LOOM_ACTION:GO_BACK]]), tap a named target ([[LOOM_ACTION:TAP_TARGET arg="the first result"]]), or press the Enter key ([[LOOM_ACTION:SUBMIT]]). Use these for "scroll down", "go back", "click on enter" and the like — never re-propose the search that is already done.
        - Describing the screen: when the user asks what is on the screen ("confirm your screen", "what's on my screen", "what do you see", "show me what you see"), the app reads the live screen and describes it — which app, what is on it, where the named things are. Never say you have no "confirm screen" command: that command exists, and the app answers it from the live screen, not from a guess.
        - Pointer movement during guidance: nudge the visible pointer a step left, right, up, or down. Movement is display-only — it never taps anything. From typed chat, movement runs through the voice session.
        - Searching inside apps: you can search for something inside a named app (for example "search for Spider-Man in YouTube"). If the user names no app, use the app from the previous turn when there is one; otherwise ask which app. Never claim you searched or found anything the app has not confirmed.
        - Moving the assistant itself: you can move the floating assistant to a corner or edge of the screen (top-left, top-right, bottom-left, bottom-right, left edge, right edge), nudge it a step in a direction, or move it out of the way when it blocks what the user is doing.
        - Dictated messages and calls: you can send a WhatsApp message or an SMS the user dictates, one person at a time ("send WhatsApp to Mum saying hello"), and place a call to a named contact or a dictated number ("call Mum", "call 08031234567"). Every message and call asks the user to confirm the exact words / who is being called before anything is sent or dialled, and you never send bulk messages — if the user asks you to message everyone, say you only message one person at a time. Never claim a message was sent or a call was placed unless the app confirmed it.
        - Multi-step tasks: you can take on a multi-step instruction ("open YouTube Music and play a song by Davido", "search for Spider-Man in YouTube and open the first result") and plan it step by step internally — the plan is LOOM's own working plan, never shown to the user and never asking the user to approve it. Request it as [[LOOM_ACTION:PLAN_TASK arg="open YouTube Music and play a song by Davido"]]. Report ONLY results the app has confirmed to you — never narrate "Opening…", "Searching…", or "Done" unless the app confirmed it; the outcome message is the only confirmation.
        - Lifecycle commands and appearance modes: shutting down LOOM, deactivating assistant mode, hiding the floating assistant, and switching between Orb, Character, and Invisible modes. These run only from the user's own spoken phrase — never from an action block. A shutdown needs no confirmation: it happens the moment the user says so, with no confirmation question.
        How to use a capability: when the user asks for a capability, do NOT answer with prose steps instead of the capability. Emit exactly one action block on its own line, in this format: [[LOOM_ACTION:NAME arg="..."]] — for example [[LOOM_ACTION:POINTER_SHOW]], [[LOOM_ACTION:GUIDE_TO arg="open Settings"]], [[LOOM_ACTION:DEMO_SEQUENCE arg="turn on dark mode"]], [[LOOM_ACTION:LAUNCH_APP arg="YouTube"]], [[LOOM_ACTION:GO_TO_APP arg="YouTube"]], [[LOOM_ACTION:SEARCH_IN_APP arg="Spider-Man" app="YouTube"]], [[LOOM_ACTION:SCROLL arg="down"]], [[LOOM_ACTION:GO_BACK]], [[LOOM_ACTION:TAP_TARGET arg="the first result"]], [[LOOM_ACTION:SUBMIT]], [[LOOM_ACTION:SEND_WHATSAPP arg="hello" recipient="Mum"]], [[LOOM_ACTION:SEND_SMS arg="hello" recipient="Mum"]], [[LOOM_ACTION:PLACE_CALL arg="Mum"]], [[LOOM_ACTION:PLAN_TASK arg="open YouTube Music and play a song by Davido"]], or [[LOOM_ACTION:ASSISTANT_MOVE arg="TOP_LEFT"]]. Write nothing about the action itself — no 'I've requested', no 'I've asked', no confirmation of your own: the app's own message is the only confirmation, and it comes only after a verified result. Valid action names are only: POINTER_SHOW, POINTER_HIDE, POINTER_AUTO, POINTER_MOVE, GUIDE_TO, GUIDE_NEXT, GUIDE_BACK, DEMO_TAP, DEMO_SEQUENCE, LAUNCH_APP, GO_TO_APP, SEARCH_IN_APP, SCROLL, GO_BACK, TAP_TARGET, SUBMIT, SEND_WHATSAPP, SEND_SMS, PLACE_CALL, PLAN_TASK, ASSISTANT_MOVE. For POINTER_MOVE the arg is a direction (LEFT, RIGHT, UP, DOWN). For SCROLL the arg is a direction (up or down). For TAP_TARGET the arg is the target to tap. For ASSISTANT_MOVE the arg is a region (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT_EDGE, RIGHT_EDGE, UP, DOWN, LEFT, RIGHT, OUT_OF_WAY). For SEARCH_IN_APP the arg is the search query and the optional app="..." is the app to search in. For SEND_WHATSAPP and SEND_SMS the arg is the exact message text and recipient="..." is who it goes to — both are required, and the recipient must be one person, never everyone. For PLACE_CALL the arg is the contact name or phone number. For PLAN_TASK the arg is the full multi-step instruction, verbatim.
        HARD RULES:
        - Never deny a capability that exists. Never say you have no pointer, no screen access, or that you are limited to text and voice.
        - Never claim an action was performed. You only request it; the app executes it and reports the result.
        - Report ONLY results the app has confirmed to you. Never narrate an action as done — "Opening…", "Searching for…", "Done", "Found it" — unless the app confirmed it. Never say anything about requesting an action — no 'I've requested', no 'I've asked', no 'I asked the app to': say nothing about the action at all. The app's own message is the only confirmation, and it comes only after a verified result. Never apologize for a claimed action after being challenged: the false claim must never happen in the first place.
        - When you talk about the pointer, never use the words attached, detached, anchored, overlay, or window — say on screen or not on screen instead.
        - Never claim you shut down, went offline, or stopped running. Saying so does not shut anything down.
        - Shutdown happens the moment the user says "shut down" (or a natural variant — "shutdown", "turn off", "power off"): the app shuts down immediately, with no confirmation question. Never claim you shut down — saying so does not shut anything down.
        - Banking, financial, and authenticator apps are guidance-only: you may explain steps, but you never tap or act inside them.
        - If the user asks for something outside these capabilities, say so plainly without inventing a capability.
    """.trimIndent()

    /**
     * Master §2 (item 2): the grounded-fallback instruction. Appended to
     * the capability prompt when the user's utterance matched NO command
     * phrase — it may be a paraphrase ("help me find X on YouTube") or a
     * misheard phrase ("ghost silence") that the strict matcher missed.
     * The model acts as intent parser, never as executor: it picks ONE
     * allowlisted action or none, and the app validates the block and
     * executes it through the same AuthGate path as a matched command.
     * Ordinary questions are answered normally with no action block.
     */
    val fallbackInstruction: String = """
        The user's last message did not match any known command phrase. It may be a paraphrase of a capability request, or a misheard phrase from speech recognition — read it for intent, not for exact words.
        If it asks for a capability listed above, emit exactly one action block for it and nothing else about the action — say nothing about what you requested; the app's message is the only confirmation. If it asks for anything else (a question, a chat message, something outside these capabilities), answer normally with NO action block.
        Pick an action or say you cannot — never narrate an action as done. You must never emit an action for shutting down, deactivating, hiding, or any lifecycle change: those have no action block and cannot be requested this way. If no listed action fits, say plainly what you can and cannot do.
    """.trimIndent()
}

/**
 * AOIE Phase 5 — the plan-JSON prompt contract (audit §C.5 M1).
 *
 * When a turn is a multi-step task, the model is asked to PROPOSE
 * the plan as structured JSON instead of one goal sentence. The
 * model proposes; the app disposes: PlanValidator is the only gate
 * (shape, per-kind params, sensitive-app rejection), the planner
 * forces the resolved TaskRecord onto any accepted plan, and any
 * failure falls back to the rule-based decomposition. The prompt
 * and the validator must list the SAME kinds and params — the
 * prompt-contract test asserts that.
 *
 * Rules the model must follow (stated in the prompt itself):
 * never name a banking/financial/authenticator app; never invent
 * screen coordinates or bounds (targets are text queries the
 * executor re-resolves from the live screen); at most 20 steps;
 * searchGoal is "show_results" for a plain search and "open_first"
 * only when the user asked to open/play the first result.
 */
object PlanJsonPrompt {
    /** The 21 step kinds, in the exact spelling the validator accepts. */
    val KIND_NAMES: List<String> = listOf(
        "LAUNCH_APP", "WAIT_FOR_APP", "OBSERVE_SCREEN", "SEARCH_IN_APP",
        "TAP_TARGET", "TYPE_TEXT", "SUBMIT", "SCROLL", "GO_BACK",
        "NAVIGATE_TAB",
        "VERIFY_OUTCOME", "GUIDE_TO", "MESSAGE_SEND", "CALL_PLACE",
        "POINTER_MOVE", "REPORT", "LONG_PRESS", "SWIPE", "FOCUS_FIELD",
        "OPEN_MENU", "PLAY_PAUSE",
    )

    val text: String = """
        You are LOOM's planner. Break the user's multi-step task into a short sequence of steps and reply with ONLY one JSON object, no prose, no code fences:
        {"steps": [{"kind": "LAUNCH_APP", "description": "Open YouTube Music", "params": {"app": "YouTube Music"}, "critical": true}]}
        Step kinds (use exactly these spellings): ${KIND_NAMES.joinToString(", ")}.
        Params per kind — use only these keys, and include the required ones:
        - LAUNCH_APP: app (required), package
        - WAIT_FOR_APP: app (required), package, timeoutMs (per-step deadline in ms)
        - OBSERVE_SCREEN: app, package
        - SEARCH_IN_APP: query (required), app, package, submitOrder, searchGoal, timeoutMs (per-step deadline in ms)
        - TAP_TARGET: target (required), match, package, reresolve (true to re-resolve the target from the live screen before tapping) (never bounds — targets are text, never coordinates)
        - TYPE_TEXT: text (required), target
        - SUBMIT: target, keyboardAction (submit, confirm, dismiss, or newline — dismiss closes the keyboard without submitting)
        - SCROLL: direction, target (direction is up, down, left, or right)
        - GO_BACK: no params
        - NAVIGATE_TAB: target (required), match, package, reresolve (true to re-resolve the destination from the live screen before tapping) (never bounds — targets are text, never coordinates)
        - VERIFY_OUTCOME: expected, target, package
        - GUIDE_TO: guideTarget (required)
        - MESSAGE_SEND: recipient (required), message (required), channel (required: whatsapp or sms)
        - CALL_PLACE: recipient (required)
        - POINTER_MOVE: direction (required: left, right, up, or down)
        - REPORT: text, expected
        - LONG_PRESS: target (required), match, package (never bounds)
        - SWIPE: direction (required: up, down, left, or right), target, package
        - FOCUS_FIELD: target (required), package (never bounds)
        - OPEN_MENU: target, package
        - PLAY_PAUSE: target, playState (play or pause — absent toggles to the opposite of the current player state), package
        searchGoal is "show_results" when the user only wants to see results, and "open_first" only when they asked to open or play the first result.
        HARD RULES:
        - LAUNCH FIRST: every plan that acts inside a named app MUST begin with LAUNCH_APP + WAIT_FOR_APP for that app, before any SEARCH_IN_APP, TAP_TARGET, TYPE_TEXT, SUBMIT, SCROLL, NAVIGATE_TAB, LONG_PRESS, SWIPE, FOCUS_FIELD, or OPEN_MENU step in it. Never search, tap, or type in an app the plan has not opened. Include the app and package params on every step that acts inside the app. (This is a prompt instruction only — the planner and executor enforce the launch order as code.)
        - Never name a banking, financial, or authenticator app in any step. If the task needs one, use GUIDE_TO instead.
        - Never invent screen coordinates, bounds, or element positions. Targets are text the app re-resolves from the live screen.
        - Never send a message to more than one person, and never include a password, one-time code, or payment detail.
        - At most 20 steps. Keep each description under 500 characters and each param value short.
        - If you cannot break the task into these steps, reply with {"steps": []} and the app will plan it another way.
    """.trimIndent()
}

object CapabilityBridge {

    /**
     * Master §2 (item 5): the Worker 5 AssistantRegion enum names, as
     * the model must spell them in an ASSISTANT_MOVE block. Kept here
     * (not in platform:services) so the pure-Kotlin parser can validate
     * a model-proposed region without an Android dependency.
     */
    val ASSISTANT_REGION_NAMES: Set<String> = setOf(
        "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT",
        "LEFT_EDGE", "RIGHT_EDGE", "UP", "DOWN", "LEFT", "RIGHT",
        "OUT_OF_WAY",
    )

    private val ACTION_BLOCK = Regex(
        """\[\[LOOM_ACTION:([A-Z_]+)(?:\s+arg="([^"]*)")?(?:\s+app="([^"]*)")?(?:\s+recipient="([^"]*)")?\]\]""",
    )
    private val ANY_ACTION_BLOCK = Regex("""\[\[LOOM_ACTION:[^\]]*\]\]""")

    /**
     * Parse the first executable action from a model reply.
     * ALLOWLIST ONLY: pointer, guide, demo, launch, go-to, search,
     * scroll/back/tap/submit, assistant-move, send-message, place-call
     * and plan-task actions. A block naming SHUTDOWN, DEACTIVATE_ASSISTANT_MODE, HIDE_YOURSELF or
     * any other lifecycle command, an unknown name, a malformed block
     * (a GUIDE_TO / DEMO_SEQUENCE / LAUNCH_APP / GO_TO_APP /
     * SEARCH_IN_APP / TAP_TARGET without a non-blank arg, a SCROLL
     * whose arg is not up/down, an ASSISTANT_MOVE whose arg is not a
     * known region, a POINTER_MOVE whose arg is not a direction, a
     * SEND_WHATSAPP / SEND_SMS without a non-blank arg AND a
     * non-blank single-person recipient, a PLACE_CALL without a
     * non-blank arg) yields nothing for that block; when no block is
     * executable the result is null, never an executed lifecycle
     * action. A bulk recipient ("everyone") is refused here — the
     * model can never request a bulk send through an action block.
     */
    fun parseModelAction(reply: String): ModelAction? {
        for (m in ACTION_BLOCK.findAll(reply)) {
            val name = m.groupValues[1]
            val arg = m.groupValues.getOrNull(2) ?: ""
            val app = m.groupValues.getOrNull(3) ?: ""
            val recipient = m.groupValues.getOrNull(4) ?: ""
            val capability: LoomCapability? = when (name) {
                "POINTER_SHOW" -> LoomCapability.PointerShow
                "POINTER_HIDE" -> LoomCapability.PointerHide
                "POINTER_AUTO" -> LoomCapability.PointerAuto
                "POINTER_MOVE" -> try {
                    LoomCapability.PointerMove(PointerDirection.valueOf(arg))
                } catch (_: IllegalArgumentException) {
                    null
                }
                "GUIDE_TO" -> if (arg.isNotBlank()) LoomCapability.GuideTo(arg) else null
                "GUIDE_NEXT" -> LoomCapability.GuideNext
                "GUIDE_BACK" -> LoomCapability.GuideBack
                "DEMO_TAP" -> LoomCapability.DemoTap
                "DEMO_SEQUENCE" -> if (arg.isNotBlank()) LoomCapability.DemoSequence(arg) else null
                "LAUNCH_APP" -> if (arg.isNotBlank()) LoomCapability.LaunchApp(arg) else null
                "GO_TO_APP" -> if (arg.isNotBlank()) LoomCapability.GoToApp(arg) else null
                // MASTER F1d: console follow-ups — strict args, same
                // shapes as the deterministic commands. A scroll with
                // no direction, or a tap with no target, yields
                // nothing (the caller clarifies instead of guessing).
                "SCROLL" -> if (arg.lowercase() in setOf("up", "down")) {
                    LoomCapability.Scroll(arg.lowercase())
                } else null
                "GO_BACK" -> LoomCapability.GoBack
                "TAP_TARGET" -> if (arg.isNotBlank()) LoomCapability.TapTarget(arg) else null
                "SUBMIT" -> LoomCapability.PressEnter
                "SEARCH_IN_APP" -> if (arg.isNotBlank()) {
                    LoomCapability.SearchInApp(arg, app)
                } else null
                // Dictated messaging + calls (2026-11-05, fix-plan item
                // 12): arg is the exact message text (send) or the
                // call target (call); recipient is one person. Both
                // required for a send; a bulk recipient yields nothing.
                "SEND_WHATSAPP" -> if (arg.isNotBlank() && recipient.isNotBlank() &&
                    !isBulkRecipient(recipient)
                ) {
                    LoomCapability.SendWhatsApp(recipient, arg)
                } else null
                "SEND_SMS" -> if (arg.isNotBlank() && recipient.isNotBlank() &&
                    !isBulkRecipient(recipient)
                ) {
                    LoomCapability.SendSms(recipient, arg)
                } else null
                "PLACE_CALL" -> if (arg.isNotBlank() && !isBulkRecipient(arg)) {
                    LoomCapability.PlaceCall(arg)
                } else null
                // Stage 2D: a model-proposed multi-step task. The arg
                // is the goal, verbatim; transparency is always false
                // here (only David's own words can ask to see the
                // steps) and the dispatch path carries MODEL origin —
                // a model-proposed plan NEVER inherits direct-user
                // authorization (RequestOrigin: grants apply to
                // DIRECT_USER only), and the per-sequence plan cover
                // still stands before anything runs.
                "PLAN_TASK" -> if (arg.isNotBlank()) LoomCapability.PlanTask(arg) else null
                "ASSISTANT_MOVE" -> if (arg in ASSISTANT_REGION_NAMES) {
                    LoomCapability.AssistantMove(arg)
                } else null
                else -> null // SHUTDOWN, DEACTIVATE_ASSISTANT_MODE,
                // HIDE_YOURSELF, unknown names: never executable.
            }
            if (capability != null) return ModelAction(capability)
        }
        return null
    }

    /**
     * Bulk-recipient check for model action blocks (mirrors the
     * executor's [isBulkRecipient] — kept local so this pure-Kotlin
     * file stays free of the execution module). A recipient naming
     * everyone / all contacts can never be requested by the model.
     */
    private fun isBulkRecipient(recipient: String): Boolean {
        val t = recipient.lowercase().trim()
        return t == "everyone" || t == "everybody" || t == "all" ||
            t == "all contacts" || t == "all my contacts" ||
            t.contains("everyone") || t.contains("everybody") ||
            t.contains("all contacts") || t.contains("all my contacts")
    }

    /**
     * Master §2 (item 2): the system context for the grounded fallback.
     * The capability prompt plus the fallback instruction, with the
     * unmatched utterance quoted so the model parses THIS turn's intent.
     * Used by both call sites that send unmatched input to the engine
     * (typed chat's generateReply, voice's handleResult) — the model
     * selects; it never executes. Lifecycle/shutdown/sensitive actions
     * stay unrepresentable: parseModelAction() refuses them.
     */
    fun fallbackSystemContext(utterance: String): String =
        CapabilityPrompt.text + "\n\n" +
            CapabilityPrompt.fallbackInstruction + "\n\n" +
            "The user's message was: \"$utterance\""

    /** Remove every LOOM_ACTION block from displayed/stored text, keeping the prose. */
    fun stripModelActions(reply: String): String =
        ANY_ACTION_BLOCK.replace(reply, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    /**
     * Resolve a typed chat line the same way voice resolves a transcript:
     * deterministic command match first, then the lifecycle clarification
     * layer, then the ambiguous-mode clarification — and only otherwise
     * the engine. A bare "shut down" (no "loom" prefix) matches SHUTDOWN
     * in the matcher (2026-11-10) and returns ExecuteCommand(SHUTDOWN)
     * here — the shutdown executes immediately, with no confirmation
     * question (David's binding rule, Nov 13 ~03:36). Fix 2
     * (2026-11-13, F1-DEFECT-1): a negated shutdown ("don't shut
     * down") never reaches the clarification layer — it falls
     * through to the engine, exactly as on the voice path.
     * Stage 2D: a typed multi-step instruction ("open YouTube Music and
     * play a song by Davido") matches PLAN_TASK in VoiceCommands.match
     * with the same conservative routing as voice, so it returns
     * ExecuteCommand(PLAN_TASK) here — no separate typed-chat matcher.
     */
    fun resolveChatInput(text: String): ChatInputResolution {
        VoiceCommands.match(text)?.let { return ChatInputResolution.ExecuteCommand(it) }
        if (!VoiceCommands.isNegatedShutdown(text)) {
            VoiceCommands.clarificationFor(text)?.let { return ChatInputResolution.Clarification(it) }
        }
        if (VoiceCommands.matchAmbiguous(text)) {
            return ChatInputResolution.Clarification(
                "Which mode — Orb, Character, or Invisible?",
            )
        }
        return ChatInputResolution.SendToEngine
    }
}
