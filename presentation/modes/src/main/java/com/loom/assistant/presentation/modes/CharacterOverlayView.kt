package com.loom.assistant.presentation.modes

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.loom.assistant.presentation.designsystem.R

/**
 * Phase 4 — floating character (arch §2.1, lockdown card 14).
 *
 * A small abstract chrome figure — mature, geometric, no face — drawn in
 * Canvas at fingertip scale (~56dp). Draggable anywhere on screen; a tap
 * toggles the floating voice session (exactly like the orb) and a
 * long-press opens the mode panel. A soft idle bob keeps it alive without
 * distracting. Pure Canvas, no assets.
 */
class CharacterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Called on a clean tap (not a drag, not a long-press). */
    var onTap: (() -> Unit)? = null

    /**
     * Called on a long-press only (~500ms hold, no drag). The panel moved
     * here when tap was rewired to floating voice, so the panel stays
     * reachable without any capability removal.
     */
    var onLongPress: (() -> Unit)? = null

    /** Called with drag deltas in pixels; the overlay host moves the window. */
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null

    /**
     * Correction pass: the character consumes the same authoritative voice
     * state as the orb (driven by LoomOverlayService.busListener). This is
     * state WIRING on the existing figure — not a visual redesign (the
     * production asset from lockdown card 14 is a separate, authorized pass).
     */
    enum class VoiceVisualState { IDLE, LISTENING, THINKING, SPEAKING, PAUSED, ERROR }

    var voiceState: VoiceVisualState = VoiceVisualState.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val silver = context.getColor(R.color.loom_silver)
    private val silverDark = context.getColor(R.color.loom_silver_dark)
    private val accent = context.getColor(R.color.loom_accent)
    private val errorRed = context.getColor(R.color.loom_error)

    private var bob = 0f
    private var bobAnimator: ValueAnimator? = null
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private val touchSlop = 12f * resources.displayMetrics.density

    // Long-press detection: armed on ACTION_DOWN, cancelled on drag-start
    // and on ACTION_UP / ACTION_CANCEL. A fired long-press sets
    // longPressed, which suppresses the tap on ACTION_UP — a long-press
    // must never also fire onTap.
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress?.invoke()
    }

    private fun cancelPendingLongPress() {
        longPressHandler.removeCallbacks(longPressRunnable)
    }

    init {
        bobAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { bob = it.animatedValue as Float; invalidate() }
            start()
        }
        // State pulse for LISTENING/THINKING/SPEAKING/ERROR visuals.
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                longPressed = false
                cancelPendingLongPress()
                longPressHandler.postDelayed(longPressRunnable, 500L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop) {
                    dragging = true
                    // A drag is not a hold: cancel before it can fire.
                    cancelPendingLongPress()
                }
                if (dragging) {
                    onDrag?.invoke(dx, dy)
                    downX = event.rawX
                    downY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingLongPress()
                if (!dragging && !longPressed) onTap?.invoke()
                dragging = false
                longPressed = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                dragging = false
                longPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val bobPx = bob * 4f * resources.displayMetrics.density

        val chrome = LinearGradient(
            cx - w * 0.2f, 0f, cx + w * 0.2f, h,
            intArrayOf(silver, silverDark, silver),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.shader = chrome

        val headR = w * 0.20f
        val headCy = h * 0.18f + bobPx
        // Head.
        canvas.drawCircle(cx, headCy, headR, paint)
        // Torso: tapered rounded rect.
        val torso = RectF(cx - w * 0.16f, headCy + headR * 0.9f, cx + w * 0.16f, h * 0.62f + bobPx)
        canvas.drawRoundRect(torso, w * 0.10f, w * 0.10f, paint)
        // Arms.
        paint.strokeWidth = w * 0.09f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            cx - w * 0.14f, h * 0.36f + bobPx, cx - w * 0.30f, h * 0.52f + bobPx, paint,
        )
        canvas.drawLine(
            cx + w * 0.14f, h * 0.36f + bobPx, cx + w * 0.30f, h * 0.52f + bobPx, paint,
        )
        // Legs.
        canvas.drawLine(
            cx - w * 0.07f, h * 0.62f + bobPx, cx - w * 0.10f, h * 0.86f + bobPx, paint,
        )
        canvas.drawLine(
            cx + w * 0.07f, h * 0.62f + bobPx, cx + w * 0.10f, h * 0.86f + bobPx, paint,
        )
        paint.shader = null

        // Subtle accent visor line — the only color on the figure.
        paint.color = accent
        paint.strokeWidth = w * 0.035f
        canvas.drawLine(
            cx - headR * 0.55f, headCy - headR * 0.1f,
            cx + headR * 0.55f, headCy - headR * 0.1f, paint,
        )

        // Correction pass: per-state visuals on the existing figure.
        drawStateOverlay(canvas, w, h, cx, headCy, headR)
    }

    /**
     * State differentiation WITHOUT redesigning the figure:
     * - LISTENING: expanding accent ring (recognizable "I'm hearing you").
     * - THINKING: faster shimmer ring.
     * - SPEAKING: visor glow pulse, synced to the SPEAKING state (framework
     *   TTS exposes no amplitude — state-synced is the honest limit).
     * - PAUSED: dimmed veil.
     * - ERROR: red ring pulse.
     * - IDLE: nothing (the bob alone).
     */
    private fun drawStateOverlay(
        canvas: Canvas, w: Float, h: Float, cx: Float, headCy: Float, headR: Float,
    ) {
        when (voiceState) {
            VoiceVisualState.IDLE -> return
            VoiceVisualState.PAUSED -> {
                paint.shader = null
                paint.color = 0x99000000.toInt()
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.style = Paint.Style.FILL
                return
            }
            VoiceVisualState.LISTENING, VoiceVisualState.THINKING -> {
                val speed = if (voiceState == VoiceVisualState.THINKING) 1.6f else 1f
                val alpha = ((0.35f + 0.45f * pulse * speed).coerceAtMost(0.85f) * 255).toInt()
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.03f
                paint.color = (alpha shl 24) or (accent and 0x00FFFFFF)
                val r = w * (0.42f + 0.08f * pulse)
                canvas.drawCircle(cx, h * 0.45f, r, paint)
                paint.style = Paint.Style.FILL
            }
            VoiceVisualState.SPEAKING -> {
                // Visor glow: layered strokes, alpha pulsing with the state.
                val glowAlpha = (0.45f + 0.55f * pulse).coerceAtMost(1f)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val visorY = headCy - headR * 0.1f
                val x0 = cx - headR * 0.55f
                val x1 = cx + headR * 0.55f
                paint.strokeWidth = w * 0.10f
                paint.color = ((0.30f * glowAlpha * 255).toInt() shl 24) or (accent and 0x00FFFFFF)
                canvas.drawLine(x0, visorY, x1, visorY, paint)
                paint.strokeWidth = w * 0.045f
                paint.color = ((glowAlpha * 255).toInt() shl 24) or (accent and 0x00FFFFFF)
                canvas.drawLine(x0, visorY, x1, visorY, paint)
                paint.style = Paint.Style.FILL
            }
            VoiceVisualState.ERROR -> {
                val alpha = ((0.40f + 0.50f * pulse) * 255).toInt()
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.035f
                paint.color = (alpha shl 24) or (errorRed and 0x00FFFFFF)
                canvas.drawCircle(cx, h * 0.45f, w * 0.46f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    override fun onDetachedFromWindow() {
        cancelPendingLongPress()
        bobAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
