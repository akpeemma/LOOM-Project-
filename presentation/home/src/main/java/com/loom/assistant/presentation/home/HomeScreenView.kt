package com.loom.assistant.presentation.home

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomIntents
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.MediaControlPhrases
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.devicecontrol.DeviceControlCenter
import com.loom.assistant.execution.screenawareness.MediaControlRouter
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.intelligence.authgate.PseudoControlPolicy
import com.loom.assistant.intelligence.authgate.PseudoControlStore
import com.loom.assistant.intelligence.memory.ChatMessage
import com.loom.assistant.intelligence.llm.EngineMode
import com.loom.assistant.intelligence.llm.LlmMessage
import com.loom.assistant.intelligence.llm.ModelSelection
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.TaskStatus
import com.loom.assistant.intelligence.llm.ResponseEngine
import com.loom.assistant.intelligence.llm.resolveEngine
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.interaction.voice.DeviceVoicePipeline
import com.loom.assistant.interaction.voice.VoicePipeline
import com.loom.assistant.interaction.voice.VoiceSessionManager
import com.loom.assistant.interaction.voice.VoiceSessionState
import com.loom.assistant.platform.services.LoomAccessibilityService
import com.loom.assistant.platform.services.LoomOverlayService
import com.loom.assistant.platform.services.LoomPlanRunner
import com.loom.assistant.platform.services.LoomSearchRunner
import com.loom.assistant.platform.services.LoomShutdown
import com.loom.assistant.platform.services.ShowPointerFailure
import com.loom.assistant.platform.services.AutomaticRecoveryStore
import com.loom.assistant.core.common.VoiceCommand
import com.loom.assistant.core.common.VoiceSessionOwner
import com.loom.assistant.lifecycle.AutomaticRecovery
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.ModelPickerDialog
import com.loom.assistant.presentation.designsystem.R
import com.loom.assistant.presentation.panels.LoomDialogs

/**
 * Home screen — lockdown cards 1–3.
 * Logo, tagline, greeting card, Tap to Chat, quick actions, recents.
 * The voice orb starts a REAL voice session on Home (quick voice):
 * tap → listen → think → speak, all on this screen.
 */
class HomeScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
    private val requestMicPermission: ((Boolean) -> Unit) -> Unit,
) : ScrollView(activity), VoiceSessionManager.Host {

    private val store = ConversationStore.get(activity)
    private val voice: VoicePipeline = DeviceVoicePipeline.get(activity)
    private val models = ModelSelection(activity)
    // Explicit engine control: Local or Cloud per the user's choice —
    // never an automatic substitution. Re-resolved per screen instance.
    private val engine: ResponseEngine by lazy {
        resolveEngine(activity, models) {
            store.getActive().messages.takeLast(8)
                .map { LlmMessage(it.role, it.text) }
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var orb: OrbView
    private lateinit var voiceStatus: TextView
    private lateinit var engineStatus: TextView
    private lateinit var engineCardTitle: TextView
    private lateinit var engineCardSub: TextView
    private lateinit var recentsBox: LinearLayout
    private lateinit var modelActionLabel: TextView
    /** Accessibility fix (Nov 5): one-tap pointer controls on Home. */
    private lateinit var showPointerBtn: TextView
    private lateinit var pointerStatus: TextView
    private lateinit var pointerSettingsLink: TextView
    /** Binding survival (Nov 6): exact toggle steps, shown with the settings link. */
    private lateinit var pointerSteps: TextView
    private var pointerAttemptRunning = false
    /**
     * Binding survival (Nov 6): re-checks left for this attach — Home
     * re-reads liveness twice after opening (1 s, 3 s) so a system
     * rebind that lands just after the app starts clears the "helper
     * is off" hint by itself instead of waiting for a tap.
     */
    private var bindingRechecksLeft = 0
    /**
     * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT): the
     * grant store (the SAME store the gate and the grant screen
     * read — one policy, no parallel system) and the home-screen
     * entry's status line + one-time prompt box.
     */
    private val pseudoStore: PseudoControlStore by lazy { PseudoControlStore.get(activity) }
    private lateinit var pseudoStatus: TextView
    private lateinit var pseudoPromptBox: LinearLayout
    /** Stage 2A: the conversational voice session. Replaces the one-shot orb tap. */
    private lateinit var voiceSession: VoiceSessionManager

    private val busListener: (LoomStateEvent) -> Unit = { event ->
        // Automatic recovery (Nov 6): a fresh report is waiting in the
        // store — re-read the status line. Checked first: it is not a
        // voice event and must land even mid-session.
        if (event is LoomStateEvent.RecoveryReported) {
            if (!pointerAttemptRunning) refreshPointerHint()
        } else if (!voiceSession.isActive) {
            // Stage 2A: while the session manager runs, it owns the voice UI
            // (via onSessionState). These branches cover the idle typed path.
            when (event) {
                is LoomStateEvent.Listening -> setVoiceUi(OrbView.OrbState.LISTENING, "Listening...")
                is LoomStateEvent.Thinking -> setVoiceUi(OrbView.OrbState.THINKING, "Thinking...")
                is LoomStateEvent.Speaking -> setVoiceUi(OrbView.OrbState.SPEAKING, "Speaking...")
                is LoomStateEvent.Completed, is LoomStateEvent.Idle ->
                    setVoiceUi(OrbView.OrbState.IDLE, "Tap the orb to talk")
                is LoomStateEvent.Error -> {
                    setVoiceUi(OrbView.OrbState.IDLE, event.message)
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
                refreshEngineCard()
                refreshRecents()
                // Binding survival (Nov 6): arm the two delayed
                // liveness re-checks for this attach only — the
                // system rebind may land just after the app opens.
                bindingRechecksLeft = 2
                refreshPointerHint()
            }
            override fun onViewDetachedFromWindow(v: View) {
                LoomEventBus.unsubscribe(busListener)
                removeOnAttachStateChangeListener(this)
            }
        })
    }

    /** Lockdown route home/quick-voice lands here with the session autostarted. */
    fun startQuickVoice() {
        post { onOrbTap() }
    }

    // ---------- build ----------

    private fun build() {
        val root = LoomUi.screenRoot(context)
        val inner = LoomUi.innerOf(root)
        LoomUi.addCenteredPill(inner, "Home Screen")

        // Compact AI selector (Stage 1): provider · model · connection status ·
        // Local/Cloud mode. Same ModelSelection state as Settings → AI Connections.
        inner.addView(
            buildEngineCard(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = LoomUi.dp(context, 4)
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_16)
            },
        )

        // Show pointer — one tap from Home (accessibility fix, Nov 5).
        // Directly under the engine card, same full-width primary pattern
        // as "Shut down LOOM" below. No command to know, no digging:
        // one tap starts the floating assistant and asks for a verified
        // render; the result is shown here in plain words.
        showPointerBtn = primaryButton("Show pointer") { onShowPointerTap() }
        inner.addView(
            showPointerBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = LoomUi.dp(context, 8)
            },
        )

        // Plain-words result of the last "Show pointer" tap. Empty until
        // tapped (or until the accessibility service is found off).
        pointerStatus = LoomUi.text(
            context, "", R.style.LoomText_Body, R.color.loom_text_secondary, Gravity.CENTER,
        ).apply { visibility = View.GONE }
        inner.addView(
            pointerStatus,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = LoomUi.dp(context, 8)
            },
        )

        // One-tap link to the SYSTEM accessibility settings — shown only
        // when LOOM's accessibility service is off. One tap to the
        // system's own screen, not a maze through LOOM's Settings.
        pointerSettingsLink = TextView(context).apply {
            text = "Open Accessibility settings"
            setTextAppearance(R.style.LoomText_Label)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            gravity = Gravity.CENTER
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                activity.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
        inner.addView(
            pointerSettingsLink,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = LoomUi.dp(context, 8)
            },
        )

        // Exact steps for turning the helper back on — shown only with
        // the settings link, when the helper is off or not running.
        // The link opens the Accessibility list; these steps are the
        // rest of the trip, in plain words, on this screen.
        pointerSteps = LoomUi.text(
            context, "", R.style.LoomText_Body, R.color.loom_text_secondary, Gravity.CENTER,
        ).apply { visibility = View.GONE }
        inner.addView(
            pointerSteps,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = LoomUi.dp(context, 8)
            },
        )

        // See what LOOM sees — one tap from Home to the EXISTING view
        // (today buried at Settings → Device Control → See what LOOM
        // sees). Reuses LoomDestination.WhatLoomSees; nothing rebuilt.
        val seeWhatBtn = primaryButton("See what LOOM sees") {
            navigate(HomePointerControl.seeWhatLoomSeesDestination())
        }
        inner.addView(
            seeWhatBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_20)
            },
        )

        // Shutdown — visible on Home, never buried in a menu. Confirmation first.
        val shutdownBtn = TextView(context).apply {
            text = "Shut down LOOM"
            setTextAppearance(R.style.LoomText_Headline)
            setTextColor(context.getColor(R.color.loom_text_primary))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmShutdown() }
        }
        inner.addView(
            shutdownBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_20)
            },
        )

        // Logo + name + tagline (lockdown card 1).
        inner.addView(LoomUi.logoRow(context))
        inner.addView(
            LoomUi.text(context, "LOOM", R.style.LoomText_Display, gravity = Gravity.CENTER)
                .apply { letterSpacing = 0.12f },
        )
        inner.addView(
            LoomUi.text(
                context, "Your Personal AI Companion",
                R.style.LoomText_Label, R.color.loom_text_secondary, Gravity.CENTER,
            ),
        )
        inner.addView(
            LoomUi.text(
                context, "Think • Plan • Create • Evolve",
                R.style.LoomText_Caption, R.color.loom_text_muted, Gravity.CENTER,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_24)
                }
            },
        )

        // Greeting card.
        val greetCard = LoomCardView(context)
        val greetCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        greetCol.addView(
            LoomUi.text(
                context, "${LoomUi.greeting()}, David \uD83D\uDC4B",
                R.style.LoomText_Headline,
            ),
        )
        greetCol.addView(
            LoomUi.text(
                context, "What would you like me to do today?",
                R.style.LoomText_Body, R.color.loom_text_secondary,
            ),
        )
        greetCard.addView(greetCol)
        inner.addView(
            greetCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_24)
            },
        )

        // Voice orb + status (lockdown cards 2–3).
        val orbSize = LoomUi.dp(context, 148)
        orb = OrbView(context)
        val orbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        orbRow.addView(orb, LinearLayout.LayoutParams(orbSize, orbSize))
        val orbSide = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.marginStart = LoomUi.dp(context, 16)
            layoutParams = p
        }
        voiceStatus = LoomUi.text(context, "Tap the orb to talk", R.style.LoomText_Headline)
        engineStatus = LoomUi.text(
            context, engine.engineLabel, R.style.LoomText_Caption, R.color.loom_text_muted,
        )
        val openChatBtn = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Label)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            text = "Open full chat →"
            setPadding(0, LoomUi.dp(context, 12), 0, 0)
            setOnClickListener { navigate(LoomDestination.ChatText) }
        }
        orbSide.addView(voiceStatus)
        orbSide.addView(engineStatus)
        orbSide.addView(openChatBtn)
        orbRow.addView(orbSide)
        orb.setOnClickListener { onOrbTap() }
        inner.addView(
            orbRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_20)
            },
        )

        // Tap to Chat (primary).
        val tapToChat = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Headline)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            text = "Tap to Chat"
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 16)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { navigate(LoomDestination.ChatText) }
        }
        inner.addView(
            tapToChat,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_24)
            },
        )

        // Quick actions 2x2.
        inner.addView(sectionLabel("Quick actions"))
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(quickRow("New conversation", "Voice chat"))
        grid.addView(quickRow("Conversations", "Choose model"))
        inner.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_24)
            },
        )

        // PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT) —
        // the plain-words home-screen entry, never buried in
        // Settings: taps open the grant screen with the approved
        // app list, a deliberate grant control and one-tap revoke.
        // The status line shows the grant state, refreshed on
        // every attach (see refreshPseudoControl).
        inner.addView(sectionLabel("Pseudo control"))
        val pseudoCard = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val pseudoCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pseudoCol.addView(LoomUi.text(context, "Pseudo control", R.style.LoomText_Body))
        pseudoStatus = LoomUi.text(
            context, "", R.style.LoomText_Caption, R.color.loom_text_muted,
        )
        pseudoCol.addView(pseudoStatus)
        pseudoCard.addView(pseudoCol)
        pseudoCard.setOnClickListener { navigate(LoomDestination.PseudoControl) }
        inner.addView(
            pseudoCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = LoomUi.dp(context, 8)
            },
        )

        // The one-time grant question: shown ONLY until David
        // answers it for the current app list (grant, or "Not
        // now") — never again after that, unless the list changes
        // or he revokes. The grant itself is deliberate and
        // happens on the grant screen, never from this banner:
        // "See the list and decide" opens that screen; "Not now"
        // answers the question without granting.
        pseudoPromptBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pseudoPromptBox.addView(
            LoomUi.text(context, "Allow pseudo control?", R.style.LoomText_Body),
        )
        pseudoPromptBox.addView(
            LoomUi.text(
                context, "LOOM would do ordinary things in YouTube, " +
                    "YouTube Music, Chrome, Facebook, WhatsApp, Phone, " +
                    "Messages, Gallery, Clock and Settings without an " +
                    "approval card each time. Messages, calls, money " +
                    "and deleting still ask every time, and banking " +
                    "apps stay guidance-only.",
                R.style.LoomText_Caption, R.color.loom_text_secondary,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = LoomUi.dp(context, 8) }
            },
        )
        pseudoPromptBox.addView(
            primaryButton("See the list and decide") {
                navigate(LoomDestination.PseudoControl)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 8) },
        )
        val promptNo = TextView(context).apply {
            text = "Not now"
            setTextAppearance(R.style.LoomText_Label)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            gravity = Gravity.CENTER
            val v = LoomUi.dp(context, 8)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    pseudoStore.dismissPrompt()
                } catch (_: Exception) {
                }
                refreshPseudoControl()
            }
        }
        pseudoPromptBox.addView(promptNo)
        inner.addView(
            pseudoPromptBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_24)
            },
        )

        // Recents.
        inner.addView(sectionLabel("Recent conversations"))
        recentsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(recentsBox)

        addView(root)
    }

    /** Compact AI selector card — provider · model · status · Local/Cloud. */
    private fun buildEngineCard(): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        engineCardTitle = LoomUi.text(context, "", R.style.LoomText_Headline)
        engineCardSub = LoomUi.text(
            context, "", R.style.LoomText_Caption, R.color.loom_text_muted,
        )
        col.addView(engineCardTitle)
        col.addView(engineCardSub)
        row.addView(col)
        row.addView(TextView(context).apply {
            text = "›"
            setTextAppearance(R.style.LoomText_Title)
            setTextColor(context.getColor(R.color.loom_accent_bright))
        })
        card.addView(row)
        card.setOnClickListener {
            ModelPickerDialog.show(activity, models, onOpenSettings = {
                navigate(LoomDestination.Settings)
            }) { refreshEngineCard() }
        }
        refreshEngineCard()
        return card
    }

    private fun refreshEngineCard() {
        val mode = models.engineMode
        val modeLabel = if (mode == EngineMode.LOCAL) "Local" else "Cloud"
        val activeName = models.activeModelName()
        engineCardTitle.text = "● $modeLabel · ${activeName ?: "No active model"}"
        val sub = if (mode == EngineMode.LOCAL) {
            "On-device engine — no key needed"
        } else if (activeName != null) {
            val pid = models.activeProviderId!!
            val st = models.providerStatus(pid)
            "MODEL ACTIVE · ${st.name.replace('_', ' ')}"
        } else {
            "No validated model — open AI Connections"
        }
        engineCardSub.text = sub
        if (::engineStatus.isInitialized) engineStatus.text = engine.engineLabel
    }

    /**
     * Device-failure fix (2026-11-13, David's binding rule Nov 13
     * ~03:36): a SHUTDOWN *command* (voice) executes IMMEDIATELY —
     * no dialog, no question, no cancel path. The 12-step teardown
     * inside [LoomShutdown.shutdown] is unchanged. The dedicated
     * "Shut down LOOM" BUTTON below keeps its confirmation dialog
     * ([confirmShutdown]) — a deliberate button tap is not a voice
     * command, and whether the button keeps its dialog is an open
     * question for David, flagged in the fix report, not assumed.
     */
    private fun shutdownImmediately() {
        voiceSession.end()
        LoomShutdown.shutdown(activity) { voice.shutdown() }
    }

    /** Orderly shutdown with confirmation (BUTTON path only). Relaunch = tap the LOOM icon. */
    private fun confirmShutdown() {
        LoomDialogs.confirm(
            activity,
            "Shut down LOOM?",
            "Voice listening stops, the floating orb/character is removed, and " +
                "assistant services stop. Tap the LOOM icon to start again. " +
                "Note: LOOM's helper is also listed on its own in system " +
                "Settings — turn it off there if you want LOOM fully off.",
            "Shut down",
        ) {
            voiceSession.end()
            LoomShutdown.shutdown(activity) { voice.shutdown() }
        }
    }

    /** Full-width primary button, same visual pattern as "Shut down LOOM". */
    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextAppearance(R.style.LoomText_Headline)
            setTextColor(context.getColor(R.color.loom_text_primary))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    // ---------- one-tap pointer (accessibility fix, Nov 5) ----------

    /**
     * Whether LOOM's accessibility service is enabled — the same
     * Settings.Secure read DeviceControlCenter.isAccessibilityEnabled()
     * uses. Without it the pointer cannot draw, so there is no point
     * starting the service; say so plainly instead.
     *
     * Pointer failure fix (2026-11-05): this is the SETTINGS FLAG
     * ONLY. A HyperOS-killed service still reads "on" here, so this
     * must never be the sole liveness check — [isHostLive] is the
     * live signal, and [HomePointerControl.decideLiveness] combines
     * the two before any render is attempted.
     */
    private fun isAccessibilityOn(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                activity.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(
                activity,
                "com.loom.assistant.platform.services.LoomAccessibilityService",
            ).flattenToString()
            enabled.split(':').any { it.equals(me, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Start the floating assistant — the SAME mechanism as the Assistant
     * Modes "Start floating" button: an explicit ACTION_START_OVERLAY
     * intent addressed at LoomOverlayService, foreground service on
     * Android O+. (LoomOverlayService.start() wraps this identical
     * intent; the explicit form is used here so Home matches the Modes
     * screen's start path exactly.)
     * Returns false only if the start could not even be issued — the
     * start itself is async, so true is never proof the service is up.
     */
    private fun startFloatingAssistant(): Boolean {
        return try {
            val i = Intent(LoomIntents.ACTION_START_OVERLAY).apply {
                setClassName(
                    activity.packageName,
                    "com.loom.assistant.platform.services.LoomOverlayService",
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(i)
            } else {
                activity.startService(i)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * LIVE liveness, not the switch: true only when the accessibility
     * service instance is connected AND bound into
     * ScreenAwarenessHolder — the same live signal
     * DeviceControlCenter.isServiceBound() reads. False when the
     * service was killed or never connected, even if the Settings
     * flag still reads on (the cfdb8fb failure: flag on, service
     * started, render never verified). Never throws.
     */
    private fun isHostLive(): Boolean {
        return try {
            LoomAccessibilityService.isLive()
        } catch (_: Exception) {
            false
        }
    }

    private fun onShowPointerTap() {
        if (pointerAttemptRunning) return
        // Automatic recovery (Nov 6, refused-start fix): a manual
        // start after SHUT DOWN / DEACTIVATE is refused — coming back
        // is launcher-only (§4.4). Say so in plain words up front
        // instead of waiting for the liveness check and the start
        // attempt, both of which would fail identically and end in
        // the generic "try again" text.
        if (isStoppedByChoice()) {
            showPointerOutcome(HomePointerControl.stoppedByChoiceOutcome())
            return
        }
        // Pointer failure fix (2026-11-05): check LIVE liveness
        // before attempting a render that cannot win. Flag off →
        // the settings link at once. Flag on but host not live →
        // wait for Android's own rebind first (see below); only if
        // it still has not arrived does the NO_HOST outcome (with
        // the settings link and the toggle steps) appear.
        val flagOn = isAccessibilityOn()
        val hostLiveAtTap = isHostLive()
        when (HomePointerControl.decideLiveness(flagOn, hostLiveAtTap)) {
            HomePointerControl.PointerLiveness.FLAG_OFF -> {
                showPointerOutcome(
                    HomePointerControl.outcome(
                        a11yOn = false, serviceStarted = false, rendered = null,
                    ),
                )
                return
            }
            HomePointerControl.PointerLiveness.FLAG_ON_BUT_NOT_LIVE -> {
                // Binding survival (2026-11-06): do NOT give up on
                // the first read. Android binds the helper service
                // itself — the app cannot rebind it — and opening
                // the app gives the system a live process to bind
                // into, so the rebind may land a moment after the
                // tap. Wait for it (bounded, logged) and only send
                // David to the toggle if it still has not arrived.
                waitForSystemRebind { liveNow ->
                    if (liveNow) {
                        startPointerAttempt(flagOn, hostLiveAtTap = true)
                    } else {
                        android.util.Log.w(
                            "LoomPointer",
                            "Home show-pointer: helper still not live after " +
                                "${HomePointerControl.REBIND_WAIT_MS}ms wait — " +
                                "showing the turn-it-back-on steps",
                        )
                        showPointerOutcome(
                            HomePointerControl.outcome(
                                a11yOn = true,
                                serviceStarted = false,
                                rendered = false,
                                failure = ShowPointerFailure.NO_HOST,
                            ),
                        )
                    }
                }
                return
            }
            HomePointerControl.PointerLiveness.LIVE -> { startPointerAttempt(flagOn, hostLiveAtTap) }
        }
    }

    /**
     * Binding survival (2026-11-06): wait for Android's own rebind
     * of the helper service. This is NOT a programmatic rebind — no
     * such API exists for an app's own accessibility service. The
     * wait only gives the system time to bind an enabled service
     * into the live app process; [onResult] reports whether the
     * helper went live inside the window. Runs on a worker thread;
     * the result is posted back on the main thread.
     */
    private fun waitForSystemRebind(onResult: (Boolean) -> Unit) {
        pointerAttemptRunning = true
        showPointerBtn.isEnabled = false
        pointerStatus.text = "Checking the helper…"
        pointerStatus.visibility = View.VISIBLE
        pointerSettingsLink.visibility = View.GONE
        pointerSteps.visibility = View.GONE
        android.util.Log.i(
            "LoomPointer",
            "Home show-pointer: helper not live at tap, waiting up to " +
                "${HomePointerControl.REBIND_WAIT_MS}ms for Android to reconnect it",
        )
        Thread {
            val deadline = System.currentTimeMillis() + HomePointerControl.REBIND_WAIT_MS
            var liveNow = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (isHostLive()) {
                        liveNow = true
                        break
                    }
                } catch (_: Exception) {
                    break
                }
                try {
                    Thread.sleep(HomePointerControl.REBIND_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            android.util.Log.i(
                "LoomPointer",
                "Home show-pointer: rebind wait finished liveNow=$liveNow",
            )
            mainHandler.post {
                pointerAttemptRunning = false
                showPointerBtn.isEnabled = true
                onResult(liveNow)
            }
        }.start()
    }

    /**
     * Automatic recovery (Nov 6, refused-start fix): the same
     * stopped-by-choice read the service's start guard makes — an
     * intentional-stop marker is present AND the lifecycle is not
     * ACTIVE. Never throws: a failed read means "not stopped by
     * choice", and the normal tap flow (and the service guard) decide.
     */
    private fun isStoppedByChoice(): Boolean {
        return try {
            HomePointerControl.isStoppedByChoice(
                markerPresent =
                    AutomaticRecoveryStore.get(context).readIntentionalStop() != null,
                lifecycleActive =
                    LifecycleController.currentState() == LifecycleState.ACTIVE,
            )
        } catch (_: Exception) {
            false
        }
    }

    /** The LIVE-path tap flow: start the overlay service, poll the seam, report. */
    private fun startPointerAttempt(flagOn: Boolean, hostLiveAtTap: Boolean) {
        // Automatic recovery (Nov 6, refused-start fix): covers the
        // rebind-wait callback path too — the stop could have happened
        // during the wait, and the service would refuse the start.
        if (isStoppedByChoice()) {
            showPointerOutcome(HomePointerControl.stoppedByChoiceOutcome())
            return
        }
        pointerAttemptRunning = true
        showPointerBtn.isEnabled = false
        pointerStatus.text = "Starting the pointer…"
        pointerStatus.visibility = View.VISIBLE
        pointerSettingsLink.visibility = View.GONE
        if (::pointerSteps.isInitialized) pointerSteps.visibility = View.GONE
        Thread {
            val startMs = System.currentTimeMillis()
            val started = startFloatingAssistant()
            // The service start is async — the first render attempt can
            // land before onCreate runs, and a cold start can take
            // seconds. Bounded 10 s wait at a 250 ms cadence: poll
            // until the overlay-service instance exists AND the a11y
            // host is live, then run the detailed seam each attempt
            // and keep the LAST reason for the final message. The
            // warm path stays fast — the first attempt runs at once,
            // so a live service shows in well under a second. Only
            // the cold path may take longer.
            //
            // Device evidence (Nov 5 22:05) ruled out the killed-host
            // suspect — the a11y service is live and bound at the
            // moment of failure — so this loop also records whether
            // the OVERLAY instance was EVER non-null during the window
            // ([sawOverlayInstance]) and whether the host was EVER
            // live ([sawHostLive]). That pair alone distinguishes the
            // remaining candidates on device: never non-null → the
            // overlay service never started / never stayed up (a);
            // non-null but the seam never ran → the host never went
            // live; seam ran → the LAST reason names the failing
            // step inside the render path. Both go in the final log
            // line; timing alone is NOT presented as the cause — the
            // second tap failing identically weakens a pure timing
            // story (the service should have been warm by then).
            //
            // Note the service itself now marshals the detailed seam
            // onto the main thread (LoomOverlayService
            // .showPointerNowDetailed): WindowManager.addView needs a
            // Looper on the calling thread, and this poll thread has
            // none — a first render used to throw inside addView and
            // report HOST_REFUSED on every tap. Early polls during a
            // cold start can also hit transient NO_INTERACTION:
            // onCreate sets `instance = this` BEFORE building the
            // interaction layer, and the loop retries past it.
            var rendered = false
            var lastFailure: ShowPointerFailure? = null
            var attemptedSeam = false
            var sawOverlayInstance = false
            var sawHostLive = false
            if (started) {
                val deadline = startMs + 10_000L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val overlayUp = LoomOverlayService.instance != null
                        if (overlayUp) sawOverlayInstance = true
                        val hostLiveNow = isHostLive()
                        if (hostLiveNow) sawHostLive = true
                        if (overlayUp && hostLiveNow) {
                            attemptedSeam = true
                            val result = LoomOverlayService.showPointerNowDetailed()
                            if (result.shown) {
                                rendered = true
                                break
                            }
                            lastFailure = result.failure
                        }
                        // Cold start: service / host not up yet —
                        // wait, do not call a seam that cannot win.
                    } catch (_: Exception) {
                        break
                    }
                    if (rendered) break
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            val failure = HomePointerControl.resolvePollFailure(
                rendered = rendered,
                serviceStarted = started,
                attemptedSeam = attemptedSeam,
                lastFailure = lastFailure,
            )
            if (rendered) {
                android.util.Log.i(
                    "LoomPointer",
                    "Home show-pointer shown in ${elapsedMs}ms",
                )
            } else {
                android.util.Log.w(
                    "LoomPointer",
                    "Home show-pointer failed: reason=$failure flagOn=$flagOn " +
                        "hostLiveAtTap=$hostLiveAtTap serviceStarted=$started " +
                        "sawOverlayInstance=$sawOverlayInstance sawHostLive=$sawHostLive " +
                        "attemptedSeam=$attemptedSeam elapsedMs=$elapsedMs",
                )
            }
            val outcome = HomePointerControl.outcome(
                a11yOn = true,
                serviceStarted = started,
                rendered = rendered,
                failure = failure,
            )
            mainHandler.post { showPointerOutcome(outcome) }
        }.start()
    }

    private fun showPointerOutcome(outcome: HomePointerControl.PointerTapOutcome) {
        pointerAttemptRunning = false
        if (::showPointerBtn.isInitialized) showPointerBtn.isEnabled = true
        pointerStatus.text = outcome.statusText
        pointerStatus.visibility = View.VISIBLE
        pointerSettingsLink.visibility =
            if (outcome.showAccessibilitySettings) View.VISIBLE else View.GONE
        // Binding survival (Nov 6): the exact toggle steps ride with
        // the outcome — shown only when the helper needs turning back
        // on, hidden on every other outcome (including success).
        if (::pointerSteps.isInitialized) {
            val steps = outcome.stepsText
            if (steps != null) {
                pointerSteps.text = steps
                pointerSteps.visibility = View.VISIBLE
            } else {
                pointerSteps.visibility = View.GONE
            }
        }
    }

    /**
     * Binding survival (Nov 6): re-check liveness when this screen
     * regains focus — the user may just have come back from Settings
     * after turning LOOM off and on again. Without this, Home would
     * keep showing a stale "helper is off" hint until the next
     * attach, even though the helper is live again.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !pointerAttemptRunning) refreshPointerHint()
    }

    /**
     * On attach, say so up front when the accessibility service is off —
     * the user should not have to tap to discover it. (The tap itself
     * re-checks live, so a stale hint clears the moment it is tapped.)
     *
     * Pointer failure fix (2026-11-05): the same LIVE check as the
     * tap — flag on but host not live shows the NO_HOST hint (with
     * the settings link) instead of silence; flag off shows the
     * a11y-off hint. When both are live there is nothing to say.
     */
    private fun refreshPointerHint() {
        if (!::pointerStatus.isInitialized) return
        when (HomePointerControl.decideLiveness(isAccessibilityOn(), isHostLive())) {
            HomePointerControl.PointerLiveness.FLAG_OFF -> {
                showPointerOutcome(
                    HomePointerControl.outcome(
                        a11yOn = false, serviceStarted = false, rendered = null,
                    ),
                )
                scheduleBindingRecheck()
            }
            HomePointerControl.PointerLiveness.FLAG_ON_BUT_NOT_LIVE -> {
                showPointerOutcome(
                    HomePointerControl.outcome(
                        a11yOn = true,
                        serviceStarted = false,
                        rendered = false,
                        failure = ShowPointerFailure.NO_HOST,
                    ),
                )
                scheduleBindingRecheck()
            }
            HomePointerControl.PointerLiveness.LIVE -> {
                // Binding survival (Nov 6): a rebind that landed
                // after the app opened clears the stale hint — the
                // status, link and steps all go away, so Home stops
                // telling David the helper is off when it is not.
                // Automatic recovery (Nov 6): a fresh recovery report
                // takes the same line instead — success or failure, in
                // plain words. Nothing to say means the line stays gone.
                bindingRechecksLeft = 0
                if (!showRecoveryReport()) {
                    pointerStatus.visibility = View.GONE
                    pointerSettingsLink.visibility = View.GONE
                    if (::pointerSteps.isInitialized) pointerSteps.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Automatic recovery (Nov 6): show the last recovery report under
     * the Show pointer button — but only while it is fresh, and only
     * when no liveness problem is showing (the hint wins: it names the
     * same fix). The settings link appears only for the "turn it on in
     * Settings" report, with the exact toggle steps; every other report
     * is text only. Returns false when there is nothing fresh to show.
     */
    private fun showRecoveryReport(): Boolean {
        if (!::pointerStatus.isInitialized) return false
        val store = try {
            AutomaticRecoveryStore.get(context)
        } catch (_: Exception) {
            return false
        }
        val report = try {
            store.lastReport()
        } catch (_: Exception) {
            null
        } ?: return false
        val atMs = try {
            store.lastReportAtMs()
        } catch (_: Exception) {
            0L
        }
        if (System.currentTimeMillis() - atMs > AutomaticRecovery.REPORT_FRESH_MS) return false
        pointerStatus.text = report
        pointerStatus.visibility = View.VISIBLE
        val needsSettings = try {
            store.lastReportKind()
        } catch (_: Exception) {
            null
        } == AutomaticRecovery.REPORT_KIND_A11Y_OFF
        pointerSettingsLink.visibility = if (needsSettings) View.VISIBLE else View.GONE
        if (::pointerSteps.isInitialized) {
            if (needsSettings) {
                pointerSteps.text = HomePointerControl.TURN_ON_STEPS
                pointerSteps.visibility = View.VISIBLE
            } else {
                pointerSteps.visibility = View.GONE
            }
        }
        return true
    }

    /**
     * Binding survival (Nov 6): after opening, the system may still
     * be rebinding the helper into the fresh app process — the first
     * read can say "not live" a moment before the rebind lands.
     * Re-read twice (1 s, then 3 s after attach). Each re-check runs
     * the same [refreshPointerHint]: live clears the hint, still not
     * live leaves it. Bounded — two re-checks per attach, never a
     * loop — and skipped while a tap attempt is running.
     */
    private fun scheduleBindingRecheck() {
        // Armed once per attach (see the attach listener); never
        // re-armed here, so this cannot loop.
        if (bindingRechecksLeft <= 0) return
        bindingRechecksLeft--
        val delayMs = if (bindingRechecksLeft == 1) 1_000L else 2_000L
        mainHandler.postDelayed(
            {
                if (isAttachedToWindow && !pointerAttemptRunning) refreshPointerHint()
            },
            delayMs,
        )
    }

    private fun sectionLabel(text: String): TextView =
        LoomUi.text(context, text, R.style.LoomText_Label, R.color.loom_text_secondary)
            .apply {
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                p.bottomMargin = LoomUi.dp(context, 8)
                layoutParams = p
            }

    private fun quickRow(left: String, right: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(quickCard(left), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val spacer = View(context)
        row.addView(spacer, LinearLayout.LayoutParams(LoomUi.dp(context, 12), 1))
        row.addView(quickCard(right), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        p.bottomMargin = LoomUi.dp(context, 12)
        row.layoutParams = p
        return row
    }

    private fun quickCard(label: String): View {
        val card = LoomCardView(context).apply {
            isClickable = true
            isFocusable = true
        }
        val tv = LoomUi.text(context, label, R.style.LoomText_Body, gravity = Gravity.CENTER)
        if (label == "Choose model") modelActionLabel = tv
        card.addView(tv)
        card.setOnClickListener {
            when (label) {
                "New conversation" -> {
                    store.create()
                    navigate(LoomDestination.ChatText)
                }
                "Conversations" -> navigate(LoomDestination.Conversations)
                "Voice chat" -> navigate(LoomDestination.ChatVoice)
                "Choose model" -> ModelPickerDialog.show(activity, models) { refreshRecents() }
            }
        }
        return card
    }

    /**
     * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT): refresh
     * the home-screen entry's status line and the one-time prompt.
     * The entry shows the grant state in plain words; the prompt
     * shows only while the grant question is unanswered for the
     * current app list. Called from refreshRecents (which runs on
     * every attach and every finished turn), so the entry never
     * goes stale after a grant or a revoke on the grant screen.
     */
    private fun refreshPseudoControl() {
        if (!::pseudoStatus.isInitialized) return
        val granted = try {
            pseudoStore.isGranted()
        } catch (_: Exception) {
            false
        }
        pseudoStatus.text = if (granted) {
            "On — ${PseudoControlPolicy.FREE_APPS.size} apps · no approval cards in those apps"
        } else {
            "Off — approval cards appear as before"
        }
        if (::pseudoPromptBox.isInitialized) {
            pseudoPromptBox.visibility = try {
                if (pseudoStore.shouldShowPrompt()) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                View.GONE
            }
        }
    }

    private fun refreshRecents() {
        refreshPseudoControl()
        recentsBox.removeAllViews()
        modelActionLabel.text = models.label
        val recents = store.list().take(3)
        if (recents.isEmpty()) {
            recentsBox.addView(
                LoomUi.text(
                    context, "No conversations yet — start one from Tap to Chat.",
                    R.style.LoomText_Body, R.color.loom_text_muted,
                ),
            )
            return
        }
        for (s in recents) {
            val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(LoomUi.text(context, s.title, R.style.LoomText_Body))
            col.addView(
                LoomUi.text(
                    context,
                    "${s.preview.take(60)} • ${LoomUi.timeAgo(System.currentTimeMillis(), s.updatedAt)}",
                    R.style.LoomText_Caption, R.color.loom_text_muted,
                ),
            )
            card.addView(col)
            card.setOnClickListener {
                store.setActive(s.id)
                navigate(LoomDestination.ChatText)
            }
            recentsBox.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = LoomUi.dp(context, 8) },
            )
        }
        val viewAll = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Label)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            gravity = Gravity.CENTER
            text = "View all →"
            setPadding(0, LoomUi.dp(context, 8), 0, 0)
            setOnClickListener { navigate(LoomDestination.Conversations) }
        }
        recentsBox.addView(viewAll)
    }

    // ---------- quick voice (Stage 2A: conversational session) ----------

    private fun onOrbTap() {
        // Tap toggles: an active session ends, an idle orb starts listening.
        // Inside a session the manager auto-continues — no per-turn taps.
        if (voiceSession.isActive) voiceSession.end()
        else voiceSession.start()
    }

    // ---------- VoiceSessionManager.Host ----------

    override fun provideEngine(): ResponseEngine = engine
    override fun provideStore(): ConversationStore = store
    override fun historyWindow(): Int = 8

    override fun checkMicPermission(onResult: (Boolean) -> Unit) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
            return
        }
        requestMicPermission { granted ->
            if (!granted) {
                setVoiceUi(OrbView.OrbState.IDLE, "Microphone permission needed for voice.")
            }
            onResult(granted)
        }
    }

    override fun idlePrompt(): String = "Tap the orb to talk"

    // Session/state awareness (2026-11-11, OBSERVE-FIRST): a Pause
    // / Play control on the live screen means the command is about
    // the media on screen, not LOOM's own session.
    override fun mediaControlOnScreen(control: String): Boolean {
        val screen = ScreenAwarenessHolder.current.snapshot() ?: return false
        val want = MediaControlPhrases.match(control) ?: return false
        return MediaControlRouter.mediaControlOnScreen(screen, want)
    }

    override fun onSessionState(state: VoiceSessionState, statusText: String) {
        // Correction pass: mutual exclusion with the floating service session —
        // only one may own the mic at a time. Phase 2 (E.4): atomic
        // claim/release — a claim that loses the race simply fails instead
        // of stealing ownership.
        if (state != VoiceSessionState.IDLE && state != VoiceSessionState.ENDING) {
            VoiceSessionOwner.claimInApp()
        } else {
            VoiceSessionOwner.releaseInApp()
        }
        val orb = when (state) {
            VoiceSessionState.IDLE, VoiceSessionState.ENDING -> OrbView.OrbState.IDLE
            VoiceSessionState.LISTENING -> OrbView.OrbState.LISTENING
            VoiceSessionState.PROCESSING -> OrbView.OrbState.THINKING
            VoiceSessionState.SPEAKING -> OrbView.OrbState.SPEAKING
            VoiceSessionState.PAUSED -> OrbView.OrbState.PAUSED
            VoiceSessionState.ERROR -> OrbView.OrbState.ERROR
        }
        setVoiceUi(orb, statusText)
    }

    override fun onPartialText(text: String) {
        voiceStatus.text = "\u201C$text\u201D"
    }

    override fun onTurnFinished() {
        refreshRecents()
    }

    override fun onCommandAction(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.SHUTDOWN -> shutdownImmediately()
            // Stage 2A.1 (D2): "switch to keyboard" takes the existing
            // text-mode path — end voice, hand the thread to Chat text.
            VoiceCommand.EXIT_VOICE, VoiceCommand.SWITCH_KEYBOARD -> {
                voiceSession.end()
                navigate(LoomDestination.ChatText)
            }
            else -> { /* PAUSE/RESUME/STOP/STOP_LISTENING/END_VOICE/START_LISTENING/MODE_* handled in the manager */ }
        }
    }

    // Device-evidence fix (2026-11-05): the home voice session's launch
    // path — same shared launcher as typed chat. Home has no chat
    // thread to message, so the outcome goes to a Toast.
    // Master §8: the origin rides through to the gate.
    override fun onLaunchRequested(appName: String) {
        onLaunchRequested(appName, RequestOrigin.MODEL)
    }

    override fun onLaunchRequested(appName: String, origin: RequestOrigin) {
        com.loom.assistant.platform.services.LoomAppLauncher.launch(
            activity, appName,
            onResult = {
                android.widget.Toast.makeText(activity, it, android.widget.Toast.LENGTH_LONG).show()
            },
            origin = origin,
        )
    }

    // In-app routing (fix plan item 5, search H2): same shared runner
    // as the chat screen — the home voice session reaches the real
    // search executor instead of the manager's refusal. Home has no
    // chat thread, so a finished session's ack goes to a Toast (the
    // same fallback its launch path already uses).
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
                    android.widget.Toast.makeText(activity, ack, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Stage 2D: in-app plans run through the same executor as the
    // floating host, via the shared LoomPlanRunner — no separate
    // in-app execution path. Home has no chat thread, so a finished
    // session's ack goes to a Toast (the same fallback its launch
    // path already uses). The In-progress status is Toasted too
    // (simple-task execution fix, 2026-11-07), so a plan running
    // from Home is visible from the first moment.
    // MASTER Wave 4 (F12): Toast-only is no longer the record — every
    // TaskStatus update is ALSO appended to the shared conversation
    // store (the same store Recent Conversations reads), so a plan
    // run from Home leaves a durable trace with its steps and
    // outcome instead of vanishing with the Toast. The helper-down
    // gate (F10) refuses before the plan starts when the helper is
    // down, and records the event.
    // Overrides VoiceSessionManager.Host.onPlanRequested (Worker C
    // added the interface method + handlePlanTask routing).
    override fun onPlanRequested(request: PlanRequest): Boolean {
        return LoomPlanRunner.runPlan(activity, request, onAck = { ack ->
            mainHandler.post {
                if (::voiceSession.isInitialized && voiceSession.isActive) {
                    voiceSession.speakNow(ack)
                } else {
                    android.widget.Toast.makeText(activity, ack, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, onStatus = { update ->
            mainHandler.post {
                try {
                    val conv = store.getActive()
                    store.append(
                        conv.id,
                        ChatMessage("assistant", update.message, System.currentTimeMillis()),
                    )
                    refreshRecents()
                } catch (_: Exception) {
                }
                if (update.status == TaskStatus.IN_PROGRESS) {
                    android.widget.Toast.makeText(
                        activity, update.message, android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }, helperDownReason = {
            val center = DeviceControlCenter(activity)
            val refusal = center.helperDownRefusal()
            if (refusal != null) {
                center.helperDownCause()?.let { center.recordHelperDown(it) }
            }
            refusal
        })
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

    private fun setVoiceUi(state: OrbView.OrbState, status: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setVoiceUi(state, status) }
            return
        }
        orb.orbState = state
        voiceStatus.text = status
    }
}
