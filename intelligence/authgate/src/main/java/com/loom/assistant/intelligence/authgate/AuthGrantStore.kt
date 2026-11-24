package com.loom.assistant.intelligence.authgate

import android.content.Context

/**
 * Master implementation §8 — scoped, revocable "always allow" grants.
 *
 * A grant lets ONE low-risk action skip its confirmation card when the
 * command came from David himself — never when the model proposed it.
 * Modelled on the PointerPolicyStore pattern (SharedPreferences, narrow
 * key, explicit opt-in, revocable, audit-logged).
 *
 * Narrow by construction:
 * - Keyed by (action key + scope). Grant-eligible keys are
 *   [AuthActionRegistry.ACTION_APP_LAUNCH] (scoped by the app's
 *   package name — "Always allow opening YouTube" is a grant for
 *   ("app.launch", "com.google.android.youtube"), nothing else) and,
 *   since the 2026-11-05 card-storm fix, the search sequence's
 *   [AuthActionRegistry.ACTION_TAP_GUIDANCE],
 *   [AuthActionRegistry.ACTION_DEVICE_TYPE] and
 *   [AuthActionRegistry.ACTION_DEVICE_SUBMIT], each scoped by the
 *   searched app's package name. A search-step grant covers only
 *   search-flow requests carrying that scope — unscoped taps/types
 *   (guidance sessions, other callers) never gain or use one.
 * - A grant is created ONLY from the confirmation card's explicit opt-in
 *   ([AuthGate.resolve] with alwaysAllow=true), and only when the gate
 *   re-validates: DIRECT_USER origin, eligible key, non-blank scope,
 *   strictness != paranoid. The card never offers the opt-in otherwise.
 * - A grant can never be created for a sensitive app: the only production
 *   path to an app.launch card is [com.loom.assistant.platform.services.LoomAppLauncher],
 *   which refuses sensitive packages BEFORE any card is shown — so no
 *   grant entry for a sensitive package can ever exist. The gate additionally
 *   refuses to honour a grant for a hard-denied key (the hard-deny check
 *   runs before the grant check in [AuthGate]).
 * - Grants are NEVER inherited by model requests: the grant check requires
 *   origin == DIRECT_USER. A stored grant is invisible to MODEL requests.
 * - Under paranoid strictness (level 2) grants are never honoured and
 *   never offered — "confirm everything" means everything.
 * - A grant issues an ALLOW-class token through the normal gate path; it
 *   never skips the token. [AuthGate.consumeAndDispatch] structurally
 *   requires one, and the grant path issues it like any ALLOW decision.
 *
 * Revocation: [revoke] (one action+app), [revokeAll] (Settings →
 * Privacy → "Clear always-allow approvals"). Every grant / revoke /
 * grant-skip is audit-logged by the gate ("auth" kind).
 *
 * Storage: one SharedPreferences entry per grant in "loom_auth_grants":
 * key "grant|<actionKey>|<scope>", value "<grantedAtMillis>|<label>".
 * Package names cannot contain '|', so no escaping is needed.
 */
class AuthGrantStore private constructor(context: Context) {

    data class Grant(
        val actionKey: String,
        val scope: String,
        val label: String,
        val grantedAt: Long,
    )

    private val prefs =
        context.getSharedPreferences("loom_auth_grants", Context.MODE_PRIVATE)

    companion object {
        private const val PREFIX = "grant|"

        /** The only keys a grant may ever cover. Deliberately tiny.
         *
         * Device-evidence fix (2026-11-05, item 6 — card storm): the
         * search sequence's keys join `app.launch`, each scoped per
         * app+action and DIRECT_USER only (the gate enforces origin,
         * scope and strictness; see [AuthGate.grantApplies]). One
         * search previously needed up to 5 CONFIRM cards on every run
         * even with "always allow" opted in, because only the launch
         * step was grant-eligible. Still NEVER grant-eligible, by
         * design: `message.send` and `call.place` (a grant would let
         * later sends/calls skip their per-message card),
         * `tap.financial_transaction` and `automation.sensitive_app`
         * (hard-deny keys), and every other key. */
        val GRANT_ELIGIBLE_KEYS: Set<String> =
            setOf(
                AuthActionRegistry.ACTION_APP_LAUNCH,
                AuthActionRegistry.ACTION_TAP_GUIDANCE,
                AuthActionRegistry.ACTION_DEVICE_TYPE,
                AuthActionRegistry.ACTION_DEVICE_SUBMIT,
                // AOIE order (a) (2026-11-07): scroll/back join the
                // grantable set, scoped per app like the rest.
                // Before this they could never be granted, so any
                // scroll/back inside a search or plan always carded.
                // Still NEVER grant-eligible: message.send /
                // call.place, tap.financial_transaction,
                // automation.sensitive_app, demo.sequence.
                AuthActionRegistry.ACTION_DEVICE_SCROLL,
                AuthActionRegistry.ACTION_DEVICE_BACK,
            )

        fun isGrantEligible(actionKey: String): Boolean =
            actionKey in GRANT_ELIGIBLE_KEYS

        @Volatile
        private var instance: AuthGrantStore? = null

        fun get(context: Context): AuthGrantStore {
            return instance ?: synchronized(this) {
                instance ?: AuthGrantStore(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * True only when a grant exists for this exact (action, scope) AND the
     * key is grant-eligible. Blank scope never matches — grants are keyed
     * narrowly, never wildcarded.
     */
    @Synchronized
    fun hasGrant(actionKey: String, scope: String): Boolean {
        if (scope.isBlank()) return false
        if (!isGrantEligible(actionKey)) return false
        return prefs.contains(keyFor(actionKey, scope))
    }

    /**
     * Store a grant. Returns false (and stores nothing) for a blank scope,
     * a blank label, or an ineligible key — the caller must already have
     * validated; this is the second line of defence.
     */
    @Synchronized
    fun grant(actionKey: String, scope: String, label: String): Boolean {
        if (scope.isBlank() || label.isBlank()) return false
        if (!isGrantEligible(actionKey)) return false
        prefs.edit()
            .putString(keyFor(actionKey, scope), "${System.currentTimeMillis()}|$label")
            .apply()
        return true
    }

    /** Revoke one grant. Returns true iff one existed. */
    @Synchronized
    fun revoke(actionKey: String, scope: String): Boolean {
        val key = keyFor(actionKey, scope)
        if (!prefs.contains(key)) return false
        prefs.edit().remove(key).apply()
        return true
    }

    /** Revoke every grant. Returns how many were removed. */
    @Synchronized
    fun revokeAll(): Int {
        val keys = prefs.all.keys.filter { it.startsWith(PREFIX) }
        if (keys.isEmpty()) return 0
        val editor = prefs.edit()
        for (k in keys) editor.remove(k)
        editor.apply()
        return keys.size
    }

    /** All stored grants, newest first. */
    @Synchronized
    fun list(): List<Grant> {
        return prefs.all.entries.mapNotNull { (k, v) ->
            if (!k.startsWith(PREFIX)) return@mapNotNull null
            val parts = k.removePrefix(PREFIX).split("|")
            if (parts.size != 2) return@mapNotNull null
            val raw = v as? String ?: return@mapNotNull null
            val at = raw.substringBefore("|").toLongOrNull() ?: return@mapNotNull null
            val label = raw.substringAfter("|", "")
            Grant(parts[0], parts[1], label, at)
        }.sortedByDescending { it.grantedAt }
    }

    @Synchronized
    fun count(): Int = prefs.all.keys.count { it.startsWith(PREFIX) }

    private fun keyFor(actionKey: String, scope: String): String =
        "$PREFIX$actionKey|$scope"
}
