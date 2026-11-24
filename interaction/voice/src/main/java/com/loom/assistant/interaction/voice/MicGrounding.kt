package com.loom.assistant.interaction.voice

/**
 * Phase 2 (§C) — mic-state grounding derivation. Pure Kotlin, zero Android
 * APIs, so the §7-D desync test drives it on the JVM.
 *
 * Replaces the hardcoded prompt text that used to claim "microphone
 * permission granted, speech recognition active" on every turn (the audit's
 * R4 contradiction). The composed sentence is DERIVED from the live
 * controller state every time:
 *
 * - session: the manager's own [VoiceSessionState], reduced to mic-relevant
 *   values (LISTENING / PROCESSING_TURN / IDLE).
 * - recognizerLive: the pipeline's live recognizer truth (LISTENING, or
 *   THINKING right after a live capture produced the turn being processed).
 * - micOwned: the atomic [com.loom.assistant.core.common.VoiceSessionOwner]
 *   flag for this session — a session that does not own the mic is not live.
 * - permissionGranted: the last checkMicPermission result, cached truthfully
 *   per session — never assumed; null (never checked) counts as not live.
 * - staleLiveClaim: models a stale/cached "live" claim (e.g. a UI flag that
 *   was never cleared). It can only ever force a disagreement note — it can
 *   NEVER produce a live verdict on its own.
 *
 * Authoritative order (plan §4.1): on any disagreement the most conservative
 * verdict (mic off / not listening) wins, and the disagreement is surfaced
 * in the sentence. A stale flag claiming "live" while the controller says
 * released ⇒ the context follows the CONTROLLER.
 */
object MicGrounding {

    /** The manager's session state, reduced to mic-relevant values. */
    enum class SessionMic { LISTENING, PROCESSING_TURN, IDLE }

    data class Inputs(
        val session: SessionMic,
        /** Live recognizer truth from the pipeline (LISTENING/THINKING). */
        val recognizerLive: Boolean,
        /** Atomic ownership: this session currently owns the mic. */
        val micOwned: Boolean,
        /** Last checkMicPermission result; null = never checked this session. */
        val permissionGranted: Boolean?,
        /** A stale "live" claim — can only flag disagreement, never liveness. */
        val staleLiveClaim: Boolean = false,
        val softFailures: Int = 0,
    )

    data class Result(
        /** Conservative mic-live verdict — the controller always wins. */
        val micLive: Boolean,
        val disagreement: Boolean,
        val text: String,
    )

    fun derive(i: Inputs): Result {
        // The controller's verdict: every live source must agree.
        val controllerLive = i.recognizerLive && i.micOwned && i.permissionGranted == true
        // The mic is genuinely capturing right now only when the session is
        // in LISTENING AND the controller agrees.
        val micLive = controllerLive && i.session == SessionMic.LISTENING
        // Disagreement: anyone claims live while the controller says released
        // (the §7-D desync), the session idled while the controller still
        // reports live, or a turn is being processed whose recognizer is
        // already gone. Conservative verdict wins either way.
        val disagreement = (i.staleLiveClaim && !controllerLive) ||
            (i.session == SessionMic.LISTENING && !controllerLive) ||
            (i.session == SessionMic.PROCESSING_TURN && !controllerLive) ||
            (i.session == SessionMic.IDLE && controllerLive)

        val sb = StringBuilder("Device state: ")
        when {
            micLive ->
                sb.append("the microphone is live and listening now.")
            i.session == SessionMic.PROCESSING_TURN && controllerLive ->
                sb.append("the user's message was transcribed from live speech just now.")
            else -> {
                sb.append("the microphone is not live right now.")
                if (i.session == SessionMic.PROCESSING_TURN) {
                    // Structural provenance: this sentence is only composed on
                    // the recognizer-callback path, so the turn genuinely came
                    // from live speech — no inference, no assumption.
                    sb.append(" The user's message was transcribed from live speech just now.")
                }
            }
        }
        if (disagreement) {
            sb.append(" Session, recognizer, and ownership disagree about the microphone; treating it as off.")
        }
        if (i.softFailures > 0) {
            sb.append(" Speech recognition needed retries on the previous turn.")
        }
        return Result(micLive, disagreement, sb.toString())
    }
}
