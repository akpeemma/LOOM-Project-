package android.util

/** Minimal android.util.Log fake — test-only. */
object Log {
    @JvmStatic
    fun w(tag: String, msg: String): Int = 0

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable): Int = 0
}
