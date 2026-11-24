package com.loom.assistant.presentation.designsystem

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * LOOM card: dark rounded surface with blue-violet glow rim.
 * Content views are added as children.
 */
open class LoomCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        background = context.getDrawable(R.drawable.loom_card_background)
        val pad = resources.getDimensionPixelSize(R.dimen.loom_space_16)
        setPadding(pad, pad, pad, pad)
        clipToPadding = false
    }
}
