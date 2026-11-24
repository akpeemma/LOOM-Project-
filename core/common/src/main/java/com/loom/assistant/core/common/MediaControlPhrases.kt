package com.loom.assistant.core.common

/**
 * Session/state awareness (2026-11-11, David's device evidence
 * ~21:43 WAT) — media control phrases.
 *
 * The defect: David played a song in YouTube Music, said "pause",
 * then "play" again — and LOOM tried to OPEN YouTube Music again
 * instead of pausing/resuming the song already playing. Two causes
 * shared one root: nothing in the command layer knew a control
 * command is about the thing ALREADY RUNNING.
 *
 *  1. `VoiceCommands.match` reads `contains("pause")` as LOOM's own
 *     session pause — "pause the music" never reached the planner.
 *  2. The planner tries the LLM first, and the model cannot see the
 *     screen — so "pause the music" became a fresh play-search plan
 *     (launch + search + open the first result), restarting the song.
 *
 * The fix is one shared phrase table (this file, pure text, no
 * Android dependency so core, planner and voice can all read it):
 * a control command is an EXACT allowlisted phrase. A named song
 * ("play Davido", "play the song Fall") is NOT here — it falls
 * through to the play-search plan, as before. A bare control ("play",
 * "pause", "resume") and a media-named control ("pause the music",
 * "play the song again") ARE here — they route to PLAY_PAUSE on the
 * current screen, never to a launch.
 *
 * Lives in core:common (not execution) so [VoiceCommands], the
 * planner and the voice session can all read it without a module
 * cycle. The screen side — resolving the control on the live tree —
 * lives in [com.loom.assistant.execution.screenawareness.MediaControlRouter].
 */
enum class MediaControl {
    PAUSE,
    PLAY,
}

object MediaControlPhrases {

    private val PAUSE_PHRASES = setOf(
        "pause",
        "pause it",
        "pause this",
        "pause the music",
        "pause music",
        "pause the song",
        "pause this song",
        "pause the video",
        "pause video",
        "pause playback",
        "stop the music",
        "stop music",
        "stop the song",
        "stop playing",
        "stop the video",
        "stop it",
        "stop this",
    )

    private val PLAY_PHRASES = setOf(
        "play",
        "play it",
        "play this",
        // "play music" / "play the music" / "play the song" are
        // deliberately NOT here: with no "again" / "this" they are a
        // NEW play request (launch + search for something to play),
        // not a resume — the planner test pins "play music" to the
        // launch+search plan. Only the resume-shaped forms below
        // ("again", "this") mean the thing already playing.
        "play this song",
        "play the song again",
        "play it again",
        "play again",
        "play the video",
        "play video",
        "resume",
        "resume it",
        "resume the music",
        "resume music",
        "resume the song",
        "resume the video",
        "resume video",
        "resume playback",
        "unpause",
        "continue playing",
        "continue the music",
    )

    /**
     * The media control for this utterance, or null when it is not a
     * control command. Exact-phrase only: "play Davido" is null
     * (a named song is a search), "pause the meeting" is null, and
     * any longer sentence containing a control word is null — the
     * control must BE the utterance, not hide inside it.
     */
    fun match(text: String): MediaControl? {
        val t = text.lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', '!', '?')
            .trim()
            .removePrefix("please ").trim()
            .removeSuffix(" please").trim()
        return when {
            t in PAUSE_PHRASES -> MediaControl.PAUSE
            t in PLAY_PHRASES -> MediaControl.PLAY
            else -> null
        }
    }
}
