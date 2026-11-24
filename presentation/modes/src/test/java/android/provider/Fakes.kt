package android.provider

/** Minimal android.provider.Settings fake — test-only. */
object Settings {
    const val ACTION_MANAGE_OVERLAY_PERMISSION =
        "android.settings.action.MANAGE_OVERLAY_PERMISSION"

    @JvmStatic
    var canDrawOverlaysResult: Boolean = true

    @JvmStatic
    fun canDrawOverlays(context: android.content.Context): Boolean =
        canDrawOverlaysResult
}
