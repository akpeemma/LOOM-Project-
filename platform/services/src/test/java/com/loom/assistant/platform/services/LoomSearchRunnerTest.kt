package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.search.SearchOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-app routing (fix plan item 5, search H2) — JVM checks for
 * LoomSearchRunner, the shared in-app search path.
 *
 * Compiles the REAL runner + the REAL SearchExecutor against the
 * android.content fake plus a test-harness stub for
 * SearchExecutorWiring (the production wiring needs the accessibility
 * service, which has no JVM fake). The executor itself is unconfigured
 * here — execute() fails honestly with the not-set-up text, which
 * proves runSearch actually called it instead of refusing or dropping
 * the request.
 *
 * Plain-main() pattern (see test-harness/run-search-tests.sh, r_inapp).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected true, got false")
    }
}

private fun testAckFor() {
    check(
        "Success → detail verbatim",
        LoomSearchRunner.ackFor(
            SearchOutcome.Success("Found it in YouTube."),
            "YouTube",
        ) == "Found it in YouTube.",
    )
    check(
        "Failed → prefixed reason",
        LoomSearchRunner.ackFor(
            SearchOutcome.Failed("I couldn't find a search box in YouTube"),
            "YouTube",
        ) == "Search failed: I couldn't find a search box in YouTube",
    )
    check(
        "AppNotFound names the app",
        LoomSearchRunner.ackFor(SearchOutcome.AppNotFound, "NoSuchApp") ==
            "I couldn't find an app called 'NoSuchApp' on this phone.",
    )
    check(
        "Ambiguous lists candidates",
        LoomSearchRunner.ackFor(
            SearchOutcome.Ambiguous(listOf("YouTube", "YT Music")),
            "YouTube",
        ) == "Which one — YouTube, YT Music?",
    )
}

private fun testRunSearchReachesExecutor() {
    val latch = CountDownLatch(1)
    var ack: String? = null
    val handled = LoomSearchRunner.runSearch(
        Context(), "YouTube", "Spider-Man", RequestOrigin.DIRECT_USER,
    ) { text ->
        ack = text
        latch.countDown()
    }
    check("runSearch returns true (handled, never refused)", handled)
    check("ack arrives on the background thread", latch.await(5, TimeUnit.SECONDS))
    check(
        "ack is the executor's honest not-set-up failure",
        ack == "Search failed: Search is not set up yet — the device-control service isn't running.",
    )
}

fun main() {
    testAckFor()
    testRunSearchReachesExecutor()

    println("LoomSearchRunnerTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
