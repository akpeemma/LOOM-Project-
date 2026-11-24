package com.loom.assistant.presentation.modes

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.loom.assistant.presentation.designsystem.R

/**
 * Phase 4 — tap panel for the floating character / orb (lockdown card 6
 * pattern, applied to the overlay).
 *
 * Small dark card: Voice chat, Open LOOM, the three mode switches, Close.
 * Every button performs its action via [Listener] — no dead controls.
 */
class ModePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onVoiceChat()
        fun onOpenApp()
        fun onSwitchMode(mode: AssistantMode)
        fun onClose()
    }

    var listener: Listener? = null

    init {
        orientation = VERTICAL
        val density = resources.displayMetrics.density
        background = GradientDrawable().apply {
            setColor(0xF2141226.toInt())
            cornerRadius = 18f * density
            setStroke((1.5f * density).toInt(), context.getColor(R.color.loom_stroke_glow))
        }
        val pad = (16f * density).toInt()
        setPadding(pad, pad, pad, pad)

        addRow("Voice chat", R.color.loom_accent_bright) { listener?.onVoiceChat() }
        addRow("Open LOOM", R.color.loom_text_primary) { listener?.onOpenApp() }
        addDivider()
        // Stage 2A.1 (D6): honest active mark — live bound state first,
        // persisted selection as fallback.
        val active = ModeController.boundMode() ?: ModeController.getMode(context)
        for (mode in AssistantMode.values()) {
            val mark = if (mode == active) "● " else "○ "
            addRow(mark + ModeController.label(mode), R.color.loom_text_secondary) {
                listener?.onSwitchMode(mode)
            }
        }
        addDivider()
        addRow("Close", R.color.loom_text_muted) { listener?.onClose() }
    }

    private fun addDivider() {
        val density = resources.displayMetrics.density
        addView(android.view.View(context).apply {
            setBackgroundColor(context.getColor(R.color.loom_stroke))
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, (1f * density).toInt(),
            ).apply {
                topMargin = (8f * density).toInt()
                bottomMargin = (8f * density).toInt()
            }
        })
    }

    private fun addRow(label: String, colorRes: Int, onClick: () -> Unit) {
        val density = resources.displayMetrics.density
        addView(TextView(context).apply {
            text = label
            setTextColor(context.getColor(colorRes))
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(
                (8f * density).toInt(), (10f * density).toInt(),
                (8f * density).toInt(), (10f * density).toInt(),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        })
    }
}
