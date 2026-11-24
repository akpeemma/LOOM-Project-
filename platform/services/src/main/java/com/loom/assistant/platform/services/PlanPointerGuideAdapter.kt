package com.loom.assistant.platform.services

import android.graphics.RectF
import com.loom.assistant.execution.tasks.boundsOf
import com.loom.assistant.execution.tasks.parseBoundsParam
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.TargetBounds
import com.loom.assistant.interaction.pointer.GeomPoint
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.interaction.pointer.PointerMotion

/**
 * Stage 2D (Worker B) — the plan pointer guide, no-session fallback.
 *
 * Implements [PointerGuide] over the existing
 * [GuidanceRendererContract]: [moveTo] glides the pointer to the
 * verified target (previewMoveTo, the same preview-before-action the
 * guidance session uses), [showIntended] parks the pointer on the
 * step's target with its description in the pill, and [trackTarget]
 * re-resolves an element id against a fresh snapshot. Display-only
 * throughout: moving or showing the pointer never taps, types, or
 * submits anything.
 *
 * NOTE (2026-11-06): Worker C's `PlanPointerGuide`
 * (interaction:pointer) HAS now landed, and [PlanWiring] uses it
 * whenever the caller supplies a GuidanceSession (the floating host
 * does). This adapter remains only for hosts with no session — the
 * in-app runs, where there is no GuidanceSession and no floating
 * renderer. The renderer provider is optional: with no renderer
 * (in-app runs, no floating overlay) move/show record a pointer
 * failure ([lastPointerFailure] — the executor names it in the
 * outcome) instead of silently doing nothing, while trackTarget still
 * re-resolves for real against the live tree.
 */
class PlanPointerGuideAdapter(
    private val observer: ScreenObserver,
    private val rendererProvider: (() -> GuidanceRendererContract?)? = null,
) : PointerGuide {

    /**
     * Pointer-drive fix (2026-11-06): the last pointer-drive failure
     * (no renderer, marshal timeout, policy HIDE, draw threw). Null
     * when the last attempt drew fine, or when nothing was attempted.
     * A successful draw clears it; the executor reads it after every
     * pointer call and names it in the plan outcome. The old code
     * swallowed every draw failure silently — a missed visual never
     * failed a step, but nobody ever said so either.
     */
    private var lastDrawFailure: String? = null

    /**
     * The step label of the last showIntended call — moveTo parks with
     * it (instead of the bare "Target") so the pill keeps naming the
     * step the pointer is on after the move.
     */
    private var lastLabel: String = "Target"

    override fun lastPointerFailure(): String? = lastDrawFailure

    override fun glideWaitMs(bounds: TargetBounds): Long {
        val renderer = liveRenderer() ?: return 0L
        val cursor = try {
            renderer.cursorPosition()
        } catch (_: Exception) {
            null
        }
        val to = GeomPoint(
            (bounds.left + bounds.right) / 2f,
            (bounds.top + bounds.bottom) / 2f,
        )
        val distance = if (cursor == null) 0f else PointerMotion.distance(cursor, to)
        return PointerMotion.glideDurationMs(distance)
    }

    /**
     * Runs a draw call, recording any failure instead of swallowing it
     * silently. Returns false when the draw could not be shown.
     */
    private fun drawCall(call: (GuidanceRendererContract) -> Unit): Boolean {
        val renderer = liveRenderer()
        if (renderer == null) {
            lastDrawFailure =
                "the pointer has nowhere to draw — the floating assistant isn't running"
            return false
        }
        return try {
            call(renderer)
            val failure = renderer.lastDrawFailure()
            if (failure != null) {
                lastDrawFailure = failure
                false
            } else {
                lastDrawFailure = null
                true
            }
        } catch (e: Exception) {
            lastDrawFailure = "the pointer could not be shown (${e.javaClass.simpleName})"
            false
        }
    }

    override fun moveTo(bounds: TargetBounds, highlight: Boolean) {
        val rect = RectF(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
        )
        // Glide (preview) — never a teleport; the highlight ring
        // comes from parking on the target when asked. The park keeps
        // the last step's label (showIntended's description), so the
        // pill keeps naming the step after the move.
        drawCall { it.previewMoveTo(rect) }
        if (highlight) {
            drawCall { it.parkPointer(rect, lastLabel) }
        }
    }

    override fun showIntended(step: PlanStep) {
        lastLabel = step.description
        val bounds = parseBoundsParam(step.params[PlanParams.BOUNDS])
        if (bounds != null) {
            drawCall {
                it.parkPointer(
                    RectF(
                        bounds.left.toFloat(),
                        bounds.top.toFloat(),
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat(),
                    ),
                    step.description,
                )
            }
        } else {
            // No resolvable target: never draw a fake outline — narrate
            // the step in the pill instead (LAUNCH_APP has no target).
            drawCall { it.setStatusNarration(step.description) }
        }
    }

    override fun trackTarget(targetId: String): Boolean {
        // No draw here — presence only, re-resolved against a fresh
        // snapshot. A draw failure can never make this report
        // target-gone: it never draws.
        val screen = try {
            observer.observeNow()
        } catch (_: Exception) {
            null
        } ?: return false
        return screen.elements.any { it.id == targetId }
    }

    /**
     * Plan over: nothing to release — this adapter holds no plan
     * target state (in-app runs have no floating renderer; its moves
     * are transient preview glides). Present so the executor's
     * completion path clears every PointerGuide the same way.
     */
    override fun clearPlanTarget() {
    }

    private fun liveRenderer(): GuidanceRendererContract? = try {
        rendererProvider?.invoke()
    } catch (_: Exception) {
        null
    }
}
