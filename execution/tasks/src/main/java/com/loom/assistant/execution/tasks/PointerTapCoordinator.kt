package com.loom.assistant.execution.tasks

import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.StepDispatchResult
import com.loom.assistant.intelligence.planner.TargetBounds

/**
 * App-aware execution (2026-11-07, David's directive §4) — the
 * pointer is a real interaction mechanism, not decoration.
 *
 * A coordinated tap proves the link between the pointer and the
 * action: the pointer moves to the target bounds and the tap is
 * issued AT those exact bounds' centre — the SAME bounds object the
 * pointer parked on. The tap point == the pointer parked point BY
 * CONSTRUCTION: there is no second, separately resolved centre to
 * drift away from. The coordinator returns the dispatch result
 * together with [PointerTapEvidence] so the caller can prove (or
 * report) the order: move → arrive → tap.
 *
 * A pointer draw failure is reported in the evidence but NEVER fails
 * the tap: display failure ≠ action failure (existing rule). The tap
 * still proceeds, and the evidence names the gap.
 */
data class PointerTapEvidence(
    /** The bounds the pointer parked on. */
    val lastPointerBounds: TargetBounds,
    /** Where the tap was issued — the same bounds object, by construction. */
    val tapPoint: TargetBounds,
    /** True when the pointer had arrived before the tap was issued. */
    val arrivedBeforeTap: Boolean,
    /** The pointer's last draw failure, if any (never fails the tap). */
    val pointerFailure: String?,
)

class PointerTapCoordinator(
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * Moves the pointer to [bounds] (highlighted), waits for it to
     * arrive ([PointerGuide.glideWaitMs]), then issues [tap] AT those
     * exact bounds. Returns the tap's result plus the evidence.
     * [targetId] is recorded in the evidence path only through the
     * caller's audit — the coordinator itself needs no tree access.
     */
    fun coordinateTap(
        bounds: TargetBounds,
        targetId: String,
        pointer: PointerGuide,
        tap: (TargetBounds) -> StepDispatchResult,
    ): Pair<StepDispatchResult, PointerTapEvidence> {
        var moveFailure: String? = null
        var arrived = false
        try {
            pointer.moveTo(bounds, highlight = true)
            // MASTER Wave 4 (F11): `arrived` must reflect an ACTUAL
            // draw. moveTo swallows draw failures internally (the
            // guides record them instead of throwing), so a
            // no-throw move used to claim arrival while nothing
            // rendered — the executor then logged "pointer-ok" for a
            // pointer David never saw. Arrival requires no throw AND
            // no recorded draw failure.
            arrived = try {
                pointer.lastPointerFailure()
            } catch (_: Exception) {
                null
            } == null
            if (!arrived) {
                moveFailure = try {
                    pointer.lastPointerFailure()
                } catch (_: Exception) {
                    null
                } ?: "pointer move did not draw"
            }
        } catch (e: Exception) {
            // Prefer the pointer's own failure report over the raw
            // exception message: the renderer names the failure the
            // same way for every step, so one reason groups the
            // steps it hit (the outcome names "steps 4, 6", not two
            // different wordings for the same broken draw).
            moveFailure = try {
                pointer.lastPointerFailure()
            } catch (_: Exception) {
                null
            } ?: (e.message ?: "pointer move threw")
            arrived = false
        }
        // Wait for the glide to arrive — unless the move already
        // failed to draw (there is nothing to wait for then).
        val waitMs = try {
            pointer.glideWaitMs(bounds)
        } catch (_: Exception) {
            0L
        }
        val drawFailure = try {
            pointer.lastPointerFailure()
        } catch (_: Exception) {
            null
        }
        val pointerFailure = moveFailure ?: drawFailure
        // A draw failure recorded after the move (e.g. by the glide
        // wait path) also un-arrives the pointer: the evidence must
        // never claim arrival when the last draw is known failed.
        if (drawFailure != null) arrived = false
        if (arrived && waitMs > 0 && pointerFailure == null) {
            try {
                sleeper(waitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // The tap goes to the SAME bounds object the pointer parked
        // on — tap point == pointer position, by construction.
        val result = try {
            tap(bounds)
        } catch (e: Exception) {
            StepDispatchResult.FAILED("Tap failed: ${e.message ?: "unknown error"}")
        }
        val evidence = PointerTapEvidence(
            lastPointerBounds = bounds,
            tapPoint = bounds,
            arrivedBeforeTap = arrived,
            pointerFailure = pointerFailure,
        )
        return result to evidence
    }
}
