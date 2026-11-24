package com.loom.assistant.presentation.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.core.common.AssistantActivation
import com.loom.assistant.core.common.CapabilityBridge
import com.loom.assistant.core.common.ChatInputResolution
import com.loom.assistant.core.common.SharedConversationContext
import com.loom.assistant.core.common.TaskRecord
import com.loom.assistant.core.common.TaskRecordStatus
import com.loom.assistant.core.common.LoomCapability
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.core.common.PointerShowGate
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.VoiceCommands
import com.loom.assistant.execution.devicecontrol.DeviceControlCenter
import com.loom.assistant.execution.interaction.InteractionLayerRegistry
import com.loom.assistant.core.common.MediaControlPhrases
import com.loom.assistant.execution.screenawareness.MediaControlRouter
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenDescriber
import com.loom.assistant.intelligence.llm.LlmMessage
import com.loom.assistant.intelligence.llm.ModelSelection
import com.loom.assistant.intelligence.llm.ResponseEngine
import com.loom.assistant.intelligence.llm.resolveEngine
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.TaskStatus
import com.loom.assistant.intelligence.memory.ChatMessage
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.interaction.pointer.PointerPolicy
import com.loom.assistant.interaction.pointer.PointerPolicyStore
import com.loom.assistant.interaction.voice.DeviceVoicePipeline
import com.loom.assistant.interaction.voice.VoicePipeline
import com.loom.assistant.interaction.voice.VoiceSessionManager
import com.loom.assistant.interaction.voice.VoiceSessionState
import com.loom.assistant.platform.services.AssistantMover
import com.loom.assistant.platform.services.LoomAppLauncher
import com.loom.assistant.platform.services.LoomOverlayService
import com.loom.assistant.platform.services.LoomPlanRunner
import com.loom.assistant.platform.services.LoomSearchRunner
import com.loom.assistant.platform.services.LoomShutdown
import com.loom.assistant.core.common.VoiceCommand
import com.loom.assistant.core.common.VoiceSessionOwner
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.ModelPickerDialog
import com.loom.assistant.presentation.designsystem.R
import com.loom.assistant.presentation.home.OrbView
import com.loom.assistant.presentation.modes.AssistantMode
import com.loom.assistant.presentation.modes.ModeController
import com.loom.assistant.presentation.modes.ModeResult
import com.loom.assistant.presentation.panels.LoomDialogs
import java.io.File
import java.io.FileOutputStream

/** Turn watchdog bound — see [ChatScreenView.scheduleTurnWatchdog]. */
private const val TURN_TIMEOUT_MS = 300_000L

/**
 * Full-screen Chat/Voice — lockdown cards 4, 5, 6.
 * Text + voice share ONE conversation thread (the store's active thread):
 * switching modes never restarts or disconnects it.
 *
 * @param startMode "text" | "voice" | "switching"
 */
class ChatScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
    private val requestMicPermission: ((Boolean) -> Unit) -> Unit,
    private val pickImage: (((Uri?) -> Unit)) -> Unit,
    startMode: String = "text",
) : FrameLayout(activity), VoiceSessionManager.Host {

    private val store = ConversationStore.get(activity)
    private val voice: VoicePipeline = DeviceVoicePipeline.get(activity)
    private val models = ModelSelection(activity)
    // Explicit engine control — Local or Cloud per the user's choice, with the
    // active conversation's recent history for real cloud conversations.
    private val engine: ResponseEngine by lazy {
        resolveEngine(activity, models) {
            store.getActive().messages.takeLast(20)
                .map { LlmMessage(it.role, it.text) }
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var messageList: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText
    private lateinit var statusText: TextView
    private lateinit var modelButton: TextView
    private lateinit var voiceOverlay: FrameLayout
    private lateinit var voiceOrb: OrbView
    private lateinit var voiceStateText: TextView
    private lateinit var transcriptionText: TextView
    private lateinit var switchingBanner: TextView
    private lateinit var muteLabel: TextView
    private lateinit var voiceSession: VoiceSessionManager

    private var streaming = false
    private var voiceMode = false
    private var streamIndex = 0

    /**
     * Blank-reply fix (2026-11-06): the turn watchdog. The engine
     * guarantees onDone for Exception-class failures, but nothing
     * guarantees it for an Error-class thread death or a future engine
     * that drops the callback — and while [streaming] is true every
     * later send is silently dropped. The watchdog bounds the stuck
     * state: 300s covers the worst live cloud call (3 attempts ×
     * (15s connect + 60s read) + backoff + chunk pacing ≈ 260s), so
     * it never fires on a call that is still working — it only fires
     * when the turn is genuinely lost, posts the fallback, and frees
     * the input for the next message.
     */
    private var turnWatchdog: Runnable? = null

    private fun scheduleTurnWatchdog() {
        cancelTurnWatchdog()
        val w = Runnable {
            if (streaming) {
                streaming = false
                Log.w("LoomChat", "chat turn timed out without a reply")
                assistantMessage(CHAT_FALLBACK_REPLY)
            }
        }
        turnWatchdog = w
        mainHandler.postDelayed(w, TURN_TIMEOUT_MS)
    }

    private fun cancelTurnWatchdog() {
        turnWatchdog?.let { mainHandler.removeCallbacks(it) }
        turnWatchdog = null
    }

    private fun helperState(): DeviceControlCenter = DeviceControlCenter(activity)

    /**
     * Master §2 (item 6): minimal conversational context — the app the
     * last launch/search used. A typed follow-up "search for X" with no
     * app resolves from here; an explicit app always wins. In-memory
     * only, never persisted.
     *
     * Device-test failures (2026-11-11, D-05): this screen used its
     * OWN ConversationContext, so a typed plan task was never recorded
     * anywhere — "execute the task" had nothing to resolve to. The
     * context is now the SHARED one (same as every VoiceSessionManager
     * instance), so typed and spoken tasks live in the same record.
     */
    private val conversationContext = SharedConversationContext.instance()

    private val busListener: (LoomStateEvent) -> Unit = { event ->
        // Stage 2A: while the voice session manager runs the session it owns
        // the in-app voice UI exclusively (via onSessionState). These branches
        // only apply when no session is active (e.g. typed-path events).
        if (!voiceSession.isActive) {
            when (event) {
                is LoomStateEvent.Listening -> setVoiceState(OrbView.OrbState.LISTENING, "Listening...")
                is LoomStateEvent.Thinking -> setVoiceState(OrbView.OrbState.THINKING, "Thinking...")
                is LoomStateEvent.Speaking -> setVoiceState(OrbView.OrbState.SPEAKING, "Speaking...")
                is LoomStateEvent.Completed, is LoomStateEvent.Idle ->
                    if (voiceMode) setVoiceState(OrbView.OrbState.IDLE, "Tap the mic to talk")
                is LoomStateEvent.Error -> if (voiceMode) {
                    transcriptionText.text = event.message
                    setVoiceState(OrbView.OrbState.IDLE, "Tap the mic to talk")
                }
                else -> {}
            }
        }
    }

    init {
        build()
        voiceSession = VoiceSessionManager(activity, voice, this)
        LoomEventBus.subscribe(busListener)
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                renderMessages()
                refreshHeader()
            }
            override fun onViewDetachedFromWindow(v: View) {
                LoomEventBus.unsubscribe(busListener)
                removeOnAttachStateChangeListener(this)
            }
        })
        when (startMode) {
            "voice" -> post { enterVoiceMode(autoStart = true) }
            "switching" -> post {
                showSwitchingBanner()
                enterTextMode()
            }
        }
    }

    // ---------- build ----------

    private fun build() {
        // Header.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LoomUi.dp(context, 12)
            setPadding(p, p, p, p)
        }
        val backBtn = TextView(context).apply {
            text = "‹"
            setTextAppearance(R.style.LoomText_Display)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            setPadding(LoomUi.dp(context, 8), 0, LoomUi.dp(context, 8), 0)
            setOnClickListener { navigate(LoomDestination.Conversations) }
        }
        header.addView(backBtn)
        val logoSize = LoomUi.dp(context, 32)
        header.addView(
            ImageView(context).apply { setImageResource(R.drawable.ic_loom_logo) },
            LinearLayout.LayoutParams(logoSize, logoSize),
        )
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.marginStart = LoomUi.dp(context, 8)
            layoutParams = p
        }
        titleCol.addView(LoomUi.text(context, "LOOM", R.style.LoomText_Headline))
        statusText = LoomUi.text(
            context, engine.engineLabel, R.style.LoomText_Caption, R.color.loom_text_muted,
        )
        titleCol.addView(statusText)
        header.addView(titleCol)
        modelButton = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Label)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            background = context.getDrawable(R.drawable.loom_header_pill)
            val h = LoomUi.dp(context, 12)
            val v = LoomUi.dp(context, 8)
            setPadding(h, v, h, v)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ModelPickerDialog.show(activity, models) { refreshHeader() }
            }
        }
        header.addView(modelButton)
        // (header is attached to the root layout at the end of build())

        // Switching banner (card 6) — hidden by default.
        switchingBanner = LoomUi.text(
            context, "Switching to text — conversation continues",
            R.style.LoomText_Label, R.color.loom_accent_bright, Gravity.CENTER,
        ).apply {
            background = context.getDrawable(R.drawable.loom_header_pill)
            visibility = GONE
            val v = LoomUi.dp(context, 8)
            setPadding(v, v, v, v)
        }

        // Messages.
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            switchingBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = LoomUi.dp(context, 4)
                bottomMargin = LoomUi.dp(context, 4)
            },
        )
        scroller = ScrollView(context).apply { isFillViewport = true }
        messageList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = LoomUi.dp(context, 16)
            setPadding(p, p, p, p)
        }
        scroller.addView(
            messageList,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        body.addView(
            scroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        // Input bar.
        val inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LoomUi.dp(context, 8)
            setPadding(p, p, p, p)
        }
        val attachBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            setColorFilter(context.getColor(R.color.loom_accent_bright))
            background = null
            contentDescription = "Attach image"
            setOnClickListener { onAttachTap() }
        }
        inputBar.addView(attachBtn, LinearLayout.LayoutParams(LoomUi.dp(context, 44), LoomUi.dp(context, 44)))
        input = EditText(context).apply {
            hint = "Message LOOM..."
            setHintTextColor(context.getColor(R.color.loom_text_muted))
            setTextColor(context.getColor(R.color.loom_text_primary))
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_2, 22)
            val h = LoomUi.dp(context, 14)
            val v = LoomUi.dp(context, 10)
            setPadding(h, v, h, v)
            setOnEditorActionListener { _, _, _ -> sendText(); true }
        }
        inputBar.addView(
            input,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = LoomUi.dp(context, 4)
                marginEnd = LoomUi.dp(context, 4)
            },
        )
        val micBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(context.getColor(R.color.loom_accent_bright))
            background = null
            contentDescription = "Voice mode"
            setOnClickListener { enterVoiceMode(autoStart = true) }
        }
        inputBar.addView(micBtn, LinearLayout.LayoutParams(LoomUi.dp(context, 44), LoomUi.dp(context, 44)))
        val sendBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(context.getColor(R.color.loom_silver))
            background = LoomUi.bubbleDrawable(context, R.color.loom_accent, 22)
            contentDescription = "Send"
            setOnClickListener { sendText() }
        }
        inputBar.addView(sendBtn, LinearLayout.LayoutParams(LoomUi.dp(context, 44), LoomUi.dp(context, 44)))
        body.addView(
            inputBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // (body is attached to contentWrap below)

        // Voice overlay (card 5) — built once, toggled.
        voiceOverlay = buildVoiceOverlay()
        voiceOverlay.visibility = GONE

        // Root: header on top, then body + voice overlay stacked.
        removeAllViews()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val contentWrap = FrameLayout(context)
        contentWrap.addView(
            body,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        contentWrap.addView(
            voiceOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            contentWrap,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        addView(
            root,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun buildVoiceOverlay(): FrameLayout {
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.loom_background))
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val spacer1 = View(context)
        col.addView(spacer1, LinearLayout.LayoutParams(1, 0, 1f))
        voiceOrb = OrbView(context)
        val orbSize = LoomUi.dp(context, 168)
        col.addView(voiceOrb, LinearLayout.LayoutParams(orbSize, orbSize))
        voiceStateText = LoomUi.text(context, "Tap the mic to talk", R.style.LoomText_Headline, gravity = Gravity.CENTER)
            .apply { setPadding(0, LoomUi.dp(context, 16), 0, 0) }
        col.addView(voiceStateText)
        transcriptionText = LoomUi.text(
            context, "", R.style.LoomText_Body, R.color.loom_text_secondary, Gravity.CENTER,
        ).apply {
            setPadding(LoomUi.dp(context, 24), LoomUi.dp(context, 12), LoomUi.dp(context, 24), 0)
        }
        col.addView(transcriptionText)
        val spacer2 = View(context)
        col.addView(spacer2, LinearLayout.LayoutParams(1, 0, 2f))

        // Controls: talk / keyboard / mute / end (lockdown card 5 + Stage 2A).
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, LoomUi.dp(context, 48))
        }
        // Stage 2A: the overlay used to say "tap the mic" with no mic to tap
        // (the input-bar mic sits underneath the overlay). This is the mic.
        val micBtn = controlButton("🎙", "Talk") {
            if (voiceSession.state == VoiceSessionState.PAUSED) voiceSession.resume()
            else voiceSession.start()
        }
        val keyboardBtn = controlButton("⌨", "Keyboard") {
            showSwitchingBanner()
            enterTextMode()
        }
        val muteBtn = controlButton("🔇", "Mute", onLabel = { muteLabel = it }) {
            val nowMuted = voiceSession.toggleMute()
            muteLabel.text = if (nowMuted) "Unmute" else "Mute"
        }
        val endBtn = controlButton("✕", "End") { enterTextMode() }
        controls.addView(micBtn)
        controls.addView(keyboardBtn)
        controls.addView(muteBtn)
        controls.addView(endBtn)
        col.addView(controls)

        overlay.addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return overlay
    }

    private fun controlButton(
        symbol: String,
        desc: String,
        onLabel: ((TextView) -> Unit)? = null,
        onTap: () -> Unit,
    ): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            val h = LoomUi.dp(context, 20)
            setPadding(h, 0, h, 0)
            contentDescription = desc
        }
        val circle = TextView(context).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.loom_silver))
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_3, 999)
            val s = LoomUi.dp(context, 56)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        col.addView(circle)
        val label = LoomUi.text(context, desc, R.style.LoomText_Caption, R.color.loom_text_muted, Gravity.CENTER)
        col.addView(label)
        onLabel?.invoke(label)
        col.setOnClickListener { onTap() }
        return col
    }

    // ---------- header ----------

    private fun refreshHeader() {
        modelButton.text = models.label
        statusText.text = engine.engineLabel
    }

    // ---------- messages ----------

    private fun renderMessages() {
        messageList.removeAllViews()
        val conv = store.getActive()
        if (conv.messages.isEmpty()) {
            messageList.addView(
                LoomUi.text(
                    context,
                    "Start the conversation — type below or tap the mic.",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ).apply { setPadding(0, LoomUi.dp(context, 32), 0, 0) },
            )
            return
        }
        for (m in conv.messages) {
            messageList.addView(bubbleFor(m))
        }
        scrollToBottom()
    }

    private fun bubbleFor(m: ChatMessage): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            p.bottomMargin = LoomUi.dp(context, 8)
            layoutParams = p
        }
        val isUser = m.role == "user"
        row.gravity = if (isUser) Gravity.END else Gravity.START
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = LoomUi.bubbleDrawable(
                context,
                if (isUser) R.color.loom_accent_soft else R.color.loom_surface_2,
            )
            val h = LoomUi.dp(context, 14)
            val v = LoomUi.dp(context, 10)
            setPadding(h, v, h, v)
        }
        if (m.modality == "image" && m.imagePath != null) {
            val thumb = ImageView(context)
            val file = File(m.imagePath)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) thumb.setImageBitmap(bmp)
            }
            val s = LoomUi.dp(context, 180)
            thumb.layoutParams = LinearLayout.LayoutParams(s, s).apply { gravity = Gravity.CENTER }
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            bubble.addView(thumb)
        }
        if (m.text.isNotBlank()) {
            bubble.addView(
                LoomUi.text(
                    context, m.text,
                    R.style.LoomText_Body,
                    if (isUser) R.color.loom_silver else R.color.loom_text_primary,
                ),
            )
        }
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(
            bubble,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = if (isUser) Gravity.END else Gravity.START },
        )
        // Blank-reply fix (2026-11-06, David Nov 6 09:24): a reply that
        // sends David to Settings carries the button to go there — "there's
        // no such button inside the Loom app" was the defect. The button
        // opens the system Accessibility settings page for LOOM directly
        // (the same intent Device Control uses). It renders from the
        // message TEXT, so it survives re-renders and restarts, and it
        // covers model prose too — any assistant reply that directs him
        // to Settings gets it, including the pointer-show failure ack,
        // which named Settings with nothing to tap until now.
        if (!isUser && replyNeedsSettingsButton(m.text)) {
            wrap.addView(
                TextView(context).apply {
                    text = "Open Accessibility settings"
                    setTextAppearance(R.style.LoomText_Label)
                    setTextColor(context.getColor(R.color.loom_silver))
                    gravity = Gravity.CENTER
                    background = LoomUi.bubbleDrawable(context, R.color.loom_accent, 22)
                    val h = LoomUi.dp(context, 16)
                    val v = LoomUi.dp(context, 10)
                    setPadding(h, v, h, v)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Open Accessibility settings"
                    setOnClickListener {
                        activity.startActivity(
                            DeviceControlCenter(activity).accessibilitySettingsIntent(),
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.START
                    topMargin = LoomUi.dp(context, 8)
                },
            )
        }
        row.addView(
            wrap,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
            },
        )
        // spacer pushes the bubble to its side
        val spacer = View(context)
        if (isUser) row.addView(spacer, 0, LinearLayout.LayoutParams(0, 1, 0.15f))
        else row.addView(spacer, LinearLayout.LayoutParams(0, 1, 0.15f))
        return row
    }

    private fun scrollToBottom() {
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- text send ----------

    private fun sendText() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || streaming) return
        input.text.clear()
        val conv = store.getActive()
        store.append(conv.id, ChatMessage("user", text, System.currentTimeMillis()))
        renderMessages()
        // Device-test failures (2026-11-11, D-05): "the task" refers
        // to the task already given — resolve it here, before the
        // router sends it to the engine (the engine has no record
        // and answers "I don't have a task from you yet"). A
        // completed task is named as done, never re-run; a failed or
        // unfinished one is resumed with its own origin, so a
        // model-proposed task still goes through its cover card.
        if (VoiceCommands.isTaskReference(text)) {
            val referenced = conversationContext.referencedTask()
            if (referenced != null) {
                if (referenced.status == TaskRecordStatus.COMPLETED) {
                    assistantMessage(
                        "That task is already done: ${referenced.instruction}.",
                    )
                } else {
                    conversationContext.startTask(referenced)
                    val request = PlanRequest(
                        goal = referenced.instruction,
                        origin = referenced.origin,
                        transparency = false,
                        contextApp = conversationContext.appForFollowUpSearch(),
                        taskRecord = referenced,
                    )
                    onPlanRequested(request)
                }
                return
            }
        }
        // Capability-API fix: resolve typed input through the command router
        // BEFORE any engine call — matched commands and clarifications never
        // reach the model, so it cannot deny the capability or roleplay it.
        when (val resolution = CapabilityBridge.resolveChatInput(text)) {
            is ChatInputResolution.ExecuteCommand ->
                dispatchChatCommand(resolution.cmd, text, null)
            is ChatInputResolution.Clarification ->
                assistantMessage(resolution.text)
            is ChatInputResolution.SendToEngine -> generateReply(text)
        }
    }

    /** Append an assistant message to the shared thread and re-render. */
    private fun assistantMessage(text: String) {
        val conv = store.getActive()
        store.append(conv.id, ChatMessage("assistant", text, System.currentTimeMillis()))
        renderMessages()
    }

    /**
     * Chat-side dispatch table for commands resolved from typed input and
     * for model-requested capability actions. Execution goes only through
     * existing paths: confirmShutdown(), enterTextMode(), PointerPolicyStore
     * and the overlay start. Voice-session/floating-scoped commands are
     * never executed from chat — they get a truthful routing message naming
     * the voice phrase, never a denial that the capability exists.
     * [modelArg] is the action block's arg when the dispatch comes from a
     * model reply; typed input extracts its target/goal from [userText].
     * [modelApp] is the action block's app="..." attribute (SEARCH_IN_APP).
     */
    private fun dispatchChatCommand(
        cmd: VoiceCommand,
        userText: String,
        modelArg: String?,
        modelApp: String? = null,
    ) {
        when (cmd) {
            VoiceCommand.SHUTDOWN -> shutdownImmediately()
            // Device-test failures (2026-11-11, D-04): "confirm your
            // screen" describes the live screen from Screen
            // Awareness — never a denial that the command exists.
            VoiceCommand.DESCRIBE_SCREEN -> assistantMessage(describeCurrentScreen())
            VoiceCommand.EXIT_VOICE, VoiceCommand.SWITCH_KEYBOARD -> enterTextMode()
            VoiceCommand.POINTER_SHOW -> {
                // Ack gating (accessibility fix 2026-11-05, audit §7.1):
                // the confirmation comes ONLY from a verified render,
                // never from the policy write. showPointerNow() persists
                // SHOW itself, so there is no separate set() here — and
                // the ack is posted from the render result, never
                // optimistically (see requestPointerShow).
                requestPointerShow()
            }
            VoiceCommand.POINTER_HIDE,
            VoiceCommand.POINTER_AUTO -> {
                // HIDE/AUTO have nothing to render, so the persisted
                // read-back is the confirmation — as before.
                val policy = if (cmd == VoiceCommand.POINTER_HIDE) {
                    PointerPolicy.HIDE
                } else {
                    PointerPolicy.AUTO
                }
                val result = PointerPolicyStore.set(activity, policy)
                assistantMessage(pointerPolicyAck(policy, result))
            }
            VoiceCommand.HALT_SEQUENCE -> assistantMessage("Nothing is running.")
            VoiceCommand.GUIDE_TO -> {
                // In-app routing (fix plan item 5, pointer H4): typed
                // GUIDE_TO actually starts guidance through the shared
                // interaction layer — the old branch only started the
                // overlay service and claimed "Guidance is available…"
                // while starting nothing. No layer yet means the
                // floating assistant isn't running: start it and say so
                // honestly instead of claiming guidance started.
                val target = modelArg?.takeIf { it.isNotBlank() }
                    ?: VoiceCommands.extractGuideTarget(userText).takeIf { it.isNotBlank() }
                val layer = InteractionLayerRegistry.get()
                when {
                    layer == null -> {
                        LoomOverlayService.start(activity)
                        assistantMessage(
                            "Guidance isn't running yet — I've started the floating assistant. " +
                                "Once it's up, type 'guide me to ${target ?: "…"}' here again " +
                                "or say it by voice.",
                        )
                    }
                    target == null -> assistantMessage("Guide you to what?")
                    else -> {
                        layer.startGuidance(target)
                        // D-08: intent only — the outcome ("Found it"
                        // / the reason it could not be found) arrives
                        // from the guidance session itself. The old
                        // "Guiding you to X." read as a done claim
                        // before anything had happened.
                        assistantMessage(
                            "Looking for $target — I'll move the pointer " +
                                "to it and tell you where it is.",
                        )
                    }
                }
            }
            VoiceCommand.GUIDE_NEXT -> {
                LoomOverlayService.start(activity)
                assistantMessage(
                    "I've started the floating assistant — once it's up, " +
                        "say 'guide next' by voice to move to the next step.",
                )
            }
            VoiceCommand.GUIDE_BACK -> {
                LoomOverlayService.start(activity)
                assistantMessage(
                    "I've started the floating assistant — once it's up, " +
                        "say 'guide back' by voice to go to the previous step.",
                )
            }
            VoiceCommand.DEMO_TAP -> {
                LoomOverlayService.start(activity)
                assistantMessage(
                    "I've started the floating assistant — once it's up, " +
                        "say 'tap it for me' by voice during guidance and it will " +
                        "demonstrate the tap after your confirmation.",
                )
            }
            VoiceCommand.DEMO_SEQUENCE -> {
                val goal = modelArg?.takeIf { it.isNotBlank() }
                    ?: VoiceCommands.extractDemoGoal(userText).takeIf { it.isNotBlank() }
                LoomOverlayService.start(activity)
                assistantMessage(
                    if (goal != null) {
                        "I've started the floating assistant — once it's up, " +
                            "say 'show me how to $goal' by voice and it will demonstrate it."
                    } else {
                        "I've started the floating assistant — once it's up, " +
                            "say 'show me how to …' by voice and it will demonstrate it."
                    },
                )
            }
            // ACTIVATE ASSISTANT MODE — typed-chat edition of the voice
            // handler: same planner, same verify-then-ack, but the ack
            // comes from the ModeResult callback via assistantMessage
            // (typed chat has no mic — never speakStatus). Unlike
            // SHOW_ASSISTANT, this is NOT a "say it by voice" routing
            // message: starting the floating assistant works from chat.
            // LoomOverlayService.start is the already-imported pattern
            // (as used by GUIDE_TO/DEMO_* above); applyMode's cold-start
            // would also start the service, so the start call is belt
            // and braces, and the ack still comes ONLY from ModeResult.
            VoiceCommand.ACTIVATE_ASSISTANT_MODE -> {
                val plan = AssistantActivation.plan(
                    lifecycleActive = LifecycleController.isDispatchAllowed(),
                    overlayPermissionGranted = OverlayPermission.granted(activity),
                    serviceRunning = ModeController.isOverlayRunning(activity) ||
                        ModeController.boundMode() != null,
                    lastBoundMode = ModeController.boundMode()?.name
                        ?: ModeController.getMode(activity).name,
                )
                when (plan) {
                    is AssistantActivation.ActivationPlan.Refused ->
                        assistantMessage(plan.reason)
                    is AssistantActivation.ActivationPlan.AlreadyRunning -> {
                        val mode = ModeController.boundMode()
                            ?: ModeController.getMode(activity)
                        applyActivationMode(mode)
                    }
                    is AssistantActivation.ActivationPlan.StartService -> {
                        LoomOverlayService.start(activity)
                        val mode = try {
                            AssistantMode.valueOf(plan.modeName)
                        } catch (_: IllegalArgumentException) {
                            AssistantMode.ORB
                        }
                        applyActivationMode(mode)
                    }
                }
            }
            // Device-evidence fix (2026-11-05): typed mode commands
            // execute directly via ModeController — the old "say it by
            // voice" routing is what let the LLM answer "to invisible
            // mode" with Discord's invisible status. Ack comes ONLY from
            // the verified ModeResult, exactly like voice mode switches.
            VoiceCommand.MODE_ORB, VoiceCommand.MODE_CHARACTER,
            VoiceCommand.MODE_INVISIBLE -> {
                val mode = when (cmd) {
                    VoiceCommand.MODE_ORB -> AssistantMode.ORB
                    VoiceCommand.MODE_CHARACTER -> AssistantMode.CHARACTER
                    else -> AssistantMode.INVISIBLE
                }
                ModeController.applyMode(activity, mode) { result ->
                    assistantMessage(
                        ModeController.ackText(result, ModeController.shortLabel(mode)),
                    )
                }
            }
            // "fix everything": pointer movement from typed chat hands
            // off to the voice session (the pointer only exists during
            // voice guidance) — same honesty standard as guide/demo:
            // never claims a move, names the exact voice phrase.
            VoiceCommand.POINTER_MOVE -> {
                val dir = VoiceCommands.extractPointerDirection(userText)
                LoomOverlayService.start(activity)
                assistantMessage(
                    "I've started the floating assistant — once it's up, " +
                        "say 'move the pointer ${dir?.label ?: "left"}' by voice and it will move.",
                )
            }
            // Device-evidence fix (2026-11-05): typed launch requests go
            // to the real AuthGate-gated launch path, never the LLM.
            // Master §2 (item 6): a launch sets the last-turn context,
            // so a follow-up "search for X" resolves its app from here.
            VoiceCommand.LAUNCH_APP -> {
                val app = modelArg?.takeIf { it.isNotBlank() }
                    ?: VoiceCommands.extractLaunchApp(userText).takeIf { it.isNotBlank() }
                if (app != null) {
                    conversationContext.rememberLaunch(app)
                    // Master §8 — provenance: a model arg means the model
                    // proposed this launch (MODEL origin: always a card);
                    // typed input with no model arg is David's own command.
                    val origin = if (modelArg != null) RequestOrigin.MODEL
                        else RequestOrigin.DIRECT_USER
                    LoomAppLauncher.launch(
                        activity, app,
                        onResult = { assistantMessage(it) },
                        origin = origin,
                    )
                } else {
                    assistantMessage("Which app do you want me to open?")
                }
            }
            // MASTER F4e — typed search runs through the same plan
            // path as voice (blueprint §9.1: voice and text create
            // the same structured task request), via this screen's
            // own onPlanRequested (LoomPlanRunner) — no
            // floating-assistant handoff, no "say it by voice".
            // Provenance, same as LAUNCH_APP/PLAN_TASK above: a model
            // arg means the model proposed it (MODEL origin: always
            // a card); typed input with no model arg is David's own
            // command. The question branches (no query / no app) are
            // unchanged. A typed follow-up with no app resolves it
            // from the last-turn context first.
            VoiceCommand.SEARCH_IN_APP -> {
                // Device-failure fix (2026-11-13, F2): the typed
                // goal is first formed here — a model-proposed
                // query/app is stripped of meta-speech before the
                // goal string exists (typed extraction already cuts
                // via VoiceCommands).
                val query = modelArg?.let { VoiceCommands.stripMetaSpeech(it) }?.takeIf { it.isNotBlank() }
                    ?: VoiceCommands.extractSearchQuery(userText).takeIf { it.isNotBlank() }
                val app = modelApp?.let { VoiceCommands.stripMetaSpeech(it) }?.takeIf { it.isNotBlank() }
                    ?: VoiceCommands.extractSearchApp(
                        userText, conversationContext.appForFollowUpSearch(),
                    ).takeIf { it.isNotBlank() }
                when {
                    query == null ->
                        assistantMessage("What do you want me to search for?")
                    app == null ->
                        assistantMessage("Which app should I search in?")
                    else -> {
                        conversationContext.rememberSearch(app)
                        val origin = if (modelArg != null) RequestOrigin.MODEL
                            else RequestOrigin.DIRECT_USER
                        // D-05: the typed search is RECORDED here, in
                        // the shared context, and carried on the
                        // request — so "execute the task" later
                        // resolves to it.
                        val record = TaskRecord(
                            instruction = "search for $query in $app",
                            app = app,
                            appPackage = null,
                            query = query,
                            origin = origin,
                        )
                        conversationContext.startTask(record)
                        val request = PlanRequest(
                            goal = "search for $query in $app",
                            origin = origin,
                            transparency = VoiceCommands.extractTransparency(userText),
                            contextApp = conversationContext.appForFollowUpSearch(),
                            taskRecord = record,
                        )
                        onPlanRequested(request)
                    }
                }
            }
            // Master §2 (item 5): typed assistant-move executes directly
            // via Worker 5's AssistantMover (same pattern as the typed
            // MODE_* switches above) — the ack comes ONLY from the
            // returned MoveResult, never a claim. AssistantRegion /
            // MoveResult are nested in the AssistantMover object (not
            // top-level types as the task stated). The mover backend is
            // registered by LoomOverlayService.onCreate; until then the
            // move fails honestly.
            VoiceCommand.ASSISTANT_MOVE -> {
                val regionName = modelArg?.takeIf { it.isNotBlank() }
                    ?: VoiceCommands.extractAssistantRegionName(userText)
                if (regionName == null) {
                    assistantMessage(
                        "Where should I move — top-left, top-right, bottom-left, " +
                            "bottom-right, or out of the way?",
                    )
                } else {
                    val result = try {
                        AssistantMover.move(
                            AssistantMover.AssistantRegion.valueOf(regionName),
                        )
                    } catch (_: IllegalArgumentException) {
                        AssistantMover.MoveResult.Failed("unknown region '$regionName'")
                    }
                    assistantMessage(
                        when (result) {
                            AssistantMover.MoveResult.Ok -> "Moved the assistant ${assistantMoveLabel(regionName)}."
                            is AssistantMover.MoveResult.Failed -> "I couldn't move the assistant: ${result.reason}"
                        },
                    )
                }
            }
            // Task-record fix (2026-11-07): typed multi-step
            // instructions run through the same plan host as voice.
            // The planner report §5 claimed this routing, but the
            // table had no PLAN_TASK branch, so a typed composite
            // fell to the routing message and never ran at all.
            // Provenance, same as LAUNCH_APP above: a model arg means
            // the model proposed it (MODEL origin: always a card);
            // typed input with no model arg is David's own command.
            // The goal's app is remembered into the typed context so
            // a follow-up ("play music") resolves from it; the
            // planner builds the record from the goal.
            VoiceCommand.PLAN_TASK -> {
                val goal = modelArg?.takeIf { it.isNotBlank() }
                    ?: VoiceCommands.extractPlanGoal(userText).takeIf { it.isNotBlank() }
                val origin = if (modelArg != null) RequestOrigin.MODEL
                    else RequestOrigin.DIRECT_USER
                if (goal == null) {
                    assistantMessage("What should I do?")
                } else {
                    val app = VoiceCommands.extractPlanApp(goal)
                    // D-05: the typed task is RECORDED here, in the
                    // shared context, and carried on the request — so
                    // "execute the task" later resolves to it.
                    val record = TaskRecord(
                        instruction = goal,
                        app = app,
                        appPackage = null,
                        query = null,
                        origin = origin,
                    )
                    conversationContext.startTask(record)
                    val request = PlanRequest(
                        goal = goal,
                        origin = origin,
                        transparency = VoiceCommands.extractTransparency(userText),
                        contextApp = conversationContext.appForFollowUpSearch(),
                        taskRecord = record,
                    )
                    app.takeIf { it.isNotBlank() }?.let {
                        conversationContext.rememberLaunch(it)
                    }
                    onPlanRequested(request)
                }
            }
            // Session/state awareness (2026-11-11, OBSERVE-FIRST):
            // typed "pause" / "play" while a player control is on the
            // live screen is a media task, not a session command —
            // run it as a plan (the same PLAY_PAUSE plan voice uses),
            // with the typed text as the goal. With no player control
            // on screen, the truthful routing message, as before.
            VoiceCommand.PAUSE, VoiceCommand.RESUME -> {
                val control = if (cmd == VoiceCommand.PAUSE) "pause" else "play"
                if (mediaControlOnScreen(control)) {
                    val origin = if (modelArg != null) RequestOrigin.MODEL
                        else RequestOrigin.DIRECT_USER
                    val record = TaskRecord(
                        instruction = userText,
                        app = null,
                        appPackage = null,
                        query = null,
                        origin = origin,
                    )
                    conversationContext.startTask(record)
                    onPlanRequested(
                        PlanRequest(
                            goal = userText,
                            origin = origin,
                            transparency = VoiceCommands.extractTransparency(userText),
                            contextApp = conversationContext.appForFollowUpSearch(),
                            taskRecord = record,
                        ),
                    )
                } else {
                    assistantMessage(voiceRoutingMessage(cmd))
                }
            }
            else -> assistantMessage(voiceRoutingMessage(cmd))
        }
    }

    /** Typed-chat activation: ack ONLY from the verified ModeResult. */
    private fun applyActivationMode(mode: AssistantMode) {
        ModeController.applyMode(activity, mode) { result ->
            when (result) {
                ModeResult.Success -> assistantMessage(
                    AssistantActivation.successAck(ModeController.shortLabel(mode)),
                )
                ModeResult.PermissionRequired -> assistantMessage(
                    AssistantActivation.permissionRefusedMessage(),
                )
                is ModeResult.Failed -> assistantMessage(
                    AssistantActivation.failureAck(result.reason),
                )
                is ModeResult.Unavailable -> assistantMessage(
                    AssistantActivation.failureAck(
                        "LOOM's floating assistant isn't available right now.",
                    ),
                )
            }
        }
    }

    /**
     * Ack for the policy-only pointer commands (HIDE/AUTO) — there is
     * nothing to render for those, so the persisted read-back is the
     * confirmation. SHOW never comes here: it is gated on the verified
     * render in [requestPointerShow] ([PointerShowGate]). The SHOW arm
     * below exists only for exhaustiveness and must stay unreachable.
     * Same ack wording as VoiceSessionManager.pointerPolicyAck.
     */
    private fun pointerPolicyAck(
        policy: PointerPolicy,
        result: PointerPolicyStore.SetResult,
    ): String {
        val label = PointerPolicyStore.label(policy)
        return when (result) {
            is PointerPolicyStore.SetResult.Applied -> when (policy) {
                PointerPolicy.HIDE -> "The pointer is now hidden."
                PointerPolicy.AUTO -> "The pointer is now automatic — shown during guidance."
                // Unreachable: SHOW is gated on the verified render, not
                // the policy write. If it ever arrives, say nothing
                // rendered rather than claim visibility.
                else -> PointerShowGate.NOT_RENDERED_ACK
            }
            is PointerPolicyStore.SetResult.Unchanged ->
                "The pointer is already $label."
            is PointerPolicyStore.SetResult.Failed ->
                PointerShowGate.SAVE_FAILED_ACK
        }
    }

    /** Guards requestPointerShow(): a second tap while an attempt is in
     *  flight is dropped — the in-flight attempt posts the one ack. */
    @Volatile
    private var pointerShowAttemptRunning = false

    /**
     * Accessibility fix (2026-11-05, audit §7.1 — words are not
     * action): typed "show the pointer" verifies the render before
     * saying anything. The overlay-service start is async, so the
     * first render attempt can land before the service exists: retry
     * the verified-render seam briefly on a worker thread (the Home
     * one-tap pattern) and post the ack ONLY from the result, on the
     * main thread — a plain confirmation on a verified render, one
     * plain sentence with the remedy when nothing rendered. Never
     * "I've requested", never "done" without verification. The seam
     * ([LoomOverlayService.showPointerNow]) returns true ONLY on a
     * verified render; false (including service-not-running) means
     * "not rendered", never a claim.
     */
    private fun requestPointerShow() {
        if (pointerShowAttemptRunning) return
        pointerShowAttemptRunning = true
        LoomOverlayService.start(activity)
        Thread {
            var rendered = false
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (LoomOverlayService.showPointerNow()) {
                        rendered = true
                        break
                    }
                } catch (_: Exception) {
                    break
                }
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
            }
            val ack = PointerShowGate.ack(rendered)
            mainHandler.post {
                pointerShowAttemptRunning = false
                assistantMessage(ack)
            }
        }.start()
    }

    /** Human label for an AssistantRegion enum name, for move acks. */
    private fun assistantMoveLabel(regionName: String): String = when (regionName) {
        "TOP_LEFT" -> "to the top-left corner"
        "TOP_RIGHT" -> "to the top-right corner"
        "BOTTOM_LEFT" -> "to the bottom-left corner"
        "BOTTOM_RIGHT" -> "to the bottom-right corner"
        "LEFT_EDGE" -> "to the left edge"
        "RIGHT_EDGE" -> "to the right edge"
        "UP" -> "up a bit"
        "DOWN" -> "down a bit"
        "LEFT" -> "left a bit"
        "RIGHT" -> "right a bit"
        "OUT_OF_WAY" -> "out of the way"
        else -> "there"
    }

    /** Truthful routing message for voice-session/floating-scoped commands. */
    private fun voiceRoutingMessage(cmd: VoiceCommand): String {
        val phrase = when (cmd) {
            // MODE_* no longer routes here: typed mode commands execute
            // directly (device-evidence fix 2026-11-05).
            VoiceCommand.PAUSE -> "pause"
            VoiceCommand.RESUME -> "resume"
            VoiceCommand.STOP -> "stop talking"
            VoiceCommand.STOP_LISTENING -> "stop listening"
            VoiceCommand.START_LISTENING -> "start listening"
            VoiceCommand.GO_SILENT -> "go quiet"
            VoiceCommand.SPEAK_AGAIN -> "speak again"
            VoiceCommand.HIDE_YOURSELF -> "hide yourself"
            VoiceCommand.SHOW_ASSISTANT -> "show yourself"
            VoiceCommand.DEACTIVATE_ASSISTANT_MODE -> "deactivate assistant mode"
            VoiceCommand.END_VOICE -> "shut down voice"
            else -> null
        }
        return if (phrase != null) {
            "That only works by voice — say '$phrase' out loud. " +
                "It can't run from typed chat."
        } else {
            "That can't run from typed chat — try it by voice."
        }
    }

    private fun generateReply(userText: String) {
        streaming = true
        streamIndex = 0
        scheduleTurnWatchdog()
        LoomEventBus.post(LoomStateEvent.Thinking)
        val conv = store.getActive()
        val acc = StringBuilder()
        // Placeholder bubble that fills in as chunks stream.
        val liveBubble = liveAssistantBubble()
        engine.generate(
            userText, conv.messages.size,
            onChunk = { chunk ->
                val i = streamIndex++
                mainHandler.postDelayed(
                    {
                        acc.append(chunk)
                        liveBubble.text = acc.toString()
                        scrollToBottom()
                    },
                    (i * 45).toLong(),
                )
            },
            onDone = { reply ->
                val delay = (streamIndex * 45).toLong() + 60
                mainHandler.postDelayed(
                    {
                        cancelTurnWatchdog()
                        // Blank-reply fix (2026-11-06): the turn resolves
                        // through resolveChatTurn — an action-only reply
                        // whose block failed validation stores the plain
                        // fallback sentence, never a blank bubble; a
                        // helper question gains the live state and the
                        // concrete steps (with the Settings button under
                        // it). The dispatch runs in its own try/catch:
                        // a throw inside a command used to kill the UI
                        // thread from this handler and strand the turn;
                        // now it posts the fallback instead. The outer
                        // catch is the last resort: the turn always
                        // resolves and the input never stays locked.
                        var turn: ChatTurnResolution? = null
                        try {
                            val raw = acc.toString().ifBlank { reply }
                            turn = resolveChatTurn(raw)
                            if (turn is ChatTurnResolution.StoreText) {
                                val center = helperState()
                                val text = groundHelperAnswer(
                                    userText, turn.text,
                                    center.isAccessibilityEnabled(),
                                    center.isServiceBound(),
                                )
                                store.append(
                                    conv.id,
                                    ChatMessage("assistant", text, System.currentTimeMillis()),
                                )
                            }
                            streaming = false
                            LoomEventBus.post(LoomStateEvent.Completed)
                            renderMessages()
                        } catch (e: Exception) {
                            Log.w("LoomChat", "chat turn failed: ${e.javaClass.simpleName}")
                            streaming = false
                            turn = null
                            try {
                                assistantMessage(CHAT_FALLBACK_REPLY)
                            } catch (_: Exception) {
                            }
                            try {
                                renderMessages()
                            } catch (_: Exception) {
                            }
                        }
                        // Capability-API fix: strip model action blocks from
                        // the stored reply, then dispatch a parsed capability
                        // through the same table as typed commands — its
                        // ack/handoff message is the execution notice.
                        // Lifecycle blocks never parse, so no new path opens.
                        // Accessibility fix (2026-11-05, audit §7.1): the
                        // model's prose in an action turn is never a
                        // confirmation — under the new prompt it says
                        // nothing about the action, and any action-talk it
                        // does emit ("I've requested…") must never reach
                        // the thread. The dispatch ack below is the turn's
                        // only message; the ack is stored by dispatch, so
                        // the turn is still recorded. (Voice keeps storing
                        // the prose — its acks are spoken, not stored, so
                        // dropping it there would lose the turn record.)
                        (turn as? ChatTurnResolution.DispatchAction)?.action?.let { action ->
                            try {
                                val modelApp =
                                    (action.capability as? LoomCapability.SearchInApp)?.appName
                                dispatchChatCommand(
                                    action.command, userText, action.argument, modelApp,
                                )
                            } catch (e: Exception) {
                                Log.w("LoomChat", "chat command failed: ${e.javaClass.simpleName}")
                                assistantMessage(CHAT_FALLBACK_REPLY)
                            }
                        }
                    },
                    delay,
                )
            },
            // Master §2 (item 2): the grounded fallback — generateReply
            // only runs for input the matcher did not resolve, so the
            // engine gets the capability context PLUS the
            // pick-an-action-or-say-you-cannot instruction for this
            // utterance. The model selects; it never executes.
            systemContext = CapabilityBridge.fallbackSystemContext(userText),
        )
    }

    private fun liveAssistantBubble(): TextView {
        val tv = LoomUi.text(context, "Thinking…", R.style.LoomText_Body)
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_2)
            val h = LoomUi.dp(context, 14)
            val v = LoomUi.dp(context, 10)
            setPadding(h, v, h, v)
        }
        bubble.addView(tv)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            bubble,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f),
        )
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 0.15f))
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        p.bottomMargin = LoomUi.dp(context, 8)
        row.layoutParams = p
        messageList.addView(row)
        scrollToBottom()
        return tv
    }

    // ---------- voice mode (Stage 2A: driven by VoiceSessionManager) ----------

    private fun enterVoiceMode(autoStart: Boolean) {
        voiceMode = true
        voiceOverlay.visibility = VISIBLE
        transcriptionText.text = ""
        if (::muteLabel.isInitialized) muteLabel.text = "Mute"
        if (autoStart) voiceSession.start()
    }

    private fun enterTextMode() {
        voiceMode = false
        voiceSession.end()
        voiceOverlay.visibility = GONE
        renderMessages()
    }

    private fun showSwitchingBanner() {
        switchingBanner.visibility = VISIBLE
        mainHandler.postDelayed({ switchingBanner.visibility = GONE }, 2200)
    }

    private fun setVoiceState(state: OrbView.OrbState, label: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setVoiceState(state, label) }
            return
        }
        if (::voiceOrb.isInitialized) {
            voiceOrb.orbState = state
            voiceStateText.text = label
        }
    }

    private fun startVoiceListening() {
        // Stage 2A: replaced by VoiceSessionManager — kept as a thin alias so
        // no caller breaks; the manager owns permission + recognizer startup.
        voiceSession.start()
    }

    // ---------- VoiceSessionManager.Host ----------

    override fun provideEngine(): ResponseEngine = engine
    override fun provideStore(): ConversationStore = store
    override fun historyWindow(): Int = 20

    override fun checkMicPermission(onResult: (Boolean) -> Unit) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
            return
        }
        requestMicPermission { granted ->
            if (!granted) transcriptionText.text = "Microphone permission needed for voice."
            onResult(granted)
        }
    }

    override fun idlePrompt(): String = "Tap the mic to talk"

    override fun onSessionState(state: VoiceSessionState, statusText: String) {
        // Stage 2A.1 (D5/R6): update the ownership flag BEFORE the voiceMode
        // guard. enterTextMode() sets voiceMode=false before voiceSession.end(),
        // and the async IDLE callback must still clear the flag — otherwise it
        // sticks TRUE forever and floating voice degrades to opening the app.
        // Phase 2 (E.4): atomic claim/release — a claim that loses the race
        // simply fails instead of stealing ownership.
        if (state != VoiceSessionState.IDLE && state != VoiceSessionState.ENDING) {
            VoiceSessionOwner.claimInApp()
        } else {
            VoiceSessionOwner.releaseInApp()
        }
        if (!voiceMode) return
        val orb = when (state) {
            VoiceSessionState.IDLE, VoiceSessionState.ENDING -> OrbView.OrbState.IDLE
            VoiceSessionState.LISTENING -> OrbView.OrbState.LISTENING
            VoiceSessionState.PROCESSING -> OrbView.OrbState.THINKING
            VoiceSessionState.SPEAKING -> OrbView.OrbState.SPEAKING
            VoiceSessionState.PAUSED -> OrbView.OrbState.PAUSED
            VoiceSessionState.ERROR -> OrbView.OrbState.ERROR
        }
        setVoiceState(orb, statusText)
        if (state == VoiceSessionState.ERROR) transcriptionText.text = statusText
    }

    override fun onPartialText(text: String) {
        if (voiceMode) transcriptionText.text = "\u201C$text\u201D"
    }

    override fun onTurnFinished() {
        renderMessages()
    }

    override fun onCommandAction(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.SHUTDOWN -> shutdownImmediately()
            // Stage 2A.1 (D2): "switch to keyboard" takes the existing
            // text-mode path — the shared conversation thread is preserved.
            VoiceCommand.EXIT_VOICE, VoiceCommand.SWITCH_KEYBOARD -> enterTextMode()
            else -> { /* PAUSE/RESUME/STOP/STOP_LISTENING/END_VOICE/START_LISTENING/MODE_* handled in the manager */ }
        }
    }

    // Device-evidence fix (2026-11-05): the in-app voice session's
    // launch path — same shared launcher as typed chat. The manager
    // forwards LAUNCH_APP here (it cannot import platform:services).
    // Master §8: the origin rides through to the gate.
    override fun onLaunchRequested(appName: String) {
        onLaunchRequested(appName, RequestOrigin.MODEL)
    }

    override fun onLaunchRequested(appName: String, origin: RequestOrigin) {
        LoomAppLauncher.launch(
            activity, appName,
            onResult = { assistantMessage(it) },
            origin = origin,
        )
    }

    // In-app routing (fix plan item 5, search H2): in-app voice search
    // runs the same executor path as the floating host, via the shared
    // LoomSearchRunner — the manager's "can't search from this session"
    // refusal only fires when this returns false, which it never does.
    // The ack is spoken through the live in-app session; if the session
    // ended while the search ran, it lands in the chat thread instead of
    // vanishing (the floating host's voice-only ack-loss edge).
    override fun onSearchRequested(
        appName: String,
        query: String,
        origin: RequestOrigin,
    ): Boolean {
        return LoomSearchRunner.runSearch(activity, appName, query, origin) { ack ->
            mainHandler.post {
                if (::voiceSession.isInitialized && voiceSession.isActive) {
                    voiceSession.speakNow(ack)
                } else {
                    assistantMessage(ack)
                }
            }
        }
    }

    // Stage 2D: in-app plans run through the same executor as the
    // floating host, via the shared LoomPlanRunner — no separate
    // in-app execution path. The ack is spoken through the live
    // in-app session AND always lands in the chat thread (simple-task
    // execution fix, 2026-11-07: the old code spoke OR appended —
    // a spoken-only outcome vanished from the thread when the
    // session was live, so David could not re-read it afterwards).
    // The In-progress status is appended as soon as the plan
    // starts, so the run is visible in the thread from the first
    // moment; the terminal outcome arrives via onAck below.
    // Overrides VoiceSessionManager.Host.onPlanRequested (Worker C
    // added the interface method + handlePlanTask routing).
    override fun onPlanRequested(request: PlanRequest): Boolean {
        return LoomPlanRunner.runPlan(activity, request, onAck = { ack ->
            mainHandler.post {
                // D-05: the terminal outcome rides onto the shared
                // record — a later "the task" reference can say it
                // is done, or resume it by name when it failed.
                conversationContext.noteTaskOutcome(ack.startsWith("Done —"))
                if (::voiceSession.isInitialized && voiceSession.isActive) {
                    voiceSession.speakNow(ack)
                }
                assistantMessage(ack)
            }
        }, onStatus = { update ->
            // MASTER Wave 4 (F12): the in-progress and waiting
            // updates are durable here; the terminal outcome is
            // recorded once via onAck below (appending it here too
            // would duplicate the same summary line in the chat).
            mainHandler.post {
                if (update.status == TaskStatus.IN_PROGRESS ||
                    update.status == TaskStatus.WAITING_FOR_USER_INPUT
                ) {
                    assistantMessage(update.message)
                }
            }
        }, helperDownReason = {
            // MASTER Wave 4 (F10): the helper-down gate, same as the
            // floating and Home hosts — a dead helper fails honestly
            // in the chat instead of running a plan that cannot act.
            val center = DeviceControlCenter(activity)
            val refusal = center.helperDownRefusal()
            if (refusal != null) {
                center.helperDownCause()?.let { center.recordHelperDown(it) }
            }
            refusal
        })
    }

    // Device-test failures (2026-11-11, D-04): "confirm your screen"
    // reads the live screen through Screen Awareness and describes
    // it — which app, what is on it, where the named things are. A
    // screen that cannot be read says so with the exact reason,
    // never a denial that the command exists.
    override fun describeCurrentScreen(): String =
        ScreenDescriber.describe(ScreenAwarenessHolder.current.read())

    // Session/state awareness (2026-11-11, OBSERVE-FIRST): a Pause
    // / Play control on the live screen means the command is about
    // the media on screen, not LOOM's own session.
    override fun mediaControlOnScreen(control: String): Boolean {
        val screen = ScreenAwarenessHolder.current.snapshot() ?: return false
        val want = MediaControlPhrases.match(control) ?: return false
        return MediaControlRouter.mediaControlOnScreen(screen, want)
    }

    // Stage 2A.1 (D1): the in-app voice session reaches mode switches through
    // ModeController.applyMode via the manager — this screen no longer
    // carries its own switch path (the old LoomOverlayService.applyMode).

    // Stage 2A.1 (D5): never leak a live session (and its mic ownership)
    // across a config change or navigation — end it on detach.
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (::voiceSession.isInitialized && voiceSession.isActive) voiceSession.end()
    }

    /**
     * Device-failure fix (2026-11-13, David's binding rule Nov 13
     * ~03:36): a SHUTDOWN *command* (voice or typed) executes
     * IMMEDIATELY — no dialog, no question, no cancel path. The
     * 12-step teardown, the intentional-stop write and the
     * reactivation path inside [LoomShutdown.shutdown] are
     * unchanged; only the confirmation is gone. The deliberate
     * on-screen shutdown BUTTON (Home) keeps its dialog — see
     * [confirmShutdown]; that button path is a separate question
     * for David and is deliberately NOT changed here.
     */
    private fun shutdownImmediately() {
        voiceSession.end()
        LoomShutdown.shutdown(activity) { voice.shutdown() }
    }

    private fun confirmShutdown() {
        LoomDialogs.confirm(
            activity,
            "Shut down LOOM?",
            "Voice listening stops, the floating assistant goes away, and " +
                "LOOM stops. Tap the LOOM icon to start again. " +
                "Note: LOOM's helper is listed separately in Settings — " +
                "turn it off there if you turned it on.",
            "Shut down",
        ) {
            voiceSession.end()
            LoomShutdown.shutdown(activity) { voice.shutdown() }
        }
    }

    // ---------- image attach ----------

    private fun onAttachTap() {
        pickImage { uri ->
            if (uri == null) return@pickImage
            val path = copyImageToPrivate(uri) ?: return@pickImage
            val conv = store.getActive()
            store.append(
                conv.id,
                ChatMessage(
                    role = "user",
                    text = "",
                    timestamp = System.currentTimeMillis(),
                    modality = "image",
                    imagePath = path,
                ),
            )
            renderMessages()
            generateReply("[image]")
        }
    }

    private fun copyImageToPrivate(uri: Uri): String? {
        return try {
            val imagesDir = File(context.filesDir, "loom_images").apply { mkdirs() }
            val dest = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
