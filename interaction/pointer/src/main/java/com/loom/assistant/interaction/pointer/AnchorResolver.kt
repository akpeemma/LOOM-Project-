package com.loom.assistant.interaction.pointer

/**
 * Stage 2C (F1) — per-mode beam anchors.
 *
 * Pure logic, no Android imports: JVM-testable (T7 AnchorResolverTest).
 *
 * The anchor is where the guidance beam starts: the character's center in
 * Character mode, the orb's center in Orb mode, the screen-edge midpoint in
 * Invisible mode.
 *
 * Anchor changes are EVENTS ([AnchorEvent.Moved]) that the renderer animates
 * over [TRANSITION_MS] with an ease — they never teleport. This replaces the
 * old `currentAnchor()` jump in the overlay host (Worker F removes that call
 * site and drives this resolver instead).
 *
 * [GuidanceMode] mirrors `presentation.modes.AssistantMode` without
 * depending on it (that module is Android-coupled). The host maps
 * AssistantMode -> GuidanceMode with a trivial `when`; that mapping lives
 * with the host (platform:services), not here.
 */

/** Guidance anchor modes (mirrors AssistantMode: CHARACTER | ORB | INVISIBLE). */
enum class GuidanceMode {
    CHARACTER,
    ORB,
    INVISIBLE,
}

/** Screen positions the host supplies. Nulls mean "not laid out yet". */
interface AnchorPositions {
    /** Center of the character view, or null when unavailable. */
    fun characterCenter(): GeomPoint?

    /** Center of the orb view, or null when unavailable. */
    fun orbCenter(): GeomPoint?

    /** Full window size in the same units as the centers. */
    fun windowSize(): GeomSize
}

data class GeomSize(val width: Float, val height: Float)

/** Anchor changes the renderer must animate (never teleport). */
sealed interface AnchorEvent {
    data class Moved(val from: GeomPoint, val to: GeomPoint, val durationMs: Long) : AnchorEvent
}

class AnchorResolver(private val positions: AnchorPositions) {

    /** Optional observer for [AnchorEvent.Moved] (the renderer subscribes). */
    var listener: ((AnchorEvent) -> Unit)? = null

    private var lastAnchor: GeomPoint? = null
    private var lastMode: GuidanceMode? = null

    /** Current anchor for [mode]. Falls back to the screen edge when a view position is missing. */
    fun anchorFor(mode: GuidanceMode): GeomPoint = when (mode) {
        GuidanceMode.CHARACTER -> positions.characterCenter() ?: edgeMidpoint()
        GuidanceMode.ORB -> positions.orbCenter() ?: edgeMidpoint()
        GuidanceMode.INVISIBLE -> edgeMidpoint()
    }

    /**
     * First anchor resolution: places the anchor, emits no event (there is
     * nothing to animate from yet).
     */
    fun resolveInitial(mode: GuidanceMode): GeomPoint {
        val anchor = anchorFor(mode)
        lastAnchor = anchor
        lastMode = mode
        return anchor
    }

    /**
     * Call after a verified mode transition. Returns (and emits to
     * [listener]) [AnchorEvent.Moved] when the anchor actually changed, or
     * null when the anchor is unchanged / this is the first resolution.
     */
    fun onModeChanged(newMode: GuidanceMode): AnchorEvent? {
        val to = anchorFor(newMode)
        val from = lastAnchor
        lastAnchor = to
        lastMode = newMode
        if (from == null || from == to) return null
        return AnchorEvent.Moved(from, to, TRANSITION_MS).also { listener?.invoke(it) }
    }

    /** Last resolved anchor, or null before the first resolution. */
    fun lastResolved(): GeomPoint? = lastAnchor

    /** Last mode passed to [resolveInitial]/[onModeChanged], or null. */
    fun lastMode(): GuidanceMode? = lastMode

    /**
     * Invisible-mode anchor: left screen-edge midpoint. Matches the overlay
     * host's long-standing `currentAnchor()` convention for invisible mode.
     */
    private fun edgeMidpoint(): GeomPoint =
        GeomPoint(EDGE_X_DP, positions.windowSize().height / 2f)

    companion object {
        /** Anchor-change animation duration (matches the choreography contract's ~300ms). */
        const val TRANSITION_MS = 300L

        /** Invisible-mode anchor x (dp), matching the host's old convention. */
        const val EDGE_X_DP = 4f
    }
}

/**
 * Pure anchor-transition math: ease-in-out-cubic interpolation of an anchor
 * from [from] to [to] over [durationMs]. The renderer evaluates this per
 * frame; tests assert the curve shape (T7).
 */
object AnchorTransition {

    fun position(
        from: GeomPoint,
        to: GeomPoint,
        elapsedMs: Long,
        durationMs: Long = AnchorResolver.TRANSITION_MS,
    ): GeomPoint {
        val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        // easeInOutCubic
        val e = if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - (-2f * t + 2f).let { it * it * it } / 2f
        }
        return GeomPoint(
            from.x + (to.x - from.x) * e,
            from.y + (to.y - from.y) * e,
        )
    }

    fun isFinished(elapsedMs: Long, durationMs: Long = AnchorResolver.TRANSITION_MS): Boolean =
        elapsedMs >= durationMs
}
