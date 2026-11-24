package android.graphics

/**
 * JVM test fake for android.graphics.RectF.
 * Mirrors the members of the real class used by the pointer module's
 * JVM-compiled sources and tests.
 */
class RectF(
    var left: Float = 0f,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f,
) {
    constructor(r: Rect) : this(
        r.left.toFloat(),
        r.top.toFloat(),
        r.right.toFloat(),
        r.bottom.toFloat(),
    )

    constructor(other: RectF) : this(other.left, other.top, other.right, other.bottom)

    fun centerX(): Float = (left + right) / 2f

    fun centerY(): Float = (top + bottom) / 2f

    override fun equals(other: Any?): Boolean =
        other is RectF &&
            left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom

    override fun hashCode(): Int {
        var result = left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        return result
    }

    override fun toString(): String = "RectF($left, $top, $right, $bottom)"
}
