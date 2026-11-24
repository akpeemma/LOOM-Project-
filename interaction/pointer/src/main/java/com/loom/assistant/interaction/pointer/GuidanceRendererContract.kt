package com.loom.assistant.interaction.pointer

import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection

/**
 * Guidance rendering contract — EXACT members, shared with the worker that
 * implements the renderer (interaction:pointer) and the worker that hosts
 * it (platform:services). Do not rename.
 *
 * Moved from execution:interaction (InteractionLayer.kt) to
 * interaction:pointer so the module dependency edge runs one direction
 * only: execution:interaction → interaction:pointer. Previously the contract
 * living in execution:interaction forced interaction:pointer to import from
 * execution:interaction, while execution:interaction already imported
 * PointerPolicy from interaction:pointer — a two-way module dependency.
 * (Build-foundation prerequisite, LIFECYCLE-PLAN.md Addendum A.)
 */
interface GuidanceRendererContract {
    fun showStep(
        anchorX: Float,
        anchorY: Float,
        target: RectF,
        stepIndex: Int,
        stepCount: Int,
        instruction: String,
    )

    fun hide()

    fun previewMoveTo(target: RectF)

    fun pulseOnResult()

    fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float)

    /**
     * "fix everything" (2026-11-05): nudge the visible pointer indicator
     * one step (stepDp in dp) in the given direction, clamped inside the
     * window. Returns false when nothing is shown (idle) — the caller
     * reports honestly instead of claiming a move. Display-only: the
     * offset moves the drawn indicator, never a tap target.
     */
    fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean

    /**
     * Device-evidence fix (2026-11-05, pointer H3): render a persistent
     * idle indicator proving the pointer is visible when no guidance
     * step is live — "show the pointer" must SHOW something, not just
     * set a policy. Returns true when an indicator is now visible (a
     * live step already counts: the pointer is showing). Returns false
     * when nothing can be drawn (no window, policy HIDE) — the caller
     * then says so honestly instead of claiming visibility. Default
     * no-op (false) so existing implementers keep compiling.
     */
    fun showIdleIndicator(): Boolean = false

    // ---- POINTER FEW-CURSOR SPEC DELTAS (2026-11-05) ----
    // All default no-ops so existing implementers keep compiling; the
    // real renderer (GuidanceRenderer) implements them. The spec's
    // standard: few bright cursors that glide (no teleporting), gather,
    // outline, tap with a VISIBLE ripple, and narrate each step in the
    // status pill. swarm-cursors is a Chrome extension in JS with no
    // licence — only its visibility language is borrowed, never its code;
    // LOOM taps go through its own AccessibilityService gesture path only.

    /**
     * Visible tap ripple / press state at the tap point: the lead dart
     * performs the tap, and this draws a distinct expanding ring + press
     * dot at the tap point (briefly held) so David can SEE the tap land.
     * Called by the tap path when the system accepts the gesture —
     * distinct from [pulseOnResult], which fires on async completion.
     * Default no-op.
     */
    fun showTapRipple() {}

    /**
     * POINTER CHOREOGRAPHY FOR SEARCH/TYPE (2026-11-05): park the
     * pointer on a target OUTSIDE a guidance step. The search/type
     * flows have no step list, but the pointer must still be visible
     * there: glide to the field, park the cursor on it, pill narrates
     * ("Typing…"). Draws the ring + gathered darts + pill for [label];
     * no step numbering, no tooltip. Replaces any parked pointer; a
     * later showStep replaces it with a real step. Default no-op.
     */
    fun parkPointer(target: RectF, label: String) {}

    /**
     * Clear a parked pointer (see [parkPointer]). A real guidance step
     * is untouched — only a parked pointer clears. Default no-op.
     */
    fun clearParked() {}

    /**
     * Selection highlight: a translucent fill over the selected text
     * element + selection handles, shown ONLY after a selection is
     * confirmed — never as a claim before the system confirms it.
     * Default no-op.
     */
    fun showSelection(target: RectF, label: String) {}

    /**
     * Visible press state held for a long-press: a press dot + ring held
     * at the tap point for [holdMs] milliseconds, then released into the
     * tap ripple. Called by the long-press path when the system accepts
     * the hold gesture. Default no-op.
     */
    fun showPressState(holdMs: Long) {}

    /**
     * Status-pill narration for the current step, e.g. "Moving to
     * Search…" or "Tapping Search" — the pill narrates each phase, not
     * just "Guiding you — Step N of M". Passing an empty string clears
     * the narration back to the default step label. Default no-op.
     */
    fun setStatusNarration(text: String) {}

    /**
     * The ONE owned cursor position, or null when the renderer cannot
     * report one (nothing shown / unknown). The tap path reads this
     * immediately before dispatch to verify the cursor is really over
     * the target ([isCursorOver]) — null means "unknown", and the caller
     * refuses rather than guessing. Default null.
     */
    fun cursorPosition(): GeomPoint? = null

    /**
     * Pointer-drive fix (2026-11-06): the last draw failure, if the most
     * recent draw could not be shown (no window, marshal timeout,
     * policy HIDE swallow, draw threw). Null when the last draw landed
     * fine, or when nothing was attempted. The pointer guides read this
     * after every draw call so a display failure is reported as a
     * pointer failure — never silently swallowed, never misreported as
     * a target failure. Default null so existing implementers keep
     * compiling.
     */
    fun lastDrawFailure(): String? = null

    /**
     * True when the owned cursor position is over [target] (expanded by
     * [tolerancePx] on every side). Default implementation reads
     * [cursorPosition] through [PointerMotion]; false when the position
     * is unknown — verification fails closed.
     */
    fun isCursorOver(target: RectF, tolerancePx: Float = PointerMotion.VERIFY_TOLERANCE_PX): Boolean {
        val cursor = cursorPosition() ?: return false
        return PointerMotion.isCursorOverTarget(
            cursor,
            GeomRect(target.left, target.top, target.right, target.bottom),
            tolerancePx,
        )
    }
}
