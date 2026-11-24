package com.loom.assistant.execution.screenawareness

/**
 * Phase 4 — privacy boundary for screen awareness (arch §6).
 * Sensitive-app denylist + password redaction happen HERE, before any
 * screen content reaches the planner, pointer tooltips, or the LLM.
 */
object ScreenDataPolicy {

    /**
     * Package-name fragments that mark an app as sensitive. Never forwarded.
     *
     * Stage 2C (§4.1.1): extended ONLY with well-known banking / finance /
     * authenticator fragments — conservative additions, no ad-hoc checks at
     * call sites. This classifier is the single place new fragments are added.
     * Consequence of a match is guidance-only (never a refusal of the app).
     */
    private val SENSITIVE_FRAGMENTS = listOf(
        "bank", "banking", "finance", "wallet", "pay",
        "authenticator", "authy", "yubico", "okta",
        "keepass", "1password", "bitwarden", "lastpass", "dashlane",
        "password", "pin",
        // Stage 2C: well-known mobile-money + banking fragments (incl.
        // Nigerian banking apps, verified against the Redmi device set).
        "momo", "kuda", "opay", "palmpay", "moniepoint", "piggyvest",
        "flutterwave", "paystack",
        "gtbank", "zenithbank", "accessbank", "firstbank", "uba",
        "fidelitybank", "unionbank", "sterlingbank", "wema",
    )

    fun isSensitivePackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return SENSITIVE_FRAGMENTS.any { lower.contains(it) }
    }

    /**
     * Redact a screen model: password fields lose their text everywhere;
     * inside a sensitive package, ALL element text is cleared (bounds and
     * roles stay so guidance can still point, but nothing readable leaks).
     * Hint text is cleared alongside text: a hint can carry readable
     * content (e.g. a pre-filled account number) and must not leak
     * through the label fallback.
     */
    fun redact(model: ScreenModel): ScreenModel {
        val sensitive = isSensitivePackage(model.packageName)
        if (!sensitive && model.elements.none { it.isPassword }) return model
        return model.copy(
            elements = model.elements.map { el ->
                if (sensitive || el.isPassword) {
                    el.copy(text = null, contentDescription = null, hintText = null)
                } else el
            },
        )
    }
}
