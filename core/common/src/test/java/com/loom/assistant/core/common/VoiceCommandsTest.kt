package com.loom.assistant.core.common

/**
 * Stage 2A.1 (D2) — pure-JVM checks for the voice command matcher.
 * Run: kotlinc VoiceCommands.kt VoiceCommandsTest.kt -d out && java -cp out
 * com.loom.assistant.core.common.VoiceCommandsTestKt
 * (VoiceCommands.kt has no android.* imports, so no fakes are needed.)
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

private fun checkAmb(name: String, text: String, expected: Boolean) {
    val actual = VoiceCommands.matchAmbiguous(text)
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL(ambiguous): $name — expected $expected, got $actual")
    }
}

private fun checkExtract(name: String, actual: String, expected: String) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL(extract): $name — expected \"$expected\", got \"$actual\"")
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
        println("FAIL(clarify): $name — expected \"$expected\", got \"$actual\"")
    }
}

fun main() {
    // ---- MODE_* : unprefixed variations (David's Part 3 examples) ----
    check("switch to orb mode", VoiceCommands.match("Switch to Orb mode."), VoiceCommand.MODE_ORB)
    check("activate orb", VoiceCommands.match("Activate Orb."), VoiceCommand.MODE_ORB)
    check("change to character", VoiceCommands.match("change to character"), VoiceCommand.MODE_CHARACTER)
    check("go to invisible", VoiceCommands.match("go to invisible"), VoiceCommand.MODE_INVISIBLE)
    check("enable orb", VoiceCommands.match("enable orb"), VoiceCommand.MODE_ORB)
    check("put on invisible mode", VoiceCommands.match("put on invisible mode"), VoiceCommand.MODE_INVISIBLE)
    check("switch to character mode", VoiceCommands.match("switch to character mode"), VoiceCommand.MODE_CHARACTER)
    // ---- MODE_* : legacy prefixed form still works ----
    check("loom switch to orb mode", VoiceCommands.match("loom, switch to orb mode"), VoiceCommand.MODE_ORB)
    check("loom orb mode", VoiceCommands.match("loom orb mode"), VoiceCommand.MODE_ORB)
    check("loom invisible mode", VoiceCommands.match("loom invisible mode"), VoiceCommand.MODE_INVISIBLE)
    check("loom character mode", VoiceCommands.match("loom character mode"), VoiceCommand.MODE_CHARACTER)

    // ---- START_LISTENING (new) ----
    check("start listening", VoiceCommands.match("start listening"), VoiceCommand.START_LISTENING)
    check("resume listening", VoiceCommands.match("resume listening"), VoiceCommand.START_LISTENING)
    // ---- SWITCH_KEYBOARD (new) ----
    check("switch to keyboard", VoiceCommands.match("switch to keyboard"), VoiceCommand.SWITCH_KEYBOARD)
    check("keyboard mode", VoiceCommands.match("keyboard mode"), VoiceCommand.SWITCH_KEYBOARD)
    check("type instead", VoiceCommands.match("type instead"), VoiceCommand.SWITCH_KEYBOARD)
    // ---- STOP_LISTENING unprefixed ----
    check("stop listening", VoiceCommands.match("Stop listening."), VoiceCommand.STOP_LISTENING)
    // ---- STOP: new forms; bare "stop" is HALT_SEQUENCE (Stage 2C), never STOP ----
    check("stop talking", VoiceCommands.match("stop talking"), VoiceCommand.STOP)
    // ---- Phase 2 (plan §4.2-C, David decision 3): "be quiet" is GO_SILENT
    // now, NOT STOP; the word "quiet" alone never matches a command ----
    check("be quiet is GO_SILENT", VoiceCommands.match("be quiet"), VoiceCommand.GO_SILENT)
    check("go quiet is GO_SILENT", VoiceCommands.match("go quiet"), VoiceCommand.GO_SILENT)
    check("loom be quiet please is GO_SILENT", VoiceCommands.match("loom, be quiet please"), VoiceCommand.GO_SILENT)
    check("go quiet for now is GO_SILENT", VoiceCommands.match("go quiet for now"), VoiceCommand.GO_SILENT)
    check("quiet alone never matches", VoiceCommands.match("quiet"), null)
    check("mid-sentence be quiet is not a command", VoiceCommands.match("be quiet for a moment, I'm thinking"), null)
    check("mid-sentence go quiet is not a command", VoiceCommands.match("go quiet over there"), null)
    check("bare stop is HALT_SEQUENCE", VoiceCommands.match("stop"), VoiceCommand.HALT_SEQUENCE)
    // ---- Phase 2 (§4.2): SPEAK_AGAIN / HIDE_YOURSELF / SHOW_ASSISTANT /
    // DEACTIVATE_ASSISTANT_MODE ----
    check("speak again", VoiceCommands.match("speak again"), VoiceCommand.SPEAK_AGAIN)
    check("hide yourself", VoiceCommands.match("hide yourself"), VoiceCommand.HIDE_YOURSELF)
    check("loom hide yourself", VoiceCommands.match("loom, hide yourself"), VoiceCommand.HIDE_YOURSELF)
    check("show yourself", VoiceCommands.match("show yourself"), VoiceCommand.SHOW_ASSISTANT)
    check("deactivate assistant mode", VoiceCommands.match("deactivate assistant mode"), VoiceCommand.DEACTIVATE_ASSISTANT_MODE)
    // ---- ACTIVATE_ASSISTANT_MODE (state-aware activation; "show the
    // assistant" moved here from SHOW_ASSISTANT — notification SHOW path
    // is a direct service intent and never touches this matcher) ----
    check("activate assistant mode", VoiceCommands.match("activate assistant mode"), VoiceCommand.ACTIVATE_ASSISTANT_MODE)
    check("loom activate assistant mode please", VoiceCommands.match("loom, activate assistant mode please"), VoiceCommand.ACTIVATE_ASSISTANT_MODE)
    check("activate the assistant", VoiceCommands.match("activate the assistant"), VoiceCommand.ACTIVATE_ASSISTANT_MODE)
    check("start floating assistant", VoiceCommands.match("start floating assistant"), VoiceCommand.ACTIVATE_ASSISTANT_MODE)
    check("start the floating assistant", VoiceCommands.match("start the floating assistant"), VoiceCommand.ACTIVATE_ASSISTANT_MODE)
    check("show the assistant is ACTIVATE", VoiceCommands.match("show the assistant"), VoiceCommand.ACTIVATE_ASSISTANT_MODE)
    checkTrue("show the assistant is not SHOW_ASSISTANT", VoiceCommands.match("show the assistant") != VoiceCommand.SHOW_ASSISTANT)
    checkTrue("activate has no clarification", VoiceCommands.clarificationFor("activate assistant mode") == null)
    checkTrue("activate is not ambiguous", !VoiceCommands.matchAmbiguous("activate assistant mode"))
    checkTrue("activate is not deactivate", VoiceCommands.match("activate assistant mode") != VoiceCommand.DEACTIVATE_ASSISTANT_MODE)
    check("loom deactivate assistant mode", VoiceCommands.match("loom, deactivate assistant mode"), VoiceCommand.DEACTIVATE_ASSISTANT_MODE)
    check("turn off assistant mode", VoiceCommands.match("turn off assistant mode"), VoiceCommand.DEACTIVATE_ASSISTANT_MODE)
    // ---- Phase 2 (§4.2): "shut down voice" stays END_VOICE, distinct from
    // the destructive SHUTDOWN (loom prefix) and from DEACTIVATE ----
    check("shut down voice is not SHUTDOWN", VoiceCommands.match("shut down voice"), VoiceCommand.END_VOICE)
    checkTrue("deactivate is not END_VOICE", VoiceCommands.match("deactivate assistant mode") != VoiceCommand.END_VOICE)
    // ---- PAUSE / RESUME ----
    check("pause", VoiceCommands.match("pause"), VoiceCommand.PAUSE)
    check("resume", VoiceCommands.match("resume"), VoiceCommand.RESUME)
    // ---- SHUTDOWN (2026-11-10, David's rule): plain "shut down"
    // and natural variants match — no "loom" prefix required. A
    // match executes the shutdown IMMEDIATELY (Nov 13 binding
    // rule: no confirmation question, at any length). ----
    check("loom shut down", VoiceCommands.match("loom, shut down"), VoiceCommand.SHUTDOWN)
    check("bare shut down is SHUTDOWN", VoiceCommands.match("shut down"), VoiceCommand.SHUTDOWN)
    check("shutdown is SHUTDOWN", VoiceCommands.match("shutdown"), VoiceCommand.SHUTDOWN)
    check("shut down loom is SHUTDOWN", VoiceCommands.match("shut down loom"), VoiceCommand.SHUTDOWN)
    check("turn off is SHUTDOWN", VoiceCommands.match("turn off"), VoiceCommand.SHUTDOWN)
    check("power off is SHUTDOWN", VoiceCommands.match("power off"), VoiceCommand.SHUTDOWN)
    check("switch off is SHUTDOWN", VoiceCommands.match("switch off"), VoiceCommand.SHUTDOWN)
    check("turn it off is SHUTDOWN", VoiceCommands.match("turn it off"), VoiceCommand.SHUTDOWN)
    check("kill loom is SHUTDOWN", VoiceCommands.match("kill loom"), VoiceCommand.SHUTDOWN)
    check("shut it down is SHUTDOWN", VoiceCommands.match("please shut it down"), VoiceCommand.SHUTDOWN)
    check("turn loom off is SHUTDOWN", VoiceCommands.match("turn loom off"), VoiceCommand.SHUTDOWN)
    check("turn off the pointer stays POINTER_HIDE", VoiceCommands.match("turn off the pointer"), VoiceCommand.POINTER_HIDE)
    check("shut down the pointer is not SHUTDOWN (excluded)", VoiceCommands.match("shut down the pointer"), null)
    // ---- END_VOICE / EXIT_VOICE ----
    check("end voice session", VoiceCommands.match("end voice session"), VoiceCommand.END_VOICE)
    check("shut down voice", VoiceCommands.match("shut down voice session"), VoiceCommand.END_VOICE)
    check("exit voice mode", VoiceCommands.match("exit voice mode"), VoiceCommand.EXIT_VOICE)

    // ---- negatives: normal speech is not a command ----
    check("gloomy is not a command", VoiceCommands.match("it's a gloomy day"), null)
    check("plain chat", VoiceCommands.match("what can you do"), null)
    check("switch it -> null (ambiguous path)", VoiceCommands.match("switch it"), null)
    check("change mode -> null (ambiguous path)", VoiceCommands.match("change mode"), null)

    // ---- Stage 2B (D3a): pointer policy commands ----
    check("show the pointer", VoiceCommands.match("show the pointer"), VoiceCommand.POINTER_SHOW)
    check("show pointer", VoiceCommands.match("show pointer"), VoiceCommand.POINTER_SHOW)
    check("turn the pointer on", VoiceCommands.match("turn the pointer on"), VoiceCommand.POINTER_SHOW)
    check("switch the pointer on", VoiceCommands.match("switch the pointer on"), VoiceCommand.POINTER_SHOW)
    check("enable the pointer", VoiceCommands.match("enable the pointer"), VoiceCommand.POINTER_SHOW)
    check("hide the pointer", VoiceCommands.match("hide the pointer"), VoiceCommand.POINTER_HIDE)
    check("hide pointer", VoiceCommands.match("hide pointer"), VoiceCommand.POINTER_HIDE)
    check("turn the pointer off", VoiceCommands.match("turn the pointer off"), VoiceCommand.POINTER_HIDE)
    check("switch the pointer off", VoiceCommands.match("switch the pointer off"), VoiceCommand.POINTER_HIDE)
    check("disable the pointer", VoiceCommands.match("disable the pointer"), VoiceCommand.POINTER_HIDE)
    check("pointer auto", VoiceCommands.match("pointer auto"), VoiceCommand.POINTER_AUTO)
    check("automatic pointer", VoiceCommands.match("automatic pointer"), VoiceCommand.POINTER_AUTO)
    check("auto pointer", VoiceCommands.match("make the pointer automatic"), VoiceCommand.POINTER_AUTO)
    // ---- Accessibility fix (2026-11-05, audit §2): the Nov 5 transcript
    // phrasings now match POINTER_SHOW instead of falling to the engine.
    check("show me the pointer", VoiceCommands.match("show me the pointer"), VoiceCommand.POINTER_SHOW)
    check("where is the pointer", VoiceCommands.match("where is the pointer"), VoiceCommand.POINTER_SHOW)
    check("where's the pointer", VoiceCommands.match("where's the pointer"), VoiceCommand.POINTER_SHOW)
    check("i can't see the pointer", VoiceCommands.match("I can't see the pointer"), VoiceCommand.POINTER_SHOW)
    check("i cant see the pointer", VoiceCommands.match("i cant see the pointer"), VoiceCommand.POINTER_SHOW)
    check("make the pointer appear", VoiceCommands.match("make the pointer appear"), VoiceCommand.POINTER_SHOW)
    check("help me find my way", VoiceCommands.match("help me find my way"), VoiceCommand.POINTER_SHOW)
    check("transcript: okay help me find my way then", VoiceCommands.match("okay help me find my way then"), VoiceCommand.POINTER_SHOW)
    check("bring up the pointer", VoiceCommands.match("bring up the pointer"), VoiceCommand.POINTER_SHOW)
    check("put the pointer on screen", VoiceCommands.match("put the pointer on screen"), VoiceCommand.POINTER_SHOW)
    // ---- widening negatives: the new triggers steal no neighbour ----
    checkTrue("help me find my way is not GUIDE_TO", VoiceCommands.match("help me find my way") != VoiceCommand.GUIDE_TO)
    checkTrue("help me find my way to X is not GUIDE_TO", VoiceCommands.match("help me find my way to the settings") != VoiceCommand.GUIDE_TO)
    check("make the pointer invisible stays HIDE", VoiceCommands.match("make the pointer invisible"), VoiceCommand.POINTER_HIDE)
    check("help me do X stays GUIDE_TO", VoiceCommands.match("help me do the washing"), VoiceCommand.GUIDE_TO)
    checkClarify("help me find my way is a command, not clarification", "help me find my way", null)
    checkAmb("help me find my way is not ambiguous", "help me find my way", false)
    // ---- fuzzy rescue: STT near-misses land on POINTER_SHOW ----
    check("fuzzy show the pointr", VoiceCommands.match("show the pointr"), VoiceCommand.POINTER_SHOW)
    check("fuzzy show me the pointr", VoiceCommands.match("show me the pointr"), VoiceCommand.POINTER_SHOW)
    check("fuzzy turn the pointr on", VoiceCommands.match("turn the pointr on"), VoiceCommand.POINTER_SHOW)
    check("fuzzy wher is the pointer", VoiceCommands.match("wher is the pointer"), VoiceCommand.POINTER_SHOW)
    check("fuzzy help me find my wey", VoiceCommands.match("help me find my wey"), VoiceCommand.POINTER_SHOW)
    check("fuzzy make the pointer appeer", VoiceCommands.match("make the pointer appeer"), VoiceCommand.POINTER_SHOW)
    checkTrue("fuzzy hid the pointer is not SHOW", VoiceCommands.match("hid the pointer") != VoiceCommand.POINTER_SHOW)
    // ---- pointer negatives: not a mode switch, not chat ----
    check("switch to invisible is still MODE_INVISIBLE", VoiceCommands.match("switch to invisible mode"), VoiceCommand.MODE_INVISIBLE)
    check("point me is not a pointer command", VoiceCommands.match("can you point me to the settings"), null)
    checkAmb("switch the pointer off is not ambiguous", "switch the pointer off", false)
    checkAmb("pointer chat is not ambiguous", "where is the pointer", false)

    // ---- matchAmbiguous ----
    checkAmb("switch it", "switch it", true)
    checkAmb("change mode", "change mode", true)
    checkAmb("loom switch", "loom switch", true)
    checkAmb("switch to orb mode is not ambiguous", "switch to orb mode", false)
    checkAmb("activate orb is not ambiguous", "activate orb", false)
    checkAmb("gloomy is not ambiguous", "gloomy day", false)
    checkAmb("plain chat is not ambiguous", "what can you do", false)
    checkAmb("stop listening is not ambiguous", "stop listening", false)

    // ---- Stage 2C (T9): HALT_SEQUENCE entries ----
    check("stop", VoiceCommands.match("stop"), VoiceCommand.HALT_SEQUENCE)
    check("stop with punctuation", VoiceCommands.match("Stop."), VoiceCommand.HALT_SEQUENCE)
    check("stop the demo", VoiceCommands.match("stop the demo"), VoiceCommand.HALT_SEQUENCE)
    check("stop doing that", VoiceCommands.match("stop doing that"), VoiceCommand.HALT_SEQUENCE)
    check("stop the sequence", VoiceCommands.match("stop the sequence"), VoiceCommand.HALT_SEQUENCE)
    // ---- Stage 2C (T9): guidance entries ----
    check("guide me to", VoiceCommands.match("guide me to the wifi toggle"), VoiceCommand.GUIDE_TO)
    check("guide me to (caps)", VoiceCommands.match("Guide me to the Settings app"), VoiceCommand.GUIDE_TO)
    check("guide next", VoiceCommands.match("guide next"), VoiceCommand.GUIDE_NEXT)
    check("next step", VoiceCommands.match("next step"), VoiceCommand.GUIDE_NEXT)
    check("guide back", VoiceCommands.match("guide back"), VoiceCommand.GUIDE_BACK)
    check("previous step", VoiceCommands.match("previous step"), VoiceCommand.GUIDE_BACK)
    // ---- Stage 2C (T9): demonstration entries ----
    check("tap it for me", VoiceCommands.match("tap it for me"), VoiceCommand.DEMO_TAP)
    check("show me how to", VoiceCommands.match("show me how to open the calculator"), VoiceCommand.DEMO_SEQUENCE)
    // ---- Stage 2C (T9): target/goal extraction ----
    checkExtract("guide target", VoiceCommands.extractGuideTarget("guide me to the wifi toggle"), "the wifi toggle")
    checkExtract("guide target (caps)", VoiceCommands.extractGuideTarget("Guide me to the Settings app."), "the Settings app")
    checkExtract("guide target empty", VoiceCommands.extractGuideTarget("guide me to"), "")
    checkExtract("demo goal", VoiceCommands.extractDemoGoal("show me how to open the calculator"), "open the calculator")
    checkExtract("demo goal empty", VoiceCommands.extractDemoGoal("show me how to"), "")
    // ---- Stage 2C (T9): negatives — the collisions that must never happen ----
    check("stop listening stays STOP_LISTENING", VoiceCommands.match("stop listening"), VoiceCommand.STOP_LISTENING)
    check("stop listening (caps) stays STOP_LISTENING", VoiceCommands.match("Stop listening."), VoiceCommand.STOP_LISTENING)
    check("make the pointer invisible stays POINTER_HIDE", VoiceCommands.match("make the pointer invisible"), VoiceCommand.POINTER_HIDE)
    check("stop talking stays STOP", VoiceCommands.match("stop talking"), VoiceCommand.STOP)
    checkAmb("guide me to is not ambiguous", "guide me to the wifi toggle", false)
    checkAmb("stop the demo is not ambiguous", "stop the demo", false)
    checkAmb("show me how to is not ambiguous", "show me how to open settings", false)

    // ---- Phase 2 (mismatch #4): clarification layer — unmatched
    // lifecycle-class phrasings → spoken templates, never the LLM ----
    val SHUTDOWN_T = "Do you want me to shut down LOOM completely, or just end the voice session?"
    val STOP_T = "Do you want me to stop the demonstration, stop listening, or stop talking?"
    val HIDE_T = "Do you want me to hide the floating assistant, or switch to Invisible mode?"
    val QUIET_T = "Do you want me to go silent, or stop something specific?"
    // shutdown class (2026-11-10): the plain variants now match
    // SHUTDOWN, so they clarify no longer — each is a command, not
    // a clarification. Only a "shut down" phrasing the matcher
    // deliberately excludes ("shut down the pointer") still asks.
    checkClarify("power off is a command, not clarification", "power off", null)
    checkClarify("turn it off is a command, not clarification", "turn it off", null)
    checkClarify("kill loom is a command, not clarification", "kill loom", null)
    checkClarify("shut it down is a command, not clarification", "please shut it down", null)
    checkClarify("shut down the pointer still asks", "shut down the pointer", SHUTDOWN_T)
    // Fix 2 (2026-11-13, F1-DEFECT-1): the shutdown clarification
    // is scoped to the pointer case ONLY. Negated shutdown text
    // never asks — not even the pointer form — and a long
    // shutdown utterance is a command, not a clarification.
    // Assistant-mode forms ask nothing either (no pointer).
    checkClarify("negated shutdown asks nothing", "don't shut down", null)
    checkClarify("negated shutdown (do not) asks nothing", "do not shut down", null)
    checkClarify("negated pointer shutdown asks nothing", "don't shut down the pointer", null)
    checkClarify("long shutdown is a command, not clarification", "loom please shut down completely now I am finished using you for today thank you", null)
    checkClarify("shut down assistant mode asks nothing", "shut down assistant mode", null)
    // stop class
    checkClarify("stop it", "stop it", STOP_T)
    checkClarify("please stop", "please stop", STOP_T)
    checkClarify("stop now", "stop now", STOP_T)
    checkClarify("mute", "mute", STOP_T)
    // hide / mode class
    checkClarify("go away", "go away", HIDE_T)
    checkClarify("hide", "hide", HIDE_T)
    checkClarify("disappear", "disappear", HIDE_T)
    // quiet class — mid-sentence occurrences only (exact forms are GO_SILENT)
    checkClarify("mid-sentence be quiet", "be quiet for a moment, I'm thinking", QUIET_T)
    checkClarify("mid-sentence go quiet", "go quiet over there", QUIET_T)
    checkClarify("quiet alone", "quiet", QUIET_T)
    // negatives: commands and plain chat never clarify
    checkClarify("be quiet is a command, not clarification", "be quiet", null)
    checkClarify("go quiet is a command, not clarification", "go quiet", null)
    checkClarify("stop is a command, not clarification", "stop", null)
    checkClarify("hide yourself is a command, not clarification", "hide yourself", null)
    checkClarify("hide the pointer is a command, not clarification", "hide the pointer", null)
    checkClarify("plain chat is not clarification", "what can you do", null)
    checkClarify("gloomy is not clarification", "it's a gloomy day", null)
    checkClarify("deactivate is a command, not clarification", "deactivate assistant mode", null)
    checkClarify("loom shut down is a command, not clarification", "loom, shut down", null)

    // ---- Device-evidence fix (2026-11-05): bare mode fragments ----
    // Typed "to invisible mode" was answered as DISCORD's invisible
    // status. The fragment rule intercepts it as MODE_INVISIBLE.
    check("to invisible mode", VoiceCommands.match("to invisible mode"), VoiceCommand.MODE_INVISIBLE)
    check("invisible mode", VoiceCommands.match("invisible mode"), VoiceCommand.MODE_INVISIBLE)
    check("bare invisible", VoiceCommands.match("invisible"), VoiceCommand.MODE_INVISIBLE)
    check("to orb mode", VoiceCommands.match("to orb mode"), VoiceCommand.MODE_ORB)
    check("to character mode", VoiceCommands.match("to character mode"), VoiceCommand.MODE_CHARACTER)
    check("loom to invisible mode", VoiceCommands.match("loom, to invisible mode"), VoiceCommand.MODE_INVISIBLE)
    check("switch to invisible mode still works", VoiceCommands.match("switch to invisible mode"), VoiceCommand.MODE_INVISIBLE)
    // Extra words break the fragment — these stay conversational.
    check("what is invisible mode is not a command", VoiceCommands.match("what is invisible mode?"), null)
    check("invisible is cool is not a command", VoiceCommands.match("invisible is cool"), null)
    checkAmb("to invisible mode is not ambiguous", "to invisible mode", false)
    checkAmb("what is invisible mode is not ambiguous", "what is invisible mode?", false)

    // ---- Device-evidence fix (2026-11-05): LAUNCH_APP ----
    // Typed "can you open my Youtube" was roleplayed then denied.
    check("can you open my Youtube", VoiceCommands.match("can you open my Youtube"), VoiceCommand.LAUNCH_APP)
    check("open youtube", VoiceCommands.match("open youtube"), VoiceCommand.LAUNCH_APP)
    check("open my youtube", VoiceCommands.match("open my youtube"), VoiceCommand.LAUNCH_APP)
    check("launch whatsapp", VoiceCommands.match("launch whatsapp"), VoiceCommand.LAUNCH_APP)
    check("please open youtube", VoiceCommands.match("please open youtube"), VoiceCommand.LAUNCH_APP)
    checkExtract("extract can you open my Youtube", VoiceCommands.extractLaunchApp("can you open my Youtube"), "youtube")
    checkExtract("extract open my youtube", VoiceCommands.extractLaunchApp("open my youtube"), "youtube")
    checkExtract("extract launch whatsapp", VoiceCommands.extractLaunchApp("launch whatsapp"), "whatsapp")
    checkExtract("extract open alone is empty", VoiceCommands.extractLaunchApp("open"), "")
    // No collisions: launch verbs never steal existing commands.
    check("start floating assistant still ACTIVATE", VoiceCommands.match("start floating assistant"), VoiceCommand.ACTIVATE_ASSISTANT_MODE)
    check("start listening still START_LISTENING", VoiceCommands.match("start listening"), VoiceCommand.START_LISTENING)
    check("open alone is not a command", VoiceCommands.match("open"), null)
    checkClarify("open alone asks which app", "open", "Which app do you want me to open?")
    checkClarify("launch alone asks which app", "launch", "Which app do you want me to open?")

    // ---- Device-evidence fix (2026-11-05): GO_SILENT "go silent" ----
    check("go silent", VoiceCommands.match("go silent"), VoiceCommand.GO_SILENT)
    check("go quiet still GO_SILENT", VoiceCommands.match("go quiet"), VoiceCommand.GO_SILENT)
    check("be quiet still GO_SILENT", VoiceCommands.match("be quiet"), VoiceCommand.GO_SILENT)
    check("mid-sentence silent is not GO_SILENT", VoiceCommands.match("I'm telling you to go silent"), null)
    checkClarify("mid-sentence silent clarifies", "I'm telling you to go silent", "Do you want me to go silent, or stop something specific?")
    checkClarify("go silent is a command, not clarification", "go silent", null)

    // ---- Device-evidence fix (2026-11-05), updated 2026-11-10 ----
    // "shut down low", "okay shut down now", "shut down shut down"
    // ended in "Shutting down." / hanging "..." — none may reach the
    // engine. Master §2 (item 1) CHANGES "shut down low": "low" is a
    // misheard "loom", so it matches SHUTDOWN through the bounded fuzzy
    // loom-token rule. 2026-11-10 (David's rule): plain "shut down"
    // matches SHUTDOWN outright, so "okay shut down now" and "shut
    // down shut down" now match too — the confirmation dialog still
    // stands, so a near-miss only ever asks, never shuts down.
    check("shut down low is SHUTDOWN (misheard loom)", VoiceCommands.match("shut down low"), VoiceCommand.SHUTDOWN)
    check("okay shut down now is SHUTDOWN", VoiceCommands.match("okay shut down now"), VoiceCommand.SHUTDOWN)
    check("shut down shut down is SHUTDOWN", VoiceCommands.match("shut down shut down"), VoiceCommand.SHUTDOWN)
    checkClarify("shut down low is a command, not clarification", "shut down low", null)
    checkClarify("okay shut down now is a command, not clarification", "okay shut down now", null)
    checkClarify("shut down shut down is a command, not clarification", "shut down shut down", null)

    // ---- "fix everything" (2026-11-05): natural guidance phrasings ----
    check("guide me through", VoiceCommands.match("guide me through the settings"), VoiceCommand.GUIDE_TO)
    checkExtract("extract guide me through", VoiceCommands.extractGuideTarget("guide me through the settings"), "the settings")
    checkExtract("extract guide me to still wins", VoiceCommands.extractGuideTarget("guide me to the wifi toggle"), "the wifi toggle")
    check("pointer+guide routes to GUIDE_TO", VoiceCommands.match("I need you to use your pointer to guide me"), VoiceCommand.GUIDE_TO)
    checkTrue("pointer+guide is not a policy change", VoiceCommands.match("I need you to use your pointer to guide me") != VoiceCommand.POINTER_SHOW)

    // ---- "fix everything": 5:44 voice + MovieBox launch cases ----
    check("open my Youtube (5:44 transcript)", VoiceCommands.match("open my Youtube"), VoiceCommand.LAUNCH_APP)
    checkExtract("extract open my Youtube", VoiceCommands.extractLaunchApp("open my Youtube"), "youtube")
    check("open MovieBox", VoiceCommands.match("open MovieBox"), VoiceCommand.LAUNCH_APP)
    checkExtract("extract open MovieBox", VoiceCommands.extractLaunchApp("open MovieBox"), "moviebox")
    check("launch MovieBox", VoiceCommands.match("launch MovieBox"), VoiceCommand.LAUNCH_APP)

    // ---- "fix everything": POINTER_MOVE ----
    check("move the pointer left", VoiceCommands.match("move the pointer left"), VoiceCommand.POINTER_MOVE)
    check("move left", VoiceCommands.match("move left"), VoiceCommand.POINTER_MOVE)
    check("move pointer right", VoiceCommands.match("move pointer right"), VoiceCommand.POINTER_MOVE)
    check("move the pointer up", VoiceCommands.match("move the pointer up"), VoiceCommand.POINTER_MOVE)
    check("move down", VoiceCommands.match("move down"), VoiceCommand.POINTER_MOVE)
    check("move the pointer to the left", VoiceCommands.match("move the pointer to the left"), VoiceCommand.POINTER_MOVE)
    check("loom move the pointer left", VoiceCommands.match("loom, move the pointer left"), VoiceCommand.POINTER_MOVE)
    check("move sideways left", VoiceCommands.match("move sideways left"), VoiceCommand.POINTER_MOVE)
    checkTrue("dir move left is LEFT", VoiceCommands.extractPointerDirection("move the pointer left") == PointerDirection.LEFT)
    checkTrue("dir move right is RIGHT", VoiceCommands.extractPointerDirection("move pointer right") == PointerDirection.RIGHT)
    checkTrue("dir move up is UP", VoiceCommands.extractPointerDirection("move up") == PointerDirection.UP)
    checkTrue("dir move down is DOWN", VoiceCommands.extractPointerDirection("move down") == PointerDirection.DOWN)
    check("move sideways is not a command", VoiceCommands.match("move sideways"), null)
    check("move the pointer is not a command", VoiceCommands.match("move the pointer"), null)
    check("move left and right is not a command", VoiceCommands.match("move left and right"), null)
    check("remove the pointer is not a move", VoiceCommands.match("remove the pointer"), null)
    check("move to a new city is not a move", VoiceCommands.match("move to a new city"), null)
    checkTrue("move left is not POINTER_SHOW", VoiceCommands.match("move the pointer left") != VoiceCommand.POINTER_SHOW)
    checkClarify("move sideways asks left or right", "move sideways", "Which way should I move the pointer — left, right, up, or down?")
    checkClarify("move the pointer asks direction", "move the pointer", "Which way should I move the pointer — left, right, up, or down?")
    checkClarify("move to a new city is not clarification", "move to a new city", null)

    // ---- Master §2 (item 4): search is a REAL command now (5:44
    // "Searching for…" roleplay is replaced by Worker 4's executor).
    // Only a bare "search" (no query) still clarifies.
    check("search for is SEARCH_IN_APP", VoiceCommands.match("search for Spider-Man"), VoiceCommand.SEARCH_IN_APP)
    check("search for in app is SEARCH_IN_APP", VoiceCommands.match("search for Spider-Man in YouTube"), VoiceCommand.SEARCH_IN_APP)
    checkExtract("search query", VoiceCommands.extractSearchQuery("search for Spider-Man in YouTube"), "spider-man")
    checkExtract("search app", VoiceCommands.extractSearchApp("search for Spider-Man in YouTube"), "youtube")
    checkExtract("search query no app", VoiceCommands.extractSearchQuery("search for cats"), "cats")
    checkExtract("search app no app", VoiceCommands.extractSearchApp("search for cats"), "")
    checkClarify("bare search asks what", "search", "What do you want me to search for?")
    checkClarify("search for (no query) asks what", "search for", "What do you want me to search for?")
    checkClarify("research is not search clarification", "research AI tools", null)
    check("research is not a command", VoiceCommands.match("research AI tools"), null)

    // ---- Device-evidence fix (2026-11-05, item 6): matcher widening ONLY ----
    check("can you search for is SEARCH_IN_APP", VoiceCommands.match("can you search for Spider-Man"), VoiceCommand.SEARCH_IN_APP)
    checkExtract("can you search query", VoiceCommands.extractSearchQuery("can you search for Spider-Man"), "spider-man")
    checkExtract("can you search app in form", VoiceCommands.extractSearchApp("can you search for Spider-Man in YouTube"), "youtube")
    check("search app for query is SEARCH_IN_APP", VoiceCommands.match("search YouTube for Spider-Man"), VoiceCommand.SEARCH_IN_APP)
    checkExtract("app-for query", VoiceCommands.extractSearchQuery("search YouTube for Spider-Man"), "spider-man")
    checkExtract("app-for app", VoiceCommands.extractSearchApp("search YouTube for Spider-Man"), "youtube")
    checkExtract("app-for multiword app", VoiceCommands.extractSearchApp("search YouTube Music for lo-fi"), "youtube music")
    checkExtract("app-for multiword query", VoiceCommands.extractSearchQuery("search YouTube Music for lo-fi"), "lo-fi")
    check("search query on app is SEARCH_IN_APP", VoiceCommands.match("search Spider-Man on YouTube"), VoiceCommand.SEARCH_IN_APP)
    checkExtract("on-form query", VoiceCommands.extractSearchQuery("search Spider-Man on YouTube"), "spider-man")
    checkExtract("on-form app", VoiceCommands.extractSearchApp("search Spider-Man on YouTube"), "youtube")
    checkExtract("on-form with for-introducer query", VoiceCommands.extractSearchQuery("search for Spider-Man on YouTube"), "spider-man")
    checkExtract("on-form with for-introducer app", VoiceCommands.extractSearchApp("search for Spider-Man on YouTube"), "youtube")
    // The "for" introducer protects a query containing " for ".
    checkExtract("for-introducer query keeps for-phrase", VoiceCommands.extractSearchQuery("search for hotels for dogs"), "hotels for dogs")
    checkExtract("for-introducer app is empty", VoiceCommands.extractSearchApp("search for hotels for dogs"), "")
    checkClarify("bare can you search asks what", "can you search", "What do you want me to search for?")
    checkClarify("bare can you search for asks what", "can you search for", "What do you want me to search for?")

    // ---- "fix everything": PointerNudge math (pure, JVM-testable) ----
    checkTrue("nudge left steps", PointerNudge.step(500f, 500f, PointerDirection.LEFT, 48f, 1080f, 2400f, 24f) == (452f to 500f))
    checkTrue("nudge right steps", PointerNudge.step(500f, 500f, PointerDirection.RIGHT, 48f, 1080f, 2400f, 24f) == (548f to 500f))
    checkTrue("nudge up steps", PointerNudge.step(500f, 500f, PointerDirection.UP, 48f, 1080f, 2400f, 24f) == (500f to 452f))
    checkTrue("nudge down steps", PointerNudge.step(500f, 500f, PointerDirection.DOWN, 48f, 1080f, 2400f, 24f) == (500f to 548f))
    checkTrue("nudge clamps at left edge", PointerNudge.step(30f, 500f, PointerDirection.LEFT, 48f, 1080f, 2400f, 24f) == (24f to 500f))
    checkTrue("nudge clamps at bottom edge", PointerNudge.step(500f, 2390f, PointerDirection.DOWN, 48f, 1080f, 2400f, 24f) == (500f to 2376f))

    // ---- Dictated messaging + calls (2026-11-05, fix-plan item 12) ----
    check("send whatsapp is SEND_WHATSAPP_MESSAGE", VoiceCommands.match("send WhatsApp to Mum saying hello there"), VoiceCommand.SEND_WHATSAPP_MESSAGE)
    check("message on whatsapp is SEND_WHATSAPP_MESSAGE", VoiceCommands.match("message Mum on WhatsApp saying hello there"), VoiceCommand.SEND_WHATSAPP_MESSAGE)
    check("text is SEND_SMS", VoiceCommands.match("text Mum saying on my way"), VoiceCommand.SEND_SMS)
    check("send sms is SEND_SMS", VoiceCommands.match("send SMS to Mum saying on my way"), VoiceCommand.SEND_SMS)
    check("call is PLACE_CALL", VoiceCommands.match("call Mum"), VoiceCommand.PLACE_CALL)
    check("please call is PLACE_CALL", VoiceCommands.match("please call Mum"), VoiceCommand.PLACE_CALL)
    check("call a number is PLACE_CALL", VoiceCommands.match("call 08031234567"), VoiceCommand.PLACE_CALL)
    checkExtract("whatsapp target", VoiceCommands.extractMessageTarget("send WhatsApp to Mum saying hello there"), "Mum")
    checkExtract("whatsapp text verbatim", VoiceCommands.extractMessageText("send WhatsApp to Mum saying Hello There, see you at 5"), "Hello There, see you at 5")
    checkExtract("first saying splits, second stays in text", VoiceCommands.extractMessageText("send WhatsApp to Mum saying I was saying hello"), "I was saying hello")
    checkExtract("message-on-whatsapp target", VoiceCommands.extractMessageTarget("message Mum on WhatsApp saying hi"), "Mum")
    checkExtract("sms target", VoiceCommands.extractMessageTarget("text Dad saying on my way"), "Dad")
    checkExtract("sms text", VoiceCommands.extractMessageText("send SMS to Dad saying on my way"), "on my way")
    checkExtract("call target", VoiceCommands.extractCallTarget("call Mum"), "Mum")
    checkExtract("call my target", VoiceCommands.extractCallTarget("call my Mum"), "Mum")
    checkExtract("call number target", VoiceCommands.extractCallTarget("call 08031234567"), "08031234567")
    checkTrue("whatsapp channel", VoiceCommands.extractMessageChannel("send WhatsApp to Mum saying hi") == MessageChannel.WHATSAPP)
    checkTrue("sms channel", VoiceCommands.extractMessageChannel("text Mum saying hi") == MessageChannel.SMS)
    checkTrue("bulk everyone is bulk", VoiceCommands.isBulkMessageRequest("send WhatsApp to everyone saying hi"))
    checkTrue("bulk everybody is bulk", VoiceCommands.isBulkMessageRequest("text everybody saying hi"))
    checkTrue("bulk all contacts is bulk", VoiceCommands.isBulkMessageRequest("send SMS to all my contacts saying hi"))
    checkTrue("one person is not bulk", !VoiceCommands.isBulkMessageRequest("send WhatsApp to Mum saying hi"))
    checkTrue("bulk call is bulk", VoiceCommands.isBulkMessageRequest("call everyone"))
    checkTrue("everyone in the message text is NOT bulk", !VoiceCommands.isBulkMessageRequest("send WhatsApp to Mum saying tell everyone hi"))
    check("everyone in the text still matches a send", VoiceCommands.match("send WhatsApp to Mum saying tell everyone hi"), VoiceCommand.SEND_WHATSAPP_MESSAGE)
    check("bulk send never matches a send command", VoiceCommands.match("send WhatsApp to everyone saying hi"), null)
    checkClarify("bare call asks who", "call", "Who should I call?")
    checkClarify("send whatsapp no text asks what to say", "send WhatsApp to Mum", "What should I say to mum?")
    checkClarify("bare send whatsapp asks who + what", "send WhatsApp", "Who should I message, and what should I say?")
    checkClarify("bare text asks who + what", "text", "Who should I message, and what should I say?")
    checkClarify("bare send sms asks who + what", "send SMS", "Who should I message, and what should I say?")
    checkClarify("full send phrase does not clarify", "send WhatsApp to Mum saying hi", null)
    checkClarify("full call phrase does not clarify", "call Mum", null)
    checkClarify("bulk send is refused, not clarified to the engine", "send WhatsApp to everyone saying hi", "I don't send bulk messages — tell me one person at a time.")

    // ---- Stage 2D (Worker C): PLAN_TASK routing ----
    check("Davido sentence routes to PLAN_TASK", VoiceCommands.match("open YouTube Music and play a song by Davido"), VoiceCommand.PLAN_TASK)
    check("guide me through turning on dark mode routes to PLAN_TASK", VoiceCommands.match("guide me through turning on dark mode"), VoiceCommand.PLAN_TASK)
    check("search + open first result routes to PLAN_TASK", VoiceCommands.match("search for Spider-Man in YouTube and open the first result"), VoiceCommand.PLAN_TASK)
    check("open Settings and turn on dark mode routes to PLAN_TASK", VoiceCommands.match("open Settings and turn on dark mode"), VoiceCommand.PLAN_TASK)
    check("do + composite routes to PLAN_TASK", VoiceCommands.match("do open YouTube Music and play a song by Davido"), VoiceCommand.PLAN_TASK)
    check("walk me through turning on dark mode routes to PLAN_TASK", VoiceCommands.match("walk me through turning on dark mode"), VoiceCommand.PLAN_TASK)
    // ---- Stage 2D negatives: single-action commands keep winning ----
    check("open YouTube stays LAUNCH_APP", VoiceCommands.match("open YouTube"), VoiceCommand.LAUNCH_APP)
    check("search for X in YouTube stays SEARCH_IN_APP", VoiceCommands.match("search for Spider-Man in YouTube"), VoiceCommand.SEARCH_IN_APP)
    check("guide me to Settings stays GUIDE_TO", VoiceCommands.match("guide me to Settings"), VoiceCommand.GUIDE_TO)
    check("guide me through the settings stays GUIDE_TO (destination, not process)", VoiceCommands.match("guide me through the settings"), VoiceCommand.GUIDE_TO)
    check("open YouTube and Spotify stays LAUNCH_APP (no action verb)", VoiceCommands.match("open YouTube and Spotify"), VoiceCommand.LAUNCH_APP)
    check("show me how to stays DEMO_SEQUENCE", VoiceCommands.match("show me how to turn on dark mode"), VoiceCommand.DEMO_SEQUENCE)
    check("help me do the washing stays GUIDE_TO", VoiceCommands.match("help me do the washing"), VoiceCommand.GUIDE_TO)
    check("do not disturb is not a plan", VoiceCommands.match("do not disturb"), null)
    // ---- Stage 2D: transparency extraction ----
    checkTrue("show me what you're doing is transparent", VoiceCommands.extractTransparency("open YouTube Music and play a song by Davido, show me what you're doing"))
    checkTrue("plain instruction is not transparent", !VoiceCommands.extractTransparency("open YouTube Music and play a song by Davido"))
    checkTrue("show me how to is not transparency", !VoiceCommands.extractTransparency("show me how to turn on dark mode"))
    checkTrue("let me see is transparent", VoiceCommands.extractTransparency("let me see, open YouTube Music and play a song by Davido"))
    checkExtract("plan goal strips show-me-what-you're-doing", VoiceCommands.extractPlanGoal("open YouTube Music and play a song by Davido, show me what you're doing"), "open YouTube Music and play a song by Davido")
    checkExtract("plan goal strips leading transparency phrase", VoiceCommands.extractPlanGoal("show me what you're doing, open YouTube Music and play a song by Davido"), "open YouTube Music and play a song by Davido")
    checkExtract("plan goal strips let me see", VoiceCommands.extractPlanGoal("let me see, open YouTube Music and play a song by Davido"), "open YouTube Music and play a song by Davido")
    checkExtract("plan goal keeps verbatim casing", VoiceCommands.extractPlanGoal("open YouTube Music and play a song by Davido"), "open YouTube Music and play a song by Davido")
    checkExtract("plan goal keeps show-me-how-to intact", VoiceCommands.extractPlanGoal("show me how to turn on dark mode"), "show me how to turn on dark mode")

    // ---- Task-record fix (2026-11-07): polite prefixes must not
    // knock a composite out of PLAN_TASK, and no extractor may
    // swallow the second clause as part of the app name. ----
    check("please open composite routes to PLAN_TASK", VoiceCommands.match("please open YouTube Music and play music"), VoiceCommand.PLAN_TASK)
    check("can you open composite routes to PLAN_TASK", VoiceCommands.match("can you open YouTube Music and play music"), VoiceCommand.PLAN_TASK)
    check("please open search composite routes to PLAN_TASK", VoiceCommands.match("please search for Spider-Man in YouTube and open the first result"), VoiceCommand.PLAN_TASK)
    checkExtract("plan app keeps casing", VoiceCommands.extractPlanApp("open YouTube Music and play a song by Davido"), "YouTube Music")
    checkExtract("plan app from please composite", VoiceCommands.extractPlanApp("please open YouTube Music and play music"), "YouTube Music")
    checkExtract("plan app from can-you composite", VoiceCommands.extractPlanApp("can you open YouTube Music and play music"), "YouTube Music")
    checkExtract("plan app from search composite", VoiceCommands.extractPlanApp("search for Spider-Man in YouTube and open the first result"), "YouTube")
    checkExtract("plan app from plain launch", VoiceCommands.extractPlanApp("open YouTube Music"), "YouTube Music")
    checkExtract("plan app bare tail is empty", VoiceCommands.extractPlanApp("play music"), "")
    checkExtract("plan app bare search is empty", VoiceCommands.extractPlanApp("search for Davido"), "")
    checkExtract("launch app truncates the tail", VoiceCommands.extractLaunchApp("open YouTube Music and play music"), "youtube music")
    checkExtract("launch app keeps and inside a name", VoiceCommands.extractLaunchApp("open Tom and Jerry"), "tom and jerry")
    checkExtract("launch app can-you truncates the tail", VoiceCommands.extractLaunchApp("can you open YouTube Music and play music"), "youtube music")

    // ---- MASTER F1a: matchAmbiguous must NOT hijack utterances that
    // name an app or an action — the mode question fires only for
    // genuinely bare mode requests. ----
    checkAmb("go to Google Play Store is not ambiguous", "go to Google Play Store", false)
    checkAmb("go to my DM is not ambiguous", "go to my DM", false)
    checkAmb("switch to YouTube is not ambiguous", "switch to YouTube", false)
    checkAmb("enable dark mode is not ambiguous", "enable dark mode", false)
    checkAmb("activate dark mode is not ambiguous", "activate dark mode", false)
    checkAmb("take me to YouTube is not ambiguous", "take me to YouTube", false)
    checkAmb("switch it IS ambiguous (bare mode request)", "switch it", true)
    checkAmb("change mode IS ambiguous (bare mode request)", "change mode", true)
    checkAmb("activate it IS ambiguous (bare mode request)", "activate it", true)
    check("activate orb mode is MODE_ORB, never the mode question", VoiceCommands.match("activate orb mode"), VoiceCommand.MODE_ORB)
    check("go to orb mode stays MODE_ORB", VoiceCommands.match("go to orb mode"), VoiceCommand.MODE_ORB)
    checkTrue("go to Google Play Store has no mode clarification", VoiceCommands.clarificationFor("go to Google Play Store") != "Which mode — Orb, Character, or Invisible?")

    // ---- MASTER F1b: GO_TO_APP — "go to X" / "take me to X" /
    // "switch to X" resolve as launch, wired like LAUNCH_APP. ----
    check("go to YouTube is GO_TO_APP", VoiceCommands.match("go to YouTube"), VoiceCommand.GO_TO_APP)
    check("take me to YouTube is GO_TO_APP", VoiceCommands.match("take me to YouTube"), VoiceCommand.GO_TO_APP)
    check("switch to YouTube is GO_TO_APP", VoiceCommands.match("switch to YouTube"), VoiceCommand.GO_TO_APP)
    check("go to Google Play Store is GO_TO_APP", VoiceCommands.match("go to Google Play Store"), VoiceCommand.GO_TO_APP)
    checkExtract("go-to strips leading my", VoiceCommands.extractGoToApp("go to my DM"), "dm")
    checkExtract("go-to strips leading the", VoiceCommands.extractGoToApp("go to the settings"), "settings")
    checkExtract("go-to keeps the app name", VoiceCommands.extractGoToApp("go to YouTube"), "youtube")
    checkTrue("go to orb mode is not GO_TO_APP", VoiceCommands.match("go to orb mode") != VoiceCommand.GO_TO_APP)

    // ---- MASTER F1c: leading words before open/launch/go to. ----
    check("let's go open YouTube for me is LAUNCH_APP", VoiceCommands.match("let's go open YouTube for me"), VoiceCommand.LAUNCH_APP)
    check("please open YouTube is LAUNCH_APP", VoiceCommands.match("please open YouTube"), VoiceCommand.LAUNCH_APP)
    check("could you open YouTube is LAUNCH_APP", VoiceCommands.match("could you open YouTube"), VoiceCommand.LAUNCH_APP)
    check("I want you to open YouTube is LAUNCH_APP", VoiceCommands.match("I want you to open YouTube"), VoiceCommand.LAUNCH_APP)
    check("I said you should open YouTube is LAUNCH_APP", VoiceCommands.match("I said you should open YouTube"), VoiceCommand.LAUNCH_APP)
    check("can you open YouTube is LAUNCH_APP", VoiceCommands.match("can you open YouTube"), VoiceCommand.LAUNCH_APP)
    check("let's go composite stays PLAN_TASK", VoiceCommands.match("let's go open YouTube Music and play music"), VoiceCommand.PLAN_TASK)
    checkTrue("let's go open is a plan task", VoiceCommands.isPlanTask("let's go open YouTube Music and play music"))

    // ---- MASTER F1d: deterministic console follow-ups — scroll,
    // back, tap, enter — never fall through to the engine. ----
    check("scroll down is SCROLL", VoiceCommands.match("scroll down"), VoiceCommand.SCROLL)
    check("scroll up is SCROLL", VoiceCommands.match("scroll up"), VoiceCommand.SCROLL)
    check("scroll down a bit is SCROLL", VoiceCommands.match("scroll down a bit"), VoiceCommand.SCROLL)
    checkExtract("scroll direction down", VoiceCommands.extractScrollDirection("scroll down") ?: "", "down")
    checkExtract("scroll direction up", VoiceCommands.extractScrollDirection("scroll up") ?: "", "up")
    checkExtract("scroll direction down a bit", VoiceCommands.extractScrollDirection("scroll down a bit") ?: "", "down")
    checkTrue("scroll left is not a scroll command (it is a swipe)", VoiceCommands.extractScrollDirection("scroll left") == null)
    check("go back is BACK", VoiceCommands.match("go back"), VoiceCommand.BACK)
    check("tap the first result is TAP", VoiceCommands.match("tap the first result"), VoiceCommand.TAP)
    check("click on the first result is TAP", VoiceCommands.match("click on the first result"), VoiceCommand.TAP)
    checkExtract("tap target", VoiceCommands.extractTapTarget("tap the first result"), "the first result")
    check("click on enter is ENTER", VoiceCommands.match("click on enter"), VoiceCommand.ENTER)
    check("press enter is ENTER", VoiceCommands.match("press enter"), VoiceCommand.ENTER)
    check("hit enter is ENTER", VoiceCommands.match("hit enter"), VoiceCommand.ENTER)
    check("press the enter key is ENTER", VoiceCommands.match("press the enter key"), VoiceCommand.ENTER)
    check("bare enter is ENTER", VoiceCommands.match("enter"), VoiceCommand.ENTER)
    checkTrue("enter the code is NOT press-enter", !VoiceCommands.isPressEnter("enter the code"))
    check("enter the code matches no command", VoiceCommands.match("enter the code"), null)

    // ---- MASTER F2a: trailing filler is stripped from the
    // extracted app name, in launch and search. ----
    checkExtract("launch strips for me", VoiceCommands.extractLaunchApp("open YouTube for me"), "youtube")
    checkExtract("launch strips now", VoiceCommands.extractLaunchApp("open YouTube now"), "youtube")
    checkExtract("launch strips for me please", VoiceCommands.extractLaunchApp("open YouTube for me please"), "youtube")
    checkExtract("launch strips right now", VoiceCommands.extractLaunchApp("open YouTube right now"), "youtube")
    checkExtract("launch strips please", VoiceCommands.extractLaunchApp("open YouTube please"), "youtube")
    checkExtract("search strips for me", VoiceCommands.extractSearchApp("search for Davido in YouTube for me"), "youtube")
    checkExtract("search strips now", VoiceCommands.extractSearchApp("search for Davido in YouTube now"), "youtube")

    // ---- Device-test failures (2026-11-11, D-04): "confirm your
    // screen" is a real command, never the engine. ----
    check("confirm your screen routes to DESCRIBE_SCREEN", VoiceCommands.match("confirm your screen"), VoiceCommand.DESCRIBE_SCREEN)
    check("confirm screen routes to DESCRIBE_SCREEN", VoiceCommands.match("confirm screen"), VoiceCommand.DESCRIBE_SCREEN)
    check("what's on my screen routes to DESCRIBE_SCREEN", VoiceCommands.match("what's on my screen"), VoiceCommand.DESCRIBE_SCREEN)
    check("what is on my screen routes to DESCRIBE_SCREEN", VoiceCommands.match("what is on my screen"), VoiceCommand.DESCRIBE_SCREEN)
    check("what do you see routes to DESCRIBE_SCREEN", VoiceCommands.match("what do you see"), VoiceCommand.DESCRIBE_SCREEN)
    check("show me what you see routes to DESCRIBE_SCREEN", VoiceCommands.match("show me what you see"), VoiceCommand.DESCRIBE_SCREEN)
    check("describe the screen routes to DESCRIBE_SCREEN", VoiceCommands.match("describe the screen"), VoiceCommand.DESCRIBE_SCREEN)
    check("what can you see routes to DESCRIBE_SCREEN", VoiceCommands.match("what can you see"), VoiceCommand.DESCRIBE_SCREEN)

    // ---- Device-test failures (2026-11-11, D-05): "the task"
    // refers to the task already given — including STT "tax". ----
    checkTrue("execute the task is a task reference", VoiceCommands.isTaskReference("execute the task"))
    checkTrue("execute the task I give to you is a task reference", VoiceCommands.isTaskReference("execute the task I give to you"))
    checkTrue("loom execute the tax I give to you is a task reference", VoiceCommands.isTaskReference("loom execute the tax I give to you"))
    checkTrue("I'm giving you a task do it is a task reference", VoiceCommands.isTaskReference("I'm giving you a task do it"))
    checkTrue("I'm giving you a tax do it is a task reference", VoiceCommands.isTaskReference("I'm giving you a tax do it"))
    checkTrue("do it is a task reference", VoiceCommands.isTaskReference("do it"))
    checkTrue("do the task is a task reference", VoiceCommands.isTaskReference("do the task"))
    checkTrue("run the task is a task reference", VoiceCommands.isTaskReference("run the task"))
    checkTrue("bare tax is NOT a task reference", !VoiceCommands.isTaskReference("what is the tax rate"))
    checkTrue("do it later is not a bare do-it", !VoiceCommands.isTaskReference("do it later please"))

    // ---- Utterance fix (2026-11-11): turn-consuming commands only
    // match SHORT utterances (≤ 10 words). A long description that
    // happens to contain the phrase goes to the engine (match == null),
    // never swallowed as the command. EXCEPTION (fix 2, 2026-11-13,
    // F1-DEFECT-1): SHUTDOWN is exempt — David's binding rule is
    // immediate shutdown at any length, so a long shutdown
    // utterance matches SHUTDOWN and never reaches the
    // clarification question. The other commands keep the guard. ----
    check("long utterance with shut down inside IS SHUTDOWN (fix 2 exemption)", VoiceCommands.match("I was telling you about the video where I said shut down the app yesterday"), VoiceCommand.SHUTDOWN)
    check("long utterance with stop listening inside is not STOP_LISTENING", VoiceCommands.match("please stop listening to the music and tell me about the song I heard"), null)
    check("long utterance with stop doing that inside is not HALT_SEQUENCE", VoiceCommands.match("I kept saying stop doing that over and over while you were talking to me"), null)
    check("long utterance with shut down voice inside is not END_VOICE", VoiceCommands.match("I was asking you to shut down voice chat yesterday because the music was too loud"), null)
    check("long shut-down-low IS SHUTDOWN (fix 2: exact arm fires, length guard lifted)", VoiceCommands.match("I was telling you about the time we had to shut down low the whole system"), VoiceCommand.SHUTDOWN)
    check("9-word shut down still matches SHUTDOWN", VoiceCommands.match("I told you yesterday to shut down the app"), VoiceCommand.SHUTDOWN)
    // Fix 2 (2026-11-13, F1-DEFECT-1): a long shutdown utterance
    // is a shutdown command — see the updated cases above.
    check("long shutdown utterance matches SHUTDOWN", VoiceCommands.match("loom please shut down completely now I am finished using you for today thank you"), VoiceCommand.SHUTDOWN)
    check("long negated shutdown never matches SHUTDOWN", VoiceCommands.match("please do not shut down loom I am still using you for something important today"), null)
    check("11-word shut down matches SHUTDOWN (fix 2: no length guard)", VoiceCommands.match("I told you yesterday to shut down the app please now"), VoiceCommand.SHUTDOWN)
    check("short stop doing that still matches HALT_SEQUENCE", VoiceCommands.match("stop doing that"), VoiceCommand.HALT_SEQUENCE)
    check("short stop listening still matches STOP_LISTENING", VoiceCommands.match("stop listening"), VoiceCommand.STOP_LISTENING)
    check("short shut down voice still matches END_VOICE", VoiceCommands.match("shut down voice"), VoiceCommand.END_VOICE)

    println("VoiceCommandsTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
