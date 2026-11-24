package com.loom.assistant.execution.screenawareness

import java.security.MessageDigest

/**
 * Mark 57 transfer (2026-11-11) — position-blind structure digests
 * and a settle-wait, ported from core/screenview.py.
 *
 * Two digests, both blind to POSITION (nothing in this design ever
 * caches a coordinate between steps) and to capture time:
 *  - [sensitiveDigest]: structure — every element's role, label,
 *    enabled and selected state. Answers "did my click do anything".
 *  - [stableDigest]: only what acting depends on — interactable
 *    controls, their names, enabled and selected state. A ticking
 *    clock or blinking cursor must not keep a settle-wait from
 *    settling, so text bodies, images and non-interactable labels
 *    are excluded here.
 * An unanswerable digest is treated as CHANGED, never unchanged —
 * the safe direction.
 *
 * [waitStable] replaces fixed sleeps for screen settling (Mark 57:
 * fixed sleeps are "always wrong twice" — too long on an instant
 * dialog, too short on a slow app, and the short case is the
 * misclick). It polls [observe] until the stable digest is
 * unchanged for [settleMs] (default 0.35 s, max [maxMs] 6 s), with
 * the poll gap never shorter than the last scan took. The caller
 * gets the settled model — never a stale one.
 */
object ScreenDigest {

    /** Roles that count as interactable for the stable digest. */
    private val INTERACTABLE_ROLES: Set<ElementRole> = setOf(
        ElementRole.BUTTON,
        ElementRole.ICON,
        ElementRole.INPUT,
        ElementRole.SEARCH_FIELD,
        ElementRole.MENU_ITEM,
        ElementRole.TOGGLE,
    )

    /** Position-blind structure digest of the whole model. */
    fun sensitiveDigest(model: ScreenModel): String =
        digestOf(model.elements.sortedBy { it.id }.joinToString("\n") { el ->
            "${el.role}|${el.label()}|${el.enabled}|${el.selected}"
        })

    /** Settle digest: interactable controls + enabled/selected only. */
    fun stableDigest(model: ScreenModel): String =
        digestOf(
            model.elements
                .filter { it.role in INTERACTABLE_ROLES && !it.bounds.isEmpty }
                .sortedBy { it.id }
                .joinToString("\n") { el ->
                    "${el.role}|${el.label()}|${el.enabled}|${el.selected}"
                },
        )

    private fun digestOf(joined: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    sealed class WaitStableResult {
        /** The screen settled: [model] is the last observation, read
         *  after [polls] observations over [elapsedMs]. */
        data class Settled(val model: ScreenModel, val polls: Int, val elapsedMs: Long) :
            WaitStableResult()

        /** The budget ran out before the screen settled: [model] is
         *  the last observation (null when none was ever readable).
         *  Callers use the last reading rather than refusing — an
         *  animated page still has a screen to act on. */
        data class TimedOut(val model: ScreenModel?, val polls: Int, val elapsedMs: Long) :
            WaitStableResult()
    }

    /**
     * Polls [observe] until the stable digest is unchanged for
     * [settleMs]. The poll gap is never shorter than the last scan
     * took (and never under 50 ms). Bounded twice: by wall clock
     * ([maxMs]) and by poll count ([maxPolls]) — a frozen clock
     * must not loop forever. [clock] and [sleeper] are seams for
     * tests (pass settleMs=0 for an instant two-identical-reads
     * settle in JVM tests; production uses the defaults).
     */
    fun waitStable(
        observe: () -> ScreenModel?,
        settleMs: Long = 350L,
        maxMs: Long = 6_000L,
        maxPolls: Int = 40,
        clock: () -> Long = System::currentTimeMillis,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
    ): WaitStableResult {
        val start = clock()
        var polls = 0
        var lastModel: ScreenModel? = null
        var lastDigest: String? = null
        var stableSince = clock()
        while (polls < maxPolls) {
            if (clock() - start >= maxMs) {
                return WaitStableResult.TimedOut(lastModel, polls, clock() - start)
            }
            val scanStart = clock()
            val model = try {
                observe()
            } catch (_: Exception) {
                null
            }
            val scanMs = (clock() - scanStart).coerceAtLeast(0L)
            polls++
            if (model == null) {
                // Unreadable is CHANGED, never unchanged — reset the
                // settle clock and keep polling.
                lastDigest = null
                stableSince = clock()
            } else {
                lastModel = model
                val digest = stableDigest(model)
                if (digest != lastDigest) {
                    lastDigest = digest
                    stableSince = clock()
                }
                if (clock() - stableSince >= settleMs) {
                    return WaitStableResult.Settled(model, polls, clock() - start)
                }
            }
            // Poll gap: never shorter than the last scan took.
            val gap = maxOf(scanMs, 50L)
            if (clock() - start + gap >= maxMs) {
                return WaitStableResult.TimedOut(lastModel, polls, clock() - start)
            }
            try {
                sleeper(gap)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return WaitStableResult.TimedOut(lastModel, polls, clock() - start)
            }
        }
        return WaitStableResult.TimedOut(lastModel, polls, clock() - start)
    }
}
