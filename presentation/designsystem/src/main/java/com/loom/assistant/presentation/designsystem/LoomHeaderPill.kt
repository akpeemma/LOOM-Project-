package com.loom.assistant.presentation.designsystem

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/** LOOM header pill — the labeled capsule at the top of every screen. */
class LoomHeaderPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    init {
        // Apply the Loom.HeaderPill style attributes manually (framework TextView).
        setTextAppearance(R.style.LoomText_Label)
        background = context.getDrawable(R.drawable.loom_header_pill)
        val hPad = resources.getDimensionPixelSize(R.dimen.loom_space_20)
        val vPad = resources.getDimensionPixelSize(R.dimen.loom_space_8)
        setPadding(hPad, vPad, hPad, vPad)
        gravity = android.view.Gravity.CENTER
        isAllCaps = true
    }
}
