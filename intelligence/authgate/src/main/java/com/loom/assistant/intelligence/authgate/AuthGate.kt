package com.loom.assistant.intelligence.authgate

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomPreferences
import java.util.UUID

/**
 * Phase 6 — AuthGate: the structural confirmation gate (arch §2.14, §6).
 *
 * Port of the approach in repo/core/confirm.py + core/protocols.py, adapted
 * to Android:
 *  - confirm.py: the confirmation TOKEN IS ISSUED BY THE INTERFACE, never by
 *    the model or any caller. Only genuinely irreversible things get a gate
 *    (reversible things are done at once — undo beats a question). A pending
 *    confirmation expires after 90 seconds. With no UI bound, the gate
 *    REFUSES rather than silently performing something irreversible.
 *  - protocols.py: named, registered actions (the registered_protocols dict)
 *    → [AuthActionRegistry]: every consequential action is a named registry
 *    entry with a declared risk class, never an ad-hoc string.
 *
 * Enforcement is structural, not voluntary: gated sinks (gesture dispatch,
 * skill install, external sends) require an [AuthToken] that only this gate
 * issues. No token → no dispatch. Every decision is audit-logged.
 *
 * Risk matrix (arch §6):
 *  - ALLOW (no prompt): read-only queries, minor self-heal corrections,
 *    resuming the user's own paused task.
 *  - CONFIRM (plain-language card: what, why, reversible?): changing
 *    settings, installing skills, file writes outside app scope, sending
 *    messages on the user's behalf, any external publish, device-control
 *    taps, guidance taps.
 *  - DENY (always): spending money, factory/security changes, anything the
 *    policy list forbids.
 *
 * Master §8 — provenance and scoped grants: requests carry a
 * [RequestOrigin] (David's own words vs a model-proposed action). A
 * stored grant ([AuthGrantStore]) skips the card ONLY for a direct user
 * command on a grant-eligible key with a narrow scope, never for model
 * requests, never for sensitive/financial actions (hard-deny runs first),
 * and never under paranoid strictness. A grant still issues a normal
 * ALLOW-class token — the token is never skipped.
 */
class AuthGate private constructor(appContext: Context) {

    /** Risk class of an action (arch §6 auth-gate matrix). */
    enum class RiskClass { ALLOW, CONFIRM, DENY }

    /**
     * A consequential action. [isPureRead] marks actions that stay ALLOW even
     * under strict/paranoid strictness (they reveal nothing and change nothing).
     *
     * [scope] narrows the action to one target (for app.launch: the app's
     * package name). [grantOffer] is the card's "always allow" opt-in label
     * (e.g. "Always allow opening YouTube") — only ever honoured for a
     * direct user command on a grant-eligible key; see [AuthGrantStore].
     */
    data class AuthAction(
        val key: String,
        val title: String,
        val detail: String,
        val riskClass: RiskClass,
        val isPureRead: Boolean = false,
        val scope: String = "",
        val grantOffer: String? = null,
    )

    /**
     * Single-use token issued ONLY by this gate. The constructor is internal
     * so callers (including the model layer) cannot forge one — the gate is
     * the sole issuer. Kept a plain class (not data) so the internal
     * constructor stays enforced.
     */
    class AuthToken internal constructor(
        val id: String,
        val actionKey: String,
        val expiresAt: Long,
    )

    sealed class GateResult {
        data class Allow(val token: AuthToken) : GateResult()
        data class NeedsConfirmation(val pendingId: String) : GateResult()
        data class Deny(val reason: String) : GateResult()
    }

    /**
     * Stage 2C (plan §4.2-F, §4.3 step 5, correction 3) — report from
     * [purgeAll]: how many pending confirmations were cancelled and how
     * many issued tokens were revoked. [auditLogged] is always true — the
     * purge and every individual destruction are audit-logged.
     */
    data class PurgeReport(
        val pendingCancelled: Int,
        val tokensRevoked: Int,
        val auditLogged: Boolean,
    )

    /**
     * Stage 2C (plan §8.2) — result of the atomic consume+dispatch boundary
     * ([consumeAndDispatch]).
     */
    sealed class DispatchResult<out T> {
        /**
         * The handoff ran: lifecycle was ACTIVE at consumption AND at the
         * dispatch handoff, and the token was valid, key-matched, unexpired.
         */
        data class Dispatched<out T>(val value: T) : DispatchResult<T>()

        /**
         * No dispatch happened. When the refusal came from the lifecycle
         * check (at consumption or at the handoff re-check), the token was
         * NOT consumed — fail-closed, the token survives (Stage 2B P1
         * semantics), so a pre-destructive SHUTTING_DOWN → ACTIVE recovery
         * keeps working. Refusals for unknown/expired/key-mismatched tokens
         * behave exactly like [consumeToken].
         */
        data class Refused(val reason: String) : DispatchResult<Nothing>()
    }

    /**
     * One parked confirmation. [origin] is who asked (direct user vs model);
     * [grantOffer] is the card's "always allow" label, set only when the
     * gate computed that the opt-in may be offered for this request —
     * the host/card never decide that themselves.
     */
    data class PendingConfirmation(
        val id: String,
        val action: AuthAction,
        val requestedAt: Long,
        internal val onToken: (AuthToken?) -> Unit,
        val origin: RequestOrigin = RequestOrigin.MODEL,
        val grantOffer: String? = null,
    )

    companion object {
        /** Port of confirm.py TIMEOUT_SECONDS. */
        const val CONFIRM_TIMEOUT_MS = 90_000L

        @Volatile
        private var instance: AuthGate? = null

        fun init(context: Context): AuthGate {
            return instance ?: synchronized(this) {
                instance ?: AuthGate(context.applicationContext).also { instance = it }
            }
        }

        fun get(): AuthGate =
            instance ?: throw IllegalStateException("AuthGate not initialized — call init() first.")

        fun getOrNull(): AuthGate? = instance

        fun isReady(): Boolean = instance != null
    }

    private val prefs = LoomPreferences.get(appContext)
    private val audit = ActivityLog.get(appContext)
    private val grants = AuthGrantStore.get(appContext)
    // PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT): the
    // one-time grant for the approved apps (see
    // [PseudoControlPolicy] / [PseudoControlStore]).
    private val pseudo = PseudoControlStore.get(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Presentation layer for CONFIRM cards; set by the app module. */
    @Volatile
    var confirmationHost: AuthConfirmationHost? = null

    private val issuedTokens = mutableMapOf<String, AuthToken>()
    private val pending = mutableMapOf<String, PendingConfirmation>()

    // ---------- decision ----------

    /**
     * Decide an action's fate. Pure decision — parks nothing.
     * Audit-logs every decision (arch §2.14).
     *
     * [origin] is who asked: a stored grant skips the card only for
     * [RequestOrigin.DIRECT_USER] (see [grantApplies]) — MODEL requests
     * always get their card, grant or no grant.
     */
    @Synchronized
    fun check(action: AuthAction, origin: RequestOrigin = RequestOrigin.MODEL): GateResult {
        purgeExpired()
        val effective = effectiveRisk(action)
        return when (effective) {
            RiskClass.ALLOW -> {
                val token = issueToken(action.key)
                audit("ALLOW", action, "allowed, token ${token.id.take(8)}")
                GateResult.Allow(token)
            }
            RiskClass.CONFIRM -> {
                if (grantApplies(action, origin)) {
                    val token = issueToken(action.key)
                    audit(
                        "GRANT_ALLOW",
                        action,
                        "allowed by stored grant for ${action.scope}, " +
                            "token ${token.id.take(8)}",
                    )
                    GateResult.Allow(token)
                } else if (pseudoApplies(action)) {
                    val token = issueToken(action.key)
                    audit(
                        "PSEUDO_ALLOW",
                        action,
                        "allowed by pseudo control for ${action.scope}, " +
                            "token ${token.id.take(8)}",
                    )
                    GateResult.Allow(token)
                } else if (confirmationHost == null) {
                    // confirm.py: no interface bound → REFUSE, never silently perform.
                    audit("DENY", action, "denied — no confirmation interface bound")
                    GateResult.Deny("No confirmation interface is available right now, so I have not done it.")
                } else {
                    val p = PendingConfirmation(
                        id = UUID.randomUUID().toString(),
                        action = action,
                        requestedAt = System.currentTimeMillis(),
                        onToken = {},
                        origin = origin,
                        grantOffer = grantOfferFor(action, origin),
                    )
                    pending[p.id] = p
                    audit("CONFIRM_REQUESTED", action, "awaiting user decision (pending ${p.id.take(8)})")
                    GateResult.NeedsConfirmation(p.id)
                }
            }
            RiskClass.DENY -> {
                audit("DENY", action, "denied by policy")
                GateResult.Deny("This action is not permitted: ${action.title}.")
            }
        }
    }

    /**
     * Port of confirm.py request(): decide, and for CONFIRM park the action
     * with its continuation. [onToken] runs with the issued token on CONFIRM,
     * or null on deny/timeout. For ALLOW it is invoked synchronously.
     * The continuation always runs on the main thread.
     *
     * [origin] is who asked (see [check]). A stored grant skips the card
     * only for [RequestOrigin.DIRECT_USER] — MODEL requests always park
     * and get their card, grant or no grant.
     */
    @Synchronized
    fun request(
        action: AuthAction,
        origin: RequestOrigin = RequestOrigin.MODEL,
        onToken: (AuthToken?) -> Unit,
    ): GateResult {
        purgeExpired()
        val wrapped: (AuthToken?) -> Unit = { token ->
            if (Looper.myLooper() == Looper.getMainLooper()) onToken(token)
            else mainHandler.post { onToken(token) }
        }
        val effective = effectiveRisk(action)
        return when (effective) {
            RiskClass.ALLOW -> {
                val token = issueToken(action.key)
                audit("ALLOW", action, "allowed, token ${token.id.take(8)}")
                wrapped(token)
                GateResult.Allow(token)
            }
            RiskClass.CONFIRM -> {
                if (grantApplies(action, origin)) {
                    val token = issueToken(action.key)
                    audit(
                        "GRANT_ALLOW",
                        action,
                        "allowed by stored grant for ${action.scope}, " +
                            "token ${token.id.take(8)}",
                    )
                    wrapped(token)
                    GateResult.Allow(token)
                } else if (pseudoApplies(action)) {
                    val token = issueToken(action.key)
                    audit(
                        "PSEUDO_ALLOW",
                        action,
                        "allowed by pseudo control for ${action.scope}, " +
                            "token ${token.id.take(8)}",
                    )
                    wrapped(token)
                    GateResult.Allow(token)
                } else {
                    val host = confirmationHost
                    if (host == null) {
                        audit("DENY", action, "denied — no confirmation interface bound")
                        wrapped(null)
                        GateResult.Deny("No confirmation interface is available right now, so I have not done it.")
                    } else {
                        val p = PendingConfirmation(
                            id = UUID.randomUUID().toString(),
                            action = action,
                            requestedAt = System.currentTimeMillis(),
                            onToken = wrapped,
                            origin = origin,
                            grantOffer = grantOfferFor(action, origin),
                        )
                        pending[p.id] = p
                        val shown = try {
                            host.showConfirmation(p)
                        } catch (_: Exception) {
                            false
                        }
                        if (!shown) {
                            pending.remove(p.id)
                            audit("DENY", action, "denied — confirmation could not be shown")
                            wrapped(null)
                            GateResult.Deny("Could not ask for confirmation. Nothing was done.")
                        } else {
                            audit("CONFIRM_REQUESTED", action, "awaiting user decision (pending ${p.id.take(8)})")
                            GateResult.NeedsConfirmation(p.id)
                        }
                    }
                }
            }
            RiskClass.DENY -> {
                audit("DENY", action, "denied by policy")
                wrapped(null)
                GateResult.Deny("This action is not permitted: ${action.title}.")
            }
        }
    }

    /**
     * Structural enforcement point: consume a token for [actionKey].
     * Single-use, 90s expiry, key-bound. Returns false for anything else —
     * callers cannot forge a token (constructor is internal).
     *
     * Stage 2C (§8.2): the lifecycle check at consumption executes INSIDE
     * this @Synchronized block. A token is never consumed while the
     * lifecycle is not ACTIVE — fail-closed, the token survives (Stage 2B
     * P1 semantics). The dispatch-handoff re-check lives in
     * [consumeAndDispatch]; sinks that still call consumeToken directly are
     * at least refused here after deactivation/shutdown. purgeAll() on
     * deactivation/shutdown additionally destroys issued tokens, so a
     * pre-deactivation token presented post-reactivation is refused.
     */
    @Synchronized
    fun consumeToken(tokenId: String?, actionKey: String): Boolean {
        purgeExpired()
        if (!LifecycleController.isDispatchAllowed()) {
            val state = LifecycleController.currentState()
            auditRaw("auth", "TOKEN_REJECTED lifecycle=$state is not ACTIVE for $actionKey")
            return false
        }
        if (tokenId.isNullOrBlank()) return false
        // Stage 2B P1: validate the key BEFORE destructively consuming.
        // The old remove-then-validate ordering destroyed tap.device tokens
        // when requestTap probed tap.guidance first, so that key could never
        // succeed. A token survives a wrong-key probe; it is consumed only by
        // a successful, key-matched, unexpired validation. Single-use and
        // fail-closed are unchanged.
        val token = issuedTokens[tokenId] ?: return false
        if (token.actionKey != actionKey) {
            auditRaw("auth", "TOKEN_REJECTED key mismatch for $actionKey")
            return false
        }
        if (System.currentTimeMillis() > token.expiresAt) {
            auditRaw("auth", "TOKEN_REJECTED expired for $actionKey")
            return false
        }
        issuedTokens.remove(tokenId)
        return true
    }

    /**
     * Stage 2C (plan §8.2, closure decision 5) — the atomic
     * consume+dispatch boundary.
     *
     * Check → probe/consume → re-check → handoff execute as ONE critical
     * section on the gate monitor (this @Synchronized block):
     *  1. **Check at consumption:** [LifecycleController.isDispatchAllowed]
     *     must be true (lifecycle == ACTIVE), else [DispatchResult.Refused]
     *     and the token is NOT consumed (fail-closed, token survives).
     *  2. **Probe:** [actionKeys] are probed IN ORDER with the same
     *     fail-closed semantics as [consumeToken] — a key mismatch does not
     *     destroy the token. This covers requestTap's two-probe pattern
     *     (`[ACTION_TAP_GUIDANCE, ACTION_TAP]`, map mismatch #6): both probe
     *     call sites live inside this same critical section. The token is
     *     consumed only by a successful, key-matched, unexpired validation.
     *  3. **Re-check at the dispatch handoff:** the lifecycle is read again
     *     immediately before [handoff] runs — a token consumed while ACTIVE
     *     but handed to dispatch after the lifecycle left ACTIVE is refused
     *     (the token is restored, un-consumed, per the fail-closed rule).
     *  4. **Handoff:** runs INSIDE the gate lock. Keep it short and
     *     non-blocking — build the dispatch payload / make the dispatch
     *     call; never show UI or block on IO while holding the gate.
     *
     * Gated dispatch sinks (LoomAccessibilityService.requestTap, the
     * ActionQueue dispatch sink, LoomAccessibilityGestures.*) must route
     * through this instead of calling consumeToken and then dispatching
     * separately — the separate pattern reintroduces the TOCTOU race this
     * closes (token consumed at T0, lifecycle flips at T1, dispatch at T2).
     *
     * Atomicity note: this boundary serializes everything that takes the
     * gate monitor (consumeToken, purgeAll, other dispatches). A lifecycle
     * transition racing the handoff BODY itself is excluded only if the
     * controller's transition function also synchronizes on the gate
     * monitor — a LifecycleController-worker integration point. In the
     * plan's ordering (halt-first, then purgeAll before/during the state
     * flip), the handoff re-check observes the flip.
     */
    @Synchronized
    fun <T> consumeAndDispatch(
        tokenId: String?,
        actionKeys: List<String>,
        handoff: () -> T,
    ): DispatchResult<T> {
        purgeExpired()
        // Check 1 — at consumption.
        if (!LifecycleController.isDispatchAllowed()) {
            val state = LifecycleController.currentState()
            auditRaw(
                "auth",
                "DISPATCH_REFUSED lifecycle=$state is not ACTIVE " +
                    "(check-at-consumption) for token ${tokenId?.take(8)}",
            )
            return DispatchResult.Refused("lifecycle $state is not ACTIVE")
        }
        // Probe in order; a wrong-key probe never destroys the token (P1).
        if (tokenId.isNullOrBlank()) {
            auditRaw("auth", "DISPATCH_REFUSED null/blank token id")
            return DispatchResult.Refused("no token presented")
        }
        if (actionKeys.isEmpty()) {
            auditRaw("auth", "DISPATCH_REFUSED no action keys for token ${tokenId.take(8)}")
            return DispatchResult.Refused("no action keys to probe")
        }
        val token = issuedTokens[tokenId]
        if (token == null) {
            auditRaw("auth", "DISPATCH_REFUSED unknown token ${tokenId.take(8)}")
            return DispatchResult.Refused("unknown token")
        }
        if (token.actionKey !in actionKeys) {
            auditRaw("auth", "TOKEN_REJECTED key mismatch for ${actionKeys.joinToString(",")}")
            return DispatchResult.Refused(
                "token key ${token.actionKey} not in ${actionKeys.joinToString(",")}",
            )
        }
        if (System.currentTimeMillis() > token.expiresAt) {
            auditRaw("auth", "TOKEN_REJECTED expired for ${token.actionKey}")
            return DispatchResult.Refused("token expired")
        }
        issuedTokens.remove(tokenId)
        // Check 2 — re-check at the dispatch handoff, same critical section.
        if (!LifecycleController.isDispatchAllowed()) {
            val state = LifecycleController.currentState()
            // Fail-closed: the token was valid at consumption; restore it
            // un-consumed so a pre-destructive recovery keeps working.
            issuedTokens[tokenId] = token
            auditRaw(
                "auth",
                "DISPATCH_REFUSED lifecycle=$state is not ACTIVE " +
                    "(handoff re-check) for ${token.actionKey}; token restored",
            )
            return DispatchResult.Refused("lifecycle $state is not ACTIVE at dispatch handoff")
        }
        val value = handoff()
        auditRaw(
            "auth",
            "DISPATCHED ${token.actionKey} — token ${tokenId.take(8)} " +
                "lifecycle=ACTIVE at consumption and handoff",
        )
        return DispatchResult.Dispatched(value)
    }

    /**
     * Called by the confirmation UI when David taps Approve/Deny.
     * Port of confirm.py resolve(): accepted → issue token into the parked
     * continuation; denied/expired → continuation gets null. Nothing blocks.
     *
     * The hide is pending-aware ([AuthConfirmationHost.hideConfirmation]
     * with this id): resolving one pending hides only its card, never a
     * card belonging to another still-parked pending — the old blanket
     * hide is what let a second card's pending sit unanswerable.
     *
     * [alwaysAllow] records a scoped grant, but only when the parked
     * pending itself was a direct user command on a grant-eligible key
     * with a scope, and never under paranoid strictness — the card's
     * checkbox is not trusted; every condition is re-validated here.
     */
    @Synchronized
    fun resolve(pendingId: String, accepted: Boolean, alwaysAllow: Boolean = false) {
        purgeExpired()
        val p = pending.remove(pendingId)
        try {
            confirmationHost?.hideConfirmation(pendingId)
        } catch (_: Exception) {
        }
        if (p == null) return
        if (System.currentTimeMillis() - p.requestedAt > CONFIRM_TIMEOUT_MS) {
            audit("CONFIRM_EXPIRED", p.action, "pending ${p.id.take(8)} expired")
            p.onToken(null)
            return
        }
        if (!accepted) {
            audit("CONFIRM_DENIED", p.action, "David declined")
            p.onToken(null)
            return
        }
        if (alwaysAllow) maybeCreateGrant(p)
        val token = issueToken(p.action.key)
        audit("CONFIRM_GRANTED", p.action, "David approved, token ${token.id.take(8)}")
        p.onToken(token)
    }

    /** Cancel a pending confirmation without resolving (e.g. superseded). */
    @Synchronized
    fun cancelPending(pendingId: String) {
        val p = pending.remove(pendingId)
        if (p != null) {
            audit("CONFIRM_CANCELLED", p.action, "pending ${p.id.take(8)} cancelled")
            p.onToken(null)
        }
        try {
            confirmationHost?.hideConfirmation(pendingId)
        } catch (_: Exception) {
        }
    }

    /**
     * Stage 2C (plan §4.2-F, §4.3 step 5, correction 3) — destroy ALL
     * authorization state in one critical section.
     *
     * Called by the application-scoped LifecycleController on deactivation
     * (reason `"deactivate"`) and on shutdown (reason `"shutdown"`), after
     * live sequences are halted and before views/services are torn down:
     *  1. Expired entries are dropped first (same as every other entry).
     *  2. Every pending confirmation is cancelled: removed, audit-logged
     *     `CONFIRM_CANCELLED` per entry (with the reason), its continuation
     *     invoked with null, and the confirmation host hidden once at the
     *     end — no stale approval can land after deactivation/shutdown.
     *  3. Every issued token is revoked: cleared from the store, each
     *     audit-logged `TOKEN_REVOKED` (token id prefix, actionKey, reason).
     *  4. The purge itself is audit-logged, and a [PurgeReport] with exact
     *     counts is returned.
     *
     * This is the ONLY place tokens are destroyed en masse; purgeAll()
     * never issues. Per Part 10 §8(1) the controller never issues, holds,
     * or bypasses tokens — purge destroys only. After purgeAll(),
     * reactivation starts with an empty gate: tokens issued before
     * deactivation are refused afterwards (plan §7-B negative test), which
     * closes the 90s post-shutdown window the audit found.
     */
    @Synchronized
    fun purgeAll(reason: String): PurgeReport {
        purgeExpired()
        val pendingIds = pending.keys.toList()
        val tokenIds = issuedTokens.keys.toList()
        for (id in pendingIds) {
            val p = pending.remove(id) ?: continue
            audit(
                "CONFIRM_CANCELLED",
                p.action,
                "pending ${p.id.take(8)} cancelled by purgeAll (reason=$reason)",
            )
            try {
                p.onToken(null)
            } catch (_: Exception) {
            }
        }
        for (id in tokenIds) {
            val t = issuedTokens.remove(id) ?: continue
            auditRaw(
                "auth",
                "TOKEN_REVOKED ${t.actionKey} — token ${id.take(8)} revoked by purgeAll (reason=$reason)",
            )
        }
        if (pendingIds.isNotEmpty()) {
            try {
                confirmationHost?.hideAllConfirmations()
            } catch (_: Exception) {
            }
        }
        auditRaw(
            "auth",
            "PURGE_ALL reason=$reason pendingCancelled=${pendingIds.size} tokensRevoked=${tokenIds.size}",
        )
        return PurgeReport(
            pendingCancelled = pendingIds.size,
            tokensRevoked = tokenIds.size,
            auditLogged = true,
        )
    }

    @Synchronized
    fun pendingCount(): Int {
        purgeExpired()
        return pending.size
    }

    // ---------- internals ----------

    /**
     * Stage 2C (§4.1.3): keys that are DENY by policy no matter what risk
     * class the presented AuthAction declares. Covers both [check] and
     * [request] — they share [effectiveRisk].
     */
    private fun isHardDeniedKey(key: String): Boolean =
        key == AuthActionRegistry.ACTION_SENSITIVE_APP ||
            key == AuthActionRegistry.ACTION_TAP_FINANCIAL

    private fun effectiveRisk(action: AuthAction): RiskClass {
        // Stage 2C (§4.1.3): hard DENY by KEY, not by the caller's declared
        // risk class. AuthAction's constructor is public, so a caller that
        // bypasses the queue could forge an AuthAction with key
        // "automation.sensitive_app" or "tap.financial_transaction" and a
        // milder riskClass — the gate refuses it anyway, with no
        // confirmation surface and an audit entry.
        if (isHardDeniedKey(action.key)) return RiskClass.DENY
        if (action.riskClass == RiskClass.DENY) return RiskClass.DENY
        return when (prefs.confirmStrictness) {
            // 0 = standard: the registered matrix as-is.
            0 -> action.riskClass
            // 1 = strict: ALLOW becomes CONFIRM except for pure reads.
            1 -> if (action.riskClass == RiskClass.ALLOW && !action.isPureRead) RiskClass.CONFIRM
            else action.riskClass
            // 2 = paranoid: everything except pure reads needs confirmation.
            else -> if (action.isPureRead) RiskClass.ALLOW else RiskClass.CONFIRM
        }
    }

    // ---------- grants (master §8) ----------

    /**
     * A stored grant skips the card — ONLY for a direct user command, an
     * eligible key, a non-blank scope, and never under paranoid strictness.
     * Runs AFTER [effectiveRisk] (so hard-deny always wins: a grant can
     * never cover a sensitive app or a financial action) and AFTER the
     * sensitive pre-gates and FinancialActionDetector pre-scan, which run
     * in the caller before [request] is even reached. MODEL origin never
     * applies a grant — grants are never inherited by model requests.
     */
    private fun grantApplies(action: AuthAction, origin: RequestOrigin): Boolean {
        if (origin != RequestOrigin.DIRECT_USER) return false
        if (prefs.confirmStrictness == 2) return false
        if (action.scope.isBlank()) return false
        return grants.hasGrant(action.key, action.scope)
    }

    /**
     * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT) — the
     * one-time grant for the approved apps (see
     * [PseudoControlPolicy] / [PseudoControlStore]).
     *
     * True only when the grant is active for the current list
     * version AND the action is inside the policy table
     * ([PseudoControlPolicy.covers]: a reversible operation key in
     * an approved app). Runs AFTER [effectiveRisk] — a hard DENY
     * (sensitive app, financial tap) always wins, so a pseudo
     * grant can never authorize execution inside a sensitive app —
     * and never under paranoid strictness (level 2 disables it,
     * like stored grants). Unlike a stored grant, pseudo control
     * deliberately applies to MODEL-origin requests too: plan
     * execution IS model-origin, so an origin gate would make the
     * feature dead on arrival. The safety boundary is the TABLE
     * (approved apps + reversible keys), not the origin — and the
     * task itself is always started by David's own words.
     */
    /**
     * Device-failure fix (2026-11-13, F4) — the runtime-resolved
     * role packages (default dialer / SMS / gallery / clock),
     * supplied by the application layer
     * (PseudoControlPackages in platform:services, bound in
     * LoomApplication.onCreate). The gate module must not depend
     * on the telecom/telephony APIs the resolution needs, so the
     * provider is injected; unset means "no runtime packages"
     * (the static table alone — fails safe, the card appears).
     * A throwing provider is treated as no packages, never a
     * gate failure.
     */
    @Volatile
    private var pseudoRolePackagesProvider: () -> Set<String> = { emptySet() }

    fun setPseudoRolePackagesProvider(provider: () -> Set<String>) {
        pseudoRolePackagesProvider = provider
    }

    private fun pseudoApplies(action: AuthAction): Boolean {
        if (prefs.confirmStrictness == 2) return false
        if (!pseudo.isGranted()) return false
        val extras = runCatching { pseudoRolePackagesProvider() }.getOrDefault(emptySet())
        return PseudoControlPolicy.covers(action.key, action.scope, extras)
    }

    /**
     * The card may offer the "always allow" opt-in only for a direct user
     * command, an eligible key, a non-blank scope, and never under paranoid
     * strictness. The offer text itself comes from the action (registry
     * factory); the host/card only render what the gate computed here.
     */
    private fun grantOfferFor(action: AuthAction, origin: RequestOrigin): String? {
        if (origin != RequestOrigin.DIRECT_USER) return null
        if (prefs.confirmStrictness == 2) return null
        if (!AuthGrantStore.isGrantEligible(action.key)) return null
        if (action.scope.isBlank()) return null
        return action.grantOffer
    }

    /**
     * Create a scoped grant from an approval with the opt-in ticked.
     * Every condition is re-validated here — the card is not trusted to
     * have checked. A grant for a sensitive package can never be created
     * through this path: the only production route to an app.launch card
     * refuses sensitive packages before any card is shown.
     *
     * Device-evidence fix (2026-11-05, item 10 — grant
     * retroactivity): a grant created by resolving card 1 also
     * cancels already-parked DIRECT_USER pendings for the SAME
     * (action key + scope) — otherwise card 2 would still demand an
     * answer for an action David just always-allowed. MODEL pendings
     * are never cancelled: a grant never applies to them, so their
     * card still stands. Each cancelled pending's continuation gets
     * null (its caller fails honestly as "not confirmed") and its
     * card is hidden via the host.
     */
    private fun maybeCreateGrant(p: PendingConfirmation) {
        if (p.origin != RequestOrigin.DIRECT_USER) return
        if (prefs.confirmStrictness == 2) return
        if (!AuthGrantStore.isGrantEligible(p.action.key)) return
        if (p.action.scope.isBlank()) return
        val label = p.grantOffer ?: return
        if (grants.grant(p.action.key, p.action.scope, label)) {
            audit(
                "GRANT_CREATED",
                p.action,
                "always-allow grant stored for ${p.action.scope}",
            )
            cancelParkedForNewGrant(p.action.key, p.action.scope)
        }
    }

    /**
     * Retroactive grant effect (item 10): cancel every still-parked
     * DIRECT_USER pending for the same (key + scope) — David just
     * always-allowed this action for this target, so re-asking it on
     * an already-parked card is a stale question. Called only from
     * [maybeCreateGrant], inside the gate monitor (@Synchronized
     * re-entry is safe). MODEL pendings are left alone: grants never
     * apply to them.
     */
    private fun cancelParkedForNewGrant(actionKey: String, scope: String) {
        val superseded = pending.values.filter {
            it.action.key == actionKey &&
                it.action.scope == scope &&
                it.origin == RequestOrigin.DIRECT_USER
        }
        for (parked in superseded) {
            pending.remove(parked.id)
            audit(
                "CONFIRM_CANCELLED",
                parked.action,
                "pending ${parked.id.take(8)} superseded by a new always-allow grant " +
                    "for $actionKey / $scope",
            )
            try {
                parked.onToken(null)
            } catch (_: Exception) {
            }
            try {
                confirmationHost?.hideConfirmation(parked.id)
            } catch (_: Exception) {
            }
        }
    }

    private fun issueToken(actionKey: String): AuthToken {
        val token = AuthToken(
            id = UUID.randomUUID().toString(),
            actionKey = actionKey,
            expiresAt = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS,
        )
        issuedTokens[token.id] = token
        return token
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        issuedTokens.entries.removeIf { it.value.expiresAt <= now }
        val expired = pending.filterValues { now - it.requestedAt > CONFIRM_TIMEOUT_MS }
        for ((id, p) in expired) {
            pending.remove(id)
            audit("CONFIRM_EXPIRED", p.action, "pending ${id.take(8)} expired")
            p.onToken(null)
        }
        if (expired.isNotEmpty()) {
            for ((id, _) in expired) {
                try {
                    confirmationHost?.hideConfirmation(id)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun audit(decision: String, action: AuthAction, note: String) {
        auditRaw("auth", "$decision ${action.key} — ${action.title}. $note")
    }

    private fun auditRaw(kind: String, text: String) {
        try {
            audit.record(kind, text)
        } catch (_: Exception) {
        }
    }
}
