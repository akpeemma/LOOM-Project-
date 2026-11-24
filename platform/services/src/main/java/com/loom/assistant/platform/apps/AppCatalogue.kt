package com.loom.assistant.platform.apps

/**
 * Master implementation §1 — installed-app awareness.
 *
 * The catalogue of launchable apps, with honest name resolution. This file
 * is pure Kotlin (no android.* imports) so the resolution rules are
 * JVM-testable without fakes; the Android-backed sources live in
 * [AndroidAppSources.kt] ([PackageManagerAppSource],
 * [SystemForegroundSource]) and the process-wide holder in
 * [AppCatalogueHolder].
 *
 * Resolution rules (in order — never guess a similar name):
 *  0. A literal package name (`com.whatsapp`) resolves directly when it
 *     has a launcher entry.
 *  1. Exact label match, case-insensitive, punctuation/whitespace
 *     normalised. "YouTube Music" resolves to YouTube Music, never to
 *     YouTube.
 *  2. Whole-word phrase match: every query word must appear as a whole
 *     word, in order, in the label's words. "Music" finds YouTube Music
 *     when it is the only *-music app; "Tube" or "You" match nothing —
 *     substring matching is what used to open the wrong app
 *     (LoomAppLauncher's old exact→`contains` fallback).
 *  3. More than one candidate at any stage → [AppResolution.Ambiguous]
 *     with the full candidate list. The caller must ask the user which
 *     one — it must never pick the first.
 *  4. No candidate → [AppResolution.NotFound].
 *
 * No fuzzy / similarity matching lives here on purpose: STT-error
 * correction belongs to the speech worker (§2), which normalises the
 * spoken form *before* calling [AppCatalogue.resolveByName]. The
 * catalogue never turns "You Tube" into YouTube by itself.
 * (MASTER F2d supersedes that line for ONE bounded case: after exact,
 * phrase and alias stages all fail, a single best similarity match
 * at >= 0.80 on the whole normalised label may resolve a garbled
 * name like "youtub". Ambiguous similarity results never pick.)
 *
 * Package visibility (Android 11+): discovery queries launcher intents
 * via [AppSource]. What the query can see depends on the manifest:
 *  - `<queries>` with an ACTION_MAIN / CATEGORY_LAUNCHER intent makes
 *    every launchable app visible — this is the supported route and is
 *    what the app manifest declares.
 *  - `QUERY_ALL_PACKAGES` is also declared (LOOM is sideloaded, so it is
 *    granted), but it is a Play-restricted permission: a future
 *    Play-distributed build must rely on the `<queries>` entry alone,
 *    which is sufficient for launcher discovery.
 *  Without either, `queryIntentActivities` silently returns a filtered
 *  subset and resolution degrades to [AppResolution.NotFound] for the
 *  hidden apps — never to a wrong app.
 *
 * Sensitive apps (banking / financial / authenticator) are NOT filtered
 * out of the catalogue: resolution may find them, but execution never
 * runs inside them — the guidance-only rule is enforced by the existing
 * pre-token gates in LoomAppLauncher / AuthGate, which stay untouched.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val className: String?,
    /**
     * Launcher icon metadata: the icon resource id from
     * `ApplicationInfo.icon` (0 when the app declares none). Metadata
     * only — the drawable itself is loaded on demand by the UI via
     * `PackageManager`, never cached here.
     */
    val iconResId: Int = 0,
)

sealed class AppResolution {
    data class Found(val app: AppInfo) : AppResolution()
    object NotFound : AppResolution()
    data class Ambiguous(val candidates: List<AppInfo>) : AppResolution()
}

sealed class ForegroundResult {
    /** Foreground package; [app] is null when it is not a launchable app (e.g. System UI). */
    data class Known(val packageName: String, val app: AppInfo?) : ForegroundResult()
    /** Honest reason — names what is missing (accessibility off / Usage access not granted). */
    data class Unavailable(val reason: String) : ForegroundResult()
}

/** Supplies the launchable-app list. Production: [PackageManagerAppSource]. */
interface AppSource {
    fun queryLaunchableApps(): List<AppInfo>
}

sealed class ForegroundProbe {
    data class Found(val packageName: String) : ForegroundProbe()
    data class Unavailable(val reason: String) : ForegroundProbe()
}

/**
 * Supplies the foreground package name, or an honest [ForegroundProbe.Unavailable].
 * Production: [SystemForegroundSource] (accessibility window state first,
 * UsageStats second). Null source means foreground awareness is not wired.
 */
interface ForegroundSource {
    fun probe(): ForegroundProbe
}

class AppCatalogue(
    private val source: AppSource,
    private val foregroundSource: ForegroundSource? = null,
) {
    companion object {
        /**
         * Device-evidence fix (2026-11-05, item 9): spoken names that
         * differ from the real launcher label. YouTube Music's launcher
         * label is "YT Music", so "YouTube Music" matched nothing and
         * the search/launch failed with NotFound. The alias is a
         * FALLBACK: stages 1–2 run on the spoken name first (so a
         * catalogue whose label really is "YouTube Music" still
         * resolves exactly), and only on NotFound is the query
         * retried as the alias — "YouTube Music" then resolves to the
         * "YT Music" entry, while "YouTube" still resolves to YouTube,
         * never to the music app.
         */
        private val LABEL_ALIASES: Map<String, String> = mapOf(
            "youtube music" to "yt music",
            // MASTER F2b: spoken names that differ from the real
            // launcher label. The Play Store's label is "Play Store",
            // so "Google Play Store" matched nothing by construction.
            // "yt" resolves to YouTube only through this alias — and
            // only when no exact/phrase match claims it first (an
            // installed app labelled exactly "YT" still wins, and a
            // "YT Music" entry still claims "yt" at the phrase stage,
            // exactly like "music" claims it today).
            "google play store" to "play store",
            "playstore" to "play store",
            "you tube" to "youtube",
            "yt" to "youtube",
        )

        /**
         * MASTER F2d: the similarity floor for the fuzzy fallback —
         * 1 − Levenshtein / longer length on the whole normalised
         * label. 0.80 admits one-typo names ("youtub" → "youtube" at
         * 0.86) and rejects short or unrelated ones ("yt" → "youtube"
         * at 0.29, "fb" → "facebook" at 0.25). Deliberately high:
         * the catalogue must never resolve to the wrong app.
         */
        const val FUZZY_SIMILARITY_THRESHOLD: Double = 0.80
    }

    @Volatile
    private var apps: List<AppInfo> = emptyList()

    @Volatile
    private var loaded = false

    /**
     * Re-query the source (install / remove / replace). Called by the package-change receiver.
     *
     * MASTER F2c: a failed or empty load must NOT poison the process.
     * The old code set [loaded] = true on an empty list, so one failed
     * first load (service not bound yet, query throwing) left the
     * catalogue empty for the lifetime of the process. Now an empty /
     * failed load keeps the previous list (if any) and leaves [loaded]
     * false, so the next resolve re-queries on demand. A phone always
     * has launchable apps — an empty list is a failed load, never a
     * real answer.
     */
    @Synchronized
    fun refresh() {
        val fresh = try {
            source.queryLaunchableApps().sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            null
        }
        if (fresh.isNullOrEmpty()) return
        apps = fresh
        loaded = true
    }

    /** Receiver seam: a package was installed, removed or replaced. */
    fun onPackageChanged() = refresh()

    fun ensureLoaded() {
        // MASTER F2c: re-query on demand when the list is empty, not
        // only when never loaded — a poisoned/failed first load must
        // recover on the next resolve instead of serving NotFound
        // forever.
        if (!loaded || apps.isEmpty()) refresh()
    }

    /** All launchable apps, sorted by label. */
    fun allApps(): List<AppInfo> {
        ensureLoaded()
        return apps
    }

    /**
     * Resolve [name] to an app. See the file header for the exact rules.
     * Never guesses: ambiguity and no-match are reported, not resolved.
     */
    fun resolveByName(name: String): AppResolution {
        val query = name.trim()
        if (query.isBlank()) return AppResolution.NotFound
        ensureLoaded()

        // Stage 0: literal package name.
        if (query.contains('.')) {
            apps.firstOrNull { it.packageName.equals(query, ignoreCase = true) }
                ?.let { return AppResolution.Found(it) }
        }

        val normQuery = normalize(query)

        // Stages 1–2 over the spoken name, then — only on no match —
        // over its alias (item 9). An alias never overrides an exact
        // or phrase match for the spoken name itself.
        val candidates = listOf(normQuery) +
            listOfNotNull(LABEL_ALIASES[normQuery])
        for (candidate in candidates) {
            val candidateWords = words(candidate)
            // Stage 1: exact label (normalised).
            val exact = apps.filter { normalize(it.label) == candidate }
            if (exact.size == 1) return AppResolution.Found(exact[0])
            if (exact.size > 1) return AppResolution.Ambiguous(exact)

            // Stage 2: whole-word phrase — all query words, in order, as whole
            // words of the label. Substrings never match ("Tube" ≠ YouTube).
            val phrase = apps.filter { words(normalize(it.label)).containsPhrase(candidateWords) }
            if (phrase.size == 1) return AppResolution.Found(phrase[0])
            if (phrase.size > 1) return AppResolution.Ambiguous(phrase)
        }

        // Stage 3 (MASTER F2d): bounded fuzzy fallback — the safety
        // net for STT garbling ("youtub"). Runs ONLY after exact,
        // phrase and alias stages all failed, on the whole normalised
        // label, single best candidate only: exactly one candidate at
        // or above the threshold resolves; two or more is Ambiguous
        // (never a pick); none is NotFound. Exact matches always win
        // because this stage never runs when one exists.
        val fuzzy = apps.mapNotNull { app ->
            val sim = similarity(normQuery, normalize(app.label))
            if (sim >= FUZZY_SIMILARITY_THRESHOLD) app to sim else null
        }
        if (fuzzy.size == 1) return AppResolution.Found(fuzzy[0].first)
        if (fuzzy.size > 1) {
            return AppResolution.Ambiguous(fuzzy.map { it.first })
        }

        return AppResolution.NotFound
    }

    /**
     * Which app is in the foreground, where the device permits knowing.
     * Resolves the package against the catalogue when it is a launchable
     * app; otherwise [ForegroundResult.Known] still carries the package
     * name with `app = null`. When neither the accessibility service nor
     * UsageStats can answer, returns [ForegroundResult.Unavailable] with
     * the reason — never a guessed package.
     */
    fun foregroundApp(): ForegroundResult {
        val source = foregroundSource
            ?: return ForegroundResult.Unavailable(
                "Foreground awareness isn't wired — no foreground source was provided.",
            )
        return when (val probe = source.probe()) {
            is ForegroundProbe.Found -> {
                ensureLoaded()
                ForegroundResult.Known(
                    probe.packageName,
                    apps.firstOrNull { it.packageName == probe.packageName },
                )
            }
            is ForegroundProbe.Unavailable -> ForegroundResult.Unavailable(probe.reason)
        }
    }
}

/** Lowercase, punctuation → space, collapse whitespace. */
private fun normalize(text: String): String =
    text.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

private fun words(normalized: String): List<String> =
    if (normalized.isEmpty()) emptyList() else normalized.split(' ')

/** True when [phrase] appears as a contiguous subsequence of this word list. */
private fun List<String>.containsPhrase(phrase: List<String>): Boolean {
    if (phrase.isEmpty() || phrase.size > size) return false
    for (start in 0..size - phrase.size) {
        if (subList(start, start + phrase.size) == phrase) return true
    }
    return false
}

/** MASTER F2d: edit distance between two normalised labels. */
private fun levenshtein(a: String, b: String): Int {
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

/** MASTER F2d: similarity in [0, 1] — 1 − distance / longer length. */
private fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    val max = maxOf(a.length, b.length)
    if (max == 0) return 1.0
    return 1.0 - levenshtein(a, b).toDouble() / max
}
