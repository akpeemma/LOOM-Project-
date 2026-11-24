package com.loom.assistant.interaction.pointer

/**
 * MASTER Wave 4 (F11 strengthening) — the pointer never parks on an
 * unverified element, and an approval card never occludes the target
 * being acted on. Pure Kotlin over [GeomRect]/[GeomPoint] (no
 * android.* imports), so the JVM harness can prove the placement
 * rules without a phone.
 *
 * Video evidence: the orb sat parked on a spam number (600–620s) and
 * on the middle of a Facebook post while nothing was verified there;
 * the "Scroll for you?" card covered "My Mummy" (540s), the very
 * contact David wanted called.
 */

/** Where the pointer rests when there is no verified target. */
enum class ParkingDecision {
    /** Park on the verified target — the only parking that names an element. */
    PARK_VERIFIED,

    /** No verified target: park at the neutral home position (top-centre, off content). */
    PARK_HOME,

    /** No verified target and no home position available: hide the pointer. */
    HIDE,
}

object PointerParkingPolicy {
    /**
     * Decides where the pointer rests. [verifiedTarget] is non-null
     * only when the target was resolved against a fresh screen and
     * its label/bounds are the ones the pill names — a merely
     * suspected element (a call-log row, a post, a banner) is NOT
     * verified and never gets parked on.
     */
    fun decide(verifiedTarget: GeomRect?): ParkingDecision =
        if (verifiedTarget != null) ParkingDecision.PARK_VERIFIED else ParkingDecision.PARK_HOME

    /**
     * The neutral home position: a small rect centred horizontally
     * near the top of the screen (10% down), clear of the content
     * centre where posts, contacts and results live. The pointer
     * waits here — visible, but claiming nothing.
     */
    fun homeRect(screenWidthPx: Float, screenHeightPx: Float): GeomRect {
        val half = 28f
        val cx = screenWidthPx / 2f
        val cy = screenHeightPx * 0.10f
        return GeomRect(cx - half, cy - half, cx + half, cy + half)
    }
}

/** Which edge an approval card docks to so it never covers the target. */
enum class CardEdge { TOP, BOTTOM }

data class CardPlacement(
    val edge: CardEdge,
    /** Distance in px from the chosen edge. */
    val marginPx: Int,
    /** True when the target must be re-verified after the card closes (it was near the card). */
    val reverifyTargetAfterClose: Boolean,
) {
    // MASTER Wave 4 fix (test-found): `decide` lives in the
    // companion object — a top-level `object CardPlacement` next to
    // the data class of the same name is a redeclaration and does
    // not compile. Callers still write CardPlacement.decide(...).
    companion object {
    /**
     * Places an approval card away from [target] (the element being
     * acted on, in screen px; null when no target is known — then the
     * card docks at the bottom, the side furthest from the usual
     * top-of-screen controls).
     *
     * Rule: the card goes on the opposite half from the target's
     * centre; when the target spans the centre, the side with more
     * free space wins. The card never overlaps the target rect: if
     * the chosen edge's band would still touch the target (a very
     * tall target), [reverifyTargetAfterClose] is set so the caller
     * re-resolves the target after the card closes instead of acting
     * on a possibly-covered element.
     */
    fun decide(
        target: GeomRect?,
        screenWidthPx: Float,
        screenHeightPx: Float,
        cardHeightPx: Float,
        marginPx: Int = 24,
    ): CardPlacement {
        if (target == null) {
            return CardPlacement(CardEdge.BOTTOM, marginPx, reverifyTargetAfterClose = false)
        }
        val centerY = (target.top + target.bottom) / 2f
        val midY = screenHeightPx / 2f
        val edge = if (centerY < midY) CardEdge.BOTTOM else CardEdge.TOP
        // Would the card band overlap the target? Top band is
        // [0, margin+cardHeight]; bottom band is [H-margin-cardHeight, H].
        val overlaps = when (edge) {
            CardEdge.TOP -> target.top < marginPx + cardHeightPx
            CardEdge.BOTTOM -> target.bottom > screenHeightPx - marginPx - cardHeightPx
        }
        return CardPlacement(edge, marginPx, reverifyTargetAfterClose = overlaps)
    }
    }
}

/**
 * Detached-draw guard (F11): a draw into a detached view is a silent
 * `invalidate()` no-op — the executor then logs "pointer-ok" while
 * nothing rendered. [check] returns the recorded failure when the
 * view is not attached (or has zero size), else null. The renderer
 * stores the returned string as its `lastDrawFailure` so the guides
 * read it after every draw call.
 */
object DetachedDrawGuard {
    fun check(attached: Boolean, widthPx: Int, heightPx: Int, method: String): String? {
        if (!attached) {
            return "the pointer view is not attached to a window — the $method draw was dropped"
        }
        if (widthPx <= 0 || heightPx <= 0) {
            return "the pointer window has zero size — the $method draw was dropped"
        }
        return null
    }
}
