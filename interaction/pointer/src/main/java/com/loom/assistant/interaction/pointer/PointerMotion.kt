package com.loom.assistant.interaction.pointer

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * POINTER FEW-CURSOR SPEC DELTAS (2026-11-05, David's direction ~17:53 WAT).
 *
 * Pure pointer-motion math, no Android imports: JVM-testable.
 *
 * Two ideas, borrowed as DESIGN ONLY from the references named in
 * POINTER-FEW-CURSOR-SPEC.md — no code lifted from any of them:
 *  - ghost-cursor-playwright: verify the cursor is really over the target
 *    before clicking, and move along a curved path with overshoot-and-
 *    correct on long moves. (That repo is web/Playwright, not Android;
 *    the formulae below are the standard quadratic-Bezier / easeOutBack
 *    equations, written from scratch here.)
 *  - farzaa/clicky (macOS): a single visible pointer that flies to its
 *    target on a Bezier arc and lands in a "pointing at target" state.
 *  - swarm-cursors is a Chrome extension in JavaScript with NO licence:
 *    only its visibility language (bright colours, gather, outline,
 *    status pill) is borrowed — never its code, and LOOM taps still go
 *    through its own AccessibilityService gesture path only.
 *
 * What this object owns:
 *  - [isCursorOverTarget]: the position check the session runs immediately
 *    before dispatch — the owned cursor position must be inside the
 *    verified target bounds (plus tolerance), or the tap/long-press is
 *    refused and nothing dispatches.
 *  - [glidePoint]: the curved glide the renderer animates the preview
 *    along — a quadratic Bezier whose control point sits perpendicular
 *    to the straight line, so the eye can follow a curve instead of a
 *    teleport or a straight-line jump; long moves overshoot slightly
 *    past the target and settle back (easeOutBack on the final approach).
 *  - [glideDurationMs]: the glide duration — 250ms baseline (SPEED,
 *    David 2026-11-05 18:58: visible but quick), up to 300ms for long
 *    moves — never longer, because the queue dispatches 300ms after the
 *    preview starts and the pointer must have arrived by then.
 */
object PointerMotion {

    /**
     * Baseline glide duration — the spec's quick slide. SPEED (David,
     * 2026-11-05 18:58): smooth visible motion is intentional so the eye
     * can follow, but it must be QUICK — 250ms reads as a deliberate
     * glide, not a teleport or a drift. (The pointer worker's 400ms
     * slide was the top of the range; 250ms is the standard now.)
     */
    const val BASE_GLIDE_MS = 250L

    /**
     * Longest a glide may run. SPEED: the queue dispatches 300ms after
     * the preview starts ([PreviewDelayer.delayed]) — the pointer must
     * ARRIVE before the tap dispatches, so no glide may run longer
     * than 300ms. A glide that outlasts the dispatch would tap while
     * the pointer is still travelling.
     */
    const val MAX_GLIDE_MS = 300L

    /** Moves longer than this (px) get the overshoot-and-correct finish. */
    const val LONG_MOVE_PX = 400f

    /** Overshoot amount on a long move, as a fraction of the move distance. */
    const val OVERSHOOT_FRACTION = 0.06f

    /** Perpendicular bow of the Bezier control point, fraction of distance. */
    const val CURVE_BOW_FRACTION = 0.18f

    /** Verification tolerance: cursor may sit this far outside the bounds. */
    const val VERIFY_TOLERANCE_PX = 8f

    /**
     * True when [cursor] is over [target] (expanded by [tolerancePx] on
     * every side). Null cursor = unknown position = NOT verified — the
     * caller refuses rather than guessing.
     */
    fun isCursorOverTarget(
        cursor: GeomPoint?,
        target: GeomRect,
        tolerancePx: Float = VERIFY_TOLERANCE_PX,
    ): Boolean {
        if (cursor == null) return false
        return cursor.x >= target.left - tolerancePx &&
            cursor.x <= target.right + tolerancePx &&
            cursor.y >= target.top - tolerancePx &&
            cursor.y <= target.bottom + tolerancePx
    }

    /**
     * Point on the quadratic Bezier from [from] through [control] to [to]
     * at parameter [t] (0 = start, 1 = end). Standard formula, written
     * from scratch: (1-t)²·P0 + 2(1-t)t·C + t²·P1.
     */
    fun bezierPoint(from: GeomPoint, control: GeomPoint, to: GeomPoint, t: Float): GeomPoint {
        val u = 1f - t
        return GeomPoint(
            u * u * from.x + 2f * u * t * control.x + t * t * to.x,
            u * u * from.y + 2f * u * t * control.y + t * t * to.y,
        )
    }

    /**
     * Control point for the glide curve: the midpoint of [from]→[to]
     * pushed perpendicular by [CURVE_BOW_FRACTION] of the distance (capped
     * at 90px so the bow never swings the pointer across the screen).
     * The bow bends toward the top of the screen (negative y), the
     * direction the eye reads as an arc rather than a detour.
     */
    fun glideControl(from: GeomPoint, to: GeomPoint): GeomPoint {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dist = hypot(dx, dy)
        if (dist < 1f) return GeomPoint((from.x + to.x) / 2f, (from.y + to.y) / 2f)
        val bow = (dist * CURVE_BOW_FRACTION).coerceAtMost(90f)
        // Unit perpendicular, rotated so the bow bends upward (-y).
        var px = -dy / dist
        var py = dx / dist
        if (py > 0f) {
            px = -px
            py = -py
        }
        return GeomPoint(
            (from.x + to.x) / 2f + px * bow,
            (from.y + to.y) / 2f + py * bow,
        )
    }

    /**
     * Ease-out-back: rises past 1 and settles back to exactly 1 at t=1 —
     * the overshoot-and-correct finish. Standard easeOutBack (c1=1.70158,
     * c3=c1+1), written from scratch.
     */
    fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    /**
     * The glide position at progress [t] (0..1) from [from] to [to]:
     * a Bezier curve through [glideControl], with the parameter eased —
     * ease-in-out for the bulk of the move, and for long moves the final
     * approach overshoots past the target by [OVERSHOOT_FRACTION] of the
     * distance and settles back, so the pointer visibly ARRIVES instead
     * of stopping dead. At t=1 the result is exactly [to] — the cursor
     * never rests off-target.
     */
    fun glidePoint(from: GeomPoint, to: GeomPoint, t: Float): GeomPoint {
        val clamped = t.coerceIn(0f, 1f)
        if (clamped <= 0f) return from
        if (clamped >= 1f) return to
        val dist = hypot(to.x - from.x, to.y - from.y)
        val control = glideControl(from, to)
        // Ease the parameter. Short moves: plain smoothstep. Long moves:
        // smoothstep remapped to reach 0.7 at t=0.7, then easeOutBack
        // over the last 30% — continuous at the join (both sides are
        // exactly 0.7 there), overshooting to ~1.03 mid-finish and
        // settling back to exactly 1.0 at t=1.
        val eased = if (dist >= LONG_MOVE_PX) {
            if (clamped <= 0.7f) {
                val local = clamped / 0.7f
                0.7f * (local * local * (3f - 2f * local))
            } else {
                val local = (clamped - 0.7f) / 0.3f
                0.7f + 0.3f * easeOutBack(local)
            }
        } else {
            clamped * clamped * (3f - 2f * clamped) // smoothstep
        }
        // The overshoot lives in the eased parameter (it exceeds 1 mid-way
        // through the last 30%): extrapolate past the curve end along the
        // arrival tangent. Parameter excess × distance ≈ overshoot in px,
        // capped at OVERSHOOT_FRACTION of the move.
        return if (eased > 1f) {
            val beyond = bezierPoint(from, control, to, 1f)
            val before = bezierPoint(from, control, to, 0.97f)
            val ox = beyond.x - before.x
            val oy = beyond.y - before.y
            val len = sqrt(ox * ox + oy * oy).coerceAtLeast(1f)
            val over = ((eased - 1f) * dist).coerceAtMost(dist * OVERSHOOT_FRACTION)
            GeomPoint(beyond.x + ox / len * over, beyond.y + oy / len * over)
        } else {
            bezierPoint(from, control, to, eased)
        }
    }

    /**
     * Glide duration for a move of [distancePx]: the 250ms baseline,
     * +1ms per 8px beyond 400px, capped at [MAX_GLIDE_MS] (300ms — the
     * queue dispatches then, so the pointer must have arrived). Short
     * moves never run shorter than the baseline — a faster glide would
     * read as a teleport.
     */
    fun glideDurationMs(distancePx: Float): Long {
        val extra = ((distancePx - LONG_MOVE_PX).coerceAtLeast(0f) / 8f).toLong()
        return (BASE_GLIDE_MS + extra).coerceAtMost(MAX_GLIDE_MS)
    }

    /** Straight-line distance between two points (px). */
    fun distance(from: GeomPoint, to: GeomPoint): Float =
        hypot(to.x - from.x, to.y - from.y)

    /** True when the two points are within [tolerancePx] of each other. */
    fun isSettled(a: GeomPoint, b: GeomPoint, tolerancePx: Float = VERIFY_TOLERANCE_PX): Boolean =
        abs(a.x - b.x) <= tolerancePx && abs(a.y - b.y) <= tolerancePx
}
