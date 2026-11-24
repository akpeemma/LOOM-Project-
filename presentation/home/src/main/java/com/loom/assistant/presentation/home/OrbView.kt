package com.loom.assistant.presentation.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.loom.assistant.presentation.designsystem.R

/**
 * The LOOM voice orb: glowing blue-violet core with a soft aura.
 * States: idle (slow breathing pulse), listening (expanding rings),
 * speaking (faster pulse). Pure Canvas — no assets, no deps.
 */
class OrbView @JvmOverloads constructor(
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

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.loom_space_4)
    }

    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var ringPhase = 0f
    private var ringAnimator: ValueAnimator? = null

    private val accent = context.getColor(R.color.loom_accent)
    private val accentBright = context.getColor(R.color.loom_accent_bright)
    private val accentGlow = context.getColor(R.color.loom_accent_glow)

    init {
        restartPulse()
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
        val breathe = 1f + 0.06f * pulse
        val r = base * breathe

        // Aura.
        val aura = RadialGradient(
            cx, cy, r * 2.2f,
            intArrayOf(accentGlow, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        corePaint.shader = aura
        canvas.drawCircle(cx, cy, r * 2.2f, corePaint)

        // Core.
        val core = RadialGradient(
            cx - r * 0.25f, cy - r * 0.25f, r * 1.15f,
            intArrayOf(accentBright, accent, 0xFF2A2358.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        corePaint.shader = core
        canvas.drawCircle(cx, cy, r, corePaint)

        // Listening rings.
        if (orbState == OrbState.LISTENING) {
            ringPaint.color = accentBright
            for (k in 0..1) {
                val ph = (ringPhase + k * 0.5f) % 1f
                ringPaint.alpha = ((1f - ph) * 160).toInt()
                canvas.drawCircle(cx, cy, r * (1.15f + ph * 0.9f), ringPaint)
            }
        }
        // Thinking: small orbiting dot.
        if (orbState == OrbState.THINKING) {
            val a = pulse * Math.PI * 2
            ringPaint.color = accentBright
            ringPaint.style = Paint.Style.FILL
            canvas.drawCircle(
                cx + Math.cos(a).toFloat() * r * 1.35f,
                cy + Math.sin(a).toFloat() * r * 1.35f,
                r * 0.12f,
                ringPaint,
            )
            ringPaint.style = Paint.Style.STROKE
        }
        // Paused: dimmed veil over the orb — visibly "held", not off.
        if (orbState == OrbState.PAUSED) {
            corePaint.shader = null
            corePaint.color = 0x9908080F.toInt()
            canvas.drawCircle(cx, cy, r * 1.05f, corePaint)
        }
        // Error: red ring pulse — distinct from every other state.
        if (orbState == OrbState.ERROR) {
            ringPaint.color = context.getColor(R.color.loom_error)
            ringPaint.alpha = (120 + 100 * pulse).toInt()
            canvas.drawCircle(cx, cy, r * 1.25f, ringPaint)
            ringPaint.alpha = 255
        }
        corePaint.shader = null
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        ringAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
