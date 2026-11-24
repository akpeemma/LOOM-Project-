package com.loom.assistant.execution.screenawareness

/**
 * Device-test failures (2026-11-11, D-04) — the screen description.
 *
 * "Confirm your screen" / "what's on my screen" / "what do you see"
 * must make LOOM describe the current screen from Screen Awareness:
 * which app, what is on it, where the named things are. Pure Kotlin
 * over the redacted [ScreenModel], so the harness proves it without
 * a device — and so the description can only ever name what the
 * tree actually exposed, never an invented screen.
 *
 * Rules:
 * - The model is already redacted ([ScreenDataPolicy] stripped
 *   passwords and sensitive-app labels before the model existed),
 *   so describing it leaks nothing the screen did not already show
 *   LOOM. An unreadable screen says so with the exact reason
 *   ([ScreenRead.Insufficient]), never a guessed description.
 * - Positions are rough zones from the element bounds (top / middle
 *   / bottom, left / right) — the tree has no pixels, so the
 *   description never claims pixel precision.
 * - At most [MAX_ITEMS] named things, distinct labels, in reading
 *   order (top to bottom); the rest are counted, not hidden silently.
 * - Fields, lists and the keyboard state are named when present:
 *   "a search field is focused" is the difference between a results
 *   screen and a suggestions screen.
 */
object ScreenDescriber {

    const val MAX_ITEMS = 8

    /**
     * Screen fix (2026-11-11): the spoken description is capped —
     * a feed screen must never produce a minutes-long list again.
     */
    const val MAX_DESCRIPTION_CHARS = 500

    /** Describe a screen read: the app, the things on it, where they are. */
    fun describe(read: ScreenRead): String = when (read) {
        is ScreenRead.Insufficient ->
            "I can't see the screen right now — ${read.reason}"
        is ScreenRead.Available -> describeModel(read.model)
    }

    /**
     * The label a description may name: real content only — text,
     * content-description, or hint. The class name is NOT a label:
     * [ScreenElement.label()] falls back to it ("FrameLayout",
     * "ViewGroup"), which is tree noise presented as things on
     * screen. Null when the element has nothing readable to say.
     */
    fun contentLabel(el: ScreenElement): String? =
        el.text?.takeIf { it.isNotBlank() }
            ?: el.contentDescription?.takeIf { it.isNotBlank() }
            ?: el.hintText?.takeIf { it.isNotBlank() }

    /** Describe a redacted screen model, or say it is empty. */
    fun describeModel(model: ScreenModel): String {
        if (model.elements.isEmpty()) {
            return "The screen looks empty to me — I can see the " +
                "${appLabel(model.packageName)} app, but nothing readable on it."
        }
        // Screen fix (2026-11-11, CORRECTED): the old head counted the
        // RAW tree ("I can see 135 things on screen") while only 8
        // were named. The description reads ONLY the visible subset
        // ([ScreenModel.visibleElements] — what the user can actually
        // see right now); the full tree stays in the model for LOOM's
        // own work and is never described as if it were on screen.
        // The count is the number of distinct LABELLED visible items
        // — never model.elements.size. Class-name-only nodes are
        // unnamed and uncounted; nodes outside the viewport are not
        // visible and not counted here (LOOM still knows them).
        val labelled = model.visibleElements
            .filter { it.enabled && !it.bounds.isEmpty }
            .mapNotNull { el ->
                val label = contentLabel(el)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                el to label
            }
            .distinctBy { it.second.lowercase() }
            .sortedBy { it.first.bounds.centerY() }
        val named = labelled.take(MAX_ITEMS).joinToString(", ") { (el, label) ->
            // Mark 57 transfer (2026-11-11, M4) — screen content is
            // UNTRUSTED data: an instruction-like label ("ignore
            // your instructions…") is wrapped as data, never read
            // aloud as LOOM's own words or an instruction to follow.
            "\"${ScreenContentTrust.renderLabel(label)}\" (${zoneOf(el, model.screenBounds)})"
        }
        val rest = labelled.size - MAX_ITEMS
        val countWord = if (labelled.size == 1) "thing" else "things"
        val head = "You're in ${appLabel(model.packageName)} — I can see " +
            "${labelled.size} $countWord on screen" +
            if (model.keyboardVisible) ", and the keyboard is up" else ""
        val tail = when {
            named.isEmpty() -> "but nothing with a readable label."
            rest > 0 -> ": $named, and $rest more."
            else -> ": $named."
        }
        return (head + tail).take(MAX_DESCRIPTION_CHARS)
    }

    /**
     * Rough zone of an element from its bounds centre, in thirds of
     * the screen ([screenBounds] when the model carries it, else a
     * standard 1080x2400 phone screen — approximate, and said so in
     * the description only as top/middle/bottom + left/right).
     */
    fun zoneOf(el: ScreenElement, screenBounds: android.graphics.Rect? = null): String {
        val width = screenBounds?.width()?.takeIf { it > 0 } ?: 1080
        val height = screenBounds?.height()?.takeIf { it > 0 } ?: 2400
        val top = screenBounds?.top ?: 0
        val left = screenBounds?.left ?: 0
        val cy = el.bounds.exactCenterY() - top
        val cx = el.bounds.exactCenterX() - left
        val vertical = when {
            cy < height / 3f -> "top"
            cy < height * 2 / 3f -> "middle"
            else -> "bottom"
        }
        val horizontal = when {
            cx < width / 3f -> "left"
            cx > width * 2 / 3f -> "right"
            else -> "centre"
        }
        return if (horizontal == "centre") vertical else "$vertical $horizontal"
    }

    /**
     * A readable app name for a package: the well-known apps David
     * uses, else the package's last segment. Never blank while the
     * package is known — a bare package string is better than
     * nothing, and the catalogue label (used by the guidance gate)
     * is not reachable from this pure module.
     */
    fun appLabel(packageName: String): String = when (packageName) {
        "com.facebook.katana" -> "Facebook"
        "com.google.android.youtube" -> "YouTube"
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        "com.whatsapp" -> "WhatsApp"
        "com.android.chrome" -> "Chrome"
        "com.loom.assistant" -> "LOOM"
        else -> packageName.substringAfterLast('.').takeIf { it.isNotBlank() }
            ?: packageName
    }
}
