package com.loom.assistant.execution.screenawareness

/**
 * MASTER Wave 3 (F9) — semantic target resolution, in one place.
 *
 * Target resolution was substring matching, not semantic resolution:
 * the X button (plainly visible top-left, video frames 465/480/495s)
 * exposed no text containing "x", so "click the X" failed; and
 * "search" matched the voice-search microphone (contentDescription
 * "Voice search"), which LOOM then tapped instead of a video result
 * (video ~442s, CONFIRMED event).
 *
 * Rules, applied by every resolver that reads this object:
 *  - Semantic aliases: "x" also means close / clear / navigate-up /
 *    back / dismiss; "close" also means the X. An icon-only button
 *    is matched by its content-description / class, never by
 *    invented text.
 *  - "search" NEVER resolves to the voice-search mic: any element
 *    whose contentDescription mentions "voice" is excluded from
 *    search-field / search-button / search-target tiers. The mic is
 *    a voice control, never a search field.
 *  - Ambiguity is never resolved here: callers still refuse (or ask)
 *    on several matches — this object only decides whether ONE
 *    element matches the query.
 */
object TargetAliases {

    /** Words that mean "close this screen / clear this field". */
    private val CLOSE_WORDS = setOf(
        "x", "×", "close", "clear", "dismiss", "cancel",
        "navigate up", "back", "exit",
    )

    /**
     * Screen-confirmation + shutdown wave (2026-11-10, David's
     * common-sense rule): first-person words in his command map to
     * second-person words on screen, and vice versa. The app labels
     * the user's own things from its own perspective — "Your story",
     * "Your profile" — while David says "my story", "my profile".
     * The swap is token-wise and symmetric: "my story" → "your
     * story", "your videos" → "my videos". Only possessives swap
     * (my/your, mine/yours): "me"/"you" never do — "call me" is not
     * "call you".
     */
    fun perspectiveVariants(query: String): List<String> {
        val tokens = query.trim().lowercase().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val swapped = tokens.map { token ->
            when (token.trimEnd('.', ',', '!', '?')) {
                "my" -> "your"
                "your" -> "my"
                "mine" -> "yours"
                "yours" -> "mine"
                else -> token
            }
        }
        val original = tokens.joinToString(" ")
        val swappedText = swapped.joinToString(" ")
        return if (swappedText == original) listOf(original)
        else listOf(original, swappedText)
    }

    /** Similarity bar for a close (not exact) match: 1 − edit distance / longer length. */
    const val CLOSE_MATCH_THRESHOLD = 0.80

    /** Normalised for comparison: lowercase, punctuation stripped, spaces collapsed. */
    private fun normalise(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun stringSimilarity(a: String, b: String): Double {
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / max
    }

    /**
     * True when [el]'s label is CLOSE to [query] without matching
     * exactly — a small wording difference, a typo, "versus" vs
     * "vs". Either the whole strings are similar (≥ threshold) or
     * every query token has a label token similar to it (so extra
     * words in the label, "Call Mummy 2", do not hide the match).
     * One- and two-letter queries never close-match: at that length
     * almost anything is "close". A search-flavoured query never
     * close-matches a voice control, exactly as in exact matching.
     */
    fun matchesClose(el: ScreenElement, query: String): Boolean {
        if (!el.enabled || el.bounds.isEmpty) return false
        val variants = perspectiveVariants(query)
        if (variants.isEmpty()) return false
        if (variants.any { normalise(it).length < 3 }) return false
        val labels = listOfNotNull(el.text, el.contentDescription, el.hintText)
            .map { normalise(it) }.filter { it.isNotEmpty() }
        if (labels.isEmpty()) return false
        return variants.any { variant ->
            val q = normalise(variant)
            if (q.contains("search") && isVoiceControl(el)) return@any false
            labels.any { label ->
                if (stringSimilarity(q, label) >= CLOSE_MATCH_THRESHOLD) return@any true
                val qTokens = q.split(' ')
                val labelTokens = label.split(' ')
                qTokens.all { qt ->
                    labelTokens.any { lt -> stringSimilarity(qt, lt) >= CLOSE_MATCH_THRESHOLD }
                }
            }
        }
    }

    /**
     * Ranked resolution over [elements]: exact matches first
     * (semantic aliases + perspective variants); only when nothing
     * matches exactly, close matches. Callers keep their existing
     * ambiguity rule on the returned list — several matches still
     * refuse/ask instead of guessing — so one clear close match is
     * acted on and two plausible ones are never picked between.
     */
    fun resolveElements(elements: List<ScreenElement>, query: String): List<ScreenElement> {
        val exact = elements.filter { matchesTargetQuery(it, query) }
        if (exact.isNotEmpty()) return exact
        return elements.filter { matchesClose(it, query) }
    }

    /**
     * Expanded query words for [query]: the query itself plus its
     * semantic aliases. "x" expands to the close family; "close"
     * expands back to "x"; everything else expands to itself.
     */
    fun expandQuery(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        if (q in CLOSE_WORDS) return CLOSE_WORDS.toList()
        return listOf(q)
    }

    /** True when the element is a voice control (microphone), never a search target. */
    fun isVoiceControl(el: ScreenElement): Boolean =
        el.contentDescription?.contains("voice", ignoreCase = true) == true ||
            el.text?.contains("voice search", ignoreCase = true) == true

    /**
     * True when [el] matches [query] semantically: the query (or one
     * of its aliases) appears in the element's text, content-
     * description, hint or class name. A search-flavoured query
     * ("search", "search box", "search field", "search button")
     * never matches a voice control. Perspective variants are tried
     * too: "my story" matches "Your story" on screen (David's
     * common-sense rule, 2026-11-10 — the app labels his things
     * from its own perspective).
     */
    fun matchesTargetQuery(el: ScreenElement, query: String): Boolean {
        if (!el.enabled || el.bounds.isEmpty) return false
        val variants = perspectiveVariants(query)
        if (variants.isEmpty()) return false
        return variants.any { variant ->
            val q = variant.trim().lowercase()
            if (q.contains("search") && isVoiceControl(el)) return@any false
            val words = expandQuery(q)
            words.any { word ->
                (el.text?.lowercase()?.contains(word) == true) ||
                    (el.contentDescription?.lowercase()?.contains(word) == true) ||
                    (el.hintText?.lowercase()?.contains(word) == true) ||
                    // Class names are matched only for words of 2+ chars:
                    // every widget class contains "Text", so a one-letter
                    // word ("x") would match every element on screen and
                    // "click the X" would resolve to everything at once.
                    (word.length >= 2 && el.className.lowercase().contains(word))
            }
        }
    }

    /**
     * True when [el] mentions "search" (text / description / hint)
     * AND is not a voice control. The search-affordance tiers
     * (search field, search button, contact search) must use this,
     * never a bare "search" substring — the voice-search mic
     * mentions "search" in its description ("Voice search") and
     * must never be picked as the search box.
     */
    fun mentionsSearch(el: ScreenElement): Boolean {
        if (isVoiceControl(el)) return false
        return (el.text?.contains("search", ignoreCase = true) == true) ||
            (el.contentDescription?.contains("search", ignoreCase = true) == true) ||
            (el.hintText?.contains("search", ignoreCase = true) == true)
    }
}
