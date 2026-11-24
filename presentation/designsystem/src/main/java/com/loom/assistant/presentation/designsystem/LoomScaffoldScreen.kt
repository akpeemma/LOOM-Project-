package com.loom.assistant.presentation.designsystem

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Honest scaffold for a locked destination whose content is NOT built yet.
 * Shows the destination's header pill + label in the LOOM design system and
 * names the phase that will deliver it. This is scaffolding — never a
 * completed feature.
 */
class LoomScaffoldScreen(
    context: Context,
    destination: LoomDestination,
) : ScrollView(context) {

    init {
        val pad = resources.getDimensionPixelSize(R.dimen.loom_space_24)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val pill = LoomHeaderPill(context).apply { text = destination.label }
        val pillParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_24) }
        inner.addView(pill, pillParams)

        val card = LoomCardView(context)
        val note = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Body)
            gravity = Gravity.CENTER
            text = "Design locked (v1.1).\nContent arrives in Phase ${destination.phase}."
        }
        card.addView(note)
        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        inner.addView(card, cardParams)

        addView(inner)
    }
}
