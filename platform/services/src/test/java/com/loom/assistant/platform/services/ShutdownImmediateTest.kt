package com.loom.assistant.platform.services

import com.loom.assistant.core.common.VoiceCommand
import com.loom.assistant.core.common.VoiceCommands
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState

/**
 * Device-failure fix (2026-11-13, F1) — immediate shutdown, NO
 * confirmation. Replaces the old confirmation-protocol tests: the
 * protocol class is DELETED, and "shut down" goes straight to the
 * teardown on every command path.
 *
 * Proves, at the JVM-testable seams:
 *  1. The classifier matches every shutdown variant as SHUTDOWN
 *     (the command the hosts execute immediately, with no
 *     confirmation state anywhere in the path).
 *  2. Negated utterances NEVER match SHUTDOWN — with no
 *     confirmation question, the negation guard is the safety
 *     boundary ("don't shut down" must chat, never shut down).
 *  3. The voice/pointer/assistant-mode exclusions still hold.
 *  4. Stranded-state recovery: SHUTTING_DOWN with no destructive
 *     step recovers (the MainActivity launch caller); a completed
 *     SHUT_DOWN never recovers; a destructive step blocks recovery.
 *
 * What this file CANNOT prove (device tests DF-01 only): that the
 * hosts actually call the teardown, that the teardown runs, or that
 * voice works after reopening. The harness proves the classifier
 * and the lifecycle semantics; David's phone proves the shutdown.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name $detail")
    }
}

fun main() {
    // 1. Shutdown variants match SHUTDOWN.
    for (t in listOf(
        "shut down", "shutdown", "shut it down", "shut down loom",
        "turn loom off", "kill loom", "power off", "switch off",
        "turn off", "turn it off", "loom shutdown",
    )) {
        check(
            "shutdown variant matches: \"$t\"",
            VoiceCommands.match(t) == VoiceCommand.SHUTDOWN,
            "got ${VoiceCommands.match(t)}",
        )
    }

    // 2. Negations never match SHUTDOWN.
    for (t in listOf(
        "don't shut down", "do not shut down", "never shut down",
        "don't shut down loom", "dont shut down",
        "don\u2019t shut down",
        "i'm not asking you to shut down",
        "don't turn it off", "do not turn off",
    )) {
        check(
            "negated shutdown never matches: \"$t\"",
            VoiceCommands.match(t) != VoiceCommand.SHUTDOWN,
            "got ${VoiceCommands.match(t)}",
        )
    }

    // 3. Exclusions still hold.
    check(
        "\"shut down voice\" is END_VOICE, never SHUTDOWN",
        VoiceCommands.match("shut down voice") == VoiceCommand.END_VOICE,
    )
    check(
        "\"turn off the pointer\" is never SHUTDOWN",
        VoiceCommands.match("turn off the pointer") != VoiceCommand.SHUTDOWN,
    )
    check(
        "\"turn off assistant mode\" is DEACTIVATE, never SHUTDOWN",
        VoiceCommands.match("turn off assistant mode") ==
            VoiceCommand.DEACTIVATE_ASSISTANT_MODE,
    )
    check(
        "long description containing \"shut down\" never matches",
        VoiceCommands.match(
            "yesterday I told you to shut down the whole thing and you did not listen to me at all",
        ) != VoiceCommand.SHUTDOWN,
    )

    // Fix 2 (2026-11-13, F1-DEFECT-1): negated shutdown text never
    // produces the shutdown clarification question, and a long
    // shutdown utterance matches SHUTDOWN (immediate), never the
    // question. Only "shut down the pointer" still clarifies.
    check(
        "clarificationFor(don't shut down) is null",
        VoiceCommands.clarificationFor("don't shut down") == null,
    )
    check(
        "clarificationFor(do not shut down) is null",
        VoiceCommands.clarificationFor("do not shut down") == null,
    )
    check(
        "clarificationFor(curly-apostrophe don't shut down) is null",
        VoiceCommands.clarificationFor("don\u2019t shut down") == null,
    )
    check(
        "clarificationFor(never shut down) is null",
        VoiceCommands.clarificationFor("never shut down") == null,
    )
    check(
        "long shutdown utterance matches SHUTDOWN",
        VoiceCommands.match(
            "loom please shut down completely now I am finished using you for today thank you",
        ) == VoiceCommand.SHUTDOWN,
    )
    check(
        "clarificationFor(long shutdown utterance) is null (a command now)",
        VoiceCommands.clarificationFor(
            "loom please shut down completely now I am finished using you for today thank you",
        ) == null,
    )
    check(
        "clarificationFor(shut down the pointer) still asks (pointer case only)",
        VoiceCommands.clarificationFor("shut down the pointer") ==
            "Do you want me to shut down LOOM completely, or just end the voice session?",
    )

    // 4. Stranded-state recovery semantics.
    LifecycleController.resetForTest()
    check(
        "recovery refused from ACTIVE (not stranded)",
        !LifecycleController.requestShutdownRecovery(),
    )
    LifecycleController.resetForTest()
    check(
        "ACTIVE→SHUTTING_DOWN for the stranded case",
        LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown"),
    )
    check(
        "stranded SHUTTING_DOWN recovers (no destructive step)",
        LifecycleController.requestShutdownRecovery(),
    )
    check(
        "recovered state is ACTIVE",
        LifecycleController.currentState() == LifecycleState.ACTIVE,
    )
    LifecycleController.resetForTest()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    LifecycleController.transitionTo(LifecycleState.SHUT_DOWN, "verified")
    check(
        "completed SHUT_DOWN never recovers (explicit shutdown stays shut)",
        !LifecycleController.requestShutdownRecovery(),
    )
    check(
        "state stays SHUT_DOWN after refused recovery",
        LifecycleController.currentState() == LifecycleState.SHUT_DOWN,
    )
    LifecycleController.resetForTest()
    LifecycleController.transitionTo(LifecycleState.SHUTTING_DOWN, "shutdown")
    LifecycleController.noteDestructiveStep("authgate-purge")
    check(
        "destructive step blocks recovery",
        !LifecycleController.requestShutdownRecovery(),
    )
    check(
        "state stays SHUTTING_DOWN after destructive refusal",
        LifecycleController.currentState() == LifecycleState.SHUTTING_DOWN,
    )

    println("ShutdownImmediateTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED")
}
