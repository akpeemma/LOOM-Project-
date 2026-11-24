package com.loom.assistant.interaction.pointer

import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import kotlin.math.sqrt

/**
 * Master implementation §6/§7 — pure assistant-placement geometry.
 *
 * Applies [ObstructionLayout]'s safe-window logic as the pattern, but to
 * the ASSISTANT rect (the floating character/orb view) rather than the
 * pointer's beam/ring/tooltip: the assistant must never cover an
 * interactive element, and "move out of the way" must land in the largest
 * content-free area the tree can see.
 *
 * Pure Kotlin, no Android imports beyond the android.graphics.Rect type
 * carried by ScreenElement.bounds (covered on the JVM by the existing
 * Rect test fake): JVM-testable. All coordinates are
 * pixels in screen space; callers convert dp with the display density.
 */
object AssistantPlacement {

    /** Roles that count as interactive content the assistant must not cover. */
    fun isInteractive(role: ElementRole): Boolean = when (role) {
        ElementRole.BUTTON,
        ElementRole.TOGGLE,
        ElementRole.INPUT,
        ElementRole.MENU_ITEM,
        ElementRole.ICON,
        ElementRole.SEARCH_FIELD -> true
        // Containers / chrome (LIST, NAVIGATION, DIALOG) and passive
        // content (TEXT, IMAGE, OTHER) do not block a move: covering part
        // of a list is not covering a control, and a transient dialog
        // would otherwise make every move fail.
        ElementRole.TEXT,
        ElementRole.IMAGE,
        ElementRole.OTHER,
        ElementRole.DIALOG,
        ElementRole.NAVIGATION,
        ElementRole.LIST -> false
    }

    /** First interactive element whose bounds [candidate] overlaps, or null. */
    fun coversInteractive(
        candidate: GeomRect,
        elements: List<ScreenElement>,
    ): ScreenElement? {
        for (el in elements) {
            if (!isInteractive(el.role)) continue
            if (el.bounds.isEmpty) continue
            if (overlaps(candidate, el.bounds.toGeom())) return el
        }
        return null
    }

    /** True when [candidate] overlaps any element at all (interactive or not). */
    fun coversAnyContent(
        candidate: GeomRect,
        elements: List<ScreenElement>,
    ): Boolean {
        for (el in elements) {
            if (el.bounds.isEmpty) continue
            if (overlaps(candidate, el.bounds.toGeom())) return true
        }
        return false
    }

    /** Clamp a rect of fixed size inside [safe], preserving its size. */
    fun clampRect(rect: GeomRect, safe: GeomRect): GeomRect {
        val w = rect.width
        val h = rect.height
        val maxLeft = (safe.right - w).coerceAtLeast(safe.left)
        val maxTop = (safe.bottom - h).coerceAtLeast(safe.top)
        val left = rect.left.coerceIn(safe.left, maxLeft)
        val top = rect.top.coerceIn(safe.top, maxTop)
        return GeomRect(left, top, left + w, top + h)
    }

    /**
     * Check-before-move: return [desired] (clamped into [safe]) when it
     * covers nothing interactive; otherwise the NEAREST non-covering spot,
     * searching outward in rings of [stepPx] (8 compass directions per
     * ring, two rings), then the four corners and four edge centres.
     * Null when no candidate spot is free — the caller must fail honestly
     * instead of moving onto a control.
     */
    fun findFreeSpot(
        desired: GeomRect,
        safe: GeomRect,
        elements: List<ScreenElement>,
        stepPx: Float,
    ): GeomRect? {
        val clamped = clampRect(desired, safe)
        if (coversInteractive(clamped, elements) == null) return clamped
        val tried = mutableSetOf<String>()
        fun consider(rect: GeomRect): GeomRect? {
            val c = clampRect(rect, safe)
            val key = "${c.left},${c.top}"
            if (!tried.add(key)) return null
            return if (coversInteractive(c, elements) == null) c else null
        }
        // Rings 1–2: 8 compass directions each, cardinals before diagonals
        // (same ring, shorter distance first).
        val dirs = listOf(
            1f to 0f, -1f to 0f, 0f to -1f, 0f to 1f,
            1f to -1f, -1f to -1f, 1f to 1f, -1f to 1f,
        )
        for (ring in 1..2) {
            val d = stepPx * ring
            for ((dx, dy) in dirs) {
                val hit = consider(
                    GeomRect(
                        clamped.left + dx * d, clamped.top + dy * d,
                        clamped.right + dx * d, clamped.bottom + dy * d,
                    ),
                )
                if (hit != null) return hit
            }
        }
        // Fallbacks: corners, then edge centres (nearest-first ordering is
        // by construction: the caller's desired spot was already refused,
        // so any free corner/edge is the best that remains).
        val w = clamped.width
        val h = clamped.height
        val corners = listOf(
            GeomRect(safe.left, safe.top, safe.left + w, safe.top + h),
            GeomRect(safe.right - w, safe.top, safe.right, safe.top + h),
            GeomRect(safe.left, safe.bottom - h, safe.left + w, safe.bottom),
            GeomRect(safe.right - w, safe.bottom - h, safe.right, safe.bottom),
        )
        for (c in corners) {
            val hit = consider(c)
            if (hit != null) return hit
        }
        val edges = listOf(
            GeomRect(
                safe.centerX - w / 2f, safe.top,
                safe.centerX + w / 2f, safe.top + h,
            ),
            GeomRect(
                safe.centerX - w / 2f, safe.bottom - h,
                safe.centerX + w / 2f, safe.bottom,
            ),
            GeomRect(
                safe.left, safe.centerY - h / 2f,
                safe.left + w, safe.centerY + h / 2f,
            ),
            GeomRect(
                safe.right - w, safe.centerY - h / 2f,
                safe.right, safe.centerY + h / 2f,
            ),
        )
        for (c in edges) {
            val hit = consider(c)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * OUT_OF_WAY placement: the largest content-free rectangle for an
     * assistant of [assistantW] x [assistantH] inside [safe].
     *
     * Grid-scans candidate top-left positions at [gridStepPx] intervals.
     * A candidate is valid only when it covers no interactive element;
     * among valid candidates the winner maximises clearance to the nearest
     * interactive element, preferring spots that overlap no content at all.
     * Null when every candidate covers an interactive element. When the
     * tree is empty the whole safe area is free and the assistant centres.
     */
    fun largestFreeRect(
        safe: GeomRect,
        assistantW: Float,
        assistantH: Float,
        elements: List<ScreenElement>,
        gridStepPx: Float,
    ): GeomRect? {
        val interactive = elements.filter { isInteractive(it.role) && !it.bounds.isEmpty }
        if (interactive.isEmpty()) {
            return GeomRect(
                safe.centerX - assistantW / 2f, safe.centerY - assistantH / 2f,
                safe.centerX + assistantW / 2f, safe.centerY + assistantH / 2f,
            ).let { clampRect(it, safe) }
        }
        val step = gridStepPx.coerceAtLeast(1f)
        var best: GeomRect? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var x = safe.left
        while (x + assistantW <= safe.right + 0.001f) {
            var y = safe.top
            while (y + assistantH <= safe.bottom + 0.001f) {
                val cand = GeomRect(x, y, x + assistantW, y + assistantH)
                if (coversInteractive(cand, elements) == null) {
                    val clearance = minClearance(cand, interactive)
                    // Overlapping passive content is allowed but penalised:
                    // a truly content-free spot always beats one sitting on
                    // a text block or image.
                    val score = clearance - if (coversAnyContent(cand, elements)) 10_000f else 0f
                    if (score > bestScore) {
                        bestScore = score
                        best = cand
                    }
                }
                y += step
            }
            x += step
        }
        return best
    }

    /** Smallest gap between [rect] and any interactive element (0 = touching). */
    fun minClearance(rect: GeomRect, interactive: List<ScreenElement>): Float {
        var min = Float.MAX_VALUE
        for (el in interactive) {
            val b = el.bounds.toGeom()
            val dx = maxOf(b.left - rect.right, rect.left - b.right, 0f)
            val dy = maxOf(b.top - rect.bottom, rect.top - b.bottom, 0f)
            val d = sqrt(dx * dx + dy * dy)
            if (d < min) min = d
        }
        return if (min == Float.MAX_VALUE) Float.MAX_VALUE else min
    }

    /** Strict overlap: touching edges does not count as covering. */
    fun overlaps(a: GeomRect, b: GeomRect): Boolean =
        a.left < b.right && a.right > b.left &&
            a.top < b.bottom && a.bottom > b.top

    private fun android.graphics.Rect.toGeom(): GeomRect =
        GeomRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
