package com.loom.assistant.presentation.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small shared UI builders so Phase 3 screens stay visually consistent. */
object LoomUi {

    fun dp(context: Context, dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics,
        ).toInt()

    fun sp(context: Context, sp: Int): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics,
        )

    fun text(
        context: Context,
        text: String,
        style: Int,
        color: Int = R.color.loom_text_primary,
        gravity: Int = Gravity.START,
    ): TextView = TextView(context).apply {
        setTextAppearance(style)
        setTextColor(context.getColor(color))
        this.text = text
        this.gravity = gravity
    }

    fun screenRoot(context: Context): ScrollView {
        val sv = ScrollView(context)
        sv.isFillViewport = true
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = context.resources.getDimensionPixelSize(R.dimen.loom_space_24)
            setPadding(pad, pad, pad, pad * 3)
        }
        sv.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        sv.setTag(R.id.loom_ui_inner_tag, inner)
        return sv
    }

    @Suppress("UNCHECKED_CAST")
    fun innerOf(root: ScrollView): LinearLayout =
        root.getTag(R.id.loom_ui_inner_tag) as LinearLayout

    fun addCenteredPill(inner: LinearLayout, text: String) {
        val pill = LoomHeaderPill(inner.context).apply { this.text = text }
        inner.addView(
            pill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = inner.context.resources.getDimensionPixelSize(R.dimen.loom_space_20)
            },
        )
    }

    fun logoRow(context: Context, sizeDp: Int = 56): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val s = dp(context, sizeDp)
        row.addView(
            ImageView(context).apply { setImageResource(R.drawable.ic_loom_logo) },
            LinearLayout.LayoutParams(s, s),
        )
        return row
    }

    /** Rounded bubble background. */
    fun bubbleDrawable(context: Context, color: Int, radiusDp: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            setColor(context.getColor(color))
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    fun timeAgo(now: Long, then: Long): String {
        val d = now - then
        val m = d / 60000
        if (m < 1) return "just now"
        if (m < 60) return "${m}m ago"
        val h = m / 60
        if (h < 24) return "${h}h ago"
        val days = h / 24
        if (days < 7) return "${days}d ago"
        return "${days / 7}w ago"
    }

    fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
    }
}
