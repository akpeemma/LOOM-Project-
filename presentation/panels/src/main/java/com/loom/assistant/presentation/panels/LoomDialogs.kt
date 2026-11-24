package com.loom.assistant.presentation.panels

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/** Shared dialog builders for the Phase 5 panel screens. */
object LoomDialogs {

    /**
     * Multi-field input dialog. [fields] = label to initial value.
     * Calls [onOk] with the entered values in order (not called when cancelled).
     */
    fun input(
        activity: Activity,
        title: String,
        fields: List<Pair<String, String>>,
        okLabel: String = "Save",
        onOk: (List<String>) -> Unit,
    ) {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = LoomUi.dp(activity, 16)
            setPadding(p, p / 2, p, p / 2)
        }
        val inputs = fields.map { (label, initial) ->
            col.addView(
                LoomUi.text(activity, label, R.style.LoomText_Caption, R.color.loom_text_muted)
                    .apply { setPadding(0, LoomUi.dp(activity, 8), 0, 4) },
            )
            val et = EditText(activity).apply {
                setText(initial)
                setHintTextColor(activity.getColor(R.color.loom_text_muted))
                setTextColor(activity.getColor(R.color.loom_text_primary))
                background = LoomUi.bubbleDrawable(activity, R.color.loom_surface_2, 12)
                val h = LoomUi.dp(activity, 12)
                setPadding(h, h, h, h)
            }
            col.addView(
                et,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            et
        }
        AlertDialog.Builder(activity, R.style.Loom_Dialog)
            .setTitle(title)
            .setView(col)
            .setPositiveButton(okLabel) { d, _ ->
                onOk(inputs.map { it.text.toString() })
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    /** Password-style single-field input: typed text is masked. */
    fun secureInput(
        activity: Activity,
        title: String,
        hint: String,
        okLabel: String = "Save",
        onOk: (String) -> Unit,
    ) {
        val et = EditText(activity).apply {
            setHint(hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHintTextColor(activity.getColor(R.color.loom_text_muted))
            setTextColor(activity.getColor(R.color.loom_text_primary))
            background = LoomUi.bubbleDrawable(activity, R.color.loom_surface_2, 12)
            val h = LoomUi.dp(activity, 12)
            setPadding(h, h, h, h)
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = LoomUi.dp(activity, 16)
            setPadding(p, p / 2, p, p / 2)
            addView(
                et,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(activity, R.style.Loom_Dialog)
            .setTitle(title)
            .setMessage("Stored encrypted in the Android Keystore. LOOM never logs or displays it.")
            .setView(col)
            .setPositiveButton(okLabel) { d, _ ->
                onOk(et.text.toString())
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        okLabel: String = "Confirm",
        onOk: () -> Unit,
    ) {        AlertDialog.Builder(activity, R.style.Loom_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(okLabel) { d, _ ->
                onOk()
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    /** Simple option list; each option runs its action and closes. */
    fun options(
        activity: Activity,
        title: String,
        options: List<Pair<String, () -> Unit>>,
    ) {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = LoomUi.dp(activity, 8)
            setPadding(p, p, p, p)
        }
        var dialog: AlertDialog? = null
        for ((label, action) in options) {
            val row = TextView(activity).apply {
                text = label
                setTextAppearance(R.style.LoomText_Body)
                setTextColor(activity.getColor(R.color.loom_text_primary))
                gravity = Gravity.START
                val v = LoomUi.dp(activity, 14)
                val h = LoomUi.dp(activity, 16)
                setPadding(h, v, h, v)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog?.dismiss()
                    action()
                }
            }
            col.addView(row)
        }
        dialog = AlertDialog.Builder(activity, R.style.Loom_Dialog)
            .setTitle(title)
            .setView(col)
            .setNegativeButton("Close") { d, _ -> d.dismiss() }
            .create()
        dialog.show()
    }

    fun info(activity: Activity, title: String, message: String) {
        AlertDialog.Builder(activity, R.style.Loom_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }
}
