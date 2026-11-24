package com.loom.assistant.presentation.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.core.common.BuildStamp
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.lifecycle.AutomaticRecovery
import com.loom.assistant.platform.services.AutomaticRecoveryRunner
import com.loom.assistant.platform.services.AutomaticRecoveryStore
import com.loom.assistant.platform.services.LoomAccessibilityService
import com.loom.assistant.intelligence.authgate.AuthGrantStore
import com.loom.assistant.intelligence.llm.AuthKind
import com.loom.assistant.intelligence.llm.EngineMode
import com.loom.assistant.intelligence.llm.LlmProvider
import com.loom.assistant.intelligence.llm.ModelSelection
import com.loom.assistant.intelligence.llm.ModelStatus
import com.loom.assistant.intelligence.llm.ProviderModel
import com.loom.assistant.intelligence.llm.ProviderStatus
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.intelligence.memory.MemoryStore
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.interaction.pointer.PointerPolicy
import com.loom.assistant.interaction.pointer.PointerPolicyStore
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomPreferences
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R
import com.loom.assistant.presentation.modes.AssistantMode
import com.loom.assistant.presentation.modes.ModeController
import com.loom.assistant.presentation.panels.LoomDialogs

/**
 * Settings — the 8 categories from lockdown card 11.
 * Every control reads/writes a real persisted preference or store.
 * Internal list/detail navigation (not new locked destinations).
 */
class SettingsScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
    private val openOverlaySettings: () -> Unit,
) : ScrollView(activity) {

    private val prefs = LoomPreferences.get(activity)
    private val modelSelection = ModelSelection(activity)
    private val memoryStore = MemoryStore.get(activity)
    private val conversationStore = ConversationStore.get(activity)
    private val activityLog = ActivityLog.get(activity)

    private lateinit var body: LinearLayout
    private var openCategory: String? = null

    /**
     * Stage 2A.1 (D6/R8): re-render the modes section when a voice command
     * (or any other entry point) switches the mode — the "● Active" row no
     * longer goes stale until a manual refresh.
     */
    private val busListener: (LoomStateEvent) -> Unit = { event ->
        if (event is LoomStateEvent.ModeChanged && openCategory == "Assistant Modes") {
            refresh()
        }
    }

    init {
        build()
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                LoomEventBus.subscribe(busListener)
                refresh()
            }
            override fun onViewDetachedFromWindow(v: View) {
                LoomEventBus.unsubscribe(busListener)
                removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun build() {
        val root = LoomUi.screenRoot(context)
        val inner = LoomUi.innerOf(root)
        LoomUi.addCenteredPill(inner, "SETTINGS")
        inner.addView(
            LoomUi.text(context, "Settings", R.style.LoomText_Display, gravity = Gravity.START),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 12) },
        )
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(root)
    }

    private fun refresh() {
        body.removeAllViews()
        val open = openCategory
        if (open == null) {
            renderCategoryList()
        } else {
            renderBack()
            when (open) {
                "AI Connections" -> renderAiConnections()
                "Appearance" -> renderAppearance()
                "Assistant Modes" -> renderModes()
                "Voice & Speech" -> renderVoice()
                "Automation" -> renderAutomation()
                "Privacy & Security" -> renderPrivacy()
                "Keep LOOM running" -> renderKeepRunning()
                "Profile" -> renderProfile()
                "Notifications" -> renderNotifications()
            }
        }
    }

    private fun renderCategoryList() {
        categoryRow("Profile", "Your name") { openCategory = "Profile"; refresh() }
        categoryRow("AI Connections", modelSelection.label) { openCategory = "AI Connections"; refresh() }
        categoryRow("Appearance", "Dark premium") { openCategory = "Appearance"; refresh() }
        categoryRow("Assistant Modes", ModeController.label(ModeController.boundMode() ?: ModeController.getMode(context))) {
            openCategory = "Assistant Modes"; refresh()
        }
        categoryRow("Voice & Speech", "${prefs.speechRate}x rate") { openCategory = "Voice & Speech"; refresh() }
        categoryRow("Automation", if (prefs.autoResumeTasks) "Auto-resume on" else "Auto-resume off") {
            openCategory = "Automation"; refresh()
        }
        categoryRow("Privacy & Security", "Your data controls") { openCategory = "Privacy & Security"; refresh() }
        categoryRow("Device Control", "Helper and what it can do") {
            navigate(LoomDestination.DeviceControl)
        }
        // Binding survival (Nov 6): the battery / autostart checklist
        // that decides whether the helper survives the night.
        categoryRow("Keep LOOM running", KeepRunningChecklist.subtitle(batteryIgnoringOptimizations())) {
            openCategory = "Keep LOOM running"; refresh()
        }
        categoryRow("Notifications", if (prefs.digestMode) "Digest on" else "Digest off") {
            openCategory = "Notifications"; refresh()
        }
    }

    private fun categoryRow(title: String, subtitle: String, onTap: () -> Unit) {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(LoomUi.text(context, title, R.style.LoomText_Headline))
        col.addView(LoomUi.text(context, subtitle, R.style.LoomText_Caption, R.color.loom_text_muted))
        row.addView(col)
        row.addView(TextView(context).apply {
            text = "›"
            setTextAppearance(R.style.LoomText_Title)
            setTextColor(context.getColor(R.color.loom_accent_bright))
        })
        card.addView(row)
        card.setOnClickListener { onTap() }
        body.addView(card, rowParams())
    }

    private fun renderBack() {
        val back = TextView(context).apply {
            text = "‹ Settings"
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openCategory = null
                refresh()
            }
        }
        body.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = LoomUi.dp(context, 8) })
    }

    // ---------- category details ----------

    private fun renderAiConnections() {
        val sel = modelSelection

        // Status summary — the ACTIVE pair is what the cloud engine actually uses.
        val modeLabel = if (sel.engineMode == EngineMode.LOCAL) "Local" else "Cloud"
        val activeName = activeModelName(sel)
        infoCard(
            "Status",
            "$modeLabel · ${activeName ?: "No active model"}",
            if (sel.engineMode == EngineMode.CLOUD && activeName == null)
                "Cloud is on but no checked model is active — check one below."
            else "Only a checked model that is active answers in Cloud mode.",
        )

        // Explicit engine control — the user picks; LOOM never auto-substitutes.
        body.addView(sectionMiniLabel("Engine"))
        settingRow("Local", if (sel.engineMode == EngineMode.LOCAL) "● Active" else "○") {
            sel.engineMode = EngineMode.LOCAL
            activityLog.record("ai", "Engine mode → Local")
            refresh()
        }
        settingRow("Cloud", if (sel.engineMode == EngineMode.CLOUD) "● Active" else "○") {
            sel.engineMode = EngineMode.CLOUD
            activityLog.record("ai", "Engine mode → Cloud")
            refresh()
        }

        // One section per provider — each keeps its own key and activation state.
        for (provider in sel.allProviders()) {
            renderProviderSection(sel, provider)
        }

        body.addView(primaryButton("Add provider") {
            LoomDialogs.options(
                activity, "How the key is sent",
                listOf(
                    "API key sent in the header" to { showAddProviderForm(AuthKind.BEARER_HEADER) },
                    "API key sent in the web address" to { showAddProviderForm(AuthKind.QUERY_PARAM) },
                ),
            )
        })
    }

    private fun activeModelName(sel: ModelSelection): String? = sel.activeModelName()

    /** Live version name of the installed APK (falls back to the Gradle default). */
    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(activity.packageName, 0)
            .versionName ?: "0.1.0-m0"
    } catch (_: Exception) {
        "0.1.0-m0"
    }

    /** The 8 statuses, in plain words. */
    private fun providerStatusLabel(sel: ModelSelection, pid: String): String {
        return when (sel.providerStatus(pid)) {
            ProviderStatus.NO_KEY -> "No key saved"
            ProviderStatus.KEY_SAVED -> {
                val at = sel.lastValidatedAt(pid)
                if (at > 0L) "Key saved · last checked ${LoomUi.timeAgo(System.currentTimeMillis(), at)}"
                else "Key saved"
            }
            ProviderStatus.VALIDATING -> "Checking…"
            ProviderStatus.AUTH_FAILED -> "Key rejected — check the saved key"
            ProviderStatus.CONNECTED -> "Connected"
        }
    }

    private fun renderProviderSection(sel: ModelSelection, provider: LlmProvider) {
        val pid = provider.id
        body.addView(sectionMiniLabel("Provider — ${provider.displayName}"))
        infoCard(
            provider.displayName,
            providerStatusLabel(sel, pid),
            if (provider.removable) "Custom provider · ${provider.baseUrl}"
            else "Built-in provider",
        )

        // API key editor — inline in the screen (no popup), paste + show/hide.
        renderKeyEditor(sel, pid, provider.displayName)

        // Validate & activate — the ONLY path to MODEL ACTIVE.
        body.addView(primaryButton("Check & activate") {
            val model = sel.modelsFor(pid).firstOrNull { it.id == sel.selectedId }
                ?: sel.modelsFor(pid).firstOrNull()
            if (model == null) {
                LoomDialogs.info(activity, "No model", "Add a model ID for this provider first.")
                return@primaryButton
            }
            sel.selectedId = model.id
            LoomDialogs.info(
                activity, "Checking…",
                "Contacting ${provider.displayName}. This spends nothing on a reply.",
            )
            sel.validateAndActivate(activity, provider, model) { ok, msg ->
                activity.runOnUiThread {
                    activityLog.record(
                        "ai",
                        "Validate & activate (${provider.displayName}): ${if (ok) "ACTIVE" else "failed"}",
                    )
                    val full = if (ok) {
                        "$msg\n\nNext: set Engine → Cloud above, then chat uses ${model.displayName}."
                    } else msg
                    LoomDialogs.info(activity, if (ok) "Model active" else "Couldn't activate", full)
                    refresh()
                }
            }
        })

        // Models — SELECTED vs ACTIVE, exactly distinguished.
        body.addView(sectionMiniLabel("Models"))
        for (m in sel.modelsFor(pid)) {
            val mark = when (sel.modelStatus(pid, m.id)) {
                ModelStatus.ACTIVE -> "● ACTIVE"
                ModelStatus.REQUEST_FAILED -> "○ SELECTED · last request failed"
                ModelStatus.SELECTED ->
                    if (sel.selectedId == m.id) "○ SELECTED" else "○"
                ModelStatus.NO_MODEL -> "○"
            }
            settingRow(m.displayName, "$mark · ${m.note}") {
                sel.selectedId = m.id
                activityLog.record("ai", "Model selected → ${m.displayName}")
                refresh()
            }
        }
        if (provider.removable) {
            body.addView(primaryButton("Add model ID") {
                LoomDialogs.input(
                    activity, "Model ID",
                    listOf("Model identifier" to ""), "Add",
                ) { values ->
                    if (sel.addCustomModelId(pid, values[0])) refresh()
                    else LoomDialogs.info(activity, "Invalid", "Enter a non-empty, unique model ID.")
                }
            })
            body.addView(primaryButton("Remove provider") {
                LoomDialogs.confirm(
                    activity, "Remove provider?",
                    "Its saved key is deleted too. Other providers are untouched.",
                    "Remove",
                ) {
                    sel.removeCustomProvider(pid)
                    activityLog.record("ai", "Provider removed: ${provider.displayName}")
                    refresh()
                }
            })
        }
    }

    private fun showAddProviderForm(authKind: AuthKind) {
        LoomDialogs.input(
            activity, "New provider",
            listOf(
                "Name" to "",
                "Base URL (https://…)" to "https://",
                "Chat path" to "/chat/completions",
                "Test path" to "/models",
            ),
            "Add",
        ) { values ->
            val name = values[0].trim()
            val base = values[1].trim()
            if (name.isEmpty() || !base.startsWith("http")) {
                LoomDialogs.info(activity, "Invalid", "Give the provider a name and an https:// base URL.")
            } else {
                modelSelection.addCustomProvider(name, base, authKind, values[2], values[3])
                activityLog.record("ai", "Provider added: $name")
                refresh()
            }
        }
    }

    private fun renderKeyEditor(sel: ModelSelection, providerId: String, providerName: String) {
        val hasKey = sel.hasKeyFor(providerId)
        infoCard(
            "Saved key",
            if (hasKey) "A key is stored (encrypted)." else "No key stored.",
            "Keys never leave the encrypted store. LOOM never logs or displays them.",
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val et = EditText(context).apply {
            hint = if (hasKey) "Paste a new key to replace" else "Paste your $providerName API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHintTextColor(context.getColor(R.color.loom_text_muted))
            setTextColor(context.getColor(R.color.loom_text_primary))
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_2, 12)
            val h = LoomUi.dp(context, 12)
            setPadding(h, h, h, h)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(et)
        val toggle = TextView(context).apply {
            text = "Show"
            setTextAppearance(R.style.LoomText_Label)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            val h = LoomUi.dp(context, 12)
            setPadding(h, 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val showing = (et.inputType and
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0
                et.inputType = if (showing) {
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                text = if (showing) "Show" else "Hide"
                et.setSelection(et.text.length)
            }
        }
        row.addView(toggle)
        body.addView(row, rowParams())

        body.addView(primaryButton(if (hasKey) "Save / update key" else "Save key") {
            val raw = et.text.toString().trim()
            if (raw.isEmpty()) {
                LoomDialogs.info(activity, "Empty key", "Paste a key first — nothing was stored.")
            } else if (sel.setApiKey(providerId, raw)) {
                activityLog.record("security", "API key stored (encrypted) for $providerId")
                LoomDialogs.info(activity, "Saved", "Key stored encrypted. It was not displayed or logged.")
                refresh()
            } else {
                LoomDialogs.info(activity, "Storage failed", "The key could not be stored encrypted. Nothing was saved.")
            }
        })
        if (hasKey) {
            body.addView(primaryButton("Remove key") {
                LoomDialogs.confirm(
                    activity, "Remove key?",
                    "Cloud mode will stop answering until a new key is added.",
                    "Remove",
                ) {
                    sel.clearApiKey(providerId)
                    activityLog.record("security", "API key removed for $providerId")
                    refresh()
                }
            })
        }
    }

    private fun sectionMiniLabel(text: String): TextView =
        LoomUi.text(context, text, R.style.LoomText_Label, R.color.loom_text_secondary).apply {
            val p = LoomUi.dp(context, 8)
            setPadding(0, p, 0, 4)
        }

    private fun renderAppearance() {
        infoCard("Theme", "Dark premium", "LOOM's look is dark premium — the only look in this version.")
        toggleRow("Animations", prefs.animationsEnabled) {
            prefs.animationsEnabled = !prefs.animationsEnabled
            refresh()
        }
    }

    private fun renderModes() {
        // Stage 2A.1 (D6): honest "● Active" from the live bound state — null
        // when the service isn't running, so nothing claims to be active.
        val current = ModeController.boundMode()
        for (mode in AssistantMode.values()) {
            val selected = mode == current
            val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                LoomUi.text(context, ModeController.label(mode), R.style.LoomText_Headline).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                LoomUi.text(
                    context, if (selected) "● Active" else "○",
                    R.style.LoomText_Body,
                    if (selected) R.color.loom_accent_bright else R.color.loom_text_muted,
                ),
            )
            card.addView(row)
            card.setOnClickListener {
                // Stage 2A.1 (D1): entry (1) — the single authoritative
                // applier with live verification; refresh() re-renders rows
                // from the honest bound state.
                ModeController.applyMode(context, mode) { refresh() }
            }
            body.addView(card, rowParams())
        }
        val overlayOk = OverlayPermission.granted(context)
        infoCard(
            "Draw over other apps",
            if (overlayOk) "Allowed." else "Not allowed yet.",
            "Character and Orb float over other apps — they need this allowed first.",
        )
        if (!overlayOk) {
            body.addView(primaryButton("Allow drawing over apps") { openOverlaySettings() })
        }
    }

    private fun renderVoice() {
        settingRow("Speech rate", "${prefs.speechRate}x") {
            LoomDialogs.input(
                activity, "Speech rate",
                listOf("Rate 0.5 – 2.0" to prefs.speechRate.toString()),
                "Set",
            ) { values ->
                val r = values[0].toFloatOrNull()
                if (r == null || r < 0.5f || r > 2.0f) {
                    LoomDialogs.info(activity, "Invalid", "Enter a rate between 0.5 and 2.0.")
                } else {
                    prefs.speechRate = r
                    refresh()
                }
            }
        }
        settingRow("Speech pitch", "${prefs.speechPitch}x") {
            LoomDialogs.input(
                activity, "Speech pitch",
                listOf("Pitch 0.5 – 2.0" to prefs.speechPitch.toString()),
                "Set",
            ) { values ->
                val p = values[0].toFloatOrNull()
                if (p == null || p < 0.5f || p > 2.0f) {
                    LoomDialogs.info(activity, "Invalid", "Enter a pitch between 0.5 and 2.0.")
                } else {
                    prefs.speechPitch = p
                    refresh()
                }
            }
        }
        toggleRow("Tap to talk", prefs.tapToTalk) {
            prefs.tapToTalk = !prefs.tapToTalk
            refresh()
        }
        infoCard(
            "Always-listening",
            "Off.",
            "Hotword detection stays off unless you explicitly opt in — battery policy (arch §2.6).",
        )
    }

    private fun renderAutomation() {
        toggleRow("Auto-resume interrupted tasks", prefs.autoResumeTasks) {
            prefs.autoResumeTasks = !prefs.autoResumeTasks
            refresh()
        }
        // Stage 2B (D3a): pointer visibility policy — the same persisted
        // setting the voice commands ("show the pointer", "hide the
        // pointer", "pointer auto") write. Independent of automation.
        val policy = PointerPolicyStore.get(activity)
        cycleRow("Pointer visibility", PointerPolicyStore.titleLabel(policy)) {
            val next = when (PointerPolicyStore.get(activity)) {
                PointerPolicy.SHOW -> PointerPolicy.HIDE
                PointerPolicy.HIDE -> PointerPolicy.AUTO
                PointerPolicy.AUTO -> PointerPolicy.SHOW
            }
            PointerPolicyStore.set(activity, next)
            refresh()
        }
        infoCard(
            "Background work",
            "Runs where you can see it.",
            "Work LOOM does runs where you can see it. Timed and scheduled " +
                "work isn't available yet — it comes in a later version.",
        )
    }

    private fun renderPrivacy() {
        toggleRow("Read what's on screen", prefs.screenAnalysisAllowed) {
            prefs.screenAnalysisAllowed = !prefs.screenAnalysisAllowed
            refresh()
        }
        infoCard(
            "Never read without asking",
            "Banking / finance, authenticators, password managers.",
            "Banking and finance apps, authenticators and password managers " +
                "are never read. Passwords and card numbers are hidden before " +
                "LOOM sees anything.",
        )
        // Phase 6 — AuthGate controls (arch §2.14): confirmation strictness
        // and the audit trail of every gated decision.
        settingRow("How often LOOM asks first", prefs.strictnessLabel()) {
            LoomDialogs.options(
                activity, "How often LOOM asks first",
                listOf(
                    "Standard — ask first only before actions that can't be undone" to {
                        prefs.confirmStrictness = 0; refresh()
                    },
                    "Strict — ask first more often, even for routine actions" to {
                        prefs.confirmStrictness = 1; refresh()
                    },
                    "Thorough — ask first for everything except just looking" to {
                        prefs.confirmStrictness = 2; refresh()
                    },
                ),
            )
        }
        settingRow("Approval history", "Every allow, ask-first and no") {
            val entries = activityLog.listByKind("auth", 10)
            val text = if (entries.isEmpty()) {
                "No approvals yet. Every time LOOM asks you first, your " +
                    "answer appears here with its time."
            } else {
                entries.joinToString("\n\n") { e ->
                    val t = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
                        .format(java.util.Date(e.timestamp))
                    "$t\n${e.text}"
                }
            }
            LoomDialogs.info(activity, "Approval history", text)
        }
        // Master §8 — scoped "always allow" grants are revocable here.
        // The count is the live store size. Device-evidence fix
        // (2026-11-05, item 10): per-grant revoke, not just revoke-all
        // — tapping the row lists every grant; tapping one revokes only
        // that grant (after a confirm), "Clear all approvals" keeps the
        // old revoke-all path. Grants never cover model requests,
        // sensitive apps, or financial actions, and never apply under
        // paranoid strictness.
        val grantStore = AuthGrantStore.get(activity)
        settingRow(
            "Always-allow approvals",
            grantStore.count().let { n -> if (n == 0) "None saved" else "$n saved — tap to manage" },
        ) {
            val grants = grantStore.list()
            if (grants.isEmpty()) {
                LoomDialogs.info(
                    activity, "Always-allow approvals",
                    "No always-allow approvals are saved. When you tick " +
                        "\"Always allow…\" on an approval card, it appears " +
                        "here and can be removed one at a time.",
                )
            } else {
                val options = grants.map { g ->
                    "Revoke — ${g.label}" to {
                        LoomDialogs.confirm(
                            activity, "Revoke this approval?",
                            "“${g.label}”\n\n" +
                                "The next time you ask LOOM to do this, it will ask " +
                                "for confirmation again. Other approvals are untouched.",
                            "Revoke",
                        ) {
                            if (grantStore.revoke(g.actionKey, g.scope)) {
                                activityLog.record(
                                    "auth",
                                    "GRANT_REVOKED ${g.actionKey} / ${g.scope} " +
                                        "revoked from Settings",
                                )
                            }
                            refresh()
                        }
                    }
                } + listOf(
                    "Clear all approvals" to {
                        LoomDialogs.confirm(
                            activity, "Clear always-allow approvals?",
                            grants.joinToString("\n") { g -> "• ${g.label}" } +
                                "\n\nClearing removes every approval. The next time " +
                                "you ask LOOM to open one of these apps, it will ask " +
                                "for confirmation again.",
                            "Clear",
                        ) {
                            val removed = grantStore.revokeAll()
                            activityLog.record(
                                "auth",
                                "GRANT_REVOKED_ALL $removed always-allow grant(s) " +
                                    "cleared from Settings",
                            )
                            refresh()
                        }
                    },
                )
                LoomDialogs.options(activity, "Always-allow approvals", options)
            }
        }
        // Device-evidence fix (2026-11-05, item 10): build stamp — the
        // version name is read live from the installed APK, the commit
        // from BuildStamp (bumped per release). Quote both in reports.
        infoCard(
            "Build",
            "${appVersionName()} · ${BuildStamp.COMMIT}",
            "Version and commit — quote these when reporting a problem, " +
                "so the build on your phone can be identified exactly.",
        )
        body.addView(primaryButton("Clear conversations") {
            LoomDialogs.confirm(
                activity, "Clear all conversations?",
                "Every conversation thread will be removed permanently.",
                "Clear",
            ) {
                for (s in conversationStore.list()) conversationStore.delete(s.id)
                activityLog.record("privacy", "Cleared all conversations")
                refresh()
            }
        })
        body.addView(primaryButton("Clear memory") {
            LoomDialogs.confirm(
                activity, "Clear all memory?",
                "Every memory entry in all 6 categories will be removed permanently.",
                "Clear",
            ) {
                for (m in memoryStore.list()) memoryStore.delete(m.id)
                activityLog.record("privacy", "Cleared all memory")
                refresh()
            }
        })
        body.addView(primaryButton("Clear activity log") {
            LoomDialogs.confirm(
                activity, "Clear activity log?",
                "The dashboard activity history will be removed.",
                "Clear",
            ) {
                activityLog.clear()
                refresh()
            }
        })
    }

    // ---------- keep LOOM running (binding survival, Nov 6) ----------

    /**
     * The survival checklist, in-app. Only the helper and battery rows
     * read a live state; Autostart, Recents-lock and Cleaner have no
     * API on this phone, so those rows give plain steps and never a
     * check mark the app cannot verify. Taps open real system screens
     * only where a real intent exists (battery list, app info,
     * accessibility list); the rest show their steps in a dialog.
     */
    private fun renderKeepRunning() {
        infoCard("Keep LOOM running", "So the helper survives the night.", KeepRunningChecklist.INTRO_TEXT)
        val items = KeepRunningChecklist.items(
            ignoringOptimizations = batteryIgnoringOptimizations(),
            flagOn = isA11yFlagOn(),
            hostLive = isHostLive(),
        )
        for (item in items) {
            settingRow(item.title, item.valueLabel) {
                when (item.action) {
                    KeepRunningChecklist.ChecklistAction.OPEN_BATTERY_SETTINGS -> {
                        activity.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                    KeepRunningChecklist.ChecklistAction.OPEN_APP_INFO -> {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", activity.packageName, null),
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                    KeepRunningChecklist.ChecklistAction.OPEN_ACCESSIBILITY_SETTINGS -> {
                        activity.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                    KeepRunningChecklist.ChecklistAction.NONE -> {
                        LoomDialogs.info(activity, item.title, item.detail)
                    }
                }
            }
        }
        // Automatic recovery (Nov 6, opt-in, default OFF): bring LOOM
        // back on its own after the phone stops it — never after David
        // stops it himself. Turning it off cancels any pending attempt.
        val recoveryStore = AutomaticRecoveryStore.get(activity)
        toggleRow(
            AutomaticRecovery.SETTING_TITLE,
            recoveryStore.isAutoRecoverEnabled(),
        ) {
            val turningOn = !recoveryStore.isAutoRecoverEnabled()
            recoveryStore.setAutoRecoverEnabled(turningOn)
            if (!turningOn) AutomaticRecoveryRunner.cancelPending()
            activityLog.record(
                "settings",
                "Automatic recovery → ${if (turningOn) "on" else "off"}",
            )
            refresh()
        }
        infoCard(
            "What it does",
            if (recoveryStore.isAutoRecoverEnabled()) "On." else "Off.",
            AutomaticRecovery.SETTING_EXPLAIN,
        )
    }

    /** Live battery state: true when the system is ignoring battery optimizations for LOOM. */
    private fun batteryIgnoringOptimizations(): Boolean {
        return try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(activity.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** The Settings.Secure flag ONLY — same read Home uses. Never liveness by itself. */
    private fun isA11yFlagOn(): Boolean {
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

    /** LIVE liveness, not the switch — LoomAccessibilityService.isLive(). Never throws. */
    private fun isHostLive(): Boolean {
        return try {
            LoomAccessibilityService.isLive()
        } catch (_: Exception) {
            false
        }
    }

    private fun renderProfile() {
        settingRow("Name", prefs.profileName.ifBlank { "Not set" }) {
            LoomDialogs.input(
                activity, "Profile",
                listOf("Your name" to prefs.profileName),
                "Save",
            ) { values ->
                prefs.profileName = values[0].trim()
                refresh()
            }
        }
        infoCard(
            "How it's used",
            "Greetings.",
            "Your name personalizes the Home greeting. Nothing else.",
        )
    }

    private fun renderNotifications() {
        toggleRow("Digest mode", prefs.digestMode) {
            prefs.digestMode = !prefs.digestMode
            refresh()
        }
        settingRow("Notification history", "Open") {
            navigate(LoomDestination.Notifications)
        }
        infoCard(
            "Policy",
            "Only what matters.",
            "Finished work, problems, times LOOM asks you first, and big steps. " +
                "Never a running commentary, and notifications never speak out loud.",
        )
    }

    // ---------- builders ----------

    private fun settingRow(title: String, value: String, onTap: () -> Unit) {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            LoomUi.text(context, title, R.style.LoomText_Headline).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(LoomUi.text(context, value, R.style.LoomText_Body, R.color.loom_text_muted))
        row.addView(TextView(context).apply {
            text = " ›"
            setTextAppearance(R.style.LoomText_Title)
            setTextColor(context.getColor(R.color.loom_accent_bright))
        })
        card.addView(row)
        card.setOnClickListener { onTap() }
        body.addView(card, rowParams())
    }

    private fun toggleRow(title: String, on: Boolean, onTap: () -> Unit) {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            LoomUi.text(context, title, R.style.LoomText_Headline).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            LoomUi.text(
                context, if (on) "ON" else "OFF",
                R.style.LoomText_Body,
                if (on) R.color.loom_success else R.color.loom_text_muted,
            ),
        )
        card.addView(row)
        card.setOnClickListener { onTap() }
        body.addView(card, rowParams())
    }

    /**
     * Stage 2B (D3a): a row that cycles through a small set of named values
     * (for the 3-state pointer policy — a plain ON/OFF toggle cannot
     * represent it). Shows the current value; each tap advances to the next.
     */
    private fun cycleRow(title: String, value: String, onTap: () -> Unit) {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            LoomUi.text(context, title, R.style.LoomText_Headline).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            LoomUi.text(
                context, value,
                R.style.LoomText_Body,
                R.color.loom_accent,
            ),
        )
        card.addView(row)
        card.setOnClickListener { onTap() }
        body.addView(card, rowParams())
    }

    private fun infoCard(title: String, value: String, blurb: String) {
        val card = LoomCardView(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, title, R.style.LoomText_Headline))
        col.addView(LoomUi.text(context, value, R.style.LoomText_Body, R.color.loom_text_secondary))
        col.addView(LoomUi.text(context, blurb, R.style.LoomText_Caption, R.color.loom_text_muted))
        card.addView(col)
        body.addView(card, rowParams())
    }

    private fun primaryButton(label: String, onTap: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 10) }
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = LoomUi.dp(context, 10) }
}
