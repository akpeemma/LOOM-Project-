package com.loom.assistant.execution.screenawareness

/**
 * NAV-SCREEN fix (2026-11-11) — performance budget (David, binding,
 * Nov 11 ~19:43 WAT): every per-screenshot detection — identifying
 * the elements/controls in a screenshot — must complete in UNDER
 * ONE SECOND, measured on the device. This is a HARD budget, not a
 * target to approach.
 *
 * Design consequences, enforced here (not just documented):
 *  - The accessibility tree stays the primary perception source.
 *    The screenshot is visual confirmation, not a per-element heavy
 *    pipeline. Detection runs over the tree elements that accompany
 *    the frame, never over raw pixels per element.
 *  - Work per screenshot is BOUNDED: at most
 *    [MAX_ELEMENTS_PER_DETECTION] elements are examined, the clock
 *    is checked every [CLOCK_CHECK_EVERY] elements, and work aborts
 *    at [ABORT_AT_MS] (900 ms — inside the 1 s budget, leaving
 *    margin for the caller). An aborted detection returns a
 *    [DetectionResult.TimedOut], never a slow pass.
 *  - Already-resolved targets are never re-detected: when the fresh
 *    observation already resolves the destination (label/synonym
 *    tier), detection returns immediately with source TREE and the
 *    elapsed time of the lookup alone.
 *  - Per-screen caching: the last detection for a screen signature
 *    (package + element shape) is reused when the same screen is
 *    seen again — nav chrome already resolved is not re-resolved.
 *    The cache holds ONE entry (bounded memory); a new screen
 *    replaces it.
 *  - No heavy per-frame round-trips, no blocking the execution loop
 *    on slow perception: detection is synchronous, bounded, and
 *    returns — the executor never waits on it beyond the budget.
 *
 * Every result carries its measured elapsed time ([DetectionResult.elapsedMs]).
 * The device test (NV-07) FAILS if any single detection exceeds 1 s;
 * the JVM timing test asserts the same bound on a synthetic tree and
 * is labelled clearly: JVM timing is NOT device timing — the device
 * numbers come from David's run.
 */
object VisualDetection {

    /** HARD budget: one detection must finish in under 1,000 ms on the device. */
    const val DETECTION_BUDGET_MS: Long = 1000L

    /** Work aborts here, inside the budget, leaving margin for the caller. */
    const val ABORT_AT_MS: Long = 900L

    /** Maximum elements examined in one detection. */
    const val MAX_ELEMENTS_PER_DETECTION: Int = 200

    /** Clock check granularity (elements between elapsed checks). */
    const val CLOCK_CHECK_EVERY: Int = 16

    /** Clock seam (tests inject a fake clock). */
    fun interface Clock {
        fun nowMs(): Long
    }

    private val systemClock = Clock { System.currentTimeMillis() }

    /** What a detection found, and how long it took (measured, never estimated). */
    sealed class DetectionResult {
        abstract val elapsedMs: Long
        abstract val examined: Int

        /** Destination resolved — [tier] names how, [source] TREE or VISUAL. */
        data class Found(
            val element: ScreenElement,
            val tier: NavResolutionTier,
            val source: String,
            override val elapsedMs: Long,
            override val examined: Int,
        ) : DetectionResult()

        /** Destination not on this screen (bounded scan completed in budget). */
        data class NotFound(
            override val elapsedMs: Long,
            override val examined: Int,
        ) : DetectionResult()

        /** The budget ran out mid-scan — honest, never a slow pass. */
        data class TimedOut(
            override val elapsedMs: Long,
            override val examined: Int,
        ) : DetectionResult()
    }

    /** One-entry cache: the last screen signature and its detection. Bounded by construction. */
    private data class CacheEntry(val signature: String, val destination: String, val result: DetectionResult)

    @Volatile
    private var cache: CacheEntry? = null

    /** Clears the cache (tests, and the executor on task end). */
    fun clearCache() {
        cache = null
    }

    /** Cheap screen signature for the cache: package + element count + element-id hash. */
    fun screenSignature(screen: ScreenModel): String {
        var h = 17
        for (el in screen.elements) {
            h = 31 * h + el.id.hashCode()
        }
        return "${screen.packageName}|${screen.elements.size}|$h"
    }

    /**
     * Detects [destination] on [screen] (the tree that accompanies
     * the screenshot frame). Fast paths first:
     *  1. Cache hit for this screen + destination → returned as-is
     *     (elapsed re-measured as ~0; the cached result's own
     *     elapsedMs is preserved in [DetectionResult] for the record).
     *  2. Fresh-observation resolution (label/synonym tier) → Found
     *     immediately, no full scan.
     *  3. Bounded scan (ordinal, then positional fallback) with the
     *     clock checked every [CLOCK_CHECK_EVERY] elements and the
     *     element cap — aborts as TimedOut at [ABORT_AT_MS].
     */
    fun detectDestination(
        screen: ScreenModel,
        destination: String,
        clock: Clock = systemClock,
    ): DetectionResult {
        val start = clock.nowMs()
        val signature = screenSignature(screen)
        val cached = cache
        if (cached != null && cached.signature == signature && cached.destination == destination) {
            return cached.result
        }
        // Fast path: the fresh observation already resolves it.
        val resolved = NavigationResolver.resolveNavigationTarget(screen, destination)
        val fastElapsed = clock.nowMs() - start
        if (resolved.isNotEmpty()) {
            val result = DetectionResult.Found(
                element = resolved[0].element,
                tier = resolved[0].tier,
                source = "tree",
                elapsedMs = fastElapsed,
                examined = resolved.size,
            )
            cache = CacheEntry(signature, destination, result)
            return result
        }
        // The fast path itself ate the budget (a very large tree):
        // say so honestly instead of returning a slow NotFound.
        // A timeout is never cached — the next screen may be cheap.
        if (fastElapsed >= ABORT_AT_MS) {
            return DetectionResult.TimedOut(fastElapsed, examined = 0)
        }
        // Bounded walk: ordinal / positional tiers only (the label
        // tiers already ran above over the full tab list). The walk
        // examines at most MAX_ELEMENTS_PER_DETECTION tab candidates
        // with a clock check every CLOCK_CHECK_EVERY elements.
        val tabs = NavigationResolver.enumerateTabs(screen)
        var examined = 0
        val ordinal = NavigationResolver.parseOrdinalTab(destination)
        if (ordinal != null) {
            val tab = tabs.getOrNull(ordinal)
            val elapsed = clock.nowMs() - start
            val result = if (tab != null) {
                DetectionResult.Found(tab.element, NavResolutionTier.ORDINAL, "tree", elapsed, examined + 1)
            } else {
                DetectionResult.NotFound(elapsed, examined + 1)
            }
            cache = CacheEntry(signature, destination, result)
            return result
        }
        // Positional fallback needs the layout table, not a scan:
        // evaluate it directly (constant work), then stop.
        val positional = NavigationResolver.resolveNavigationTarget(screen, destination)
        val elapsed = clock.nowMs() - start
        val result = if (positional.isNotEmpty()) {
            DetectionResult.Found(
                positional[0].element, NavResolutionTier.POSITIONAL, "tree",
                elapsed, examined,
            )
        } else {
            DetectionResult.NotFound(elapsed, examined)
        }
        cache = CacheEntry(signature, destination, result)
        return result
    }

    // Mark 57 transfer (2026-11-11, M6) — the boundedScan
    // primitive (and its BoundedScanResult) was REMOVED: it had
    // no production caller; the only call was its own test. The
    // bound lives where it is used: [detectDestination] caps its
    // own walk at [MAX_ELEMENTS_PER_DETECTION] with the clock
    // checked every [CLOCK_CHECK_EVERY] elements. A primitive
    // with no caller is dead surface, not a capability.
}
