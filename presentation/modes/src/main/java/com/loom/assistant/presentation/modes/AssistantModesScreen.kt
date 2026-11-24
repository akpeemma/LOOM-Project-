package com.loom.assistant.presentation.modes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.loom.assistant.core.common.LoomIntents
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Phase 4 — in-app Assistant Modes screen (settings switching path).
 *
 * Shows the three modes with Activate buttons, the overlay-permission state
 * with a grant button, and Start/Stop for the floating overlay. Every
 * control performs its action — no placeholders.
 */
class AssistantModesScreen(
    context: Context,
    private val onOpenOverlaySettings: () -> Unit,
) : ScrollView(context) {

    init {
        isFillViewport = true
        val density = resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20f * density).toInt()
            setPadding(pad, pad, pad, pad * 2)
        }
        addView(
            root,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        root.addView(
            LoomUi.text(
                context, "Assistant Modes", R.style.LoomText_Title, gravity = Gravity.CENTER,
            ),
        )
        root.addView(
            LoomUi.text(
                context,
                "One intelligence, three appearances. The character and orb " +
                    "float over any app; invisible mode keeps only the edge light.",
                R.style.LoomText_Body, R.color.loom_text_secondary, Gravity.CENTER,
            ).apply {
                val p = (12f * density).toInt()
                setPadding(p, p, p, p)
            },
        )

        for (mode in AssistantMode.values()) {
            root.addView(modeCard(context, mode))
        }

        root.addView(permissionCard(context))
        root.addView(serviceCard(context))
    }

    private fun modeCard(context: Context, mode: AssistantMode): View {
        val density = resources.displayMetrics.density
        // Stage 2A.1 (D6): "● Active" reads the LIVE bound state — the pref
        // alone could claim a mode whose view never attached (R7).
        val bound = ModeController.boundMode()
        val active = bound == mode
        return LoomCardView(context).apply {
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16f * density).toInt()
                setPadding(p, p, p, p)
            }
            inner.addView(
                LoomUi.text(
                    context,
                    (if (active) "● " else "○ ") + ModeController.label(mode),
                    R.style.LoomText_Headline,
                    if (active) R.color.loom_accent_bright else R.color.loom_text_primary,
                ),
            )
            inner.addView(
                LoomUi.text(
                    context, modeDescription(mode),
                    R.style.LoomText_Body, R.color.loom_text_secondary,
                ),
            )
            inner.addView(
                primaryButton(
                    context,
                    if (active) "Active" else "Activate",
                    enabled = !active,
                ) {
                    // Stage 2A.1 (D1): entry C now goes through the single
                    // authoritative applier — the raw ACTION_REFRESH_OVERLAY
                    // intent path (no verification at all) is deleted.
                    ModeController.applyMode(context, mode) { result ->
                        Toast.makeText(
                            context,
                            ModeController.ackText(result, ModeController.shortLabel(mode)),
                            Toast.LENGTH_LONG,
                        ).show()
                        (context as? Activity)?.recreate()
                    }
                },
            )
            addView(inner)
        }
    }

    private fun modeDescription(mode: AssistantMode): String = when (mode) {
        AssistantMode.CHARACTER ->
            "Small chrome companion floating over any app. Drag to move, tap for the panel."
        AssistantMode.ORB ->
            "Glowing blue-violet orb with the infinity mark. Tap to talk."
        AssistantMode.INVISIBLE ->
            "Nothing on screen but the edge light. Pointers come from the screen edges."
    }

    private fun permissionCard(context: Context): View {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        return LoomCardView(context).apply {
            val density = resources.displayMetrics.density
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16f * density).toInt()
                setPadding(p, p, p, p)
            }
            inner.addView(
                LoomUi.text(context, "Draw over other apps", R.style.LoomText_Headline),
            )
            inner.addView(
                LoomUi.text(
                    context,
                    if (granted) "Allowed — LOOM can float over other apps."
                    else "Not allowed yet — LOOM can't float over other apps until you allow it.",
                    R.style.LoomText_Body,
                    if (granted) R.color.loom_success else R.color.loom_warning,
                ),
            )
            if (!granted) {
                inner.addView(
                    primaryButton(context, "Allow drawing over apps") { onOpenOverlaySettings() },
                )
            }
            addView(inner)
        }
    }

    private fun serviceCard(context: Context): View {
        val running = ModeController.isOverlayRunning(context)
        // Stage 2A.1 (D6): honest service state — the old text claimed "the
        // active mode is floating now" from prefs alone (R7).
        val liveMode = ModeController.boundMode()
        val status = when {
            !running -> "Stopped — start it to float LOOM over other apps."
            liveMode != null -> "Running — ${ModeController.label(liveMode)} is floating now."
            else -> "Running — couldn't tell which mode is showing."
        }
        return LoomCardView(context).apply {
            val density = resources.displayMetrics.density
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16f * density).toInt()
                setPadding(p, p, p, p)
            }
            inner.addView(
                LoomUi.text(context, "Floating assistant", R.style.LoomText_Headline),
            )
            inner.addView(
                LoomUi.text(
                    context,
                    status,
                    R.style.LoomText_Body, R.color.loom_text_secondary,
                ),
            )
            inner.addView(
                primaryButton(
                    context,
                    if (running) "Stop floating" else "Start floating",
                ) {
                    sendOverlayAction(
                        context,
                        if (running) LoomIntents.ACTION_STOP_OVERLAY
                        else LoomIntents.ACTION_START_OVERLAY,
                    )
                    (getContext() as? Activity)?.recreate()
                },
            )
            addView(inner)
        }
    }

    /** Control the overlay purely by intent — no service import needed. */
    private fun sendOverlayAction(context: Context, action: String) {
        val i = Intent(action).apply {
            setClassName(
                context.packageName,
                "com.loom.assistant.platform.services.LoomOverlayService",
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            action == LoomIntents.ACTION_START_OVERLAY
        ) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }

    private fun primaryButton(
        context: Context,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(context, null, 0, R.style.Loom_Button_Primary).apply {
        text = label
        isEnabled = enabled
        setOnClickListener { onClick() }
        val density = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = (12f * density).toInt()
        }
    }
}
