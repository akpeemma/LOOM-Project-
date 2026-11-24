package com.loom.assistant.execution.screenawareness

/**
 * NAV-SCREEN fix (2026-11-11, David's order ~19:29 WAT) — N6, live
 * re-resolution at tap time in the plan path.
 *
 * The audit's Cause E: the plan tap path taps frozen snapshot
 * coordinates after a potentially long token wait (up to 90 s),
 * while the search path already re-identifies live
 * (SearchExecutor.tapFresh). This is the plan path's equivalent,
 * as a pure function so the dispatcher (which owns the fresh
 * snapshot via its ScreenObserver) and the executor can share it
 * without duplicating the guards.
 *
 * Identity is (class, text, content-description, role) — the same
 * identity tapFresh uses. Centre moved > 24px or size changed > 2×
 * refuses (moved, never a stale tap); no identity match refuses
 * (gone); several identity matches refuse (ambiguous, never a
 * guess). An icon-only element (no text/description) matches by
 * identity only when exactly one element on the fresh screen shares
 * its class + role — otherwise the bounds-proximity fallback applies:
 * the single element of the same class whose centre is within 24px
 * of the original centre.
 */
/**
 * Mark 57 transfer (2026-11-11, M2) — tap-origin labels.
 *
 * A tap point must declare where it came from: an ELEMENT of the
 * accessibility tree (the only origin LOOM taps), a VISION guess
 * from screenshot pixels, a USER-supplied point, or a RELATIVE
 * offset. VISION is REFUSED, unconditionally: a coordinate guessed
 * from a downscaled screenshot is how you tap the wrong thing and
 * report success (Mark 57 moved away from exactly that path with
 * screenview/screenact). There is no production path that
 * constructs a VISION tap — [LiveTapResolutionChecker.refuseVisionTap]
 * is the single place a pixel coordinate would arrive, and it
 * refuses it. USER/RELATIVE exist so a caller that ever needs
 * them must name the origin explicitly; neither is used today.
 */
enum class TapOrigin {
    ELEMENT,
    VISION,
    USER,
    RELATIVE,
}

sealed class LiveTapResolution {
    /** The fresh element to tap (live bounds). */
    data class Resolved(
        val element: ScreenElement,
        /** Where the tap point came from — always ELEMENT here. */
        val origin: TapOrigin = TapOrigin.ELEMENT,
    ) : LiveTapResolution()

    /** The target moved beyond tolerance — re-resolve, never tap stale coordinates. */
    data class Moved(val reason: String) : LiveTapResolution()

    /** The target is gone from the fresh screen. */
    data class Gone(val reason: String) : LiveTapResolution()

    /** Several elements match — refuse, never guess. */
    data class Ambiguous(val reason: String) : LiveTapResolution()

    /** A VISION-origin tap was refused — pixels never resolve a tap. */
    data class VisionRefused(val reason: String) : LiveTapResolution()
}

object LiveTapResolutionChecker {
    /** Re-identification tolerance — mirrors SearchExecutor.TOCTOU_TOLERANCE_PX / ActionQueue. */
    const val TOCTOU_TOLERANCE_PX = 24

    /**
     * Mark 57 transfer (M2) — the VISION refusal. A tap point that
     * comes from screenshot pixels (a model guessing coordinates
     * from a picture) is refused, unconditionally and with no
     * parameter to turn it off. Taps land on ELEMENTS resolved
     * from the tree ([reResolveTapTarget]); a vision coordinate
     * must first become an element (resolve it from the tree) or
     * the tap does not happen.
     */
    fun refuseVisionTap(x: Int, y: Int): LiveTapResolution =
        LiveTapResolution.VisionRefused(
            "refusing a tap at ($x, $y) from screenshot pixels " +
                "(VISION origin) — taps land on tree elements only; " +
                "resolve the target from the accessibility tree first",
        )

    fun reResolveTapTarget(
        freshScreen: ScreenModel,
        original: ScreenElement,
        query: String,
    ): LiveTapResolution {
        val identityMatches = freshScreen.elements.filter {
            it.enabled && !it.bounds.isEmpty &&
                it.className == original.className &&
                it.text == original.text &&
                it.contentDescription == original.contentDescription &&
                it.role == original.role
        }
        if (identityMatches.size > 1) {
            // Icon-only siblings share class/text/description/role
            // (six identical ImageViews in a bottom nav) — identity
            // alone cannot tell them apart. Narrow by centre
            // proximity: exactly one within tolerance is the target
            // (it has not moved); several within tolerance is a
            // genuine ambiguity. None within tolerance means the
            // target moved away — except for an icon-only target
            // (no text/description of its own), whose description
            // may have changed while it stayed put: then the
            // centre-proximity fallback over all same-class elements
            // still identifies it by where it is. A labelled target
            // that changed its text is never rescued this way: its
            // identity changed, so it is gone.
            val near = identityMatches.filter { centreWithin(it, original, TOCTOU_TOLERANCE_PX) }
            if (near.size == 1) {
                return if (sameTarget(near[0], original)) {
                    LiveTapResolution.Resolved(near[0], TapOrigin.ELEMENT)
                } else {
                    LiveTapResolution.Moved(
                        "target moved: \"$query\" is no longer where it was",
                    )
                }
            }
            if (near.size > 1) {
                return LiveTapResolution.Ambiguous(
                    "the target \"$query\" now matches ${near.size} elements — be more specific",
                )
            }
            val labelled = !original.text.isNullOrBlank() ||
                !original.contentDescription.isNullOrBlank()
            if (!labelled) {
                return proximityFallback(freshScreen, original, query)
            }
            return LiveTapResolution.Moved(
                "target moved: \"$query\" is no longer where it was",
            )
        }
        if (identityMatches.size == 1) {
            val fresh = identityMatches[0]
            return if (sameTarget(fresh, original)) {
                LiveTapResolution.Resolved(fresh, TapOrigin.ELEMENT)
            } else {
                LiveTapResolution.Moved(
                    "target moved: \"$query\" is no longer where it was",
                )
            }
        }
        // No identity match: icon-only fallback — one element of the
        // same class whose centre is within tolerance of the original
        // centre. (A labelled element that changed its text is NOT
        // rescued this way: its identity changed, so it is gone.)
        val labelled = !original.text.isNullOrBlank() ||
            !original.contentDescription.isNullOrBlank()
        if (!labelled) {
            return proximityFallback(freshScreen, original, query)
        }
        return LiveTapResolution.Gone(
            "target gone: \"$query\" no longer matches anything on screen",
        )
    }

    /**
     * Icon-only proximity fallback: the single element of the same
     * class and role whose centre is within tolerance of the
     * original centre. Several → Ambiguous; none → Moved (among
     * identical siblings, removal and movement are
     * indistinguishable — either way the replan path re-resolves
     * from a fresh screen).
     */
    private fun proximityFallback(
        freshScreen: ScreenModel,
        original: ScreenElement,
        query: String,
    ): LiveTapResolution {
        val near = freshScreen.elements.filter {
            it.enabled && !it.bounds.isEmpty &&
                it.className == original.className && it.role == original.role &&
                centreWithin(it, original, TOCTOU_TOLERANCE_PX)
        }
        if (near.size == 1) return LiveTapResolution.Resolved(near[0], TapOrigin.ELEMENT)
        if (near.size > 1) {
            return LiveTapResolution.Ambiguous(
                "the target \"$query\" now matches ${near.size} elements — be more specific",
            )
        }
        return LiveTapResolution.Moved(
            "target moved: \"$query\" is no longer where it was",
        )
    }

    /** TOCTOU sameness — mirrors SearchExecutor.sameTarget (24px centre, 2× size). */
    fun sameTarget(a: ScreenElement, b: ScreenElement): Boolean {
        if (!centreWithin(a, b, TOCTOU_TOLERANCE_PX)) return false
        val aw = a.bounds.width().toDouble()
        val ah = a.bounds.height().toDouble()
        val bw = b.bounds.width().toDouble().coerceAtLeast(1.0)
        val bh = b.bounds.height().toDouble().coerceAtLeast(1.0)
        if (aw < 1 || ah < 1) return false
        return aw / bw in 0.5..2.0 && ah / bh in 0.5..2.0
    }

    private fun centreWithin(a: ScreenElement, b: ScreenElement, tolerancePx: Int): Boolean {
        val dcx = kotlin.math.abs(a.bounds.exactCenterX() - b.bounds.exactCenterX())
        val dcy = kotlin.math.abs(a.bounds.exactCenterY() - b.bounds.exactCenterY())
        return dcx <= tolerancePx && dcy <= tolerancePx
    }
}
