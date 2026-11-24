package com.loom.assistant.interaction.edgelight

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.presentation.designsystem.R

/**
 * Phase 4 — edge-light communication (arch §2.4, lockdown card 16).
 *
 * A soft blue-violet glow running INSIDE the screen along the inner display
 * edges. Six distinct pulse patterns, one per intelligence state:
 *  LISTENING  — bright wave traveling around the edges
 *  THINKING   — slow breathing pulse, medium alpha
 *  ANALYZING  — alternating corner pulses
 *  SPEAKING   — faster bright pulse
 *  EXECUTING  — steady glow with a sweeping highlight
 *  COMPLETED  — one bright flash, then auto-dim to invisible
 *  IDLE       — invisible
 *
 * Subscribes to [LoomEventBus]; the overlay host adds this view ONLY in
 * INVISIBLE mode, where it is the sole visual. It is removed whenever the
 * mode changes away (correction pass — it previously leaked into Orb and
 * Character modes). Pure Canvas, no assets.
 */
class EdgeLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class EdgeState {
        IDLE, LISTENING, THINKING, ANALYZING, SPEAKING, EXECUTING, COMPLETED, PAUSED
    }

    var edgeState: EdgeState = EdgeState.IDLE
        set(value) {
            if (field == value) return
            field = value
            restartAnimators()
            invalidate()
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val accent = context.getColor(R.color.loom_accent)
    private val accentBright = context.getColor(R.color.loom_accent_bright)

    private var phase = 0f
    private var phaseAnimator: ValueAnimator? = null
    private var dimAnimator: ValueAnimator? = null
    private var dimAlpha = 1f

    private val busListener: (LoomStateEvent) -> Unit = { event ->
        edgeState = when (event) {
            is LoomStateEvent.Idle -> EdgeState.IDLE
            is LoomStateEvent.Listening -> EdgeState.LISTENING
            is LoomStateEvent.Thinking -> EdgeState.THINKING
            is LoomStateEvent.Analyzing -> EdgeState.ANALYZING
            is LoomStateEvent.Speaking -> EdgeState.SPEAKING
            is LoomStateEvent.Executing -> EdgeState.EXECUTING
            is LoomStateEvent.Completed -> EdgeState.COMPLETED
            is LoomStateEvent.Paused -> EdgeState.PAUSED
            // Stage 2A: an error must never leave the edge light stuck in an
            // active state — fall back to IDLE.
            is LoomStateEvent.Error -> EdgeState.IDLE
            else -> edgeState
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LoomEventBus.subscribe(busListener)
        restartAnimators()
    }

    override fun onDetachedFromWindow() {
        LoomEventBus.unsubscribe(busListener)
        phaseAnimator?.cancel()
        dimAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun restartAnimators() {
        phaseAnimator?.cancel()
        dimAnimator?.cancel()
        dimAlpha = 1f
        if (edgeState == EdgeState.IDLE) return
        val duration = when (edgeState) {
            EdgeState.LISTENING -> 1800L
            EdgeState.THINKING -> 2600L
            EdgeState.ANALYZING -> 2200L
            EdgeState.SPEAKING -> 1100L
            EdgeState.EXECUTING -> 1500L
            EdgeState.COMPLETED -> 900L
            EdgeState.PAUSED -> 3200L
            EdgeState.IDLE -> 0L
        }
        phaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = if (edgeState == EdgeState.COMPLETED) 0
            else ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                if (edgeState == EdgeState.COMPLETED && phase >= 1f) {
                    startDim()
                }
                invalidate()
            }
            start()
        }
    }

    /** Completed flashes once, then fades the light out. */
    private fun startDim() {
        dimAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 700L
            interpolator = LinearInterpolator()
            addUpdateListener {
                dimAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (edgeState == EdgeState.IDLE) return

        val inset = 3f * resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val half = strokePaint.strokeWidth / 2f

        // Base alpha per state.
        val baseAlpha = when (edgeState) {
            EdgeState.LISTENING -> 0.95f
            EdgeState.THINKING -> 0.55f + 0.25f * phase
            EdgeState.ANALYZING -> 0.7f
            EdgeState.SPEAKING -> 0.75f + 0.25f * phase
            EdgeState.EXECUTING -> 0.85f
            EdgeState.COMPLETED -> 1f
            EdgeState.PAUSED -> 0.35f
            EdgeState.IDLE -> 0f
        } * dimAlpha

        strokePaint.strokeWidth = 3f * resources.displayMetrics.density

        // Traveling highlight position for LISTENING / EXECUTING.
        val perim = 2 * (w + h)
        val head = phase * perim

        val path = Path().apply {
            moveTo(inset, inset)
            lineTo(w - inset, inset)
            lineTo(w - inset, h - inset)
            lineTo(inset, h - inset)
            close()
        }

        // Soft under-glow: draw the rounded rect a few times with low alpha.
        strokePaint.shader = null
        strokePaint.color = accent
        strokePaint.alpha = (baseAlpha * 90).toInt()
        for (k in 1..3) {
            strokePaint.strokeWidth = (3 + k * 5) * resources.displayMetrics.density
            canvas.drawPath(path, strokePaint)
        }

        // Main line with a traveling bright head for wave states.
        strokePaint.strokeWidth = 3f * resources.displayMetrics.density
        if (edgeState == EdgeState.LISTENING || edgeState == EdgeState.EXECUTING) {
            val grad = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(accent, accentBright, accent),
                floatArrayOf(0f, ((head % perim) / perim).toFloat(), 1f),
                Shader.TileMode.CLAMP,
            )
            strokePaint.shader = grad
        } else {
            strokePaint.shader = null
            strokePaint.color = if (edgeState == EdgeState.ANALYZING) accentBright else accent
        }
        strokePaint.alpha = (baseAlpha * 255).toInt().coerceIn(0, 255)
        // Slight corner emphasis for ANALYZING: draw twice, second offset blink.
        canvas.drawPath(path, strokePaint)
        if (edgeState == EdgeState.ANALYZING && phase > 0.5f) {
            strokePaint.alpha = (baseAlpha * 140).toInt().coerceIn(0, 255)
            canvas.drawPath(path, strokePaint)
        }
        strokePaint.shader = null
        strokePaint.alpha = 255
    }
}
