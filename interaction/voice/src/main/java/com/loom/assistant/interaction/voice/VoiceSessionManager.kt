package com.loom.assistant.interaction.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.loom.assistant.core.common.AssistantActivation
import com.loom.assistant.core.common.CapabilityBridge
import com.loom.assistant.core.common.MessageChannel
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SharedConversationContext
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.core.common.TaskRecordStatus
import com.loom.assistant.core.common.LoomCapability
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.ModelAction
import com.loom.assistant.core.common.PointerShowGate
import com.loom.assistant.core.common.VoiceCommand
import com.loom.assistant.core.common.VoiceCommands
import com.loom.assistant.core.common.VoiceSessionOwner
import com.loom.assistant.execution.interaction.HaltSource
import com.loom.assistant.execution.interaction.InteractionLayer
import com.loom.assistant.execution.interaction.InteractionLayerRegistry
import com.loom.assistant.intelligence.llm.ResponseEngine
import com.loom.assistant.intelligence.memory.ChatMessage
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.interaction.pointer.PointerPolicy
import com.loom.assistant.interaction.pointer.PointerPolicyStore
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.presentation.modes.AssistantMode
import com.loom.assistant.presentation.modes.ModeController
import com.loom.assistant.presentation.modes.ModeResult

/**
 * Stage 2A — conversational voice session coordinator.
 *
 * Turns the one-shot STT→engine→TTS chain into a tap-once conversational
 * loop: LISTENING → PROCESSING → SPEAKING → (auto) LISTENING …
 *
 * The 7 states (David's §3) always reflect the real system condition:
 * LISTENING is entered only around an actual recognizer session, SPEAKING
 * only while a TTS utterance is playing, PAUSED only when the user paused.
 * Nothing is faked: no wake-word, no always-on listening, no barge-in
 * (TTS and capture cannot overlap on Android), capture is activity-bound.
 *
 * Reuses (never duplicates): the screen's [ResponseEngine] (no second
 * engine), the shared [ConversationStore] active thread (voice and keyboard
 * stay on ONE conversation), [DeviceVoicePipeline], [VoiceCommands].
 */
class VoiceSessionManager(
    context: Context,
    private val pipeline: VoicePipeline,
    private val host: Host,
    /**
     * Stage 2A.1 (D5): true when this manager is hosted by the overlay
     * service (floating session). In-app managers refuse to start while a
     * floating session owns the mic; the floating one never refuses itself.
     */
    private val floating: Boolean = false,
    /**
     * Stage 2C: the interaction layer, wired by the host
     * (platform:services). Null until the host wires it — every new command
     * below answers honestly when it is absent.
     */
    private val interactionLayer: InteractionLayer? = null,
) {

    interface Host {
        fun provideEngine(): ResponseEngine
        fun provideStore(): ConversationStore
        fun historyWindow(): Int
        fun checkMicPermission(onResult: (Boolean) -> Unit)
        fun idlePrompt(): String
        fun onSessionState(state: VoiceSessionState, statusText: String)
        fun onPartialText(text: String)
        fun onTurnFinished()
        fun onCommandAction(cmd: VoiceCommand)
        // Device-evidence fix (2026-11-05): launch requests carry an app
        // name, which onCommandAction(cmd) cannot express — so LAUNCH_APP
        // arrives through this dedicated host method. The host owns the
        // execution (chat screen, home screen, or floating service each
        // call the shared LoomAppLauncher); the manager never imports
        // platform:services (module cycle) and never launches itself.
        fun onLaunchRequested(appName: String)
        // Master §8 — provenance: the two-arg form carries who asked.
        // Direct user commands (matched voice/chat) pass DIRECT_USER;
        // model-proposed launches pass MODEL, so a stored "always allow"
        // grant can skip the card only for David's own words, never for
        // a model request. Default delegates to the one-arg form (MODEL
        // origin: always a card) so existing hosts keep compiling.
        fun onLaunchRequested(appName: String, origin: RequestOrigin) {
            onLaunchRequested(appName)
        }
        // Master §2 (item 4): search inside an app. The floating host
        // executes via Worker 4's SearchExecutor and owns the spoken
        // outcome; other hosts return false and the manager speaks the
        // honest routing message. Returns true when handled. [origin] is
        // DIRECT_USER for a matched voice command (the manager passes it),
        // so §8 scoped grants apply to search tokens.
        fun onSearchRequested(
            appName: String,
            query: String,
            origin: RequestOrigin = RequestOrigin.MODEL,
        ): Boolean = false
        // Master §2 (item 5): move the assistant itself. [regionName]
        // is a Worker 5 AssistantRegion enum name. The floating host
        // executes via AssistantMover; other hosts return false.
        fun onAssistantMoveRequested(regionName: String): Boolean = false
        // Dictated messaging + calls (2026-11-05, fix-plan item 12):
        // send a dictated message / place a dictated call. The
        // floating host executes via the message/call executors and
        // owns the spoken outcome; other hosts return false and the
        // manager speaks the honest routing message. Returns true when
        // handled. [channel] is WHATSAPP or SMS; [origin] is
        // DIRECT_USER for a matched voice command, MODEL for a
        // model-proposed send/call — either way the send/call still
        // cards per message (message.send / call.place are never
        // grant-eligible), so MODEL origin cannot silently send.
        fun onMessageRequested(
            channel: MessageChannel,
            recipient: String,
            text: String,
            origin: RequestOrigin = RequestOrigin.MODEL,
        ): Boolean = false
        fun onCallRequested(
            target: String,
            origin: RequestOrigin = RequestOrigin.MODEL,
        ): Boolean = false
        // Stage 2D: a multi-step task for the planner. [request]
        // carries the goal verbatim, the origin (DIRECT_USER for
        // David's own matched words, MODEL for a model-proposed plan)
        // and whether he asked to see the steps. The host owns
        // planning + the outcome ack; there is NO plan-approval step
        // (David, 2026-11-06: the plan is for LOOM, not for David —
        // his blanket advance approval covers DIRECT_USER plans, and
        // MODEL plans go through the per-sequence cover card).
        // Other hosts return false and the manager speaks the honest
        // routing message. Returns true when handled. Worker B
        // implements it in LoomOverlayService + the in-app hosts.
        // A model-proposed plan NEVER inherits direct-user
        // authorization — MODEL origin always cards (grants apply
        // to DIRECT_USER only; see RequestOrigin).
        fun onPlanRequested(request: PlanRequest): Boolean = false
        // Device-test failures (2026-11-11, D-04): "confirm your
        // screen" — the host reads the live screen and returns the
        // description, or null when it cannot read it (the manager
        // then says so plainly). Default null so existing hosts keep
        // compiling; the chat and floating hosts implement it from
        // ScreenAwarenessHolder + ScreenDescriber.
        fun describeCurrentScreen(): String? = null
        // Session/state awareness (2026-11-11, OBSERVE-FIRST): true
        // when the live screen shows the matching media control (a
        // Pause control for "pause", a Play control for "play") — the
        // command is about the media on screen, not LOOM's own
        // session. Default false so existing hosts keep compiling;
        // the chat, floating and home hosts implement it from
        // ScreenAwarenessHolder + MediaControlRouter.
        fun mediaControlOnScreen(control: String): Boolean = false
        // MASTER F6a/F6b — provenance for layer-mediated guidance /
        // demo sessions. The interaction layer carries only strings
        // (query / goal), so the manager notes the origin on the host
        // immediately before the layer call; the host (which owns the
        // session) consumes it when the session starts. Default no-op:
        // hosts that do not build guidance/demo sessions ignore it,
        // and their sessions stay MODEL (safe: always a card, no
        // grant) — chat-voice guidance/demo stays MODEL for exactly
        // this reason (its host does not build sessions).
        fun noteGuideOrigin(origin: RequestOrigin) {}
        fun noteDemoOrigin(origin: RequestOrigin) {}
        // Stage 2A.1: the host no longer executes mode switches — the manager
        // calls ModeController.applyMode directly (the single authoritative
        // applier), so there is no per-host switch path left to go stale.
    }

    /**
     * Master §2 (item 6): minimal conversational context — the app the
     * last launch/search used. A follow-up "search for X" with no app
     * resolves from here; an explicit app always wins.
     *
     * MASTER F3a: this is the ONE application-scoped context
     * ([SharedConversationContext]), shared by every VoiceSessionManager
     * instance (Home, Chat, floating) — a new floating session
     * (recreated on every orb tap) inherits the active task and the
     * last-turn app instead of starting with no memory. Process-scoped:
     * a process death still loses the task (see ConversationContext).
     */
    private val conversationContext = SharedConversationContext.instance()

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    var state: VoiceSessionState = VoiceSessionState.IDLE
        private set

    /** True while a session is live (LISTENING/PROCESSING/SPEAKING/PAUSED/ERROR). */
    val isActive: Boolean
        get() = state != VoiceSessionState.IDLE

    private var muted = false
    val isMuted: Boolean get() = muted

    /**
     * Phase 2 (plan §4.2-C): GO_SILENT sets this — no unsolicited speech, no
     * conversational responses spoken (text still logged). "Speak again"
     * clears it. Mic may stay live; the session, views and sequences are
     * untouched.
     */
    private var silent = false
    val isSilent: Boolean get() = silent

    /**
     * Phase 2 (§C): the last checkMicPermission result, cached truthfully
     * per session — never assumed. Feeds buildSystemContext(); null (never
     * checked) counts as not-live.
     */
    private var lastMicPermission: Boolean? = null

    /** Invalidates in-flight engine turns (stop/end/pause/new-turn). */
    private var generationId = 0L

    /**
     * Stage 2A.1 (D4): closes the async-permission-callback hole. stop(),
     * pause() and end() bump this; start()'s permission callback captures
     * the value and proceeds only if unchanged — a "stop listening" issued
     * while the callback is pending now really stops.
     */
    private var stopGeneration = 0L

    private var softFailures = 0
    private var hardFailures = 0
    private var retryRunnable: Runnable? = null

    /**
     * Device-test failures (2026-11-11, D-02) — the post-turn idle
     * listen. True while the recognizer is listening only because a
     * finished turn left the loop running (autoContinue after TTS,
     * or a matched command whose outcome is still to come) — NOT
     * because David tapped to talk. A silent idle listen ends the
     * session QUIETLY (see endSessionQuietly): no "Didn't catch
     * that" bubble, no spoken retry, no terminal message. Silence
     * after a finished turn is not a failure to report — the Nov 10
     * build reported it after every turn, in every screenshot.
     * Set by autoContinue and by matched-command dispatch; cleared
     * by an explicit start/resume and by any real heard turn.
     */
    private var idleListen = false

    /**
     * Device-test failures (2026-11-11, D-02) — a plan was dispatched
     * and its outcome has not arrived yet. A quiet end during this
     * window must not swallow the spoken outcome: the listen is
     * suspended (mic off, no bubble) instead of ended, and the
     * outcome ack (speakNow) resumes the loop. Set when a host
     * accepts a plan request; cleared by speakNow.
     */
    private var planOutcomePending = false

    /** True while the listen is suspended for a pending plan outcome. */
    private var listenSuspended = false

    /**
     * MASTER Wave 4 (F12) — whether the current listen turn's lost
     * command has already been recorded durably. A lost turn is
     * recorded ONCE (first soft failure), not on every retry —
     * otherwise five retries would spam five identical records.
     * Reset at each listen start (see beginListen).
     */
    private var lostTurnRecorded = false

    /**
     * MASTER Wave 3 (F16) — the last partial transcript of the
     * current listen turn, retained ONLY so a lost command can be
     * diagnosed: when the recognizer errors with NO_MATCH after
     * partials arrived, the partial is the only record of what was
     * heard. It is logged on error and never acted on (a partial is
     * not a command — acting on it would be a guessed fix for the
     * lost-command failure, which the investigation forbids).
     * MASTER Wave 4 (F12) also shows it in the stored lost-turn
     * record (see recordLostTurnOnce) — display only, still never
     * acted on.
     */
    private var lastPartialText: String = ""

    /**
     * MASTER Wave 4 (F12) — durable voice-turn records. Every voice
     * turn leaves a trace in the shared conversation store (the same
     * store Home "Recent Conversations" reads), so nothing exists
     * only as speech (acceptance C-03, T-01):
     *  - [recordHeard]: the heard transcript, stored once per turn
     *    as the user message. The engine path used to be the only
     *    place that stored it — matched commands, clarifications,
     *    mode switches and failures spoke only, so a greeting
     *    answered by the engine was stored but "open YouTube" (a
     *    matched plan) left no user line at all.
     *  - [recordAssistant]: a spoken outcome, stored as the
     *    assistant message — EXCEPT progress statuses ("Switching…",
     *    "Starting…", "Thinking…"), which are transient, not
     *    outcomes. The plan host's own onStatus appends are
     *    de-duplicated by the last-message check below (whichever
     *    lands first wins; the second identical line is skipped),
     *    so a plan outcome is recorded once, never twice.
     *  - [recordLostTurnOnce]: a lost command ("Didn't catch that")
     *    stored once per turn, WITH the last partial heard — so
     *    David can see what was caught when a command is lost
     *    (F16 follow-up). Stored as the assistant message (a partial
     *    is not a user turn and must not pollute engine history).
     * All three are best-effort: a store failure never fails the
     * turn (the voice reply still goes out).
     */
    private fun appendToStore(role: String, text: String) {
        if (text.isBlank()) return
        try {
            val store = host.provideStore()
            val conv = store.getActive()
            val last = conv.messages.lastOrNull()
            if (last?.role == role && last.text == text) return // already recorded
            store.append(conv.id, ChatMessage(role, text, System.currentTimeMillis(), "voice"))
        } catch (_: Exception) {
        }
    }

    private fun recordHeard(text: String) = appendToStore("user", text)

    private fun recordAssistant(text: String) {
        val progress = setOf(
            "Switching…", "Starting…", "Updating pointer setting…",
            "Listening…", "Speaking…", "Thinking…",
        )
        if (text.trim() in progress) return
        if (text.trim().startsWith("Thinking… — heard:")) return
        appendToStore("assistant", text)
    }

    private fun recordLostTurnOnce(status: String) {
        if (lostTurnRecorded) return
        lostTurnRecorded = true
        val heard = lastPartialText.trim()
        val text = if (heard.isBlank()) status
        else "$status (I heard: \"$heard\")"
        appendToStore("assistant", text)
    }

    companion object {
        private const val MAX_SOFT_RETRIES = 5
        private const val MAX_HARD_FAILURES = 3
        /**
         * VOICE FIX (2026-11-10) — the no-speech retry bound. A turn
         * that heard nothing retries at most this many times, then the
         * session stops and says plainly that nothing was heard. The
         * Nov 10 device evidence is four "Didn't catch that" lines in
         * a row with no answer; the old bound (5, shared with the
         * network-wobble path) let the loop run five times. This bound
         * applies ONLY to softFailure (the no-speech path); the medium
         * network path keeps MAX_SOFT_RETRIES.
         */
        private const val MAX_NO_SPEECH_RETRIES = 2
        /**
         * Blank-reply fix (2026-11-06): the one plain sentence a failed
         * voice turn gets — spoken and stored, never a blank bubble.
         * Same text as the typed-chat fallback (ChatReplyGrounding);
         * duplicated because interaction:voice cannot import
         * presentation:chat (module cycle).
         */
        private const val VOICE_FALLBACK_REPLY = "I couldn't answer that, try again."
    }

    // ---------- session controls (David's §5) ----------

    /** Start (or restart) a conversational session. One tap. */
    fun start() {
        if (state == VoiceSessionState.ENDING) return
        // Phase 2 (E.3-5): refuse new sessions unless the lifecycle is ACTIVE.
        // isDispatchAllowed() is true only when lifecycle == ACTIVE — the
        // controller's own contract says "dispatch sinks and session creation
        // consult this".
        if (!LifecycleController.isDispatchAllowed()) {
            setState(
                VoiceSessionState.ERROR,
                "Assistant is not active right now.",
            )
            return
        }
        // Stage 2A.1: start while paused is a resume, never a silent no-op
        // (beginListen early-returned on PAUSED — R3).
        if (state == VoiceSessionState.PAUSED) {
            resume()
            return
        }
        // Stage 2A.1 (D5): in-app sessions refuse while a floating session
        // owns the mic — the two share one DeviceVoicePipeline.
        if (!floating && VoiceSessionOwner.floatingActive) {
            setState(
                VoiceSessionState.ERROR,
                "A floating voice session is already active.",
            )
            return
        }
        cancelRetry()
        softFailures = 0
        hardFailures = 0
        // D-02: an explicit start is a real listen, never an idle
        // one — silence here may still be reported (bounded), unlike
        // a post-turn idle listen.
        idleListen = false
        planOutcomePending = false
        listenSuspended = false
        ensurePermissionThenBeginListen()
    }

    /**
     * Phase 2 (E.3-4): the permission check beginListen() was missing. Every
     * path that opens the recognizer routes through here — start() and
     * handleStartListening() — so the mic is never opened on an unchecked
     * (or denied) permission. The D4 stop-generation guard is preserved:
     * stop()/pause()/end() while the callback is pending still win.
     */
    private fun ensurePermissionThenBeginListen() {
        val stopGen = stopGeneration
        host.checkMicPermission { granted ->
            mainHandler.post {
                // Stage 2A.1 (D4): superseded by stop/pause/end while pending.
                if (stopGen != stopGeneration) return@post
                // Phase 2 (§C): cache the truthful result per session — this
                // is what buildSystemContext() reports, never an assumption.
                lastMicPermission = granted
                if (granted) {
                    beginListen()
                } else {
                    setState(
                        VoiceSessionState.ERROR,
                        "Microphone permission needed for voice.",
                    )
                }
            }
        }
    }

    /** Pause: mic off, TTS stopped, session + context held. */
    fun pause() {
        if (state != VoiceSessionState.LISTENING &&
            state != VoiceSessionState.PROCESSING &&
            state != VoiceSessionState.SPEAKING
        ) return
        stopGeneration++
        generationId++
        cancelRetry()
        pipeline.stopListening()
        pipeline.stopSpeaking()
        // Phase 2 (E.3-2): drop queued speech — nothing may speak after the
        // user paused.
        pipeline.clearPendingSpeech()
        setState(VoiceSessionState.PAUSED, "Paused — tap resume to continue.")
        LoomEventBus.post(LoomStateEvent.Paused)
    }

    /**
     * Resume a paused session.
     *
     * Phase 2 (E.3-1): the PAUSED one-way trap is fixed — resume() used to
     * call beginListen(), which early-returned on PAUSED, so resume was a
     * silent no-op forever. The resume path now explicitly opts into the
     * PAUSED→LISTENING transition.
     */
    fun resume() {
        if (state != VoiceSessionState.PAUSED && state != VoiceSessionState.ERROR) return
        softFailures = 0
        idleListen = false
        listenSuspended = false
        beginListen(resuming = state == VoiceSessionState.PAUSED)
    }

    /** Mute: replies appear as text, TTS is skipped, listening continues. */
    fun toggleMute(): Boolean {
        muted = !muted
        if (muted && state == VoiceSessionState.SPEAKING) {
            pipeline.stopSpeaking()
            // Fall through to auto-continue: the turn is done, keep listening.
            autoContinue()
        }
        return muted
    }

    /** Stop the current utterance ("be quiet") but stay in the session. */
    fun stopUtterance() {
        if (state == VoiceSessionState.SPEAKING) {
            pipeline.stopSpeaking()
            setState(VoiceSessionState.LISTENING, "Stopped — listening…")
            autoContinue()
        }
    }

    /**
     * Correction pass ("stop listening"): halt the recognizer, release the
     * microphone, session idles — with visible feedback, never a silent no-op.
     * Distinct from stopUtterance ("stop talking"), which only halts TTS.
     * Stage 2A.1 (D4): outside LISTENING/PROCESSING, voice-initiated calls
     * get a brief truthful "Mic is already off." instead of silence.
     */
    fun stopListening(speakIfIdle: Boolean = false) {
        if (state == VoiceSessionState.LISTENING ||
            state == VoiceSessionState.PROCESSING
        ) {
            stopGeneration++
            generationId++
            cancelRetry()
            pipeline.stopListening()
            setState(VoiceSessionState.IDLE, "Mic off — tap to talk.")
        } else if (speakIfIdle) {
            speakStatus("Mic is already off.", thenIdle = true)
        } else {
            setState(state, "Mic is already off.")
        }
    }

    /**
     * Stage 2A.1 (D2): voice-requested overlay mode switch. Goes through
     * ModeController.applyMode — the single authoritative applier — and the
     * spoken confirmation comes ONLY from the returned ModeResult (the five
     * outcomes). Nothing is claimed without the live bound-state evidence.
     */
    private fun executeModeSwitch(cmd: VoiceCommand) {
        val mode = when (cmd) {
            VoiceCommand.MODE_ORB -> AssistantMode.ORB
            VoiceCommand.MODE_CHARACTER -> AssistantMode.CHARACTER
            else -> AssistantMode.INVISIBLE
        }
        val turnGuard = generationId
        setState(VoiceSessionState.PROCESSING, "Switching…")
        ModeController.applyMode(appContext, mode) { result ->
            if (turnGuard != generationId) return@applyMode // superseded
            speakStatus(
                ModeController.ackText(result, ModeController.shortLabel(mode)),
            )
        }
    }

    /**
     * ACTIVATE ASSISTANT MODE — manager-direct, never host-forwarded.
     * Plans via AssistantActivation, then applies via ModeController.
     * applyMode alone (its cold-start path sends the ACTION_START_OVERLAY
     * intent) — NO LoomOverlayService import here: interaction:voice must
     * not depend on platform:services (module cycle). Mic ownership
     * UNCHANGED: this starts the service/views only, never a floating
     * voice session, never VoiceSessionOwner. The in-app session keeps
     * the mic; a floating session still starts only by an explicit
     * orb/character tap (or the panel's Voice chat row).
     */
    private fun executeAssistantActivation() {
        val plan = AssistantActivation.plan(
            lifecycleActive = LifecycleController.isDispatchAllowed(),
            overlayPermissionGranted = OverlayPermission.granted(appContext),
            serviceRunning = ModeController.isOverlayRunning(appContext) ||
                ModeController.boundMode() != null,
            lastBoundMode = ModeController.boundMode()?.name
                ?: ModeController.getMode(appContext).name,
        )
        when (plan) {
            is AssistantActivation.ActivationPlan.Refused -> {
                speakStatus(plan.reason)
            }
            is AssistantActivation.ActivationPlan.AlreadyRunning -> {
                // Service running: no restart. applyMode's setMode bus post
                // rebinds hidden views; verifyAndReport proves attachment.
                val mode = ModeController.boundMode()
                    ?: ModeController.getMode(appContext)
                applyActivationMode(mode)
            }
            is AssistantActivation.ActivationPlan.StartService -> {
                val mode = try {
                    AssistantMode.valueOf(plan.modeName)
                } catch (_: IllegalArgumentException) {
                    AssistantMode.ORB
                }
                applyActivationMode(mode)
            }
        }
    }

    private fun applyActivationMode(mode: AssistantMode) {
        val turnGuard = generationId
        setState(VoiceSessionState.PROCESSING, "Starting…")
        ModeController.applyMode(appContext, mode) { result ->
            if (turnGuard != generationId) return@applyMode // superseded
            when (result) {
                ModeResult.Success -> speakStatus(
                    AssistantActivation.successAck(ModeController.shortLabel(mode)),
                )
                ModeResult.PermissionRequired -> speakStatus(
                    AssistantActivation.permissionRefusedMessage(),
                )
                is ModeResult.Failed -> speakStatus(
                    AssistantActivation.failureAck(result.reason),
                )
                is ModeResult.Unavailable -> speakStatus(
                    AssistantActivation.failureAck(
                        "LOOM's floating assistant isn't available right now.",
                    ),
                )
            }
        }
    }

    /**
     * Stage 2B (D3a): voice-requested pointer policy change. The policy is
     * persisted first; the spoken confirmation comes ONLY from the persisted
     * read-back (Applied / Unchanged / Failed) — the 2A.1 truthfulness rule.
     * Nothing is claimed without the stored evidence.
     */
    private fun executePointerPolicy(cmd: VoiceCommand) {
        val policy = when (cmd) {
            VoiceCommand.POINTER_SHOW -> PointerPolicy.SHOW
            VoiceCommand.POINTER_HIDE -> PointerPolicy.HIDE
            else -> PointerPolicy.AUTO
        }
        val turnGuard = generationId
        setState(VoiceSessionState.PROCESSING, "Updating pointer setting…")
        val result = PointerPolicyStore.set(appContext, policy)
        if (turnGuard != generationId) return // superseded
        speakStatus(pointerPolicyAck(policy, result))
    }

    /**
     * Device-evidence fix (2026-11-05, pointer H3) + accessibility fix
     * (2026-11-05, audit §7.1 — words are not action): "show the
     * pointer" must SHOW something, and the spoken confirmation is
     * gated on a VERIFIED render — never on the request being issued.
     * - Layer null (floating assistant never started): nothing can
     *   render, so say plainly it isn't on screen and what to tap —
     *   never the old "will now stay visible during guidance" claim.
     * - Sequence active: the pointer is already on screen as part of
     *   guidance — say so as the live state, not as a new render.
     * - Otherwise the ack comes ONLY from showPointerIdle()'s verified
     *   boolean, via the shared [PointerShowGate].
     * HIDE/AUTO keep the policy-only ack — there is nothing to render
     * for those.
     */
    private fun pointerShowAck(): String {
        val layer = effectiveLayer()
        if (layer == null) {
            return PointerShowGate.NOT_RENDERED_ACK
        }
        if (layer.isSequenceActive) {
            return "The pointer is already on screen — guidance is showing it."
        }
        return PointerShowGate.ack(layer.showPointerIdle())
    }

    private fun pointerPolicyAck(
        policy: PointerPolicy,
        result: PointerPolicyStore.SetResult,
    ): String {
        val label = PointerPolicyStore.label(policy)
        return when (result) {
            is PointerPolicyStore.SetResult.Applied -> when (policy) {
                PointerPolicy.SHOW -> pointerShowAck()
                PointerPolicy.HIDE -> "The pointer is now hidden."
                PointerPolicy.AUTO -> "The pointer is now automatic — shown during guidance."
            }
            is PointerPolicyStore.SetResult.Unchanged -> when (policy) {
                // Unchanged SHOW still renders the proof — "already on"
                // with nothing visible was the reported symptom.
                PointerPolicy.SHOW -> pointerShowAck()
                else -> "The pointer is already $label."
            }
            is PointerPolicyStore.SetResult.Failed ->
                "I could not save that — the pointer setting is unchanged."
        }
    }

    /**
     * In-app routing (fix plan item 5, pointer H5): the injected layer
     * is authoritative when present (the floating session always passes
     * its own). In-app sessions are constructed with null and resolve
     * the process-wide layer lazily — the overlay service registers it
     * in onCreate and clears it in onDestroy, so a null here means
     * guidance genuinely is not running yet, never a stale capture
     * from construction time (the service may start after the screen).
     * This is the minimal safe route: no constructor change in the
     * screens, and a layer registered after the session started still
     * reaches it.
     */
    private fun effectiveLayer(): InteractionLayer? =
        interactionLayer ?: InteractionLayerRegistry.get()

    /**
     * Stage 2C (§5.2 P2): voice stop. Halts via the layer when a sequence
     * is active; the honest "Nothing is running." when it is not — never a
     * silent no-op, never HALT when the layer is absent.
     */
    private fun handleHaltSequence() {
        // AOIE Phase 5 (audit open question (b)): a plan run is not
        // an interaction-layer sequence, so the old code answered
        // "Nothing is running" and set no flag while a plan was
        // executing — the stop never reached it. The shared registry
        // is set on EVERY voice stop; the executor (via PlanWiring's
        // fallback signal and the floating host's signal) reads it.
        // The layer halt still runs when a sequence is active.
        com.loom.assistant.core.common.PlanStopRegistry.requestStop()
        val layer = effectiveLayer()
        val planRunning = try {
            com.loom.assistant.core.common.PlanRunState.isPlanRunning
        } catch (_: Exception) {
            false
        }
        if (layer != null && layer.isSequenceActive) {
            layer.halt(HaltSource.VOICE)
            speakStatus("Stopped.")
        } else if (planRunning) {
            speakStatus("Stopped.")
        } else {
            speakStatus("Nothing is running.")
        }
    }

    /** Stage 2C (§2a A4): "guide me to <x>" → layer.startGuidance. */
    private fun handleGuideTo(
        text: String,
        origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    ) {
        val layer = effectiveLayer()
        val target = VoiceCommands.extractGuideTarget(text)
        when {
            layer == null ->
                speakStatus("Guidance is not available right now.")
            target.isBlank() ->
                speakStatus("Guide you to what?")
            else -> {
                // MASTER F6a — the host notes the provenance before
                // the layer call (the layer carries only the query).
                host.noteGuideOrigin(origin)
                layer.startGuidance(target)
                speakStatus("Guiding you to $target.")
            }
        }
    }

    /**
     * Stage 2C (§2a A4): "guide next"/"next step" and "guide back"/
     * "previous step". Routed to the host's live guidance session through
     * the layer; the host returns the honest spoken status (step position
     * or the failure reason).
     */
    private fun handleGuideStep(next: Boolean) {
        val layer = effectiveLayer()
        if (layer == null || !layer.isSequenceActive) {
            speakStatus("No active guidance session.")
            return
        }
        speakStatus(layer.guideStep(next) ?: "No active guidance session.")
    }

    /** Stage 2C (§2d D2): "tap it for me" → demonstration on the guided target. */
    private fun handleDemoTap(origin: RequestOrigin = RequestOrigin.DIRECT_USER) {
        val layer = effectiveLayer()
        when {
            layer == null ->
                speakStatus("Demonstration is not available right now.")
            !layer.isSequenceActive ->
                speakStatus("Guide me to something first, then say tap it for me.")
            else -> {
                // MASTER F6b — the host notes the provenance before
                // the layer call (the layer carries only the goal).
                host.noteDemoOrigin(origin)
                layer.startDemonstration(layer.activeQuery ?: "tap the target")
                speakStatus("Setting up the demonstration.")
            }
        }
    }

    /** Stage 2C (§2d D2): "show me how to <x>" → layer.startDemonstration. */
    private fun handleDemoSequence(
        text: String,
        origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    ) {
        val layer = effectiveLayer()
        val goal = VoiceCommands.extractDemoGoal(text)
        when {
            layer == null ->
                speakStatus("Demonstration is not available right now.")
            goal.isBlank() ->
                speakStatus("Show you how to do what?")
            else -> {
                // MASTER F6b — see handleDemoTap.
                host.noteDemoOrigin(origin)
                layer.startDemonstration(goal)
                speakStatus("Setting up the demonstration for $goal.")
            }
        }
    }

    /**
     * Capability-API fix: model-requested capabilities dispatch through the
     * EXISTING private handlers above — the same code voice commands use.
     * The synthetic phrasings only feed the target/goal extractors; they
     * are never spoken or stored. Only pointer/guide/demo/search/move
     * capabilities can arrive here (the parser's allowlist); lifecycle
     * actions cannot.
     */
    private fun dispatchModelAction(action: ModelAction) {
        when (val cap = action.capability) {
            is LoomCapability.PointerShow ->
                executePointerPolicy(VoiceCommand.POINTER_SHOW)
            is LoomCapability.PointerHide ->
                executePointerPolicy(VoiceCommand.POINTER_HIDE)
            is LoomCapability.PointerAuto ->
                executePointerPolicy(VoiceCommand.POINTER_AUTO)
            is LoomCapability.PointerMove -> {
                val layer = effectiveLayer()
                when {
                    layer == null ->
                        speakStatus("Pointer movement isn't available right now.")
                    else -> speakStatus(layer.nudgePointer(cap.direction))
                }
            }
            is LoomCapability.GuideTo ->
                handleGuideTo("guide me to ${cap.target}", RequestOrigin.MODEL)
            is LoomCapability.GuideNext -> handleGuideStep(next = true)
            is LoomCapability.GuideBack -> handleGuideStep(next = false)
            is LoomCapability.DemoTap -> handleDemoTap(RequestOrigin.MODEL)
            is LoomCapability.DemoSequence ->
                handleDemoSequence("show me how to ${cap.goal}", RequestOrigin.MODEL)
            is LoomCapability.LaunchApp -> {
                // MASTER F3c: a model-proposed launch is an attempt
                // like a matched one — recorded, never assumed.
                // MASTER F4a — it also goes through the plan path
                // (MODEL origin: the per-sequence cover card stands).
                conversationContext.rememberLaunchAttempt(cap.appName)
                handleSingleActionPlan("open ${cap.appName}", RequestOrigin.MODEL)
            }
            is LoomCapability.GoToApp -> {
                conversationContext.rememberLaunchAttempt(cap.appName)
                handleSingleActionPlan("open ${cap.appName}", RequestOrigin.MODEL)
            }
            // MASTER F1d: model-proposed console follow-ups — the
            // same planner console steps as the deterministic voice
            // commands, so the model can advance a search instead of
            // re-proposing it.
            is LoomCapability.Scroll ->
                handleConsolePlan("scroll ${cap.direction}")
            is LoomCapability.GoBack -> handleBack()
            is LoomCapability.TapTarget ->
                handleConsolePlan("tap ${cap.target}")
            is LoomCapability.PressEnter ->
                handleConsolePlan("press enter")
            // Master §2 (items 4–5): model-selected search / assistant
            // move — same handlers as the matched commands, same
            // context resolution, same host path. MASTER F4a: the
            // search now goes through the plan path with MODEL
            // origin (this fixes the pre-existing bug where a
            // model-proposed search was routed as DIRECT_USER).
            is LoomCapability.SearchInApp ->
                handleSearchQuery(
                    cap.query,
                    cap.appName.ifBlank {
                        conversationContext.appForFollowUpSearch()
                    },
                    RequestOrigin.MODEL,
                )
            is LoomCapability.AssistantMove ->
                handleAssistantMove("", regionName = cap.regionName)
            // Dictated messaging + calls (2026-11-05, fix-plan item
            // 12): model-selected send/call — same handlers as the
            // matched commands, same host path, MODEL origin. The
            // per-message / per-call card still stands (message.send
            // / call.place are never grant-eligible), so a model
            // proposal cannot silently send or dial.
            is LoomCapability.SendWhatsApp ->
                handleMessage(
                    MessageChannel.WHATSAPP, cap.recipient, cap.text,
                    RequestOrigin.MODEL,
                )
            is LoomCapability.SendSms ->
                handleMessage(
                    MessageChannel.SMS, cap.recipient, cap.text,
                    RequestOrigin.MODEL,
                )
            is LoomCapability.PlaceCall ->
                handleCallTarget(cap.target, RequestOrigin.MODEL)
            // Stage 2D: a model-proposed plan — same handler as the
            // matched command, MODEL origin. The model may PROPOSE;
            // the per-sequence cover card still stands for MODEL
            // plans, and a stored grant never applies to MODEL
            // origin, so a model guess can never silently run.
            is LoomCapability.PlanTask ->
                handlePlanTask(cap.goal, RequestOrigin.MODEL, cap.transparency)
        }
    }

    /**
     * Master §2 (item 4): "search for <query> [in <app>]". The app
     * resolves: explicit in the utterance, else the last-turn context
     * (ConversationContext), else the manager asks which app — never a
     * guess. The host owns execution and the outcome ack; when no host
     * handles it (in-app / typed-chat sessions), the routing message
     * names the exact voice phrase instead of denying the capability.
     */
    private fun handleSearch(text: String) {
        handleSearchQuery(
            VoiceCommands.extractSearchQuery(text),
            VoiceCommands.extractSearchApp(
                text, conversationContext.appForFollowUpSearch(),
            ).ifBlank { null },
        )
    }

    private fun handleSearchQuery(
        query: String,
        app: String?,
        origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    ) {
        // Device-failure fix (2026-11-13, F2): the goal is FIRST
        // formed here ("search for $query in $app") — meta-speech
        // is cut from the query/app BEFORE that string exists, so
        // the narrated "Working on it" line and the planner never
        // see it. The voice path's extractors already cut it; this
        // covers the model-capability path, whose query/app arrive
        // uncut. A query that strips to blank asks the question
        // instead of searching for the remark.
        val cleanQuery = VoiceCommands.stripMetaSpeech(query)
        val cleanApp = app?.let { VoiceCommands.stripMetaSpeech(it) }?.takeIf { it.isNotBlank() }
        handleSearchQueryClean(cleanQuery, cleanApp, origin)
    }

    private fun handleSearchQueryClean(
        query: String,
        app: String?,
        origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    ) {
        when {
            query.isBlank() -> {
                // MASTER F3b: the query question, asked at most once
                // per task (the planner's askedApp guard, for the
                // single-command path). A repeat of the same bare
                // search fails honestly; a turn naming an app resets
                // the flag (new task — see noteSearchApp).
                if (!app.isNullOrBlank()) conversationContext.noteSearchApp(app)
                if (conversationContext.askedSearchQuery) {
                    speakStatus(
                        "I still don't know what to search for — say " +
                            "the search words and I'll carry on from there.",
                    )
                } else {
                    conversationContext.markSearchQueryAsked()
                    speakStatus(VoiceCommands.SEARCH_QUERY_QUESTION)
                }
            }
            app.isNullOrBlank() -> {
                // MASTER F3b: the app question, asked at most once
                // per task. A repeat of the SAME query fails honestly;
                // a NEW query resets the flag (new task).
                conversationContext.noteSearchQuery(query)
                if (conversationContext.askedSearchApp) {
                    speakStatus(
                        "I still don't know which app to search in — " +
                            "I asked once already. Say the app name and " +
                            "I'll carry on from there.",
                    )
                } else {
                    conversationContext.markSearchAppAsked()
                    speakStatus(VoiceCommands.SEARCH_APP_QUESTION)
                }
            }
            else -> {
                conversationContext.rememberSearch(app)
                // MASTER F4a — single-action search goes through the
                // plan path (blanket cover, stop handling, status),
                // not the standalone search executor. The plan stops
                // at the verified results list (SHOW_RESULTS); the old
                // standalone floating search opened the first result —
                // the plan behaviour is the intended consolidation
                // (Universal Console rule).
                handleSingleActionPlan(
                    "search for $query in $app",
                    origin,
                    fallback = "I can't search from this session — start the " +
                        "floating assistant and say 'search for $query in $app' by voice.",
                )
            }
        }
    }

    /**
     * MASTER F1d: a console follow-up ("scroll down", "go back",
     * "tap X", "press enter") as a small planner task. The goal rides
     * on the CURRENT record (carried, never replaced) so the
     * follow-up neither supersedes the active task nor loses its app
     * — a scroll after a search keeps the search's context. The
     * planner decomposes it into OBSERVE + the console step
     * (SCROLL / GO_BACK / TAP_TARGET / SUBMIT); the executor verifies
     * it like any other plan. No new execution path is invented.
     */
    private fun handleConsolePlan(goal: String) {
        val request = PlanRequest(
            goal = goal,
            origin = RequestOrigin.DIRECT_USER,
            transparency = false,
            contextApp = conversationContext.appForFollowUpSearch(),
            taskRecord = conversationContext.activeTask,
        )
        // D-02: an accepted plan means its outcome is still to
        // come — a silent listen in the meantime suspends, never
        // ends, so the spoken outcome is not lost.
        if (host.onPlanRequested(request)) {
            planOutcomePending = true
        } else {
            speakStatus(
                "I can't do that from this session — start the " +
                    "floating assistant and say it by voice.",
            )
        }
    }

    /**
     * MASTER F4a — a matched single action (launch, search, message,
     * call) as a single-action PLAN, not a standalone executor call.
     * The plan path is the ONE covered execution path: the DIRECT_USER
     * blanket cover spans it (no per-action CONFIRM card storm),
     * TaskStatus posts to chat/speech, the stop registry is honoured
     * at every step boundary, and the pointer drives it.
     *
     * Deliberately NOT handlePlanTask: that creates a TaskRecord via
     * startTask, which seeds lastAppName immediately — a failed launch
     * would then poison follow-ups (the F3c regression). A single
     * action carries the CURRENT record (carried, never replaced) so
     * a follow-up keeps its context, and nothing seeds the app until
     * the plan's own outcome promotes it (F4d). Goals are the
     * planner's canonical shapes, verified against TaskPlanner's rule
     * shapes: "open <app>", "search for <query> in <app>", "send
     * whatsapp message to <recipient> saying <text>", "send sms to
     * <recipient> saying <text>", "call <target>".
     *
     * [fallback] is spoken when no host runs plans; null keeps the
     * generic routing message.
     */
    private fun handleSingleActionPlan(
        goal: String,
        origin: RequestOrigin,
        fallback: String? = null,
        // D-05 (refined): the verbatim instruction for a DIRECT_USER
        // launch ("open my story"), so the launch is remembered as
        // the last task "do it" / "the task" resolves to. Only
        // launches pass this — a launch is an ATTEMPT until confirmed
        // (MASTER F3c), so its record goes to the LAST task only (see
        // ConversationContext.noteLastTask), never the active task and
        // never with an app. MODEL-origin actions are never David's
        // task and create no record; searches, messages and calls
        // keep the old behaviour — they ride the current record,
        // never replace it.
        recordInstruction: String? = null,
    ) {
        if (origin == RequestOrigin.DIRECT_USER && recordInstruction != null) {
            conversationContext.noteLastTask(
                TaskRecord(
                    instruction = recordInstruction,
                    app = null, // a launch names no task app until confirmed
                    appPackage = null, // resolved by the planner
                    query = null, // extracted by the planner
                    origin = origin,
                ),
            )
        }
        val request = PlanRequest(
            goal = goal,
            origin = origin,
            transparency = false,
            contextApp = conversationContext.appForFollowUpSearch(),
            taskRecord = conversationContext.activeTask,
        )
        // D-02: an accepted plan means its outcome is still to
        // come — a silent listen in the meantime suspends, never
        // ends, so the spoken outcome is not lost.
        if (host.onPlanRequested(request)) {
            planOutcomePending = true
        } else {
            speakStatus(
                fallback ?: "I can't do that from this session — start the " +
                    "floating assistant and say it by voice.",
            )
        }
    }

    /**
     * MASTER F1d: "go back" — a live guidance session owns the step
     * (its back IS the working route, and "go back" with no session
     * is the video's "No active guidance session" failure); with no
     * session it is a planner GO_BACK step on the current screen.
     */
    private fun handleBack() {
        val layer = effectiveLayer()
        if (layer != null && layer.isSequenceActive) {
            handleGuideStep(next = false)
        } else {
            handleConsolePlan("go back")
        }
    }

    /**
     * MASTER F3c: the launch outcome, from the host that ran the
     * launch. Hosts call this with the spoken app name when the
     * launcher reports back — success promotes the pending attempt
     * to the last-turn app, failure drops it with nothing recorded.
     * (The floating host speaks "Opened <label>." through [speakNow],
     * which calls this same promotion via [noteLaunchOutcomeFromSpeech];
     * this method is the explicit seam for hosts that report the
     * outcome directly — the overlay/chat/home one-liners belong to
     * a later wave, since the overlay file is not this slice's.)
     */
    fun noteLaunchOutcome(appName: String, success: Boolean) {
        if (success) conversationContext.confirmLaunch(appName)
        else conversationContext.failLaunch(appName)
    }

    /**
     * MASTER F3c: the floating host speaks every launch outcome
     * through [speakNow] ("Opened <label>." on success). That exact
     * sentence is the success signal — the same "Opened " prefix the
     * plan dispatcher already parses as a launch success — so a
     * matching pending attempt is promoted here, and only here: any
     * other wording (including every failure sentence) promotes
     * nothing. The promotion also requires the attempt to still be
     * current (no newer turn since it was dispatched). Aliased
     * labels ("YT Music" for spoken "youtube music") do not match
     * by text and stay unpromoted until the host reports the outcome
     * via [noteLaunchOutcome] with the spoken name.
     */
    private fun noteLaunchOutcomeFromSpeech(text: String) {
        val trimmed = text.trim().removeSuffix(".").trim()
        // MASTER F4d — plan summaries: a completed single-action
        // launch plan reports "Done — completed all N step(s) for:
        // open <app>." (or "Done — completed the task for: …" when a
        // replan superseded a step). The goal after "for: " names the
        // app; confirmLaunch's attempt-match + generation guards make
        // this a no-op for composite plans (no attempt recorded) and
        // for anything else. Partial ("Finished with…") and stopped
        // runs never start with "Done —", so they promote nothing.
        // (The floating host also wires this directly from its
        // request + ack — a double promotion is a no-op, since
        // confirmLaunch clears the attempt on the first call.)
        if (trimmed.startsWith("Done —", ignoreCase = true) && trimmed.contains("for: ")) {
            val goal = trimmed.substringAfter("for: ").trim()
            val app = VoiceCommands.extractLaunchApp(goal)
            if (app.isNotBlank()) {
                conversationContext.confirmLaunch(app)
                return
            }
        }
        if (!trimmed.startsWith("Opened ", ignoreCase = true)) return
        val label = trimmed.substring("Opened ".length).trim()
        if (label.isBlank()) return
        conversationContext.confirmLaunch(label)
    }

    /**
     * Dictated messaging + calls (2026-11-05, fix-plan item 12):
     * "send WhatsApp to X saying Y" / "text X saying Y". The exact
     * dictated words ride verbatim from the extractors into the host
     * — never reworded. The host owns execution and the outcome ack;
     * when no host handles it (in-app / typed-chat sessions), the
     * routing message names the floating assistant instead of denying
     * the capability. The model path calls the 4-arg form with the
     * already-parsed recipient + text (MODEL origin — the per-message
     * card still stands).
     */
    private fun handleMessage(channel: MessageChannel, text: String, origin: RequestOrigin) {
        handleMessage(
            channel,
            VoiceCommands.extractMessageTarget(text),
            VoiceCommands.extractMessageText(text),
            origin,
        )
    }

    private fun handleMessage(
        channel: MessageChannel,
        recipient: String,
        text: String,
        origin: RequestOrigin,
    ) {
        when {
            recipient.isBlank() ->
                speakStatus("Who should I message?")
            text.isBlank() ->
                speakStatus("What should I say to $recipient?")
            // MASTER F4a — single-action send goes through the plan
            // path (blanket cover, stop handling, status), not the
            // standalone message executor. The MESSAGE_SEND step is
            // never covered and still cards per message (F4c).
            else -> {
                val verb = if (channel == MessageChannel.WHATSAPP) {
                    "send whatsapp message"
                } else {
                    "send sms"
                }
                handleSingleActionPlan(
                    "$verb to $recipient saying $text",
                    origin,
                    fallback = "I can't send messages from this session — start the " +
                        "floating assistant and say it by voice.",
                )
            }
        }
    }

    /**
     * Dictated messaging + calls (2026-11-05, fix-plan item 12):
     * "call X" — a named contact routes through the Phone app, a
     * dictated number through the ACTION_DIAL fallback; the executor
     * decides, the manager only forwards the target. Same host /
     * routing honesty as [handleMessage].
     */
    private fun handleCall(text: String, origin: RequestOrigin) {
        handleCallTarget(VoiceCommands.extractCallTarget(text), origin)
    }

    private fun handleCallTarget(target: String, origin: RequestOrigin) {
        when {
            target.isBlank() ->
                speakStatus("Who should I call?")
            // MASTER F4a — single-action call goes through the plan
            // path (blanket cover, stop handling, status), not the
            // standalone call executor. The CALL_PLACE step is never
            // covered and still cards per call (F4c).
            else -> handleSingleActionPlan(
                "call $target",
                origin,
                fallback = "I can't place calls from this session — start the " +
                    "floating assistant and say it by voice.",
            )
        }
    }

    /**
     * Stage 2D: route a multi-step instruction to the planner host.
     * [goal] is the instruction verbatim (transparency phrases
     * already stripped by the voice extractor); [origin] is
     * DIRECT_USER for David's own matched words and MODEL for a
     * model-proposed plan — never the other way round. The host owns
     * the plan and the outcome ack (no plan-approval step: the plan
     * is LOOM's internal mechanism, David never sees or approves it);
     * when no host handles it (in-app / typed-chat sessions), the
     * routing message names the floating assistant instead of
     * denying the capability.
     * Nothing is spoken here before the host confirms or honestly
     * reports — no "Opening…", no "Done", no "I've requested".
     *
     * Task-record fix (2026-11-07): the ONE task record is created
     * HERE, when the instruction is understood — see [TaskRecord].
     * The full instruction seeds it (the app is extracted from the
     * goal text, original casing kept); a bare follow-up ("play
     * music") resumes the active record instead of starting from
     * scratch — but only while it is still in sync with the
     * last-turn app, so launching another app supersedes the task;
     * an app-less plan creates a record marked as waiting for the
     * one app question, so David's answer can fill it (see the
     * answer-turn check in [handleResult]). The record rides on
     * the [PlanRequest] to the planner, which adopts it — the
     * executor then carries that SAME record unchanged through
     * approval and replan. The plan's origin always stays [origin]
     * here: a resumed MODEL task never inherits DIRECT_USER
     * blanket approval from the record it adopted.
     */
    private fun handlePlanTask(goal: String, origin: RequestOrigin, transparency: Boolean) {
        if (goal.isBlank()) {
            speakStatus("What should I do?")
            return
        }
        val spokenApp = VoiceCommands.extractPlanApp(goal)
        val contextApp = conversationContext.appForFollowUpSearch()
        val record: TaskRecord = when {
            spokenApp.isNotBlank() -> {
                val rec = TaskRecord(
                    instruction = goal,
                    app = spokenApp,
                    appPackage = null, // resolved by the planner
                    query = null, // extracted by the planner
                    origin = origin,
                )
                conversationContext.startTask(rec)
                rec
            }
            // Bare follow-up resumes the active record — in sync only.
            // ONLY a bare tail ("play music", "search for Davido")
            // resumes: a full new instruction that names no app
            // ("guide me through turning on dark mode", "search for X
            // and open the first result") is its own task and gets a
            // fresh record below — resuming the old record for it
            // would bolt the old app onto a new instruction.
            // A later launch/search of another app supersedes the
            // task (its app no longer matches the last-turn app).
            (isBarePlay(goal) || isBareSearch(goal)) &&
                conversationContext.activeTask != null &&
                conversationContext.activeTask?.app == contextApp -> {
                conversationContext.activeTask!!
            }
            else -> {
                // App-less plan: the record still gets created, so
                // the one app question and its answer attach to it.
                // It waits for an answer only when the planner will
                // actually have to ask (bare play / bare search with
                // no app anywhere); any other shape asks something
                // else, and its answer is not an app name.
                // A repeat of the same bare goal while the question
                // is already outstanding keeps askedApp — the planner
                // then fails honestly instead of asking twice.
                val askedBefore =
                    conversationContext.activeTask?.askedApp == true
                // The planner will have to ask when the goal is a
                // play/search shape with no app anywhere — bare or
                // with a second clause ("search for X and open …"
                // asks too when no app is named).
                val needsApp = contextApp.isNullOrBlank() &&
                    (isBarePlay(goal) || isSearchShaped(goal))
                val rec = TaskRecord(
                    instruction = goal,
                    app = null,
                    appPackage = null,
                    query = null,
                    origin = origin,
                    askedApp = askedBefore,
                    awaitingApp = needsApp || askedBefore,
                )
                conversationContext.startTask(rec)
                rec
            }
        }
        val request = PlanRequest(
            goal = goal,
            origin = origin,
            transparency = transparency,
            contextApp = contextApp,
            taskRecord = record,
        )
        // D-02: an accepted plan means its outcome is still to
        // come — a silent listen in the meantime suspends, never
        // ends, so the spoken outcome is not lost.
        if (host.onPlanRequested(request)) {
            planOutcomePending = true
        } else {
            speakStatus(
                "I can't run multi-step tasks from this session — start the " +
                    "floating assistant and say it by voice.",
            )
        }
    }

    /** Bare "play …" with no app named — the shape that asks for one. */
    private fun isBarePlay(goal: String): Boolean =
        goal.lowercase().trim().startsWith("play ")

    /**
     * Bare "search …" with no app named AND no second action clause
     * ("search for Davido") — the shape that asks for one app. A
     * search WITH a second clause ("search for X and open the first
     * result") is a full instruction, never a bare follow-up, even
     * though it starts with "search ".
     */
    private fun isBareSearch(goal: String): Boolean {
        val t = goal.lowercase().trim()
        if (!(t.startsWith("search ") || t.startsWith("can you search"))) return false
        val verbs = listOf(
            "play", "search", "tap", "turn", "send", "message", "text",
            "call", "find", "go", "switch", "enable", "disable", "start",
            "show", "watch", "listen", "open",
        ).joinToString("|")
        return !Regex("""\s+and\s+($verbs)\b""").containsMatchIn(t)
    }

    /** Any search-shaped goal (bare or with a second clause). */
    private fun isSearchShaped(goal: String): Boolean {
        val t = goal.lowercase().trim()
        return t.startsWith("search ") || t.startsWith("can you search")
    }

    /**
     * Task-record fix (2026-11-07): is this turn the answer to the
     * one outstanding app question? A short turn that names no
     * command ("YouTube Music") is the answer. Bare yes/no, a
     * question, and anything that matches a command are not — they
     * fall through to normal routing. The window is narrow (only
     * while a record is awaiting its app), and the answer is only
     * ever used as the app slot of THAT record — never as a new task.
     */
    private fun looksLikeAppAnswer(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        val lower = t.lowercase()
        if (lower in setOf("yes", "yeah", "no", "ok", "okay")) return false
        if (t.endsWith("?")) return false
        if (lower.startsWith("what ") || lower.startsWith("why ") ||
            lower.startsWith("how ") || lower.startsWith("who ") ||
            lower.startsWith("when ") || lower.startsWith("where ")
        ) return false
        if (VoiceCommands.match(text) != null) return false
        return t.split(Regex("\\s+")).size <= 4
    }

    /**
     * Master §2 (item 5): "move to the top-left corner" / "move out of
     * the way" / "move the assistant left". The host owns execution and
     * the outcome ack; when no host handles it, the routing message
     * names the exact voice phrase.
     */
    private fun handleAssistantMove(text: String, regionName: String? = null) {
        val region = regionName ?: VoiceCommands.extractAssistantRegionName(text)
        when {
            region == null -> speakStatus(
                "Where should I move — top-left, top-right, bottom-left, " +
                    "bottom-right, or out of the way?",
            )
            !host.onAssistantMoveRequested(region) -> speakStatus(
                "I can't move the assistant from this session — start the " +
                    "floating assistant and say it by voice.",
            )
        }
    }

    /**
     * Stage 2A.1 (D2): brief spoken status that stays inside the session —
     * SPEAKING around the utterance so the TTS-completion chain resumes the
     * loop (or idles) afterwards. Used for truthful non-engine feedback.
     */
    private fun speakStatus(text: String, thenIdle: Boolean = false) {
        // MASTER Wave 4 (F12): the spoken outcome is ALSO the
        // durable record (one TaskResult per voice task, including
        // greetings and matched-command outcomes — previously
        // speak-only). Progress statuses are excluded inside
        // recordAssistant; plan-host appends de-duplicate by
        // last-message equality.
        recordAssistant(text)
        setState(VoiceSessionState.SPEAKING, "Speaking…")
        val finish = {
            mainHandler.post {
                if (thenIdle) setState(VoiceSessionState.IDLE, host.idlePrompt())
                else autoContinue() // SPEAKING -> listen again, the loop continues
            }
            Unit
        }
        pipeline.speak(text, onDone = finish, onError = { finish() })
    }

    /**
     * Stage 2C (F): host-spoken status from service-owned paths (halt
     * acknowledgements on the notification / stop-window paths, guidance
     * failure reasons). No-op unless a voice session is live — the service
     * never starts a session just to speak.
     *
     * Phase 2 (E.3-3): never speaks over a user-paused session — speakNow
     * must not silently resume or talk across PAUSED.
     */
    fun speakNow(text: String) {
        // MASTER F3c: every launch outcome spoken through this session
        // passes here first (the floating host's onResult) — a success
        // sentence promotes the pending launch attempt, nothing else.
        noteLaunchOutcomeFromSpeech(text)
        if (state == VoiceSessionState.PAUSED) return
        // D-02: an outcome ack is the arrival the suspended listen
        // was waiting for — clear the pending flag and resume the
        // loop through speakStatus (which auto-continues afterwards).
        planOutcomePending = false
        if (listenSuspended) {
            listenSuspended = false
            if (isActive) speakStatus(text)
            return
        }
        if (isActive) speakStatus(text)
    }

    /**
     * Phase 2 (§4.2 disambiguation): "shut down voice" ends the voice session
     * with an explicit ack — "Voice session ended — the app is still running.
     * Say 'loom, shut down' for a full shutdown." — so it can never be
     * confused with a full shutdown. The ack is a one-shot pipeline speak:
     * the session is already IDLE, so it must not re-enter the loop.
     */
    private fun handleEndVoice() {
        end()
        pipeline.speak(
            "Voice session ended — the app is still running. " +
                "Say loom, shut down for a full shutdown.",
            onDone = {},
            onError = {},
        )
    }

    /** Stage 2A.1 (D2): "stop talking" — truthful per-state, never silent. */
    private fun handleStopTalking() {
        when (state) {
            VoiceSessionState.SPEAKING -> stopUtterance()
            VoiceSessionState.LISTENING, VoiceSessionState.PROCESSING ->
                speakStatus("Nothing is playing right now.")
            else -> speakStatus("Nothing is playing.", thenIdle = true)
        }
    }

    /**
     * Stage 2A.1 (D2): "start listening" — clears the stop-generation (D4).
     * Phase 2 (E.3-4): the recognizer path routes through the permission
     * check (ensurePermissionThenBeginListen) — the mic is never opened on
     * an unchecked permission.
     */
    private fun handleStartListening() {
        when (state) {
            VoiceSessionState.LISTENING ->
                speakStatus("Already listening.")
            VoiceSessionState.PAUSED -> resume()
            VoiceSessionState.ENDING -> return
            else -> {
                if (!LifecycleController.isDispatchAllowed()) {
                    setState(
                        VoiceSessionState.ERROR,
                        "Assistant is not active right now.",
                    )
                    return
                }
                stopGeneration = 0
                ensurePermissionThenBeginListen()
            }
        }
    }

    /**
     * Phase 2 (§C): mic-state grounding for the engine. The old version was
     * hardcoded prompt text ("microphone permission granted, speech
     * recognition active") — the audit's R4 contradiction. Now the sentence
     * is DERIVED from the live controller state every turn:
     * - this.state (session layer),
     * - pipeline.state (the pipeline owns recognizer liveness — LISTENING,
     *   or THINKING right after a live capture produced this turn),
     * - VoiceSessionOwner (atomic — a session that does not own the mic is
     *   not live),
     * - lastMicPermission (the cached truthful check result — never assumed).
     * On disagreement the conservative verdict (mic off) wins. A stale flag
     * claiming "live" while the controller says released ⇒ the context
     * follows the controller (§7-D).
     */
    private fun buildSystemContext(): String {
        val inputs = MicGrounding.Inputs(
            session = when (state) {
                VoiceSessionState.LISTENING -> MicGrounding.SessionMic.LISTENING
                VoiceSessionState.PROCESSING -> MicGrounding.SessionMic.PROCESSING_TURN
                else -> MicGrounding.SessionMic.IDLE
            },
            recognizerLive = pipeline.state == VoiceState.LISTENING ||
                pipeline.state == VoiceState.THINKING,
            micOwned = if (floating) VoiceSessionOwner.floatingActive
                       else VoiceSessionOwner.inAppActive,
            permissionGranted = lastMicPermission,
            softFailures = softFailures,
        )
        return MicGrounding.derive(inputs).text
    }

    /** End: mic provably off, TTS stopped, queue dropped, resources released. */
    fun end() {
        if (state == VoiceSessionState.IDLE) return
        setState(VoiceSessionState.ENDING, "")
        stopGeneration++
        generationId++
        cancelRetry()
        pipeline.stopListening()
        pipeline.stopSpeaking()
        // Phase 2 (E.3-2): the TTS queue previously drained after end() —
        // queued utterances spoke into the next session. Dropped here.
        pipeline.clearPendingSpeech()
        setState(VoiceSessionState.IDLE, host.idlePrompt())
    }

    // ---------- internal loop ----------

    private fun setState(s: VoiceSessionState, status: String) {
        state = s
        // Phase 2 (E.3-5): the session→controller state mirror — the
        // controller's read-only Layer-2 channel. The enum names match the
        // controller's SESSION_MIRROR_NAMES exactly (IDLE/LISTENING/…), so
        // this call can never drift. A live mirror arriving while lifecycle
        // != ACTIVE is dropped as stale by the controller (impossible #1)
        // with a teardown request — never a lifecycle transition.
        LifecycleController.reportSessionState(s.name)
        mainHandler.post { host.onSessionState(s, status) }
    }

    private fun beginListen(resuming: Boolean = false) {
        // Phase 2 (E.3-1): the PAUSED early-return stays for every path
        // EXCEPT the explicit resume path — resume() passes resuming=true.
        if (state == VoiceSessionState.PAUSED && !resuming) return
        if (state == VoiceSessionState.ENDING) return
        // D-02: the mic is live again — a suspension ends here.
        listenSuspended = false
        if (!pipeline.isSttAvailable(appContext)) {
            setState(
                VoiceSessionState.ERROR,
                "Speech recognition unavailable on this device.",
            )
            return
        }
        setState(VoiceSessionState.LISTENING, "Listening…")
        lastPartialText = ""
        lostTurnRecorded = false
        pipeline.startListening(
            onPartial = { partial ->
                // MASTER Wave 3 (F16): retain the latest partial for
                // the lost-command diagnosis (see lastPartialText).
                lastPartialText = partial
                mainHandler.post { host.onPartialText(partial) }
            },
            onResult = { text -> mainHandler.post { handleResult(text) } },
            onError = { msg -> mainHandler.post { handleSttError(msg) } },
        )
    }

    private fun handleResult(text: String) {
        if (state != VoiceSessionState.LISTENING) return // stale callback
        generationId++
        val turnId = generationId

        // MASTER Wave 4 (F12): the heard transcript is stored FIRST,
        // for every turn — matched commands, clarifications, mode
        // switches and failures included. The old code stored the
        // user line only on the engine fallthrough below, so a
        // matched "open YouTube" left no user line in the chat at
        // all (C-03). Blank text stores nothing (the lost-turn
        // record below covers it, with the last partial).
        if (text.isNotBlank()) recordHeard(text)

        // Dictated messaging + calls (2026-11-05, fix-plan item 12):
        // bulk / automated messaging is refused at PARSE time, before
        // match() is consulted — "send WhatsApp to everyone saying
        // hi" never becomes a send command, never reaches the
        // executor, and never reaches the engine as a capability
        // request. One recipient per invocation, always.
        if (VoiceCommands.isBulkMessageRequest(text)) {
            speakStatus("I don't send bulk messages — tell me one person at a time.")
            return
        }

        // Task-record fix (2026-11-07): the answer to the one app
        // question. While a record is waiting for its app, a short
        // turn that names no command is the answer — fill the slot
        // and re-issue the ORIGINAL instruction with the SAME record
        // (its origin preserved, so a model-guessed task still goes
        // through its cover card). This runs before match()/engine so
        // the answer can never be re-derived as a new bare task.
        conversationContext.activeTask?.takeIf { it.awaitingApp }?.let { pending ->
            if (looksLikeAppAnswer(text)) {
                val answer = text.trim()
                conversationContext.answerActiveTaskApp(answer)
                val filled = conversationContext.activeTask ?: pending.withApp(answer)
                handlePlanTask(filled.instruction, filled.origin, transparency = false)
                return
            }
        }

        // Voice commands are handled locally — never reach the chat engine.
        // MASTER F3b: any matched command is a new instruction, so the
        // bare-launch question is live again after it.
        val cmd = VoiceCommands.match(text)
        if (cmd != null) {
            conversationContext.clearLaunchAsked()
            // D-02: a matched turn is done from the session's side —
            // the listen that stays open afterwards is an idle listen
            // until David speaks again (silence in it ends quietly).
            idleListen = true
        }
        when (cmd) {
            VoiceCommand.SHUTDOWN, VoiceCommand.EXIT_VOICE -> {
                end()
                host.onCommandAction(cmd)
                return
            }
            // Correction pass: voice-session-only shutdown — ends the session,
            // never the app dialog. Phase 2 (§4.2): the ack is explicit so
            // "shut down voice" can never be confused with a full shutdown.
            VoiceCommand.END_VOICE -> {
                handleEndVoice()
                return
            }
            // Phase 2 (plan §4.2-C): GO_SILENT — stop in-flight TTS, drop the
            // queue, set the silent flag. Mic may stay live; the session,
            // views and sequences are untouched.
            VoiceCommand.GO_SILENT -> {
                pipeline.stopSpeaking()
                pipeline.clearPendingSpeech()
                silent = true
                speakStatus("Silent mode on — say speak again and I'll talk.")
                return
            }
            // Phase 2 (plan §4.2-C): "speak again" clears the silent flag.
            VoiceCommand.SPEAK_AGAIN -> {
                silent = false
                speakStatus("I'll speak again.")
                return
            }
            // Phase 2 (plan §4.2-D): HIDE YOURSELF — the views are host-owned;
            // the manager forwards and touches nothing (session, mic, TTS and
            // sequences stay exactly as they are). The host removes the
            // floating views; the controller stays ACTIVE (hidden).
            VoiceCommand.HIDE_YOURSELF -> {
                host.onCommandAction(cmd)
                return
            }
            // Phase 2 (plan §4.2-D): SHOW ASSISTANT — re-binds the existing
            // floating presentation only. Never reactivates, never mics, no
            // lifecycle transition; the host guards on lifecycle == ACTIVE.
            VoiceCommand.SHOW_ASSISTANT -> {
                host.onCommandAction(cmd)
                return
            }
            // ACTIVATE ASSISTANT MODE — manager-direct (NOT host-forwarded:
            // in-app hosts no-op SHOW-class commands, so a forward would
            // silently do nothing). Plans via AssistantActivation, applies
            // via ModeController.applyMode, speaks ONLY from the verified
            // ModeResult. Mic ownership UNCHANGED: starts service/views
            // only, never a floating voice session, never VoiceSessionOwner.
            VoiceCommand.ACTIVATE_ASSISTANT_MODE -> {
                executeAssistantActivation()
                return
            }
            // Phase 2 (plan §4.2-F): DEACTIVATE ASSISTANT MODE — the manager
            // does its part (end session → mic off → TTS stopped + queue
            // cleared) and forwards; the host performs halt, AuthGate
            // purgeAll, ActionQueue drain, view hiding, and the controller
            // transition to ASSISTANT_MODE_INACTIVE.
            VoiceCommand.DEACTIVATE_ASSISTANT_MODE -> {
                end()
                host.onCommandAction(cmd)
                return
            }
            // Stage 2A.1 (D2): "switch to keyboard" / "type instead" — the
            // existing text-mode path, conversation preserved.
            VoiceCommand.SWITCH_KEYBOARD -> {
                end()
                host.onCommandAction(cmd)
                return
            }
            // Session/state awareness (2026-11-11, OBSERVE-FIRST):
            // "pause" while a Pause control is on the live screen is
            // about the music/video playing — forward it as a plan
            // task (the planner's deterministic PLAY_PAUSE branch
            // taps pause where it is) instead of pausing LOOM. With
            // no player control on screen, the session pause, as
            // before — bare "pause" alone keeps meaning voice-session
            // pause (testPauseStaysSessionPause).
            VoiceCommand.PAUSE -> {
                if (host.mediaControlOnScreen("pause")) {
                    handlePlanTask(text, RequestOrigin.DIRECT_USER, transparency = false)
                } else {
                    pause()
                }
                return
            }
            VoiceCommand.RESUME -> {
                // Same rule, mirrored: a Play control on the live
                // screen means "resume" is about the paused media.
                if (host.mediaControlOnScreen("play")) {
                    handlePlanTask(text, RequestOrigin.DIRECT_USER, transparency = false)
                } else {
                    resume()
                }
                return
            }
            // Stage 2A.1 (D2): "stop talking" was a silent no-op while
            // LISTENING (it only acted during SPEAKING, which a transcript
            // can never coincide with). Now truthful per-state feedback.
            VoiceCommand.STOP -> {
                handleStopTalking()
                return
            }
            // Correction pass: "stop listening" was a silent no-op while
            // LISTENING (STOP only acted when SPEAKING). Now a real action.
            VoiceCommand.STOP_LISTENING -> {
                stopListening(speakIfIdle = true)
                return
            }
            // Stage 2A.1 (D2): "start listening" / "resume listening".
            VoiceCommand.START_LISTENING -> {
                handleStartListening()
                return
            }
            // Correction pass: mode switches previously fell through to the
            // LLM, which would SAY it switched while changing nothing.
            VoiceCommand.MODE_ORB, VoiceCommand.MODE_CHARACTER,
            VoiceCommand.MODE_INVISIBLE -> {
                executeModeSwitch(cmd)
                return
            }
            // Stage 2B (D3a): pointer policy — persisted first, and the
            // spoken ack comes only from the persisted read-back.
            VoiceCommand.POINTER_SHOW, VoiceCommand.POINTER_HIDE,
            VoiceCommand.POINTER_AUTO -> {
                executePointerPolicy(cmd)
                return
            }
            // Stage 2C (§5.2 P2): interrupt-first. HALT_SEQUENCE routes to
            // the interaction layer BEFORE the conversational generator
            // (the Stage 2A.1 anti-roleplay rule). Bare "stop" halts only
            // when a sequence is active — otherwise an honest no-op, never
            // a fake halt and never the LLM.
            VoiceCommand.HALT_SEQUENCE -> {
                handleHaltSequence()
                return
            }
            // Stage 2C (§2a A4 / §2d D2): guidance + demonstration entries —
            // deterministic layer routing, never the conversational model.
            VoiceCommand.GUIDE_TO -> {
                handleGuideTo(text)
                return
            }
            VoiceCommand.GUIDE_NEXT -> {
                handleGuideStep(next = true)
                return
            }
            VoiceCommand.GUIDE_BACK -> {
                handleGuideStep(next = false)
                return
            }
            VoiceCommand.DEMO_TAP -> {
                handleDemoTap()
                return
            }
            VoiceCommand.DEMO_SEQUENCE -> {
                handleDemoSequence(text)
                return
            }
            // MASTER F1d: deterministic console follow-ups — the
            // planner console steps (SCROLL / GO_BACK / TAP_TARGET /
            // SUBMIT), via handleConsolePlan (carried record, never
            // a superseding one). BACK prefers the live guidance
            // session when one is active (see handleBack).
            VoiceCommand.SCROLL -> {
                val dir = VoiceCommands.extractScrollDirection(text) ?: "down"
                handleConsolePlan("scroll $dir")
                return
            }
            VoiceCommand.BACK -> {
                handleBack()
                return
            }
            VoiceCommand.TAP -> {
                handleConsolePlan("tap ${VoiceCommands.extractTapTarget(text)}")
                return
            }
            VoiceCommand.ENTER -> {
                handleConsolePlan("press enter")
                return
            }
            // "fix everything": POINTER_MOVE — display-only nudge via
            // the layer; no layer → honest unavailability, never a claim.
            VoiceCommand.POINTER_MOVE -> {
                val layer = effectiveLayer()
                val dir = VoiceCommands.extractPointerDirection(text)
                when {
                    layer == null ->
                        speakStatus("Pointer movement isn't available right now.")
                    dir == null ->
                        speakStatus("Which way — left, right, up, or down?")
                    else -> speakStatus(layer.nudgePointer(dir))
                }
                return
            }
            // Device-evidence fix (2026-11-05): launch is a
            // plan-path execution (MASTER F4a — see
            // handleSingleActionPlan) — the manager forwards a
            // single-action plan and returns, never speaking a claim
            // before the host reports the real outcome.
            // Master §2 (item 6): a launch sets the last-turn context,
            // so a follow-up "search for X" resolves its app from here.
            // MASTER F3c: recorded as an ATTEMPT only — the app becomes
            // the last-turn app when the outcome is known (see
            // noteLaunchOutcome / noteLaunchOutcomeFromSpeech), never
            // before. A failed launch must not poison follow-ups.
            VoiceCommand.LAUNCH_APP -> {
                val app = VoiceCommands.extractLaunchApp(text)
                conversationContext.rememberLaunchAttempt(app)
                // MASTER F4a — single-action launch goes through the
                // plan path (blanket cover, stop handling, status),
                // not the standalone launcher. The verbatim text is
                // the launch's record instruction (D-05).
                handleSingleActionPlan("open $app", RequestOrigin.DIRECT_USER, recordInstruction = text)
                return
            }
            // MASTER F1b: "go to X" — the same launch handler as
            // LAUNCH_APP (same attempt recording, same host path).
            VoiceCommand.GO_TO_APP -> {
                val app = VoiceCommands.extractGoToApp(text)
                conversationContext.rememberLaunchAttempt(app)
                // MASTER F4a — normalised to "open" (the planner's
                // extractLaunchApp does not handle "go to"). The
                // verbatim text is the launch's record instruction.
                handleSingleActionPlan("open $app", RequestOrigin.DIRECT_USER, recordInstruction = text)
                return
            }
            // Master §2 (item 4): in-app search — plan-path execution
            // (MASTER F4a — handleSearchQuery routes it as a
            // single-action plan), same honesty rule as launch.
            VoiceCommand.SEARCH_IN_APP -> {
                handleSearch(text)
                return
            }
            // Master §2 (item 5): move the assistant itself — host-owned
            // execution (see Host.onAssistantMoveRequested).
            VoiceCommand.ASSISTANT_MOVE -> {
                handleAssistantMove(text)
                return
            }
            // Dictated messaging + calls (2026-11-05, fix-plan item
            // 12): plan-path execution (MASTER F4a) — the manager
            // forwards the extracted recipient + exact text as a
            // single-action plan and returns, never speaking a claim
            // before the host reports the real outcome.
            VoiceCommand.SEND_WHATSAPP_MESSAGE -> {
                handleMessage(MessageChannel.WHATSAPP, text, RequestOrigin.DIRECT_USER)
                return
            }
            VoiceCommand.SEND_SMS -> {
                handleMessage(MessageChannel.SMS, text, RequestOrigin.DIRECT_USER)
                return
            }
            VoiceCommand.PLACE_CALL -> {
                handleCall(text, RequestOrigin.DIRECT_USER)
                return
            }
            // Stage 2D: multi-step instruction — host-owned planning
            // (see Host.onPlanRequested), same honesty rule as
            // launch/search: the manager forwards the goal verbatim
            // and returns, never speaking a claim before the host
            // reports the plan / the verified outcome.
            VoiceCommand.PLAN_TASK -> {
                handlePlanTask(
                    VoiceCommands.extractPlanGoal(text),
                    RequestOrigin.DIRECT_USER,
                    VoiceCommands.extractTransparency(text),
                )
                return
            }
            // Device-test failures (2026-11-11, D-04): "confirm your
            // screen" — describe the live screen from the host's
            // Screen Awareness. A host that cannot read it returns
            // null and the answer says so plainly, never a denial
            // that the command exists.
            VoiceCommand.DESCRIBE_SCREEN -> {
                val description = host.describeCurrentScreen()
                    ?: "I can't read the screen right now — the screen reading isn't available."
                speakStatus(description)
                return
            }
            null -> { /* normal turn — but check lifecycle classes first */ }
        }

        // MASTER F3b: the bare search/launch questions, asked at most
        // once per task. These never match a command (no query / no
        // app named), so they are guarded here, before the
        // clarification layer would ask them unconditionally on every
        // repeat. A repeat of the same bare request fails honestly;
        // a matched command above already reset the launch flag.
        if (VoiceCommands.isBareSearchRequest(text)) {
            if (conversationContext.askedSearchQuery) {
                speakStatus(
                    "I still don't know what to search for — say the " +
                        "search words and I'll carry on from there.",
                )
            } else {
                conversationContext.markSearchQueryAsked()
                speakStatus(VoiceCommands.SEARCH_QUERY_QUESTION)
            }
            return
        }
        if (VoiceCommands.isBareOpenRequest(text)) {
            if (conversationContext.askedLaunchApp) {
                speakStatus(
                    "I still don't know which app to open — say the " +
                        "app name and I'll carry on from there.",
                )
            } else {
                conversationContext.markLaunchAppAsked()
                speakStatus(VoiceCommands.LAUNCH_APP_QUESTION)
            }
            return
        }

        // Device-test failures (2026-11-11, D-05): "the task" refers
        // to the task David already gave — the active record, or the
        // last one when the active record ended (usually the failed
        // story task). Resume it by name; a completed one is named
        // as done, never re-run. With no task anywhere, fall through
        // to the engine, which asks what the task is.
        if (VoiceCommands.isTaskReference(text)) {
            val referenced = conversationContext.referencedTask()
            if (referenced != null) {
                if (referenced.status == TaskRecordStatus.COMPLETED) {
                    speakStatus(
                        "That task is already done: ${referenced.instruction}.",
                    )
                } else {
                    handlePlanTask(
                        referenced.instruction,
                        referenced.origin,
                        transparency = false,
                    )
                }
                return
            }
        }

        // Phase 2 (mismatch #4): unmatched lifecycle-class phrasings
        // (shutdown/stop/hide/quiet classes per plan §4.2) get a spoken
        // clarification — NEVER the LLM's roleplay. The clarification
        // performs no action. Fix 2 (2026-11-13, F1-DEFECT-1): the
        // negation intercept — a negated shutdown ("don't shut
        // down") never reaches this layer at all; it falls through
        // to the engine below, so LOOM can never answer it with the
        // shutdown question. (clarificationFor itself also refuses
        // the shutdown branch for negated text; this guard is the
        // voice-path intercept, mirrored on the typed path in
        // CapabilityBridge.resolveChatInput.)
        if (!VoiceCommands.isNegatedShutdown(text)) {
            VoiceCommands.clarificationFor(text)?.let { template ->
                speakStatus(template)
                return
            }
        }

        // Stage 2A.1 (D2): ambiguous mode phrasing ("switch it", "change
        // mode") gets a spoken clarification — never the LLM's roleplay.
        if (VoiceCommands.matchAmbiguous(text)) {
            speakStatus("Which mode — Orb, Character, or Invisible?")
            return
        }

        if (text.isBlank()) {
            // VOICE FIX (2026-11-10): an empty final result is not a
            // lost turn when the recognizer already delivered a
            // partial — the Nov 10 device evidence shows the partial
            // is the true transcript ("can you hear me", correct,
            // twice). Route it through the normal path (command match
            // first, then the engine) instead of discarding it. The
            // partial is consumed once, so a later error cannot
            // re-route the same text. One level of recursion, bounded:
            // the routed text is non-blank, so it cannot re-enter
            // this branch.
            val heard = lastPartialText.trim()
            if (heard.isNotBlank()) {
                android.util.Log.i(
                    "LoomStt",
                    "handleResult blank final result — using lastPartial=\"$heard\"",
                )
                lastPartialText = ""
                handleResult(heard)
                return
            }
            // MASTER Wave 3 (F16): an empty final result is a second
            // lost-command shape (distinct from the recognizer's
            // NO_MATCH error) — log it with the last partial, so the
            // replay can tell the two apart.
            android.util.Log.i(
                "LoomStt",
                "handleResult blank final result lastPartial=\"$lastPartialText\"",
            )
            // D-02: a blank result on an idle listen is silence after
            // a finished turn — end quietly, never report it.
            if (idleListen) {
                endSessionQuietly()
                return
            }
            softFailure("Didn't catch that — listening again…")
            return
        }
        softFailures = 0
        // MASTER Wave 4 (F12): the status text carries the heard
        // transcript while thinking — "Thinking…" alone (video
        // failure 4: ~20s with nothing shown) is replaced by
        // "Thinking… — heard: \"…\"", which the hosts show in the
        // voice UI and the floating pill narrates. The user line
        // itself was already stored by recordHeard above (the old
        // append here duplicated it).
        setState(VoiceSessionState.PROCESSING, "Thinking… — heard: \"$text\"")
        val store = host.provideStore()
        val conv = store.getActive()
        val acc = StringBuilder()
        host.provideEngine().generate(
            text, conv.messages.size,
            onChunk = { acc.append(it) },
            onDone = { reply ->
                mainHandler.post {
                    if (turnId != generationId) return@post // superseded/ended
                    onEngineDone(acc.toString().ifBlank { reply })
                }
            },
            // Stage 2A.1 (D3): mic-state grounding — prompt context, not
            // conversation history (the store is untouched). Master §2
            // (item 2): the grounded fallback — unmatched input goes to
            // the engine WITH the capability context and the
            // pick-an-action-or-say-you-cannot instruction attached, so
            // a paraphrase or misheard phrase can still resolve to an
            // allowlisted action. The model selects; it never executes:
            // onEngineDone validates the block through parseModelAction()
            // and dispatches through the same handlers as a matched
            // command. No model upgrade — Flash-Lite stays.
            systemContext = buildSystemContext() + "\n\n" +
                CapabilityBridge.fallbackSystemContext(text),
        )
    }

    private fun onEngineDone(full: String) {
        if (state != VoiceSessionState.PROCESSING) return
        // Blank-reply fix (2026-11-06): the voice turn has the same
        // blank-store hole as typed chat — an action-only reply whose
        // block failed validation left "" stored in the shared thread,
        // which renders as a blank bubble. An action turn with no prose
        // stores nothing (the action's own outcome is the record);
        // anything else stores the fallback sentence, never blank.
        // The outer catch never strands the session in PROCESSING: a
        // failed turn ends with the fallback and the loop resumes.
        // The dispatch catch is the same guard as typed chat — a throw
        // inside a command used to kill the UI thread from this handler.
        // Capability-API fix: strip model action blocks before storing or
        // speaking, and dispatch a parsed capability through the EXISTING
        // private handlers below — the same code voice commands use, so the
        // layer-null honesty, AuthGate-gated tap flow and sensitive-app
        // rules downstream are unchanged. Lifecycle actions are unparseable
        // by construction, so no new path is needed, and the block text
        // itself is never spoken.
        // Accessibility fix (2026-11-05, §7.3): a cloud error that reaches
        // voice as raw text must never be spoken — or stored — with its
        // HTTP code. The engine boundary (LlmModels) already maps these
        // to one plain sentence; this is belt and braces for any other
        // path that hands an error to voice.
        try {
            val clean = plainSpokenError(CapabilityBridge.stripModelActions(full))
            val action = CapabilityBridge.parseModelAction(full)
            val store = host.provideStore()
            val conv = store.getActive()
            if (action == null) {
                store.append(
                    conv.id,
                    ChatMessage(
                        "assistant",
                        clean.ifBlank { VOICE_FALLBACK_REPLY },
                        System.currentTimeMillis(),
                    ),
                )
            } else if (clean.isNotBlank()) {
                store.append(conv.id, ChatMessage("assistant", clean, System.currentTimeMillis()))
            }
            host.onTurnFinished()
            if (action != null) {
                try {
                    dispatchModelAction(action)
                } catch (e: Exception) {
                    speakStatus(VOICE_FALLBACK_REPLY)
                    return
                }
                // Dictated messaging + calls (2026-11-05, fix-plan item
                // 12): a dispatched model action previously left the
                // session stranded in PROCESSING — the next voice turn
                // was dropped as stale, so after ANY model action
                // (launch/search/move/send/call) voice stopped listening.
                // Resume the loop, same as the muted/blank path below:
                // the host owns the outcome ack (spoken through the live
                // session), and the executor runs on its own thread, so
                // resuming listening here cannot claim an outcome.
                autoContinue()
                return
            }
            // Phase 2 (plan §4.2-C): GO_SILENT suppresses spoken conversational
            // responses — the text is still logged above (store.append), only the
            // TTS is skipped. "Speak again" clears the flag. A blank reply
            // speaks the fallback instead of silence: the user asked by
            // voice, so the answer is spoken too.
            if (muted || silent) {
                autoContinue()
                return
            }
            val spoken = clean.ifBlank { VOICE_FALLBACK_REPLY }
            setState(VoiceSessionState.SPEAKING, "Speaking…")
            pipeline.speak(
                stripMarkdownForSpeech(spoken),
                onDone = { mainHandler.post { autoContinue() } },
                onError = {
                    mainHandler.post {
                        setState(
                            VoiceSessionState.ERROR,
                            "Speaker unavailable — the reply is in the chat.",
                        )
                    }
                },
            )
        } catch (e: Exception) {
            try {
                val store = host.provideStore()
                val conv = store.getActive()
                store.append(
                    conv.id,
                    ChatMessage("assistant", VOICE_FALLBACK_REPLY, System.currentTimeMillis()),
                )
            } catch (_: Exception) {
            }
            autoContinue()
        }
    }

    /** TTS finished and the session is still live → listen again. No tap. */
    private fun autoContinue() {
        if (state == VoiceSessionState.SPEAKING ||
            state == VoiceSessionState.PROCESSING
        ) {
            // D-02: the listen that follows a finished turn is an
            // IDLE listen — silence in it ends quietly (see
            // endSessionQuietly), it is never reported as a failure.
            idleListen = true
            beginListen()
        }
    }

    private fun handleSttError(msg: String) {
        if (state != VoiceSessionState.LISTENING) return // stale callback
        // MASTER Wave 3 (F16) — the lost-command evidence: log the
        // error sentence together with the last partial heard on
        // this turn, if any. A NO_MATCH after partials ("open
        // youtube…") proves the mic heard speech the recognizer
        // then discarded; a NO_MATCH with no partials at all points
        // at the recognizer never starting / the mic, not at the
        // words. No behaviour change here — the cause is named from
        // this evidence, not guessed from code.
        android.util.Log.i(
            "LoomStt",
            "handleSttError msg=\"$msg\" lastPartial=\"$lastPartialText\" " +
                "softFailures=$softFailures hardFailures=$hardFailures",
        )
        when {
            // Hard: user action required, no auto-retry.
            msg == "Microphone permission denied." ||
                msg == "Microphone error." ||
                msg == "Speech recognition is not available on this device." -> {
                hardFailures++
                setState(VoiceSessionState.ERROR, msg)
            }
            // Soft: silence / no match — retry listening.
            // VOICE FIX (2026-11-10): the recognizer errored, but a
            // partial transcript already arrived for this turn — the
            // Nov 10 device evidence shows it is the true transcript.
            // Route it through the normal path instead of discarding
            // the turn (see the handleResult blank branch for the
            // same rule). Consumed once, so a later error cannot
            // re-route the same text. Only a turn with NO partial at
            // all falls through to the bounded retry below.
            msg == "Didn't catch that — try again." ||
                msg == "No speech heard." -> {
                val heard = lastPartialText.trim()
                if (heard.isNotBlank()) {
                    android.util.Log.i(
                        "LoomStt",
                        "handleSttError using lastPartial as result " +
                            "msg=\"$msg\" heard=\"$heard\"",
                    )
                    lastPartialText = ""
                    handleResult(heard)
                } else {
                    softFailure("Didn't catch that — listening again…")
                }
            }
            // Medium (network, server, busy…): a wobbled turn WITH
            // heard words routes them — same rule as the NO_MATCH
            // branch above (utterance fix, 2026-11-11). The old code
            // only recorded the partial and retried, so the words
            // were lost and the NEXT statement became the one sent.
            // Only a turn with NO partial at all falls through to
            // the bounded retry below.
            else -> {
                val heard = lastPartialText.trim()
                if (heard.isNotBlank()) {
                    android.util.Log.i(
                        "LoomStt",
                        "handleSttError using lastPartial as result " +
                            "msg=\"$msg\" heard=\"$heard\"",
                    )
                    lastPartialText = ""
                    handleResult(heard)
                } else {
                    softFailures++
                    hardFailures++
                    if (softFailures > MAX_SOFT_RETRIES || hardFailures >= MAX_HARD_FAILURES) {
                        setState(VoiceSessionState.ERROR, "$msg Tap ${startLabel()} to retry.")
                    } else {
                        // MASTER Wave 4 (F12/F16): a wobbled turn is a
                        // lost turn too — record it once, with the last
                        // partial, so the chat shows what was caught.
                        recordLostTurnOnce("Connection wobbled — listening again…")
                        scheduleRetry("Connection wobbled — listening again…", backoffMs())
                    }
                }
            }
        }
    }

    private fun softFailure(status: String) {
        // D-02: silence on an idle listen is not a failure — end
        // quietly before anything is recorded, spoken, or retried.
        // The bound below (MAX_NO_SPEECH_RETRIES) is for EXPLICIT
        // listens only: David tapped to talk and nothing was heard.
        if (idleListen) {
            endSessionQuietly()
            return
        }
        softFailures++
        // MASTER Wave 4 (F12/F16): the lost turn is recorded once,
        // with the last partial heard — David can see what was
        // caught instead of hearing "didn't catch that" with no
        // evidence. Retries do not re-record (lostTurnRecorded).
        recordLostTurnOnce(status)
        // VOICE FIX (2026-11-10): the no-speech retry is bounded at
        // MAX_NO_SPEECH_RETRIES (2). A turn that heard nothing
        // retries at most twice, then the session stops in ERROR and
        // says plainly, once, that nothing was heard — never a loop.
        // The message is spoken one-shot (the handleEndVoice
        // pattern: direct pipeline.speak, no autoContinue), so the
        // loop cannot restart; muted/silent sessions show it as text
        // only. The medium network path keeps its own bound.
        if (softFailures > MAX_NO_SPEECH_RETRIES) {
            val plain = "I didn't hear anything — tap ${startLabel()} to try again."
            setState(VoiceSessionState.ERROR, plain)
            if (!muted && !silent) {
                pipeline.speak(plain, onDone = {}, onError = {})
            }
            return
        }
        scheduleRetry(status, 400L * softFailures)
    }

    /**
     * Device-test failures (2026-11-11, D-02): end a silent idle
     * listen QUIETLY — mic off, no bubble in the chat, no spoken
     * message, no retry. A finished turn followed by silence is a
     * finished conversation, not a lost command. When a plan outcome
     * is still pending, the listen is only SUSPENDED (mic off, state
     * kept): the outcome ack resumes the loop through speakNow, so
     * the spoken result is never lost to the quiet end.
     */
    private fun endSessionQuietly() {
        cancelRetry()
        softFailures = 0
        idleListen = false
        if (planOutcomePending) {
            listenSuspended = true
            pipeline.stopListening()
            return
        }
        pipeline.stopListening()
        setState(VoiceSessionState.IDLE, host.idlePrompt())
    }

    private fun scheduleRetry(status: String, delayMs: Long) {
        cancelRetry()
        setState(VoiceSessionState.LISTENING, status)
        val r = Runnable { beginListen() }
        retryRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun backoffMs(): Long =
        (1500L * (1 shl minOf(softFailures, 4))).coerceAtMost(12000L)

    private fun cancelRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun startLabel(): String =
        if (host.idlePrompt().contains("orb", ignoreCase = true)) "the orb" else "the mic"
}

/**
 * Accessibility fix (2026-11-05, §7.3): belt-and-braces plain-error
 * mapping for voice. If an engine reply is a raw cloud error ("Cloud
 * error: … (503) …"), speak one plain sentence with no HTTP code
 * instead — the agreed wording for a busy provider. Ordinary replies
 * pass through untouched. The engine boundary (LlmModels) already
 * maps these, so this should never fire; it exists so a code can
 * never be read aloud.
 */
internal fun plainSpokenError(text: String): String {
    val t = text.trim()
    val looksLikeCloudError =
        t.startsWith("Cloud error:", ignoreCase = true) ||
            t.contains("temporarily overloaded", ignoreCase = true) ||
            "(503)" in t || "(429)" in t ||
            "HTTP 503" in t || "HTTP 429" in t
    return if (looksLikeCloudError) {
        "Google is busy right now — I'll try again in a moment."
    } else {
        text
    }
}

/**
 * Strip markdown for speech: TTS must never read "**bold**" or "#" aloud.
 * The chat bubble keeps the raw text; only the spoken copy is cleaned.
 */
fun stripMarkdownForSpeech(text: String): String {
    var s = text
    s = s.replace(Regex("```[\\s\\S]*?```"), " ") // code blocks
    s = s.replace(Regex("`([^`]*)`"), "$1") // inline code
    s = s.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1") // images
    s = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1") // links keep text
    s = s.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2") // bold
    s = s.replace(Regex("(^|\\s)[*_]([^*_]+)[*_]"), "$1$2") // italic
    s = s.replace(Regex("(?m)^#{1,6}\\s*"), "") // headings
    s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "") // list markers
    s = s.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "") // numbered lists
    s = s.replace(Regex("(?m)^>\\s?"), "") // quotes
    s = s.replace(Regex("\\s+"), " ")
    return s.trim()
}
