package com.loom.assistant.core.common

/**
 * "fix everything" (2026-11-05): directions for POINTER_MOVE — the user
 * directs the pointer itself ("move [the pointer] left", "move right /
 * up / down"). Pure Kotlin, no Android imports, so the matcher, the
 * layer, the renderer contract and the JVM tests all share it.
 *
 * Deliberate reading of "move sideways": it names no directed direction,
 * so it is NOT a direction — it reaches the move-class clarification
 * ("Left or right?") instead of guessing a way. "move sideways left"
 * does carry a direction and maps to LEFT.
 */
enum class PointerDirection(val label: String) {
    LEFT("left"),
    RIGHT("right"),
    UP("up"),
    DOWN("down"),
}

/**
 * Pure step math for pointer nudges: one step in the direction, clamped
 * so the indicator center stays inside the window with a margin. The
 * renderer owns density conversion (stepDp -> px) and calls this with
 * pixel values. Movement is display-only by construction: this moves a
 * drawn position, never a tap target.
 */
object PointerNudge {
    fun step(
        centerX: Float,
        centerY: Float,
        direction: PointerDirection,
        stepPx: Float,
        windowWidth: Float,
        windowHeight: Float,
        marginPx: Float,
    ): Pair<Float, Float> {
        val dx = when (direction) {
            PointerDirection.LEFT -> -stepPx
            PointerDirection.RIGHT -> stepPx
            else -> 0f
        }
        val dy = when (direction) {
            PointerDirection.UP -> -stepPx
            PointerDirection.DOWN -> stepPx
            else -> 0f
        }
        val xMax = (windowWidth - marginPx).coerceAtLeast(marginPx)
        val yMax = (windowHeight - marginPx).coerceAtLeast(marginPx)
        val x = (centerX + dx).coerceIn(marginPx, xMax)
        val y = (centerY + dy).coerceIn(marginPx, yMax)
        return x to y
    }
}
