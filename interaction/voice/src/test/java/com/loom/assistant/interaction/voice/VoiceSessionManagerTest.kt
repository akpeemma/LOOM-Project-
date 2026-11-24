package com.loom.assistant.interaction.voice

import android.content.Context
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SharedConversationContext
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.core.common.VoiceCommand
import com.loom.assistant.core.common.VoiceSessionOwner
import com.loom.assistant.execution.interaction.InteractionLayer
import com.loom.assistant.execution.interaction.InteractionLayerRegistry
import com.loom.assistant.intelligence.llm.ResponseEngine
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState

/**
 * Phase 2 (voice track) — JVM checks for VoiceSessionManager defect fixes,
 * new command handlers, the clarification layer, and mic-grounding wiring.
 *
 * Compiles the REAL VoiceSessionManager.kt against the android.content /
 * android.os fakes plus test-local stubs for the LLM/memory/interaction/
 * modes/pointer surface (stubs declare only what the manager uses; the real
 * Gradle build compiles against the real classes).
 *
 * Plain-main() pattern: kotlinc <fakes> <core> <voice-main> <stubs> <test>
 * -d out && java -cp out com.loom.assistant.interaction.voice.VoiceSessionManagerTestKt
 * (see test-harness/run-voice-tests.sh).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected true, got false")
    }
}

private fun resetOwner() {
    VoiceSessionOwner.releaseInApp()
    VoiceSessionOwner.releaseFloating()
}

// ---------- fakes ----------

private class FakeVoicePipeline : VoicePipeline {
    override var state: VoiceState = VoiceState.IDLE
    var sttAvailable: Boolean = true
    var stopListeningCalls = 0
    var stopSpeakingCalls = 0
    var clearPendingSpeechCalls = 0
    val spoken = mutableListOf<String>()
    private var onResult: ((String) -> Unit)? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override fun isSttAvailable(context: Context): Boolean = sttAvailable

    override fun startListening(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        this.onPartial = onPartial
        this.onResult = onResult
        this.onError = onError
        state = VoiceState.LISTENING
    }

    override fun stopListening() {
        stopListeningCalls++
        if (state == VoiceState.LISTENING) state = VoiceState.IDLE
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        spoken.add(text)
        state = VoiceState.SPEAKING
        state = VoiceState.IDLE
        onDone()
    }

    override fun stopSpeaking() {
        stopSpeakingCalls++
        if (state == VoiceState.SPEAKING) state = VoiceState.IDLE
    }

    override fun clearPendingSpeech() {
        clearPendingSpeechCalls++
    }

    override fun shutdown() {}

    /**
     * Simulate a recognizer result. Production sets THINKING before posting
     * the result; [pipelineStateDuring] lets the desync test hold it at IDLE
     * (recognizer released) instead.
     */
    fun deliverResult(text: String, pipelineStateDuring: VoiceState = VoiceState.THINKING) {
        state = pipelineStateDuring
        onResult?.invoke(text)
    }

    /** Simulate a partial hypothesis arriving mid-turn. */
    fun deliverPartial(text: String) {
        onPartial?.invoke(text)
    }

    /** Simulate a recognizer error (the real pipeline passes a sentence). */
    fun deliverError(msg: String) {
        onError?.invoke(msg)
    }
}

private class FakeEngine : ResponseEngine {
    var calls = 0
    var lastUserText: String? = null
    var lastSystemContext: String? = null
    var reply = "fake reply"
    override fun generate(
        userText: String,
        historySize: Int,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        systemContext: String?,
    ) {
        calls++
        lastUserText = userText
        lastSystemContext = systemContext
        onDone(reply)
    }
}

private open class FakeHost(val engine: FakeEngine = FakeEngine()) : VoiceSessionManager.Host {
    var micPermission = true
    val states = mutableListOf<VoiceSessionState>()
    val statusTexts = mutableListOf<String>()
    val commands = mutableListOf<VoiceCommand>()
    val launchRequests = mutableListOf<String>()
    val store = ConversationStore()
    override fun provideEngine(): ResponseEngine = engine
    override fun provideStore(): ConversationStore = store
    override fun historyWindow(): Int = 8
    override fun checkMicPermission(onResult: (Boolean) -> Unit) {
        onResult(micPermission)
    }
    override fun idlePrompt(): String = "Tap to talk"
    override fun onSessionState(state: VoiceSessionState, statusText: String) {
        states.add(state)
        statusTexts.add(statusText)
    }
    override fun onPartialText(text: String) {}
    override fun onTurnFinished() {}
    override fun onCommandAction(cmd: VoiceCommand) {
        commands.add(cmd)
    }

    override fun onLaunchRequested(appName: String) {
        launchRequests.add(appName)
    }
}

// MASTER F4a: searches, launches, messages and calls no longer
// travel through the standalone host methods — the manager routes
// every matched single action through the plan path, so the test
// hosts are plan-capable. The standalone onSearchRequested /
// onMessageRequested / onCallRequested test-host overrides are gone
// with the route they served; PlanCapableHost (below) is the host
// every routing test uses.

/** Stage 2D: a host that DOES handle multi-step tasks — records the PlanRequest. */
private open class PlanCapableHost : FakeHost() {
    val planRequests = mutableListOf<PlanRequest>()
    override fun onPlanRequested(request: PlanRequest): Boolean {
        planRequests.add(request)
        return true
    }
}

/** MASTER F6: a plan-capable host that also records guide/demo provenance. */
private class GuidanceNotingHost : PlanCapableHost() {
    val guideOrigins = mutableListOf<RequestOrigin>()
    val demoOrigins = mutableListOf<RequestOrigin>()
    override fun noteGuideOrigin(origin: RequestOrigin) {
        guideOrigins.add(origin)
    }

    override fun noteDemoOrigin(origin: RequestOrigin) {
        demoOrigins.add(origin)
    }
}

/** Records guidance starts so the registry-fallback tests can prove routing. */
private class RecordingLayer : InteractionLayer() {
    val guidanceQueries = mutableListOf<String>()
    val demonstrationGoals = mutableListOf<String>()
    override fun startGuidance(query: String) {
        guidanceQueries.add(query)
    }

    override fun startDemonstration(goal: String) {
        demonstrationGoals.add(goal)
    }
}

/** A layer whose idle indicator renders — showPointerIdle() verifies true. */
private class RenderingLayer : InteractionLayer() {
    override fun showPointerIdle(): Boolean = true
}

/** A layer with a live sequence — the pointer is already on screen. */
private class ActiveSequenceLayer : InteractionLayer() {
    override val isSequenceActive: Boolean = true
}

// Accessibility fix (2026-11-05, audit §7.1): "show the pointer" ack
// gating through the real manager — the spoken confirmation comes ONLY
// from a verified render, never from the request. The Nov 5 transcript
// case is the layer-null branch: the floating assistant never started,
// so nothing can render, and the old "will now stay visible during
// guidance" claim must never be spoken.
private fun testPointerShowAckGating() {
    val forbidden = listOf(
        "will now stay visible", "I've requested", "I've started",
        "attached", "detached", "anchored", "guidance window",
    )
    // (a) No layer anywhere → plain one-sentence failure, no claim.
    resetOwner()
    InteractionLayerRegistry.register(null)
    var (mgr, pipeline, _) = startLive()
    pipeline.deliverResult("show the pointer")
    check(
        "voice show-pointer, no layer → plain failure, not a visibility claim",
        pipeline.spoken.last() ==
            "The pointer isn't on screen right now — LOOM needs to be " +
            "turned on in Settings, under Accessibility.",
    )
    check(
        "voice show-pointer, no layer → no forbidden wording spoken",
        forbidden.none { pipeline.spoken.last().contains(it, ignoreCase = true) },
    )
    check("session still live after the honest failure", mgr.state == VoiceSessionState.LISTENING)
    InteractionLayerRegistry.register(null)

    // (b) Layer renders the idle indicator → plain confirmation.
    resetOwner()
    InteractionLayerRegistry.register(null)
    val renderingPipeline = FakeVoicePipeline()
    val renderingHost = FakeHost()
    val renderingMgr = newManager(renderingPipeline, renderingHost, RenderingLayer())
    renderingMgr.start()
    renderingPipeline.deliverResult("show the pointer")
    check(
        "voice show-pointer, render verified → plain confirmation",
        renderingPipeline.spoken.last() ==
            "The pointer is now visible — you can see it on screen. " +
            "Say 'guide me to' something to point at it.",
    )
    check(
        "voice show-pointer, render verified → no forbidden wording spoken",
        forbidden.none { renderingPipeline.spoken.last().contains(it, ignoreCase = true) },
    )
    InteractionLayerRegistry.register(null)

    // (c) Layer present but the render fails (stub mirrors the real
    // no-renderer case) → the same plain failure, never a claim.
    resetOwner()
    InteractionLayerRegistry.register(null)
    val failingPipeline = FakeVoicePipeline()
    val failingHost = FakeHost()
    val failingMgr = newManager(failingPipeline, failingHost, InteractionLayer())
    failingMgr.start()
    failingPipeline.deliverResult("show the pointer")
    check(
        "voice show-pointer, render failed → plain failure, not a claim",
        failingPipeline.spoken.last() ==
            "The pointer isn't on screen right now — LOOM needs to be " +
            "turned on in Settings, under Accessibility.",
    )
    InteractionLayerRegistry.register(null)

    // (d) Sequence active → the pointer is already on screen as part of
    // guidance; the ack says so as the live state, never "will stay".
    resetOwner()
    InteractionLayerRegistry.register(null)
    val activePipeline = FakeVoicePipeline()
    val activeHost = FakeHost()
    val activeMgr = newManager(activePipeline, activeHost, ActiveSequenceLayer())
    activeMgr.start()
    activePipeline.deliverResult("show the pointer")
    check(
        "voice show-pointer, sequence active → already-on-screen ack",
        activePipeline.spoken.last() ==
            "The pointer is already on screen — guidance is showing it.",
    )
    check(
        "voice show-pointer, sequence active → no 'will stay visible' claim",
        !activePipeline.spoken.last().contains("will now stay visible"),
    )
    InteractionLayerRegistry.register(null)
}

// Accessibility fix (2026-11-05, §7.3): a raw cloud error reaching
// voice must never be spoken — or stored — with its HTTP code. The
// engine boundary already maps it; this proves the belt-and-braces
// mapping in onEngineDone.
private fun testEngineErrorSpokenPlain() {
    resetOwner()
    InteractionLayerRegistry.register(null)
    val pipeline = FakeVoicePipeline()
    val host = FakeHost()
    host.engine.reply =
        "Cloud error: Google is temporarily overloaded (503) — wait a moment and retry."
    val mgr = newManager(pipeline, host)
    mgr.start()
    pipeline.deliverResult("what is the capital of France?")
    check(
        "voice 503 → spoken as one plain sentence, no code",
        pipeline.spoken.last() == "Google is busy right now — I'll try again in a moment.",
    )
    check(
        "voice 503 → spoken text carries no '503' or 'Cloud error'",
        !pipeline.spoken.last().contains("503") &&
            !pipeline.spoken.last().contains("Cloud error"),
    )
    check(
        "voice 503 → stored thread text is the plain sentence too",
        host.store.getActive().messages.lastOrNull()?.text ==
            "Google is busy right now — I'll try again in a moment.",
    )
    check("session still live after the plain error", mgr.state == VoiceSessionState.LISTENING)
    InteractionLayerRegistry.register(null)
}

private fun newManager(
    pipeline: FakeVoicePipeline = FakeVoicePipeline(),
    host: FakeHost = FakeHost(),
    layer: InteractionLayer? = null,
): VoiceSessionManager {
    LifecycleController.resetForTest()
    resetOwner()
    // MASTER F3a: the conversation context is shared across all
    // managers in the process — each test starts from a clean one,
    // exactly as a fresh process would. (The inheritance test below
    // uses newManagerShared() instead, so it can prove the second
    // manager sees the first one's task.)
    SharedConversationContext.resetForTest()
    return VoiceSessionManager(
        Context(),
        pipeline,
        host,
        floating = false,
        interactionLayer = layer,
    )
}

/** Like [newManager] but WITHOUT resetting the shared context — for
 *  the cross-session inheritance test (MASTER F3a). */
private fun newManagerShared(
    pipeline: FakeVoicePipeline = FakeVoicePipeline(),
    host: FakeHost = FakeHost(),
    layer: InteractionLayer? = null,
): VoiceSessionManager {
    LifecycleController.resetForTest()
    resetOwner()
    return VoiceSessionManager(
        Context(),
        pipeline,
        host,
        floating = false,
        interactionLayer = layer,
    )
}

private fun startLive(): Triple<VoiceSessionManager, FakeVoicePipeline, FakeHost> {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = FakeHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    return Triple(mgr, pipeline, host)
}

// ---------- tests ----------

private fun testPausedResumeTrap() {
    val (mgr, _, _) = startLive()
    check("start → LISTENING", mgr.state == VoiceSessionState.LISTENING)
    mgr.pause()
    check("pause → PAUSED", mgr.state == VoiceSessionState.PAUSED)
    mgr.resume()
    // Pre-fix this stayed PAUSED forever (beginListen early-returned).
    check("E.3-1 resume escapes PAUSED → LISTENING", mgr.state == VoiceSessionState.LISTENING)
    mgr.pause()
    mgr.start() // start() while PAUSED is a resume
    check("start() while PAUSED resumes → LISTENING", mgr.state == VoiceSessionState.LISTENING)
}

private fun testTtsQueueCleared() {
    var (mgr, pipeline, _) = startLive()
    mgr.end()
    check("E.3-2 end() clears the TTS queue", pipeline.clearPendingSpeechCalls == 1)
    check("end → IDLE", mgr.state == VoiceSessionState.IDLE)

    val t2 = startLive()
    mgr = t2.first; pipeline = t2.second
    mgr.pause()
    check("E.3-2 pause() clears the TTS queue", pipeline.clearPendingSpeechCalls == 1)
}

private fun testSpeakNowPausedGuard() {
    val (mgr, pipeline, _) = startLive()
    mgr.pause()
    pipeline.spoken.clear()
    mgr.speakNow("should never be spoken")
    check("E.3-3 speakNow() is a no-op while PAUSED", pipeline.spoken.isEmpty())
    mgr.resume()
    mgr.speakNow("hello")
    check("speakNow() speaks while LISTENING", pipeline.spoken.contains("hello"))
}

private fun testPermissionCheck() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = FakeHost()
    host.micPermission = false
    val mgr = newManager(pipeline, host)
    mgr.start()
    check(
        "E.3-4 denied permission → ERROR, never LISTENING",
        mgr.state == VoiceSessionState.ERROR,
    )
    host.micPermission = true
    mgr.start()
    check("granted permission → LISTENING", mgr.state == VoiceSessionState.LISTENING)
}

private fun testLifecycleGateAndReporter() {
    // Gate closed: lifecycle != ACTIVE → start() refused (E.3-5).
    val gated = newManager() // resets controller to ACTIVE…
    LifecycleController.transitionTo(LifecycleState.ASSISTANT_MODE_INACTIVE) // …then deactivate
    gated.start()
    check(
        "E.3-5 gate closed (INACTIVE) → start() refused with ERROR",
        gated.state == VoiceSessionState.ERROR,
    )
    check(
        "E.3-5 gate closed → isDispatchAllowed() is false",
        !LifecycleController.isDispatchAllowed(),
    )

    // Gate open: lifecycle == ACTIVE → start() works, and the session mirror
    // reaches the controller on every setState().
    val open = newManager() // resets controller to ACTIVE
    open.start()
    check("E.3-5 gate open (ACTIVE) → start() → LISTENING", open.state == VoiceSessionState.LISTENING)
    check(
        "E.3-5 controller mirror saw LISTENING",
        LifecycleController.currentObservable().sessionMirror == "LISTENING",
    )
    open.pause()
    check(
        "E.3-5 controller mirror saw PAUSED",
        LifecycleController.currentObservable().sessionMirror == "PAUSED",
    )
    open.end()
    check(
        "E.3-5 controller mirror saw IDLE after end()",
        LifecycleController.currentObservable().sessionMirror == "IDLE",
    )
    LifecycleController.resetForTest()
}

private fun testGoSilent() {
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverResult("be quiet")
    check("GO_SILENT sets the silent flag", mgr.isSilent)
    check("GO_SILENT stops in-flight TTS", pipeline.stopSpeakingCalls > 0)
    check("GO_SILENT clears the TTS queue", pipeline.clearPendingSpeechCalls > 0)
    check(
        "GO_SILENT acks",
        pipeline.spoken.any { it.contains("Silent mode on") },
    )
    check("GO_SILENT keeps the session live", mgr.state == VoiceSessionState.LISTENING)

    // Conversational replies are suppressed but still logged.
    val assistantBefore = host.store.getActive().messages.count { it.role == "assistant" }
    pipeline.deliverResult("tell me a story")
    val assistantAfter = host.store.getActive().messages.count { it.role == "assistant" }
    check("silent: reply text still logged", assistantAfter == assistantBefore + 1)
    check("silent: reply NOT spoken", pipeline.spoken.none { it == "fake reply" })

    // "Speak again" clears the flag.
    pipeline.deliverResult("speak again")
    check("SPEAK_AGAIN clears the silent flag", !mgr.isSilent)
    pipeline.deliverResult("tell me another story")
    check("after speak-again the reply IS spoken", pipeline.spoken.contains("fake reply"))
}

private fun testClarificationLayer() {
    val (mgr, pipeline, host) = startLive()
    val engineCallsBefore = host.engine.calls
    // Shutdown recognition (2026-11-10; immediate since 2026-11-13):
    // "power off" matches SHUTDOWN and is forwarded to the host,
    // which executes the shutdown immediately with NO confirmation
    // question (David's binding rule, Nov 13 ~03:36) — it is a
    // command, not a clarification.
    pipeline.deliverResult("power off")
    check(
        "power off forwards SHUTDOWN to the host",
        host.commands.contains(VoiceCommand.SHUTDOWN),
    )
    check("shutdown command never reaches the LLM", host.engine.calls == engineCallsBefore)
    check("shutdown command ends the session", mgr.state == VoiceSessionState.IDLE)
    // A fresh session for the remaining clarification classes.
    val (mgr2, pipeline2, host2) = startLive()
    val engineCallsBefore2 = host2.engine.calls
    pipeline2.deliverResult("be quiet for a moment, I'm thinking")
    check(
        "mid-sentence be quiet → quiet-class clarification, not GO_SILENT",
        pipeline2.spoken.last() == "Do you want me to go silent, or stop something specific?",
    )
    check("mid-sentence quiet does not set silent", !mgr2.isSilent)
    check("clarification never reaches the LLM", host2.engine.calls == engineCallsBefore2)

    pipeline2.deliverResult("mute")
    check(
        "mute → stop-class clarification",
        pipeline2.spoken.last() == "Do you want me to stop the demonstration, stop listening, or stop talking?",
    )

    pipeline2.deliverResult("hide")
    check(
        "hide → hide/mode-class clarification",
        pipeline2.spoken.last() == "Do you want me to hide the floating assistant, or switch to Invisible mode?",
    )
}

private fun testImmediateShutdown() {
    // Device-failure fix (2026-11-13, F1): a voice "shut down" is
    // forwarded to the host SYNCHRONOUSLY with no confirmation
    // question spoken, no re-ask, and no second turn — the host
    // executes the teardown. The session ends at once.
    val (mgr, pipeline, host) = startLive()
    val spokenBefore = pipeline.spoken.size
    pipeline.deliverResult("shut down")
    check(
        "shut down forwards SHUTDOWN to the host at once",
        host.commands.contains(VoiceCommand.SHUTDOWN),
    )
    check(
        "shut down is forwarded exactly once",
        host.commands.count { it == VoiceCommand.SHUTDOWN } == 1,
    )
    check("shut down ends the session", mgr.state == VoiceSessionState.IDLE)
    val spokenAfter = pipeline.spoken.drop(spokenBefore)
    check(
        "no confirmation question is spoken for shutdown",
        spokenAfter.none {
            it.contains("confirm", ignoreCase = true) ||
                it.contains("say yes", ignoreCase = true) ||
                it.contains("shut down LOOM completely", ignoreCase = true)
        },
    )
    // A negated shutdown never reaches the host as SHUTDOWN.
    val (mgr2, pipeline2, host2) = startLive()
    val spokenBefore2 = pipeline2.spoken.size
    val engineCallsBefore2 = host2.engine.calls
    pipeline2.deliverResult("don't shut down")
    check(
        "negated shutdown never forwards SHUTDOWN",
        VoiceCommand.SHUTDOWN !in host2.commands,
    )
    check(
        "negated shutdown does not end the session",
        mgr2.state != VoiceSessionState.IDLE,
    )
    // Fix 2 (2026-11-13, F1-DEFECT-1): assert WHAT IS SPOKEN for
    // the negated case — the round-1 suite never did, and the
    // clarification layer was asking the shutdown question right
    // after being told not to. The negated turn goes to the engine
    // (conversational), and nothing spoken may be the shutdown
    // question, a confirmation ask, or a yes/no prompt.
    val spokenNegated = pipeline2.spoken.drop(spokenBefore2)
    check(
        "negated shutdown speaks NO shutdown question",
        spokenNegated.none {
            it.contains("shut down LOOM completely", ignoreCase = true) ||
                it.contains("confirm", ignoreCase = true) ||
                it.contains("say yes", ignoreCase = true) ||
                it.contains("end the voice session", ignoreCase = true)
        },
    )
    check(
        "negated shutdown reaches the engine (conversational)",
        host2.engine.calls > engineCallsBefore2,
    )
    check(
        "negated shutdown speaks the engine reply, not a question",
        spokenNegated.contains("fake reply"),
    )
    // Fix 2 (2026-11-13, F1-DEFECT-1): a LONG shutdown utterance
    // (>10 words) routes to immediate shutdown, never to the
    // clarification question — the old length guard sent it to the
    // question instead. 15 words, no negation, no pointer/voice/
    // assistant-mode exclusion.
    val (mgr3, pipeline3, host3) = startLive()
    val spokenBefore3 = pipeline3.spoken.size
    val engineCallsBefore3 = host3.engine.calls
    pipeline3.deliverResult(
        "loom please shut down completely now I am finished using you for today thank you",
    )
    check(
        "long shutdown utterance forwards SHUTDOWN to the host",
        host3.commands.contains(VoiceCommand.SHUTDOWN),
    )
    check(
        "long shutdown utterance forwards SHUTDOWN exactly once",
        host3.commands.count { it == VoiceCommand.SHUTDOWN } == 1,
    )
    check("long shutdown utterance ends the session", mgr3.state == VoiceSessionState.IDLE)
    check(
        "long shutdown utterance never reaches the LLM",
        host3.engine.calls == engineCallsBefore3,
    )
    val spokenLong = pipeline3.spoken.drop(spokenBefore3)
    check(
        "long shutdown utterance speaks NO shutdown question",
        spokenLong.none {
            it.contains("shut down LOOM completely", ignoreCase = true) ||
                it.contains("confirm", ignoreCase = true) ||
                it.contains("say yes", ignoreCase = true)
        },
    )
}

private fun testEndVoiceAck() {
    val (mgr, pipeline, _) = startLive()
    pipeline.deliverResult("shut down voice")
    check("END_VOICE ends the session → IDLE", mgr.state == VoiceSessionState.IDLE)
    check(
        "END_VOICE speaks the explicit disambiguation ack",
        pipeline.spoken.any { it.contains("Voice session ended") && it.contains("still running") },
    )
}

private fun testHideShowDeactivate() {
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverResult("hide yourself")
    check("HIDE_YOURSELF forwarded to host", host.commands.contains(VoiceCommand.HIDE_YOURSELF))
    check(
        "HIDE_YOURSELF touches nothing (session still LISTENING)",
        mgr.state == VoiceSessionState.LISTENING,
    )

    pipeline.deliverResult("show yourself")
    check("SHOW_ASSISTANT forwarded to host", host.commands.contains(VoiceCommand.SHOW_ASSISTANT))

    pipeline.deliverResult("deactivate assistant mode")
    check(
        "DEACTIVATE forwarded to host",
        host.commands.contains(VoiceCommand.DEACTIVATE_ASSISTANT_MODE),
    )
    check("DEACTIVATE ends the session → IDLE", mgr.state == VoiceSessionState.IDLE)
    check("DEACTIVATE cleared the TTS queue", pipeline.clearPendingSpeechCalls > 0)
}

// Device-evidence fix (2026-11-05): voice launch requests forward the
// extracted app name to the host (which owns the shared launcher) and
// never reach the engine — no roleplay, no denial.
// MASTER F4a: the forward is now a single-action plan (goal
// "open <app>", DIRECT_USER) through the plan path — the standalone
// onLaunchRequested route is no longer the voice route.
private fun testLaunchForwarding() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("can you open my youtube")
    check(
        "voice launch reaches the planner as open youtube",
        host.planRequests == listOf(
            PlanRequest("open youtube", RequestOrigin.DIRECT_USER, transparency = false),
        ),
    )
    check("voice launch never reaches the LLM", host.engine.calls == engineCallsBefore)
    check("voice launch leaves the session live", mgr.state == VoiceSessionState.LISTENING)

    pipeline.deliverResult("open whatsapp")
    check(
        "open whatsapp reaches the planner as open whatsapp",
        host.planRequests.last().goal == "open whatsapp",
    )
    pipeline.deliverResult("launch settings")
    check(
        "launch settings reaches the planner as open settings",
        host.planRequests.last().goal == "open settings",
    )
    check(
        "voice launches never take the standalone route",
        host.launchRequests.isEmpty(),
    )
}

// "fix everything" (2026-11-05): exact 5:44 / 5:37 transcripts through
// the voice manager — every one intercepted, none reaching the LLM.
// MASTER F4a: launches and the follow-up search reach the planner as
// single-action plans; the follow-up search resolves from the
// just-opened app (see the F3c comment below).
private fun testFixEverythingForwarding() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("open my Youtube")
    check(
        "5:44 open my Youtube reaches the planner as open youtube",
        host.planRequests.last().goal == "open youtube" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    pipeline.deliverResult("open MovieBox")
    check(
        "open MovieBox reaches the planner as open moviebox",
        host.planRequests.last().goal == "open moviebox",
    )
    // MASTER F3c: a launch is an ATTEMPT until its outcome is known —
    // the follow-up search resolves from the just-opened app only
    // once the host confirms the launch (here the test simulates the
    // host's "Opened MovieBox." outcome). Without the confirmation
    // the search asks which app instead (see
    // testLaunchAttemptNotLastApp).
    mgr.noteLaunchOutcome("moviebox", true)
    pipeline.deliverResult("search for Spider-Man")
    check("search for Spider-Man never reaches the LLM", host.engine.calls == engineCallsBefore)
    // Integration (2026-11-05): the Master §2 rewrite clarified a
    // search with no app by asking WHICH app — "Which app should I
    // search in?" — never a guess. The old expectation ("guide you to
    // its search bar") never existed in the manager at HEAD 7951a46
    // either. In THIS test the app is not actually unknown: the
    // preceding "open MovieBox" set the conversation context
    // (rememberLaunch → appForFollowUpSearch), so the follow-up search
    // resolves to MovieBox and — with a plan-capable host — reaches
    // the planner as a single-action search plan. That is the
    // specified behaviour; the check asserts it (and the LLM is never
    // reached).
    check(
        "search follow-up uses the just-opened app and reaches the planner",
        host.planRequests.last().goal == "search for spider-man in moviebox" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    pipeline.deliverResult("guide me through the settings")
    check("guide me through never reaches the LLM", host.engine.calls == engineCallsBefore)
    check(
        "guide me through with no layer is honest",
        pipeline.spoken.last() == "Guidance is not available right now.",
    )
    pipeline.deliverResult("move the pointer left")
    check("move with no layer never reaches the LLM", host.engine.calls == engineCallsBefore)
    check(
        "move with no layer is honest",
        pipeline.spoken.last() == "Pointer movement isn't available right now.",
    )
    check("session still live after all interceptions", mgr.state == VoiceSessionState.LISTENING)
}

private fun testMoveWithLayer() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = FakeHost()
    val mgr = newManager(pipeline, host, InteractionLayer())
    mgr.start()
    pipeline.deliverResult("move the pointer left")
    check("move with layer speaks the layer result", pipeline.spoken.last() == "Moved the pointer left.")
    check("move with layer never reaches the LLM", host.engine.calls == 0)
}

// In-app routing (fix plan item 5, search H2): MASTER F4a — an
// in-app search now reaches the planner as a single-action plan
// (goal "search for <query> in <app>", DIRECT_USER origin) instead
// of the standalone onSearchRequested route, and never speaks the
// "can't search from this session" refusal.
private fun testInAppSearchReachesHost() {
    resetOwner()
    InteractionLayerRegistry.register(null)
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("search for Spider-Man in YouTube")
    check(
        "in-app search reaches the planner as a search plan with DIRECT_USER origin",
        host.planRequests == listOf(
            PlanRequest(
                "search for spider-man in youtube",
                RequestOrigin.DIRECT_USER,
                transparency = false,
                // rememberSearch seeds the last-turn app before the
                // plan is built, so the plan carries it as context.
                contextApp = "youtube",
            ),
        ),
    )
    check(
        "in-app search never speaks the refusal",
        pipeline.spoken.none { it.contains("can't search from this session") },
    )
    check("in-app search never reaches the LLM", host.engine.calls == engineCallsBefore)
    check("session still live after the search handoff", mgr.state == VoiceSessionState.LISTENING)
}

// In-app routing (fix plan item 5, pointer H5): a manager built with a
// null injected layer resolves the process-wide registry layer lazily —
// guidance and pointer moves route through it, so in-app voice can
// guide without the floating session owning the layer.
private fun testRegistryLayerFallback() {
    resetOwner()
    val recording = RecordingLayer()
    InteractionLayerRegistry.register(recording)
    try {
        val pipeline = FakeVoicePipeline()
        val host = FakeHost()
        val mgr = newManager(pipeline, host, layer = null)
        mgr.start()
        pipeline.deliverResult("guide me to Settings")
        check(
            "registry layer receives the guidance start",
            recording.guidanceQueries == listOf("Settings"),
        )
        check(
            "registry guide speaks the live ack",
            pipeline.spoken.last() == "Guiding you to Settings.",
        )
        pipeline.deliverResult("move the pointer left")
        check(
            "registry layer serves the pointer move",
            pipeline.spoken.last() == "Moved the pointer left.",
        )
        check("session still live after registry routing", mgr.state == VoiceSessionState.LISTENING)
    } finally {
        InteractionLayerRegistry.register(null)
    }
}

private fun testInjectedLayerBeatsRegistry() {
    resetOwner()
    val injected = RecordingLayer()
    val registered = RecordingLayer()
    InteractionLayerRegistry.register(registered)
    try {
        val pipeline = FakeVoicePipeline()
        val host = FakeHost()
        val mgr = newManager(pipeline, host, layer = injected)
        mgr.start()
        pipeline.deliverResult("guide me to Settings")
        check(
            "injected layer wins over the registry layer",
            injected.guidanceQueries == listOf("Settings") &&
                registered.guidanceQueries.isEmpty(),
        )
    } finally {
        InteractionLayerRegistry.register(null)
    }
}

private fun testNoLayerNoRegistryIsHonest() {
    resetOwner()
    InteractionLayerRegistry.register(null)
    val (mgr, pipeline, _) = startLive()
    pipeline.deliverResult("guide me to Settings")
    check(
        "no layer anywhere → honest unavailability",
        pipeline.spoken.last() == "Guidance is not available right now.",
    )
    check("session still live after the honest refusal", mgr.state == VoiceSessionState.LISTENING)
}

private fun testPauseStaysSessionPause() {
    // Mismatch #2: "pause" alone keeps meaning voice-session pause (demo
    // pause/continue lives in ActionQueue — another worker's track).
    val (mgr, pipeline, _) = startLive()
    pipeline.deliverResult("pause")
    check("pause alone → session PAUSED", mgr.state == VoiceSessionState.PAUSED)
    pipeline.spoken.clear()
    pipeline.deliverResult("resume") // handleResult guard: PAUSED drops results…
    check("voice resume while PAUSED is dropped (stale-callback guard)", mgr.state == VoiceSessionState.PAUSED)
    mgr.resume()
    check("programmatic resume → LISTENING", mgr.state == VoiceSessionState.LISTENING)
}

private fun testPauseRoutesToMediaWhenPlaying() {
    // Session/state awareness (2026-11-11, OBSERVE-FIRST): "pause"
    // while a Pause control is on the live screen is about the media,
    // not LOOM — it forwards as a plan task (goal "pause") and the
    // session keeps listening. With no control on screen, bare
    // "pause" is still the session pause (testPauseStaysSessionPause).
    resetOwner()
    InteractionLayerRegistry.register(null)
    val pipeline = FakeVoicePipeline()
    val host = object : PlanCapableHost() {
        override fun mediaControlOnScreen(control: String): Boolean = control == "pause"
    }
    val mgr = newManager(pipeline, host)
    mgr.start()
    pipeline.deliverResult("pause")
    check(
        "observe-first: 'pause' with a Pause control on screen forwards a plan task",
        host.planRequests.any { it.goal == "pause" },
    )
    check(
        "observe-first: the session keeps listening (LOOM itself is not paused)",
        mgr.state == VoiceSessionState.LISTENING,
    )
    // Mirror: "resume" with a Play control on screen forwards too.
    val pipeline2 = FakeVoicePipeline()
    val host2 = object : PlanCapableHost() {
        override fun mediaControlOnScreen(control: String): Boolean = control == "play"
    }
    val mgr2 = newManager(pipeline2, host2)
    mgr2.start()
    pipeline2.deliverResult("resume")
    check(
        "observe-first: 'resume' with a Play control on screen forwards a plan task",
        host2.planRequests.any { it.goal == "resume" },
    )
    check(
        "observe-first: the session keeps listening after a media resume",
        mgr2.state == VoiceSessionState.LISTENING,
    )
}

private fun testMicGroundingWiring() {
    // §7-D end-to-end through the real manager: the engine's systemContext
    // must follow the live controller, never a stale assumption.
    // Live: owner claimed, recognizer THINKING (production post-result state).
    val pipeline = FakeVoicePipeline()
    val host = FakeHost()
    val mgr = newManager(pipeline, host)
    VoiceSessionOwner.claimInApp()
    mgr.start()
    check("wiring setup → LISTENING", mgr.state == VoiceSessionState.LISTENING)
    pipeline.deliverResult("hello")
    val liveCtx = host.engine.lastSystemContext ?: ""
    check("live: provenance reported", liveCtx.contains("transcribed from live speech just now"))
    check("live: never claims mic off", !liveCtx.contains("not live"))
    check("live: no disagreement note", !liveCtx.contains("disagree"))

    // Desync: session LISTENING but the controller says released (recognizer
    // IDLE, ownership cleared) → the context MUST say not live.
    resetOwner()
    val pipeline2 = FakeVoicePipeline()
    val host2 = FakeHost()
    val mgr2 = newManager(pipeline2, host2)
    mgr2.start()
    pipeline2.state = VoiceState.IDLE // recognizer released under us
    pipeline2.deliverResult("hello", pipelineStateDuring = VoiceState.IDLE)
    val desyncCtx = host2.engine.lastSystemContext ?: ""
    check("desync: context says not live", desyncCtx.contains("not live right now"))
    check(
        "desync: context never claims live listening",
        !desyncCtx.contains("live and listening"),
    )
    check("desync: disagreement surfaced", desyncCtx.contains("disagree"))
    resetOwner()
}

// Dictated messaging + calls (2026-11-05, fix-plan item 12): a
// host that handles plans receives the matched voice commands as
// single-action plans with the recipient + exact text in the goal
// and DIRECT_USER origin, and the LLM is never reached.
// MASTER F4a: the message/call routes go through the planner, not
// the standalone host methods; the MESSAGE_SEND / CALL_PLACE steps
// are never covered and still card per action (F4c).
private fun testMessageCallForwarding() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("send WhatsApp to Mum saying hello there")
    check(
        "voice whatsapp reaches the planner with channel + recipient + exact text + DIRECT_USER",
        host.planRequests == listOf(
            PlanRequest(
                "send whatsapp message to Mum saying hello there",
                RequestOrigin.DIRECT_USER,
                transparency = false,
            ),
        ),
    )
    check("voice whatsapp never reaches the LLM", host.engine.calls == engineCallsBefore)
    check(
        "voice whatsapp speaks no refusal",
        pipeline.spoken.none { it.contains("can't send messages") },
    )
    pipeline.deliverResult("text Dad saying on my way")
    check(
        "voice sms reaches the planner as an sms plan",
        host.planRequests.last().goal == "send sms to Dad saying on my way",
    )
    pipeline.deliverResult("call Mum")
    check(
        "voice call reaches the planner with target + DIRECT_USER",
        host.planRequests.last().goal == "call Mum" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    check("voice call never reaches the LLM", host.engine.calls == engineCallsBefore)
    pipeline.deliverResult("call 08031234567")
    check(
        "voice call of a number reaches the planner with the digits",
        host.planRequests.last().goal == "call 08031234567",
    )
    check("session still live after the message/call handoffs", mgr.state == VoiceSessionState.LISTENING)
}

// Dictated messaging + calls: bulk is refused at parse time — the
// host is never called, the engine is never reached, and the spoken
// refusal names the one-person rule.
private fun testBulkMessageRefused() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("send WhatsApp to everyone saying hi")
    check("bulk send never reaches the host", host.planRequests.isEmpty())
    check("bulk send never reaches the LLM", host.engine.calls == engineCallsBefore)
    check(
        "bulk send speaks the one-person refusal",
        pipeline.spoken.last().contains("don't send bulk messages"),
    )
    pipeline.deliverResult("call everyone")
    check("bulk call never reaches the host", host.planRequests.isEmpty())
    check(
        "bulk call speaks the one-person refusal",
        pipeline.spoken.last().contains("don't send bulk messages"),
    )
    check("session still live after bulk refusals", mgr.state == VoiceSessionState.LISTENING)
}

// Dictated messaging + calls: a host that does NOT handle messages
// (the default) gets the honest routing message, not a denial of
// the capability and not an engine turn.
private fun testMessageCallRoutingHonesty() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = FakeHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("send WhatsApp to Mum saying hi")
    check(
        "unhandled message speaks the routing message",
        pipeline.spoken.last().contains("can't send messages from this session"),
    )
    pipeline.deliverResult("call Mum")
    check(
        "unhandled call speaks the routing message",
        pipeline.spoken.last().contains("can't place calls from this session"),
    )
    check("unhandled message/call never reach the LLM", host.engine.calls == engineCallsBefore)
}

// Dictated messaging + calls: a model-proposed send (grounded
// fallback block) reaches the same plan path with MODEL origin —
// the per-message card still stands downstream.
private fun testModelMessageForwarding() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    host.engine.reply = "[[LOOM_ACTION:SEND_WHATSAPP arg=\"hello there\" recipient=\"Mum\"]] I'll send that."
    val mgr = newManager(pipeline, host)
    mgr.start()
    pipeline.deliverResult("help me get in touch with mum")
    check(
        "model whatsapp reaches the planner with MODEL origin",
        host.planRequests == listOf(
            PlanRequest(
                "send whatsapp message to Mum saying hello there",
                RequestOrigin.MODEL,
                transparency = false,
            ),
        ),
    )
    host.engine.reply = "[[LOOM_ACTION:PLACE_CALL arg=\"Mum\"]] Calling."
    pipeline.deliverResult("i need to talk to mum on the phone")
    check(
        "model call reaches the planner with MODEL origin",
        host.planRequests.last().goal == "call Mum" &&
            host.planRequests.last().origin == RequestOrigin.MODEL,
    )
    check("session still live after model handoffs", mgr.state == VoiceSessionState.LISTENING)
}

// Stage 2D (Worker C): a matched multi-step voice instruction reaches
// the host as a PlanRequest with DIRECT_USER origin and the goal
// verbatim; a model-proposed plan reaches the same host path with
// MODEL origin — never DIRECT_USER. An unhandled host gets the honest
// routing message, and nothing is ever spoken before the host reports.
private fun testPlanTaskForwarding() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("open YouTube Music and play a song by Davido")
    check(
        "voice plan forwards goal verbatim + DIRECT_USER + not transparent",
        host.planRequests == listOf(
            PlanRequest(
                goal = "open YouTube Music and play a song by Davido",
                origin = RequestOrigin.DIRECT_USER,
                transparency = false,
                contextApp = null,
                // Task-record fix (2026-11-07): the handoff carries
                // the record created when the instruction was
                // understood — same goal, same app, DIRECT_USER.
                taskRecord = TaskRecord(
                    instruction = "open YouTube Music and play a song by Davido",
                    app = "YouTube Music",
                    appPackage = null,
                    query = null,
                    origin = RequestOrigin.DIRECT_USER,
                ),
            ),
        ),
    )
    check("voice plan never reaches the LLM", host.engine.calls == engineCallsBefore)
    check(
        "voice plan speaks no claim before the host reports",
        pipeline.spoken.none {
            it.contains("I've requested", ignoreCase = true) ||
                it.contains("Opening", ignoreCase = true)
        },
    )
    pipeline.deliverResult(
        "open YouTube Music and play a song by Davido, show me what you're doing",
    )
    check(
        "voice plan with show-me strips the goal and sets transparency",
        host.planRequests.last() == PlanRequest(
            goal = "open YouTube Music and play a song by Davido",
            origin = RequestOrigin.DIRECT_USER,
            transparency = true,
            // Task-record fix: the first plan turn remembered the app
            // into the last-turn context, so the second turn carries
            // it as contextApp (was null before plan turns remembered).
            contextApp = "YouTube Music",
            taskRecord = TaskRecord(
                instruction = "open YouTube Music and play a song by Davido",
                app = "YouTube Music",
                appPackage = null,
                query = null,
                origin = RequestOrigin.DIRECT_USER,
            ),
        ),
    )
    pipeline.deliverResult("guide me through turning on dark mode")
    check(
        "voice guide-through-process forwards as a plan, DIRECT_USER",
        host.planRequests.last().goal == "guide me through turning on dark mode" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    check("session still live after plan handoffs", mgr.state == VoiceSessionState.LISTENING)

    // Model path: an unmatched utterance goes to the engine, whose
    // PLAN_TASK block reaches the same host path with MODEL origin.
    host.engine.reply =
        "[[LOOM_ACTION:PLAN_TASK arg=\"open YouTube Music and play a song by Davido\"]]"
    pipeline.deliverResult("help me play some davido")
    check(
        "model plan forwards with MODEL origin, never DIRECT_USER",
        host.planRequests.last() == PlanRequest(
            goal = "open YouTube Music and play a song by Davido",
            origin = RequestOrigin.MODEL,
            transparency = false,
            // Task-record fix: as above, the earlier plan turns left
            // YouTube Music as the last-turn context app.
            contextApp = "YouTube Music",
            taskRecord = TaskRecord(
                instruction = "open YouTube Music and play a song by Davido",
                app = "YouTube Music",
                appPackage = null,
                query = null,
                origin = RequestOrigin.MODEL,
            ),
        ),
    )
    check("session still live after the model plan", mgr.state == VoiceSessionState.LISTENING)

    // Unhandled host: the honest routing message, exact sentence.
    resetOwner()
    val routingPipeline = FakeVoicePipeline()
    val routingHost = FakeHost()
    val routingMgr = newManager(routingPipeline, routingHost)
    routingMgr.start()
    routingPipeline.deliverResult("open YouTube Music and play a song by Davido")
    check(
        "unhandled plan speaks the routing message",
        routingPipeline.spoken.last() ==
            "I can't run multi-step tasks from this session — start the " +
            "floating assistant and say it by voice.",
    )
    check("session still live after the routing message", routingMgr.state == VoiceSessionState.LISTENING)
}

// Task-record fix (2026-11-07): the record created by one turn
// survives into the next — a bare follow-up resumes it, an answer to
// the one app question fills it, and a later launch of another app
// supersedes it. Provenance never leaks: a resumed MODEL turn keeps
// MODEL origin even when the record it adopted is DIRECT_USER.
private fun testTaskRecordResume() {
    // (a) Bare follow-up resumes the record: "play music" after the
    // full Davido instruction reaches the host with the SAME record
    // (app YouTube Music, original instruction), MODEL origin (the
    // turn arrived via the engine), never a re-derived bare goal.
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    pipeline.deliverResult("open YouTube Music and play a song by Davido")
    host.engine.reply = "[[LOOM_ACTION:PLAN_TASK arg=\"play music\"]]"
    pipeline.deliverResult("play music")
    val resumed = host.planRequests.last()
    check(
        "bare follow-up keeps the goal as spoken",
        resumed.goal == "play music",
    )
    check(
        "bare follow-up keeps MODEL origin, never inherits DIRECT_USER",
        resumed.origin == RequestOrigin.MODEL,
    )
    check(
        "bare follow-up carries the recorded app",
        resumed.taskRecord?.app == "YouTube Music",
    )
    check(
        "bare follow-up carries the original instruction",
        resumed.taskRecord?.instruction ==
            "open YouTube Music and play a song by Davido",
    )
    check(
        "bare follow-up record still DIRECT_USER provenance",
        resumed.taskRecord?.origin == RequestOrigin.DIRECT_USER,
    )
    check("session still live after the resumed follow-up", mgr.state == VoiceSessionState.LISTENING)

    // (b) The one app question + its answer: "play music" with no
    // app anywhere creates a record waiting for the app; the next
    // turn ("YouTube Music") fills the slot and re-issues the
    // ORIGINAL instruction with the SAME record — no engine call,
    // no new task, no second question.
    resetOwner()
    val pipeline2 = FakeVoicePipeline()
    val host2 = PlanCapableHost()
    val mgr2 = newManager(pipeline2, host2)
    mgr2.start()
    host2.engine.reply = "[[LOOM_ACTION:PLAN_TASK arg=\"play music\"]]"
    pipeline2.deliverResult("play music")
    val waiting = host2.planRequests.last()
    check(
        "app-less plan creates a record waiting for the app",
        waiting.taskRecord?.awaitingApp == true &&
            waiting.taskRecord?.app == null,
    )
    val engineCallsBeforeAnswer = host2.engine.calls
    pipeline2.deliverResult("YouTube Music")
    check(
        "answer turn never reaches the engine",
        host2.engine.calls == engineCallsBeforeAnswer,
    )
    val answered = host2.planRequests.last()
    check(
        "answer turn re-issues the original instruction",
        answered.goal == "play music",
    )
    check(
        "answer turn fills the recorded app",
        answered.taskRecord?.app == "YouTube Music",
    )
    check(
        "answer turn clears the wait",
        answered.taskRecord?.awaitingApp == false,
    )
    check("session still live after the answer turn", mgr2.state == VoiceSessionState.LISTENING)

    // (c) Supersede: launching another app ("open YouTube") after the
    // Davido plan means a later bare "play music" does NOT resume the
    // YouTube Music record — the last-turn app wins instead.
    // MASTER F3c: the supersede happens when the launch is CONFIRMED
    // (the host's outcome), not when it is dispatched — so the test
    // simulates the "Opened YouTube." outcome here, exactly as the
    // floating host reports it through speakNow / noteLaunchOutcome.
    resetOwner()
    val pipeline3 = FakeVoicePipeline()
    val host3 = PlanCapableHost()
    val mgr3 = newManager(pipeline3, host3)
    mgr3.start()
    pipeline3.deliverResult("open YouTube Music and play a song by Davido")
    pipeline3.deliverResult("open YouTube")
    mgr3.noteLaunchOutcome("youtube", true)
    host3.engine.reply = "[[LOOM_ACTION:PLAN_TASK arg=\"play music\"]]"
    pipeline3.deliverResult("play music")
    val superseded = host3.planRequests.last()
    check(
        "superseded follow-up carries no recorded app",
        superseded.taskRecord?.app == null,
    )
    check(
        "superseded follow-up resolves from the last-turn app",
        superseded.contextApp == "youtube",
    )
    check("session still live after the superseded follow-up", mgr3.state == VoiceSessionState.LISTENING)

    // (d) A search WITH a second clause is a fresh instruction,
    // never a bare follow-up — even though it starts with
    // "search ", it must not resume the old record's instruction.
    pipeline3.deliverResult("search for Davido and open the first result")
    val searchAction = host3.planRequests.last()
    check(
        "search-and-action starts a fresh record",
        searchAction == PlanRequest(
            goal = "search for Davido and open the first result",
            origin = RequestOrigin.DIRECT_USER,
            transparency = false,
            contextApp = "youtube",
            taskRecord = TaskRecord(
                instruction = "search for Davido and open the first result",
                app = null,
                appPackage = null,
                query = null,
                origin = RequestOrigin.DIRECT_USER,
            ),
        ),
    )
    check(
        "fresh record replaces the old instruction",
        searchAction.taskRecord?.instruction ==
            "search for Davido and open the first result",
    )
    check("session still live after the search-and-action turn", mgr3.state == VoiceSessionState.LISTENING)
}

// MASTER F1b: "go to X" / "take me to X" route to the launch host —
// never the mode question, never the engine.
// MASTER F4a: the route is a single-action plan ("open <app>").
private fun testGoToAppForwarding() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("go to YouTube")
    check(
        "go to YouTube reaches the planner as open youtube",
        host.planRequests.last().goal == "open youtube" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    check("go to YouTube never reaches the LLM", host.engine.calls == engineCallsBefore)
    check(
        "go to YouTube never asks the mode question",
        pipeline.spoken.none { it.contains("Which mode") },
    )
    pipeline.deliverResult("take me to YouTube Music")
    check(
        "take me to reaches the planner as open youtube music",
        host.planRequests.last().goal == "open youtube music",
    )
    pipeline.deliverResult("go to Google Play Store")
    check(
        "go to Google Play Store reaches the planner as open google play store",
        host.planRequests.last().goal == "open google play store",
    )
    check(
        "go to Google Play Store never asks the mode question",
        pipeline.spoken.none { it.contains("Which mode") },
    )
    pipeline.deliverResult("go to my DM")
    check(
        "go to my DM reaches the planner as a launch attempt",
        host.planRequests.last().goal == "open dm",
    )
    check("session still live after go-to launches", mgr.state == VoiceSessionState.LISTENING)
}

// MASTER F1d: deterministic console follow-ups reach the planner as
// small console tasks — never the engine, never a re-proposed search.
private fun testConsoleFollowUps() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls
    pipeline.deliverResult("scroll down")
    check(
        "scroll down reaches the planner as a console task",
        host.planRequests.last().goal == "scroll down" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    check("scroll down never reaches the LLM", host.engine.calls == engineCallsBefore)
    pipeline.deliverResult("go back")
    check(
        "go back with no session reaches the planner as GO_BACK",
        host.planRequests.last().goal == "go back",
    )
    pipeline.deliverResult("click on enter")
    check(
        "click on enter reaches the planner as press enter",
        host.planRequests.last().goal == "press enter",
    )
    pipeline.deliverResult("tap the first result")
    check(
        "tap the first result reaches the planner as a tap",
        host.planRequests.last().goal == "tap the first result",
    )
    check("session still live after console follow-ups", mgr.state == VoiceSessionState.LISTENING)

    // Model path: the same follow-ups as action blocks — the model
    // can now advance a search instead of re-proposing it.
    host.engine.reply = "[[LOOM_ACTION:SCROLL arg=\"down\"]]"
    pipeline.deliverResult("do the thing")
    check(
        "model SCROLL reaches the planner as scroll down",
        host.planRequests.last().goal == "scroll down",
    )
    host.engine.reply = "[[LOOM_ACTION:GO_BACK]]"
    pipeline.deliverResult("do the thing")
    check(
        "model GO_BACK reaches the planner as go back",
        host.planRequests.last().goal == "go back",
    )
    host.engine.reply = "[[LOOM_ACTION:TAP_TARGET arg=\"the search box\"]]"
    pipeline.deliverResult("do the thing")
    check(
        "model TAP_TARGET reaches the planner as a tap",
        host.planRequests.last().goal == "tap the search box",
    )
    host.engine.reply = "[[LOOM_ACTION:SUBMIT]]"
    pipeline.deliverResult("do the thing")
    check(
        "model SUBMIT reaches the planner as press enter",
        host.planRequests.last().goal == "press enter",
    )

    // BACK with a live guidance session goes to the session, not a plan.
    resetOwner()
    val guidedPipeline = FakeVoicePipeline()
    val guidedHost = PlanCapableHost()
    val guidedMgr = newManager(guidedPipeline, guidedHost, GuidedBackLayer())
    guidedMgr.start()
    val plansBefore = guidedHost.planRequests.size
    guidedPipeline.deliverResult("go back")
    check(
        "go back with a live session goes to the session, not a plan",
        guidedHost.planRequests.size == plansBefore &&
            guidedPipeline.spoken.last() == "Went back a step.",
    )
}

/** A layer with a live sequence whose back step answers. */
private class GuidedBackLayer : InteractionLayer() {
    override val isSequenceActive: Boolean = true
    override fun guideStep(next: Boolean): String? =
        if (next) "Moved to the next step." else "Went back a step."
}

// MASTER F3a: a second manager instance (a new floating session, as
// on every orb tap) inherits the first one's active task and
// last-turn app from the shared context.
private fun testSharedContextInheritance() {
    SharedConversationContext.resetForTest()
    // (a) The active task record survives a session recreate: a bare
    // follow-up on the new manager resumes the first manager's task.
    resetOwner()
    val pipeline1 = FakeVoicePipeline()
    val host1 = PlanCapableHost()
    val mgr1 = newManagerShared(pipeline1, host1)
    mgr1.start()
    pipeline1.deliverResult("open YouTube Music and play a song by Davido")
    check(
        "first session creates the record",
        host1.planRequests.last().taskRecord?.app == "YouTube Music",
    )
    val pipeline2 = FakeVoicePipeline()
    val host2 = PlanCapableHost()
    val mgr2 = newManagerShared(pipeline2, host2)
    mgr2.start()
    host2.engine.reply = "[[LOOM_ACTION:PLAN_TASK arg=\"play music\"]]"
    pipeline2.deliverResult("play music")
    check(
        "second session resumes the inherited record's app",
        host2.planRequests.last().taskRecord?.app == "YouTube Music",
    )
    check(
        "second session resumes the inherited original instruction",
        host2.planRequests.last().taskRecord?.instruction ==
            "open YouTube Music and play a song by Davido",
    )

    // (b) The last-turn app survives too: a follow-up search on the
    // new manager resolves from the first session's confirmed launch.
    SharedConversationContext.resetForTest()
    resetOwner()
    val pipeline3 = FakeVoicePipeline()
    val host3 = FakeHost()
    val mgr3 = newManagerShared(pipeline3, host3)
    mgr3.start()
    pipeline3.deliverResult("open YouTube")
    mgr3.noteLaunchOutcome("youtube", true)
    val pipeline4 = FakeVoicePipeline()
    val host4 = PlanCapableHost()
    val mgr4 = newManagerShared(pipeline4, host4)
    mgr4.start()
    pipeline4.deliverResult("search for Davido")
    check(
        "second session search resolves from the inherited last app",
        host4.planRequests == listOf(
            PlanRequest(
                "search for davido in youtube",
                RequestOrigin.DIRECT_USER,
                transparency = false,
                contextApp = "youtube",
            ),
        ),
    )
    check("session still live after inherited follow-ups", mgr4.state == VoiceSessionState.LISTENING)
}

// MASTER F3c: a launch is an attempt until its outcome is known —
// the follow-up app comes only from a CONFIRMED launch.
// MASTER F4a: launches and searches reach the planner as
// single-action plans; the "no plan ran" assertions use
// planRequests (a question asked means no plan was requested).
private fun testLaunchAttemptNotLastApp() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    pipeline.deliverResult("open MovieBox")
    check(
        "launch attempt reaches the planner as open moviebox",
        host.planRequests.last().goal == "open moviebox",
    )
    pipeline.deliverResult("search for Spider-Man")
    check(
        "unconfirmed launch does NOT seed the follow-up app",
        pipeline.spoken.last() == "Which app should I search in?" &&
            host.planRequests.size == 1,
    )
    // A failed outcome changes nothing either: a NEW query still asks
    // (proving the failed launch did not seed the app), and the
    // repeat of that query then fails honestly (the asked-once guard).
    mgr.noteLaunchOutcome("moviebox", false)
    pipeline.deliverResult("search for Burna Boy")
    check(
        "failed launch does NOT seed the follow-up app",
        pipeline.spoken.last() == "Which app should I search in?" &&
            host.planRequests.size == 1,
    )
    // An outcome for a DIFFERENT app promotes nothing: a new query
    // still asks instead of routing to the other app.
    mgr.noteLaunchOutcome("youtube", true)
    pipeline.deliverResult("search for Davido")
    check(
        "outcome for another app promotes nothing",
        pipeline.spoken.last() == "Which app should I search in?" &&
            host.planRequests.size == 1,
    )
    // The host's spoken success sentence promotes the attempt: the
    // floating host speaks "Opened MovieBox." through speakNow.
    pipeline.deliverResult("open MovieBox")
    mgr.speakNow("Opened MovieBox.")
    pipeline.deliverResult("search for Spider-Man")
    check(
        "spoken Opened sentence promotes the attempt",
        host.planRequests.last().goal == "search for spider-man in moviebox" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    // A LATE success for an attempt the user already moved on from
    // promotes nothing: a search in between voids the attempt.
    pipeline.deliverResult("open YouTube")
    pipeline.deliverResult("search for Davido in WhatsApp")
    check(
        "search with an explicit app runs",
        host.planRequests.last().goal == "search for davido in whatsapp",
    )
    mgr.speakNow("Opened YouTube.")
    pipeline.deliverResult("search for Burna Boy")
    check(
        "stale Opened sentence does not overwrite the last app",
        host.planRequests.last().goal == "search for burna boy in whatsapp",
    )
    check("session still live after launch-outcome turns", mgr.state == VoiceSessionState.LISTENING)
}

// MASTER F3b: the single-command search/launch questions are asked
// at most once per task, then fail honestly — never re-asked.
// MASTER F4a: searches and launches reach the planner as
// single-action plans; the "no plan ran" assertions use planRequests.
private fun testAskedOnceGuards() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    val engineCallsBefore = host.engine.calls

    // App question: ask once, then fail honestly on the repeat.
    pipeline.deliverResult("search for Davido")
    check(
        "first search with no app asks which app",
        pipeline.spoken.last() == "Which app should I search in?",
    )
    pipeline.deliverResult("search for Davido")
    check(
        "repeat search with no app fails honestly, never re-asks",
        pipeline.spoken.last().contains("asked once already") &&
            host.planRequests.isEmpty(),
    )
    check("asked-once failure never reaches the LLM", host.engine.calls == engineCallsBefore)
    // A NEW query is a new task: it asks again (the guard resets),
    // and its own repeat fails honestly.
    pipeline.deliverResult("search for Burna Boy")
    check(
        "new query asks which app again",
        pipeline.spoken.last() == "Which app should I search in?",
    )
    pipeline.deliverResult("search for Burna Boy")
    check(
        "repeat of the new query fails honestly",
        pipeline.spoken.last().contains("asked once already"),
    )
    // A search WITH the app runs — and seeds the follow-up app, so
    // the next search without an app resolves from context instead
    // of asking (the guard is for when no app is known at all).
    pipeline.deliverResult("search for Burna Boy in YouTube")
    check(
        "search with an app runs and clears the guard",
        host.planRequests.last().goal == "search for burna boy in youtube" &&
            host.planRequests.last().origin == RequestOrigin.DIRECT_USER,
    )
    pipeline.deliverResult("search for Davido")
    check(
        "after a success the follow-up resolves from the last app",
        host.planRequests.last().goal == "search for davido in youtube",
    )

    // Query question: ask once, then fail honestly on the repeat.
    pipeline.deliverResult("search for")
    check(
        "bare search asks what to search for",
        pipeline.spoken.last() == "What do you want me to search for?",
    )
    pipeline.deliverResult("search for")
    check(
        "repeat bare search fails honestly, never re-asks",
        pipeline.spoken.last().contains("I still don't know what to search for"),
    )

    // Launch question: ask once, then fail honestly; a real launch
    // resets the flag.
    pipeline.deliverResult("open")
    check(
        "bare open asks which app",
        pipeline.spoken.last() == "Which app do you want me to open?",
    )
    pipeline.deliverResult("open")
    check(
        "repeat bare open fails honestly, never re-asks",
        pipeline.spoken.last().contains("I still don't know which app to open"),
    )
    pipeline.deliverResult("open YouTube")
    check(
        "real launch runs after the guard",
        host.planRequests.last().goal == "open youtube",
    )
    pipeline.deliverResult("open")
    check(
        "launch resets the guard — bare open asks again",
        pipeline.spoken.last() == "Which app do you want me to open?",
    )
    check("session still live after asked-once turns", mgr.state == VoiceSessionState.LISTENING)
}

// MASTER F4a/F6/F4d — unified execution + guidance/demo provenance +
// plan-summary launch promotion, in one place:
// - matched guide/demo note DIRECT_USER origin on the host before the
//   layer call; model-proposed guide/demo note MODEL origin.
// - a model-proposed launch reaches the planner with MODEL origin.
// - a completed plan's "Done — … for: open <app>." summary promotes
//   the pending launch attempt (F4d), so the follow-up search
//   resolves from the just-launched app; a partial "Finished with…"
//   summary promotes nothing.
private fun testMasterF4UnifiedExecution() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = GuidanceNotingHost()
    val layer = RecordingLayer()
    val mgr = newManager(pipeline, host, layer)
    mgr.start()

    pipeline.deliverResult("guide me through the settings")
    check(
        "matched guide notes DIRECT_USER origin on the host",
        host.guideOrigins == listOf(RequestOrigin.DIRECT_USER),
    )
    check(
        "matched guide reaches the layer with the target",
        layer.guidanceQueries == listOf("the settings"),
    )
    pipeline.deliverResult("show me how to turn on dark mode")
    check(
        "matched demo notes DIRECT_USER origin on the host",
        host.demoOrigins == listOf(RequestOrigin.DIRECT_USER),
    )
    check(
        "matched demo reaches the layer with the goal",
        layer.demonstrationGoals == listOf("turn on dark mode"),
    )

    // Model-proposed guide/demo/launch: same handlers, MODEL origin.
    // ("do the thing" is unmatched — it reaches the engine, whose
    // reply carries the action block, exactly as testConsoleFollowUps
    // and testModelMessageForwarding drive the model path.)
    host.engine.reply = "[[LOOM_ACTION:GUIDE_TO arg=\"open Settings\"]] Guiding."
    pipeline.deliverResult("do the thing")
    check(
        "model guide notes MODEL origin on the host",
        host.guideOrigins.last() == RequestOrigin.MODEL,
    )
    host.engine.reply = "[[LOOM_ACTION:DEMO_SEQUENCE arg=\"turn on dark mode\"]] Showing."
    pipeline.deliverResult("do the thing")
    check(
        "model demo notes MODEL origin on the host",
        host.demoOrigins.last() == RequestOrigin.MODEL,
    )
    host.engine.reply = "[[LOOM_ACTION:LAUNCH_APP arg=\"YouTube\"]] Opening."
    pipeline.deliverResult("do the thing")
    check(
        "model launch reaches the planner with MODEL origin",
        // The model path carries the action block's app name
        // verbatim ("YouTube"); the matched path lowercases it.
        host.planRequests.last().goal == "open YouTube" &&
            host.planRequests.last().origin == RequestOrigin.MODEL,
    )

    // F4d: a completed launch plan's summary promotes the attempt.
    pipeline.deliverResult("open YouTube")
    mgr.speakNow("Done — completed all 2 step(s) for: open youtube.")
    pipeline.deliverResult("search for Davido")
    check(
        "plan Done summary promotes the launch — follow-up resolves from it",
        host.planRequests.last().goal == "search for davido in youtube",
    )

    // F4d negative: a partial summary promotes nothing.
    resetOwner()
    val pipeline2 = FakeVoicePipeline()
    val host2 = PlanCapableHost()
    val mgr2 = newManager(pipeline2, host2)
    mgr2.start()
    pipeline2.deliverResult("open YouTube")
    mgr2.speakNow("Finished with 1 of 2 step(s) verified for: open youtube.")
    pipeline2.deliverResult("search for Davido")
    check(
        "partial plan summary promotes nothing — search asks which app",
        pipeline2.spoken.last() == "Which app should I search in?",
    )
    check("session still live after F4 turns", mgr2.state == VoiceSessionState.LISTENING)
}

// MASTER Wave 4 (F12 + F16 follow-up): durable task status.
// Nothing exists only as speech: every voice turn — including a
// matched command and a greeting — leaves a heard transcript and an
// outcome in the shared store, so Home "Recent Conversations" can
// list it. A lost command leaves a trace too, once per turn, with
// the partial that was heard.
private fun testDurableHeardTranscript() {
    // A matched plan command used to leave NO trace in the store
    // (only the engine fallthrough appended user text).
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    pipeline.deliverResult("open YouTube Music and play a song by Davido")
    val userLines = host.store.getActive().messages.filter { it.role == "user" }
    check(
        "matched plan turn stores the heard transcript",
        userLines.any { it.text == "open YouTube Music and play a song by Davido" },
    )
    check(
        "heard transcript stored exactly once for the turn",
        userLines.count { it.text == "open YouTube Music and play a song by Davido" } == 1,
    )
}

private fun testGreetingDurableOnce() {
    // A greeting is a task too (acceptance T-01): one TaskResult per
    // voice task, including greetings — user line + assistant reply,
    // each exactly once.
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverResult("hello loom")
    val messages = host.store.getActive().messages
    check(
        "greeting stores the heard user line",
        messages.count { it.role == "user" && it.text == "hello loom" } == 1,
    )
    check(
        "greeting stores the assistant reply once",
        messages.count { it.role == "assistant" && it.text == "fake reply" } == 1,
    )
    check("greeting keeps the session live", mgr.state == VoiceSessionState.LISTENING)
}

private fun testThinkingShowsHeard() {
    // The video's ~20s "Thinking…" showed no transcript. The
    // PROCESSING status now carries the heard text.
    val (_, pipeline, host) = startLive()
    pipeline.deliverResult("what is the capital of France?")
    check(
        "Thinking status carries the heard transcript",
        host.statusTexts.any { it.contains("Thinking") && it.contains("what is the capital of France?") },
    )
}

private fun testLostTurnStoredOnce() {
    // A lost command ("Didn't catch that") used to vanish — spoken
    // only, no store trace. It is now stored once per turn.
    // VOICE FIX (2026-11-10): this is the TRUE no-speech case — the
    // recognizer errored and NO partial arrived, so nothing was heard.
    // (A partial that WAS heard is now routed, not recorded as lost —
    // see testSpokenUnmatchedQuestionAnswered.) The trace is stored
    // once per turn, and the bounded retry ends in ERROR instead of
    // looping.
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverError("Didn't catch that — try again.")
    val lost = host.store.getActive().messages.filter { it.role == "assistant" }
    check(
        "lost turn stored as an assistant trace",
        lost.any { it.text.contains("Didn't catch that") },
    )
    check(
        "lost turn stored exactly once across the retry",
        lost.count { it.text.contains("Didn't catch that") } == 1,
    )
    check(
        "first no-speech failure still retries listening",
        mgr.state == VoiceSessionState.LISTENING,
    )
}

// VOICE FIX (2026-11-10): the Nov 10 device incident — David spoke
// "can you hear me", the recognizer delivered the partial correctly
// and then errored, and the session looped "Didn't catch that" four
// times without ever answering. A heard partial must be answered
// like typed chat, not discarded.
private fun testSpokenUnmatchedQuestionAnswered() {
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverPartial("can you hear me")
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "heard partial reaches the engine instead of a soft failure",
        host.engine.calls == 1 && host.engine.lastUserText == "can you hear me",
    )
    check(
        "Thinking status carries the heard partial",
        host.statusTexts.any { it.contains("Thinking") && it.contains("can you hear me") },
    )
    check(
        "heard partial stored as the user line",
        host.store.getActive().messages.any { it.role == "user" && it.text == "can you hear me" },
    )
    check(
        "engine reply stored as the assistant line",
        host.store.getActive().messages.any { it.role == "assistant" && it.text == "fake reply" },
    )
    check(
        "engine reply spoken out loud",
        pipeline.spoken.contains("fake reply"),
    )
    check(
        "no \"Didn't catch that\" trace for a turn that was answered",
        host.store.getActive().messages.none { it.text.contains("Didn't catch that") },
    )
    check(
        "session resumes listening after the spoken answer",
        mgr.state == VoiceSessionState.LISTENING,
    )
}

// VOICE FIX (2026-11-10): a partial that IS a command executes as
// that command — command matching still runs first on the routed
// partial, exactly as for a final result.
private fun testSpokenCommandAfterPartialError() {
    resetOwner()
    val pipeline = FakeVoicePipeline()
    val host = PlanCapableHost()
    val mgr = newManager(pipeline, host)
    mgr.start()
    pipeline.deliverPartial("open youtube")
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "partial that is a command executes as that command",
        host.planRequests.any { it.goal == "open youtube" },
    )
    check(
        "command from a partial never reaches the engine as chat",
        host.engine.calls == 0,
    )
    check(
        "no \"Didn't catch that\" trace for a turn that was executed",
        host.store.getActive().messages.none { it.text.contains("Didn't catch that") },
    )
}

// VOICE FIX (2026-11-10): the second lost-result shape — an empty
// final result after a partial arrived. Same rule as the error path.
private fun testBlankFinalResultUsesPartial() {
    val (_, pipeline, host) = startLive()
    pipeline.deliverPartial("can you hear me")
    pipeline.deliverResult("")
    check(
        "blank final result falls back to the partial",
        host.engine.calls == 1 && host.engine.lastUserText == "can you hear me",
    )
    check(
        "engine reply spoken out loud",
        pipeline.spoken.contains("fake reply"),
    )
    check(
        "no \"Didn't catch that\" trace for a turn that was answered",
        host.store.getActive().messages.none { it.text.contains("Didn't catch that") },
    )
}

// VOICE FIX (2026-11-10): the no-speech retry is bounded at 2.
// Three consecutive errors with nothing heard: the first two retry,
// the third stops in ERROR and says plainly that nothing was heard.
// A fourth error is a stale callback and changes nothing.
private fun testNoSpeechRetryBounded() {
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "first no-speech failure retries listening",
        mgr.state == VoiceSessionState.LISTENING,
    )
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "second no-speech failure retries listening",
        mgr.state == VoiceSessionState.LISTENING,
    )
    val spokenBefore = pipeline.spoken.size
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "third no-speech failure stops in ERROR, never a fourth retry",
        mgr.state == VoiceSessionState.ERROR,
    )
    check(
        "terminal status says plainly that nothing was heard",
        host.statusTexts.lastOrNull()?.contains("didn't hear anything") == true,
    )
    check(
        "terminal message is spoken once, out loud",
        pipeline.spoken.size == spokenBefore + 1 &&
            pipeline.spoken.last().contains("didn't hear anything"),
    )
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "a further error after ERROR changes nothing",
        mgr.state == VoiceSessionState.ERROR &&
            pipeline.spoken.size == spokenBefore + 1,
    )
    check(
        "lost turn still stored exactly once across the bounded retries",
        host.store.getActive().messages.count {
            it.text.contains("Didn't catch that")
        } == 1,
    )
    // Orb no-speech fix (2026-11-10): the terminal message promises
    // "tap the orb to try again" — one orb tap resumes from ERROR
    // (retry bound reset), it does not end the session.
    mgr.resume()
    check(
        "resume from ERROR after the terminal message listens again",
        mgr.state == VoiceSessionState.LISTENING,
    )
}

// VOICE FIX (2026-11-10): the partial is consumed once. A later
// error with no new partial must NOT re-route the same text — it is
// a fresh no-speech failure.
private fun testPartialConsumedOnce() {
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverPartial("can you hear me")
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "partial routed exactly once",
        host.engine.calls == 1,
    )
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "second error without a new partial does not re-route",
        host.engine.calls == 1,
    )
    // D-02 (2026-11-11): the partial WAS heard and answered — that
    // turn is finished, so the second error's silence is an idle
    // listen after a finished turn: it ends quietly, never a
    // "Didn't catch that" bubble. The old expectation (record the
    // failure) is the exact defect the screenshots show — a
    // Didn't-catch bubble sitting after a completed exchange.
    check(
        "second error after the answered partial ends quietly, never a Didn't-catch bubble",
        mgr.state == VoiceSessionState.IDLE &&
            host.store.getActive().messages.none { it.text.contains("Didn't catch that") },
    )
}

// Utterance fix (2026-11-11): the MEDIUM error branch (network /
// server / busy) used to record the partial and retry — the heard
// words were lost and the NEXT statement became the one sent. The
// partial now routes through the normal path, same as the NO_MATCH
// branch. Only a turn with NO partial at all falls to the retry.
private fun testMediumErrorRoutesPartial() {
    // (a) Partial that is a command executes as that command.
    run {
        resetOwner()
        val pipeline = FakeVoicePipeline()
        val host = PlanCapableHost()
        val mgr = newManager(pipeline, host)
        mgr.start()
        pipeline.deliverPartial("open youtube")
        pipeline.deliverError("Recognition error (2).")
        check(
            "medium error: partial command executes as that command",
            host.planRequests.any { it.goal == "open youtube" },
        )
        check(
            "medium error: command from a partial never reaches the engine",
            host.engine.calls == 0,
        )
        check(
            "medium error: no wobble trace for a turn that was executed",
            host.store.getActive().messages.none { it.text.contains("Connection wobbled") },
        )
    }
    // (b) Partial that is conversational reaches the engine.
    run {
        val (_, pipeline, host) = startLive()
        pipeline.deliverPartial("can you hear me")
        pipeline.deliverError("Recognition error (2).")
        check(
            "medium error: partial reaches the engine",
            host.engine.calls == 1 && host.engine.lastUserText == "can you hear me",
        )
        check(
            "medium error: engine reply spoken out loud",
            pipeline.spoken.contains("fake reply"),
        )
    }
    // (c) The partial is consumed once — a second medium error with
    // no new partial does NOT re-route the same text.
    run {
        val (_, pipeline, host) = startLive()
        pipeline.deliverPartial("can you hear me")
        pipeline.deliverError("Recognition error (2).")
        pipeline.deliverError("Recognition error (2).")
        check(
            "medium error: partial routed exactly once",
            host.engine.calls == 1,
        )
    }
    // (d) No partial at all: the bounded retry still stands — the
    // session stays live and listens again, never an instant error.
    run {
        val (mgr, pipeline, _) = startLive()
        pipeline.deliverError("Recognition error (2).")
        check(
            "medium error with no partial retries listening",
            mgr.state == VoiceSessionState.LISTENING,
        )
    }
}

// Utterance fix (2026-11-11): the silence windows are constants on
// the pipeline contract (3000 ms complete / 2000 ms possibly
// complete), so a mid-speech hesitation does not split the turn.
// The recognizer itself is device-only; the constants are what the
// harness can prove.
private fun testSilenceWindowConstants() {
    check(
        "complete-silence window is 3000 ms",
        VoicePipeline.COMPLETE_SILENCE_MS == 3000L,
    )
    check(
        "possibly-complete-silence window is 2000 ms",
        VoicePipeline.POSSIBLY_COMPLETE_SILENCE_MS == 2000L,
    )
    check(
        "complete window is longer than the possibly-complete window",
        VoicePipeline.COMPLETE_SILENCE_MS > VoicePipeline.POSSIBLY_COMPLETE_SILENCE_MS,
    )
}

private fun testMatchedCommandStillFirstOnPartial() {
    val (mgr, pipeline, host) = startLive()
    pipeline.deliverPartial("stop listening")
    pipeline.deliverError("Didn't catch that — try again.")
    check(
        "partial matching a command stops listening",
        mgr.state == VoiceSessionState.IDLE,
    )
    check(
        "matched command from a partial never reaches the engine",
        host.engine.calls == 0,
    )
}

// Device-test failures (2026-11-11, D-02/D-04/D-05): the idle listen
// ends quietly, "confirm your screen" describes the screen, and "the
// task" resumes the last task.
private class DescribingHost : FakeHost() {
    override fun describeCurrentScreen(): String =
        "You're in YouTube — I can see \"Search YouTube\" (top), \"Home\" (bottom left)."
}

private fun testDeviceTestFailuresRound() {
    // D-02a: silence AFTER a finished turn ends quietly — no bubble,
    // no spoken retry, no terminal message. The Nov 10 build reported
    // "Didn't catch that" after every turn, in every screenshot.
    run {
        val (mgr, pipeline, host) = startLive()
        pipeline.deliverResult("show the pointer") // matched turn, speaks, auto-continues
        check("d02 setup: still listening after the matched turn", mgr.state == VoiceSessionState.LISTENING)
        val bubblesBefore = host.store.getActive().messages.count { it.text.contains("Didn't catch that") }
        val spokenBefore = pipeline.spoken.size
        pipeline.deliverError("Didn't catch that — try again.")
        check("d02 idle silence ends the session", mgr.state == VoiceSessionState.IDLE)
        check(
            "d02 idle silence writes no Didn't-catch bubble",
            host.store.getActive().messages.count { it.text.contains("Didn't catch that") } == bubblesBefore,
        )
        check(
            "d02 idle silence speaks no retry",
            pipeline.spoken.size == spokenBefore,
        )
        check("d02 idle silence stops the recognizer", pipeline.stopListeningCalls >= 1)
    }
    // D-02b: the same silence on an EXPLICIT listen still reports,
    // bounded — David tapped to talk and nothing was heard.
    run {
        val (mgr, pipeline, host) = startLive()
        pipeline.deliverError("Didn't catch that — try again.")
        check("d02 explicit silence retries listening", mgr.state == VoiceSessionState.LISTENING)
        check(
            "d02 explicit silence reports once",
            host.store.getActive().messages.any { it.text.contains("Didn't catch that") },
        )
    }
    // D-02c: silence while a plan outcome is pending SUSPENDS the
    // listen instead of ending it — the spoken outcome must not be
    // lost to the quiet end.
    run {
        resetOwner()
        val pipeline = FakeVoicePipeline()
        val host = PlanCapableHost()
        val mgr = newManager(pipeline, host)
        mgr.start()
        pipeline.deliverResult("open YouTube Music and play a song by Davido")
        check("d02 plan dispatched to the host", host.planRequests.size == 1)
        check("d02 still listening after dispatch", mgr.state == VoiceSessionState.LISTENING)
        val stopsBefore = pipeline.stopListeningCalls
        pipeline.deliverError("Didn't catch that — try again.")
        check(
            "d02 pending outcome suspends, never ends",
            mgr.state == VoiceSessionState.LISTENING,
        )
        check("d02 suspension stops the recognizer", pipeline.stopListeningCalls > stopsBefore)
        mgr.speakNow("Done — completed all 1 step(s) for: open youtube.")
        check(
            "d02 suspended outcome is still spoken",
            pipeline.spoken.any { it.startsWith("Done —") },
        )
        check("d02 listening resumes after the outcome", mgr.state == VoiceSessionState.LISTENING)
    }
    // D-04: "confirm your screen" describes the live screen.
    run {
        val pipeline = FakeVoicePipeline()
        val host = DescribingHost()
        val mgr = newManager(pipeline, host)
        mgr.start()
        pipeline.deliverResult("confirm your screen")
        check(
            "d04 confirm your screen speaks the description",
            pipeline.spoken.lastOrNull() ==
                "You're in YouTube — I can see \"Search YouTube\" (top), \"Home\" (bottom left).",
        )
        check("d04 describe never reaches the engine", host.engine.calls == 0)
    }
    // D-05a: "execute the task" resumes the task already given.
    run {
        resetOwner()
        val pipeline = FakeVoicePipeline()
        val host = PlanCapableHost()
        val mgr = newManager(pipeline, host)
        mgr.start()
        val story = "open my story now you're on Facebook that's what I've told you to do so tap it and open it"
        pipeline.deliverResult(story)
        check("d05 story task dispatched", host.planRequests.size == 1)
        pipeline.deliverResult("execute the task I give to you")
        check("d05 the-task reference re-dispatches the task", host.planRequests.size == 2)
        check(
            "d05 the-task reference re-issues the story instruction",
            host.planRequests.last().goal == story,
        )
        check("d05 the-task reference never reaches the engine", host.engine.calls == 0)
    }
    // D-05b: a COMPLETED task is named as done, never re-run.
    run {
        resetOwner()
        val pipeline = FakeVoicePipeline()
        val host = PlanCapableHost()
        val mgr = newManager(pipeline, host)
        mgr.start()
        pipeline.deliverResult("open my story")
        check("d05 done-setup: task dispatched", host.planRequests.size == 1)
        SharedConversationContext.instance().noteTaskOutcome(true)
        pipeline.deliverResult("do it")
        check("d05 completed task is not re-dispatched", host.planRequests.size == 1)
        check(
            "d05 completed task is named as done",
            pipeline.spoken.any { it.contains("already done") && it.contains("open my story") },
        )
    }
    // D-05c: with no task anywhere, "the task" falls through to the
    // engine, which asks what the task is — never a denial.
    run {
        val (mgr, pipeline, host) = startLive()
        pipeline.deliverResult("execute the task")
        check("d05 no task anywhere reaches the engine", host.engine.calls == 1)
        check("d05 no task anywhere asks, never denies", mgr.state == VoiceSessionState.LISTENING)
    }
}

fun main() {
    testPausedResumeTrap()
    testTtsQueueCleared()
    testSpeakNowPausedGuard()
    testPermissionCheck()
    testLifecycleGateAndReporter()
    testGoSilent()
    testClarificationLayer()
    testImmediateShutdown()
    testEndVoiceAck()
    testHideShowDeactivate()
    testLaunchForwarding()
    testFixEverythingForwarding()
    testMoveWithLayer()
    testInAppSearchReachesHost()
    testRegistryLayerFallback()
    testInjectedLayerBeatsRegistry()
    testNoLayerNoRegistryIsHonest()
    testPauseStaysSessionPause()
    testPauseRoutesToMediaWhenPlaying()
    testMicGroundingWiring()
    testMessageCallForwarding()
    testBulkMessageRefused()
    testMessageCallRoutingHonesty()
    testModelMessageForwarding()
    testPlanTaskForwarding()
    testTaskRecordResume()
    testPointerShowAckGating()
    testEngineErrorSpokenPlain()
    testGoToAppForwarding()
    testConsoleFollowUps()
    testSharedContextInheritance()
    testLaunchAttemptNotLastApp()
    testAskedOnceGuards()
    testMasterF4UnifiedExecution()
    testDurableHeardTranscript()
    testGreetingDurableOnce()
    testThinkingShowsHeard()
    testLostTurnStoredOnce()
    testSpokenUnmatchedQuestionAnswered()
    testSpokenCommandAfterPartialError()
    testBlankFinalResultUsesPartial()
    testNoSpeechRetryBounded()
    testPartialConsumedOnce()
    testMediumErrorRoutesPartial()
    testSilenceWindowConstants()
    testMatchedCommandStillFirstOnPartial()
    testDeviceTestFailuresRound()

    println("VoiceSessionManagerTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
