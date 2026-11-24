package com.loom.assistant.interaction.pointer

/**
 * Stage 2C (A3) — obstruction-aware guidance placement.
 *
 * Pure geometry, no Android imports: JVM-testable (T11 ObstructionLayoutTest).
 *
 * Rules (David's decision A):
 *  - The tooltip flips above/below the target (existing behavior, kept).
 *  - The beam endpoint, ring and tooltip are all clamped inside the safe
 *    window bounds.
 *  - The beam never parks over the target's own content: the target-end dot
 *    offsets to the target corner nearest the anchor (pushed slightly
 *    outward), so it never covers the target's label.
 *
 * Text measurement stays outside this object: the renderer measures the
 * label/instruction with its Paint and passes pixel widths in.
 */

/** Float point, density-independent pixels. */
data class GeomPoint(val x: Float, val y: Float)

/** Float rect, density-independent pixels. */
data class GeomRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(p: GeomPoint): Boolean =
        p.x >= left && p.x <= right && p.y >= top && p.y <= bottom

    fun containsInterior(p: GeomPoint): Boolean =
        p.x > left && p.x < right && p.y > top && p.y < bottom

    fun inset(margin: Float): GeomRect =
        GeomRect(left + margin, top + margin, right - margin, bottom - margin)
}

object ObstructionLayout {

    /** Fully resolved on-screen placement for one guidance step. */
    data class Placement(
        /** Beam start: the (clamped) mode anchor. */
        val beamFrom: GeomPoint,
        /** Beam end: target-corner dot, pushed outward — never over the label. */
        val beamTo: GeomPoint,
        /** Highlight ring rect (padded target, clamped to the safe window). */
        val ring: GeomRect,
        /** Tooltip card rect (flipped above/below, clamped to the window). */
        val tooltip: GeomRect,
        /** True when the tooltip sits above the target. */
        val tooltipAbove: Boolean,
    )

    /**
     * Compute [Placement] for a step.
     *
     * @param target the verified target bounds (dp).
     * @param anchor the current beam anchor (dp).
     * @param window the full window bounds (dp).
     * @param density display density (dp -> px scale used by the renderer).
     * @param labelWidthPx measured width of the "Step N of M" label (px).
     * @param instructionWidthPx measured width of the instruction text (px).
     */
    fun layout(
        target: GeomRect,
        anchor: GeomPoint,
        window: GeomRect,
        density: Float,
        labelWidthPx: Float,
        instructionWidthPx: Float,
    ): Placement {
        val safe = window.inset(4f * density)

        // Ring: padded target, clamped into the safe window.
        val ringPad = 8f * density
        val ring = GeomRect(
            (target.left - ringPad).coerceAtLeast(safe.left),
            (target.top - ringPad).coerceAtLeast(safe.top),
            (target.right + ringPad).coerceAtMost(safe.right),
            (target.bottom + ringPad).coerceAtMost(safe.bottom),
        )

        // Target-end dot: nearest corner to the anchor, pushed outward from
        // the target center so the beam endpoint never covers the label.
        val corner = nearestCorner(target, anchor)
        val cx = target.centerX
        val cy = target.centerY
        var dx = corner.x - cx
        var dy = corner.y - cy
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len > 0.001f) {
            dx /= len
            dy /= len
        } else {
            dx = 0f
            dy = -1f
        }
        val push = 6f * density
        val beamTo = GeomPoint(
            (corner.x + dx * push).coerceIn(safe.left, safe.right),
            (corner.y + dy * push).coerceIn(safe.top, safe.bottom),
        )
        val beamFrom = GeomPoint(
            anchor.x.coerceIn(safe.left, safe.right),
            anchor.y.coerceIn(safe.top, safe.bottom),
        )

        // Tooltip: flip above/below, then clamp into the window. The box
        // never exceeds the window width (long instructions are the
        // renderer's wrapping concern; the box must stay on screen).
        val margin = 12f * density
        val boxW = minOf(
            maxOf(labelWidthPx, instructionWidthPx) + 32f * density,
            (window.width - 2f * margin).coerceAtLeast(0f),
        )
        val boxH = 64f * density
        val minLeft = window.left + margin
        val maxLeft = (window.right - margin - boxW).coerceAtLeast(minLeft)
        val boxLeft = (target.centerX - boxW / 2f).coerceIn(minLeft, maxLeft)
        val topEdge = window.top + 8f * density
        val bottomEdge = (window.bottom - 8f * density - boxH).coerceAtLeast(topEdge)
        val aboveTop = target.top - boxH - 16f * density
        val tooltipAbove = aboveTop >= topEdge
        val boxTop = (if (tooltipAbove) aboveTop else target.bottom + 16f * density)
            .coerceIn(topEdge, bottomEdge)
        val tooltip = GeomRect(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)

        return Placement(
            beamFrom = beamFrom,
            beamTo = beamTo,
            ring = ring,
            tooltip = tooltip,
            tooltipAbove = tooltipAbove,
        )
    }

    private fun nearestCorner(t: GeomRect, p: GeomPoint): GeomPoint {
        val corners = arrayOf(
            GeomPoint(t.left, t.top),
            GeomPoint(t.right, t.top),
            GeomPoint(t.left, t.bottom),
            GeomPoint(t.right, t.bottom),
        )
        var best = corners[0]
        var bestD = Float.MAX_VALUE
        for (c in corners) {
            val dx = c.x - p.x
            val dy = c.y - p.y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }
}
