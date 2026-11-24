package com.loom.assistant.interaction.pointer

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Stage 2B (P3) — owned, persisted, clamped overlay positions.
 *
 * SharedPreferences-backed store, per overlay id (character, orb, panel,
 * pointer-anchor): persist x/y on drag end / position change; on window add,
 * restore then clamp to the current WindowMetrics; windows stay clear of
 * cutouts and system bars as far as the existing APIs allow; an orientation
 * change re-clamps rather than resetting to hard-coded coords. Character and
 * orb keep independent positions.
 *
 * Purely additive — no existing behavior is altered except replacing
 * hard-coded initial coords with stored-then-clamped ones.
 */
object FloatingPositionStore {

    enum class OverlayId {
        CHARACTER,
        ORB,
        PANEL,
        POINTER_ANCHOR,
        /** Stage 2C (§5.3): the separate touchable stop window. */
        STOP_WINDOW,
    }

    data class Position(val x: Int, val y: Int)

    private const val PREFS = "loom_overlay_positions"

    fun save(context: Context, id: OverlayId, x: Int, y: Int) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(key(id, "x"), x)
                .putInt(key(id, "y"), y)
                .apply()
        } catch (_: Exception) {
            // Positions are best-effort; a failed persist must never break
            // the overlay.
        }
    }

    fun load(context: Context, id: OverlayId): Position? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(key(id, "x"))) null
        else Position(prefs.getInt(key(id, "x"), 0), prefs.getInt(key(id, "y"), 0))
    } catch (_: Exception) {
        null
    }

    private fun key(id: OverlayId, axis: String): String =
        "pos_${id.name.lowercase()}_$axis"

    /**
     * Restore the saved position for [id], clamped to the current window
     * metrics and clear of cutouts/system bars. Falls back to
     * [fallbackX]/[fallbackY] when nothing was saved yet. Called on every
     * window add, so rotation re-clamps instead of resetting.
     */
    fun restore(
        context: Context,
        wm: WindowManager,
        id: OverlayId,
        viewWidth: Int,
        viewHeight: Int,
        fallbackX: Int,
        fallbackY: Int,
    ): Position {
        val saved = load(context, id) ?: Position(fallbackX, fallbackY)
        return clamp(saved, safeArea(wm), viewWidth, viewHeight)
    }

    /**
     * Clamp an arbitrary position (e.g. anchor-derived, not stored) to the
     * current window metrics. The live anchor wins; this only supplies the
     * clamp — orientation changes re-clamp rather than resetting coords.
     */
    fun clampToCurrent(
        wm: WindowManager,
        p: Position,
        viewWidth: Int,
        viewHeight: Int,
    ): Position = clamp(p, safeArea(wm), viewWidth, viewHeight)

    /**
     * Pure clamp math — no Android window calls, unit-testable.
     */
    fun clamp(p: Position, safe: Rect, viewW: Int, viewH: Int): Position {
        val maxX = (safe.right - viewW).coerceAtLeast(safe.left)
        val maxY = (safe.bottom - viewH).coerceAtLeast(safe.top)
        return Position(
            p.x.coerceIn(safe.left, maxX),
            p.y.coerceIn(safe.top, maxY),
        )
    }

    /**
     * The on-screen area a floating window may occupy: full window bounds
     * minus system bars and the display cutout. Pre-R falls back to the raw
     * display size (minSdk 26).
     *
     * Public since the master implementation (§7): AssistantMover's
     * backend needs the same safe area the store clamps against, so the
     * mover and the store can never disagree about where the assistant
     * may sit.
     */
    fun safeArea(wm: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(
                metrics.bounds.left + insets.left,
                metrics.bounds.top + insets.top,
                metrics.bounds.right - insets.right,
                metrics.bounds.bottom - insets.bottom,
            )
        }
        @Suppress("DEPRECATION")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }
}
