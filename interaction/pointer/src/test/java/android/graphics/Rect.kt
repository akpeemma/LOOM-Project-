package android.graphics

/**
 * JVM test fake for android.graphics.Rect.
 * Mirrors the members of the real class used by the pointer module's
 * JVM-compiled sources (property-style `isEmpty` works against both this
 * fake and the real Java method's synthetic property).
 */
data class Rect(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val isEmpty: Boolean get() = left >= right || top >= bottom

    /** Real android.graphics.Rect members used by JVM-compiled sources. */
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2
    fun exactCenterX(): Float = (left + right) / 2f
    fun exactCenterY(): Float = (top + bottom) / 2f
}
