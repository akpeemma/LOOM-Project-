package com.loom.assistant.execution.screenawareness

/**
 * MASTER Wave 4 (Wave 3 residual) — list-aware first-row resolution
 * for a bare "the first result" target.
 *
 * A bare first-result target ("tap the first result", "open the
 * first result") names no text to substring-match: the old dispatch
 * failed honestly ("target gone") because no element contains the
 * words "the first result". The first result is a POSITIONAL fact,
 * not a textual one — it is the topmost row inside the first list
 * container on screen. This resolver finds it:
 *  - the first LIST-role container with non-empty bounds, else the
 *    whole screen when no list container exists;
 *  - inside it, the topmost row: elements whose top is within a
 *    small tolerance of the container's topmost content element,
 *    excluding voice controls (the mic must never be the "first
 *    result" — video ~442s) and the list container itself;
 *  - the leftmost element of that row is the first result.
 *
 * Pure Kotlin over [ScreenModel]; no Android service coupling.
 */

/** True when [query] is a bare first-result selector (no identifying text). */
fun isBareFirstTarget(query: String): Boolean {
    val q = query.trim().lowercase()
        .removePrefix("the ").trim()
    return q == "first" || q == "first result" || q == "first one" ||
        q == "first video" || q == "first item" || q == "first song"
}

/**
 * The first result row on [screen], or null when no list content
 * is visible. Returns the leftmost element of the topmost row
 * inside the first list container.
 */
fun firstResultRow(screen: ScreenModel): ScreenElement? {
    val lists = screen.elements.filter {
        it.role == ElementRole.LIST && !it.bounds.isEmpty && it.enabled
    }
    val container = lists.minByOrNull { it.bounds.top }
    val candidates = screen.elements.filter { el ->
        el.enabled && !el.bounds.isEmpty &&
            el.role != ElementRole.LIST &&
            !TargetAliases.isVoiceControl(el) &&
            (container == null || contains(container.bounds, el.bounds))
    }
    if (candidates.isEmpty()) return null
    val top = candidates.minOf { it.bounds.top }
    // One row: tops within 24px of the topmost element. A video
    // result row's title/thumbnail/views share (nearly) one top.
    val row = candidates.filter { it.bounds.top <= top + 24 }
    // The first result is the row's leftmost element — but prefer a
    // text-bearing element (a title) over a bare icon at the same
    // left edge, so the pill names something readable.
    val leftmost = row.minByOrNull { it.bounds.left } ?: return null
    val textAtLeft = row.filter { it.bounds.left <= leftmost.bounds.left + 24 }
        .filter { !it.text.isNullOrBlank() }
        .minByOrNull { it.bounds.left }
    return textAtLeft ?: leftmost
}

private fun contains(outer: android.graphics.Rect, inner: android.graphics.Rect): Boolean =
    inner.left >= outer.left && inner.top >= outer.top &&
        inner.right <= outer.right && inner.bottom <= outer.bottom
