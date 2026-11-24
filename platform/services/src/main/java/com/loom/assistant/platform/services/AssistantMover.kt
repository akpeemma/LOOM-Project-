package com.loom.assistant.platform.services

import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.interaction.pointer.AssistantPlacement
import com.loom.assistant.interaction.pointer.GeomRect

/**
 * Master implementation §7 — ASSISTANT_MOVE: voice-directed movement of
 * the floating assistant itself (character / orb), distinct from
 * POINTER_MOVE (which moves only the pointer indicator and needs guidance
 * to be active).
 *
 * Exact API — the speech worker calls [move]; do not rename:
 *  - `object AssistantMover`, `enum AssistantRegion`,
 *    `sealed class MoveResult`, `fun move(region: AssistantRegion): MoveResult`.
 *
 * Behaviour:
 *  - Corners / edges move the overlay view to that safe-area region;
 *    UP/DOWN/LEFT/RIGHT nudge it one 48dp step (same step as POINTER_MOVE);
 *    OUT_OF_WAY reads the current ScreenModel and moves to the largest
 *    content-free rectangle (avoiding BUTTON/INPUT bounds).
 *  - Check-before-move on EVERY move: the target rect must not cover an
 *    interactive element (reuse of [AssistantPlacement], the
 *    ObstructionLayout pattern applied to the assistant rect). A covered
 *    spot falls back to the nearest non-covering spot; when none exists
 *    the move fails honestly instead of covering a control.
 *  - The target is clamped to the safe area and persisted (via the
 *    backend, which owns FloatingPositionStore).
 *  - Display-only: moving never retargets taps and needs no AuthGate
 *    execution — this object imports no AuthGate code, by construction.
 *  - NO automatic repositioning: the assistant moves only when told to.
 *    NO MediaProjection: placement reads the accessibility tree only.
 *
 * The overlay view itself lives in LoomOverlayService (private fields),
 * so the host registers a [Backend] once — see [AssistantMoverBackend]
 * and its file header for the exact registration lines. Until a backend
 * is registered, every move fails honestly ("assistant is not attached").
 */
object AssistantMover {

    enum class AssistantRegion {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        LEFT_EDGE, RIGHT_EDGE,
        UP, DOWN, LEFT, RIGHT,
        OUT_OF_WAY,
    }

    sealed class MoveResult {
        object Ok : MoveResult()
        data class Failed(val reason: String) : MoveResult()
    }

    /** Screen rect in pixels (left/top inclusive, right/bottom exclusive). */
    data class AssistantRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Host-provided seam. The production implementation is
     * [AssistantMoverBackend] (WindowManager + the live overlay view +
     * ScreenAwarenessHolder); tests use a fake. The mover never touches
     * Android classes directly, so it stays JVM-testable.
     */
    interface Backend {
        /** Current assistant rect in screen pixels, or null when detached. */
        fun currentRect(): AssistantRect?

        /** Safe area the assistant may occupy (system bars + cutout excluded). */
        fun safeArea(): AssistantRect

        /** Display density (dp -> px scale) for the nudge step and margin. */
        fun density(): Float

        /** Current screen model for check-before-move / OUT_OF_WAY, or null. */
        fun screenModel(): ScreenModel?

        /** Apply the move to the live view; false when it detached mid-move. */
        fun applyMove(x: Int, y: Int): Boolean

        /** Persist the new position (FloatingPositionStore, best-effort). */
        fun persistPosition(x: Int, y: Int)
    }

    @Volatile
    private var backend: Backend? = null

    /** Host wiring: LoomOverlayService registers its backend in onCreate. */
    fun register(backend: Backend?) {
        this.backend = backend
    }

    /** Nudge step for UP/DOWN/LEFT/RIGHT, in dp (same as POINTER_MOVE). */
    const val NUDGE_STEP_DP = 48f

    /** Corner/edge margin inside the safe area, in dp. */
    const val REGION_MARGIN_DP = 8f

    fun move(region: AssistantRegion): MoveResult {
        val b = backend
            ?: return MoveResult.Failed(
                "The floating assistant isn't showing right now — " +
                    "start the assistant first, then ask me to move it.",
            )
        val current = b.currentRect()
            ?: return MoveResult.Failed(
                "The floating assistant isn't on screen right now — " +
                    "start the assistant first, then ask me to move it.",
            )
        val safe = b.safeArea()
        if (safe.width <= 0 || safe.height <= 0) {
            return MoveResult.Failed(
                "I can't tell where the safe area of the screen is right now.",
            )
        }
        // Check-before-move needs the tree on EVERY move: without it the
        // mover cannot verify the target covers nothing interactive, so
        // it refuses honestly instead of guessing.
        val model = b.screenModel()
            ?: return MoveResult.Failed(
                "I can't read the screen right now — turn on LOOM in " +
                    "Settings → Accessibility → Device Control to move the assistant.",
            )
        val density = b.density().takeIf { it > 0f } ?: 1f
        val margin = (REGION_MARGIN_DP * density).toInt()
        val stepPx = NUDGE_STEP_DP * density
        val w = current.width
        val h = current.height
        val safeGeom = GeomRect(
            safe.left.toFloat(), safe.top.toFloat(),
            safe.right.toFloat(), safe.bottom.toFloat(),
        )

        val desired: GeomRect = when (region) {
            AssistantRegion.TOP_LEFT -> GeomRect(
                (safe.left + margin).toFloat(), (safe.top + margin).toFloat(),
                (safe.left + margin + w).toFloat(), (safe.top + margin + h).toFloat(),
            )
            AssistantRegion.TOP_RIGHT -> GeomRect(
                (safe.right - margin - w).toFloat(), (safe.top + margin).toFloat(),
                (safe.right - margin).toFloat(), (safe.top + margin + h).toFloat(),
            )
            AssistantRegion.BOTTOM_LEFT -> GeomRect(
                (safe.left + margin).toFloat(), (safe.bottom - margin - h).toFloat(),
                (safe.left + margin + w).toFloat(), (safe.bottom - margin).toFloat(),
            )
            AssistantRegion.BOTTOM_RIGHT -> GeomRect(
                (safe.right - margin - w).toFloat(), (safe.bottom - margin - h).toFloat(),
                (safe.right - margin).toFloat(), (safe.bottom - margin).toFloat(),
            )
            AssistantRegion.LEFT_EDGE -> GeomRect(
                (safe.left + margin).toFloat(),
                (safe.top + (safe.height - h) / 2).toFloat(),
                (safe.left + margin + w).toFloat(),
                (safe.top + (safe.height - h) / 2 + h).toFloat(),
            )
            AssistantRegion.RIGHT_EDGE -> GeomRect(
                (safe.right - margin - w).toFloat(),
                (safe.top + (safe.height - h) / 2).toFloat(),
                (safe.right - margin).toFloat(),
                (safe.top + (safe.height - h) / 2 + h).toFloat(),
            )
            AssistantRegion.UP -> GeomRect(
                current.left.toFloat(), current.top - stepPx,
                current.right.toFloat(), current.bottom - stepPx,
            )
            AssistantRegion.DOWN -> GeomRect(
                current.left.toFloat(), current.top + stepPx,
                current.right.toFloat(), current.bottom + stepPx,
            )
            AssistantRegion.LEFT -> GeomRect(
                current.left - stepPx, current.top.toFloat(),
                current.right - stepPx, current.bottom.toFloat(),
            )
            AssistantRegion.RIGHT -> GeomRect(
                current.left + stepPx, current.top.toFloat(),
                current.right + stepPx, current.bottom.toFloat(),
            )
            AssistantRegion.OUT_OF_WAY -> {
                val free = AssistantPlacement.largestFreeRect(
                    safe = safeGeom,
                    assistantW = w.toFloat(),
                    assistantH = h.toFloat(),
                    elements = model.elements,
                    gridStepPx = stepPx / 2f,
                ) ?: return MoveResult.Failed(
                    "There's no free spot on this screen — every area " +
                        "has buttons or fields in the way.",
                )
                free
            }
        }

        // Check-before-move: refuse a covering spot, take the nearest free
        // one; fail honestly when the screen leaves nowhere to go.
        // (OUT_OF_WAY already returns a free rect; the check is a no-op
        // for it and stays as a safety net.)
        val placed = AssistantPlacement.findFreeSpot(
            desired = desired,
            safe = safeGeom,
            elements = model.elements,
            stepPx = stepPx,
        ) ?: return MoveResult.Failed(
            "Every nearby spot would cover a button or field — " +
                "I didn't move the assistant.",
        )
        val x = placed.left.toInt()
        val y = placed.top.toInt()
        if (!b.applyMove(x, y)) {
            return MoveResult.Failed(
                "The assistant moved away while I was moving it — try again.",
            )
        }
        try {
            b.persistPosition(x, y)
        } catch (_: Exception) {
            // Persistence is best-effort; the move itself succeeded.
        }
        return MoveResult.Ok
    }
}
