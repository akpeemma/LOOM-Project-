package com.loom.assistant.execution.screenawareness

/**
 * MASTER Wave 3 (F15a) — video recognition in perception.
 *
 * A video on screen is a first-class observable, not just another
 * node. Evidence (video 2026-11-08): YouTube Shorts playing at
 * 230–260s, result thumbnails at 421s and 510s, a Facebook video at
 * 695–715s — LOOM never recognized any of them, and David had to
 * pause the Facebook video himself.
 *
 * The tree exposes no "is a video" flag, so the predicate reads what
 * it does expose — video/player view classes, a Pause control (a
 * player that offers Pause is playing), "playing"/"now playing"
 * labels, and thumbnails with a play affordance. Every arm is
 * stated as evidence, never as certainty: a Pause control proves a
 * player is up with a pause affordance, not that audio is audible —
 * only David's ears prove that.
 */
object VideoPresence {

    /** What the predicate found, and the evidence for it. */
    data class VideoState(
        /** A playable video is on screen. */
        val present: Boolean,
        /** A video is currently playing (not merely present). */
        val playing: Boolean,
        /** A video is present but paused. */
        val paused: Boolean,
        /** The evidence, in plain words ("" when nothing found). */
        val evidence: String,
    )

    private val PLAYER_CLASSES = listOf(
        "VideoView", "PlayerView", "ExoPlayer", "YouTubePlayerView",
    )

    /**
     * Surface classes are weak evidence on their own (maps, camera
     * and games all use them) — they count as a video only together
     * with a play/pause control or a playing/paused label.
     */
    private val SURFACE_CLASSES = listOf("TextureView", "SurfaceView")

    /** A video node / media-playing state / thumbnail with a play affordance. */
    fun videoPresent(model: ScreenModel): Boolean = state(model).present

    /**
     * Device-test failures (2026-11-11, D-07): a video PLAYER is up
     * (a player view, a Pause control, or a playing label) — the
     * evidence an opened result actually opened, as opposed to a
     * results list that merely contains thumbnails. Thumbnails alone
     * do NOT count here ([videoPresent] counts them); a results
     * screen full of thumbnails is not an opened video.
     */
    fun playerUp(model: ScreenModel): Boolean {
        if (model.elements.isEmpty()) return false
        val labels = model.elements.map { it.label().lowercase() }
        val hasPlayerView = model.elements.any { el ->
            PLAYER_CLASSES.any { cls -> el.className.contains(cls, ignoreCase = true) }
        }
        val hasPauseControl = model.elements.any { isPauseControl(it) }
        val playingLabel = labels.any { it.contains("playing") || it.contains("now playing") }
        return hasPlayerView || hasPauseControl || playingLabel
    }

    /** True when a video is playing on screen. */
    fun isPlaying(model: ScreenModel): Boolean = state(model).playing

    /** True when a video is present but paused. */
    fun isPaused(model: ScreenModel): Boolean = state(model).paused

    fun state(model: ScreenModel): VideoState {
        if (model.elements.isEmpty()) {
            return VideoState(present = false, playing = false, paused = false, evidence = "")
        }
        val labels = model.elements.map { it.label().lowercase() }
        val hasPlayerView = model.elements.any { el ->
            PLAYER_CLASSES.any { cls -> el.className.contains(cls, ignoreCase = true) }
        }
        val hasSurfaceView = model.elements.any { el ->
            SURFACE_CLASSES.any { cls -> el.className.contains(cls, ignoreCase = true) }
        }
        val hasPauseControl = model.elements.any { isPauseControl(it) }
        val hasPlayControl = model.elements.any { isPlayControl(it) }
        val playingLabel = labels.any { it.contains("playing") || it.contains("now playing") }
        val pausedLabel = labels.any { it.contains("paused") }
        // A thumbnail with a play affordance: an image whose label
        // names a video/play action, or a "Play video" control.
        val thumbnailWithPlay = model.elements.any { el ->
            el.role == ElementRole.IMAGE &&
                ((el.contentDescription?.contains("play", ignoreCase = true) == true) ||
                    (el.contentDescription?.contains("video", ignoreCase = true) == true) ||
                    (el.text?.contains("video", ignoreCase = true) == true))
        }
        val present = hasPlayerView || hasPauseControl || playingLabel ||
            pausedLabel || thumbnailWithPlay ||
            // A Play control on its own is a play affordance only
            // when a player view or a title-like row is also present;
            // a bare "Play" button (e.g. a music app's play button)
            // is not a video. A bare surface view is likewise not a
            // video without a media control or label beside it.
            (hasPlayControl && hasPlayerView) ||
            (hasSurfaceView && (hasPlayControl || hasPauseControl || playingLabel || pausedLabel))
        if (!present) {
            return VideoState(present = false, playing = false, paused = false, evidence = "")
        }
        // Playing: a Pause control is on screen (a playing player
        // offers Pause), or a playing label — the same evidence the
        // plan verifier already accepts for playback.
        val playing = hasPauseControl || playingLabel
        // Paused: a paused label, or a Play control where a player
        // view is present and no Pause control / playing label.
        val paused = pausedLabel || (hasPlayControl && hasPlayerView && !playing)
        val evidence = when {
            hasPauseControl -> "a Pause control is on screen"
            playingLabel -> "a playing label is on screen"
            pausedLabel -> "a paused label is on screen"
            hasPlayerView -> "a video player view is on screen"
            thumbnailWithPlay -> "a video thumbnail with a play affordance is on screen"
            else -> "a video is on screen"
        }
        return VideoState(present = true, playing = playing, paused = paused, evidence = evidence)
    }

    /**
     * The play/pause control to tap for the requested state, or null
     * when no such control is on screen. [wantPlaying] = true means
     * "play it" (tap the Play control); false means "pause it" (tap
     * the Pause control). Never returns a voice-search or unrelated
     * control: a control whose description mentions "voice" is a
     * microphone, never a play/pause control.
     */
    fun findPlayPauseControl(model: ScreenModel, wantPlaying: Boolean): ScreenElement? {
        val candidates = model.elements.filter { el ->
            el.enabled && !el.bounds.isEmpty &&
                (el.contentDescription?.contains("voice", ignoreCase = true) != true) &&
                if (wantPlaying) isPlayControl(el) else isPauseControl(el)
        }
        return candidates.firstOrNull()
    }

    /** A control that pauses playback ("Pause", "Pause video"). */
    fun isPauseControl(el: ScreenElement): Boolean {
        val label = (el.text?.trim() ?: "") + " " + (el.contentDescription?.trim() ?: "")
        if (label.contains("voice", ignoreCase = true)) return false
        return label.trim().equals("pause", ignoreCase = true) ||
            label.contains("pause video", ignoreCase = true) ||
            label.contains("pause playback", ignoreCase = true)
    }

    /** A control that starts playback ("Play", "Play video"). */
    fun isPlayControl(el: ScreenElement): Boolean {
        val label = (el.text?.trim() ?: "") + " " + (el.contentDescription?.trim() ?: "")
        if (label.contains("voice", ignoreCase = true)) return false
        return label.trim().equals("play", ignoreCase = true) ||
            label.contains("play video", ignoreCase = true) ||
            label.contains("play playback", ignoreCase = true)
    }
}
