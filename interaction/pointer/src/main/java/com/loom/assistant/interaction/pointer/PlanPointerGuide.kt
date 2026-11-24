package com.loom.assistant.interaction.pointer

import android.graphics.RectF
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.TargetBounds

/**
 * Stage 2D (Worker C) — the planner's pointer.
 *
 * Implements Worker A's [PointerGuide] over the existing
 * [GuidanceSession] / [GuidanceRendererContract] — it REUSES the
 * renderer (the 3 darts, lead cyan #00E5FF, mint, yellow; ring +
 * corner handles; bottom status pill) and never duplicates it:
 * - [moveTo] glides the darts to the target centre. The glide is the
 *   renderer's own preview animation (250ms baseline, max 300ms —
 *   [PointerMotion.BASE_GLIDE_MS] / [PointerMotion.MAX_GLIDE_MS]).
 *   `previewMoveTo` is a no-op with no live step, so the first move
 *   — or any move with highlight=true — PARKS the pointer on the
 *   target (ring + corner handles + darts gathered on it); a later
 *   move with highlight=false glides from the live pointer instead
 *   of re-parking. The existing renderer draws its ring for any
 *   parked pointer, so a first park with highlight=false still shows
 *   the ring — the flag is honoured as glide-vs-park, and no renderer
 *   redesign was done (minimal-edit constraint).
 * - [showIntended] renders the step's target outline + the step's
 *   plain-words label in the status pill BEFORE any tap
 *   (transparency). Bounds come from the step's [PlanParams.BOUNDS]
 *   ("left,top,right,bottom") or from re-resolving its TARGET /
 *   GUIDE_TARGET against the live tree; when neither resolves,
 *   nothing is drawn — never a fake outline.
 * - [trackTarget] re-resolves the target id against a fresh screen
 *   snapshot and glides to its new bounds, returning false when the
 *   target is gone — the executor must then treat it as target-gone
 *   (ask or abort), never tap stale coordinates.
 * - No-idle rule: while a plan target is active the pointer stays on
 *   it — [showIdleIfAllowed] refuses the centre idle pulse, which
 *   would drag the darts away from the target. The idle pulse is
 *   used ONLY when no plan target is active; [clearPlanTarget] ends
 *   the plan target (the pointer may idle again). Hosts must call
 *   [showIdleIfAllowed] instead of the renderer's showIdleIndicator
 *   directly while a plan is running.
 *
 * Display-only throughout: moving or showing the pointer never taps,
 * types, or submits anything. No `dispatchGesture` anywhere here.
 */
class PlanPointerGuide(
    private val session: GuidanceSession,
    private val rendererOverride: GuidanceRendererContract? = null,
    /**
     * MASTER Wave 4 (F11) — the renderer PROVIDER, re-invoked on
     * every draw. The old code captured `rendererProvider?.invoke()`
     * once at wiring time (PlanWiring) and passed the captured
     * renderer here, so the plan path could draw into a detached
     * view silently for a whole run. A provider is re-resolved per
     * draw (the PlanPointerGuideAdapter pattern); a fixed renderer
     * passed as [rendererOverride] is still honoured for hosts and
     * tests that own one.
     */
    private val rendererProvider: (() -> GuidanceRendererContract?)? = null,
) : PointerGuide {

    /** The plan target the pointer is currently parked on, if any. */
    var activeTarget: TargetBounds? = null
        private set

    /** Plain-words label of the active target (the step description). */
    var activeLabel: String = ""
        private set

    /** True once this guide has parked a pointer (a live step exists to glide from). */
    private var pointerLive = false

    /**
     * Pointer-drive fix (2026-11-06): the last pointer-drive failure
     * (no renderer, marshal timeout, policy HIDE, draw threw). Null
     * when the last attempt drew fine, or when nothing was attempted.
     * A successful draw clears it; the executor reads it after every
     * pointer call and names it in the plan outcome.
     */
    private var lastDrawFailure: String? = null

    override fun lastPointerFailure(): String? = lastDrawFailure

    override fun glideWaitMs(bounds: TargetBounds): Long = glideDurationMs(bounds)

    private fun renderer(): GuidanceRendererContract? {
        // MASTER Wave 4 (F11): the provider is re-invoked on every
        // draw — a renderer captured once at wiring time can go
        // detached mid-run and keep swallowing draws silently.
        // A provider throw never becomes a draw: null means "nowhere
        // to draw", which drawCall records as a failure.
        val fromProvider = try {
            rendererProvider?.invoke()
        } catch (_: Exception) {
            null
        }
        return fromProvider ?: rendererOverride ?: session.currentRenderer()
    }

    /**
     * Runs a draw call, recording any failure instead of swallowing it
     * silently (the old code let a draw throw propagate out of
     * moveTo/trackTarget, which the executor misread as "target
     * gone"). Returns false when the draw could not be shown.
     */
    private fun drawCall(call: (GuidanceRendererContract) -> Unit): Boolean {
        val r = renderer()
        if (r == null) {
            lastDrawFailure =
                "the pointer has nowhere to draw — the floating assistant isn't running"
            return false
        }
        return try {
            call(r)
            val failure = r.lastDrawFailure()
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
        val rect = bounds.toRectF()
        if (highlight || !pointerLive) {
            // Park: ring + corner handles + the 3 darts gathered on
            // the target, gliding from the previous target over the
            // renderer's preview animation. A pointer has to exist
            // before it can glide, so the first move parks — and the
            // pointer only counts as live when the park actually drew
            // (a failed draw must not make the next move glide into
            // nothing).
            // MASTER Wave 4 (F11): a failed draw parks NOTHING and
            // [lastPointerFailure] names it — but [activeTarget] still
            // records the INTENDED target (the Nov 6 pointer-drive
            // rule: the target is there, only the draw failed, so
            // tracking and the no-idle rule keep working; arrival
            // truth lives in lastPointerFailure, checked by
            // PointerTapCoordinator — never in activeTarget).
            if (drawCall { it.parkPointer(rect, activeLabel) }) {
                pointerLive = true
            }
            activeTarget = bounds
        } else {
            // A pointer is already live: glide it (250ms) without
            // re-parking.
            drawCall { it.previewMoveTo(rect) }
            activeTarget = bounds
        }
    }

    /**
     * MASTER Wave 4 (F11 strengthening) — verified-only parking.
     * Parks on [bounds] ONLY when [verified] is true (the target was
     * resolved against a fresh screen and the pill names it). An
     * unverified element — a call-log row, a post, a banner — never
     * gets parked on: the pointer goes to the neutral home position
     * instead (see [parkAtNeutralHome]). Returns true when parked on
     * the target, false when it went home.
     */
    fun parkVerified(bounds: TargetBounds, label: String, verified: Boolean): Boolean {
        if (!verified) return false
        activeLabel = label
        moveTo(bounds, highlight = true)
        // Arrival truth is the draw result, not activeTarget (which
        // records the intended target even when the draw failed).
        return lastPointerFailure() == null
    }

    /**
     * MASTER Wave 4 (F11 strengthening) — the neutral home position.
     * When there is no verified target the pointer waits at the top
     * centre of the screen, off the content — visible, but claiming
     * nothing. Never parks on a random element. Returns false when
     * even the home park could not draw (recorded as a draw failure).
     */
    fun parkAtNeutralHome(screenWidthPx: Float, screenHeightPx: Float): Boolean {
        val home = PointerParkingPolicy.homeRect(screenWidthPx, screenHeightPx)
        activeLabel = ""
        val drawn = drawCall {
            it.parkPointer(
                RectF(home.left, home.top, home.right, home.bottom),
                "LOOM",
            )
        }
        if (drawn) {
            pointerLive = true
            activeTarget = null
        } else {
            activeTarget = null
        }
        return drawn
    }

    /** No-arg form over the live screen size (see [screenSize] via the session). */
    override fun parkAtNeutralHome() {
        val size = try {
            session.screenSize()
        } catch (_: Exception) {
            null
        } ?: return
        parkAtNeutralHome(size.first.toFloat(), size.second.toFloat())
    }

    override fun showIntended(step: PlanStep) {
        val bounds = boundsFromStep(step)
        if (bounds == null) {
            // No resolvable target: never draw a fake outline — but
            // still narrate the step in the pill when a renderer is
            // live (mirrors the adapter's fallback). A step with no
            // target to show (e.g. LAUNCH_APP) is not a draw failure;
            // a missing renderer still is, and drawCall records it.
            drawCall { it.setStatusNarration(step.description) }
            return
        }
        activeLabel = step.description
        // Outline + plain-words label in the status pill BEFORE any
        // tap (transparency): the parked pointer carries the step's
        // description as its pill label, and the narration repeats it.
        // MASTER Wave 4 (F11): a failed draw parks nothing and is
        // recorded in lastPointerFailure; the intended target is still
        // recorded (tracking / no-idle), arrival truth is the failure
        // signal, never activeTarget.
        if (drawCall { it.parkPointer(bounds.toRectF(), step.description) }) {
            pointerLive = true
        }
        activeTarget = bounds
        drawCall { it.setStatusNarration(step.description) }
    }

    override fun trackTarget(targetId: String): Boolean {
        val element = session.resolveElementById(targetId)
        if (element == null) {
            // Target gone: never pretend to track it. A genuinely gone
            // target is NOT a pointer failure — clear any earlier draw
            // failure so the executor reads this as target-gone.
            lastDrawFailure = null
            activeTarget = null
            activeLabel = ""
            return false
        }
        val bounds = TargetBounds(
            element.bounds.left, element.bounds.top,
            element.bounds.right, element.bounds.bottom,
        )
        // The target is THERE — if only the draw fails, that is a
        // pointer failure, never "target gone": record it and keep
        // tracking (the tap can still be correct).
        moveTo(bounds, highlight = true)
        return true
    }

    /**
     * No-idle rule: the centre idle pulse fires ONLY when no plan
     * target is active. Returns false (and draws nothing) while a
     * plan target is active — the pointer stays on that target.
     * Hosts must call this instead of the renderer's
     * showIdleIndicator() directly while a plan is running.
     */
    fun showIdleIfAllowed(): Boolean {
        if (activeTarget != null) return false
        return renderer()?.showIdleIndicator() == true
    }

    /** Plan over / target cleared: the pointer may idle again. */
    override fun clearPlanTarget() {
        activeTarget = null
        activeLabel = ""
        pointerLive = false
        lastDrawFailure = null
    }

    /**
     * Glide duration for the move from the current cursor to
     * [bounds] — the 250ms baseline, up to 300ms for long moves
     * ([PointerMotion.glideDurationMs]); 250ms when the cursor is
     * unknown. The pointer must have arrived before the queue
     * dispatches, so no glide may run longer than 300ms.
     */
    fun glideDurationMs(bounds: TargetBounds): Long {
        val cursor = renderer()?.cursorPosition()
        val to = GeomPoint(
            (bounds.left + bounds.right) / 2f,
            (bounds.top + bounds.bottom) / 2f,
        )
        val distance = if (cursor == null) 0f else PointerMotion.distance(cursor, to)
        return PointerMotion.glideDurationMs(distance)
    }

    private fun boundsFromStep(step: PlanStep): TargetBounds? {
        step.params[PlanParams.BOUNDS]?.let { raw ->
            parseBounds(raw)?.let { return it }
        }
        val query = step.params[PlanParams.TARGET]
            ?: step.params[PlanParams.GUIDE_TARGET]
        if (!query.isNullOrBlank()) {
            session.resolveTargetBounds(query)?.let { rect ->
                return TargetBounds(rect.left, rect.top, rect.right, rect.bottom)
            }
        }
        return null
    }

    private fun parseBounds(raw: String): TargetBounds? {
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return null
        return TargetBounds(parts[0], parts[1], parts[2], parts[3])
    }

    private fun TargetBounds.toRectF(): RectF =
        RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
