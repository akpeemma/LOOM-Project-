package com.loom.assistant.interaction.pointer

import android.graphics.RectF

/**
 * JVM test stubs for the Android View-based pointer view.
 *
 * These stand in for the real PointerGuideView / PointerGuideViewHolder
 * (which need the Android View stack) when compiling GuidanceSessionTest
 * sources on the JVM. They are compiled INSTEAD of the real view files —
 * never alongside them — so the fully-qualified names match deliberately.
 */
class PointerGuideView {
    data class Step(
        val anchorX: Float,
        val anchorY: Float,
        val target: RectF,
        val stepIndex: Int, // 1-based
        val stepCount: Int,
        val instruction: String,
    )
}

// NOTE: PointerGuideViewHolder is NOT stubbed here — the real object at the
// bottom of GuidanceSession.kt is compiled in the test (it only references
// the PointerGuideView stub above), so the holder behaves identically.
