package com.loom.assistant.presentation.modes

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.loom.assistant.presentation.designsystem.R

/**
 * Phase 4 — floating orb (arch §2.2, lockdown card 15).
 *
 * The TRUE orb: glowing blue-violet with the infinity mark inside, drawn at
 * icon scale (~40dp). States: IDLE (slow breathing), LISTENING (expanding
 * rings), THINKING (orbiting dot), SPEAKING (fast pulse). Tap = talk.
 * Draggable like the character. Pure Canvas, no assets.
 */
class FloatingOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, PAUSED, ERROR }

    var orbState: OrbState = OrbState.IDLE
        set(value) {
            field = value
            restartPulse()
            invalidate()
        }

    /** Tap = talk. */
    var onTap: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val accent = context.getColor(R.color.loom_accent)
    private val accentBright = context.getColor(R.color.loom_accent_bright)
    private val accentGlow = context.getColor(R.color.loom_accent_glow)
    private val silver = context.getColor(R.color.loom_silver)

    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var ringPhase = 0f
    private var ringAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val touchSlop = 12f * resources.displayMetrics.density

    init {
        restartPulse()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop) dragging = true
                if (dragging) {
                    onDrag?.invoke(dx, dy)
                    downX = event.rawX; downY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) onTap?.invoke()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun restartPulse() {
        pulseAnimator?.cancel()
        val duration = when (orbState) {
            OrbState.IDLE -> 2600L
            OrbState.LISTENING -> 1400L
            OrbState.THINKING -> 900L
            OrbState.SPEAKING -> 700L
            OrbState.PAUSED -> 3200L
            OrbState.ERROR -> 500L
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
        ringAnimator?.cancel()
        if (orbState == OrbState.LISTENING) {
            ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = 1600L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { ringPhase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = (minOf(width, height) / 2f) * 0.62f
        val r = base * (1f + 0.06f * pulse)

        // Aura.
        paint.shader = RadialGradient(
            cx, cy, r * 2.2f,
            intArrayOf(accentGlow, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 2.2f, paint)

        // Core.
        paint.shader = RadialGradient(
            cx - r * 0.25f, cy - r * 0.25f, r * 1.15f,
            intArrayOf(accentBright, accent, 0xFF2A2358.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Infinity mark in silver-white.
        paint.color = silver
        paint.alpha = 235
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.14f
        paint.strokeCap = Paint.Cap.ROUND
        val iw = r * 0.62f
        val ih = r * 0.34f
        val path = Path()
        // Left loop.
        path.moveTo(cx, cy)
        path.cubicTo(cx - iw * 0.5f, cy - ih, cx - iw, cy - ih * 0.6f, cx - iw, cy)
        path.cubicTo(cx - iw, cy + ih * 0.6f, cx - iw * 0.5f, cy + ih, cx, cy)
        // Right loop.
        path.cubicTo(cx + iw * 0.5f, cy - ih, cx + iw, cy - ih * 0.6f, cx + iw, cy)
        path.cubicTo(cx + iw, cy + ih * 0.6f, cx + iw * 0.5f, cy + ih, cx, cy)
        canvas.drawPath(path, paint)
        paint.alpha = 255

        // Listening rings.
        if (orbState == OrbState.LISTENING) {
            ringPaint.color = accentBright
            ringPaint.strokeWidth = 3f * resources.displayMetrics.density
            for (k in 0..1) {
                val ph = (ringPhase + k * 0.5f) % 1f
                ringPaint.alpha = ((1f - ph) * 160).toInt()
                canvas.drawCircle(cx, cy, r * (1.15f + ph * 0.9f), ringPaint)
            }
            ringPaint.alpha = 255
        }
        // Thinking: orbiting dot.
        if (orbState == OrbState.THINKING) {
            val a = pulse * Math.PI * 2
            ringPaint.color = accentBright
            ringPaint.style = Paint.Style.FILL
            canvas.drawCircle(
                cx + Math.cos(a).toFloat() * r * 1.35f,
                cy + Math.sin(a).toFloat() * r * 1.35f,
                r * 0.12f, ringPaint,
            )
            ringPaint.style = Paint.Style.STROKE
        }
        // Paused: dimmed veil — visibly "held", not off.
        if (orbState == OrbState.PAUSED) {
            paint.shader = null
            paint.color = 0x9908080F.toInt()
            canvas.drawCircle(cx, cy, r * 1.05f, paint)
        }
        // Error: red ring pulse — distinct from every other state.
        if (orbState == OrbState.ERROR) {
            ringPaint.color = context.getColor(R.color.loom_error)
            ringPaint.alpha = (120 + 100 * pulse).toInt()
            canvas.drawCircle(cx, cy, r * 1.25f, ringPaint)
            ringPaint.alpha = 255
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        ringAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
