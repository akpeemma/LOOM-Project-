package com.loom.assistant.platform.services

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.loom.assistant.presentation.designsystem.R

/**
 * Phase 6 — in-overlay confirmation card (arch §2.14: "an in-overlay card,
 * not a blocking dialog").
 *
 * Shown by the overlay host when the AuthGate parks a CONFIRM action.
 * David taps Approve or Deny; the host relays to AuthGate.resolve().
 * Styled to the lockdown v1.1 dark-premium identity.
 *
 * Master §8 — scoped "always allow" opt-in: when the gate offers one
 * ([bind]'s [grantOffer], e.g. "Always allow opening YouTube"), a
 * checkbox appears above the buttons and its state rides along with
 * Approve ([Listener.onApprove] with alwaysAllow). The checkbox is
 * shown ONLY when the gate offered it — direct user command, eligible
 * key, narrow scope, never under paranoid strictness — and the gate
 * re-validates every condition before storing any grant. The
 * notification fallback has no checkbox: grants are an on-card opt-in
 * only, never a notification tap.
 */
class AuthConfirmationCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onApprove(alwaysAllow: Boolean)
        fun onDeny()
    }

    var listener: Listener? = null

    private val titleView: TextView
    private val detailView: TextView
    private val alwaysAllowCheck: CheckBox

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

        val header = TextView(context).apply {
            text = "LOOM needs your approval"
            setTextAppearance(R.style.LoomText_Caption)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(header)

        titleView = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Headline)
            setTextColor(context.getColor(R.color.loom_text_primary))
            gravity = Gravity.CENTER_HORIZONTAL
            val m = (8f * density).toInt()
            setPadding(0, m, 0, 0)
        }
        addView(titleView)

        detailView = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_text_secondary))
            gravity = Gravity.CENTER_HORIZONTAL
            val m = (8f * density).toInt()
            setPadding(0, m, 0, m)
        }
        addView(detailView)

        // Master §8 — "always allow" opt-in, visible only when the gate
        // offered one for this request (bind sets text + visibility).
        alwaysAllowCheck = CheckBox(context).apply {
            setTextAppearance(R.style.LoomText_Caption)
            setTextColor(context.getColor(R.color.loom_text_secondary))
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = GONE
        }
        addView(alwaysAllowCheck)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val btnParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            val m = (6f * density).toInt()
            setMargins(m, 0, m, 0)
        }
        val deny = Button(context).apply {
            text = "Deny"
            setOnClickListener { listener?.onDeny() }
        }
        val approve = Button(context).apply {
            text = "Approve"
            setOnClickListener { listener?.onApprove(alwaysAllowCheck.isChecked) }
        }
        row.addView(deny, btnParams)
        row.addView(approve, btnParams)
        addView(row)
    }

    fun bind(title: String, detail: String, grantOffer: String? = null) {
        titleView.text = title
        detailView.text = detail
        if (grantOffer != null) {
            alwaysAllowCheck.text = grantOffer
            alwaysAllowCheck.isChecked = false
            alwaysAllowCheck.visibility = VISIBLE
        } else {
            alwaysAllowCheck.isChecked = false
            alwaysAllowCheck.visibility = GONE
        }
    }
}
