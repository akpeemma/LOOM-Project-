package com.loom.assistant.presentation.chat

import com.loom.assistant.core.common.PointerShowGate
import com.loom.assistant.core.common.VoiceCommand

/**
 * Blank-reply fix (2026-11-06) — pure-JVM checks for the chat turn rules
 * in ChatReplyGrounding.kt. Run: test-harness/run-chat-tests.sh
 * (No android.* imports, so no fakes are needed.)
 *
 * Replays David's exact messages through the turn logic:
 * - "move to the corner of my screen": the model answers with an
 *   action block and nothing else; when the block names no valid region
 *   (arg="CORNER") the turn must still store a real sentence, never a
 *   blank bubble.
 * - "yesterday now": an empty / whitespace-only reply must store the
 *   fallback sentence, never a blank bubble.
 * Plus the Settings-button predicate over every real reply that sends
 * David to Settings, and the helper grounding for the "service bound"
 * question.
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
    // ---- resolveChatTurn: David's exact messages ----
    // "move to the corner of my screen" — model emits an action-only
    // reply with an INVALID region ("CORNER" is not a region). The old
    // code stored "" (blank bubble). Now: the fallback sentence.
    val cornerInvalid = resolveChatTurn("[[LOOM_ACTION:ASSISTANT_MOVE arg=\"CORNER\"]]")
    check(
        "corner move, invalid region block -> fallback sentence",
        (cornerInvalid as? ChatTurnResolution.StoreText)?.text,
        CHAT_FALLBACK_REPLY,
    )
    // Same message, valid region: dispatches, no prose stored.
    val cornerValid = resolveChatTurn("[[LOOM_ACTION:ASSISTANT_MOVE arg=\"TOP_LEFT\"]]")
    checkTrue(
        "corner move, valid region block -> DispatchAction(ASSISTANT_MOVE)",
        cornerValid is ChatTurnResolution.DispatchAction &&
            (cornerValid as ChatTurnResolution.DispatchAction).action.command ==
            VoiceCommand.ASSISTANT_MOVE,
    )
    // "yesterday now" — empty and whitespace-only replies store fallback.
    check(
        "empty reply -> fallback sentence",
        (resolveChatTurn("") as? ChatTurnResolution.StoreText)?.text,
        CHAT_FALLBACK_REPLY,
    )
    check(
        "whitespace reply -> fallback sentence",
        (resolveChatTurn("   \n  ") as? ChatTurnResolution.StoreText)?.text,
        CHAT_FALLBACK_REPLY,
    )
    // A malformed action block with no arg at all: same blank hole.
    check(
        "GUIDE_TO with empty arg -> fallback sentence",
        (resolveChatTurn("[[LOOM_ACTION:GUIDE_TO arg=\"\"]]") as? ChatTurnResolution.StoreText)?.text,
        CHAT_FALLBACK_REPLY,
    )
    // Ordinary prose stores as-is, never rewritten.
    check(
        "ordinary prose -> stored as-is",
        (resolveChatTurn("Hello there, how can I help?") as? ChatTurnResolution.StoreText)?.text,
        "Hello there, how can I help?",
    )
    // Prose + valid block: dispatch wins; the prose is dropped by design
    // (the dispatch ack is the turn's only message).
    val prosePlusBlock = resolveChatTurn(
        "Moving it now. [[LOOM_ACTION:POINTER_SHOW]]",
    )
    checkTrue(
        "prose + valid block -> DispatchAction(POINTER_SHOW)",
        prosePlusBlock is ChatTurnResolution.DispatchAction &&
            (prosePlusBlock as ChatTurnResolution.DispatchAction).action.command ==
            VoiceCommand.POINTER_SHOW,
    )
    // A valid search block dispatches with its app.
    val search = resolveChatTurn(
        "[[LOOM_ACTION:SEARCH_IN_APP arg=\"cats\" app=\"YouTube\"]]",
    )
    checkTrue(
        "valid search block -> DispatchAction(SEARCH_IN_APP)",
        search is ChatTurnResolution.DispatchAction &&
            (search as ChatTurnResolution.DispatchAction).action.command ==
            VoiceCommand.SEARCH_IN_APP,
    )
    // The fallback sentence itself is never blank.
    checkTrue("fallback sentence is non-blank", CHAT_FALLBACK_REPLY.isNotBlank())

    // ---- replyNeedsSettingsButton: every real Settings reply ----
    checkTrue(
        "pointer show failure ack -> button",
        replyNeedsSettingsButton(PointerShowGate.NOT_RENDERED_ACK),
    )
    checkTrue(
        "launch helper-down reply -> button",
        replyNeedsSettingsButton(
            "The accessibility service isn't running, so I can't open " +
                "apps yet. Enable it in Settings → Accessibility → LOOM, " +
                "then try again.",
        ),
    )
    checkTrue(
        "assistant-move no-screen reply -> button",
        replyNeedsSettingsButton(
            "I couldn't move the assistant: I can't read the screen right " +
                "now — turn on LOOM in Settings → Accessibility → Device " +
                "Control to move the assistant.",
        ),
    )
    checkTrue(
        "activation permission reply -> button",
        replyNeedsSettingsButton(
            "I need the Display over other apps permission first — open " +
                "Settings to grant it for LOOM, then try again.",
        ),
    )
    checkTrue(
        "helper grounding suffix -> button",
        replyNeedsSettingsButton(
            "Right now LOOM's helper is switched off. To turn it on, open " +
                "Settings, under Accessibility, and switch LOOM on.",
        ),
    )
    // Negatives: no Settings direction, no button.
    checkTrue(
        "pointer shown ack -> no button",
        !replyNeedsSettingsButton(PointerShowGate.RENDERED_ACK),
    )
    checkTrue(
        "ordinary reply mentioning settings -> no button",
        !replyNeedsSettingsButton("Your settings look fine to me."),
    )
    checkTrue(
        "fallback sentence -> no button",
        !replyNeedsSettingsButton(CHAT_FALLBACK_REPLY),
    )
    checkTrue(
        "plain reply -> no button",
        !replyNeedsSettingsButton("Hello there, how can I help?"),
    )

    // ---- groundHelperAnswer: the "service bound" question ----
    val modelReply = "The service bound shows whether the helper is connected."
    val groundedOff = groundHelperAnswer(
        "what does this service bound represent",
        modelReply, helperOn = false, helperRunning = false,
    )
    checkTrue(
        "helper off: grounding names the state",
        groundedOff.contains("Right now LOOM's helper is switched off."),
    )
    checkTrue(
        "helper off: grounding gives the steps",
        groundedOff.contains(
            "To turn it on, open Settings, under Accessibility, and switch LOOM on.",
        ),
    )
    checkTrue(
        "helper off: grounding keeps the model's explanation",
        groundedOff.startsWith(modelReply),
    )
    checkTrue(
        "helper off: grounded reply gets the button",
        replyNeedsSettingsButton(groundedOff),
    )
    val groundedNotRunning = groundHelperAnswer(
        "what does this service bound represent",
        modelReply, helperOn = true, helperRunning = false,
    )
    checkTrue(
        "helper on but not running: grounding names the state",
        groundedNotRunning.contains(
            "Right now LOOM's helper is switched on but not running.",
        ),
    )
    checkTrue(
        "helper on but not running: grounding gives the steps",
        groundedNotRunning.contains(
            "To get it running, open Settings, under Accessibility, and turn LOOM off and on again.",
        ),
    )
    val groundedRunning = groundHelperAnswer(
        "what does this service bound represent",
        modelReply, helperOn = true, helperRunning = true,
    )
    checkTrue(
        "helper running: grounding names the state, no steps",
        groundedRunning.contains("Right now LOOM's helper is on and running.") &&
            !groundedRunning.contains("To turn it on") &&
            !groundedRunning.contains("To get it running"),
    )
    checkTrue(
        "helper running: no button when no steps",
        !replyNeedsSettingsButton(groundedRunning),
    )
    // Ordinary questions pass through untouched.
    check(
        "ordinary question -> reply untouched",
        groundHelperAnswer("what's the weather", "Sunny.", false, false),
        "Sunny.",
    )
    check(
        "helper question phrasing 'how do I turn on the helper' -> grounded",
        groundHelperAnswer("how do I turn on the helper", "Sure.", false, false)
            .contains("Right now LOOM's helper is switched off."),
        true,
    )

    println("ChatReplyGroundingTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
