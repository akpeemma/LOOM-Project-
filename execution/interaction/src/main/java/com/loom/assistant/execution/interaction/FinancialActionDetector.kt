package com.loom.assistant.execution.interaction

/**
 * Stage 2C (§4.3) — financial-transaction / security-action detector.
 *
 * The SINGLE maintained match list for the queue's pre-token refusal rule.
 * No ad-hoc financial checks at call sites: every planned step is screened
 * here BEFORE any AuthGate token request. Matches are refused outright — no
 * card, no per-step confirmation, no sequence-cover override — and mapped to
 * the DENY-class `tap.financial_transaction` registry key for audit.
 * Guidance-only is offered where a visual target exists.
 *
 * Bias is deliberately conservative: an ambiguous phrase refuses (the queue
 * shows the highlight and David completes it himself) rather than guessing.
 * Pure Kotlin — directly JVM-testable.
 */
object FinancialActionDetector {

    /**
     * The maintained match list. Each entry is a case-insensitive regex with
     * word boundaries where a bare substring would over-match. ADD new
     * phrases here — never inline equivalents at call sites.
     */
    val PATTERNS: List<Regex> = listOf(
        // ---- financial transactions ----
        "\\bpay\\b",
        "\\bpayment\\b",
        "\\bpay\\s*now\\b",
        "\\bcheckout\\b",
        "\\bbuy\\b",
        "\\bpurchase\\b",
        "\\border\\s*now\\b",
        "\\bplace\\s*order\\b",
        "\\bsend\\s*money\\b",
        "\\btransfer\\b",
        "\\bwithdraw\\b",
        "\\bdeposit\\b",
        "\\btop\\s*up\\b",
        "\\bbilling\\b",
        "\\bsubscri(be|ption)\\b",
        // ---- security-sensitive authentication ----
        "\\botp\\b",
        "one[-\\s]?time\\s*(pass|code)",
        "\\b2fa\\b",
        "two[-\\s]?factor",
        "approv(e|al).{0,12}(login|log\\s*in|sign[-\\s]?in)",
        "(login|log\\s*in|sign[-\\s]?in).{0,12}approv",
        "verification\\s*code",
        "enter\\s*(the\\s*)?code\\b",
        // ---- credentials ----
        "\\bpassword\\b",
        "\\bpasscode\\b",
        "pin\\s*code",
        "(enter|change|reset|new|confirm).{0,16}\\bpin\\b",
        "\\bbiometric\\b",
        "\\bfingerprint\\b",
        "face\\s*(id|unlock|recognition)",
        // ---- permission grants ----
        "grant\\s*permission",
        "allow\\s*permission",
        "\\bruntime\\s*permission\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * True when [instruction] (or [targetQuery]) describes a financial
     * transaction or security-sensitive action.
     */
    fun matches(instruction: String, targetQuery: String = ""): Boolean {
        val haystack = "$instruction $targetQuery"
        return PATTERNS.any { it.containsMatchIn(haystack) }
    }

    /**
     * Human-readable reason naming the first matched phrase, for the audit
     * log and the honest refusal message. Null when there is no match.
     */
    fun describe(instruction: String, targetQuery: String = ""): String? {
        val haystack = "$instruction $targetQuery"
        val hit = PATTERNS.firstOrNull { it.containsMatchIn(haystack) } ?: return null
        val m = hit.find(haystack) ?: return null
        return "matched financial/security phrase \"${m.value.trim()}\""
    }
}
