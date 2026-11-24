package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.search.SearchExecutor
import com.loom.assistant.execution.search.SearchOutcome

/**
 * In-app voice search (fix plan item 5, search H2).
 *
 * The same executor path the floating host uses
 * (`LoomOverlayService.floatingVoiceHost.onSearchRequested`): install the
 * wiring, run `SearchExecutor.execute()` on a background thread (it BLOCKS
 * on confirmation cards, up to 90 s per token), and deliver the honest
 * outcome text to [onAck]. The floating host itself is NOT reused — its
 * ack is spoken through the floating session, which an in-app session
 * does not have; the in-app hosts (ChatScreenView, HomeScreenView)
 * speak/append the ack through their own live session instead.
 *
 * Always returns true: the request is handled here. Failures arrive as
 * ack text ("Search failed: …"), never as a refusal — the manager only
 * speaks its "can't search from this session" routing message when the
 * host returns false.
 *
 * The outcome→ack mapping mirrors the floating host verbatim so the two
 * paths never drift in wording; [ackFor] is the pure, JVM-tested seam
 * (see LoomSearchRunnerTest).
 */
object LoomSearchRunner {

    fun runSearch(
        context: Context,
        appName: String,
        query: String,
        origin: RequestOrigin,
        onAck: (String) -> Unit,
    ): Boolean {
        SearchExecutorWiring.install(context)
        Thread({
            // AOIE order (a) R5: a plain voice search stops at the
            // verified results list (SHOW_RESULTS), like the plan
            // path — it no longer opens the first result unasked
            // (the old default, OPEN_FIRST_RESULT, opened a video
            // David never asked to open).
            val outcome = try {
                SearchExecutor.execute(
                    appName, query, origin,
                    goal = com.loom.assistant.execution.search.SearchGoal.SHOW_RESULTS,
                )
            } catch (e: Exception) {
                SearchOutcome.Failed("Search failed: ${e.message ?: "unknown error"}")
            }
            onAck(ackFor(outcome, appName))
        }, "loom-inapp-search").start()
        return true
    }

    /** Outcome → spoken ack text. Mirrors the floating host verbatim. */
    fun ackFor(outcome: SearchOutcome, appName: String): String = when (outcome) {
        is SearchOutcome.Success -> outcome.detail
        is SearchOutcome.Failed -> "Search failed: ${outcome.reason}"
        SearchOutcome.AppNotFound ->
            "I couldn't find an app called '$appName' on this phone."
        is SearchOutcome.Ambiguous ->
            "Which one — ${outcome.candidates.joinToString(", ")}?"
    }
}
