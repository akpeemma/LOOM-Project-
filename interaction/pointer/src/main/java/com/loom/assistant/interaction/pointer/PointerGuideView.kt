package com.loom.assistant.interaction.pointer

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet

/**
 * Stage 2C (A2) — thin compatibility wrapper.
 *
 * All drawing now lives in [GuidanceRenderer] (beam, anchor dot, target
 * ring, tooltip, choreography animations). This class keeps the Phase 4 API
 * — [Step], `showStep(Step)`, `hide()`, `isShowing` — that the overlay host
 * (LoomOverlayService) uses, unchanged.
 */
class PointerGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : GuidanceRenderer(context, attrs, defStyleAttr) {

    data class Step(
        val anchorX: Float,
        val anchorY: Float,
        val target: RectF,
        val stepIndex: Int,   // 1-based
        val stepCount: Int,
        val instruction: String,
    )

    fun showStep(step: Step) {
        super.showStep(
            step.anchorX,
            step.anchorY,
            step.target,
            step.stepIndex,
            step.stepCount,
            step.instruction,
        )
    }
}
