package com.loom.assistant.execution.interaction

/**
 * Stage 2C (§4.3) — FinancialActionDetector unit tests.
 *
 * The detector is the single maintained match list; these tests pin its
 * behavior: every phrase class from the plan matches, benign instructions
 * do not, and describe() names the hit for the audit log.
 * Pure Kotlin — directly JVM-testable.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

fun main() {
    // ---- plan §4.3 phrase classes (must all match) ----
    val mustMatch = listOf(
        "Tap the Pay button for you",
        "Buy now",
        "Send money to Ada",
        "approve login on the new device",
        "enter OTP",
        "change password",
        "grant permission to access contacts",
        "Tap checkout",
        "Confirm purchase",
        "Enter your 2FA code",
        "one-time passcode",
        "allow permission for location",
        "Top up wallet",
        "Enter PIN",
        "verification code",
    )
    for (phrase in mustMatch) {
        check("matches: \"$phrase\"", FinancialActionDetector.matches(phrase))
    }
    // targetQuery is screened too
    check(
        "matches via targetQuery",
        FinancialActionDetector.matches("Tap the button", "Pay"),
    )

    // ---- benign instructions must NOT match ----
    val mustNotMatch = listOf(
        "Tap the Settings toggle",
        "Open Wi-Fi settings",
        "Tap the profile icon",
        "Scroll down to see more",
        "Show the next tip",
        "Guide me to the brightness slider",
    )
    for (phrase in mustNotMatch) {
        check("no match: \"$phrase\"", !FinancialActionDetector.matches(phrase))
    }

    // ---- describe() names the hit ----
    val desc = FinancialActionDetector.describe("Tap the Pay button")
    check("describe names the phrase", desc != null && desc.contains("pay", ignoreCase = true))
    check(
        "describe null when no match",
        FinancialActionDetector.describe("Tap the Settings toggle") == null,
    )

    // ---- the list is a single maintained constant ----
    check("match list is non-empty", FinancialActionDetector.PATTERNS.isNotEmpty())

    println("FinancialActionDetectorTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
