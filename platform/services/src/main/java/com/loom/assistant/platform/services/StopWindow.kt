package com.loom.assistant.platform.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Stage 2C (§5.3) — the separate touchable stop window (halt path P3).
 *
 * Small floating view shown ONLY while a sequence is active; tapping it
 * halts the sequence. It cannot live on the NOT_TOUCHABLE pointer layer, so
 * it is its own view. 56dp, high-contrast stop glyph, no text rendering
 * dependency.
 *
 * This file owns the view only. Host add/remove (window type, position via
 * FloatingPositionStore, show/hide on sequence state) is Worker F's job in
 * LoomOverlayService — this file does NOT touch it.
 */
class StopWindow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Fired on tap — the host routes it to layer.halt(HaltSource.STOP_WINDOW). */
    var onTap: (() -> Unit)? = null

    private val sizePx: Int = (56 * resources.displayMetrics.density).toInt()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0000")
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    fun show() {
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onTap?.invoke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r, backgroundPaint)
        // Stop glyph: filled square, ~44% of the diameter.
        val g = sizePx * 0.22f
        canvas.drawRect(r - g, r - g, r + g, r + g, glyphPaint)
    }
}
