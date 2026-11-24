package android.graphics

/** Minimal android.graphics.RectF fake — test-only. */
class RectF(
    @JvmField var left: Float = 0f,
    @JvmField var top: Float = 0f,
    @JvmField var right: Float = 0f,
    @JvmField var bottom: Float = 0f,
) {
    override fun toString(): String = "RectF($left, $top, $right, $bottom)"
}
