package com.loom.assistant.presentation.home

import com.loom.assistant.platform.services.ShowPointerFailure
import com.loom.assistant.presentation.designsystem.LoomDestination

/**
 * Accessibility fix (Nov 5) — JVM checks for the Home one-tap pointer
 * control ([HomePointerControl]). Plain-main() pattern, same toolchain as
 * run-voice-tests.sh. Run: run-home-tests.sh
 *
 * What is tested here is the decision Home shows: tap → seam called →
 * true shows the plain "on" text, false shows the plain failure text;
 * "See what LOOM sees" opens the existing view; a11y-off shows the plain
 * sentence + settings link. The Android half (service start intent,
 * Settings.Secure read, system settings intent) lives in
 * HomeScreenView and is verified on device; the strings it displays come
 * from here, so a wrong string fails here first.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) passed++
    else {
        failed++
        println("FAIL: $name $detail")
    }
}

fun main() {
    // (a) tap -> seam returned true -> plain "on" text.
    val on = HomePointerControl.outcome(a11yOn = true, serviceStarted = true, rendered = true)
    check("true shows the plain on text", on.statusText == HomePointerControl.POINTER_ON_TEXT)
    check("on text is the approved wording", on.statusText == "Pointer is on — 3 cursors, follow me.")
    check("on text offers no settings link", !on.showAccessibilitySettings)

    // (a) tap -> seam returned false -> plain failure text, never silence,
    // never a claim, never jargon.
    val notShown = HomePointerControl.outcome(a11yOn = true, serviceStarted = true, rendered = false)
    check("false shows the plain failure text", notShown.statusText == HomePointerControl.SHOW_FAILED_TEXT)
    check("failure text says it is not on screen", notShown.statusText.contains("isn't on screen"))
    check("failure text tells the user to try again", notShown.statusText.contains("Try again"))
    check("failure offers no settings link", !notShown.showAccessibilitySettings)

    // (a) service could not even be started -> its own plain sentence.
    val startFailed = HomePointerControl.outcome(a11yOn = true, serviceStarted = false, rendered = false)
    check("start failure has its own plain text", startFailed.statusText == HomePointerControl.START_FAILED_TEXT)
    check("start failure says it is not on screen", startFailed.statusText.contains("isn't on screen"))
    check("start failure does not claim the pointer is on", !startFailed.statusText.contains("Pointer is on"))

    // (b) "See what LOOM sees" opens the EXISTING view, not a rebuild.
    val dest = HomePointerControl.seeWhatLoomSeesDestination()
    check("see-what destination is the existing WhatLoomSees view", dest == LoomDestination.WhatLoomSees)
    check("see-what route is what-loom-sees", dest.route == "what-loom-sees")
    check("see-what label is What LOOM Sees", dest.label == "What LOOM Sees")

    // (c) a11y off -> one plain sentence + the settings link.
    val a11yOff = HomePointerControl.outcome(a11yOn = false, serviceStarted = false, rendered = null)
    check("a11y-off shows the plain sentence", a11yOff.statusText == HomePointerControl.A11Y_OFF_TEXT)
    check("a11y-off is one sentence about turning LOOM on", a11yOff.statusText.startsWith("LOOM needs to be turned on"))
    check("a11y-off names Accessibility", a11yOff.statusText.contains("Accessibility"))
    check("a11y-off offers the settings link", a11yOff.showAccessibilitySettings)
    // A11y-off wins even if a stale render result is passed in.
    val a11yOffStale = HomePointerControl.outcome(a11yOn = false, serviceStarted = true, rendered = true)
    check("a11y-off wins over a stale true", a11yOffStale.statusText == HomePointerControl.A11Y_OFF_TEXT)
    check("a11y-off wins over a stale true, link shown", a11yOffStale.showAccessibilitySettings)

    // Pointer failure fix (2026-11-05): the reason overload — each
    // failing step gets its own plain sentence, never the collapsed one.
    val expected = mapOf(
        ShowPointerFailure.NO_HOST to HomePointerControl.NO_HOST_TEXT,
        ShowPointerFailure.TIMEOUT to HomePointerControl.TIMEOUT_TEXT,
        ShowPointerFailure.POLICY_FAILED to HomePointerControl.POLICY_FAILED_TEXT,
        ShowPointerFailure.HOST_REFUSED to HomePointerControl.HOST_REFUSED_TEXT,
        ShowPointerFailure.NOT_ATTACHED to HomePointerControl.NOT_ATTACHED_TEXT,
        ShowPointerFailure.NO_INTERACTION to HomePointerControl.NO_INTERACTION_TEXT,
        ShowPointerFailure.GATE_REFUSED to HomePointerControl.GATE_REFUSED_TEXT,
    )
    for ((failure, text) in expected) {
        val o = HomePointerControl.outcome(
            a11yOn = true, serviceStarted = true, rendered = false, failure = failure,
        )
        check("reason $failure shows its own sentence", o.statusText == text, "got=[${o.statusText}]")
        check("reason $failure is not the generic sentence", o.statusText != HomePointerControl.SHOW_FAILED_TEXT)
        check("reason $failure never claims the pointer is on", !o.statusText.contains("Pointer is on"))
    }
    // The cfdb8fb case, named: flag on, service started, render never
    // verified because the host was not live.
    val noHost = HomePointerControl.outcome(
        a11yOn = true, serviceStarted = true, rendered = false,
        failure = ShowPointerFailure.NO_HOST,
    )
    check("NO_HOST names the helper service", noHost.statusText.contains("helper service"))
    check("NO_HOST tells the user to turn LOOM off and on", noHost.statusText.contains("turn LOOM off and on again"))
    check("NO_HOST names Accessibility", noHost.statusText.contains("Accessibility"))
    check("NO_HOST offers the settings link", noHost.showAccessibilitySettings)
    // Only NO_HOST offers the link — every other reason is fixed by
    // trying again, not by opening Settings.
    for ((failure, _) in expected) {
        if (failure == ShowPointerFailure.NO_HOST) continue
        val o = HomePointerControl.outcome(
            a11yOn = true, serviceStarted = true, rendered = false, failure = failure,
        )
        check("reason $failure offers no settings link", !o.showAccessibilitySettings)
    }
    // TIMEOUT tells the user the pointer is still starting.
    val timeout = HomePointerControl.outcome(
        a11yOn = true, serviceStarted = true, rendered = false,
        failure = ShowPointerFailure.TIMEOUT,
    )
    check("TIMEOUT says still starting", timeout.statusText.contains("still starting"))
    check("TIMEOUT says tap again", timeout.statusText.contains("tap Show pointer again"))
    // Precedence: a11y-off wins over a reason; a verified render wins
    // over a reason; a reason wins over a start failure; no reason
    // falls back to the generic sentence / start-failure sentence.
    val offWithReason = HomePointerControl.outcome(
        a11yOn = false, serviceStarted = true, rendered = false,
        failure = ShowPointerFailure.NO_HOST,
    )
    check("a11y-off wins over a reason", offWithReason.statusText == HomePointerControl.A11Y_OFF_TEXT)
    val onWithReason = HomePointerControl.outcome(
        a11yOn = true, serviceStarted = true, rendered = true,
        failure = ShowPointerFailure.NO_HOST,
    )
    check("verified render wins over a reason", onWithReason.statusText == HomePointerControl.POINTER_ON_TEXT)
    check("verified render with reason offers no link", !onWithReason.showAccessibilitySettings)
    val reasonBeatsStart = HomePointerControl.outcome(
        a11yOn = true, serviceStarted = false, rendered = false,
        failure = ShowPointerFailure.TIMEOUT,
    )
    check("reason wins over start failure", reasonBeatsStart.statusText == HomePointerControl.TIMEOUT_TEXT)
    val noReasonStart = HomePointerControl.outcome(
        a11yOn = true, serviceStarted = false, rendered = false, failure = null,
    )
    check("no reason + start failure keeps its sentence", noReasonStart.statusText == HomePointerControl.START_FAILED_TEXT)
    val noReasonShown = HomePointerControl.outcome(
        a11yOn = true, serviceStarted = true, rendered = false, failure = null,
    )
    check("no reason keeps the generic sentence", noReasonShown.statusText == HomePointerControl.SHOW_FAILED_TEXT)

    // decideLiveness: pure function of flag + live binding. The flag
    // alone lies after a kill — only flag + live means LIVE.
    check(
        "flag off + host dead is FLAG_OFF",
        HomePointerControl.decideLiveness(a11yFlagOn = false, hostLive = false) ==
            HomePointerControl.PointerLiveness.FLAG_OFF,
    )
    check(
        "flag off + host live is still FLAG_OFF",
        HomePointerControl.decideLiveness(a11yFlagOn = false, hostLive = true) ==
            HomePointerControl.PointerLiveness.FLAG_OFF,
    )
    check(
        "flag on + host live is LIVE",
        HomePointerControl.decideLiveness(a11yFlagOn = true, hostLive = true) ==
            HomePointerControl.PointerLiveness.LIVE,
    )
    check(
        "flag on + host dead is FLAG_ON_BUT_NOT_LIVE",
        HomePointerControl.decideLiveness(a11yFlagOn = true, hostLive = false) ==
            HomePointerControl.PointerLiveness.FLAG_ON_BUT_NOT_LIVE,
    )

    // resolvePollFailure: the poll loop's final-reason decision, pure.
    check(
        "poll rendered -> no failure",
        HomePointerControl.resolvePollFailure(
            rendered = true, serviceStarted = true,
            attemptedSeam = true, lastFailure = ShowPointerFailure.HOST_REFUSED,
        ) == null,
    )
    check(
        "poll never started -> no failure (start sentence covers it)",
        HomePointerControl.resolvePollFailure(
            rendered = false, serviceStarted = false,
            attemptedSeam = false, lastFailure = null,
        ) == null,
    )
    check(
        "poll seam never ran -> TIMEOUT",
        HomePointerControl.resolvePollFailure(
            rendered = false, serviceStarted = true,
            attemptedSeam = false, lastFailure = null,
        ) == ShowPointerFailure.TIMEOUT,
    )
    check(
        "poll last seam reason is kept",
        HomePointerControl.resolvePollFailure(
            rendered = false, serviceStarted = true,
            attemptedSeam = true, lastFailure = ShowPointerFailure.HOST_REFUSED,
        ) == ShowPointerFailure.HOST_REFUSED,
    )
    check(
        "poll seam ran but no reason recorded -> TIMEOUT",
        HomePointerControl.resolvePollFailure(
            rendered = false, serviceStarted = true,
            attemptedSeam = true, lastFailure = null,
        ) == ShowPointerFailure.TIMEOUT,
    )

    // Jargon sweep: no user-facing string may leak internal state words.
    val banned = listOf("attached", "detached", "anchored", "overlay", "guidance window")
    val allText = listOf(
        HomePointerControl.POINTER_ON_TEXT,
        HomePointerControl.A11Y_OFF_TEXT,
        HomePointerControl.START_FAILED_TEXT,
        HomePointerControl.SHOW_FAILED_TEXT,
        HomePointerControl.NO_HOST_TEXT,
        HomePointerControl.TIMEOUT_TEXT,
        HomePointerControl.POLICY_FAILED_TEXT,
        HomePointerControl.HOST_REFUSED_TEXT,
        HomePointerControl.NOT_ATTACHED_TEXT,
        HomePointerControl.NO_INTERACTION_TEXT,
        HomePointerControl.GATE_REFUSED_TEXT,
        HomePointerControl.TURN_BACK_ON_STEPS,
        HomePointerControl.TURN_ON_STEPS,
    )
    for (text in allText) {
        for (word in banned) {
            check("no jargon '$word' in user text", !text.lowercase().contains(word), "text=[$text]")
        }
    }

    // Binding survival (2026-11-06): the rebind wait is bounded and
    // the not-live outcomes carry the exact toggle steps for Home.
    check("rebind wait is 3 seconds", HomePointerControl.REBIND_WAIT_MS == 3_000L)
    check("rebind poll is 250ms", HomePointerControl.REBIND_POLL_MS == 250L)
    check("rebind wait is longer than one poll", HomePointerControl.REBIND_WAIT_MS > HomePointerControl.REBIND_POLL_MS)
    check(
        "NO_HOST outcome carries the turn-back-on steps",
        noHost.stepsText == HomePointerControl.TURN_BACK_ON_STEPS,
    )
    check(
        "turn-back-on steps name LOOM in the list",
        HomePointerControl.TURN_BACK_ON_STEPS.contains("Tap LOOM in the list"),
    )
    check(
        "turn-back-on steps say off then on again",
        HomePointerControl.TURN_BACK_ON_STEPS.contains("Turn LOOM off, then turn it on again"),
    )
    check(
        "turn-back-on steps end back here",
        HomePointerControl.TURN_BACK_ON_STEPS.contains("Come back here and tap Show pointer"),
    )
    check(
        "a11y-off outcome carries the turn-on steps",
        a11yOff.stepsText == HomePointerControl.TURN_ON_STEPS,
    )
    check(
        "turn-on steps say turn LOOM on",
        HomePointerControl.TURN_ON_STEPS.contains("Turn LOOM on."),
    )
    check(
        "success outcome carries no steps",
        on.stepsText == null,
    )
    check(
        "generic failure carries no steps",
        notShown.stepsText == null,
    )
    check(
        "TIMEOUT outcome carries no steps",
        timeout.stepsText == null,
    )

    // (d) stopped by choice (Nov 6, refused-start fix): a manual
    // start after SHUT DOWN / DEACTIVATE is refused — Home says so
    // in plain words instead of the generic "try again" text.
    check(
        "marker plus not-active is stopped by choice",
        HomePointerControl.isStoppedByChoice(markerPresent = true, lifecycleActive = false),
    )
    check(
        "marker plus active is not stopped by choice (plain STOP)",
        !HomePointerControl.isStoppedByChoice(markerPresent = true, lifecycleActive = true),
    )
    check(
        "no marker plus not-active is not stopped by choice",
        !HomePointerControl.isStoppedByChoice(markerPresent = false, lifecycleActive = false),
    )
    check(
        "no marker plus active is not stopped by choice",
        !HomePointerControl.isStoppedByChoice(markerPresent = false, lifecycleActive = true),
    )
    val stopped = HomePointerControl.stoppedByChoiceOutcome()
    check(
        "stopped-by-choice shows the plain sentence",
        stopped.statusText == HomePointerControl.STOPPED_BY_CHOICE_TEXT,
    )
    check(
        "stopped-by-choice names the choice",
        stopped.statusText.contains("turned off by your choice"),
    )
    check(
        "stopped-by-choice names the launcher way back",
        stopped.statusText.contains("Open the app again from the launcher"),
    )
    check(
        "stopped-by-choice never says try again",
        !stopped.statusText.contains("Try again"),
    )
    check(
        "stopped-by-choice offers no settings link",
        !stopped.showAccessibilitySettings,
    )
    check(
        "stopped-by-choice carries no steps",
        stopped.stepsText == null,
    )

    println("")
    if (failed == 0) println("ALL $passed TESTS PASSED")
    else println("$passed passed, $failed failed")
}
