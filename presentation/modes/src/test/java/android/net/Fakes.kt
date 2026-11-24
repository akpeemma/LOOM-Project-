package android.net

/** Minimal android.net.Uri fake — test-only. */
class Uri private constructor() {
    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri()
    }
}
