package com.loom.assistant.core.common

/**
 * Master §2 (Speech understanding + command surface) — pure-JVM checks.
 * Run: kotlinc VoiceCommands.kt PointerMove.kt ConversationContext.kt
 *   CapabilityApi.kt SpeechUnderstandingTest.kt -d out
 *   && java -cp out com.loom.assistant.core.common.SpeechUnderstandingTestKt
 * (No android.* imports, so no fakes are needed.)
 *
 * Coverage: paraphrase tolerance (item 1), STT-error tolerance incl.
 * the ghost-silence / shut-down-low cases, ambiguous → clarification,
 * the grounded-fallback allowlist (item 2), SEARCH + ASSISTANT_MOVE
 * routing (items 4–5), app-name resolution incl. YouTube Music
 * disambiguation, and minimal conversational context (item 6).
 *
 * What this file does NOT cover (needs Worker 1/4/5 + device):
 * AppCatalogue.resolveByName itself, SearchExecutor execution, and
 * AssistantMover movement — those are seams, tested by their owners.
 * The resolution test below proves the matcher carries "YouTube Music"
 * whole into the seam, so the catalogue can disambiguate it.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: VoiceCommand?, expected: VoiceCommand?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected $expected, got $actual")
    }
}

private fun checkStr(name: String, actual: String?, expected: String?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL(str): $name — expected <$expected>, got <$actual>")
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

private fun checkClarify(name: String, text: String, expected: String?) {
    val actual = VoiceCommands.clarificationFor(text)
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL(clarify): $name — expected <$expected>, got <$actual>")
    }
}

fun main() {
    // ---- item 1: paraphrase tolerance — GUIDE_TO synonyms ----
    check("help me do -> GUIDE_TO", VoiceCommands.match("help me do the dishes"), VoiceCommand.GUIDE_TO)
    check("walk me through -> GUIDE_TO", VoiceCommands.match("walk me through the settings"), VoiceCommand.GUIDE_TO)
    check("show me the way to -> GUIDE_TO", VoiceCommands.match("show me the way to the wifi toggle"), VoiceCommand.GUIDE_TO)
    checkStr("extract help me do", VoiceCommands.extractGuideTarget("help me do the dishes"), "the dishes")
    checkStr("extract walk me through", VoiceCommands.extractGuideTarget("walk me through the settings"), "the settings")
    checkStr("extract show me the way to", VoiceCommands.extractGuideTarget("show me the way to the wifi toggle"), "the wifi toggle")
    check("guide me to still GUIDE_TO", VoiceCommands.match("guide me to the wifi toggle"), VoiceCommand.GUIDE_TO)
    check("guide me through still GUIDE_TO", VoiceCommands.match("guide me through the settings"), VoiceCommand.GUIDE_TO)

    // ---- item 1: STT-error tolerance (fuzzy match) ----
    check("ghost silence -> GO_SILENT", VoiceCommands.match("ghost silence"), VoiceCommand.GO_SILENT)
    check("loom, ghost silence -> GO_SILENT", VoiceCommands.match("loom, ghost silence"), VoiceCommand.GO_SILENT)
    check("go quite -> GO_SILENT", VoiceCommands.match("go quite"), VoiceCommand.GO_SILENT)
    check("shut down low -> SHUTDOWN", VoiceCommands.match("shut down low"), VoiceCommand.SHUTDOWN)
    check("stop listing -> STOP_LISTENING", VoiceCommands.match("stop listing"), VoiceCommand.STOP_LISTENING)
    check("speak agen -> SPEAK_AGAIN", VoiceCommands.match("speak agen"), VoiceCommand.SPEAK_AGAIN)
    check("hide yourself (exact) -> HIDE_YOURSELF", VoiceCommands.match("hide yourself"), VoiceCommand.HIDE_YOURSELF)
    check("hide yoursel -> HIDE_YOURSELF", VoiceCommands.match("hide yoursel"), VoiceCommand.HIDE_YOURSELF)
    check("go quiet please -> GO_SILENT", VoiceCommands.match("go quiet please"), VoiceCommand.GO_SILENT)
    check("loom, be quiet -> GO_SILENT", VoiceCommands.match("loom, be quiet"), VoiceCommand.GO_SILENT)

    // ---- item 1: fuzzy match must NOT overreach (2026-11-10:
    // plain "shut down" now matches SHUTDOWN outright — the
    // confirmation ask is the safety net for near-misses) ----
    check("go home is not GO_SILENT", VoiceCommands.match("go home"), null)
    check("shut down slow is SHUTDOWN", VoiceCommands.match("shut down slow"), VoiceCommand.SHUTDOWN)
    check("shut down (bare) is SHUTDOWN", VoiceCommands.match("shut down"), VoiceCommand.SHUTDOWN)
    check("shut down shut down is SHUTDOWN", VoiceCommands.match("shut down shut down"), VoiceCommand.SHUTDOWN)
    check("mid-sentence go silent is not GO_SILENT", VoiceCommands.match("I'm telling you to go silent"), null)
    check("mid-sentence be quiet is not GO_SILENT", VoiceCommands.match("be quiet for a moment, I'm thinking"), null)
    check("shut down boys is SHUTDOWN (confirmation is the net)", VoiceCommands.match("shut down boys"), VoiceCommand.SHUTDOWN)
    check("go silent over there is not GO_SILENT", VoiceCommands.match("go silent over there"), null)

    // ---- FuzzyMatch mechanism itself ----
    checkTrue("distance ghost/go is 3", FuzzyMatch.distance("ghost", "go") == 3)
    checkTrue("similarity low/loom is 0.50", FuzzyMatch.tokenSimilarity("low", "loom") == 0.5)
    checkTrue("phrase ghost silence ~ go silent", FuzzyMatch.phraseMatches("ghost silence", "go silent"))
    checkTrue("phrase go home !~ go quiet", !FuzzyMatch.phraseMatches("go home", "go quiet"))
    checkTrue("phrase token-count mismatch refuses", !FuzzyMatch.phraseMatches("please go silent now", "go silent"))
    checkTrue("loom-like token: low", FuzzyMatch.hasLoomLikeToken("shut down low"))
    checkTrue("loom-like token: slow is not", !FuzzyMatch.hasLoomLikeToken("shut down slow"))
    checkTrue("loom-like token: bare shut down has none", !FuzzyMatch.hasLoomLikeToken("shut down"))

    // ---- item 7 / clarification only when genuinely necessary ----
    checkClarify("move sideways asks direction", "move sideways", "Which way should I move the pointer — left, right, up, or down?")
    checkClarify("bare search asks what", "search", "What do you want me to search for?")
    checkClarify("open asks which app", "open", "Which app do you want me to open?")
    checkClarify("move the assistant asks where", "move the assistant", "Where should I move — top-left, top-right, bottom-left, bottom-right, or out of the way?")
    checkClarify("bare shut down is a command, not clarification", "shut down", null)
    checkClarify("move to a new city is not clarification", "move to a new city", null)

    // ---- item 2: grounded-fallback allowlist validation ----
    val searchBlock = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:SEARCH_IN_APP arg=\"Spider-Man\" app=\"YouTube\"]] Searching now.",
    )
    checkTrue(
        "SEARCH_IN_APP block parses with query + app",
        searchBlock?.command == VoiceCommand.SEARCH_IN_APP &&
            (searchBlock?.capability as? LoomCapability.SearchInApp)?.query == "Spider-Man" &&
            (searchBlock?.capability as? LoomCapability.SearchInApp)?.appName == "YouTube",
    )
    val searchNoApp = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:SEARCH_IN_APP arg=\"cats\"]]",
    )
    checkTrue(
        "SEARCH_IN_APP without app parses, app blank",
        searchNoApp?.command == VoiceCommand.SEARCH_IN_APP &&
            (searchNoApp?.capability as? LoomCapability.SearchInApp)?.appName == "",
    )
    checkTrue(
        "SEARCH_IN_APP without arg -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SEARCH_IN_APP]]") == null,
    )
    val moveBlock = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:ASSISTANT_MOVE arg=\"TOP_LEFT\"]]",
    )
    checkTrue(
        "ASSISTANT_MOVE block parses with region",
        moveBlock?.command == VoiceCommand.ASSISTANT_MOVE &&
            (moveBlock?.capability as? LoomCapability.AssistantMove)?.regionName == "TOP_LEFT",
    )
    checkTrue(
        "ASSISTANT_MOVE unknown region -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:ASSISTANT_MOVE arg=\"MIDDLE\"]]") == null,
    )
    checkTrue(
        "ASSISTANT_MOVE without arg -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:ASSISTANT_MOVE]]") == null,
    )
    val pointerBlock = CapabilityBridge.parseModelAction(
        "[[LOOM_ACTION:POINTER_MOVE arg=\"LEFT\"]]",
    )
    checkTrue(
        "POINTER_MOVE block parses with direction",
        pointerBlock?.command == VoiceCommand.POINTER_MOVE &&
            (pointerBlock?.capability as? LoomCapability.PointerMove)?.direction == PointerDirection.LEFT,
    )
    checkTrue(
        "POINTER_MOVE bad direction -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:POINTER_MOVE arg=\"SIDEWAYS\"]]") == null,
    )
    checkTrue(
        "fallback: SHUTDOWN block -> null, never executed",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:SHUTDOWN]]") == null,
    )
    checkTrue(
        "fallback: DEACTIVATE block -> null",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:DEACTIVATE_ASSISTANT_MODE]]") == null,
    )
    checkTrue(
        "fallback: GO_SILENT block -> null (lifecycle unrepresentable)",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:GO_SILENT]]") == null,
    )
    checkTrue(
        "fallback: MODE_ORB block -> null (modes are user-phrase only)",
        CapabilityBridge.parseModelAction("[[LOOM_ACTION:MODE_ORB]]") == null,
    )
    checkTrue(
        "fallback: blocked block skipped, later valid block parses",
        CapabilityBridge.parseModelAction(
            "[[LOOM_ACTION:SHUTDOWN]] [[LOOM_ACTION:SEARCH_IN_APP arg=\"cats\" app=\"YouTube\"]]",
        )?.command == VoiceCommand.SEARCH_IN_APP,
    )
    checkTrue(
        "fallback: strip removes app-attr block, keeps prose",
        CapabilityBridge.stripModelActions(
            "On it. [[LOOM_ACTION:SEARCH_IN_APP arg=\"cats\" app=\"YouTube\"]]",
        ) == "On it.",
    )
    // The fallback system context: capability prompt + instruction +
    // the quoted utterance, so the model parses THIS turn's intent.
    val fallbackCtx = CapabilityBridge.fallbackSystemContext("help me find X on YouTube")
    checkTrue("fallback ctx carries the utterance", fallbackCtx.contains("help me find X on YouTube"))
    checkTrue("fallback ctx carries pick-or-say rule", fallbackCtx.contains("Pick an action or say you cannot"))
    checkTrue("fallback ctx carries never-narrate rule", fallbackCtx.contains("Never narrate an action as done"))
    checkTrue("fallback ctx carries action-block format", fallbackCtx.contains("[[LOOM_ACTION:"))
    checkTrue("fallback ctx lists SEARCH_IN_APP", fallbackCtx.contains("SEARCH_IN_APP"))
    checkTrue("fallback ctx lists ASSISTANT_MOVE", fallbackCtx.contains("ASSISTANT_MOVE"))
    checkTrue("fallback ctx forbids lifecycle blocks", fallbackCtx.contains("no action block and cannot be requested this way"))

    // ---- item 4: SEARCH_IN_APP routing ----
    check("search for query in app", VoiceCommands.match("search for Spider-Man in YouTube"), VoiceCommand.SEARCH_IN_APP)
    check("search query in app (no for)", VoiceCommands.match("search Spider-Man in YouTube"), VoiceCommand.SEARCH_IN_APP)
    check("search for query only", VoiceCommands.match("search for cats"), VoiceCommand.SEARCH_IN_APP)
    checkStr("search query extraction", VoiceCommands.extractSearchQuery("search for Spider-Man in YouTube"), "spider-man")
    checkStr("search app extraction", VoiceCommands.extractSearchApp("search for Spider-Man in YouTube"), "youtube")
    checkStr("search query with in inside it", VoiceCommands.extractSearchQuery("search for hotels in Lagos in Booking"), "hotels in lagos")
    checkStr("search app with in inside query", VoiceCommands.extractSearchApp("search for hotels in Lagos in Booking"), "booking")
    check("search with no query is not a command", VoiceCommands.match("search"), null)
    check("search for with no query is not a command", VoiceCommands.match("search for"), null)
    check("search in app with no query is not a command", VoiceCommands.match("search in YouTube"), null)

    // ---- item 3: app-name resolution — the matcher carries the exact
    // string into the seam; "YouTube Music" must never collapse to
    // "YouTube" (Worker 1's AppCatalogue disambiguates at execution).
    checkStr(
        "YouTube Music preserved whole",
        VoiceCommands.extractSearchApp("search for lo-fi in YouTube Music"),
        "youtube music",
    )
    checkTrue(
        "YouTube Music != YouTube as app strings",
        VoiceCommands.extractSearchApp("search for lo-fi in YouTube Music") !=
            VoiceCommands.extractSearchApp("search for lo-fi in YouTube"),
    )
    checkStr(
        "launch app YouTube Music preserved whole",
        VoiceCommands.extractLaunchApp("open YouTube Music"),
        "youtube music",
    )

    // ---- item 5: ASSISTANT_MOVE routing ----
    check("move to top-left corner", VoiceCommands.match("move to the top-left corner"), VoiceCommand.ASSISTANT_MOVE)
    check("move to bottom right", VoiceCommands.match("move to the bottom right"), VoiceCommand.ASSISTANT_MOVE)
    check("move to left edge", VoiceCommands.match("move to the left edge"), VoiceCommand.ASSISTANT_MOVE)
    check("move to right edge", VoiceCommands.match("move to the right edge"), VoiceCommand.ASSISTANT_MOVE)
    check("move out of the way", VoiceCommands.match("move out of the way"), VoiceCommand.ASSISTANT_MOVE)
    check("move down a bit", VoiceCommands.match("move down a bit"), VoiceCommand.ASSISTANT_MOVE)
    check("move up a bit", VoiceCommands.match("move up a bit"), VoiceCommand.ASSISTANT_MOVE)
    check("move the assistant left", VoiceCommands.match("move the assistant left"), VoiceCommand.ASSISTANT_MOVE)
    check("move yourself up", VoiceCommands.match("move yourself up"), VoiceCommand.ASSISTANT_MOVE)
    checkStr("region top-left", VoiceCommands.extractAssistantRegionName("move to the top-left corner"), "TOP_LEFT")
    checkStr("region bottom-right", VoiceCommands.extractAssistantRegionName("move to the bottom right"), "BOTTOM_RIGHT")
    checkStr("region left-edge", VoiceCommands.extractAssistantRegionName("move to the left edge"), "LEFT_EDGE")
    checkStr("region out-of-way", VoiceCommands.extractAssistantRegionName("move out of the way"), "OUT_OF_WAY")
    checkStr("region down-a-bit", VoiceCommands.extractAssistantRegionName("move down a bit"), "DOWN")
    checkStr("region assistant-left", VoiceCommands.extractAssistantRegionName("move the assistant left"), "LEFT")
    checkStr("region assistant-up", VoiceCommands.extractAssistantRegionName("move yourself up"), "UP")
    // Disambiguation (deliberate): bare directions stay pointer moves.
    check("move left stays POINTER_MOVE", VoiceCommands.match("move left"), VoiceCommand.POINTER_MOVE)
    check("move down stays POINTER_MOVE", VoiceCommands.match("move down"), VoiceCommand.POINTER_MOVE)
    check("move the pointer left stays POINTER_MOVE", VoiceCommands.match("move the pointer left"), VoiceCommand.POINTER_MOVE)
    check("move the pointer to top left stays POINTER_MOVE", VoiceCommands.match("move the pointer to the top left"), VoiceCommand.POINTER_MOVE)
    check("move to the top is not a region", VoiceCommands.match("move to the top"), null)
    check("move the assistant (no region) is not a command", VoiceCommands.match("move the assistant"), null)
    check("move to a new city is not a move", VoiceCommands.match("move to a new city"), null)

    // ---- item 6: minimal conversational context ----
    val ctx = ConversationContext()
    checkTrue("fresh context has no app", ctx.appForFollowUpSearch() == null)
    ctx.rememberLaunch("YouTube")
    checkStr("launch remembers app", ctx.appForFollowUpSearch(), "YouTube")
    checkTrue("launch remembers command", ctx.lastCommand == VoiceCommand.LAUNCH_APP)
    checkStr(
        "follow-up search resolves app from context",
        VoiceCommands.extractSearchApp("search for cats", ctx.appForFollowUpSearch()),
        "YouTube",
    )
    checkStr(
        "explicit app wins over context",
        VoiceCommands.extractSearchApp("search for cats in Netflix", ctx.appForFollowUpSearch()),
        "netflix",
    )
    ctx.rememberSearch("YouTube Music")
    checkStr("search remembers app", ctx.appForFollowUpSearch(), "YouTube Music")
    checkTrue("search remembers command", ctx.lastCommand == VoiceCommand.SEARCH_IN_APP)
    ctx.rememberLaunch("")
    checkStr("blank launch does not clear context", ctx.appForFollowUpSearch(), "YouTube Music")
    ctx.clear()
    checkTrue("clear empties context", ctx.appForFollowUpSearch() == null && ctx.lastCommand == null)

    // ---- task record (2026-11-07): the same context holds the ONE
    // active record — no second context system. Approval flips only
    // the status; the answer fills only the app slot; clear drops it.
    val rec = TaskRecord(
        instruction = "open YouTube Music and play music",
        app = "YouTube Music",
        appPackage = "com.google.android.apps.youtube.music",
        query = "music",
        origin = RequestOrigin.DIRECT_USER,
    )
    checkTrue("fresh context has no record", ctx.activeTask == null)
    ctx.startTask(rec)
    checkTrue("startTask holds the record", ctx.activeTask == rec)
    checkStr("startTask syncs the last-turn app", ctx.appForFollowUpSearch(), "YouTube Music")
    checkTrue("record starts UNDERSTOOD", ctx.activeTask?.status == TaskRecordStatus.UNDERSTOOD)
    val approved = ctx.approveActiveTask()
    checkTrue("approve flips only the status", approved?.status == TaskRecordStatus.APPROVED)
    checkStr("approve keeps the app", approved?.app, "YouTube Music")
    checkStr("approve keeps the package", approved?.appPackage, "com.google.android.apps.youtube.music")
    checkStr("approve keeps the instruction", approved?.instruction, "open YouTube Music and play music")
    checkStr("approve keeps the query", approved?.query, "music")
    val waiting = TaskRecord(
        instruction = "play music", app = null, appPackage = null,
        query = "music", origin = RequestOrigin.DIRECT_USER,
    ).markAppAsked()
    ctx.startTask(waiting)
    checkTrue("waiting record asks once", ctx.activeTask?.askedApp == true && ctx.activeTask?.awaitingApp == true)
    val answered = ctx.answerActiveTaskApp("YouTube Music")
    checkStr("answer fills the app", answered?.app, "YouTube Music")
    checkTrue("answer clears the wait", answered?.awaitingApp == false)
    checkTrue("answer keeps the asked flag", answered?.askedApp == true)
    ctx.clearTask()
    checkTrue("clearTask drops the record", ctx.activeTask == null)
    ctx.startTask(rec)
    ctx.clear()
    checkTrue("clear drops the record too", ctx.activeTask == null)

    // ---- resolveChatInput routing for the new commands ----
    checkTrue(
        "typed search -> ExecuteCommand(SEARCH_IN_APP)",
        (CapabilityBridge.resolveChatInput("search for Spider-Man in YouTube")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.SEARCH_IN_APP,
    )
    checkTrue(
        "typed move to corner -> ExecuteCommand(ASSISTANT_MOVE)",
        (CapabilityBridge.resolveChatInput("move to the top-left corner")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.ASSISTANT_MOVE,
    )
    checkTrue(
        "typed help me do -> ExecuteCommand(GUIDE_TO)",
        (CapabilityBridge.resolveChatInput("help me do the dishes")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GUIDE_TO,
    )
    checkTrue(
        "typed ghost silence -> ExecuteCommand(GO_SILENT)",
        (CapabilityBridge.resolveChatInput("ghost silence")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.GO_SILENT,
    )
    checkTrue(
        "typed shut down low -> ExecuteCommand(SHUTDOWN)",
        (CapabilityBridge.resolveChatInput("shut down low")
            as? ChatInputResolution.ExecuteCommand)?.cmd == VoiceCommand.SHUTDOWN,
    )
    checkTrue(
        "typed ordinary question -> SendToEngine (fallback covers it)",
        CapabilityBridge.resolveChatInput("what is the capital of France?") ==
            ChatInputResolution.SendToEngine,
    )

    println("SpeechUnderstandingTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
