package com.loom.assistant.execution.screenawareness

import android.graphics.Rect
import com.loom.assistant.core.common.MediaControl
import com.loom.assistant.core.common.MediaControlPhrases

/**
 * Session/state awareness (2026-11-11, David's device evidence
 * ~21:43 WAT + clarification ~21:44 WAT) — OBSERVE-FIRST routing
 * tests for media control commands.
 *
 * The defect: "pause" / "play" re-opened YouTube Music instead of
 * pausing/resuming the song already playing. The rule under test:
 * a control command resolves against the CURRENT screen — the
 * control is there, tap it there, no launch; the control is
 * genuinely absent, say so honestly, never a fresh play-search.
 * A named song ("play Davido") is NOT a control command — it stays
 * a search.
 *
 * Pure JVM tests against ScreenModel fixtures (no service, no
 * Android runtime beyond the Rect fake). Device proof is SA-01 /
 * SA-02 / SA-03 in NAV-SCREEN-DEVICE-TESTS.md.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private const val YTM_PKG = "com.google.android.apps.youtube.music"
private const val FB_PKG = "com.facebook.katana"

private fun ctl(id: String, label: String, role: ElementRole = ElementRole.BUTTON) =
    ScreenElement(
        id = id,
        text = label,
        // One label source only (real player buttons carry text OR
        // a content-description, not both — both would read
        // "Pause Pause" and break the exact-match control check).
        contentDescription = null,
        className = "android.widget.ImageButton",
        bounds = Rect(300, 1400, 420, 1520),
        role = role,
        enabled = true,
        isPassword = false,
    )

private fun ytmPlaying() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        ScreenElement(
            id = "title", text = "Fall — Davido", contentDescription = null,
            className = "android.widget.TextView", bounds = Rect(0, 1200, 720, 1300),
            role = ElementRole.TEXT, enabled = true, isPassword = false,
        ),
        ctl("pause-btn", "Pause"),
    ),
    screenBounds = Rect(0, 0, 720, 1640),
)

private fun ytmPaused() = ScreenModel(
    packageName = YTM_PKG,
    elements = listOf(
        ScreenElement(
            id = "title", text = "Fall — Davido", contentDescription = null,
            className = "android.widget.TextView", bounds = Rect(0, 1200, 720, 1300),
            role = ElementRole.TEXT, enabled = true, isPassword = false,
        ),
        ctl("play-btn", "Play"),
    ),
    screenBounds = Rect(0, 0, 720, 1640),
)

private fun fbVideoPlaying() = ScreenModel(
    packageName = FB_PKG,
    elements = listOf(
        ScreenElement(
            id = "player", text = null, contentDescription = null,
            className = "android.widget.VideoView", bounds = Rect(0, 200, 720, 900),
            role = ElementRole.OTHER, enabled = true, isPassword = false,
        ),
        ctl("pause-btn", "Pause"),
    ),
    screenBounds = Rect(0, 0, 720, 1640),
)

private fun emptyScreen() = ScreenModel(
    packageName = YTM_PKG,
    elements = emptyList(),
    screenBounds = Rect(0, 0, 720, 1640),
)

fun main() {
    // ---------- 1. the phrase table ----------
    checkEq(
        "match: 'pause the music' is PAUSE",
        MediaControlPhrases.match("pause the music"), MediaControl.PAUSE,
    )
    checkEq(
        "match: 'play the song again' is PLAY",
        MediaControlPhrases.match("play the song again"), MediaControl.PLAY,
    )
    checkEq(
        "match: 'resume the music' is PLAY",
        MediaControlPhrases.match("resume the music"), MediaControl.PLAY,
    )
    checkEq(
        "match: 'stop playing' is PAUSE",
        MediaControlPhrases.match("stop playing"), MediaControl.PAUSE,
    )
    checkEq("match: bare 'pause' is PAUSE", MediaControlPhrases.match("pause"), MediaControl.PAUSE)
    checkEq("match: bare 'play' is PLAY", MediaControlPhrases.match("play"), MediaControl.PLAY)
    checkEq(
        "match: 'please pause the music' is PAUSE",
        MediaControlPhrases.match("please pause the music"), MediaControl.PAUSE,
    )
    checkEq(
        "match: 'pause the music please' is PAUSE",
        MediaControlPhrases.match("pause the music please"), MediaControl.PAUSE,
    )
    checkEq(
        "match: 'play Davido' is NOT a control (named song stays a search)",
        MediaControlPhrases.match("play Davido"), null,
    )
    checkEq(
        "match: 'play music' is NOT a control (a new play request, not a resume)",
        MediaControlPhrases.match("play music"), null,
    )
    checkEq(
        "match: 'play the song' is NOT a control (no 'again'/'this' — a new play request)",
        MediaControlPhrases.match("play the song"), null,
    )
    checkEq(
        "match: 'play the song Fall' is NOT a control (named song)",
        MediaControlPhrases.match("play the song Fall"), null,
    )
    checkEq(
        "match: 'pause the meeting' is NOT a control",
        MediaControlPhrases.match("pause the meeting"), null,
    )
    checkEq(
        "match: 'stop' alone is NOT a control (bare stop stays HALT)",
        MediaControlPhrases.match("stop"), null,
    )

    // ---------- 2. routing on the current screen ----------
    val pauseRoute = MediaControlRouter.route(ytmPlaying(), MediaControl.PAUSE)
    check(
        "route: YTM playing + PAUSE taps on the current screen",
        pauseRoute is MediaControlRouter.ControlRoute.TapOnCurrentScreen,
    )
    checkEq(
        "route: the tapped element is the Pause button already there",
        (pauseRoute as? MediaControlRouter.ControlRoute.TapOnCurrentScreen)?.element?.id,
        "pause-btn",
    )
    check(
        "route: YTM playing + PLAY finds no Play control (it is already playing)",
        MediaControlRouter.route(ytmPlaying(), MediaControl.PLAY) is MediaControlRouter.ControlRoute.NoControlOnScreen,
    )
    val playRoute = MediaControlRouter.route(ytmPaused(), MediaControl.PLAY)
    check(
        "route: YTM paused + PLAY taps the Play button on the current screen",
        playRoute is MediaControlRouter.ControlRoute.TapOnCurrentScreen,
    )
    checkEq(
        "route: the resumed element is the Play button already there",
        (playRoute as? MediaControlRouter.ControlRoute.TapOnCurrentScreen)?.element?.id,
        "play-btn",
    )
    check(
        "route: Facebook video playing + PAUSE taps on the current screen",
        MediaControlRouter.route(fbVideoPlaying(), MediaControl.PAUSE) is MediaControlRouter.ControlRoute.TapOnCurrentScreen,
    )
    check(
        "route: empty screen + PAUSE is honestly NoControl, never a launch",
        MediaControlRouter.route(emptyScreen(), MediaControl.PAUSE) is MediaControlRouter.ControlRoute.NoControlOnScreen,
    )
    check(
        "route: mediaControlOnScreen text form is true for 'pause' on a playing screen",
        MediaControlRouter.mediaControlOnScreen(ytmPlaying(), "pause"),
    )
    check(
        "route: mediaControlOnScreen text form is false for 'play Davido'",
        !MediaControlRouter.mediaControlOnScreen(ytmPlaying(), "play Davido"),
    )

    // ---------- 3. the session IS the live screen ----------
    val session = MediaControlRouter.currentSession(ytmPlaying())
    checkEq("session: package is YouTube Music", session.packageName, YTM_PKG)
    check("session: playing is true while the Pause control is up", session.playing)
    check("session: hasPauseControl is true", session.hasPauseControl)
    check("session: hasPlayControl is false while playing", !session.hasPlayControl)
    val pausedSession = MediaControlRouter.currentSession(ytmPaused())
    check("session: paused screen has a Play control", pausedSession.hasPlayControl)
    check("session: paused screen has no Pause control", !pausedSession.hasPauseControl)

    println("MediaControlRouterTest: $passed passed, $failed failed")
}
