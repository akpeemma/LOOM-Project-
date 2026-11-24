package com.loom.assistant.execution.screenawareness

/**
 * Master implementation §3 — "What LOOM Sees" text rendering.
 *
 * Pure functions over the (already redacted) [ScreenModel]: they produce
 * exactly the lines the in-app screen shows, so the rendering is
 * unit-testable on the JVM without the Android view layer. Nothing here
 * touches pixels, MediaProjection, or PixelCopy — the input is the
 * accessibility tree only, and redaction has already happened upstream
 * in [ScreenDataPolicy.redact], so no readable text can leak through
 * this renderer.
 */
object ScreenModelText {

    /** One line per element: label — ROLE — [bounds] — flags. */
    fun describeElement(el: ScreenElement): String {
        val bounds = "[${el.bounds.left},${el.bounds.top}–${el.bounds.right},${el.bounds.bottom}]"
        val flags = buildList {
            if (el.isPassword) add("password — text hidden")
            if (!el.enabled) add("disabled")
            if (el.scrollable) add("scrollable")
        }
        val flagSuffix = if (flags.isEmpty()) "" else " — " + flags.joinToString(", ")
        return "${el.label()} — ${el.role} — $bounds$flagSuffix"
    }

    /** Full rendering: header line, then one line per element. */
    fun render(model: ScreenModel): List<String> {
        val lines = ArrayList<String>(model.elements.size + 1)
        lines.add("App: ${model.packageName} · ${model.elements.size} elements")
        for (el in model.elements) lines.add(describeElement(el))
        return lines
    }

    /** Header shown above the element list when the app is sensitive. */
    fun sensitiveNotice(packageName: String): String? =
        if (ScreenDataPolicy.isSensitivePackage(packageName)) {
            "Sensitive app — labels are hidden. Bounds and roles only, " +
                "so guidance can still point without reading your data."
        } else null
}
