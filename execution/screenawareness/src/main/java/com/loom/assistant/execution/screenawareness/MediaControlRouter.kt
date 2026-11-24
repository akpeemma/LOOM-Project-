package com.loom.assistant.execution.screenawareness

import com.loom.assistant.core.common.MediaControl
import com.loom.assistant.core.common.MediaControlPhrases

/**
 * Session/state awareness (2026-11-11, David's device evidence
 * ~21:43 WAT + his clarification ~21:44 WAT) — OBSERVE-FIRST
 * routing for media control commands.
 *
 * David's words: LOOM has the ability to control the app, but it
 * does not know it is currently ON an app — when told pause/play it
 * restarts everything instead of acting on the current screen. The
 * rule: before acting on ANY control command, LOOM must understand
 * the screen in real time — know what app it is currently on and
 * what is on the screen right now — and act on the CURRENT screen
 * (tap the pause/play button that is already there). It must NEVER
 * restart the task from the beginning for a command about something
 * already running.
 *
 * This router is the pure, testable heart of that rule. It reads
 * ONLY the fresh snapshot handed to it — the session state IS the
 * live screen (active package + what is playing), never a cached
 * guess about what might be open. A stale cache is how you tap
 * pause in an app that closed five minutes ago.
 *
 *  - [currentSession] — what is on screen right now: the foreground
 *    package, whether media is playing/paused, and whether a
 *    pause/play control is present.
 *  - [route] — the routing decision: TAP_ON_SCREEN (the control is
 *    here — tap it, no launch) or NO_CONTROL (the control is
 *    genuinely absent — the caller falls back to navigate/launch,
 *    or fails honestly; it never invents a control).
 *
 * The control itself is resolved by [VideoPresence] label matching
 * ("Pause"/"Play"), which is audio-capable — a YouTube Music pause
 * button is a Pause control even though no video is on screen.
 * [VideoPresence.state] stays video-scoped (its toggle path); this
 * router does not depend on it.
 */
object MediaControlRouter {

    /**
     * What is on screen right now, read from a fresh snapshot.
     * Nothing here is cached: call it again after every observation.
     */
    data class CurrentSession(
        /** Foreground package of the snapshot. */
        val packageName: String,
        /** Media is playing (a Pause control, or a playing label). */
        val playing: Boolean,
        /** Media is present but paused. */
        val paused: Boolean,
        /** A Pause control is on this screen. */
        val hasPauseControl: Boolean,
        /** A Play control is on this screen. */
        val hasPlayControl: Boolean,
    )

    /** The routing decision for one control command on one screen. */
    sealed class ControlRoute {
        /** The control is on the current screen — tap it there. No launch. */
        data class TapOnCurrentScreen(val element: ScreenElement) : ControlRoute()

        /** The control is genuinely absent from the current screen. */
        data class NoControlOnScreen(val reason: String) : ControlRoute()
    }

    fun currentSession(screen: ScreenModel): CurrentSession {
        val hasPause = VideoPresence.findPlayPauseControl(screen, wantPlaying = false) != null
        val hasPlay = VideoPresence.findPlayPauseControl(screen, wantPlaying = true) != null
        return CurrentSession(
            packageName = screen.packageName,
            playing = VideoPresence.isPlaying(screen),
            paused = VideoPresence.isPaused(screen),
            hasPauseControl = hasPause,
            hasPlayControl = hasPlay,
        )
    }

    /**
     * Route one control command against the current screen. The
     * control is resolved from the live tree — never from a plan,
     * a cache, or a guess.
     */
    fun route(screen: ScreenModel, control: MediaControl): ControlRoute {
        val wantPlaying = control == MediaControl.PLAY
        val element = VideoPresence.findPlayPauseControl(screen, wantPlaying)
        return if (element != null) {
            ControlRoute.TapOnCurrentScreen(element)
        } else {
            ControlRoute.NoControlOnScreen(
                if (wantPlaying) "No play control on screen — nothing to play."
                else "No pause control on screen — nothing is playing to pause.",
            )
        }
    }

    /** True when the matching control is on this screen right now. */
    fun mediaControlOnScreen(screen: ScreenModel, control: MediaControl): Boolean =
        route(screen, control) is ControlRoute.TapOnCurrentScreen

    /** Text form: parses the utterance, false when it is not a control command. */
    fun mediaControlOnScreen(screen: ScreenModel, controlText: String): Boolean {
        val control = MediaControlPhrases.match(controlText) ?: return false
        return mediaControlOnScreen(screen, control)
    }
}
