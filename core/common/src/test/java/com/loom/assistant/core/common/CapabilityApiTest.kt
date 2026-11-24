package com.loom.assistant.core.common

/**
 * Capability-API fix — pure-JVM checks for CapabilityBridge, CapabilityPrompt
 * and the VoiceCommands shutdown matching (2026-11-10: plain "shut down"
 * matches SHUTDOWN; since 2026-11-13 the shutdown is immediate, with no
 * confirmation question).
 * Run: kotlinc VoiceCommands.kt CapabilityApi.kt CapabilityApiTest.kt -d out
 *   && java -cp out com.loom.assistant.core.common.CapabilityApiTestKt
 * (No android.* imports, so no fakes are needed.)
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun checkTrue(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL(bool): $name — expected true, got false")
    }
}

fun main() {
    // ---- resolveChatInput: command routing before any engine call ----
    val pointer = CapabilityBridge.resolveChatInput("show the pointer")
    checkTrue(
        "show the pointer -> ExecuteCommand(POINTER_SHOW)",
        pointer is ChatInputResolution.ExecuteCommand &&
            (pointer as ChatInputResolution.ExecuteCommand).cmd == VoiceCommand.POINTER_SHOW,
    )
    val shutdown = CapabilityBridge.resolveChatInput("loom, shut down")
    checkTrue(
        "loom, shut down -> ExecuteCommand(SHUTDOWN)",
        shutdown is ChatInputResolution.ExecuteCommand &&
            (shutdown as ChatInputResolution.ExecuteCommand).cmd == VoiceCommand.SHUTDOWN,
    )
    // Shutdown recognition (2026-11-10, David's rule): plain "shut
    // down" and its variants match SHUTDOWN — no "loom" prefix
    // required. They reach ExecuteCommand(SHUTDOWN) and execute
    // immediately, with no confirmation question (Nov 13 binding
    // rule).
    val doubled = CapabilityBridge.resolveChatInput("shut down shut down")
    checkTrue(
        "shut down shut down -> ExecuteCommand(SHUTDOWN)",
        doubled is ChatInputResolution.ExecuteCommand &&
            (doubled as ChatInputResolution.ExecuteCommand).cmd == VoiceCommand.SHUTDOWN,
    )
    val bare = CapabilityBridge.resolveChatInput("shut down")
    checkTrue(
        "shut down -> ExecuteCommand(SHUTDOWN)",
        bare is ChatInputResolution.ExecuteCommand &&
            (bare as ChatInputResolution.ExecuteCommand).cmd == VoiceCommand.SHUTDOWN,
    )
    val switchIt = CapabilityBridge.resolveChatInput("switch it")
    checkTrue(
        "switch it -> Clarification",
        switchIt is ChatInputResolution.Clarification &&
            (switchIt as ChatInputResolution.Clarification).text ==
            "Which mode — Orb, Character, or Invisible?",
    )
    checkTrue(
        "ordinary question -> SendToEngine",
        CapabilityBridge.resolveChatInput("what is the capital of France?") ==
            ChatInputResolution.SendToEngine,
    )
    checkTrue(
        "guide me to -> ExecuteCommand(GUIDE_TO)",
        (CapabilityBridge.resolveChatInput("guide me to Settings")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GUIDE_TO,
    )
    checkTrue(
        "show me how to -> ExecuteCommand(DEMO_SEQUENCE)",
        (CapabilityBridge.resolveChatInput("show me how to turn on dark mode")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.DEMO_SEQUENCE,
    )
    // Voice parity (2026-11-10): plain "shut down" and its variants
    // now match SHUTDOWN, so they clarify no longer — each is a
    // command, not a clarification. The shutdown executes
    // immediately, with no confirmation question (Nov 13 rule).
    check(
        "clarificationFor(shut down shut down) is null (a command now)",
        VoiceCommands.clarificationFor("shut down shut down"),
        null,
    )
    check(
        "clarificationFor(shut down) is null (a command now)",
        VoiceCommands.clarificationFor("shut down"),
        null,
    )
    check(
        "shut it down is a command, not clarification",
        VoiceCommands.clarificationFor("please shut it down"),
        null,
    )
    check(
        "matched shutdown is a command, not clarification",
        VoiceCommands.clarificationFor("loom, shut down"),
        null,
    )
    check(
        "shut down voice stays END_VOICE, not clarification",
        VoiceCommands.clarificationFor("shut down voice"),
        null,
    )
    // Fix 2 (2026-11-13, F1-DEFECT-1): the typed path. A negated
    // shutdown never reaches the clarification layer — it goes to
    // the engine. A long shutdown utterance executes immediately.
    // Only the pointer form still clarifies.
    check(
        "clarificationFor(don't shut down) is null (negation intercept)",
        VoiceCommands.clarificationFor("don't shut down"),
        null,
    )
    check(
        "clarificationFor(don't shut down the pointer) is null (negation intercept)",
        VoiceCommands.clarificationFor("don't shut down the pointer"),
        null,
    )
    checkTrue(
        "don't shut down -> SendToEngine (typed negation intercept)",
        CapabilityBridge.resolveChatInput("don't shut down") is ChatInputResolution.SendToEngine,
    )
    checkTrue(
        "long shutdown utterance -> ExecuteCommand(SHUTDOWN) (typed)",
        (CapabilityBridge.resolveChatInput(
            "loom please shut down completely now I am finished using you for today thank you",
        ) as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.SHUTDOWN,
    )
    checkTrue(
        "shut down the pointer -> Clarification (pointer case only)",
        CapabilityBridge.resolveChatInput("shut down the pointer") is ChatInputResolution.Clarification,
    )

    // ---- Device-evidence fix (2026-11-05): chat routing for the
    // two new required cases — mode fragments and launch requests ----
    checkTrue(
        "to invisible mode -> ExecuteCommand(MODE_INVISIBLE)",
        (CapabilityBridge.resolveChatInput("to invisible mode")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.MODE_INVISIBLE,
    )
    checkTrue(
        "invisible mode -> ExecuteCommand(MODE_INVISIBLE)",
        (CapabilityBridge.resolveChatInput("invisible mode")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.MODE_INVISIBLE,
    )
    checkTrue(
        "what is invisible mode? -> SendToEngine (not a command)",
        CapabilityBridge.resolveChatInput("what is invisible mode?") ==
            ChatInputResolution.SendToEngine,
    )
    checkTrue(
        "can you open my Youtube -> ExecuteCommand(LAUNCH_APP)",
        (CapabilityBridge.resolveChatInput("can you open my Youtube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.LAUNCH_APP,
    )
    checkTrue(
        "open youtube -> ExecuteCommand(LAUNCH_APP)",
        (CapabilityBridge.resolveChatInput("open youtube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.LAUNCH_APP,
    )
    checkTrue(
        "open alone -> Clarification (which app)",
        (CapabilityBridge.resolveChatInput("open")
            as? ChatInputResolution.Clarification)?.text ==
            "Which app do you want me to open?",
    )
    checkTrue(
        "I'm telling you to go silent -> Clarification (not engine)",
        CapabilityBridge.resolveChatInput("I'm telling you to go silent")
            is ChatInputResolution.Clarification,
    )
    checkTrue(
        "go silent -> ExecuteCommand(GO_SILENT)",
        (CapabilityBridge.resolveChatInput("go silent")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GO_SILENT,
    )
    // ---- "fix everything" (2026-11-05): exact transcripts ----
    checkTrue(
        "open my Youtube (5:44) -> ExecuteCommand(LAUNCH_APP)",
        (CapabilityBridge.resolveChatInput("open my Youtube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.LAUNCH_APP,
    )
    checkTrue(
        "open MovieBox -> ExecuteCommand(LAUNCH_APP)",
        (CapabilityBridge.resolveChatInput("open MovieBox")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.LAUNCH_APP,
    )
    checkTrue(
        "pointer+guide (5:37) -> ExecuteCommand(GUIDE_TO)",
        (CapabilityBridge.resolveChatInput("I need you to use your pointer to guide me")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GUIDE_TO,
    )
    checkTrue(
        "guide me through -> ExecuteCommand(GUIDE_TO)",
        (CapabilityBridge.resolveChatInput("guide me through the settings")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GUIDE_TO,
    )
    checkTrue(
        "search for Spider-Man -> ExecuteCommand(SEARCH_IN_APP)",
        (CapabilityBridge.resolveChatInput("search for Spider-Man")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.SEARCH_IN_APP,
    )
    checkTrue(
        "search for Spider-Man in YouTube -> ExecuteCommand(SEARCH_IN_APP)",
        (CapabilityBridge.resolveChatInput("search for Spider-Man in YouTube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.SEARCH_IN_APP,
    )
    checkTrue(
        "bare search -> Clarification (what to search for)",
        (CapabilityBridge.resolveChatInput("search")
            as? ChatInputResolution.Clarification)?.text ==
            "What do you want me to search for?",
    )
    checkTrue(
        "move the pointer left -> ExecuteCommand(POINTER_MOVE)",
        (CapabilityBridge.resolveChatInput("move the pointer left")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.POINTER_MOVE,
    )
    checkTrue(
        "move sideways -> Clarification",
        (CapabilityBridge.resolveChatInput("move sideways")
            as? ChatInputResolution.Clarification)?.text ==
            "Which way should I move the pointer — left, right, up, or down?",
    )

    // ---- parseModelAction: allowlist only ----
    val guide = CapabilityBridge.parseModelAction(
        "Here you go. [[LOOM_ACTION:GUIDE_TO arg=\"open Settings\"]]",
    )
    checkTrue(
        "valid GUIDE_TO parses with target",
        guide?.command == VoiceCommand.GUIDE_TO &&
            (guide?.capability as? LoomCapability.GuideTo)?.target == "open Settings",
    )
    val demo = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:DEMO_SEQUENCE arg=\"turn on dark mode\"]] Setting it up.",
    )
    checkTrue(
        "valid DEMO_SEQUENCE parses with goal",
        demo?.command == VoiceCommand.DEMO_SEQUENCE &&
            (demo?.capability as? LoomCapability.DemoSequence)?.goal == "turn on dark mode",
    )
    check(
        "valid POINTER_SHOW parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:POINTER_SHOW]]")?.command,
        VoiceCommand.POINTER_SHOW,
    )
    check(
        "valid POINTER_HIDE parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:POINTER_HIDE]]")?.command,
        VoiceCommand.POINTER_HIDE,
    )
    check(
        "valid POINTER_AUTO parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:POINTER_AUTO]]")?.command,
        VoiceCommand.POINTER_AUTO,
    )
    check(
        "valid GUIDE_NEXT parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GUIDE_NEXT]]")?.command,
        VoiceCommand.GUIDE_NEXT,
    )
    check(
        "valid DEMO_TAP parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:DEMO_TAP]]")?.command,
        VoiceCommand.DEMO_TAP,
    )
    // Device-evidence fix (2026-11-05): launch is allowlisted (the
    // AuthGate CONFIRM stands between a model request and any open).
    check(
        "valid LAUNCH_APP parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:LAUNCH_APP arg=\"YouTube\"]]")?.command,
        VoiceCommand.LAUNCH_APP,
    )
    check(
        "LAUNCH_APP carries the app name",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:LAUNCH_APP arg=\"YouTube\"]]")?.argument,
        "YouTube",
    )
    check(
        "LAUNCH_APP without arg -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:LAUNCH_APP]]"),
        null,
    )
    check(
        "SHUTDOWN block -> null, never executed",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SHUTDOWN]]"),
        null,
    )
    check(
        "DEACTIVATE_ASSISTANT_MODE block -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:DEACTIVATE_ASSISTANT_MODE]]"),
        null,
    )
    check(
        "HIDE_YOURSELF block -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:HIDE_YOURSELF]]"),
        null,
    )
    check(
        "unknown action -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:DELETE_EVERYTHING]]"),
        null,
    )
    check(
        "malformed (unquoted arg) -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GUIDE_TO arg=open Settings]]"),
        null,
    )
    check(
        "malformed (GUIDE_TO without arg) -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GUIDE_TO]]"),
        null,
    )
    check(
        "malformed (DEMO_SEQUENCE blank arg) -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:DEMO_SEQUENCE arg=\"\"]]"),
        null,
    )
    check(
        "plain prose without blocks -> null",
        CapabilityBridge.parseModelAction("Sure, here is how to do it step by step."),
        null,
    )
    checkTrue(
        "blocked block skipped, later valid block parses",
        CapabilityBridge.parseModelAction(
            "[[LOOM_ACTION:SHUTDOWN]] [[LOOM_ACTION:POINTER_SHOW]]",
        )?.command == VoiceCommand.POINTER_SHOW,
    )

    // ---- stripModelActions ----
    check(
        "strip removes block, keeps prose",
        CapabilityBridge.stripModelActions(
            "Done. [[LOOM_ACTION:POINTER_SHOW]] The pointer is on.",
        ),
        "Done. The pointer is on.",
    )
    check(
        "strip removes multiple blocks",
        CapabilityBridge.stripModelActions(
            "[[LOOM_ACTION:GUIDE_NEXT]] Next step [[LOOM_ACTION:DEMO_TAP]]",
        ),
        "Next step",
    )
    check(
        "strip leaves plain prose untouched",
        CapabilityBridge.stripModelActions("Just a normal reply."),
        "Just a normal reply.",
    )

    // ---- CapabilityPrompt content ----
    val prompt = CapabilityPrompt.text
    checkTrue("prompt names LOOM", prompt.contains("LOOM"))
    checkTrue("prompt names Android assistant", prompt.contains("Android assistant"))
    checkTrue("prompt lists pointer", prompt.contains("Pointer"))
    checkTrue("prompt lists guidance", prompt.contains("guidance"))
    checkTrue("prompt lists demonstration", prompt.contains("Demonstration"))
    checkTrue("prompt has routing rule (action block + sentence)", prompt.contains("[[LOOM_ACTION:"))
    checkTrue(
        "prompt forbids prose steps instead of a capability",
        prompt.contains("do NOT answer with prose steps instead of the capability"),
    )
    checkTrue(
        "prompt has never-deny rule",
        prompt.contains("Never deny a capability that exists"),
    )
    checkTrue(
        "prompt has never-claim-performed rule",
        prompt.contains("Never claim an action was performed"),
    )
    checkTrue(
        "prompt has never-claim-offline rule",
        prompt.contains("Never claim you shut down, went offline"),
    )
    checkTrue(
        "prompt has sensitive-apps guidance-only rule",
        prompt.contains("guidance-only"),
    )
    checkTrue(
        "prompt has shutdown rule (immediate, no confirmation question)",
        prompt.contains("\"shut down\"") && prompt.contains("no confirmation"),
    )
    checkTrue(
        "prompt never instructs shutdown confirmation",
        !prompt.contains("confirms when asked") &&
            !prompt.contains("without that confirmation") &&
            !prompt.contains("plus confirmation where required"),
    )
    checkTrue(
        "prompt lists LAUNCH_APP as a valid action",
        prompt.contains("LAUNCH_APP"),
    )
    checkTrue(
        "prompt lists opening apps as a capability",
        prompt.contains("Opening apps"),
    )
    checkTrue(
        "prompt has only-confirmed-results rule",
        prompt.contains("ONLY results the app has confirmed"),
    )
    checkTrue(
        "prompt has never-narrate rule",
        prompt.contains("Never narrate an action as done"),
    )
    checkTrue(
        "prompt lists search as a capability",
        prompt.contains("Searching inside apps"),
    )
    checkTrue(
        "prompt lists assistant movement",
        prompt.contains("Moving the assistant itself"),
    )
    checkTrue(
        "prompt lists dictated messages and calls",
        prompt.contains("Dictated messages and calls"),
    )
    checkTrue(
        "prompt lists SEND_WHATSAPP as a valid action",
        prompt.contains("SEND_WHATSAPP"),
    )
    checkTrue(
        "prompt lists SEND_SMS as a valid action",
        prompt.contains("SEND_SMS"),
    )
    checkTrue(
        "prompt lists PLACE_CALL as a valid action",
        prompt.contains("PLACE_CALL"),
    )
    checkTrue(
        "prompt says never send bulk messages",
        prompt.contains("never send bulk messages"),
    )

    // ---- Dictated messaging + calls (2026-11-05, fix-plan item 12):
    // parseModelAction allowlist entries ----
    val wa = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:SEND_WHATSAPP arg=\"hello there\" recipient=\"Mum\"]] I'll send that.",
    )
    checkTrue(
        "SEND_WHATSAPP block parses",
        wa != null && (wa.capability as? LoomCapability.SendWhatsApp)?.recipient == "Mum" &&
            (wa.capability as LoomCapability.SendWhatsApp).text == "hello there",
    )
    checkTrue(
        "SEND_WHATSAPP maps to SEND_WHATSAPP_MESSAGE",
        wa?.command == VoiceCommand.SEND_WHATSAPP_MESSAGE,
    )
    val sms = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:SEND_SMS arg=\"on my way\" recipient=\"Dad\"]] Sending.",
    )
    checkTrue(
        "SEND_SMS block parses",
        sms != null && (sms.capability as? LoomCapability.SendSms)?.recipient == "Dad" &&
            (sms.capability as LoomCapability.SendSms).text == "on my way",
    )
    val call = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:PLACE_CALL arg=\"Mum\"]] Calling.",
    )
    checkTrue(
        "PLACE_CALL block parses",
        call != null && (call.capability as? LoomCapability.PlaceCall)?.target == "Mum",
    )
    checkTrue(
        "PLACE_CALL maps to PLACE_CALL",
        call?.command == VoiceCommand.PLACE_CALL,
    )
    check(
        "SEND_WHATSAPP without a recipient yields nothing",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SEND_WHATSAPP arg=\"hi\"]]"),
        null,
    )
    check(
        "SEND_WHATSAPP without text yields nothing",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SEND_WHATSAPP recipient=\"Mum\"]]"),
        null,
    )
    check(
        "SEND_SMS without a recipient yields nothing",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SEND_SMS arg=\"hi\"]]"),
        null,
    )
    check(
        "PLACE_CALL without a target yields nothing",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:PLACE_CALL]]"),
        null,
    )
    check(
        "bulk recipient in a model block yields nothing",
        CapabilityBridge.parseModelAction(
            "[[LOOM_ACTION:SEND_WHATSAPP arg=\"hi\" recipient=\"everyone\"]]",
        ),
        null,
    )
    check(
        "bulk call target in a model block yields nothing",
        CapabilityBridge.parseModelAction(
            "[[LOOM_ACTION:PLACE_CALL arg=\"everyone\"]]",
        ),
        null,
    )
    checkTrue(
        "typed send whatsapp resolves to ExecuteCommand, not the engine",
        (CapabilityBridge.resolveChatInput("send WhatsApp to Mum saying hi")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.SEND_WHATSAPP_MESSAGE,
    )
    checkTrue(
        "typed call resolves to ExecuteCommand, not the engine",
        (CapabilityBridge.resolveChatInput("call Mum")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.PLACE_CALL,
    )
    checkTrue(
        "typed bulk send resolves to the bulk Clarification, not the engine",
        (CapabilityBridge.resolveChatInput("send WhatsApp to everyone saying hi")
            as? ChatInputResolution.Clarification)?.text ==
            "I don't send bulk messages — tell me one person at a time.",
    )
    checkTrue(
        "first executable block wins: a refused bulk block falls through to the next",
        CapabilityBridge.parseModelAction(
            "[[LOOM_ACTION:SEND_WHATSAPP arg=\"hi\" recipient=\"everyone\"]] " +
                "[[LOOM_ACTION:PLACE_CALL arg=\"Mum\"]]",
        )?.command == VoiceCommand.PLACE_CALL,
    )
    checkTrue(
        "prompt lists SEARCH_IN_APP as a valid action",
        prompt.contains("SEARCH_IN_APP"),
    )
    checkTrue(
        "prompt lists ASSISTANT_MOVE as a valid action",
        prompt.contains("ASSISTANT_MOVE"),
    )
    checkTrue(
        "prompt lists pointer movement",
        prompt.contains("Pointer movement during guidance"),
    )

    // ---- Accessibility fix (2026-11-05, audit §7.1/§7.2): transcript
    // replay — the exact Nov 5 20:12 typed exchange. The matcher
    // widening (audit step 1, parallel worker) now routes the turn to
    // POINTER_SHOW instead of the engine; the engine path below is
    // what the OLD prompt produced, and the gate is what the app must
    // answer with either way. The confirmation — the only message the
    // user gets for the turn — is the gate's ack from the VERIFIED
    // render result: a plain confirmation on a render, one plain
    // sentence on a failure. Never "I've requested" with nothing
    // rendered, never "will now stay visible", never a jargon word.
    val forbiddenAckWords = listOf(
        "I've requested", "I've started", "will now stay visible",
        "attached", "detached", "anchored", "overlay", "503", "done",
    )
    val replayInput = "okay help me find my way then"
    checkTrue(
        "replay: transcript turn routes to ExecuteCommand(POINTER_SHOW)",
        (CapabilityBridge.resolveChatInput(replayInput)
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.POINTER_SHOW,
    )
    // What the old prompt made the model emit for this turn (the
    // transcript's exact failure): an action block + the "I've
    // requested" sentence. The block still parses to POINTER_SHOW —
    // the parse is unchanged — but the sentence must never become the
    // confirmation.
    val oldModelReply =
        "[[LOOM_ACTION:POINTER_SHOW]] I've requested to show the on-screen pointer to help you navigate."
    checkTrue(
        "replay: old model reply still parses to POINTER_SHOW",
        CapabilityBridge.parseModelAction(oldModelReply)?.command == VoiceCommand.POINTER_SHOW,
    )
    checkTrue(
        "replay: stripped prose still carries the old claim (the input the gate must neutralise)",
        CapabilityBridge.stripModelActions(oldModelReply).contains("I've requested"),
    )
    // The gate, render FAILED (floating assistant never started — the
    // transcript case): one plain sentence, no forbidden word.
    val failedAck = PointerShowGate.ack(rendered = false)
    check(
        "replay: failed render → exact plain failure sentence",
        failedAck,
        "The pointer isn't on screen right now — LOOM needs to be " +
            "turned on in Settings, under Accessibility.",
    )
    checkTrue(
        "replay: failed ack is one sentence",
        failedAck.count { it == '.' } == 1,
    )
    checkTrue(
        "replay: failed ack carries no forbidden wording",
        forbiddenAckWords.none { failedAck.contains(it, ignoreCase = true) },
    )
    // The gate, render VERIFIED: plain confirmation, no forbidden word.
    val renderedAck = PointerShowGate.ack(rendered = true)
    checkTrue(
        "replay: verified render → plain confirmation, pointer visible",
        renderedAck.contains("you can see it on screen"),
    )
    checkTrue(
        "replay: verified ack carries no forbidden wording",
        forbiddenAckWords.none { renderedAck.contains(it, ignoreCase = true) },
    )
    // The prompt itself must no longer tell the model to narrate the
    // request — that instruction IS the source of "I've requested".
    checkTrue(
        "prompt no longer says 'say what you requested'",
        !prompt.contains("say what you requested"),
    )
    checkTrue(
        "prompt says nothing about the action; the app confirms",
        prompt.contains("say nothing about the action"),
    )
    checkTrue(
        "prompt forbids 'I've requested'",
        prompt.contains("no 'I've requested'"),
    )
    checkTrue(
        "prompt bans the jargon words for the pointer",
        prompt.contains("never use the words attached, detached, anchored"),
    )
    checkTrue(
        "fallback no longer asks for a sentence saying what was requested",
        !CapabilityPrompt.fallbackInstruction.contains("saying what you requested"),
    )
    checkTrue(
        "fallback says nothing about the action",
        CapabilityPrompt.fallbackInstruction.contains("say nothing about what you requested"),
    )
    // The gate's own strings carry no jargon either.
    checkTrue(
        "gate strings carry no jargon words",
        listOf(PointerShowGate.RENDERED_ACK, PointerShowGate.NOT_RENDERED_ACK)
            .none { s ->
                listOf("attached", "detached", "anchored", "guidance window")
                    .any { s.contains(it, ignoreCase = true) }
            },
    )

    // Stage 2D (Worker C): typed multi-step input routes to PLAN_TASK
    // with the same conservative routing as voice; single actions stay.
    checkTrue(
        "typed Davido sentence -> ExecuteCommand(PLAN_TASK)",
        (CapabilityBridge.resolveChatInput("open YouTube Music and play a song by Davido")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.PLAN_TASK,
    )
    checkTrue(
        "typed guide-through-process -> ExecuteCommand(PLAN_TASK)",
        (CapabilityBridge.resolveChatInput("guide me through turning on dark mode")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.PLAN_TASK,
    )
    checkTrue(
        "typed search + open -> ExecuteCommand(PLAN_TASK)",
        (CapabilityBridge.resolveChatInput("search for Spider-Man in YouTube and open the first result")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.PLAN_TASK,
    )
    checkTrue(
        "typed open YouTube stays LAUNCH_APP",
        (CapabilityBridge.resolveChatInput("open YouTube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.LAUNCH_APP,
    )
    checkTrue(
        "typed guide me to Settings stays GUIDE_TO",
        (CapabilityBridge.resolveChatInput("guide me to Settings")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GUIDE_TO,
    )
    // Stage 2D: the model may propose a plan (MODEL origin downstream);
    // a blank goal parses to nothing; lifecycle actions can never come
    // through the model path — shutdown least of all.
    val planAction = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:PLAN_TASK arg=\"open YouTube Music and play a song by Davido\"]]",
    )
    checkTrue(
        "PLAN_TASK block parses to PlanTask with the verbatim goal",
        planAction?.command == VoiceCommand.PLAN_TASK &&
            (planAction?.capability as? LoomCapability.PlanTask)?.goal ==
            "open YouTube Music and play a song by Davido",
    )
    checkTrue(
        "model-proposed plan is never transparent-by-request",
        (planAction?.capability as? LoomCapability.PlanTask)?.transparency == false,
    )
    check(
        "PLAN_TASK block with blank arg -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:PLAN_TASK arg=\"\"]]"),
        null,
    )
    check(
        "SHUTDOWN block -> null, never executed (model path)",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SHUTDOWN]]"),
        null,
    )
    check(
        "DEACTIVATE_ASSISTANT_MODE block -> null (model path)",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:DEACTIVATE_ASSISTANT_MODE]]"),
        null,
    )
    checkTrue(
        "prompt lists PLAN_TASK among the allowlisted actions",
        CapabilityPrompt.text.contains("PLAN_TASK"),
    )
    checkTrue(
        "prompt states the plan honesty rule (no narrated Opening/Done)",
        CapabilityPrompt.text.contains("never narrate \"Opening…\""),
    )
    checkTrue(
        "fallback context lists PLAN_TASK",
        CapabilityBridge.fallbackSystemContext("open YouTube Music and play a song by Davido")
            .contains("PLAN_TASK"),
    )

    // ---- MASTER F1b/F1d: typed-chat parity for the new voice
    // commands — they route deterministically, never to the engine,
    // and the mode question never fires for a go-to. ----
    checkTrue(
        "go to YouTube -> ExecuteCommand(GO_TO_APP)",
        (CapabilityBridge.resolveChatInput("go to YouTube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GO_TO_APP,
    )
    checkTrue(
        "go to Google Play Store -> ExecuteCommand, never the mode question",
        CapabilityBridge.resolveChatInput("go to Google Play Store") is
            ChatInputResolution.ExecuteCommand,
    )
    checkTrue(
        "take me to YouTube -> ExecuteCommand(GO_TO_APP)",
        (CapabilityBridge.resolveChatInput("take me to YouTube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GO_TO_APP,
    )
    checkTrue(
        "scroll down -> ExecuteCommand(SCROLL)",
        (CapabilityBridge.resolveChatInput("scroll down")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.SCROLL,
    )
    checkTrue(
        "go back -> ExecuteCommand(BACK)",
        (CapabilityBridge.resolveChatInput("go back")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.BACK,
    )
    checkTrue(
        "tap the first result -> ExecuteCommand(TAP)",
        (CapabilityBridge.resolveChatInput("tap the first result")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.TAP,
    )
    checkTrue(
        "press enter -> ExecuteCommand(ENTER)",
        (CapabilityBridge.resolveChatInput("press enter")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.ENTER,
    )
    checkTrue(
        "click on enter -> ExecuteCommand(ENTER)",
        (CapabilityBridge.resolveChatInput("click on enter")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.ENTER,
    )

    // ---- MASTER F1b/F1d: parseModelAction allowlist — the model can
    // now propose the follow-ups instead of re-proposing a search. ----
    check(
        "valid GO_TO_APP parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GO_TO_APP arg=\"YouTube\"]]")?.command,
        VoiceCommand.GO_TO_APP,
    )
    check(
        "GO_TO_APP carries the app name",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GO_TO_APP arg=\"YouTube\"]]")?.argument,
        "YouTube",
    )
    check(
        "GO_TO_APP without arg -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GO_TO_APP]]"),
        null,
    )
    check(
        "valid SCROLL down parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SCROLL arg=\"down\"]]")?.command,
        VoiceCommand.SCROLL,
    )
    check(
        "SCROLL carries the direction",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SCROLL arg=\"down\"]]")?.argument,
        "down",
    )
    check(
        "SCROLL up parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SCROLL arg=\"up\"]]")?.command,
        VoiceCommand.SCROLL,
    )
    check(
        "SCROLL without arg -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SCROLL]]"),
        null,
    )
    check(
        "SCROLL with a bad direction -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SCROLL arg=\"sideways\"]]"),
        null,
    )
    check(
        "valid GO_BACK parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GO_BACK]]")?.command,
        VoiceCommand.BACK,
    )
    check(
        "valid TAP_TARGET parses",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:TAP_TARGET arg=\"the first result\"]]")?.command,
        VoiceCommand.TAP,
    )
    check(
        "TAP_TARGET carries the target",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:TAP_TARGET arg=\"the first result\"]]")?.argument,
        "the first result",
    )
    check(
        "TAP_TARGET without arg -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:TAP_TARGET]]"),
        null,
    )
    check(
        "valid SUBMIT parses as ENTER",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SUBMIT]]")?.command,
        VoiceCommand.ENTER,
    )
    checkTrue(
        "prompt lists GO_TO_APP among the allowlisted actions",
        CapabilityPrompt.text.contains("GO_TO_APP"),
    )
    checkTrue(
        "prompt lists SCROLL among the allowlisted actions",
        CapabilityPrompt.text.contains("SCROLL"),
    )

    println("CapabilityApiTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
