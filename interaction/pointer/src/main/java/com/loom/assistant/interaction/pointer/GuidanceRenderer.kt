package com.loom.assistant.interaction.pointer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.core.common.PointerNudge

/**
 * Stage 2C (A2) — guidance renderer, extracted from PointerGuideView.
 *
 * Implements [GuidanceRendererContract] (defined by the execution:
 * interaction module — this class implements its EXACT members:
 * showStep/hide/previewMoveTo/pulseOnResult/onAnchorChanged).
 *
 * Draws, on top of any app:
 *  - a pointer beam from an anchor point (the character/orb position, or the
 *    screen edge in invisible mode) to the target element's corner,
 *  - a glowing highlight ring around the target element (double-pass),
 *  - a step tooltip ("Step N of M" + instruction), flipped above/below the
 *    target and clamped inside the window by [ObstructionLayout].
 *  - FEW agents (David's correction 2026-11-05): 3 darts riding the beam
 *    toward the target — the lead dart is the tapper (it glides ahead of
 *    the AuthGate tap and is drawn at the cursor when the tap lands),
 *    the followers gather behind it; the same 3 darts converge on the
 *    idle dot when no step is live. Bright flat saturated colours with
 *    white stroke outlines (swarm-cursors visibility formula).
 *  - SPEC DELTAS (2026-11-05): a visible tap ripple / press state at the
 *    tap point when the lead dart taps (held press dot, then an expanding
 *    ring — [showTapRipple]/[showPressState]); the preview glide follows
 *    a Bezier curve with overshoot-and-correct on long moves
 *    ([PointerMotion.glidePoint]); the status pill narrates each phase
 *    ([setStatusNarration]) instead of only "Guiding you — Step N of M".
 *
 * Choreography (the 2B contract):
 *  - [previewMoveTo]: intent preview — the beam animates to the upcoming
 *    target over ~300ms BEFORE a dispatch.
 *  - [pulseOnResult]: on the async completion callback the ring pulses.
 *  - [onAnchorChanged]: anchor changes are EVENTS animated over ~300ms
 *    ease — the beam never teleports between modes.
 *
 * Draws nothing when idle (no step, no preview, no idle indicator):
 * [onDraw] returns early. The rest of the screen is left fully visible
 * — this view draws only the beam, ring and tooltip (never dims the
 * user's app). Pure Canvas, no assets.
 */
open class GuidanceRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), GuidanceRendererContract {

    private data class StepState(
        val anchor: GeomPoint,
        val target: GeomRect,
        val stepIndex: Int, // 1-based
        val stepCount: Int,
        val instruction: String,
    )

    private data class AnchorAnim(val from: GeomPoint, val to: GeomPoint, val startMs: Long)
    private data class Preview(val from: GeomRect, val to: GeomRect, val startMs: Long)

    private var step: StepState? = null
    private var anchorAnim: AnchorAnim? = null
    private var idleAnchor: GeomPoint? = null
    private var preview: Preview? = null
    private var pulseStartMs: Long = -1L

    /**
     * POINTER FEW-CURSOR SPEC DELTAS (2026-11-05): visible tap ripple /
     * press state. When the lead dart performs a tap, [showTapRipple]
     * starts this at the tap point: a press dot held briefly, then an
     * expanding fading ring — so David can SEE the tap land. A
     * long-press ([showPressState]) holds the press dot + ring for the
     * hold duration first, then releases into the same ripple. Distinct
     * from [pulseStartMs] (the result pulse on async completion, a ring
     * around the whole target). Cleared on hide / new step.
     */
    private var pressStartMs: Long = -1L
    private var pressHoldMs: Long = 0L
    private var pressPoint: GeomPoint? = null

    /**
     * POINTER FEW-CURSOR SPEC DELTAS (2026-11-05): status-pill narration
     * for the current phase ("Moving to Search…", "Tapping Search").
     * Blank clears back to the default "Guiding you — Step N of M".
     */
    private var statusNarration: String? = null

    /**
     * POINTER MOUSE-VERBS (2026-11-05): confirmed text selection to
     * highlight — the element bounds + label, set ONLY by
     * [showSelection] after the system confirms a selection. Null =
     * no selection shown. Cleared on hide / new step. Never set
     * speculatively: showing a selection that does not exist would be
     * a fake claim on screen.
     */
    private var selection: Pair<GeomRect, String>? = null

    /**
     * Device-evidence fix (2026-11-05, pointer H3 + swarm-cursors
     * lesson): a persistent idle indicator — a pulsing ring at the
     * anchor proving the pointer is alive and visible when no step is
     * live. Cleared by showStep/hide: a live step owns the screen.
     * The swarm lesson is visibility first: the pointer must be
     * UNMISTAKABLE (large, high-contrast, moving) — a thin line that
     * only appears mid-step reads as "nothing renders".
     */
    private var idleIndicator = false

    /**
     * MASTER Wave 4 (F11) — the last draw failure. The contract's
     * default `lastDrawFailure()` is null, and this class never
     * overrode it — so every draw into a detached (or zero-size)
     * view was a silent `invalidate()` no-op while the executor
     * logged "pointer-ok". Every draw method below now gates on
     * [checkDrawAllowed] first: a dropped draw records its failure
     * here, and a landed draw clears it. Read by
     * MainThreadGuidanceRenderer (which delegates here) and the
     * pointer guides after every draw call.
     */
    private var lastDrawFailure: String? = null

    override fun lastDrawFailure(): String? = lastDrawFailure

    /**
     * Returns true when a draw may land (attached to a window with
     * a real size). False records the failure — the caller returns
     * early WITHOUT mutating draw state, so a detached draw cannot
     * leave a phantom cursor/target behind for later reads.
     */
    private fun checkDrawAllowed(method: String): Boolean {
        val failure = DetachedDrawGuard.check(isAttachedToWindow, width, height, method)
        if (failure != null) {
            lastDrawFailure = failure
            Log.w("LoomPointer", failure)
            return false
        }
        lastDrawFailure = null
        return true
    }    /**
     * "fix everything": manual aim offset from POINTER_MOVE. Applied to
     * the drawn target (beam, ring, tooltip all follow it as one unit)
     * in onDraw, AFTER the preview resolution and BEFORE ObstructionLayout
     * — so a moved indicator still never covers content. Reset on every
     * new step and on hide: a new step re-aims at its real target, and a
     * stale offset must never corrupt a later step. Never affects what a
     * tap targets (tap paths resolve the real target independently).
     */
    private var manualOffset = GeomPoint(0f, 0f)

    /**
     * Borrow-note (2026-11-05, remotedroid): ONE owned cursor position —
     * the single source of truth for where the pointer is, clamped to the
     * window. Every draw and every feedback report reads this, never a
     * per-call derivation — so a stale renderer cannot report a position
     * its window never drew. Null when nothing is shown. Set by showStep
     * (target centre), previewMoveTo (aimed target), nudgePointer
     * (moved point) and showIdleIndicator (anchor); cleared by hide().
     */
    private var cursor: GeomPoint? = null

    /** Clamp a point inside the window (16dp margin); pass through when unlaid-out. */
    private fun clampToWindow(p: GeomPoint): GeomPoint {
        if (width <= 0 || height <= 0) return p
        val m = 16f * resources.displayMetrics.density
        if (width <= 2 * m || height <= 2 * m) return p
        return GeomPoint(p.x.coerceIn(m, width - m), p.y.coerceIn(m, height - m))
    }

    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dartPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * David's correction (2026-11-05): FEW agents, not a swarm — 3 darts
     * is the starting count, adjustable after he sees them on his phone.
     * The lead dart (nearest the target) is the tapper: it glides to the
     * verified target ahead of the AuthGate tap and is drawn at the cursor
     * when the tap lands. The followers gather behind it on the beam.
     * Colours from the swarm-cursors 12-colour palette (inspiration
     * only — no code copied): cyan / mint / yellow, each with the white
     * stroke outline + glow treatment.
     */
    private data class DartSpec(val beamFraction: Float, val color: Int, val scale: Float)

    private val darts = listOf(
        DartSpec(0.92f, 0xFF00E5FF.toInt(), 1.15f), // lead — cyan, the tapper
        DartSpec(0.70f, 0xFF00E676.toInt(), 1.0f), // mint
        DartSpec(0.48f, 0xFFFFC700.toInt(), 0.85f), // yellow
    )

    /**
     * Video-reference colours (2026-11-05, swarm-cursors reel): bright
     * FLAT saturated colours, not the faint brand purple — mint/cyan/
     * blue/red on a dark site, unmistakable at a glance. Cyan is the
     * primary pointer colour; vivid blue ends the beam gradient; coral
     * red is the attention colour (ring while an intent preview is live
     * — the pointer is about to act). A dark under-stroke under every
     * bright line keeps the pointer readable on LIGHT apps too, where
     * bare cyan would wash out.
     */
    private val pointerCyan = 0xFF00E5FF.toInt()
    private val pointerBlue = 0xFF2979FF.toInt()
    private val attentionRed = 0xFFFF5252.toInt()
    private val underDark = 0xCC000000.toInt()

    val isShowing: Boolean get() = step != null

    /**
     * Master implementation §6 — optional "Show me what you're doing"
     * feed. The host (LoomOverlayService / LoomAccessibilityService) sets
     * this when it creates the renderer; the renderer only REPORTS into
     * it (target, instruction, pointer position) and never reads it back.
     * Null until wired — reporting is a no-op then.
     */
    var feedback: ExecutionFeedback? = null

    /** The target currently drawn (with any manual aim offset), or null. */
    val currentTarget: GeomRect?
        get() = step?.let { s ->
            GeomRect(
                s.target.left + manualOffset.x, s.target.top + manualOffset.y,
                s.target.right + manualOffset.x, s.target.bottom + manualOffset.y,
            )
        }

    /**
     * Where the pointer is aimed right now: the ONE owned cursor position
     * (remotedroid borrow-note), or null when idle. Every consumer —
     * feedback reporting, "Show me what you're doing" — reads this.
     */
    val currentPointerPosition: GeomPoint?
        get() = cursor

    /** The instruction of the step currently drawn, or null when idle. */
    val currentInstruction: String?
        get() = step?.instruction

    /** "Step N of M" for the step currently drawn, or null when idle. */
    val currentStepLabel: String?
        get() = step?.let { "Step ${it.stepIndex} of ${it.stepCount}" }

    // ---- GuidanceRendererContract ----

    override fun showStep(
        anchorX: Float,
        anchorY: Float,
        target: RectF,
        stepIndex: Int,
        stepCount: Int,
        instruction: String,
    ) {
        // MASTER Wave 4 (F11): a detached draw must be a RECORDED
        // failure, never a silent invalidate() no-op.
        if (!checkDrawAllowed("showStep")) return
        // Video-reference motion: glide from the previous target instead
        // of teleporting — the ring visibly TRAVELS to the new target over
        // the existing 300ms preview animation.
        val previousTarget = step?.target
        val newTarget = target.toGeom()
        step = StepState(
            anchor = GeomPoint(anchorX, anchorY),
            target = newTarget,
            stepIndex = stepIndex,
            stepCount = stepCount,
            instruction = instruction,
        )
        anchorAnim = null
        preview = if (previousTarget != null && previousTarget != newTarget) {
            Preview(previousTarget, newTarget, SystemClock.uptimeMillis())
        } else {
            null
        }
        pulseStartMs = -1L
        pressStartMs = -1L
        pressPoint = null
        statusNarration = null
        selection = null
        manualOffset = GeomPoint(0f, 0f)
        idleIndicator = false
        // One owned cursor position: the new target's centre, clamped.
        cursor = clampToWindow(GeomPoint(newTarget.centerX, newTarget.centerY))
        visibility = VISIBLE
        invalidate()
        // Device-evidence fix (2026-11-05, item 1): attach + geometry at
        // every show — a detached or zero-size window draws nothing, and
        // the old code never said so anywhere.
        Log.d(
            "LoomPointer",
            "showStep step=$stepIndex/$stepCount attached=$isAttachedToWindow " +
                "size=${width}x$height target=$target",
        )
        // §6: report what the pointer is now identifying.
        feedback?.setTarget(instruction, target.toGeom(), "target")
        feedback?.setPointerPosition(currentPointerPosition)
    }

    override fun hide() {
        step = null
        anchorAnim = null
        preview = null
        pulseStartMs = -1L
        pressStartMs = -1L
        pressPoint = null
        statusNarration = null
        selection = null
        manualOffset = GeomPoint(0f, 0f)
        idleIndicator = false
        cursor = null
        visibility = GONE
        invalidate()
        // §6: nothing is identified any more.
        feedback?.clearTarget()
        feedback?.setPointerPosition(null)
    }

    /**
     * Device-evidence fix (2026-11-05, pointer H3): render the idle
     * indicator — a pulsing ring at the anchor proving the pointer is
     * visible with no step live. True when the indicator is showing (a
     * live step already counts: the pointer is visible). False only when
     * there is no window to draw in.
     */
    override fun showIdleIndicator(): Boolean {
        if (!isAttachedToWindow) {
            // MASTER Wave 4 (F11): record the refusal as a draw
            // failure — the old code returned false with no record.
            lastDrawFailure =
                "the pointer view is not attached to a window — the showIdleIndicator draw was dropped"
            Log.w("LoomPointer", "showIdleIndicator refused: view not attached to window")
            return false
        }
        lastDrawFailure = null
        if (step != null) return true // a live step is already visible
        idleIndicator = true
        visibility = VISIBLE
        invalidate()
        val anchor = idleAnchor ?: GeomPoint(width / 2f, height / 2f)
        // One owned cursor position: the idle anchor, clamped.
        cursor = clampToWindow(anchor)
        Log.d(
            "LoomPointer",
            "showIdleIndicator at (${anchor.x}, ${anchor.y}) size=${width}x$height",
        )
        feedback?.setPointerPosition(currentPointerPosition)
        return true
    }

    /**
     * "fix everything": POINTER_MOVE execution. False when idle (nothing
     * is drawn, so there is no pointer to move) — the caller turns that
     * into "start guidance first", never a claimed move.
     */
    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean {
        val s = step ?: return false
        val density = resources.displayMetrics.density
        val (nx, ny) = PointerNudge.step(
            s.target.centerX + manualOffset.x,
            s.target.centerY + manualOffset.y,
            direction,
            stepDp * density,
            width.toFloat(),
            height.toFloat(),
            24f * density,
        )
        manualOffset = GeomPoint(nx - s.target.centerX, ny - s.target.centerY)
        // One owned cursor position: the moved point, clamped to the window.
        cursor = clampToWindow(GeomPoint(nx, ny))
        invalidate()
        // §6: the aimed point moved with the indicator.
        feedback?.setPointerPosition(currentPointerPosition)
        return true
    }

    /**
     * Intent preview: animate the beam to the upcoming target over ~300ms.
     * The ActionQueue calls this BEFORE dispatch so David sees intention
     * before action. No-op when no step is shown.
     */
    override fun previewMoveTo(target: RectF) {
        val s = step ?: return
        if (!checkDrawAllowed("previewMoveTo")) return
        preview = Preview(s.target, target.toGeom(), SystemClock.uptimeMillis())
        // One owned cursor position: the aimed target's centre, clamped.
        cursor = clampToWindow(GeomPoint(target.centerX(), target.centerY()))
        invalidate()
        // §6: the pointer is now aimed at the upcoming target.
        feedback?.setPointerPosition(currentPointerPosition)
    }

    /**
     * Result pulse: expanding fading ring, driven by the async gesture
     * completion callback. No-op when no step is shown.
     */
    override fun pulseOnResult() {
        if (step == null) return
        if (!checkDrawAllowed("pulseOnResult")) return
        pulseStartMs = SystemClock.uptimeMillis()
        invalidate()
    }

    /**
     * POINTER FEW-CURSOR SPEC DELTAS (2026-11-05): visible tap ripple.
     * Starts at the owned cursor position (the tap point — the lead
     * dart is drawn there): a press dot held briefly, then an expanding
     * fading ring. No-op when no step is shown (nothing to tap on).
     * Called by the tap path when the system ACCEPTS the gesture — the
     * visible record that the tap landed, distinct from [pulseOnResult]
     * (async completion).
     */
    override fun showTapRipple() {
        if (step == null) return
        if (!checkDrawAllowed("showTapRipple")) return
        pressPoint = cursor ?: step?.let {
            GeomPoint(it.target.centerX, it.target.centerY)
        } ?: return
        pressStartMs = SystemClock.uptimeMillis()
        pressHoldMs = TAP_PRESS_HOLD_MS
        Log.d("LoomPointer", "showTapRipple at $pressPoint")
        invalidate()
    }

    /**
     * POINTER FEW-CURSOR SPEC DELTAS (2026-11-05): visible press state
     * held for a long-press. The press dot + ring stay held at the tap
     * point for [holdMs], then release into the tap ripple — David sees
     * the finger go down, stay down, and lift. No-op when no step is
     * shown. Called by the long-press path when the system accepts the
     * hold gesture.
     */
    override fun showPressState(holdMs: Long) {
        if (step == null) return
        if (!checkDrawAllowed("showPressState")) return
        pressPoint = cursor ?: step?.let {
            GeomPoint(it.target.centerX, it.target.centerY)
        } ?: return
        pressStartMs = SystemClock.uptimeMillis()
        pressHoldMs = holdMs.coerceIn(0L, MAX_PRESS_HOLD_MS)
        Log.d("LoomPointer", "showPressState hold=${pressHoldMs}ms at $pressPoint")
        invalidate()
    }

    /**
     * POINTER FEW-CURSOR SPEC DELTAS (2026-11-05): status-pill narration
     * for the current phase ("Moving to Search…", "Tapping Search").
     * Blank clears back to the default "Guiding you — Step N of M".
     */
    override fun setStatusNarration(text: String) {
        // MASTER Wave 4 (F11): narration is a draw too — a detached
        // pill update is recorded, not silently dropped.
        if (!checkDrawAllowed("setStatusNarration")) return
        statusNarration = text.ifBlank { null }
        invalidate()
    }

    /** The ONE owned cursor position (remotedroid borrow-note). */
    override fun cursorPosition(): GeomPoint? = cursor

    /**
     * POINTER CHOREOGRAPHY FOR SEARCH/TYPE (2026-11-05): park the
     * pointer on a target outside a guidance step (see the contract).
     * Implemented as a step with stepCount 0 — the sentinel for
     * "parked, not a step": the ring + gathered darts + pill draw, the
     * tooltip and progress bar do not. The anchor is the target centre,
     * so the beam is short and the 3 darts gather ON the target — the
     * parked look. Any later showStep replaces it with a real step.
     */
    override fun parkPointer(target: RectF, label: String) {
        // MASTER Wave 4 (F11): a detached park must be a RECORDED
        // failure, never a silent invalidate() no-op — and it must
        // not leave a phantom parked target behind.
        if (!checkDrawAllowed("parkPointer")) return
        val geom = target.toGeom()
        // Glide from the previous target instead of teleporting — the
        // ring visibly TRAVELS to the new target over the 400ms preview
        // animation (same pattern as showStep).
        val previousTarget = step?.target
        step = StepState(
            anchor = GeomPoint(geom.centerX, geom.centerY),
            target = geom,
            stepIndex = 1,
            stepCount = 0,
            instruction = label,
        )
        anchorAnim = null
        preview = if (previousTarget != null && previousTarget != geom) {
            Preview(previousTarget, geom, SystemClock.uptimeMillis())
        } else {
            null
        }
        pulseStartMs = -1L
        pressStartMs = -1L
        pressPoint = null
        statusNarration = null
        selection = null
        manualOffset = GeomPoint(0f, 0f)
        idleIndicator = false
        cursor = clampToWindow(GeomPoint(geom.centerX, geom.centerY))
        visibility = VISIBLE
        invalidate()
        Log.d("LoomPointer", "parkPointer \"$label\" at $target")
        feedback?.setTarget(label, geom, "target")
        feedback?.setPointerPosition(currentPointerPosition)
    }

    /**
     * Clear a parked pointer (stepCount 0 sentinel). A real guidance
     * step is untouched.
     */
    override fun clearParked() {
        if (step?.stepCount == 0) hide()
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): confirmed-selection highlight.
     * Shows the translucent selection fill + handles over [target] and
     * narrates "Selected <label>". Call ONLY after the system confirms
     * a selection — this is display of a fact, never a claim. No-op
     * when no step is shown.
     */
    override fun showSelection(target: RectF, label: String) {
        if (step == null) return
        selection = target.toGeom() to label
        statusNarration = "Selected $label"
        Log.d("LoomPointer", "showSelection \"$label\" at $target")
        invalidate()
    }

    /**
     * Anchor-change EVENT (F1): when a step is shown the anchor animates
     * over ~300ms ease — never teleports. When idle there is nothing on
     * screen, so the anchor is placed directly.
     */
    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
        val to = GeomPoint(newAnchorX, newAnchorY)
        val now = SystemClock.uptimeMillis()
        if (step == null) {
            idleAnchor = to
        } else {
            val from = currentAnchor(now)
            if (from != to) anchorAnim = AnchorAnim(from, to, now)
        }
        invalidate()
    }

    // ---- drawing ----

    private fun currentAnchor(now: Long): GeomPoint {
        val anim = anchorAnim
        val s = step ?: return idleAnchor ?: GeomPoint(0f, 0f)
        if (anim == null) return s.anchor
        val elapsed = now - anim.startMs
        return if (AnchorTransition.isFinished(elapsed)) {
            anchorAnim = null
            step = s.copy(anchor = anim.to)
            anim.to
        } else {
            AnchorTransition.position(anim.from, anim.to, elapsed)
        }
    }

    private fun previewAnimating(now: Long): Boolean =
        preview?.let { pv ->
            !AnchorTransition.isFinished(now - pv.startMs, previewDurationMs(pv))
        } == true

    /**
     * SPEED (David, 2026-11-05 18:58): the preview glide duration for
     * this move — 250ms standard, up to 300ms for long moves
     * ([PointerMotion.glideDurationMs]). It must never exceed 300ms:
     * the queue dispatches then, so the pointer has to have arrived.
     */
    private fun previewDurationMs(pv: Preview): Long =
        PointerMotion.glideDurationMs(
            PointerMotion.distance(
                GeomPoint(pv.from.centerX, pv.from.centerY),
                GeomPoint(pv.to.centerX, pv.to.centerY),
            ),
        )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = step
        if (s == null) {
            // Device-evidence fix (2026-11-05, H3): idle but alive — draw
            // the persistent indicator instead of nothing. Without this,
            // "show the pointer" with no live step renders a blank screen.
            if (idleIndicator) drawIdleIndicator(canvas)
            return
        }
        val now = SystemClock.uptimeMillis()
        val density = resources.displayMetrics.density
        val window = GeomRect(0f, 0f, width.toFloat(), height.toFloat())
        val anchor = currentAnchor(now)

        // Intent-preview target: the beam glides from the current target
        // to the preview target over the distance-based preview duration
        // (250ms standard, 300ms max — SPEED, David 2026-11-05 18:58),
        // then holds until showStep/hide. POINTER FEW-CURSOR SPEC DELTAS
        // (2026-11-05): the glide follows a Bezier curve
        // ([PointerMotion.glidePoint]) with overshoot-and-correct on long
        // moves — the pointer visibly TRAVELS and ARRIVES, never
        // teleports. Motion math only; the curve is computed from the
        // standard formula, no code lifted.
        val pv = preview
        val targetGeom: GeomRect
        val previewDim: Boolean
        if (pv != null) {
            val elapsed = now - pv.startMs
            val previewMs = previewDurationMs(pv)
            targetGeom = if (AnchorTransition.isFinished(elapsed, previewMs)) {
                pv.to
            } else {
                val c = PointerMotion.glidePoint(
                    GeomPoint(pv.from.centerX, pv.from.centerY),
                    GeomPoint(pv.to.centerX, pv.to.centerY),
                    elapsed / previewMs.toFloat(),
                )
                val dx = c.x - pv.from.centerX
                val dy = c.y - pv.from.centerY
                GeomRect(
                    pv.from.left + dx, pv.from.top + dy,
                    pv.from.right + dx, pv.from.bottom + dy,
                )
            }
            previewDim = true
        } else {
            targetGeom = s.target
            previewDim = false
        }

        // "fix everything": manual aim offset from POINTER_MOVE, applied
        // to the drawn target as one unit before obstruction layout —
        // so a moved indicator still never covers content.
        val movedTarget = GeomRect(
            targetGeom.left + manualOffset.x, targetGeom.top + manualOffset.y,
            targetGeom.right + manualOffset.x, targetGeom.bottom + manualOffset.y,
        )

        // Measure the tooltip text, then resolve the obstruction-aware layout.
        val label = "Step ${s.stepIndex} of ${s.stepCount}"
        textPaint.textSize = 13f * density
        val labelW = textPaint.measureText(label)
        textPaint.textSize = 15f * density
        val instrW = textPaint.measureText(s.instruction)
        val place = ObstructionLayout.layout(
            target = movedTarget,
            anchor = anchor,
            window = window,
            density = density,
            labelWidthPx = labelW,
            instructionWidthPx = instrW,
        )

        // Beam: gradient line from anchor to the target-corner dot.
        // Swarm lesson: thick and bright — a 3dp line on a bright app
        // is invisible; 5dp with a glow pass reads at a glance.
        beamPaint.strokeWidth = 5f * density
        beamPaint.shader = LinearGradient(
            place.beamFrom.x, place.beamFrom.y,
            place.beamTo.x, place.beamTo.y,
            intArrayOf(pointerCyan, pointerBlue),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawLine(
            place.beamFrom.x, place.beamFrom.y,
            place.beamTo.x, place.beamTo.y,
            beamPaint,
        )
        beamPaint.shader = null
        // David's correction (2026-11-05): FEW agents — 3 darts riding the
        // beam toward the target. Stretched with dotted tails in their own
        // colour while the beam is gliding (moving fast); settled when it
        // arrives and the darts gather on the target.
        drawDartFormation(canvas, place.beamFrom, place.beamTo, moving = pv != null)
        // Anchor dot + target-corner dot (the corner offset keeps the beam
        // from parking over the target's own label).
        beamPaint.style = Paint.Style.FILL
        beamPaint.color = pointerCyan
        canvas.drawCircle(place.beamFrom.x, place.beamFrom.y, 8f * density, beamPaint)
        canvas.drawCircle(place.beamTo.x, place.beamTo.y, 6f * density, beamPaint)
        beamPaint.style = Paint.Style.STROKE

        // Highlight ring around the target: thin rectangular outline, the
        // video's inspector-selection language. Red while an intent
        // preview is live (about to act = attention); cyan otherwise.
        // Swarm visibility formula: saturated colour + WHITE stroke
        // outline + glow — the white pass is what keeps the ring readable
        // on any background; the dark under-stroke covers light apps.
        val ring = place.ring.toRectF()
        val ringColor = if (pv != null) attentionRed else pointerCyan
        ringPaint.strokeWidth = 8f * density
        ringPaint.color = underDark
        ringPaint.alpha = 170
        canvas.drawRoundRect(ring, 14f * density, 14f * density, ringPaint)
        ringPaint.strokeWidth = 6f * density
        ringPaint.color = 0xFFFFFFFF.toInt()
        ringPaint.alpha = if (previewDim) 200 else 255
        canvas.drawRoundRect(ring, 14f * density, 14f * density, ringPaint)
        ringPaint.strokeWidth = 4f * density
        ringPaint.color = ringColor
        ringPaint.alpha = if (previewDim) 180 else 255
        canvas.drawRoundRect(ring, 14f * density, 14f * density, ringPaint)
        ringPaint.alpha = if (previewDim) 70 else 110
        ringPaint.strokeWidth = 11f * density
        canvas.drawRoundRect(ring, 14f * density, 14f * density, ringPaint)
        ringPaint.alpha = 255
        // Corner handles, like an element selected in a design inspector:
        // small squares pinned to the ring's four corners, so the target
        // reads as selected even where the ring crosses busy content.
        // White outer square, coloured core — the swarm outline formula.
        val handleHalf = 5.5f * density
        val outlineHalf = 7.5f * density
        for ((cx, cy) in listOf(
            ring.left to ring.top,
            ring.right to ring.top,
            ring.left to ring.bottom,
            ring.right to ring.bottom,
        )) {
            ringPaint.style = Paint.Style.FILL
            ringPaint.color = 0xFFFFFFFF.toInt()
            ringPaint.alpha = 255
            canvas.drawRect(
                cx - outlineHalf, cy - outlineHalf,
                cx + outlineHalf, cy + outlineHalf,
                ringPaint,
            )
            ringPaint.color = ringColor
            canvas.drawRect(
                cx - handleHalf, cy - handleHalf,
                cx + handleHalf, cy + handleHalf,
                ringPaint,
            )
        }
        ringPaint.style = Paint.Style.STROKE
        // Confirmed text selection (mouse-verbs): a translucent cyan
        // fill over the selected element + selection handles at its
        // left/right edges — the desktop text-selection language. Drawn
        // only when a selection was confirmed (see [showSelection]);
        // never shown speculatively.
        selection?.let { (selRect, _) ->
            val sel = RectF(
                selRect.left, selRect.top, selRect.right, selRect.bottom,
            )
            dartPaint.style = Paint.Style.FILL
            dartPaint.color = pointerCyan
            dartPaint.alpha = 70
            canvas.drawRoundRect(sel, 8f * density, 8f * density, dartPaint)
            dartPaint.alpha = 255
            // Handles: small vertical bars at the selection's mid-left
            // and mid-right edges, white outline + cyan core.
            val midY = sel.centerY()
            val handleH = 18f * density
            val handleW = 4f * density
            for (hx in listOf(sel.left, sel.right)) {
                dartPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawRoundRect(
                    RectF(
                        hx - handleW, midY - handleH,
                        hx + handleW, midY + handleH,
                    ),
                    handleW, handleW, dartPaint,
                )
                dartPaint.color = pointerCyan
                canvas.drawRoundRect(
                    RectF(
                        hx - handleW / 2f, midY - handleH + 2f * density,
                        hx + handleW / 2f, midY + handleH - 2f * density,
                    ),
                    handleW / 2f, handleW / 2f, dartPaint,
                )
            }
        }
        // Shape-switching (swarm-cursors): while an intent preview is
        // live the pointer is AIMING — a crosshair at the (animated)
        // target centre, the shape for "about to act", distinct from the
        // guiding ring. White under-stroke, red core.
        if (pv != null) {
            val cx = targetGeom.centerX
            val cy = targetGeom.centerY
            val arm = 18f * density
            beamPaint.style = Paint.Style.STROKE
            beamPaint.strokeWidth = 5f * density
            beamPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawLine(cx - arm, cy, cx + arm, cy, beamPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, beamPaint)
            beamPaint.strokeWidth = 3f * density
            beamPaint.color = attentionRed
            canvas.drawLine(cx - arm, cy, cx + arm, cy, beamPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, beamPaint)
        }

        // Result pulse: expanding, fading ring on async completion.
        if (pulseStartMs >= 0) {
            val elapsed = now - pulseStartMs
            if (elapsed >= PULSE_MS) {
                pulseStartMs = -1L
            } else {
                val t = elapsed / PULSE_MS.toFloat()
                val grown = place.ring.inset(-t * 28f * density).toRectF()
                ringPaint.strokeWidth = 3f * density
                ringPaint.color = pointerCyan
                ringPaint.alpha = ((1f - t) * 255).toInt()
                canvas.drawRoundRect(grown, 14f * density, 14f * density, ringPaint)
                ringPaint.alpha = 255
                // The tap landed: the lead dart is drawn at the cursor,
                // enlarged — the visible record that the lead agent did
                // the tapping, through the AuthGate path (preview-before-
                // gate, dispatch at the verified target). No new tap path:
                // the visual ties the existing one to the lead dart.
                val tapPoint = cursor ?: GeomPoint(targetGeom.centerX, targetGeom.centerY)
                drawDart(
                    canvas, tapPoint.x, tapPoint.y,
                    beamAngleDeg(place.beamFrom, place.beamTo),
                    darts[0].color, 1.5f, stretched = false,
                )
            }
        }

        // Tap ripple / press state (few-cursor spec deltas): the visible
        // record that the lead dart's tap LANDED. Two phases at the tap
        // point: HELD — a solid press dot + static ring while the finger
        // is down (a tap holds it briefly; a long-press holds it for the
        // hold duration); RELEASE — an expanding fading ring + the lead
        // dart enlarged at the tap point. Distinct from the result pulse
        // above (a ring around the whole target on async completion).
        if (pressStartMs >= 0) {
            val pressElapsed = now - pressStartMs
            val totalMs = pressHoldMs + RIPPLE_MS
            if (pressElapsed >= totalMs) {
                pressStartMs = -1L
                pressPoint = null
            } else {
                val point = pressPoint
                    ?: GeomPoint(targetGeom.centerX, targetGeom.centerY)
                if (pressElapsed < pressHoldMs) {
                    // HELD: finger down — solid press dot, white outline,
                    // static ring. No expansion yet: the hold must read.
                    ringPaint.style = Paint.Style.STROKE
                    ringPaint.strokeWidth = 4f * density
                    ringPaint.color = 0xFFFFFFFF.toInt()
                    ringPaint.alpha = 255
                    canvas.drawCircle(point.x, point.y, 24f * density, ringPaint)
                    ringPaint.color = pointerCyan
                    ringPaint.strokeWidth = 3f * density
                    canvas.drawCircle(point.x, point.y, 24f * density, ringPaint)
                    beamPaint.style = Paint.Style.FILL
                    beamPaint.color = pointerCyan
                    canvas.drawCircle(point.x, point.y, 10f * density, beamPaint)
                    beamPaint.style = Paint.Style.STROKE
                } else {
                    // RELEASE: expanding fading ring + enlarged lead dart.
                    val t = (pressElapsed - pressHoldMs) / RIPPLE_MS.toFloat()
                    val radius = (18f + t * 30f) * density
                    ringPaint.style = Paint.Style.STROKE
                    ringPaint.strokeWidth = 4f * density
                    ringPaint.color = 0xFFFFFFFF.toInt()
                    ringPaint.alpha = ((1f - t) * 200).toInt()
                    canvas.drawCircle(point.x, point.y, radius, ringPaint)
                    ringPaint.color = pointerCyan
                    ringPaint.strokeWidth = 3f * density
                    ringPaint.alpha = ((1f - t) * 255).toInt()
                    canvas.drawCircle(point.x, point.y, radius, ringPaint)
                    ringPaint.alpha = 255
                    beamPaint.style = Paint.Style.FILL
                    beamPaint.color = pointerCyan
                    beamPaint.alpha = ((1f - t) * 255).toInt()
                    canvas.drawCircle(point.x, point.y, 8f * density, beamPaint)
                    beamPaint.alpha = 255
                    beamPaint.style = Paint.Style.STROKE
                    drawDart(
                        canvas, point.x, point.y,
                        beamAngleDeg(place.beamFrom, place.beamTo),
                        darts[0].color, 1.5f, stretched = false,
                    )
                }
            }
        }

        // Tooltip: "Step N of M" + instruction, flipped above/below the
        // target and clamped inside the window by ObstructionLayout.
        // Skipped for a PARKED pointer (stepCount 0 — search/type flows):
        // a parked pointer has a pill, not a step card.
        if (s.stepCount > 0) {
            val box = place.tooltip
            val bg = GradientDrawable().apply {
                setColor(0xE6141226.toInt())
                cornerRadius = 14f * density
                setStroke((1.5f * density).toInt(), pointerCyan)
            }
            bg.setBounds(
                box.left.toInt(), box.top.toInt(),
                box.right.toInt(), box.bottom.toInt(),
            )
            bg.draw(canvas)

            textPaint.color = pointerCyan
            textPaint.textSize = 13f * density
            canvas.drawText(label, box.left + 16f * density, box.top + 22f * density, textPaint)
            textPaint.color = 0xFFFFFFFF.toInt()
            textPaint.textSize = 15f * density
            canvas.drawText(
                s.instruction, box.left + 16f * density, box.top + 46f * density, textPaint,
            )
        }

        // Video-reference status pill: the only status UI in the reel is
        // a small pill pinned to the bottom ("24 agents 166 / 281
        // layers"). LOOM's equivalent, drawn during every live step:
        // "Guiding you — Step N of M" + a thin progress bar. Compact,
        // bottom-centred, never over the target.
        drawStatusPill(canvas, s, density)

        // Keep the frame loop alive only while something animates.
        if (anchorAnim != null || previewAnimating(now) || pulseStartMs >= 0 ||
            pressStartMs >= 0
        ) {
            invalidate()
        }
    }

    /**
     * David's correction (2026-11-05): one dart — a small elongated
     * paper-plane triangle pointing along [angleDeg]. White stroke outline
     * (swarm formula) + saturated fill. When [stretched] (moving fast)
     * it elongates along its direction with a dotted tail in its own
     * colour, the video's fast-motion language.
     */
    private fun drawDart(
        canvas: Canvas,
        x: Float,
        y: Float,
        angleDeg: Float,
        color: Int,
        scale: Float,
        stretched: Boolean,
    ) {
        val density = resources.displayMetrics.density
        val len = 14f * density * scale * if (stretched) 1.6f else 1f
        val halfW = 4.5f * density * scale
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angleDeg)
        // Dotted tail behind the dart while moving fast.
        if (stretched) {
            dartPaint.style = Paint.Style.FILL
            dartPaint.color = color
            for ((i, d) in listOf(0.95f, 1.35f, 1.75f).withIndex()) {
                dartPaint.alpha = 220 - i * 70
                canvas.drawCircle(-len * d, 0f, 2.2f * density, dartPaint)
            }
            dartPaint.alpha = 255
        }
        val path = Path().apply {
            moveTo(len, 0f)
            lineTo(-len * 0.6f, halfW)
            lineTo(-len * 0.25f, 0f)
            lineTo(-len * 0.6f, -halfW)
            close()
        }
        dartPaint.style = Paint.Style.STROKE
        dartPaint.strokeWidth = 2f * density
        dartPaint.strokeJoin = Paint.Join.ROUND
        dartPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, dartPaint)
        dartPaint.style = Paint.Style.FILL
        dartPaint.color = color
        canvas.drawPath(path, dartPaint)
        canvas.restore()
    }

    /** Beam direction in degrees (dart heading). */
    private fun beamAngleDeg(from: GeomPoint, to: GeomPoint): Float =
        Math.toDegrees(
            kotlin.math.atan2(
                (to.y - from.y).toDouble(),
                (to.x - from.x).toDouble(),
            ),
        ).toFloat()

    /** The 3 darts riding the beam toward the target, lead dart first. */
    private fun drawDartFormation(
        canvas: Canvas,
        from: GeomPoint,
        to: GeomPoint,
        moving: Boolean,
    ) {
        val angleDeg = beamAngleDeg(from, to)
        for (dart in darts) {
            val dx = from.x + (to.x - from.x) * dart.beamFraction
            val dy = from.y + (to.y - from.y) * dart.beamFraction
            drawDart(canvas, dx, dy, angleDeg, dart.color, dart.scale, moving)
        }
    }

    /**
     * Video-reference status pill (2026-11-05): pinned to the bottom
     * centre while a guidance step is live. POINTER FEW-CURSOR SPEC
     * DELTAS (2026-11-05): the pill NARRATES each phase — the tap /
     * guidance paths set it via [setStatusNarration] ("Moving to
     * Search…", "Tapping Search", "Long-pressing Search…"). Without a
     * narration it falls back to "Guiding you — Step N of M" with the
     * thin progress bar. Compact, bottom-centred, never over the target.
     * A PARKED pointer (stepCount 0 — search/type flows) shows its label
     * instead ("Typing…") and no progress bar: there is no step to count.
     */
    private fun drawStatusPill(canvas: Canvas, s: StepState, density: Float) {
        val text = statusNarration
            ?: if (s.stepCount <= 0) s.instruction
            else "Guiding you — Step ${s.stepIndex} of ${s.stepCount}"
        textPaint.textSize = 14f * density
        val textW = textPaint.measureText(text)
        val pad = 16f * density
        val pillW = textW + pad * 2
        val pillH = 48f * density
        val left = (width - pillW) / 2f
        val top = height - pillH - 24f * density
        if (top < 0f) return
        val bg = GradientDrawable().apply {
            setColor(0xE6141226.toInt())
            cornerRadius = pillH / 2f
            setStroke((1.5f * density).toInt(), pointerCyan)
        }
        bg.setBounds(left.toInt(), top.toInt(), (left + pillW).toInt(), (top + pillH).toInt())
        bg.draw(canvas)
        textPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(text, left + pad, top + 22f * density, textPaint)
        // Thin progress bar: filled fraction = stepIndex / stepCount.
        // Skipped for a parked pointer (stepCount 0): no step to count.
        if (s.stepCount <= 0) {
            beamPaint.style = Paint.Style.STROKE
            return
        }
        val barTop = top + 34f * density
        val barLeft = left + pad
        val barRight = left + pillW - pad
        val barH = 4f * density
        beamPaint.style = Paint.Style.FILL
        beamPaint.color = 0xFF3A3A4A.toInt()
        canvas.drawRoundRect(
            RectF(barLeft, barTop, barRight, barTop + barH),
            barH / 2f, barH / 2f, beamPaint,
        )
        val fraction = (s.stepIndex.toFloat() / s.stepCount.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        beamPaint.color = pointerCyan
        canvas.drawRoundRect(
            RectF(barLeft, barTop, barLeft + (barRight - barLeft) * fraction, barTop + barH),
            barH / 2f, barH / 2f, beamPaint,
        )
        beamPaint.style = Paint.Style.STROKE
    }

    /**
     * Device-evidence fix (2026-11-05, H3): the idle indicator — a
     * pulsing ring + solid dot at the anchor, labelled so David can
     * tell it apart from app content. Swarm lesson: it must be big,
     * bright, and MOVING (the pulse) or it reads as a dead pixel.
     * Re-scheduled ~2.5×/s while idle; a live step clears it.
     */
    private fun drawIdleIndicator(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val center = idleAnchor ?: GeomPoint(width / 2f, height / 2f)
        val phase = (SystemClock.uptimeMillis() % 800L) / 800f
        val radius = (26f + 8f * phase) * density
        ringPaint.style = Paint.Style.STROKE
        // Swarm formula: white outline under the saturated colour.
        ringPaint.color = 0xFFFFFFFF.toInt()
        ringPaint.alpha = (255 * (1f - phase * 0.6f)).toInt()
        ringPaint.strokeWidth = 6f * density
        canvas.drawCircle(center.x, center.y, radius, ringPaint)
        ringPaint.color = pointerCyan
        ringPaint.strokeWidth = 4f * density
        canvas.drawCircle(center.x, center.y, radius, ringPaint)
        ringPaint.alpha = 90
        ringPaint.strokeWidth = 11f * density
        canvas.drawCircle(center.x, center.y, radius, ringPaint)
        ringPaint.alpha = 255
        beamPaint.style = Paint.Style.FILL
        beamPaint.color = pointerCyan
        canvas.drawCircle(center.x, center.y, 8f * density, beamPaint)
        beamPaint.style = Paint.Style.STROKE
        // David's correction: the same 3 darts, gathered around the idle
        // dot and pointing at it — few and visible even with no step live.
        val idleR = 34f * density
        for ((i, dart) in darts.withIndex()) {
            val a = Math.toRadians((90.0 + i * 120.0))
            val dx = center.x + (idleR * kotlin.math.cos(a)).toFloat()
            val dy = center.y + (idleR * kotlin.math.sin(a)).toFloat()
            val angleToCenter = Math.toDegrees(
                kotlin.math.atan2(
                    (center.y - dy).toDouble(),
                    (center.x - dx).toDouble(),
                ),
            ).toFloat()
            drawDart(canvas, dx, dy, angleToCenter, dart.color, dart.scale, stretched = false)
        }
        // Label pill under the dot.
        val label = "LOOM pointer is on"
        textPaint.textSize = 14f * density
        val labelW = textPaint.measureText(label)
        val pad = 12f * density
        val boxLeft = (center.x - labelW / 2f - pad).coerceAtLeast(8f * density)
        val boxTop = center.y + radius + 10f * density
        val bg = GradientDrawable().apply {
            setColor(0xE6141226.toInt())
            cornerRadius = 12f * density
            setStroke((1.5f * density).toInt(), pointerCyan)
        }
        bg.setBounds(
            boxLeft.toInt(), boxTop.toInt(),
            (boxLeft + labelW + pad * 2).toInt(),
            (boxTop + 34f * density).toInt(),
        )
        bg.draw(canvas)
        textPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(label, boxLeft + pad, boxTop + 22f * density, textPaint)
        postInvalidateDelayed(400L)
    }

    private fun RectF.toGeom(): GeomRect = GeomRect(left, top, right, bottom)

    private fun GeomRect.toRectF(): RectF = RectF(left, top, right, bottom)

    companion object {
        /** Result-pulse duration. */
        private const val PULSE_MS = 600L

        /**
         * Tap-ripple press hold: the press dot is held this long after a
         * tap before the ring expands — long enough to read as a press,
         * short enough to feel instant.
         */
        private const val TAP_PRESS_HOLD_MS = 120L

        /** Tap-ripple expansion duration after the press releases. */
        private const val RIPPLE_MS = 450L

        /** Longest a long-press hold visual may run (clamps runaway holds). */
        private const val MAX_PRESS_HOLD_MS = 5000L
    }
}
