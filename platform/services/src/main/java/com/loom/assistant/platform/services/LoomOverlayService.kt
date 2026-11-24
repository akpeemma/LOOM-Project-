package com.loom.assistant.platform.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.concurrent.ConcurrentHashMap
import com.loom.assistant.core.common.FloatingAttachKind
import com.loom.assistant.core.common.FloatingAttachPolicy
import com.loom.assistant.core.common.FloatingGesture
import com.loom.assistant.core.common.FloatingSurface
import com.loom.assistant.core.common.FloatingTapRouter
import com.loom.assistant.core.common.HelperStartupRecord
import com.loom.assistant.core.common.runStartupSection
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomIntents
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SharedConversationContext
import com.loom.assistant.core.common.TapAction
import com.loom.assistant.core.common.VoiceCommand
import com.loom.assistant.core.common.VoiceCommands
import com.loom.assistant.core.common.VoiceSessionOwner
import com.loom.assistant.execution.interaction.ActionPlan
import com.loom.assistant.execution.interaction.ActionQueue
import com.loom.assistant.execution.interaction.AnchorProvider
import com.loom.assistant.execution.interaction.AuditLogger
import com.loom.assistant.execution.interaction.DispatchAttempt
import com.loom.assistant.execution.interaction.EventSink
import com.loom.assistant.execution.interaction.GestureDispatch
import com.loom.assistant.execution.interaction.LongPressDispatch
import com.loom.assistant.execution.interaction.HaltProvider
import com.loom.assistant.execution.interaction.HaltSource
import com.loom.assistant.execution.interaction.InteractionLayer
import com.loom.assistant.execution.interaction.InteractionLayerRegistry
import com.loom.assistant.execution.interaction.PlanStep
import com.loom.assistant.execution.interaction.PreviewDelayer
import com.loom.assistant.execution.interaction.SequenceOutcome
import com.loom.assistant.execution.interaction.TransitionGate
import com.loom.assistant.execution.interaction.TreeSource
import com.loom.assistant.execution.interaction.busCompletionSource
import com.loom.assistant.core.common.MediaControlPhrases
import com.loom.assistant.execution.screenawareness.MediaControlRouter
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenDescriber
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.core.common.MessageChannel
import com.loom.assistant.execution.devicecontrol.DeviceControlCenter
import com.loom.assistant.execution.messaging.CallExecutor
import com.loom.assistant.execution.messaging.CallOutcome
import com.loom.assistant.execution.messaging.MessageExecutor
import com.loom.assistant.execution.messaging.MessageOutcome
import com.loom.assistant.execution.search.SearchExecutor
import com.loom.assistant.execution.search.SearchOutcome
import com.loom.assistant.interaction.edgelight.EdgeLightView
import com.loom.assistant.interaction.pointer.AnchorEvent
import com.loom.assistant.interaction.pointer.AnchorPositions
import com.loom.assistant.interaction.pointer.AnchorResolver
import com.loom.assistant.interaction.pointer.DefaultGuidanceTreeSource
import com.loom.assistant.interaction.pointer.ExecutionFeedback
import com.loom.assistant.interaction.pointer.FloatingPositionStore
import com.loom.assistant.interaction.pointer.GeomPoint
import com.loom.assistant.interaction.pointer.GeomSize
import com.loom.assistant.interaction.pointer.GuidanceMode
import com.loom.assistant.interaction.pointer.GuidanceRenderer
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.interaction.pointer.GuidanceSession
import com.loom.assistant.interaction.pointer.MainThreadGuidanceRenderer
import com.loom.assistant.interaction.pointer.PointerGuideView
import com.loom.assistant.interaction.pointer.PointerGuideViewHolder
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.interaction.pointer.PointerPolicy
import com.loom.assistant.interaction.pointer.PointerPolicyStore
import com.loom.assistant.interaction.voice.DeviceVoicePipeline
import com.loom.assistant.interaction.voice.VoicePipeline
import com.loom.assistant.interaction.voice.VoiceSessionManager
import com.loom.assistant.interaction.voice.VoiceSessionState
import com.loom.assistant.interaction.voice.VoiceState
import com.loom.assistant.intelligence.authgate.AuthConfirmationHost
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.TaskStatus
import com.loom.assistant.intelligence.memory.ChatMessage
import com.loom.assistant.lifecycle.AutomaticRecovery
import com.loom.assistant.lifecycle.IntentionalStop
import com.loom.assistant.lifecycle.IntentionalStopCommand
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.intelligence.llm.LlmMessage
import com.loom.assistant.intelligence.llm.ModelSelection
import com.loom.assistant.intelligence.llm.ResponseEngine
import com.loom.assistant.intelligence.llm.resolveEngine
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.platform.apps.AppCatalogueHolder
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomPreferences
import com.loom.assistant.presentation.designsystem.R
import com.loom.assistant.presentation.modes.AssistantMode
import com.loom.assistant.presentation.modes.CharacterOverlayView
import com.loom.assistant.presentation.modes.FloatingOrbView
import com.loom.assistant.presentation.modes.ModeBackend
import com.loom.assistant.presentation.modes.ModeController
import com.loom.assistant.presentation.modes.ModePanelView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 4 — shared overlay host (arch §2.2 mapping: generalize
 * BrahmaFloatingOverlayService for all three modes).
 *
 * ONE foreground service owns every system-wide overlay: the floating
 * character, the floating orb, the edge-light, and the pointer guide.
 * They float over OTHER apps via TYPE_APPLICATION_OVERLAY — this is the
 * core LOOM requirement, not an in-app widget.
 *
 * Rules honored:
 *  - No view is added without SYSTEM_ALERT_WINDOW; revocation at runtime
 *    removes every view gracefully (never a crash).
 *  - Invisible mode = edge-light only; services keep running headless.
 *  - Mode switches arrive as ModeChanged events; the host rebinds views.
 *  - Foreground notification is minimal, silent, low-priority (Android 8+
 *    mandates it — "invisible" means no overlay visuals, not notification-free).
 */
class LoomOverlayService : Service() {

    companion object {
        const val ACTION_START = LoomIntents.ACTION_START_OVERLAY
        const val ACTION_STOP = LoomIntents.ACTION_STOP_OVERLAY
        const val ACTION_REFRESH = LoomIntents.ACTION_REFRESH_OVERLAY
        const val ACTION_SHOW_AUTH = "com.loom.assistant.SHOW_AUTH_CARD"
        const val ACTION_HIDE_AUTH = "com.loom.assistant.HIDE_AUTH_CARD"
        const val EXTRA_AUTH_ID = "auth_pending_id"
        const val EXTRA_AUTH_TITLE = "auth_title"
        const val EXTRA_AUTH_DETAIL = "auth_detail"
        const val EXTRA_AUTH_GRANT_OFFER = "auth_grant_offer"
        const val EXTRA_AUTH_CLEAR_ALL = "auth_clear_all"

        /**
         * Stage 2C lifecycle — execute the ordered 12-step shutdown
         * (§4.3). Sent by LoomShutdown (in-app confirmed path); the service
         * runs halt → purgeAll → SHUTTING_DOWN → teardown → verification on
         * a worker thread under the application-scoped controller.
         */
        const val ACTION_EXECUTE_SHUTDOWN = "com.loom.assistant.overlay.EXECUTE_SHUTDOWN"

        /**
         * Stage 2C lifecycle — SHOW ASSISTANT notification action (HIDE
         * variant only). Restores the hidden floating presentation; guarded:
         * requires lifecycle == ACTIVE, never starts the mic, never
         * transitions the lifecycle, never REACTIVATING.
         */
        const val ACTION_SHOW_ASSISTANT = "com.loom.assistant.overlay.SHOW_ASSISTANT"

        const val NOTIF_ID = 41
        private const val CHANNEL_ID = "loom_overlay"

        /**
         * Pointer failure fix (2026-11-05): bound on marshalling a
         * show-pointer call onto the main thread (see
         * showPointerNowDetailed). Generous for a sub-100 ms seam;
         * a marshal that times out reports TIMEOUT so the caller's
         * retry loop can try again.
         */
        private const val SHOW_POINTER_MAIN_TIMEOUT_MS = 2_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Accessibility fix (2026-11-05, audit plan step 2): the live
         * service instance, or null when the service is not running —
         * the handle the Home one-tap button, typed chat and voice ack
         * call showPointerNow() on. Mirrors LoomAccessibilityService's
         * companion instance: set in onCreate, cleared in onDestroy.
         * Callers must null-check and start the service first
         * (LoomOverlayService.start) when it is null — a null instance
         * is "not running", never a reason to claim the pointer showed.
         */
        @Volatile
        var instance: LoomOverlayService? = null
            private set

        /**
         * Accessibility fix (2026-11-05, audit plan step 2): the static
         * entry for callers without a service reference — the Home
         * one-tap button, typed chat and voice ack call this. Delegates
         * to the live instance's verified seam, or false when the service
         * is not running (a null instance is "not running", never a
         * reason to claim the pointer showed). Callers that already hold
         * the instance call the instance method directly; both carry the
         * same contract and the same signature.
         */
        fun showPointerNow(): Boolean = instance?.showPointerNow() ?: false

        /**
         * Pointer failure fix (2026-11-05): reason-carrying variant of
         * [showPointerNow]. Delegates to the live instance's verified
         * seam, or NO_HOST when the service is not running — there is
         * nothing to run the seam against, and the caller must not
         * claim the pointer showed. Callers that already hold the
         * instance call the instance method directly; both carry the
         * same contract.
         */
        fun showPointerNowDetailed(): ShowPointerResult =
            instance?.showPointerNowDetailed()
                ?: run {
                    android.util.Log.w(
                        "LoomPointer",
                        "showPointerNowDetailed: no overlay service instance (NO_HOST)",
                    )
                    ShowPointerResult(shown = false, failure = ShowPointerFailure.NO_HOST)
                }

        /**
         * Pointer-drive fix (2026-11-06): the live overlay's gated
         * renderer, for plan runs that arrive in-app (typed chat /
         * in-app voice) with no renderer of their own. The overlay
         * service owns the only window the pointer can draw in — no
         * second overlay is ever drawn from the app process. Returns
         * null when the service is not running (a null instance is
         * "not running", never a reason to claim the pointer showed);
         * the caller then starts the service first or runs pointer-
         * less and names the gap honestly. Acquisition marshals onto
         * the main thread inside ensureGatedRenderer, so this is safe
         * to call from a background thread.
         */
        fun liveRenderer(): GuidanceRendererContract? = instance?.liveGatedRenderer()

        /**
         * Best-effort onDestroy down-marker for the §4.3 step-11
         * verification gate. A HINT ONLY — never proof of stop; the
         * independent liveness probe is the verdict (closure decision 6).
         */
        @Volatile
        var destroyedAtMarker: Long = 0L
            private set

        fun start(context: Context) {
            setRunningFlag(context, true)
            val i = Intent(context, LoomOverlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            setRunningFlag(context, false)
            context.startService(
                Intent(context, LoomOverlayService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Re-read the active mode and rebind (called after in-app switches). */
        fun refresh(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, LoomOverlayService::class.java).setAction(ACTION_REFRESH),
            )
        }

        /**
         * Device-evidence fix (2026-11-05, item 7): closes the
         * fire-and-forget gap in [showAuthCard]. The companion
         * `startService` intent is async, so the host used to return
         * `true` before the card was ever attached — if the service
         * died in between, the pending parked with no visible card
         * until its 90 s expiry. The instance path now records the
         * real outcome (card on screen OR safely queued — the queue
         * guarantees a queued card becomes answerable) keyed by
         * pending id, and [authCardShown] lets the host verify it.
         * Entries are consumed on read, so nothing accumulates.
         */
        private val authCardResults = ConcurrentHashMap<String, Boolean>()

        /**
         * The recorded attach outcome for [pendingId]: true = card on
         * screen or safely queued, false = attach failed (the service
         * cancels that pending honestly), null = the service has not
         * handled the intent yet. Consumes the entry on read.
         */
        fun authCardShown(pendingId: String): Boolean? =
            authCardResults.remove(pendingId)

        /**
         * Phase 6 — show/hide the AuthGate confirmation card from outside.
         * No-ops when the overlay service is not running (the gate then
         * falls back to the notification path).
         *
         * Master §8: [showAuthCard] carries the card's "always allow"
         * opt-in label ([grantOffer], null when the gate did not offer
         * one). [hideAuthCard] hides one pending's card ([pendingId]) —
         * a stale id is a no-op, so a late hide can never kill a live
         * card — or every card at once ([clearAll], teardown only).
         *
         * The call itself is fire-and-forget (startService); the caller
         * must verify the outcome via [authCardShown] instead of
         * assuming success — see [authCardResults].
         */
        fun showAuthCard(
            context: Context,
            pendingId: String,
            title: String,
            detail: String,
            grantOffer: String? = null,
        ) {
            if (!isRunning) return
            val i = Intent(context, LoomOverlayService::class.java).setAction(ACTION_SHOW_AUTH)
                .putExtra(EXTRA_AUTH_ID, pendingId)
                .putExtra(EXTRA_AUTH_TITLE, title)
                .putExtra(EXTRA_AUTH_DETAIL, detail)
                .putExtra(EXTRA_AUTH_GRANT_OFFER, grantOffer)
            context.startService(i)
        }

        fun hideAuthCard(context: Context, pendingId: String? = null, clearAll: Boolean = false) {
            if (!isRunning) return
            val i = Intent(context, LoomOverlayService::class.java).setAction(ACTION_HIDE_AUTH)
                .putExtra(EXTRA_AUTH_ID, pendingId)
                .putExtra(EXTRA_AUTH_CLEAR_ALL, clearAll)
            context.startService(i)
        }

        /**
         * Persisted running flag so the in-app Modes screen can show state
         * without importing this service (keeps module deps acyclic).
         */
        internal fun setRunningFlag(context: Context, running: Boolean) {
            context.getSharedPreferences("loom_modes", Context.MODE_PRIVATE)
                .edit().putBoolean("overlay_running", running).apply()
        }
        // Stage 2A.1: the old companion applyMode (persist → refresh/start →
        // vacuous prefs read-back) is deleted. Every entry point now calls
        // ModeController.applyMode — the single authoritative applier with
        // live bound-state verification. No dead code left behind.
    }

    private lateinit var wm: WindowManager
    private var characterView: CharacterOverlayView? = null
    private var orbView: FloatingOrbView? = null
    private var edgeView: EdgeLightView? = null
    private var panelView: ModePanelView? = null
    private var authCardView: AuthConfirmationCardView? = null
    /** Master §8 — ordering for parked confirmation cards (queue, not clobber). */
    private val authCardQueue = AuthCardQueue()

    private var activeMode: AssistantMode? = null
    private var guidance: GuidanceSession? = null
    /** The query of the live guidance session — the demo plan builds from it. */
    private var lastGuideQuery: String = ""

    /**
     * MASTER F6a/F6b — provenance for the next layer-mediated
     * guidance/demo session. The interaction layer carries only
     * strings (query/goal), so the voice session notes the origin on
     * the host immediately before the layer call; the host consumes
     * it when the posted session start runs, then resets to MODEL
     * (safe: always a card, no grant). Narrow race: the layer
     * callback is posted to the main handler, so a second voice turn
     * could overwrite the pending origin before consumption — the
     * consequence is bounded to a grant offer on an out-of-scope
     * tap card, never a dispatch.
     */
    @Volatile
    private var pendingGuideOrigin: RequestOrigin = RequestOrigin.MODEL
    @Volatile
    private var pendingDemoOrigin: RequestOrigin = RequestOrigin.MODEL

    // ---------- Stage 2C (F): interaction layer + guidance wiring ----------
    //
    // The service hosts the single InteractionLayer (interrupt-first,
    // single AuthGate dispatch path). Guidance visuals render through the
    // trusted TYPE_ACCESSIBILITY_OVERLAY host owned by
    // LoomAccessibilityService (2B D2) — the old full-screen
    // TYPE_APPLICATION_OVERLAY pointer window is retired.

    /** The universal interaction layer; null until onCreate builds it. */
    private var interaction: InteractionLayer? = null

    /** Per-mode beam anchors (F1); the old currentAnchor() jump is gone. */
    private var anchorResolver: AnchorResolver? = null

    /** The mode the beam anchor currently follows. */
    private var guidanceMode: GuidanceMode = GuidanceMode.ORB

    /** The guidance view, attached to the trusted overlay host when live. */
    private var guidanceRenderer: GuidanceRenderer? = null

    /**
     * Device-evidence fix (2026-11-05, pointer H1): the host instance
     * the cached renderer was attached to. A rebind creates a NEW
     * LoomAccessibilityService — the old renderer's WindowManager is
     * dead, so the cache must be dropped, never reused.
     */
    private var guidanceRendererHost: LoomAccessibilityService? = null

    /**
     * Master §6 — the ONE shared execution-feedback sink: the renderer
     * (target/pointer), the interaction layer (intended action/sequence)
     * and the ActionQueue (per-step results) all write into this instance,
     * and "Show me what you're doing" reads it. Created once; never null.
     */
    private val executionFeedback = ExecutionFeedback()

    /** Policy gate in front of the renderer (2B D3a, preserved). */
    private var gatedRenderer: GuidanceRendererContract? = null

    /** The single gated demonstration-step runner. */
    private var actionQueue: ActionQueue? = null

    /** The separate touchable stop window (§5.2 P3); shown only mid-sequence. */
    private var stopWindow: StopWindow? = null

    /** Last verified mode-change time — the F4 transition gate reads it. */
    private var lastModeChangeMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val busListener: (LoomStateEvent) -> Unit = { event ->
        if (event is LoomStateEvent.ModeChanged) {
            val mode = try {
                AssistantMode.valueOf(event.mode)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (mode != null) bindMode(mode)
        }
        if (event is LoomStateEvent.Listening ||
            event is LoomStateEvent.Thinking ||
            event is LoomStateEvent.Speaking
        ) {
            orbView?.orbState = when (event) {
                is LoomStateEvent.Listening -> FloatingOrbView.OrbState.LISTENING
                is LoomStateEvent.Thinking -> FloatingOrbView.OrbState.THINKING
                else -> FloatingOrbView.OrbState.SPEAKING
            }
            // Correction pass: the character consumes the same authoritative
            // voice state as the orb (previously it received nothing).
            characterView?.voiceState = when (event) {
                is LoomStateEvent.Listening -> CharacterOverlayView.VoiceVisualState.LISTENING
                is LoomStateEvent.Thinking -> CharacterOverlayView.VoiceVisualState.THINKING
                else -> CharacterOverlayView.VoiceVisualState.SPEAKING
            }
        }
        if (event is LoomStateEvent.Idle || event is LoomStateEvent.Completed) {
            orbView?.orbState = FloatingOrbView.OrbState.IDLE
            characterView?.voiceState = CharacterOverlayView.VoiceVisualState.IDLE
        }
        // Stage 2A: the floating orb must never stick in an active state.
        // Errors return it to IDLE; pauses show the PAUSED visual.
        if (event is LoomStateEvent.Error) {
            orbView?.orbState = FloatingOrbView.OrbState.IDLE
            characterView?.voiceState = CharacterOverlayView.VoiceVisualState.ERROR
        }
        if (event is LoomStateEvent.Paused) {
            orbView?.orbState = FloatingOrbView.OrbState.PAUSED
            characterView?.voiceState = CharacterOverlayView.VoiceVisualState.PAUSED
        }
        // Stage 2C (F): a halted sequence hides the stop window (the halt
        // path may originate outside this service — voice, toggle revoke).
        if (event is LoomStateEvent.Interrupted) {
            syncStopWindow()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // MASTER Wave 4 (F10): per-section guards — this method had
        // ZERO try/catch across ~70 lines (lines 463–530), the
        // strongest hypothesis for the "malfunctioning" helper death
        // in the video: one failing section (a wiring install, the
        // interaction-layer build, a mode hook) killed the whole
        // service create. Each section is now guarded separately and
        // its failure recorded in HelperStartupRecord; the service
        // keeps coming up degraded (voice + chat still work when the
        // pointer wiring fails; the pointer still works when the
        // message wiring fails) instead of dying outright. Device
        // Control surfaces the failed sections.
        runStartupSection("window-manager") {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            ensureChannel()
        }
        runStartupSection("mode-backend") {
            // Stage 2A.1 (D1): register the live backend so ModeController can
            // verify switches against bound state instead of prefs.
            ModeController.registerBackend(modeBackend)
        }
        runStartupSection("assistant-mover") {
            // Master §2 (item 5): register the AssistantMover backend (Worker
            // 5's AssistantMoverBackend) so ASSISTANT_MOVE has a live overlay
            // to move — the providers are lambdas because the attached view
            // changes on mode switch. Until registered, every move fails
            // honestly ("assistant is not attached").
            AssistantMover.register(
                AssistantMoverBackend(
                    context = this,
                    windowManager = wm,
                    viewProvider = { characterView ?: orbView },
                    paramsProvider = {
                        (characterView ?: orbView)?.tag as? WindowManager.LayoutParams
                    },
                    overlayIdProvider = {
                        when {
                            characterView != null ->
                                FloatingPositionStore.OverlayId.CHARACTER
                            orbView != null -> FloatingPositionStore.OverlayId.ORB
                            else -> null
                        }
                    },
                ),
            )
        }
        runStartupSection("search-wiring") {
            // Master §2 (item 4): install the search wiring once at startup.
            // install() returns false until the accessibility service is
            // running — onSearchRequested retries it then.
            // POINTER CHOREOGRAPHY FOR SEARCH/TYPE (2026-11-05): the search
            // sequence gets the live gated renderer, so its taps park the
            // pointer, typing narrates "Typing…", and submit narrates
            // "Searching…" — the pointer is seen doing the search.
            SearchExecutorWiring.install(this) { ensureGatedRenderer() }
        }
        runStartupSection("message-wiring") {
            // Dictated messaging + calls (2026-11-05, fix-plan item 12):
            // install the message/call wiring once at startup; the host
            // overrides below retry it, same as search.
            MessageExecutorWiring.install(this)
        }
        runStartupSection("interaction-layer") {
            // Stage 2C (F): build the interaction layer first — voice, halt
            // paths and guidance all run through it.
            interaction = buildInteractionLayer().also { InteractionLayerRegistry.register(it) }
            // Master §6: the layer's intended-action/sequence reporting goes
            // to the shared feedback sink (read by "Show me what you're doing").
            interaction?.feedback = executionFeedback
        }
        runStartupSection("mode-change-hook") {
            // Stage 2C (F2): post-verification anchor hook (notification only —
            // the single authoritative applier in ModeController is untouched).
            ModeController.onVerifiedModeChange = { mode -> refreshGuidanceForMode(mode) }
        }
        isRunning = true
        runStartupSection("event-bus") {
            LoomEventBus.subscribe(busListener)
        }
        if (HelperStartupRecord.failedSections().isNotEmpty()) {
            android.util.Log.w(
                "LoomOverlay",
                "onCreate sections failed: ${HelperStartupRecord.failedSections()}",
            )
        }
    }

    override fun onDestroy() {
        // Stage 2C (§4.3 step 11): best-effort down-marker for the
        // verification gate — a hint only, never proof of stop.
        destroyedAtMarker = SystemClock.elapsedRealtime()
        ModeController.onVerifiedModeChange = null
        ModeController.registerBackend(null)
        // Master §2 (item 5): un-register the mover backend — a move
        // after teardown must fail honestly, never touch a dead view.
        AssistantMover.register(null)
        InteractionLayerRegistry.register(null)
        teardownInteraction()
        VoiceSessionOwner.releaseFloating()
        try {
            floatingVoice?.end()
        } catch (_: Exception) {
        }
        floatingVoice = null
        LoomEventBus.unsubscribe(busListener)
        removeAllViews()
        isRunning = false
        instance = null
        super.onDestroy()
    }

    /**
     * Stage 2A.1 (D1): the live ModeBackend behind ModeController — the
     * honest read-back that currentMode() always was (it had zero callers).
     */
    private val modeBackend = object : ModeBackend {
        override fun hasOverlayPermission(): Boolean =
            OverlayPermission.granted(this@LoomOverlayService)

        override fun requestServiceStart(context: Context): Boolean = try {
            // The service is alive whenever this backend is registered; a
            // defensive re-start covers the torn-down edge.
            LoomOverlayService.start(context)
            true
        } catch (_: Exception) {
            false
        }

        override fun boundMode(): AssistantMode? = currentMode()

        override fun isViewAttached(mode: AssistantMode): Boolean = when (mode) {
            AssistantMode.CHARACTER -> characterView != null
            AssistantMode.ORB -> orbView != null
            AssistantMode.INVISIBLE -> edgeView != null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(notificationVariant))
        if (intent == null) {
            // Automatic recovery (Nov 6): a restart the system issued
            // on its own (process death, START_STICKY) must never undo
            // an explicit stop. A marker means David stopped LOOM
            // himself — stay stopped. A manual start always carries an
            // action, so it never lands here.
            if (AutomaticRecoveryStore.get(this).readIntentionalStop() != null) {
                auditLifecycle("system restart refused — LOOM was stopped by your choice, not by the phone")
                stopSelf()
                return START_NOT_STICKY
            }
            bindMode(ModeController.getMode(this))
            return START_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                // Automatic recovery (Nov 6): an explicit STOP is
                // intentional — written before the stop, so the phone
                // never brings LOOM back on its own after it.
                try {
                    AutomaticRecoveryStore.get(this).writeIntentionalStop(
                        IntentionalStop(IntentionalStopCommand.STOP, System.currentTimeMillis()),
                    )
                } catch (_: Exception) {
                }
                stopSelf()
                return START_NOT_STICKY
            }
            // Stage 2C (§4.3): ordered 12-step shutdown under the
            // application-scoped controller. Runs on a worker thread; the
            // service stops itself LAST (step 10) and verification is
            // independent of the dying service (step 11).
            ACTION_EXECUTE_SHUTDOWN -> {
                if (!shutdownRunning.compareAndSet(false, true)) {
                    auditLifecycle("EXECUTE_SHUTDOWN ignored — a shutdown run is already in progress")
                    return START_STICKY
                }
                if (LifecycleController.currentState() != LifecycleState.ACTIVE) {
                    shutdownRunning.set(false)
                    auditLifecycle(
                        "EXECUTE_SHUTDOWN refused — lifecycle is " +
                            "${LifecycleController.currentState()}, not ACTIVE",
                    )
                    return START_STICKY
                }
                Thread({ runFullShutdown("in-app-confirmed") }, "loom-shutdown").start()
                return START_STICKY
            }
            // Stage 2C (§4.2-D): SHOW ASSISTANT — restores the hidden
            // floating presentation ONLY. Guarded: requires ACTIVE; never
            // starts the mic, never transitions the lifecycle.
            ACTION_SHOW_ASSISTANT -> {
                showFloatingViews()
                return START_STICKY
            }
            ACTION_SHOW_AUTH -> {
                val pendingId = intent.getStringExtra(EXTRA_AUTH_ID) ?: ""
                // Item 7: record the REAL attach outcome so the host can
                // verify it (fire-and-forget startService proves nothing).
                authCardResults[pendingId] = showAuthCard(
                    pendingId,
                    intent.getStringExtra(EXTRA_AUTH_TITLE) ?: "Approval needed",
                    intent.getStringExtra(EXTRA_AUTH_DETAIL) ?: "",
                    intent.getStringExtra(EXTRA_AUTH_GRANT_OFFER),
                )
                return START_STICKY
            }
            ACTION_HIDE_AUTH -> {
                hideAuthCard(
                    intent.getStringExtra(EXTRA_AUTH_ID),
                    intent.getBooleanExtra(EXTRA_AUTH_CLEAR_ALL, false),
                )
                return START_STICKY
            }
            // Stage 2C (§5.2 P1): notification Stop action → halt the live
            // sequence. Works even if the pointer overlay, mode views and
            // voice pipeline are all dead — the notification is owned by
            // the OS. No-op (honest) when nothing is running.
            LoomIntents.ACTION_HALT_SEQUENCE -> {
                // AOIE Phase 5: the notification stop also stops a
                // plan run (via the shared registry), not only a
                // layer sequence.
                com.loom.assistant.core.common.PlanStopRegistry.requestStop()
                val layer = interaction
                if (layer != null && layer.isSequenceActive) {
                    layer.halt(HaltSource.NOTIFICATION)
                    floatingVoice?.speakNow("Stopped.")
                } else if (com.loom.assistant.core.common.PlanRunState.isPlanRunning) {
                    floatingVoice?.speakNow("Stopped.")
                }
                syncStopWindow()
                return START_STICKY
            }
            else -> {
                // Automatic recovery (Nov 6): a manual start after SHUT
                // DOWN / DEACTIVATE is refused — coming back is
                // launcher-only (§4.4). After a plain STOP the lifecycle
                // is still ACTIVE, so a manual start is allowed: STOP
                // blocks automatic recovery only, never David himself.
                val marker = AutomaticRecoveryStore.get(this).readIntentionalStop()
                if (marker != null && LifecycleController.currentState() != LifecycleState.ACTIVE) {
                    auditLifecycle("start refused — LOOM was stopped by your choice; open the app to turn it back on")
                    stopSelf()
                } else {
                    bindMode(ModeController.getMode(this))
                }
            }
        }
        return START_STICKY
    }

    // ---------- view binding ----------

    private fun bindMode(mode: AssistantMode) {
        if (!OverlayPermission.granted(this)) {
            // Graceful degradation: no permission → no views, service idles.
            removeAllViews()
            activeMode = null
            return
        }
        // First-start fix (2026-11-05): kind-exact skip via
        // FloatingAttachPolicy. The old check skipped whenever the
        // requested mode equalled activeMode AND *either* floating view
        // was non-null — kind-blind, so a stale/wrong-kind attached view
        // under the right mode name was kept and reported as bound while
        // the requested surface was never (re)bound. A skip now happens
        // only when the requested kind is active AND that same kind is
        // the attached kind. Fresh state (activeMode null, no view) always
        // binds — the very first attach can never be skipped away.
        // SHOW/HIDE restore is unchanged: hidden state has no attached
        // view, so shouldBind is true and the view is re-bound.
        val requestedKind = attachKindOf(mode)
        val activeKind = activeMode?.let { attachKindOf(it) }
        val attachedKind = when {
            characterView != null -> FloatingAttachKind.CHARACTER
            orbView != null -> FloatingAttachKind.ORB
            edgeView != null -> FloatingAttachKind.EDGE_LIGHT
            else -> null
        }
        if (!FloatingAttachPolicy.shouldBind(requestedKind, activeKind, attachedKind)) {
            return
        }
        removeModeViews()
        // Stage 2A.1 (D1/R1): activeMode advances only AFTER a successful
        // view add — a failed add leaves the honest null, never a fake claim.
        // INVARIANT: bindMode never tears down floatingVoice — the floating
        // voice session survives mode switches (it is the only exit from
        // Invisible mode while floating; see D7).
        var attached = when (mode) {
            AssistantMode.CHARACTER -> {
                addCharacter()
                characterView != null
            }
            AssistantMode.ORB -> {
                addOrb()
                orbView != null
            }
            // Correction pass: the edge light is EXCLUSIVE to Invisible mode.
            // It was previously added unconditionally and leaked into Orb and
            // Character modes with no code path ever removing it.
            AssistantMode.INVISIBLE -> {
                addEdgeLight()
                edgeView != null
            }
        }
        // Post-attach verification (first-start fix): initial attach and
        // switch attach run the SAME check through FloatingAttachPolicy.
        // A view missing a required gesture gets its surface's single
        // binder re-applied; if it still fails, the bind honestly fails
        // and no handler-less view is left up claiming to be bound.
        // (INVISIBLE needs no gestures, so its verification always passes;
        // its skip behavior is the one change: an already-up edge light is
        // now kept instead of removed + re-added, same end state.)
        if (attached &&
            !FloatingAttachPolicy.bindingComplete(requestedKind, presentGestures(mode))
        ) {
            rebindGestures(mode)
            attached = FloatingAttachPolicy.bindingComplete(requestedKind, presentGestures(mode))
        }
        if (!attached) {
            removeModeViews()
        }
        activeMode = if (attached) mode else null
        // Stage 2C (F2): the verified transition refreshes the guidance
        // anchor path (mode → anchor mapping, animated anchor event) and
        // re-layers the guidance window topmost by contract. The
        // ModeController post-verification hook calls the same method;
        // both are idempotent (AnchorResolver emits an event only when the
        // anchor actually moved).
        if (attached) refreshGuidanceForMode(mode)
    }

    /**
     * First-start fix: the FloatingAttachPolicy kind for a mode.
     * core:common cannot import presentation:modes (deps stay acyclic),
     * so this mapping lives here, next to bindMode.
     */
    private fun attachKindOf(mode: AssistantMode): FloatingAttachKind = when (mode) {
        AssistantMode.CHARACTER -> FloatingAttachKind.CHARACTER
        AssistantMode.ORB -> FloatingAttachKind.ORB
        AssistantMode.INVISIBLE -> FloatingAttachKind.EDGE_LIGHT
    }

    /** Gestures currently present on the attached view for [mode]. */
    private fun presentGestures(mode: AssistantMode): Set<FloatingGesture> {
        val present = mutableSetOf<FloatingGesture>()
        when (mode) {
            AssistantMode.CHARACTER -> characterView?.let { v ->
                if (v.onTap != null) present.add(FloatingGesture.TAP)
                if (v.onLongPress != null) present.add(FloatingGesture.LONG_PRESS)
                if (v.onDrag != null) present.add(FloatingGesture.DRAG)
            }
            AssistantMode.ORB -> orbView?.let { v ->
                if (v.onTap != null) present.add(FloatingGesture.TAP)
                if (v.onDrag != null) present.add(FloatingGesture.DRAG)
            }
            AssistantMode.INVISIBLE -> Unit
        }
        return present
    }

    /**
     * Re-apply a surface's single binder onto its attached view, using
     * the live window params kept in the view tag (set by addCharacter /
     * addOrb). A missing or mistyped tag means the view cannot be
     * re-bound, so verification fails honestly instead of guessing.
     */
    private fun rebindGestures(mode: AssistantMode) {
        when (mode) {
            AssistantMode.CHARACTER -> {
                val view = characterView ?: return
                val params = view.tag as? WindowManager.LayoutParams ?: return
                bindCharacterGestures(view, params)
            }
            AssistantMode.ORB -> {
                val view = orbView ?: return
                val params = view.tag as? WindowManager.LayoutParams ?: return
                bindOrbGestures(view, params)
            }
            AssistantMode.INVISIBLE -> Unit
        }
    }

    // Stage 2C: the old full-screen TYPE_APPLICATION_OVERLAY pointer window
    // is RETIRED. Guidance renders through the trusted
    // TYPE_ACCESSIBILITY_OVERLAY host owned by LoomAccessibilityService
    // (2B D2); topmost-by-contract re-layering lives in
    // relayerGuidanceTopmost(). The P2 re-layering concept is preserved,
    // the application-overlay window is gone.

    private fun floatingParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    // Stage 2B (P3): drag positions persist once the drag settles. The views
    // expose no drag-end callback, so a save is (re)scheduled 600ms after
    // the last drag delta — the write lands on drag end, not per MOVE event.
    private val dragSaveHandler = Handler(Looper.getMainLooper())
    private val pendingDragSaves =
        mutableMapOf<FloatingPositionStore.OverlayId, Runnable>()

    private fun savePositionDebounced(
        id: FloatingPositionStore.OverlayId,
        params: WindowManager.LayoutParams,
    ) {
        pendingDragSaves[id]?.let { dragSaveHandler.removeCallbacks(it) }
        val save = Runnable {
            FloatingPositionStore.save(this, id, params.x, params.y)
            pendingDragSaves.remove(id)
        }
        pendingDragSaves[id] = save
        dragSaveHandler.postDelayed(save, 600)
    }

    private fun addCharacter() {
        val size = dp(56)
        val view = CharacterOverlayView(this)
        // Stage 2B (P3): initial coords are stored-then-clamped, never
        // hard-coded. Character and orb keep independent positions.
        val start = FloatingPositionStore.restore(
            this, wm, FloatingPositionStore.OverlayId.CHARACTER,
            size, size + dp(8), dp(280), dp(120),
        )
        val params = floatingParams(size, size + dp(8), start.x, start.y)
        // THE single character-gesture binder (see bindCharacterGestures):
        // initial attach and switch attach assign handlers through this
        // one function, and bindMode's post-attach verification re-applies
        // it — no other site assigns character handlers.
        bindCharacterGestures(view, params)
        view.tag = params
        characterView = view
        // Correction pass: a failed add must not leave a swallowed bus
        // exception with the old mode's views still up ("mode switch failed").
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            characterView = null
            android.util.Log.w("LoomOverlay", "addCharacter failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * THE single character-gesture binder. The only place character
     * handlers are assigned: addCharacter calls it before addView, and
     * bindMode's post-attach verification re-applies it if a gesture is
     * missing. Contract preserved exactly: tap = floating voice (via
     * FloatingTapRouter, exactly like the orb), long-press = mode panel,
     * drag = move + debounced position persist.
     */
    private fun bindCharacterGestures(
        view: CharacterOverlayView,
        params: WindowManager.LayoutParams,
    ) {
        // Character-tap fix (device-proven 2026-11-05): tap toggles the
        // floating voice session EXACTLY like the orb — routed through
        // FloatingTapRouter, not hardcoded, so the router is the tested
        // seam. The mode panel survives on long-press (see below).
        view.onTap = {
            when (FloatingTapRouter.tapAction(FloatingSurface.CHARACTER)) {
                TapAction.TOGGLE_FLOATING_VOICE -> toggleFloatingVoice()
                TapAction.OPEN_PANEL -> togglePanel(params)
                TapAction.NONE -> Unit
            }
        }
        view.onLongPress = {
            when (FloatingTapRouter.longPressAction(FloatingSurface.CHARACTER)) {
                TapAction.OPEN_PANEL -> togglePanel(params)
                TapAction.TOGGLE_FLOATING_VOICE -> toggleFloatingVoice()
                TapAction.NONE -> Unit
            }
        }
        view.onDrag = { dx, dy ->
            params.x += dx.toInt()
            params.y += dy.toInt()
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
            savePositionDebounced(FloatingPositionStore.OverlayId.CHARACTER, params)
        }
    }

    private fun addOrb() {
        val size = dp(44)
        val view = FloatingOrbView(this)
        // Stage 2B (P3): stored-then-clamped, independent from the character.
        val start = FloatingPositionStore.restore(
            this, wm, FloatingPositionStore.OverlayId.ORB,
            size, size, dp(320), dp(400),
        )
        val params = floatingParams(size, size, start.x, start.y)
        // THE single orb-gesture binder (see bindOrbGestures): initial
        // attach and switch attach assign handlers through this one
        // function, and bindMode's post-attach verification re-applies it.
        // Orb behavior unchanged.
        bindOrbGestures(view, params)
        view.tag = params
        orbView = view
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            orbView = null
            android.util.Log.w("LoomOverlay", "addOrb failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * THE single orb-gesture binder. The only place orb handlers are
     * assigned: addOrb calls it before addView, and bindMode's post-attach
     * verification re-applies it if a gesture is missing. Contract
     * preserved exactly: one tap toggles the floating voice session
     * directly instead of opening the main app (Part 4); drag moves +
     * debounced position persist.
     */
    private fun bindOrbGestures(
        view: FloatingOrbView,
        params: WindowManager.LayoutParams,
    ) {
        view.onTap = { toggleFloatingVoice() }
        view.onDrag = { dx, dy ->
            params.x += dx.toInt()
            params.y += dy.toInt()
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
            savePositionDebounced(FloatingPositionStore.OverlayId.ORB, params)
        }
    }

    private fun addEdgeLight() {
        if (edgeView != null) return
        val view = EdgeLightView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        edgeView = view
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            edgeView = null
            android.util.Log.w("LoomOverlay", "addEdgeLight failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Correction pass: the edge light is exclusive to Invisible mode — this
     * is the removal path that never existed (previously only service stop
     * could remove it, so it leaked into Orb/Character modes).
     */
    private fun removeEdgeLight() {
        edgeView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        edgeView = null
    }

    /** Small tap panel anchored under the character. */
    private fun togglePanel(anchor: WindowManager.LayoutParams) {
        panelView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
            panelView = null
            return
        }
        val panel = ModePanelView(this)
        val pw = dp(220)
        val ph = dp(320)
        // Stage 2B (P3): the panel keeps its anchor-under-character logic —
        // the live anchor wins over any stale stored position; the store
        // only clamps the anchored coords to the current metrics (so an
        // orientation change re-clamps instead of pushing it off-screen),
        // and the shown position is persisted on each show.
        val anchored = FloatingPositionStore.Position(
            anchor.x - pw + dp(56), anchor.y + dp(70),
        )
        val clamped = FloatingPositionStore.clampToCurrent(wm, anchored, pw, ph)
        val params = floatingParams(pw, ph, clamped.x, clamped.y)
        FloatingPositionStore.save(
            this, FloatingPositionStore.OverlayId.PANEL, params.x, params.y,
        )
        // Panel needs touch: replace NOT_FOCUSABLE-only flags with touchable modal-ish.
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        panel.listener = object : ModePanelView.Listener {
            override fun onVoiceChat() {
                dismissPanel()
                // Correction pass: one-tap floating voice — no app navigation.
                toggleFloatingVoice()
            }

            override fun onOpenApp() {
                dismissPanel()
                openApp()
            }

            override fun onSwitchMode(mode: AssistantMode) {
                dismissPanel()
                // Stage 2A.1 (D1): the single authoritative applier. The panel
                // has no surface for the async ack — the modes screen and the
                // voice acks report it.
                ModeController.applyMode(this@LoomOverlayService, mode) { _ -> }
            }

            override fun onClose() = dismissPanel()
        }
        panelView = panel
        // First-start fix (hypothesis 1 close-out): togglePanel was the
        // ONLY addView in this service without a try/catch — a failed
        // panel add propagated out of the touch handler instead of being
        // contained like addCharacter/addOrb/addEdgeLight. Contain it and
        // null the field so a later long-press retries cleanly.
        try {
            wm.addView(panel, params)
        } catch (e: Exception) {
            panelView = null
            android.util.Log.w("LoomOverlay", "togglePanel failed: ${e.javaClass.simpleName}")
        }
    }

    private fun dismissPanel() {
        panelView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
            panelView = null
        }
    }

    // ---------- Phase 6: AuthGate confirmation card (arch §2.14) ----------

    /**
     * In-overlay confirmation card: the primary CONFIRM presentation.
     * Centered, touchable card; touches outside pass through
     * (FLAG_NOT_TOUCH_MODAL). Approve/Deny resolve the pending gate.
     *
     * Master §8 — card clobbering, fixed: the old code called
     * hideAuthCard() first, which only removed the view — the previous
     * pending stayed parked, unanswerable until its 90 s expiry. Cards
     * now go through [authCardQueue]: a card becomes current when
     * nothing is shown, otherwise it queues behind the current one, and
     * every parked pending stays answerable until resolved or expired.
     * Returns true when the card is on screen or safely queued.
     */
    private fun showAuthCard(
        pendingId: String,
        title: String,
        detail: String,
        grantOffer: String? = null,
    ): Boolean {
        val card = AuthCardQueue.Card(pendingId, title, detail, grantOffer)
        return when (authCardQueue.show(card)) {
            AuthCardQueue.ShowResult.QUEUED -> true
            AuthCardQueue.ShowResult.SHOWN -> attachCurrentAuthCard()
        }
    }

    /**
     * Attach whatever card the queue currently holds. On attach failure
     * (permission revoked, window manager refusal) the pending is
     * cancelled honestly at once via [AuthGate.cancelPending] — never
     * parked unanswerable — and the next queued card is tried. Returns
     * true iff a card ended up on screen.
     *
     * Note on the cancel's knock-on hide: cancelPending() asks the host
     * to hide this id, which arrives later as an async ACTION_HIDE_AUTH
     * intent; by then the queue has already moved on, so that hide is
     * a stale no-op ([AuthCardQueue.hide] never touches a live card).
     */
    private fun attachCurrentAuthCard(): Boolean {
        while (true) {
            val card = authCardQueue.current() ?: return false
            if (attachAuthCard(card)) return true
            authCardQueue.hide(card.pendingId)
            try {
                AuthGate.getOrNull()?.cancelPending(card.pendingId)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Attach one card view. False when the overlay permission is gone
     * or the window manager refuses the view.
     */
    private fun attachAuthCard(card: AuthCardQueue.Card): Boolean {
        if (!OverlayPermission.granted(this)) return false
        val view = AuthConfirmationCardView(this)
        view.bind(card.title, card.detail, card.grantOffer)
        view.listener = object : AuthConfirmationCardView.Listener {
            override fun onApprove(alwaysAllow: Boolean) {
                val gate = AuthGate.getOrNull()
                if (gate != null) {
                    gate.resolve(card.pendingId, true, alwaysAllow)
                    // Resolve hides via the host (async intent); hide
                    // synchronously too so the card leaves at once.
                    hideAuthCard(card.pendingId)
                } else {
                    hideAuthCard(card.pendingId)
                }
            }

            override fun onDeny() {
                val gate = AuthGate.getOrNull()
                if (gate != null) {
                    gate.resolve(card.pendingId, false)
                    hideAuthCard(card.pendingId)
                } else {
                    hideAuthCard(card.pendingId)
                }
            }
        }
        val w = dp(300)
        // The "always allow" row needs room when the gate offered it.
        val h = dp(if (card.grantOffer != null) 270 else 230)
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // MASTER Wave 4 (F11 strengthening): the card used to be
        // Gravity.CENTER — dead centre, covering the very target
        // being acted on (the "Scroll for you?" card covered "My
        // Mummy", video 540s). The card now docks on the OPPOSITE
        // half from the pointer's current target (from
        // ExecutionFeedback, the same target the frame/pill name),
        // so David can still see the target while he decides. When
        // no target is known it docks at the bottom. The dispatcher
        // re-resolves the target immediately before dispatch anyway
        // (TOCTOU), so a target near the card is re-verified after
        // the card closes — that case is logged here.
        val target = try {
            executionFeedback.snapshot().target
        } catch (_: Exception) {
            null
        }
        val metrics = resources.displayMetrics
        val placement = com.loom.assistant.interaction.pointer.CardPlacement.decide(
            target = target?.bounds,
            screenWidthPx = metrics.widthPixels.toFloat(),
            screenHeightPx = metrics.heightPixels.toFloat(),
            cardHeightPx = h.toFloat(),
        )
        params.gravity = when (placement.edge) {
            com.loom.assistant.interaction.pointer.CardEdge.TOP ->
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            com.loom.assistant.interaction.pointer.CardEdge.BOTTOM ->
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        params.y = placement.marginPx
        if (placement.reverifyTargetAfterClose) {
            android.util.Log.i(
                "LoomAuthCard",
                "card placed near target \"${target?.label}\" — target is re-verified after the card closes",
            )
        }
        authCardView = view
        return try {
            wm.addView(view, params)
            true
        } catch (_: Exception) {
            authCardView = null
            false
        }
    }

    /**
     * Hide one pending's card — or every card at once for teardown.
     *
     * [forPendingId] null (legacy blanket hide) hides the current card
     * and advances the queue. A non-null id hides only that pending's
     * card: current → detach and advance; queued → drop from the queue,
     * current view untouched; unknown → no-op. [clearAll] (teardown
     * only: service dying, ordered shutdown already purged the gate)
     * drops the current view and the whole queue.
     */
    private fun hideAuthCard(forPendingId: String? = null, clearAll: Boolean = false) {
        if (clearAll) {
            detachAuthCardView()
            authCardQueue.clearAll()
            return
        }
        val wasCurrentId = authCardQueue.current()?.pendingId
        val targetId = forPendingId ?: wasCurrentId ?: return
        val next = authCardQueue.hide(targetId)
        if (targetId == wasCurrentId) {
            detachAuthCardView()
            if (next != null) attachCurrentAuthCard()
        }
    }

    /** Remove the visible card view without touching the queue. */
    private fun detachAuthCardView() {
        authCardView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        authCardView = null
    }

    private fun removeModeViews() {
        dismissPanel()
        characterView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        orbView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        characterView = null
        orbView = null
        // Correction pass: leaving a mode must also kill the edge light —
        // it is exclusive to Invisible mode and previously leaked.
        removeEdgeLight()
    }

    private fun removeAllViews() {
        removeModeViews()
        hideAuthCard(clearAll = true)
        // Stage 2B (P3): no position write may land after teardown.
        dragSaveHandler.removeCallbacksAndMessages(null)
        pendingDragSaves.clear()
        guidance?.end()
        guidance = null
    }

    /**
     * Stage 2C (F): tear down the interaction-layer wiring. Called from
     * onDestroy (after ModeController hooks are unregistered) and safe to
     * call when nothing was built.
     */
    private fun teardownInteraction() {
        hideStopWindow()
        val renderer = guidanceRenderer
        guidanceRenderer = null
        guidanceRendererHost = null
        gatedRenderer = null
        actionQueue = null
        anchorResolver = null
        if (renderer != null) {
            try {
                LoomAccessibilityService.instance?.removeGuidanceOverlayView(renderer)
            } catch (_: Exception) {
            }
        }
        interaction = null
    }

    // ---------- actions ----------

    private fun openVoiceChat() {
        val i = Intent().apply {
            setClassName(packageName, LoomIntents.MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LoomIntents.EXTRA_START_VOICE, true)
        }
        startActivity(i)
    }

    private fun openApp() {
        val i = Intent().apply {
            setClassName(packageName, LoomIntents.MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(i)
    }

    /** Read-back of the bound mode — voice commands verify against this. */
    fun currentMode(): AssistantMode? = activeMode

    // ---------- floating voice session (correction pass, Part 4) ----------
    //
    // One-tap floating activation: the service hosts a VoiceSessionManager over
    // the SAME engine + conversation store as the in-app screens — not a second
    // engine. Tapping the floating orb (or the panel's "Voice chat" row) starts
    // or ends the session directly, without opening MainActivity. "Open LOOM"
    // and the notification tap remain the separate, explicit app-opening
    // actions.
    //
    // Honest limits: a Service cannot request permissions — only check them.
    // First-ever floating activation without RECORD_AUDIO falls back to opening
    // the app (which can request); steady state is one-tap. No wake-word,
    // no always-on listening.

    private var floatingVoice: VoiceSessionManager? = null
    private val voicePipeline: VoicePipeline by lazy { DeviceVoicePipeline.get(this) }

    private fun toggleFloatingVoice() {
        val session = floatingVoice
        if (session != null && session.isActive) {
            // Orb no-speech fix (2026-11-10): after the terminal
            // no-speech message ("I didn't hear anything — tap the
            // orb to try again."), the session sits in ERROR — and
            // isActive includes ERROR — so one orb tap used to END
            // the session instead of retrying, even though the
            // message promises a retry. An ERROR tap now resumes
            // (resume() resets the retry bound and listens again);
            // any other active state still ends the session.
            if (session.state == VoiceSessionState.ERROR) {
                session.resume()
                return
            }
            session.end()
            floatingVoice = null
            VoiceSessionOwner.releaseFloating()
            return
        }
        if (VoiceSessionOwner.inAppActive) {
            // An in-app session owns the mic — never fight it for audio focus.
            openVoiceChat()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            openVoiceChat()
            return
        }
        // Stage 2C (F): inject the real interaction layer — the floating
        // session's guidance/demonstration/halt commands route through it.
        val mgr = VoiceSessionManager(
            this, voicePipeline, floatingVoiceHost(),
            floating = true,
            interactionLayer = interaction,
        )
        floatingVoice = mgr
        // Stage 2A.1 (D5) / Phase 2 (E.4): atomic ownership — claimFloating
        // succeeds only when the mic is free, closing the check-then-set
        // race. On failure an in-app session won the race: end the
        // just-created session and bail, never fight it for audio focus.
        if (!VoiceSessionOwner.claimFloating()) {
            mgr.end()
            floatingVoice = null
            return
        }
        mgr.start()
    }

    private fun floatingVoiceHost() = object : VoiceSessionManager.Host {
        private val models = ModelSelection(this@LoomOverlayService)
        private val store = ConversationStore.get(this@LoomOverlayService)
        private val engine: ResponseEngine by lazy {
            resolveEngine(this@LoomOverlayService, models) {
                store.getActive().messages.takeLast(8)
                    .map { LlmMessage(it.role, it.text) }
            }
        }

        override fun provideEngine(): ResponseEngine = engine
        override fun provideStore(): ConversationStore = store
        override fun historyWindow(): Int = 8
        override fun checkMicPermission(onResult: (Boolean) -> Unit) {
            // Pre-checked in toggleFloatingVoice(); re-check defensively.
            onResult(
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        override fun idlePrompt(): String = "Tap the orb to talk"
        override fun onSessionState(state: VoiceSessionState, statusText: String) {
            // Voice states already reach the floating orb/character through the
            // pipeline's own bus posts + busListener — no extra wiring here.
            if (state == VoiceSessionState.IDLE) {
                floatingVoice = null
                // Stage 2A.1 (D5) / Phase 2 (E.4): release mic ownership
                // symmetrically — atomic, so a stale callback can never clear
                // another session's live ownership.
                VoiceSessionOwner.releaseFloating()
            }
            // MASTER Wave 4 (F12): while the manager is thinking, the
            // status text now carries the heard transcript ("Thinking…
            // — heard: \"…\""), and the pill shows it — so a slow
            // answer is visible, never a silent "Thinking…" with no
            // trace (video failure 4: ~20s of Thinking with nothing
            // shown). Only the thinking state narrates here; plan
            // steps overwrite the pill with their own narration.
            if (state == VoiceSessionState.PROCESSING) {
                try {
                    ensureGatedRenderer()?.setStatusNarration(statusText)
                } catch (_: Exception) {
                }
            }
        }
        override fun onPartialText(text: String) {
            // MASTER Wave 4 (F12/F16): the live partial transcript,
            // shown in the pill as it arrives — David can see what
            // was caught while he is still speaking. The durable
            // record is the final heard transcript the manager stores
            // per turn; a partial is never stored as a command.
            try {
                ensureGatedRenderer()?.setStatusNarration("Heard: \"$text\"")
            } catch (_: Exception) {
            }
        }
        override fun onTurnFinished() {}
        override fun onCommandAction(cmd: VoiceCommand) {
            // Device-failure fix (2026-11-13, David's binding rule
            // Nov 13 ~03:36, overriding the old confirmation rule AND
            // the §4.3.0 confirmation design): floating "shut down
            // LOOM" executes the real 12-step shutdown IMMEDIATELY —
            // no question, no re-ask, no cancel path. A voice shutdown
            // command must never be cancellable by LOOM. The
            // confirmation protocol (the §4.3.0 protocol class +
            // the floating window chain) is DELETED, not bypassed:
            // its listening window was structurally unreliable (no
            // listening during speech, every STT error a Cancel,
            // stale callbacks unguarded) and produced exactly the
            // Nov 13 device failure (question → no-hear → cancel).
            when (cmd) {
                VoiceCommand.SHUTDOWN -> executeFloatingShutdownNow()
                VoiceCommand.HIDE_YOURSELF -> hideFloatingViews()
                VoiceCommand.SHOW_ASSISTANT -> showFloatingViews()
                VoiceCommand.DEACTIVATE_ASSISTANT_MODE -> deactivateAssistant()
                else -> Unit
            }
        }

        // Device-evidence fix (2026-11-05): the floating voice
        // session's launch path — same shared launcher as typed chat.
        // The outcome is spoken through the live floating session
        // (speakNow is a no-op unless a session is live, and this call
        // only happens inside one).
        // Master §8: the origin rides through to the gate.
        override fun onLaunchRequested(appName: String) {
            onLaunchRequested(appName, RequestOrigin.MODEL)
        }

        override fun onLaunchRequested(appName: String, origin: RequestOrigin) {
            LoomAppLauncher.launch(
                this@LoomOverlayService, appName,
                onResult = { result -> floatingVoice?.speakNow(result) },
                origin = origin,
            )
        }

        // MASTER F6a/F6b — the voice session notes the provenance of
        // the next layer-mediated guidance/demo session immediately
        // before the layer call (the layer carries only strings).
        // Consumed when the posted session start runs, then reset to
        // MODEL (see pendingGuideOrigin / pendingDemoOrigin).
        override fun noteGuideOrigin(origin: RequestOrigin) {
            pendingGuideOrigin = origin
        }

        override fun noteDemoOrigin(origin: RequestOrigin) {
            pendingDemoOrigin = origin
        }

        // Master §2 (items 3–4): the floating voice session's search
        // path. App-name resolution is OWNED by the executor, not
        // duplicated here: SearchExecutorWiring maps its AppResolver to
        // Worker 1's AppCatalogue.resolveByName(context, name) — exact
        // label wins, so "YouTube" never collapses to "YouTube Music"
        // and vice versa; partial matches surface as Ambiguous.
        // (Correction to the task's stated signature: resolveByName
        // takes (context, name), and Found carries (packageName, label)
        // — the task's resolveByName(name) / Found(label, packageName)
        // do not exist. This override calls the executor only.)
        // execute() BLOCKS on confirmation cards (up to 90s), so it runs
        // on a background thread; the ack is posted back to the main
        // thread and spoken through the live floating session. Every
        // outcome maps to an honest spoken ack; nothing is claimed
        // before the executor reports. Sensitive-app refusal lives in
        // the executor, not here. install() is retried here because the
        // accessibility service may have connected after onCreate.
        override fun onSearchRequested(
            appName: String,
            query: String,
            origin: RequestOrigin,
        ): Boolean {
            SearchExecutorWiring.install(this@LoomOverlayService) { ensureGatedRenderer() }
            Thread {
                val outcome = try {
                    // MASTER Wave 3 (F8) — the standalone floating-host
                    // search stops at the verified results list
                    // (SHOW_RESULTS): it used to open the first result
                    // unasked (the sequence default), opening a video
                    // David never asked to open.
                    SearchExecutor.execute(
                        appName, query, origin,
                        goal = com.loom.assistant.execution.search.SearchGoal.SHOW_RESULTS,
                    )
                } catch (e: Exception) {
                    SearchOutcome.Failed("Search failed: ${e.message ?: "unknown error"}")
                }
                // Device-evidence fix (2026-11-05, item 1): the outcome
                // to logcat, so a failed search is readable on the device
                // even when the floating session that would speak it is gone.
                android.util.Log.i(
                    "LoomSearch",
                    "search app=\"$appName\" query=\"$query\" origin=$origin outcome=$outcome",
                )
                val ack = when (outcome) {
                    is SearchOutcome.Success -> outcome.detail
                    is SearchOutcome.Failed -> "Search failed: ${outcome.reason}"
                    SearchOutcome.AppNotFound ->
                        "I couldn't find an app called '$appName' on this phone."
                    is SearchOutcome.Ambiguous ->
                        "Which one — ${outcome.candidates.joinToString(", ")}?"
                }
                mainHandler.post { floatingVoice?.speakNow(ack) }
            }.start()
            return true
        }

        // Master §2 (item 5): the floating voice session's
        // assistant-move path. AssistantRegion / MoveResult are nested
        // in Worker 5's AssistantMover object (not top-level types as
        // the task stated). The ack comes ONLY from the returned
        // MoveResult. The mover needs its backend registered in
        // onCreate (see below) — until then every move fails honestly.
        override fun onAssistantMoveRequested(regionName: String): Boolean {
            val result = try {
                AssistantMover.move(AssistantMover.AssistantRegion.valueOf(regionName))
            } catch (_: IllegalArgumentException) {
                AssistantMover.MoveResult.Failed("unknown region '$regionName'")
            }
            when (result) {
                AssistantMover.MoveResult.Ok ->
                    floatingVoice?.speakNow("Moved the assistant.")
                is AssistantMover.MoveResult.Failed ->
                    floatingVoice?.speakNow(
                        "I couldn't move the assistant: ${result.reason}",
                    )
            }
            return true
        }

        // Dictated messaging + calls (2026-11-05, fix-plan item 12):
        // the floating voice session's message path. execute() BLOCKS
        // on confirmation cards (up to 90s per token — the send card
        // shows the recipient + the exact text), so it runs on a
        // background thread; the ack is posted back to the main thread
        // and spoken through the live floating session. Every outcome
        // maps to an honest spoken ack; nothing is claimed before the
        // executor reports. install() is retried here because the
        // accessibility service may have connected after onCreate.
        // The channel selects the app (WhatsApp / Messages) inside the
        // one MessageExecutor — no second executor exists.
        override fun onMessageRequested(
            channel: MessageChannel,
            recipient: String,
            text: String,
            origin: RequestOrigin,
        ): Boolean {
            MessageExecutorWiring.install(this@LoomOverlayService)
            Thread {
                val outcome = try {
                    MessageExecutor.execute(channel, recipient, text, origin)
                } catch (e: Exception) {
                    MessageOutcome.Failed("Message failed: ${e.message ?: "unknown error"}")
                }
                android.util.Log.i(
                    "LoomMessaging",
                    "message channel=$channel to=\"$recipient\" origin=$origin outcome=$outcome",
                )
                val ack = when (outcome) {
                    is MessageOutcome.Success -> outcome.detail
                    is MessageOutcome.Failed -> "Message failed: ${outcome.reason}"
                    MessageOutcome.AppNotFound ->
                        "I couldn't find the messaging app on this phone."
                    is MessageOutcome.AmbiguousApp ->
                        "Which one — ${outcome.candidates.joinToString(", ")}?"
                    is MessageOutcome.AmbiguousContact ->
                        "I found more than one contact matching '$recipient': " +
                            "${outcome.candidates.joinToString(", ")}. Which one did you mean?"
                }
                mainHandler.post { floatingVoice?.speakNow(ack) }
            }.start()
            return true
        }

        // Dictated messaging + calls (2026-11-05, fix-plan item 12):
        // the floating voice session's call path. Same background
        // thread + spoken-ack shape as onMessageRequested. A named
        // contact routes through the Phone app; a dictated number
        // through the ACTION_DIAL fallback (the dialer opens
        // pre-filled — David presses call). The call card shows
        // exactly who is being called.
        override fun onCallRequested(
            target: String,
            origin: RequestOrigin,
        ): Boolean {
            MessageExecutorWiring.install(this@LoomOverlayService)
            Thread {
                val outcome = try {
                    CallExecutor.execute(target, origin)
                } catch (e: Exception) {
                    CallOutcome.Failed("Call failed: ${e.message ?: "unknown error"}")
                }
                android.util.Log.i(
                    "LoomMessaging",
                    "call target=\"$target\" origin=$origin outcome=$outcome",
                )
                val ack = when (outcome) {
                    is CallOutcome.Success -> outcome.detail
                    is CallOutcome.Failed -> "Call failed: ${outcome.reason}"
                    CallOutcome.AppNotFound ->
                        "I couldn't find the Phone app on this phone."
                    is CallOutcome.AmbiguousApp ->
                        "Which one — ${outcome.candidates.joinToString(", ")}?"
                    is CallOutcome.AmbiguousContact ->
                        "I found more than one contact matching '$target': " +
                            "${outcome.candidates.joinToString(", ")}. Which one did you mean?"
                }
                mainHandler.post { floatingVoice?.speakNow(ack) }
            }.start()
            return true
        }

        // Stage 2D: the floating voice session's plan path. Same
        // background thread + spoken-ack shape as onCallRequested: the
        // executor BLOCKS on confirmation cards for MODEL plans (the
        // per-sequence cover card, then per-step cards where the cover
        // does not span) — DIRECT_USER plans run under blanket advance
        // approval with no card — so it runs off the main thread and
        // the honest outcome is spoken through the live floating
        // session. Every outcome maps to a spoken ack; nothing is
        // claimed before the executor reports. The plan target is
        // released by the executor's own completion path
        // (TaskPlanExecutor.executePlan finally clears the pointer
        // guide), so the idle pulse may resume after any outcome.
        //
        // Overrides VoiceSessionManager.Host.onPlanRequested (Worker C
        // added the interface method + handlePlanTask routing): the
        // stop signal combines the interaction layer's halt flag
        // (either independent stop) with the lifecycle (shutdown /
        // deactivate halts a running plan).
        override fun onPlanRequested(request: PlanRequest): Boolean {
            android.util.Log.i(
                "LoomPlan",
                "plan requested goal=\"${request.goal}\" origin=${request.origin}",
            )
            return LoomPlanRunner.runPlan(
                this@LoomOverlayService,
                request,
                onAck = { ack ->
                    mainHandler.post {
                        // D-05: the terminal outcome rides onto the
                        // shared record — a later "the task" reference
                        // can say it is done, or resume it by name.
                        SharedConversationContext.instance()
                            .noteTaskOutcome(ack.startsWith("Done —"))
                        // MASTER F4d — a successful single-command
                        // launch seeds the follow-up app in the shared
                        // context, from the host's actual request +
                        // outcome (not a spoken-sentence correlation).
                        // Only full completion ("Done —") promotes;
                        // partial ("Finished with…") and stopped runs
                        // do not. (The session's speakNow path also
                        // promotes from the same ack — a double
                        // promotion is a no-op, since confirmLaunch
                        // clears the attempt on the first call.)
                        if (ack.startsWith("Done —")) {
                            VoiceCommands.extractLaunchApp(request.goal)
                                .takeIf { it.isNotBlank() }
                                ?.let { app -> floatingVoice?.noteLaunchOutcome(app, true) }
                        }
                        floatingVoice?.speakNow(ack)
                    }
                },
                // Simple-task execution fix (2026-11-07) — the ROOT
                // CAUSE of David's "I could not see the failure"
                // report: the floating host delivered the plan
                // outcome ONLY by speaking it, and only when a voice
                // session was still live. If the session had ended,
                // the ack vanished silently and the chat showed
                // nothing. Every status update now ALSO lands in the
                // shared conversation store (the same store the chat
                // screen reads), on the runner's background thread,
                // whether or not anyone is listening — so the outcome
                // is visible in the chat afterwards. Speaking stays
                // for the live session: IN_PROGRESS and WAITING are
                // spoken (the terminal outcome is spoken once via
                // onAck, never twice).
                onStatus = { update ->
                    try {
                        val conv = store.getActive()
                        store.append(
                            conv.id,
                            ChatMessage(
                                "assistant",
                                update.message,
                                System.currentTimeMillis(),
                            ),
                        )
                    } catch (_: Exception) {
                    }
                    if (update.status == TaskStatus.IN_PROGRESS ||
                        update.status == TaskStatus.WAITING_FOR_USER_INPUT
                    ) {
                        mainHandler.post { floatingVoice?.speakNow(update.message) }
                    }
                },
                rendererProvider = { ensureGatedRenderer() },
                guideTo = { target ->
                    startGuidance(
                        listOf(GuidanceSession.GuideStep(target, "Guide to \"$target\"")),
                        origin = request.origin,
                    ) is GuidanceSession.StepResult.Shown
                },
                stopSignal = object : PlanStopSignal {
                    override fun isStopped(): Boolean =
                        (interaction?.isHaltRequested() == true) ||
                            LifecycleController.currentState() != LifecycleState.ACTIVE ||
                            // AOIE Phase 5: voice "stop" / Stop window
                            // set the shared registry even when no
                            // layer sequence is active (a plan run is
                            // not a sequence).
                            com.loom.assistant.core.common.PlanStopRegistry.isStopped()
                },
                // Pointer runs through Worker C's PlanPointerGuide over a
                // GuidanceSession: reuse the live guidance session when
                // one exists, else build an empty-steps one (its tree
                // source + renderer provider are what the guide uses;
                // the steps list is irrelevant to pointer tracking).
                // MASTER F6a — the session carries the plan's origin
                // (the pointer path itself requests no gate token, but
                // the provenance is correct if it ever does).
                guidanceSession = guidance
                    ?: buildGuidanceSession(
                        emptyList(), ensureGatedRenderer(), origin = request.origin,
                    ),
                // MASTER Wave 4 (F10): the helper-down gate — a dead
                // helper refuses before the plan starts, and the
                // refusal is recorded as a helper-down event with the
                // battery/auto-revoke state at the time.
                helperDownReason = {
                    val center = DeviceControlCenter(this@LoomOverlayService)
                    val refusal = center.helperDownRefusal()
                    if (refusal != null) {
                        center.helperDownCause()?.let { center.recordHelperDown(it) }
                    }
                    refusal
                },
            )
        }
        // Device-test failures (2026-11-11, D-04): "confirm your
        // screen" reads the live screen through Screen Awareness
        // and describes it. The floating host reads the same tree
        // the pointer works from, so the description and the
        // guidance agree about what is on screen.
        override fun describeCurrentScreen(): String =
            ScreenDescriber.describe(ScreenAwarenessHolder.current.read())

        // Session/state awareness (2026-11-11, OBSERVE-FIRST): a
        // Pause / Play control on the live screen means the command
        // is about the media on screen, not LOOM's own session.
        override fun mediaControlOnScreen(control: String): Boolean {
            val screen = ScreenAwarenessHolder.current.snapshot() ?: return false
            val want = MediaControlPhrases.match(control) ?: return false
            return MediaControlRouter.mediaControlOnScreen(screen, want)
        }

        // Stage 2A.1 (D1): the floating voice session reaches mode switches
        // through ModeController.applyMode via the manager — this host no
        // longer carries its own weaker switchOverlayMode path (R2-B).
    }

    // ---------- Stage 2C lifecycle: shutdown / deactivation / hide ----------
    //
    // The application-scoped LifecycleController owns the state transitions;
    // this service executes the teardown steps it owns (views, pipeline,
    // its own stop) and verifies them. Verification never asks the dying
    // service whether it is dead (closure decision 6).

    private val shutdownRunning = AtomicBoolean(false)

    /** Foreground-notification variant (§4.2-D/F, closure decision 1). */
    private var notificationVariant: OverlayNotificationVariant = OverlayNotificationVariant.ACTIVE

    private fun auditLifecycle(detail: String) {
        try {
            ActivityLog.get(this).record("lifecycle", detail)
        } catch (_: Exception) {
        }
    }

    private fun refreshNotification() {
        try {
            startForeground(NOTIF_ID, buildNotification(notificationVariant))
        } catch (_: Exception) {
        }
    }

    /** Run [action] on the main thread, waiting for completion (teardown steps). */
    private fun mainSync(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        var error: Throwable? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                action()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        error?.let { throw it }
    }

    private fun mainQuery(query: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return query()
        var result = false
        var error: Throwable? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                result = query()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        error?.let { throw it }
        return result
    }

    // ----- Immediate floating shutdown (2026-11-13) -----

    /**
     * Device-test failures (2026-11-11, D-06): the floating shutdown
     * confirmation was spoken-only — in the screenshots David sees
     * only the "Heard: loom shutdown" pill and no question, so the
     * confirmation looks like nothing happened. The request and the
     * outcome also land in the shared chat thread, so they are
     * visible afterwards. Best-effort: a store failure never blocks
     * the spoken path.
     */
    private fun appendToChat(text: String) {
        try {
            val store = ConversationStore.get(this)
            val conv = store.getActive()
            store.append(conv.id, ChatMessage("assistant", text, System.currentTimeMillis()))
        } catch (_: Exception) {
        }
    }

    /**
     * Floating "shut down LOOM" — IMMEDIATE, no confirmation (David's
     * binding rule, Nov 13 ~03:36). The command goes straight to the
     * Affirmed branch's actions: the outcome is written to chat and
     * spoken, the intentional-stop marker is written BEFORE any
     * teardown runs (same as the in-app path — no auto-restart after
     * an intentional shutdown), and the existing 12-step
     * [runFullShutdown] teardown starts on a worker thread behind
     * the [shutdownRunning] guard. There is no question, no
     * listening window, no re-ask, and no cancel path anywhere in
     * this function — nothing here can turn a shutdown command
     * into "Shutdown cancelled.". The teardown itself, the
     * intentional-stop write, and the reactivation path are
     * unchanged; only the unreliable confirmation window is gone.
     */
    private fun executeFloatingShutdownNow() {
        if (LifecycleController.currentState() != LifecycleState.ACTIVE) {
            voicePipeline.speak(
                "LOOM isn't active right now, so there's nothing to shut down.",
                {},
                {},
            )
            return
        }
        // D-06: the outcome is written to the chat thread as well as
        // spoken — the shutdown is visible, not only heard.
        appendToChat("Shutting down.")
        voicePipeline.speak("Shutting down.", {}, {})
        // Automatic recovery (Nov 6): intentional stop — written
        // before any teardown runs, same as the in-app path.
        try {
            AutomaticRecoveryStore.get(this).writeIntentionalStop(
                IntentionalStop(IntentionalStopCommand.SHUTDOWN, System.currentTimeMillis()),
            )
        } catch (_: Exception) {
        }
        if (!shutdownRunning.compareAndSet(false, true)) {
            auditLifecycle("floating shutdown ignored — a shutdown run is already in progress")
            return
        }
        Thread({ runFullShutdown("floating-voice") }, "loom-shutdown").start()
    }

    // ----- HIDE YOURSELF / SHOW ASSISTANT (§4.2-D) -----

    /**
     * HIDE YOURSELF primitive: removes floating views WITHOUT stopping the
     * voice session, mic, services, or interaction layer. The controller
     * stays ACTIVE (hidden). The notification switches to the HIDE variant
     * with the SHOW ASSISTANT action.
     */
    private fun hideFloatingViews() {
        if (LifecycleController.currentState() != LifecycleState.ACTIVE) return
        dismissPanel()
        hideStopWindow()
        removeModeViews()
        notificationVariant = OverlayNotificationVariant.HIDDEN
        refreshNotification()
        auditLifecycle("assistant hidden — session/mic/services untouched, controller ACTIVE")
    }

    /**
     * SHOW ASSISTANT: restores ONLY the existing floating presentation
     * (re-binds the persisted mode's view). Guarded: requires lifecycle ==
     * ACTIVE; while INACTIVE/SHUT_DOWN it is a no-op. Never starts the mic,
     * never transitions the lifecycle, never REACTIVATING — restoring a
     * hidden presentation is NOT reactivation.
     */
    private fun showFloatingViews() {
        if (LifecycleController.currentState() != LifecycleState.ACTIVE) {
            auditLifecycle(
                "SHOW ASSISTANT ignored — lifecycle is " +
                    "${LifecycleController.currentState()}, not ACTIVE",
            )
            return
        }
        bindMode(ModeController.getMode(this))
        notificationVariant = OverlayNotificationVariant.ACTIVE
        refreshNotification()
        auditLifecycle("assistant presentation restored — no mic start, no lifecycle transition")
    }

    // ----- DEACTIVATE ASSISTANT MODE (§4.2-F, §4.5) -----

    /**
     * End session → mic off → TTS stopped + queue cleared → halt live
     * sequences (INTERRUPTED) → AuthGate.purgeAll("deactivate") → drain the
     * action queue → hide floating views → ASSISTANT_MODE_INACTIVE.
     *
     * Purge-before-flip: purgeAll() runs BEFORE the lifecycle leaves ACTIVE —
     * no stale authorization survives deactivation, and reactivation grants
     * none (every new action needs a fresh token). App/services installation,
     * settings, memory and preferences are preserved: not an uninstall, not
     * a data wipe, not a process kill.
     *
     * The notification becomes the status-only DEACTIVATED variant — no
     * actions, no content intent (closure decision 1, mismatch #5).
     */
    private fun deactivateAssistant() {
        if (LifecycleController.currentState() != LifecycleState.ACTIVE) {
            auditLifecycle(
                "deactivate ignored — lifecycle is ${LifecycleController.currentState()}, not ACTIVE",
            )
            return
        }
        // Automatic recovery (Nov 6): an explicit DEACTIVATE is
        // intentional — written before any teardown runs, so the phone
        // never brings LOOM back on its own after it.
        try {
            AutomaticRecoveryStore.get(this).writeIntentionalStop(
                IntentionalStop(IntentionalStopCommand.DEACTIVATE, System.currentTimeMillis()),
            )
        } catch (_: Exception) {
        }
        val steps = listOf(
            TeardownStep(
                name = "end voice session",
                destructive = false,
                expected = "no live voice session",
                run = {
                    mainSync {
                        try {
                            floatingVoice?.end()
                        } catch (_: Exception) {
                        }
                        floatingVoice = null
                        VoiceSessionOwner.releaseFloating()
                    }
                },
                verify = { floatingVoice == null },
            ),
            TeardownStep(
                name = "microphone off",
                destructive = false,
                expected = "recognizer not listening",
                run = {
                    try {
                        voicePipeline.stopListening()
                    } catch (_: Exception) {
                    }
                },
                verify = { voicePipeline.state != VoiceState.LISTENING },
            ),
            TeardownStep(
                name = "stop TTS + clear queue",
                destructive = false,
                expected = "TTS idle, pending speech cleared",
                run = {
                    try {
                        voicePipeline.stopSpeaking()
                    } catch (_: Exception) {
                    }
                    try {
                        voicePipeline.clearPendingSpeech()
                    } catch (_: Exception) {
                    }
                },
                verify = { voicePipeline.state != VoiceState.SPEAKING },
            ),
            TeardownStep(
                name = "halt live sequences",
                destructive = false,
                expected = "no live sequence",
                run = {
                    try {
                        interaction?.halt(HaltSource.VOICE)
                    } catch (_: Exception) {
                    }
                },
                verify = { interaction?.isSequenceActive != true },
            ),
            TeardownStep(
                name = "AuthGate purgeAll",
                destructive = true,
                expected = "issued tokens revoked, pending confirmations cancelled",
                run = {
                    try {
                        AuthGate.getOrNull()?.purgeAll("deactivate")
                    } catch (_: Exception) {
                    }
                },
                verify = {
                    val r = try {
                        AuthGate.getOrNull()?.purgeAll("deactivate-verify")
                    } catch (_: Exception) {
                        null
                    }
                    r == null || (r.pendingCancelled == 0 && r.tokensRevoked == 0)
                },
            ),
            TeardownStep(
                name = "drain action queue",
                destructive = true,
                expected = "no live demo run; enqueued actions dead",
                run = {
                    try {
                        actionQueue?.drainForDeactivation()
                    } catch (_: Exception) {
                    }
                },
                verify = { actionQueue?.isDemoActive() != true },
            ),
            TeardownStep(
                name = "hide floating views",
                destructive = true,
                expected = "character/orb/edge detached from WindowManager",
                run = {
                    mainSync {
                        dismissPanel()
                        hideStopWindow()
                        removeModeViews()
                    }
                },
                verify = {
                    mainQuery {
                        characterView == null && orbView == null && edgeView == null
                    }
                },
            ),
            TeardownStep(
                name = "enter ASSISTANT_MODE_INACTIVE",
                destructive = true,
                expected = "lifecycle == ASSISTANT_MODE_INACTIVE",
                run = {
                    LifecycleController.transitionTo(
                        LifecycleState.ASSISTANT_MODE_INACTIVE,
                        "deactivate assistant mode",
                    )
                },
                verify = {
                    LifecycleController.currentState() == LifecycleState.ASSISTANT_MODE_INACTIVE
                },
            ),
        )
        val report = DeactivationOrchestrator.execute(steps) { d -> auditLifecycle(d) }
        if (report.reachedInactive) {
            notificationVariant = OverlayNotificationVariant.DEACTIVATED
            refreshNotification()
            auditLifecycle("deactivated — notification is status-only (no actions, no content intent)")
        } else {
            auditLifecycle("deactivation INCOMPLETE — lifecycle unchanged:\n${report.summary()}")
        }
    }

    // ----- Complete shutdown — the 12 steps (§4.3) -----

    /**
     * Runs the ordered teardown on a worker thread (never the main thread —
     * verification polls). Order: halt → purgeAll (BEFORE the lifecycle
     * leaves ACTIVE — purge-before-flip) → SHUTTING_DOWN → mic/TTS →
     * callbacks → temp state → views → pipeline → service stop LAST →
     * independent verification gate → declare SHUT_DOWN only from a
     * fully-passing report.
     */
    private fun runFullShutdown(source: String) {
        try {
            val report = ShutdownOrchestrator.execute(buildShutdownSteps()) { d ->
                auditLifecycle(d)
            }
            if (!report.passed) {
                // §4.3.1 recoverable path: no false SHUT_DOWN. Name what
                // stopped and what remains; keep the notification for retry.
                auditLifecycle("shutdown DEGRADED — not SHUT_DOWN:\n${report.degradedSummary()}")
                notificationVariant = OverlayNotificationVariant.ACTIVE
                refreshNotification()
                try {
                    voicePipeline.speak(
                        "Shutdown didn't finish. " + report.failed.joinToString("; ") { it.component } +
                            " still need attention. You can retry the shutdown.",
                        {},
                        {},
                    )
                } catch (_: Exception) {
                }
                return
            }
            // All checks passed: report the verified mirrors, then declare.
            // transitionTo(SHUT_DOWN) refuses while any mirror reports live.
            LifecycleController.reportMicLive(false)
            LifecycleController.reportSessionState("IDLE")
            LifecycleController.reportServiceLive(false)
            LifecycleController.reportViewsAttached(false)
            if (
                LifecycleController.transitionTo(
                    LifecycleState.SHUT_DOWN,
                    "verified 12-step shutdown ($source)",
                )
            ) {
                auditLifecycle("SHUT_DOWN verified — ${report.results.size} checks passed")
                // COORDINATOR: the verified SHUT_DOWN is persisted by the
                // controller's lifecycle_state record (§4.1).
            } else {
                auditLifecycle(
                    "SHUT_DOWN REFUSED by controller from " +
                        "${LifecycleController.currentState()} — staying SHUTTING_DOWN",
                )
            }
        } finally {
            shutdownRunning.set(false)
        }
    }

    /**
     * The §4.3 step list. Destructive steps (plan steps 5–10) are reported
     * to the controller as they execute — EXCEPT the purge, which runs
     * pre-flip by design (purge-before-flip); the controller clears its
     * destructive set on SHUTTING_DOWN entry, so only post-flip destructive
     * steps are noted (recovery-rule semantics preserved).
     */
    private fun buildShutdownSteps(): List<TeardownStep> {
        val postFlipDestructive = { s: TeardownStep ->
            s.copy(
                run = {
                    LifecycleController.noteDestructiveStep(s.name)
                    s.run()
                },
            )
        }
        var stopRequestIssued = false
        return listOf(
            // Step 1 (non-destructive): entered SHUTTING_DOWN later — halt first.
            TeardownStep(
                name = "halt live sequences",
                destructive = false,
                expected = "no live sequence; INTERRUPTED reported",
                run = {
                    try {
                        interaction?.halt(HaltSource.VOICE)
                    } catch (_: Exception) {
                    }
                },
                verify = { interaction?.isSequenceActive != true },
            ),
            // Step 5-class (destructive per plan) but pre-flip by design.
            TeardownStep(
                name = "AuthGate purgeAll",
                destructive = true,
                expected = "issued tokens revoked, pending confirmations cancelled",
                run = {
                    try {
                        AuthGate.getOrNull()?.purgeAll("shutdown")
                    } catch (_: Exception) {
                    }
                },
                verify = {
                    val r = try {
                        AuthGate.getOrNull()?.purgeAll("shutdown-verify")
                    } catch (_: Exception) {
                        null
                    }
                    r == null || (r.pendingCancelled == 0 && r.tokensRevoked == 0)
                },
            ),
            TeardownStep(
                name = "enter SHUTTING_DOWN",
                destructive = false,
                expected = "lifecycle == SHUTTING_DOWN",
                run = {
                    LifecycleController.transitionTo(
                        LifecycleState.SHUTTING_DOWN,
                        // Device-failure fix (2026-11-13, F1): no
                        // confirmation exists any more — the
                        // command shuts down immediately.
                        "shutdown requested",
                    )
                },
                verify = { LifecycleController.currentState() == LifecycleState.SHUTTING_DOWN },
            ),
            TeardownStep(
                name = "stop microphone capture",
                destructive = false,
                expected = "recognizer destroyed, every session IDLE",
                run = {
                    mainSync {
                        try {
                            floatingVoice?.end()
                        } catch (_: Exception) {
                        }
                        floatingVoice = null
                        VoiceSessionOwner.releaseFloating()
                    }
                    try {
                        voicePipeline.stopListening()
                    } catch (_: Exception) {
                    }
                },
                verify = {
                    floatingVoice == null && voicePipeline.state != VoiceState.LISTENING
                },
                describeActual = {
                    if (voicePipeline.state == VoiceState.LISTENING) "recognizer still alive"
                    else if (floatingVoice != null) "floating session still present"
                    else ""
                },
            ),
            TeardownStep(
                name = "stop TTS + clear queue",
                destructive = false,
                expected = "TTS idle, pending speech cleared",
                run = {
                    try {
                        voicePipeline.stopSpeaking()
                    } catch (_: Exception) {
                    }
                    try {
                        voicePipeline.clearPendingSpeech()
                    } catch (_: Exception) {
                    }
                },
                verify = { voicePipeline.state != VoiceState.SPEAKING },
            ),
            postFlipDestructive(
                TeardownStep(
                    name = "cancel delayed callbacks",
                    destructive = true,
                    expected = "no pending postDelayed work fires into teardown",
                    run = {
                        // Directly cancellable: the overlay drag-save handler.
                        // The halt flag (step 1) already blocks the rest:
                        // ModeController's poll loop, ActionQueue's
                        // PreviewDelayer and VoiceSessionManager's retry
                        // runnable all check halt/session state before acting,
                        // and their sessions are ended above.
                        dragSaveHandler.removeCallbacksAndMessages(null)
                        pendingDragSaves.clear()
                    },
                    verify = { true },
                ),
            ),
            postFlipDestructive(
                TeardownStep(
                    name = "clear temporary interaction state",
                    destructive = true,
                    expected = "no live guidance session",
                    run = {
                        mainSync {
                            try {
                                guidance?.end()
                            } catch (_: Exception) {
                            }
                            guidance = null
                        }
                    },
                    verify = { guidance == null },
                ),
            ),
            postFlipDestructive(
                TeardownStep(
                    name = "remove floating views",
                    destructive = true,
                    expected = "character/orb/edge/panel/stop-window detached",
                    run = {
                        mainSync {
                            dismissPanel()
                            hideStopWindow()
                            hideAuthCard(clearAll = true)
                            removeModeViews()
                        }
                    },
                    verify = {
                        mainQuery {
                            characterView == null && orbView == null && edgeView == null
                        }
                    },
                    describeActual = {
                        val stuck = listOf(
                            "character" to (characterView != null),
                            "orb" to (orbView != null),
                            "edge" to (edgeView != null),
                        ).filter { it.second }.joinToString(", ") { it.first }
                        if (stuck.isNotEmpty()) "$stuck view(s) still attached" else ""
                    },
                ),
            ),
            postFlipDestructive(
                TeardownStep(
                    name = "release voice pipeline",
                    destructive = true,
                    expected = "pipeline shut down",
                    run = {
                        try {
                            voicePipeline.shutdown()
                        } catch (_: Exception) {
                        }
                    },
                    verify = {
                        voicePipeline.state != VoiceState.LISTENING &&
                            voicePipeline.state != VoiceState.SPEAKING
                    },
                ),
            ),
            postFlipDestructive(
                TeardownStep(
                    name = "stop overlay service",
                    destructive = true,
                    expected = "stop request issued (verdict comes from the probe)",
                    run = {
                        // stopService() is background-safe (no
                        // IllegalStateException); its boolean is the only
                        // signal the request path gives — never proof.
                        stopRequestIssued = try {
                            stopService(Intent(this, LoomOverlayService::class.java))
                            true
                        } catch (e: Exception) {
                            auditLifecycle("service stop request threw: ${e.javaClass.simpleName}")
                            false
                        }
                    },
                    verify = { stopRequestIssued },
                ),
            ),
            // Step 11 — the independent verification gate (never the dying
            // service reporting its own death).
            TeardownStep(
                name = "verify: overlay service stopped",
                destructive = false,
                expected = "service not bound (bind WITHOUT BIND_AUTO_CREATE)",
                run = {},
                verify = { isOverlayServiceStopped() },
                describeActual = {
                    if (destroyedAtMarker > 0) "onDestroy marker seen; probe verdict in audit"
                    else "no onDestroy marker; probe verdict in audit"
                },
            ),
            TeardownStep(
                name = "verify: notification gone",
                destructive = false,
                expected = "no LOOM foreground notification in getActiveNotifications()",
                run = {},
                verify = { isLoomNotificationGone() },
                describeActual = { "LOOM notification still present after service stop" },
            ),
        )
    }

    /**
     * Independent liveness probe (closure decision 6): bindService WITHOUT
     * BIND_AUTO_CREATE — must never restart the service. Polled every 500ms
     * up to 5s; the verdict is the final poll. getRunningServices() is not
     * used anywhere. Timeout/ambiguity → the check fails honestly (the
     * report names the component, never assumes stopped).
     */
    private fun isOverlayServiceStopped(): Boolean {
        repeat(10) { i ->
            if (!isOverlayServiceBound()) return true
            if (i < 9) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    private fun isOverlayServiceBound(): Boolean {
        // Written on the main thread (ServiceConnection callback), read on
        // the shutdown worker thread.
        val connected = AtomicBoolean(false)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.set(true)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
            }
        }
        val bound = try {
            applicationContext.bindService(
                Intent(applicationContext, LoomOverlayService::class.java),
                conn,
                0, // WITHOUT BIND_AUTO_CREATE — a bind attempt must not resurrect the service
            )
        } catch (_: Exception) {
            false
        }
        try {
            if (bound) Thread.sleep(300) // allow onServiceConnected to fire
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                applicationContext.unbindService(conn)
            } catch (_: Exception) {
            }
        }
        return connected.get()
    }

    /**
     * NotificationManager.getActiveNotifications() absence, polled every
     * 250ms up to 3s (no special permission needed for own notifications).
     */
    private fun isLoomNotificationGone(): Boolean {
        val nm = try {
            applicationContext.getSystemService(NotificationManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return false
        repeat(12) { i ->
            val gone = try {
                nm.activeNotifications.none { it.id == NOTIF_ID }
            } catch (_: Exception) {
                false
            }
            if (gone) return true
            if (i < 11) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    // ---------- Stage 2C (F): interaction layer construction + wiring ----------

    /**
     * Builds the single InteractionLayer with the production adapters:
     * EventSink → LoomEventBus, AuditLogger → ActivityLog. The host owns
     * every delegate; the layer owns all interaction state.
     */
    private fun buildInteractionLayer(): InteractionLayer {
        val layer = InteractionLayer(
            events = EventSink { event -> LoomEventBus.post(event) },
            audit = AuditLogger { kind, detail ->
                try {
                    ActivityLog.get(this).record(kind, detail)
                } catch (_: Exception) {
                }
            },
        )
        // §5.1 step 2: Android exposes no API to cancel an in-flight
        // dispatchGesture — gestureCanceller stays unset; the halt flag
        // blocks every new dispatch and the queue maps onCancelled to
        // INTERRUPTED (never "failed").
        layer.confirmationCanceller = { pendingId ->
            try {
                AuthGate.getOrNull()?.cancelPending(pendingId)
            } catch (_: Exception) {
            }
        }
        layer.onSequenceEnded = {
            mainHandler.post {
                try {
                    guidance?.end()
                } catch (_: Exception) {
                }
                guidance = null
                lastGuideQuery = ""
                syncStopWindow()
            }
        }
        layer.onGuidanceRequested = { query ->
            mainHandler.post { startGuidanceSession(query) }
        }
        layer.onDemonstrationRequested = { goal ->
            mainHandler.post { startDemonstrationSession(goal) }
        }
        layer.onGuideStep = { next ->
            describeStepResult(if (next) guidanceNext() else guidanceBack())
        }
        return layer
    }

    // ---------- Stage 2C (F1/F2): anchors ----------

    private fun ensureAnchorResolver(): AnchorResolver {
        anchorResolver?.let { return it }
        val resolver = AnchorResolver(object : AnchorPositions {
            override fun characterCenter(): GeomPoint? = viewCenter(characterView)
            override fun orbCenter(): GeomPoint? = viewCenter(orbView)
            override fun windowSize(): GeomSize {
                val dm = resources.displayMetrics
                return GeomSize(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
            }
        })
        resolver.listener = { event ->
            if (event is AnchorEvent.Moved) {
                try {
                    gatedRenderer?.onAnchorChanged(event.to.x, event.to.y)
                } catch (_: Exception) {
                }
            }
        }
        anchorResolver = resolver
        return resolver
    }

    private fun viewCenter(view: View?): GeomPoint? {
        val v = view ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return GeomPoint(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    /** Current beam anchor for the active mode, via the resolver. */
    private fun anchorPair(): Pair<Float, Float> {
        val a = ensureAnchorResolver().anchorFor(guidanceMode)
        return a.x to a.y
    }

    /**
     * Stage 2C (F1/F2): the verified mode transition refreshes the guidance
     * anchor path. Called from bindMode's tail (after a verified view
     * attach) and from ModeController's post-verification hook — both are
     * idempotent: AnchorResolver emits an animated event only when the
     * anchor actually moved, and re-layering runs only mid-sequence.
     */
    private fun refreshGuidanceForMode(mode: AssistantMode) {
        // The 3-line AssistantMode → GuidanceMode map (lives with the host).
        guidanceMode = when (mode) {
            AssistantMode.CHARACTER -> GuidanceMode.CHARACTER
            AssistantMode.ORB -> GuidanceMode.ORB
            AssistantMode.INVISIBLE -> GuidanceMode.INVISIBLE
        }
        lastModeChangeMs = SystemClock.uptimeMillis()
        interaction?.onModeChanged(mode.name)
        val event = try {
            ensureAnchorResolver().onModeChanged(guidanceMode)
        } catch (_: Exception) {
            null
        }
        if (event is AnchorEvent.Moved) {
            try {
                gatedRenderer?.onAnchorChanged(event.to.x, event.to.y)
            } catch (_: Exception) {
            }
        }
        relayerGuidanceTopmost()
    }

    // ---------- Stage 2C (A1/A2): trusted guidance rendering ----------

    /**
     * The guidance renderer on the TRUSTED overlay host — a
     * TYPE_ACCESSIBILITY_OVERLAY window from LoomAccessibilityService's own
     * WindowManager (deterministic topmost z-order, 2B D2), NOT_TOUCHABLE so
     * David's touches pass through. Null when the host is unavailable
     * (a11y service off) — callers degrade honestly, never with a fake
     * highlight.
     *
     * Device-evidence fix (2026-11-05, pointer H1): the cached renderer
     * is validated on EVERY call — host identity + live attach state +
     * the a11y service's invalidation flag. A stale cache (host rebound,
     * service toggled, window detached) is dropped and re-created; the
     * old code returned it blindly and drew into a dead window.
     */
    private fun ensureGuidanceRenderer(): GuidanceRenderer? {
        val host = LoomAccessibilityService.instance
        val invalidated = LoomAccessibilityService.consumeGuidanceRendererInvalidation()
        guidanceRenderer?.let { cached ->
            // Accessibility fix (2026-11-05, audit §3 hole 2): set
            // membership is not attach state — a window killed without
            // removeGuidanceOverlayView() was trusted as live. A cached
            // view that left the window is stale too, so it is dropped
            // and re-created instead of drawn into.
            val stale = invalidated || host == null || host !== guidanceRendererHost ||
                !host.isGuidanceOverlayAttached(cached) || !cached.isAttachedToWindow
            if (stale) {
                android.util.Log.w(
                    "LoomPointer",
                    "ensureGuidanceRenderer: dropping stale cache " +
                        "(invalidated=$invalidated hostNull=${host == null} " +
                        "hostChanged=${host != null && host !== guidanceRendererHost} " +
                        "attached=${host?.isGuidanceOverlayAttached(cached)} " +
                        "windowAttached=${cached.isAttachedToWindow})",
                )
                guidanceRenderer = null
                guidanceRendererHost = null
                gatedRenderer = null
                interaction?.renderer = null
            } else {
                android.util.Log.d(
                    "LoomPointer",
                    "ensureGuidanceRenderer: cached renderer live, " +
                        "attached=${cached.isAttachedToWindow} size=${cached.width}x${cached.height}",
                )
                return cached
            }
        }
        if (host == null || !host.isTrustedHostAvailable()) {
            android.util.Log.w("LoomPointer", "ensureGuidanceRenderer: no trusted host")
            return null
        }
        val renderer = GuidanceRenderer(this)
        return try {
            if (host.addGuidanceOverlayView(renderer)) {
                guidanceRenderer = renderer
                guidanceRendererHost = host
                // Master §6: the renderer's target/pointer reporting goes
                // to the shared feedback sink, seeded with the live tree.
                renderer.feedback = executionFeedback
                executionFeedback.updateScreen(ScreenAwarenessHolder.current.snapshot())
                android.util.Log.i(
                    "LoomPointer",
                    "ensureGuidanceRenderer: created, attached=${renderer.isAttachedToWindow} " +
                        "size=${renderer.width}x${renderer.height}",
                )
                renderer
            } else {
                android.util.Log.w("LoomPointer", "ensureGuidanceRenderer: host refused the view")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("LoomPointer", "ensureGuidanceRenderer threw: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The renderer the layer, queue and session actually drive: the trusted
     * view behind the persisted pointer-policy gate (2B D3a, preserved —
     * HIDE hides the pointer but never disables automation; AUTO shows
     * during guidance/demonstration, the only sequence kinds in 2C).
     */
    private fun ensureGatedRenderer(): GuidanceRendererContract? {
        // Pointer-drive fix (2026-11-06): renderer ACQUISITION touches
        // WindowManager (addView needs a Looper) and every caller — the
        // plan runner, the search wiring, guidance sessions — may call
        // from a background thread. Marshal the whole acquisition onto
        // the main thread (same bounded 2 s wait as
        // showPointerNowDetailed); a caller already on main runs
        // inline. A marshal that times out returns null — the caller
        // then reports honestly instead of drawing into a dead window.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: GuidanceRendererContract? = null
            mainHandler.post {
                try {
                    result = ensureGatedRenderer()
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
            return try {
                if (latch.await(
                        SHOW_POINTER_MAIN_TIMEOUT_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                ) {
                    result
                } else {
                    android.util.Log.w(
                        "LoomPointer",
                        "ensureGatedRenderer: main-thread marshal timed out — no renderer",
                    )
                    null
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        }
        // Validate the raw renderer FIRST (H1): a stale raw drops the
        // gated cache with it, so a cached gate can never wrap a dead view.
        val raw = ensureGuidanceRenderer() ?: return null
        gatedRenderer?.let { return it }
        // Pointer-drive fix (2026-11-06): the ONE marshaling point for
        // pointer draws — every draw call the cached renderer receives
        // from a background thread (plan executor, search sequence,
        // guidance session, action queue) is marshaled onto the main
        // thread by this wrapper, so invalidate() always lands on the
        // UI thread. The policy gate stays inside it (HIDE still hides
        // the pointer, never the automation).
        val gated = MainThreadGuidanceRenderer(PolicyGatedRenderer(raw))
        gatedRenderer = gated
        interaction?.renderer = gated
        return gated
    }

    /**
     * Accessibility fix (2026-11-05, audit plan step 2 — the keystone
     * seam). The ONE public entry for "show the pointer", coded against
     * by the Home one-tap button, typed chat and the voice ack: it shows
     * the pointer NOW, verified, or reports false.
     *
     * Order: persist the SHOW policy (so the gate allows drawing) →
     * resolve/attach the gated renderer → verify the raw renderer is
     * attached to its window → render the idle indicator through the
     * interaction layer → verify still attached. Returns true ONLY when
     * the idle render was issued against a renderer verified attached
     * before and after. Returns false when the policy write failed, the
     * trusted host is unavailable (a11y service off), attach was
     * refused, the cached renderer was stale/detached, the layer is
     * absent, or the gate refused the draw.
     *
     * It NEVER speaks or posts text itself — it only returns the verified
     * boolean. Callers gate every confirmation on this result and say
     * one plain sentence when it is false. The decision lives in the
     * JVM-testable ShowPointerSeam; this is the thin Android wiring
     * over it.
     */
    fun showPointerNow(): Boolean = showPointerNowDetailed().shown

    /**
     * Pointer-drive fix (2026-11-06): the live gated renderer for
     * callers without a service reference (see the [Companion]
     * [liveRenderer] static). Delegates to [ensureGatedRenderer],
     * which marshals acquisition onto the main thread itself — safe
     * from a background thread.
     */
    fun liveGatedRenderer(): GuidanceRendererContract? = ensureGatedRenderer()

    /**
     * Pointer failure fix (2026-11-05): reason-carrying variant of
     * [showPointerNow]. Same seam, same order — but the result names
     * the first step that failed, and every failure is logged under
     * the LoomPointer tag with its step, so the next "pointer didn't
     * show" on device names itself instead of collapsing into one
     * sentence.
     *
     * THREAD — the decisive device finding (Nov 5 22:05): the a11y
     * service is LIVE and BOUND at the moment of failure, yet every
     * Home tap fails identically. Both Home and chat call this seam
     * from a raw worker Thread with NO Looper. The seam touches
     * WindowManager: `addView` creates a ViewRootImpl on the calling
     * thread, and ViewRootImpl's constructor calls
     * Choreographer.getInstance(), which throws IllegalStateException
     * ("The current thread must have a looper!") on a thread with no
     * Looper. So a FIRST render (no cached renderer) threw inside
     * `addGuidanceOverlayView`, which caught it and returned false —
     * reported as HOST_REFUSED on every tap, however live the host
     * was. The seam therefore ALWAYS runs on the main thread: a
     * caller already on main runs inline; any other thread is
     * marshalled via mainHandler with a bounded 2 s wait (generous
     * for a sub-100 ms seam; a marshal that times out reports
     * TIMEOUT and lets the caller's retry loop try again, instead of
     * eating the caller's whole window). This fixes every caller —
     * Home, typed chat, voice ack — in one place; no caller needs
     * its own thread handling.
     *
     * The host adapter is defensive: a throw inside any step is
     * caught at that step and reported as that step's failure (with
     * the exception class logged, never user data), so the seam can
     * never throw out of here; the outer catch is a last-resort
     * guard only.
     */
    fun showPointerNowDetailed(): ShowPointerResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return runShowPointerDetailedNow()
        var result: ShowPointerResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            result = try {
                runShowPointerDetailedNow()
            } catch (t: Throwable) {
                android.util.Log.w(
                    "LoomPointer",
                    "showPointerNowDetailed main-thread run threw: ${t.javaClass.simpleName}",
                )
                ShowPointerResult(shown = false, failure = ShowPointerFailure.GATE_REFUSED)
            }
            latch.countDown()
        }
        val done = try {
            latch.await(
                SHOW_POINTER_MAIN_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        } catch (_: InterruptedException) {
            false
        }
        if (!done || result == null) {
            android.util.Log.w(
                "LoomPointer",
                "showPointerNowDetailed: main-thread marshal timed out " +
                    "after ${SHOW_POINTER_MAIN_TIMEOUT_MS}ms",
            )
            return ShowPointerResult(shown = false, failure = ShowPointerFailure.TIMEOUT)
        }
        return result
    }

    /**
     * The seam run itself — MUST execute on the main thread (see
     * [showPointerNowDetailed]: WindowManager.addView needs a Looper
     * on the calling thread, and View invalidate/layout must run on
     * the thread that owns the hierarchy).
     */
    private fun runShowPointerDetailedNow(): ShowPointerResult {
        return try {
            val result = ShowPointerSeam.runDetailed(
                host = object : ShowPointerHost {
                    override fun setPolicyShow(): Boolean = try {
                        PointerPolicyStore.set(
                            this@LoomOverlayService,
                            PointerPolicy.SHOW,
                        ) != PointerPolicyStore.SetResult.Failed
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "LoomPointer",
                            "showPointerNowDetailed: policy write threw: ${e.javaClass.simpleName}",
                        )
                        false
                    }

                    override fun ensureRenderer(): ShowPointerRenderer? {
                        return try {
                            if (ensureGatedRenderer() == null) return null
                            val raw = guidanceRenderer ?: return null
                            object : ShowPointerRenderer {
                                // Verify-time size log (pointer failure
                                // fix): attached + size at EACH seam
                                // verification, before and after the
                                // idle draw. Size is DIAGNOSTIC ONLY,
                                // never a success criterion — a view
                                // added in this same call reads 0x0
                                // until its first layout pass runs (the
                                // main thread is busy running this seam,
                                // so no traversal can run mid-call); a
                                // non-zero size means the renderer was
                                // already attached from an earlier call.
                                // See also GuidanceRenderer
                                // .showIdleIndicator, which anchors to
                                // (width/2, height/2) — a 0-size read
                                // here means that draw anchored at (0,0).
                                override fun isAttachedToWindow(): Boolean {
                                    val attached = raw.isAttachedToWindow
                                    android.util.Log.d(
                                        "LoomPointer",
                                        "showPointerNowDetailed: verify " +
                                            "attached=$attached size=${raw.width}x${raw.height}",
                                    )
                                    return attached
                                }

                                override fun sizeDescription(): String =
                                    "${raw.width}x${raw.height}"
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "LoomPointer",
                                "showPointerNowDetailed: ensureRenderer threw: ${e.javaClass.simpleName}",
                            )
                            null
                        }
                    }

                    override fun rendererFailureReason(): ShowPointerFailure {
                        // Read live, at the moment ensure returned null:
                        // no live host at all vs a live host that refused.
                        // isTrustedHostAvailable() is `instance === this`
                        // (LoomAccessibilityService.kt:452) — true exactly
                        // when this host object IS the currently live
                        // service instance. Device evidence (Nov 5 22:05)
                        // has the service live and bound, so a NO_HOST
                        // here would mean the host object the overlay
                        // service read is stale (rebound between the
                        // read and the check), not that the service is
                        // off; HOST_REFUSED means the live host itself
                        // refused the view (addView threw / returned
                        // false — see the thread note above).
                        val host = LoomAccessibilityService.instance
                        return if (host == null || !host.isTrustedHostAvailable()) {
                            ShowPointerFailure.NO_HOST
                        } else {
                            ShowPointerFailure.HOST_REFUSED
                        }
                    }

                    override fun showPointerIdle(): Boolean = try {
                        interaction?.showPointerIdle() ?: false
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "LoomPointer",
                            "showPointerNowDetailed: showPointerIdle threw: ${e.javaClass.simpleName}",
                        )
                        false
                    }

                    override fun idleFailureReason(): ShowPointerFailure =
                        if (interaction == null) {
                            // Real, not hypothetical: onCreate sets
                            // `instance = this` (onCreate, line 442) BEFORE
                            // `interaction = buildInteractionLayer()`
                            // (line 485), so early polls during a cold
                            // start see a live instance with a null
                            // interaction. The caller's retry loop
                            // retries past it; a persistent NO_INTERACTION
                            // means onCreate never finished building
                            // the layer (or teardown already nulled it).
                            ShowPointerFailure.NO_INTERACTION
                        } else {
                            ShowPointerFailure.GATE_REFUSED
                        }
                },
                onFailure = { failure ->
                    android.util.Log.w(
                        "LoomPointer",
                        "showPointerNowDetailed: step failed: $failure",
                    )
                },
            )
            if (!result.shown) {
                android.util.Log.w(
                    "LoomPointer",
                    "showPointerNowDetailed: not shown: failure=${result.failure}",
                )
            }
            if (result.shown) {
                // Automatic recovery (Nov 6, W1 fix): a verified manual
                // Show-pointer success means LOOM is back — the
                // failure count starts fresh, so the next unexpected
                // stop gets its full 3 attempts instead of giving up
                // at once after an earlier give-up. Only written when
                // the count is non-zero; a verified show with nothing
                // to reset writes nothing. Covers this seam's other
                // caller too (chat's showPointerNow()).
                try {
                    val store = AutomaticRecoveryStore.get(this@LoomOverlayService)
                    if (store.failureCount() > 0) AutomaticRecovery.noteRecovered(store)
                } catch (_: Exception) {
                }
            }
            // Verify-time size summary: what the renderer looked like
            // immediately after the seam's own checks (see the
            // verify-time note above — 0x0 here is expected for a
            // just-added renderer, not a failure).
            guidanceRenderer?.let { raw ->
                android.util.Log.d(
                    "LoomPointer",
                    "showPointerNowDetailed: done shown=${result.shown} " +
                        "failure=${result.failure} rendererAttached=${raw.isAttachedToWindow} " +
                        "size=${raw.width}x${raw.height}",
                )
            }
            result
        } catch (e: Exception) {
            // Unreachable through the guarded adapter above; kept so a
            // failure here can never surface as a crash or a claim.
            android.util.Log.w(
                "LoomPointer",
                "showPointerNowDetailed threw — reporting not shown: ${e.javaClass.simpleName}",
            )
            ShowPointerResult(shown = false, failure = ShowPointerFailure.GATE_REFUSED)
        }
    }

    private inner class PolicyGatedRenderer(
        private val inner: GuidanceRendererContract,
    ) : GuidanceRendererContract {
        /**
         * Pointer-drive fix (2026-11-06): the last HIDE swallow, in
         * plain words — surfaced through [lastDrawFailure] so the plan
         * outcome can name it ("the pointer is hidden by your pointer
         * setting") instead of silently running pointer-blind. Cleared
         * by the next allowed draw (the pointer is showing again).
         */
        @Volatile
        private var lastSwallow: String? = null

        override fun lastDrawFailure(): String? = lastSwallow

        private fun mayDraw(): Boolean {
            val allowed = try {
                when (PointerPolicyStore.get(this@LoomOverlayService)) {
                    PointerPolicy.SHOW -> true
                    PointerPolicy.HIDE -> false
                    PointerPolicy.AUTO -> true
                }
            } catch (_: Exception) {
                true
            }
            if (allowed) lastSwallow = null
            return allowed
        }

        /**
         * Device-evidence fix (2026-11-05, pointer H2): a swallowed draw
         * is LOGGED every time (the old code dropped it silently, so
         * "says it's guiding + silence + no visuals" was indistinguishable
         * from a working session) and SPOKEN once per minute at most —
         * David must hear that his own setting is hiding the pointer,
         * without being nagged on every step.
         */
        private var lastSwallowSpokenMs: Long = 0L

        private fun onSwallowed(method: String) {
            lastSwallow = "the pointer is hidden by your pointer setting"
            android.util.Log.w(
                "LoomPointer",
                "PolicyGatedRenderer: $method swallowed — pointer policy is HIDE",
            )
            val now = SystemClock.uptimeMillis()
            if (now - lastSwallowSpokenMs < 60_000L) return
            lastSwallowSpokenMs = now
            floatingVoice?.speakNow(
                "The pointer is hidden by your pointer setting — " +
                    "say 'show the pointer' to turn it back on.",
            )
        }

        override fun showStep(
            anchorX: Float,
            anchorY: Float,
            target: RectF,
            stepIndex: Int,
            stepCount: Int,
            instruction: String,
        ) {
            if (mayDraw()) inner.showStep(anchorX, anchorY, target, stepIndex, stepCount, instruction)
            else onSwallowed("showStep")
        }

        override fun hide() = inner.hide()

        override fun previewMoveTo(target: RectF) {
            if (mayDraw()) inner.previewMoveTo(target)
            else onSwallowed("previewMoveTo")
        }

        override fun pulseOnResult() {
            if (mayDraw()) inner.pulseOnResult()
            else onSwallowed("pulseOnResult")
        }

        override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
            if (mayDraw()) inner.onAnchorChanged(newAnchorX, newAnchorY)
            else onSwallowed("onAnchorChanged")
        }

        // "fix everything": policy-gated like showStep — a HIDE policy
        // must not let a move draw the pointer. False yields the honest
        // "isn't visible" report, never a claimed move.
        override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean =
            if (mayDraw()) inner.nudgePointer(direction, stepDp) else {
                onSwallowed("nudgePointer")
                false
            }

        // Device-evidence fix (2026-11-05, pointer H3): the idle
        // indicator is gated like every other draw — HIDE must hide it
        // too, and the refusal is logged, never silently claimed.
        override fun showIdleIndicator(): Boolean =
            if (mayDraw()) inner.showIdleIndicator() else {
                onSwallowed("showIdleIndicator")
                false
            }

        // POINTER FEW-CURSOR SPEC DELTAS + MOUSE-VERBS (2026-11-05):
        // the new draw calls are gated like showStep — HIDE must hide
        // the ripple / press state / narration / selection too, and a
        // swallowed draw is logged, never silently claimed. The cursor
        // queries are read-only facts, NOT draws: they delegate ungated,
        // so verify-before-dispatch can still read the position when the
        // pointer is shown. Under HIDE the inner cursor is null (the draw
        // was swallowed) and the session falls back to tree verification
        // — HIDE never disables automation.
        override fun showTapRipple() {
            if (mayDraw()) inner.showTapRipple() else onSwallowed("showTapRipple")
        }

        override fun showPressState(holdMs: Long) {
            if (mayDraw()) inner.showPressState(holdMs) else onSwallowed("showPressState")
        }

        override fun setStatusNarration(text: String) {
            if (mayDraw()) inner.setStatusNarration(text) else onSwallowed("setStatusNarration")
        }

        override fun showSelection(target: RectF, label: String) {
            if (mayDraw()) inner.showSelection(target, label) else onSwallowed("showSelection")
        }

        // POINTER CHOREOGRAPHY FOR SEARCH/TYPE (2026-11-05): the parked
        // pointer is a draw like showStep — gated the same way.
        override fun parkPointer(target: RectF, label: String) {
            if (mayDraw()) inner.parkPointer(target, label) else onSwallowed("parkPointer")
        }

        override fun clearParked() {
            if (mayDraw()) inner.clearParked() else onSwallowed("clearParked")
        }

        override fun cursorPosition(): GeomPoint? = inner.cursorPosition()

        override fun isCursorOver(target: RectF, tolerancePx: Float): Boolean =
            inner.isCursorOver(target, tolerancePx)
    }

    /**
     * Stage 2C (A2): the guidance window is topmost by contract, not
     * insertion luck. TYPE_ACCESSIBILITY_OVERLAY already sits above
     * application overlays (2B D2); this remove + re-add makes it the newest
     * accessibility overlay after every transition. Runs only mid-sequence —
     * the view instance (and its in-memory step state) survives the re-add.
     */
    private fun relayerGuidanceTopmost() {
        val layer = interaction ?: return
        if (!layer.isSequenceActive) return
        val renderer = guidanceRenderer ?: return
        val host = LoomAccessibilityService.instance ?: return
        if (!host.isTrustedHostAvailable()) return
        try {
            host.removeGuidanceOverlayView(renderer)
            if (!host.addGuidanceOverlayView(renderer)) {
                android.util.Log.w(
                    "LoomPointer",
                    "relayerGuidanceTopmost: re-add refused — dropping cached renderer",
                )
                guidanceRenderer = null
                guidanceRendererHost = null
                gatedRenderer = null
                layer.renderer = null
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "LoomPointer",
                "relayerGuidanceTopmost threw: ${e.javaClass.simpleName} — dropping cached renderer",
            )
            guidanceRenderer = null
            guidanceRendererHost = null
            gatedRenderer = null
            layer.renderer = null
        }
    }

    // ---------- Stage 2C (A4/C3/D2): guidance + demonstration sessions ----------

    /**
     * Host side of "guide me to <x>" (via InteractionLayer.onGuidanceRequested).
     * Display-only and tokenless — the session resolves the target against
     * the live tree (TOCTOU-safe, B1–B4); visuals go through the trusted
     * overlay host. The tap path stays behind the AuthGate: tapDispatcher is
     * the queue's bridge — the ONLY bridge (no module cycle).
     */
    private fun startGuidanceSession(query: String) {
        lastGuideQuery = query
        val renderer = ensureGatedRenderer()
        if (renderer == null) {
            // Trusted host unavailable (a11y service off): no visual
            // fallback window, no fake highlight — the session itself would
            // report "Screen reading is not enabled"; say it out loud here
            // since there is no surface to show it on.
            //
            // Binding survival (2026-11-06): this spoken message must
            // NEVER send David to Settings with nothing to tap — that
            // is exactly what he reported ("there's no such button
            // inside the Loom app"). Voice has no button channel, so
            // the message routes him to the one surface that has the
            // button: Home → Show pointer, which carries the link
            // and the exact toggle steps.
            floatingVoice?.speakNow(
                "I can't show the pointer right now — the helper isn't " +
                    "running. Open LOOM, tap Show pointer on Home, and " +
                    "I'll walk you through turning it back on.",
            )
            interaction?.endSequence()
            return
        }
        val session = buildGuidanceSession(
            listOf(),
            renderer,
            // MASTER F6a — consume the pending origin the voice
            // session noted before the layer call, then reset to
            // MODEL so a later session without a note is safe.
            origin = pendingGuideOrigin,
        )
        pendingGuideOrigin = RequestOrigin.MODEL
        try {
            guidance?.end()
        } catch (_: Exception) {
        }
        guidance = session
        session.start(query)
        syncStopWindow()
    }

    /**
     * Start a pointer-guidance session from any mode (multi-step form).
     * Legacy direct-host path — the voice path ("guide me to <x>") goes
     * through the interaction layer instead. The beam anchor follows the
     * current mode via the AnchorResolver.
     */
    fun startGuidance(
        steps: List<GuidanceSession.GuideStep>,
        origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    ): GuidanceSession.StepResult {
        val renderer = ensureGatedRenderer()
        val session = buildGuidanceSession(steps, renderer, origin = origin)
        try {
            guidance?.end()
        } catch (_: Exception) {
        }
        guidance = session
        val result = session.start()
        syncStopWindow()
        return result
    }

    /**
     * Shared session builder: the injected renderer (layer-driven path),
     * the resolver anchor, and the queue's tap bridge as the ONLY tap
     * path (C3). When the trusted host is unavailable there is no
     * renderer — the session then reports honestly instead of drawing.
     */
    private fun buildGuidanceSession(
        steps: List<GuidanceSession.GuideStep>,
        renderer: GuidanceRendererContract?,
        /**
         * MASTER F6a — provenance of this session (DIRECT_USER for
         * David's own command, MODEL for a model-proposed one).
         * Layer-mediated sessions pass the consumed pending origin;
         * the plan path passes the plan's origin; the legacy direct
         * path keeps the DIRECT_USER default.
         */
        origin: RequestOrigin = RequestOrigin.DIRECT_USER,
    ): GuidanceSession {
        val session = GuidanceSession(
            steps = steps,
            tree = DefaultGuidanceTreeSource,
            renderer = renderer,
            // Device-evidence fix (2026-11-05, pointer H6): the session
            // re-resolves the gated renderer on EVERY step — the one
            // injected at construction goes stale when the host rebinds
            // or the window is re-layered mid-guidance.
            rendererProvider = { ensureGatedRenderer() },
            origin = origin,
            // Device-test failures (2026-11-11, D-08): LOOM's own
            // package — on the own chat screen the transcript echoes
            // David's words, and the pointer parked on the echo
            // instead of the app. The session filters TEXT-role
            // matches out of resolution on that screen.
            ownPackageName = packageName,
            // MASTER F6a — the foreground package's display label,
            // resolved via the app catalogue (never "" while the
            // package is known): that is what makes "Always allow"
            // renderable and stored grants applicable on the guidance
            // path. The catalogue is cached after first load; a
            // lookup failure falls back to the package name inside
            // the session.
            appLabelProvider = { pkg ->
                try {
                    AppCatalogueHolder.getOrCreate(this@LoomOverlayService)
                        .allApps().firstOrNull { it.packageName == pkg }?.label
                } catch (_: Exception) {
                    null
                }
            },
            anchorProvider = { anchorPair() },
        )
        // (e): the lambda is the ONLY bridge between the session's tap path
        // and the gated sink — no module cycle, no second tap route.
        val queue = ensureActionQueue()
        if (queue != null) {
            session.tapDispatcher = queue.asTapDispatcher()
            // POINTER MOUSE-VERBS (2026-11-05): the long-press bridge —
            // same queue, same `tap.guidance` key, hold carried through.
            session.longPressDispatcher = queue.asLongPressDispatcher()
        }
        // POINTER MOUSE-VERBS (2026-11-05): pointer-directed scroll —
        // the session moves the pointer to the scroll target and
        // narrates; this lambda is the ONLY scroll sink (`device.scroll`).
        session.scrollDispatcher = { forward, tokenId ->
            val svc = LoomAccessibilityService.instance
            if (svc == null) {
                false
            } else {
                when (
                    val r = try {
                        svc.scrollScreen(forward, tokenId)
                    } catch (e: Exception) {
                        null
                    }
                ) {
                    is GestureResult.Accepted -> true
                    else -> false
                }
            }
        }
        session.listener = object : GuidanceSession.Listener {
            override fun onStepShown(view: PointerGuideView, step: PointerGuideView.Step) {
                // Renderer-injected path: the policy gate lives in
                // PolicyGatedRenderer; this callback never fires there.
            }

            override fun onSessionEvent(result: GuidanceSession.StepResult) {
                when (result) {
                    is GuidanceSession.StepResult.Shown -> {
                        // Confirm AND perform (2026-11-10): a found
                        // target is spoken as found — the voice path
                        // used to speak only "Guiding you to X" before
                        // knowing whether the target was found or the
                        // pointer drew, and the outcome was never
                        // spoken at all. The not-found path below
                        // already speaks its reason.
                        val what = lastGuideQuery.takeIf { it.isNotBlank() }
                        val foundText = if (what != null) {
                            "Found it — the pointer is on \"$what\" now."
                        } else {
                            "Found it — the pointer is on it now."
                        }
                        floatingVoice?.speakNow(foundText)
                        // D-08: the outcome also lands in the chat
                        // thread when no live voice session speaks it
                        // (speakNow records it itself when live — see
                        // VoiceSessionManager.speakStatus — so this
                        // append runs only when the session is not
                        // live, never twice).
                        if (floatingVoice == null || !floatingVoice!!.isActive) {
                            appendToChat(foundText)
                        }
                    }
                    is GuidanceSession.StepResult.Finished -> {
                        // Hide via the LIVE cached view, not the
                        // construction-time renderer (H6: it may be stale).
                        guidanceRenderer?.hide()
                        interaction?.endSequence()
                    }
                    is GuidanceSession.StepResult.Unavailable -> {
                        guidanceRenderer?.hide()
                        // Device-evidence fix (2026-11-05, pointer H7):
                        // the reason used to travel only through the
                        // floating voice — if that session had ended, it
                        // vanished. Now it also goes to the event bus (an
                        // in-app surface can show it) and to logcat.
                        android.util.Log.w("LoomPointer", "guidance unavailable: ${result.reason}")
                        LoomEventBus.post(LoomStateEvent.Error(result.reason))
                        floatingVoice?.speakNow(result.reason)
                        // D-08: the reason also lands in the chat
                        // thread when no live voice session speaks it
                        // (speakNow records it itself when live).
                        if (floatingVoice == null || !floatingVoice!!.isActive) {
                            appendToChat(result.reason)
                        }
                        interaction?.endSequence()
                    }
                    else -> Unit
                }
                syncStopWindow()
            }
        }
        return session
    }

    /**
     * Stage 2C (D2): host side of "show me how to <x>" / "tap it for me"
     * (via InteractionLayer.onDemonstrationRequested). 2C builds the plan
     * deterministically from the ACTIVE guidance target (single-step TAP);
     * multi-step planning from a raw goal is 2D scope. The queue runs it:
     * demo.sequence card → per-step token → gated tap → choreography →
     * honest report.
     */
    private fun startDemonstrationSession(goal: String) {
        val layer = interaction
        val queue = ensureActionQueue()
        val renderer = ensureGatedRenderer()
        val query = lastGuideQuery
        val pkg = foregroundPackage()
        if (layer == null || queue == null || renderer == null ||
            query.isBlank() || pkg == null
        ) {
            floatingVoice?.speakNow("Guide me to something first, then say tap it for me.")
            layer?.endSequence()
            return
        }
        // The queue owns the renderer from here; drop the guidance session
        // without firing its Finished event (no endSequence — the
        // DEMONSTRATION state stays live under the halt flag).
        guidance = null
        val plan = ActionPlan(
            id = "demo-${SystemClock.uptimeMillis()}",
            goal = goal,
            demoAppPackage = pkg,
            steps = listOf(
                PlanStep(
                    id = "step-1",
                    targetQuery = query,
                    instruction = "Tap the guided target",
                ),
            ),
        )
        syncStopWindow()
        // MASTER F6b — consume the pending origin the voice session
        // noted before the layer call, then reset to MODEL. The
        // demo.sequence key itself stays non-grant-eligible; the
        // origin rides the per-step requests in the run.
        val demoOrigin = pendingDemoOrigin
        pendingDemoOrigin = RequestOrigin.MODEL
        queue.start(plan, origin = demoOrigin) { outcome ->
            mainHandler.post {
                renderer.hide()
                when (outcome) {
                    is SequenceOutcome.Completed ->
                        floatingVoice?.speakNow("Done.")
                    is SequenceOutcome.Interrupted ->
                        Unit // the halt path already reported (spoken "Stopped.")
                    is SequenceOutcome.AbortedByUser ->
                        floatingVoice?.speakNow("Okay — stopped.")
                    is SequenceOutcome.Failed ->
                        floatingVoice?.speakNow("That didn't work — ${outcome.reason}.")
                    SequenceOutcome.NeverStarted ->
                        Unit // the denied card is itself the report
                }
                layer.endSequence()
            }
        }
    }

    private fun foregroundPackage(): String? = try {
        ScreenAwarenessHolder.current.snapshot()?.packageName
    } catch (_: Exception) {
        null
    }

    private fun describeStepResult(result: GuidanceSession.StepResult?): String =
        when (result) {
            is GuidanceSession.StepResult.Shown ->
                "Step ${result.stepIndex} of ${result.stepCount}."
            is GuidanceSession.StepResult.Unavailable -> result.reason
            is GuidanceSession.StepResult.Finished -> "Guidance finished."
            null -> "No active guidance session."
        }

    fun guidanceNext(): GuidanceSession.StepResult? = guidance?.next()
    fun guidanceBack(): GuidanceSession.StepResult? = guidance?.back()
    fun endGuidance() {
        try {
            guidance?.end()
        } catch (_: Exception) {
        }
        guidance = null
        lastGuideQuery = ""
        gatedRenderer?.hide()
        interaction?.endSequence()
    }

    // ---------- Stage 2C (C2): the single gated demonstration-step runner ----------

    /**
     * The ActionQueue with the production seams. D's caveat #3 — VERIFIED
     * here: the strictness lambda reads the live persisted
     * LoomPreferences.confirmStrictness (the production value), so paranoid
     * mode escalates per-step confirmations; it is not a constant.
     */
    private fun ensureActionQueue(): ActionQueue? {
        actionQueue?.let { return it }
        val renderer = ensureGatedRenderer() ?: return null
        val gate = try {
            AuthGate.getOrNull() ?: AuthGate.init(this)
        } catch (_: Exception) {
            null
        } ?: return null
        val layer = interaction ?: return null
        val queue = ActionQueue(
            gate = gate,
            trees = object : TreeSource {
                override fun snapshot(): ScreenModel? = try {
                    ScreenAwarenessHolder.current.snapshot()
                } catch (_: Exception) {
                    null
                }

                override fun findElement(query: String): ScreenElement? = try {
                    ScreenAwarenessHolder.current.findElement(query)
                } catch (_: Exception) {
                    null
                }
            },
            dispatcher = GestureDispatch { element, tokenId ->
                val svc = LoomAccessibilityService.instance
                    ?: return@GestureDispatch DispatchAttempt.Rejected(
                        "accessibility service not bound",
                    )
                when (
                    val r = try {
                        svc.requestTap(element, tokenId)
                    } catch (e: Exception) {
                        LoomAccessibilityService.TapResult.Failed(e.message ?: "tap threw")
                    }
                ) {
                    is LoomAccessibilityService.TapResult.Dispatched ->
                        DispatchAttempt.Accepted
                    is LoomAccessibilityService.TapResult.GateRequired ->
                        DispatchAttempt.Rejected("gate: ${r.reason}")
                    is LoomAccessibilityService.TapResult.Failed ->
                        DispatchAttempt.Rejected(r.reason)
                }
            },
            // POINTER MOUSE-VERBS (2026-11-05): LONG_PRESS steps route
            // here — same gate key as a tap, hold carried through.
            longPressDispatcher = LongPressDispatch { element, tokenId, holdMs ->
                val svc = LoomAccessibilityService.instance
                    ?: return@LongPressDispatch DispatchAttempt.Rejected(
                        "accessibility service not bound",
                    )
                when (
                    val r = try {
                        svc.requestLongPress(element, tokenId, holdMs)
                    } catch (e: Exception) {
                        LoomAccessibilityService.TapResult.Failed(e.message ?: "long-press threw")
                    }
                ) {
                    is LoomAccessibilityService.TapResult.Dispatched ->
                        DispatchAttempt.Accepted
                    is LoomAccessibilityService.TapResult.GateRequired ->
                        DispatchAttempt.Rejected("gate: ${r.reason}")
                    is LoomAccessibilityService.TapResult.Failed ->
                        DispatchAttempt.Rejected(r.reason)
                }
            },
            completions = busCompletionSource(),
            renderer = renderer,
            feedback = executionFeedback,
            halt = object : HaltProvider {
                override fun isHalted(): Boolean = interaction?.isHaltRequested() == true
                override fun haltSource(): String? = interaction?.lastHaltSource?.name
            },
            anchors = AnchorProvider { anchorPair() },
            transitions = TransitionGate { onSettled ->
                // F4: never dispatch while the beam anchor is mid-transition
                // (~300ms after a verified mode change).
                val elapsed = SystemClock.uptimeMillis() - lastModeChangeMs
                val remaining = AnchorResolver.TRANSITION_MS - elapsed
                if (remaining <= 0) onSettled()
                else mainHandler.postDelayed({ onSettled() }, remaining)
            },
            previewDelayer = PreviewDelayer.delayed(),
            strictness = {
                try {
                    LoomPreferences.get(this).confirmStrictness
                } catch (_: Exception) {
                    0
                }
            },
            audit = AuditLogger { kind, detail ->
                try {
                    ActivityLog.get(this).record(kind, detail)
                } catch (_: Exception) {
                }
            },
            bus = EventSink { event -> LoomEventBus.post(event) },
        )
        actionQueue = queue
        return queue
    }

    // ---------- Stage 2C (§5.3): the separate touchable stop window ----------

    /**
     * Shown while the layer reports a sequence active OR a plan run
     * is executing (AOIE Phase 5: before this a plan could never be
     * stopped from the window, because a plan is not a sequence).
     */
    private fun syncStopWindow() {
        if (interaction?.isSequenceActive == true ||
            com.loom.assistant.core.common.PlanRunState.isPlanRunning
        ) showStopWindow()
        else hideStopWindow()
    }

    private fun showStopWindow() {
        if (stopWindow != null) return
        if (!OverlayPermission.granted(this)) return
        val size = dp(56)
        val view = StopWindow(this)
        view.onTap = {
            // AOIE Phase 5: the window tap always sets the shared
            // stop — it stops a plan run as well as a sequence.
            com.loom.assistant.core.common.PlanStopRegistry.requestStop()
            val layer = interaction
            if (layer != null && layer.isSequenceActive) {
                layer.halt(HaltSource.STOP_WINDOW)
                floatingVoice?.speakNow("Stopped.")
            } else if (com.loom.assistant.core.common.PlanRunState.isPlanRunning) {
                floatingVoice?.speakNow("Stopped.")
            }
            syncStopWindow()
        }
        // Default: bottom-right above the nav bar; stored-then-clamped.
        val dm = resources.displayMetrics
        val start = FloatingPositionStore.restore(
            this, wm, FloatingPositionStore.OverlayId.STOP_WINDOW,
            size, size,
            dm.widthPixels - size - dp(24),
            dm.heightPixels - size - dp(120),
        )
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = start.x
            y = start.y
        }
        // Drag-to-move with the tap preserved: past the touch slop it drags
        // (position persisted on release); otherwise the tap reaches onTap.
        var downX = 0f
        var downY = 0f
        var dragging = false
        val slop = dp(8).toFloat()
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.rawX - downX) > slop ||
                            kotlin.math.abs(event.rawY - downY) > slop)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (params.x + (event.rawX - downX)).toInt()
                        params.y = (params.y + (event.rawY - downY)).toInt()
                        downX = event.rawX
                        downY = event.rawY
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        savePositionDebounced(FloatingPositionStore.OverlayId.STOP_WINDOW, params)
                        true
                    } else false
                }
                else -> false
            }
        }
        stopWindow = view
        // The trusted type is preferred; the application-overlay fallback
        // keeps the stop path alive where the trusted type is refused (the
        // E device test decides which one survives on HyperOS).
        val added = tryAddStopWindow(view, params, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) ||
            tryAddStopWindow(view, params, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        if (!added) {
            stopWindow = null
            android.util.Log.w("LoomOverlay", "showStopWindow: both window types refused")
        }
    }

    private fun tryAddStopWindow(
        view: StopWindow,
        params: WindowManager.LayoutParams,
        type: Int,
    ): Boolean {
        params.type = type
        return try {
            wm.addView(view, params)
            true
        } catch (_: Exception) {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun hideStopWindow() {
        val view = stopWindow ?: return
        stopWindow = null
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    // ---------- foreground plumbing ----------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "LOOM overlay", NotificationManager.IMPORTANCE_MIN,
                    ).apply { setShowBadge(false) },
                )
            }
        }
    }

    /**
     * Foreground notification, built from the Android-free
     * [overlayNotificationSpec] (§4.2-D/F, closure decision 1, mismatch #5).
     *
     * - ACTIVE: content intent opens the app + the 2C sequence Stop action.
     * - HIDDEN: kept with the SHOW ASSISTANT action (restores views only).
     * - DEACTIVATED: status visibility ONLY — no actions AND no content
     *   intent, so no notification path can reactivate LOOM or bypass
     *   launcher-icon-only reactivation (§4.4).
     */
    private fun buildNotification(variant: OverlayNotificationVariant): Notification {
        ensureChannel()
        val spec = overlayNotificationSpec(variant)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
        if (spec.hasContentIntent) {
            val open = PendingIntent.getActivity(
                this, 0,
                Intent().setClassName(packageName, LoomIntents.MAIN_ACTIVITY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(open)
        }
        for (action in spec.actions) {
            when (action) {
                is OverlayNotificationAction.HaltSequence -> {
                    // Stage 2C (§5.2 P1): the independent Stop action — a
                    // PendingIntent straight back to this service; handled in
                    // onStartCommand. Works even if the pointer overlay, mode
                    // views and voice pipeline are all dead: the notification
                    // is owned by the OS.
                    val stop = PendingIntent.getService(
                        this, 7,
                        Intent(this, LoomOverlayService::class.java)
                            .setAction(LoomIntents.ACTION_HALT_SEQUENCE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    builder.addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stop,
                    )
                }
                is OverlayNotificationAction.ShowAssistant -> {
                    val show = PendingIntent.getService(
                        this, 8,
                        Intent(this, LoomOverlayService::class.java)
                            .setAction(ACTION_SHOW_ASSISTANT),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    builder.addAction(
                        android.R.drawable.ic_menu_view,
                        "Show assistant",
                        show,
                    )
                }
            }
        }
        return builder.build()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
