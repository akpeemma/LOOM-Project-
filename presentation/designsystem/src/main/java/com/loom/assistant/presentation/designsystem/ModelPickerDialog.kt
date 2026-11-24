package com.loom.assistant.presentation.designsystem

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.loom.assistant.intelligence.llm.ModelRegistry
import com.loom.assistant.intelligence.llm.ModelSelection

/**
 * "Choose model" picker (lockdown v1.1 correction #4).
 * No fixed model name anywhere; default is chosen later by David.
 */
object ModelPickerDialog {

    fun show(
        activity: Activity,
        selection: ModelSelection,
        onOpenSettings: (() -> Unit)? = null,
        onChanged: () -> Unit,
    ) {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = LoomUi.dp(activity, 8)
            setPadding(p, p, p, p)
        }

        fun addRow(title: String, subtitle: String, selected: Boolean, onTap: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                val v = LoomUi.dp(activity, 12)
                setPadding(v, v, v, v)
            }
            val mark = if (selected) "● " else "○ "
            row.addView(
                LoomUi.text(
                    activity, mark + title,
                    R.style.LoomText_Body,
                    if (selected) R.color.loom_accent_bright else R.color.loom_text_primary,
                ),
            )
            if (subtitle.isNotEmpty()) {
                row.addView(
                    LoomUi.text(
                        activity, subtitle,
                        R.style.LoomText_Caption, R.color.loom_text_muted,
                    ),
                )
            }
            row.setOnClickListener { onTap() }
            list.addView(row)
            val div = TextView(activity).apply {
                setBackgroundColor(activity.getColor(R.color.loom_stroke))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LoomUi.dp(activity, 1),
                )
            }
            list.addView(div)
        }

        var dialog: AlertDialog? = null
        addRow(
            "Choose model",
            "No default — you pick after comparing",
            selection.selectedId == null,
        ) {
            selection.selectedId = null
            dialog?.dismiss()
            onChanged()
        }
        for (m in ModelRegistry.models) {
            addRow(
                m.displayName,
                "${m.provider} — ${m.note}",
                selection.selectedId == m.id,
            ) {
                selection.selectedId = m.id
                dialog?.dismiss()
                onChanged()
            }
        }
        list.addView(
            LoomUi.text(
                activity,
                "Provider connection needs an API key — add one under Settings → AI Connections.",
                R.style.LoomText_Caption, R.color.loom_text_muted, Gravity.CENTER,
            ).apply {
                setPadding(0, LoomUi.dp(activity, 12), 0, 0)
            },
        )

        dialog = AlertDialog.Builder(activity, R.style.Loom_Dialog)
            .setTitle("AI Model")
            .setView(list)
            .create()
        if (onOpenSettings != null) {
            // Extra row inside the dialog is awkward; expose it via the neutral button.
            dialog.setButton(
                AlertDialog.BUTTON_NEUTRAL, "AI Connections…",
            ) { _, _ -> onOpenSettings() }
        }
        dialog.show()
    }
}
