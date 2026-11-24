package com.loom.assistant.lifecycle

import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent

/**
 * LOOM lifecycle — authoritative Layer-1 state machine (plan §4.1).
 *
 * Application-scoped singleton, owned by the Application object (correction 5:
 * it must survive the overlay service's teardown). All lifecycle transitions
 * run through ONE synchronized transition function; every verified transition
 * is audit-logged, bus-published ([LoomStateEvent.LifecycleChanged]) and
 * persisted atomically.
 *
 * Two-layer model (plan §4.1):
 * - Layer 1 (lifecycle) is written ONLY here.
 * - Layer 2 (session) is written ONLY by VoiceSessionManager; it arrives via
 *   [reportSessionState] as a READ-ONLY mirror and never drives lifecycle.
 *   The observable (lifecycle, session) pair is computed live, never stored,
 *   so it cannot go stale (impossible state #5, prevented structurally).
 *
 * HARD RULE — capability ≠ authority: this controller NEVER issues, holds,
 * or bypasses AuthGate tokens. It coordinates teardown and reads lifecycle
 * state; purge destroys only. It is not a second authorization system.
 */
object LifecycleController {

    // ------------------------------------------------------------------
    // Transition matrix (plan §4.1). Anything not listed here is refused.
    // ------------------------------------------------------------------
    private val allowed: Map<LifecycleState, Set<LifecycleState>> = mapOf(
        LifecycleState.ACTIVE to setOf(
            LifecycleState.ASSISTANT_MODE_INACTIVE, // DEACTIVATE ASSISTANT MODE
            LifecycleState.SHUTTING_DOWN,           // SHUT DOWN LOOM (confirmed)
        ),
        LifecycleState.ASSISTANT_MODE_INACTIVE to setOf(
            LifecycleState.REACTIVATING,            // launcher-icon tap
            LifecycleState.SHUTTING_DOWN,           // SHUT DOWN LOOM (confirmed)
        ),
        LifecycleState.REACTIVATING to setOf(
            LifecycleState.ACTIVE,                  // §4.4 verification passes
            LifecycleState.ASSISTANT_MODE_INACTIVE, // verification fails safely
        ),
        // SHUTTING_DOWN exits are handled explicitly in transitionTo:
        //  → SHUT_DOWN only via the §4.3 verification gate (all checks pass)
        //  → ACTIVE only via requestShutdownRecovery() (user-initiated,
        //    pre-destructive only). No other exit exists.
        LifecycleState.SHUTTING_DOWN to emptySet(),
        LifecycleState.SHUT_DOWN to setOf(
            LifecycleState.REACTIVATING,            // launcher-icon tap
        ),
    )

    private val lock = Any()

    private var state: LifecycleState = LifecycleState.ACTIVE
    private var persistence: LifecyclePersistence? = null

    // ---- read-only mirrors / reported truths (never drive lifecycle) ----
    private var sessionMirror: String = "IDLE"
    private var micLive: Boolean = false
    private var serviceLive: Boolean = false
    private var viewsAttached: Boolean = false
    private var lastVerifiedMode: String? = null

    /** Destructive §4.3 steps (5–10) recorded during SHUTTING_DOWN. Cleared
     * on SHUTTING_DOWN entry. Non-empty ⇒ recovery to ACTIVE is REFUSED. */
    private val destructiveSteps = mutableSetOf<String>()

    /** Set when the persisted record said SHUTTING_DOWN at launch: the
     * previous shutdown was interrupted; verification must run read-only. */
    private var interruptedShutdown: Boolean = false

    private var sessionTeardownRequest: (() -> Unit)? = null

    /**
     * Automatic recovery (Nov 6): explicit-user-return hook. Invoked
     * (outside the lock, best-effort) when the user deliberately returns
     * LOOM to ACTIVE — a reactivation success, or a pre-destructive
     * shutdown recovery. The intentional-stop marker lives outside this
     * controller (Android storage); the hook lets its owner clear the
     * marker exactly here, so a deliberate return never leaves a stale
     * marker behind that would block a later start. Never fires on a
     * refusal or a failure — only on a verified return to ACTIVE.
     */
    private var explicitReactivationCallback: (() -> Unit)? = null

    // ---- audit log (bounded) ----
    private val audit = ArrayDeque<LifecycleAuditEntry>()
    private var auditSeq = 0L
    private val auditCap = 300

    // ==================================================================
    // Exact public API (other workers code against these — do not rename)
    // ==================================================================

    /** Current Layer-1 lifecycle state. */
    fun currentState(): LifecycleState = synchronized(lock) { state }

    /**
     * Attempt an atomic, matrix-validated transition.
     *
     * Refuses (returns false, audited, no state change):
     * - any transition not in the §4.1 matrix;
     * - SHUT_DOWN while mic/session/service/views are still reported live;
     * - SHUTTING_DOWN → ACTIVE except via [requestShutdownRecovery]
     *   (user-initiated, pre-destructive only);
     * - REACTIVATING entry while the session mirror is live
     *   (impossible state #2 prevention).
     *
     * Same-state requests are a no-op success (no bus event, no audit spam).
     */
    fun transitionTo(target: LifecycleState): Boolean =
        transitionTo(target, reason = "unspecified")

    /** [transitionTo] with a reason recorded in the audit log and bus event. */
    fun transitionTo(target: LifecycleState, reason: String): Boolean {
        val (ok, from, fireTeardown) = synchronized(lock) {
            decideTransition(target, reason)
        }
        if (fireTeardown) requestSessionTeardown()
        return ok.also {
            if (ok && from != null && from != target) {
                // Bus publication outside the lock: LoomEventBus marshals to
                // the main thread itself.
                LoomEventBus.post(LoomStateEvent.LifecycleChanged(from, target, reason))
            }
        }
    }

    /**
     * True only when lifecycle == ACTIVE. Dispatch sinks and session
     * creation consult this; anything else refuses.
     */
    fun isDispatchAllowed(): Boolean = synchronized(lock) {
        state == LifecycleState.ACTIVE
    }

    /**
     * Accept the voice session's state name as a String — READ-ONLY mirror.
     * Never drives lifecycle. Unknown names are audited and ignored.
     *
     * Impossible state #1 enforcement: a live session mirror
     * (LISTENING/PROCESSING/SPEAKING/PAUSED) arriving while lifecycle !=
     * ACTIVE is dropped as stale, audit-logged as an anomaly, and a session
     * teardown is requested. The lifecycle state is kept; the mirror is
     * forced back to IDLE.
     */
    fun reportSessionState(state: String) {
        val name = state.trim().uppercase()
        val fireTeardown = synchronized(lock) {
            if (name !in SESSION_MIRROR_NAMES) {
                auditLocked("SESSION_MIRROR_UNKNOWN", "ignored unknown session name=$state")
                return@synchronized false
            }
            if (this.state != LifecycleState.ACTIVE && name in LIVE_SESSION_NAMES) {
                auditLocked(
                    "IMPOSSIBLE_STATE",
                    "live session mirror $name while lifecycle=${this.state}; " +
                        "dropped as stale, teardown requested",
                )
                sessionMirror = "IDLE"
                return@synchronized true
            }
            sessionMirror = name
            false
        }
        if (fireTeardown) requestSessionTeardown()
    }

    // ==================================================================
    // Initialization, launch recovery, reactivation
    // ==================================================================

    /**
     * Wire the persisted `lifecycle_state` record. Called once from the
     * Application's onCreate. Safe to call more than once (last wins).
     */
    fun init(persistence: LifecyclePersistence?) {
        synchronized(lock) {
            this.persistence = persistence
            auditLocked("INIT", "persistence=${if (persistence == null) "none" else "attached"}")
        }
    }

    /**
     * Launch routing (plan §4.1 "Interrupted transitions", §4.4/§4.6).
     * Reads the persisted record — never trusts it as proof, only as the
     * recovery-path selector:
     * - persisted SHUTTING_DOWN → state forced to SHUTTING_DOWN with the
     *   interrupted flag; caller must run §4.3 verification READ-ONLY and
     *   report honestly. Never assumes the previous shutdown completed.
     * - persisted REACTIVATING → falls back to ASSISTANT_MODE_INACTIVE
     *   (last verified safe state), never a half-active ACTIVE.
     * - persisted INACTIVE/SHUT_DOWN → in-memory state set; caller drives
     *   the deliberate launcher-icon reactivation path.
     * - persisted ACTIVE or nothing → fresh start.
     */
    fun onAppLaunch(): LaunchDecision {
        val (decision, from, to) = synchronized(lock) {
            val persisted = try {
                persistence?.read()
            } catch (e: Exception) {
                auditLocked("PERSISTENCE_READ_FAILED", e::class.simpleName ?: "error")
                null
            }
            when (persisted) {
                LifecycleState.SHUTTING_DOWN -> {
                    val f = state
                    forceStateLocked(LifecycleState.SHUTTING_DOWN, "launch: persisted SHUTTING_DOWN (interrupted)")
                    interruptedShutdown = true
                    Triple<LaunchDecision, LifecycleState, LifecycleState?>(
                        LaunchDecision.InterruptedShutdown(persisted), f, LifecycleState.SHUTTING_DOWN,
                    )
                }
                LifecycleState.REACTIVATING -> {
                    val f = state
                    forceStateLocked(
                        LifecycleState.ASSISTANT_MODE_INACTIVE,
                        "launch: persisted REACTIVATING interrupted → safe fallback",
                    )
                    Triple<LaunchDecision, LifecycleState, LifecycleState?>(
                        LaunchDecision.RecoveredToInactive, f, LifecycleState.ASSISTANT_MODE_INACTIVE,
                    )
                }
                LifecycleState.ASSISTANT_MODE_INACTIVE -> {
                    val f = state
                    forceStateLocked(
                        LifecycleState.ASSISTANT_MODE_INACTIVE,
                        "launch: persisted INACTIVE",
                    )
                    Triple<LaunchDecision, LifecycleState, LifecycleState?>(
                        LaunchDecision.ReactivationRequired, f, LifecycleState.ASSISTANT_MODE_INACTIVE,
                    )
                }
                LifecycleState.SHUT_DOWN -> {
                    val f = state
                    forceStateLocked(LifecycleState.SHUT_DOWN, "launch: persisted SHUT_DOWN")
                    Triple<LaunchDecision, LifecycleState, LifecycleState?>(
                        LaunchDecision.ReactivationRequired, f, LifecycleState.SHUT_DOWN,
                    )
                }
                else -> {
                    // ACTIVE or never written: default ACTIVE. (A warm singleTop
                    // relaunch keeps whatever the live controller already holds;
                    // forceState is skipped when already ACTIVE to avoid noise.)
                    val f = state
                    var t: LifecycleState? = null
                    if (state != LifecycleState.ACTIVE) {
                        forceStateLocked(LifecycleState.ACTIVE, "launch: no persisted record")
                        t = LifecycleState.ACTIVE
                    }
                    Triple<LaunchDecision, LifecycleState, LifecycleState?>(
                        LaunchDecision.FreshStart, f, t,
                    )
                }
            }
        }
        if (to != null && from != to) {
            LoomEventBus.post(LoomStateEvent.LifecycleChanged(from, to, "launch recovery"))
        }
        return decision
    }

    /**
     * Deliberate launcher-icon-only reactivation (§4.4/§4.6).
     * REACTIVATING → run [checks] → ACTIVE, or back to
     * ASSISTANT_MODE_INACTIVE on any failure (no half-active state).
     * May only be called from ASSISTANT_MODE_INACTIVE or SHUT_DOWN.
     * Reactivation grants no authorization: AuthGate starts empty; every
     * subsequent action needs a fresh token (correction 3).
     */
    fun requestReactivation(checks: List<ReactivationCheck>): ReactivationResult {
        val entry: Triple<Boolean, LifecycleState?, LifecycleState?> = synchronized(lock) {
            if (state != LifecycleState.ASSISTANT_MODE_INACTIVE && state != LifecycleState.SHUT_DOWN) {
                auditLocked("REACTIVATION_REFUSED", "requestReactivation from $state")
                return ReactivationResult.Refused("reactivation requires INACTIVE or SHUT_DOWN, was $state")
            }
            if (sessionMirror != "IDLE" && sessionMirror != "ENDING") {
                auditLocked(
                    "IMPOSSIBLE_STATE",
                    "reactivation requested with live session mirror $sessionMirror; refused",
                )
                return ReactivationResult.Refused("session mirror is $sessionMirror, expected IDLE")
            }
            val (ok, from, _) = decideTransition(LifecycleState.REACTIVATING, "launcher-icon reactivation")
            if (!ok) {
                return ReactivationResult.Refused("could not enter REACTIVATING")
            }
            Triple(true, from, LifecycleState.REACTIVATING)
        }
        val (entered, reactFrom, reactTo) = entry
        if (!entered) return ReactivationResult.Refused("entry failed")
        if (reactFrom != null && reactTo != null && reactFrom != reactTo) {
            LoomEventBus.post(
                LoomStateEvent.LifecycleChanged(reactFrom, reactTo, "launcher-icon reactivation"),
            )
        }

        val failures = mutableListOf<String>()
        for (check in checks) {
            val result = try {
                check.verify()
            } catch (e: Exception) {
                ReactivationCheckResult.Fail("threw ${e::class.simpleName}")
            }
            if (result is ReactivationCheckResult.Fail) {
                failures.add("${check.name}: ${result.reason}")
            }
        }
        return if (failures.isEmpty()) {
            transitionTo(LifecycleState.ACTIVE, "reactivation verification passed")
            fireExplicitReactivation()
            ReactivationResult.Success
        } else {
            transitionTo(
                LifecycleState.ASSISTANT_MODE_INACTIVE,
                "reactivation verification failed: ${failures.joinToString("; ")}",
            )
            ReactivationResult.Failed(failures)
        }
    }

    /**
     * User-initiated SHUTTING_DOWN recovery (closure decision 2).
     * Permitted ONLY when no destructive teardown step (§4.3 steps 5–10)
     * has executed. Destructive started ⇒ REFUSED (no artificial ACTIVE;
     * the §4.3.1 degraded path applies instead).
     * Recovery re-establishes ACTIVE with the session at IDLE; the ended
     * voice session is NOT silently resurrected (caller re-runs the §4.4
     * voice-init subset: mic permission confirmed, pipeline available).
     */
    fun requestShutdownRecovery(): Boolean {
        val ok = synchronized(lock) {
            if (state != LifecycleState.SHUTTING_DOWN) {
                auditLocked("RECOVERY_REFUSED", "not SHUTTING_DOWN (was $state)")
                return false
            }
            if (destructiveSteps.isNotEmpty()) {
                auditLocked(
                    "RECOVERY_REFUSED",
                    "destructive teardown already started: ${destructiveSteps.sorted().joinToString(",")}",
                )
                return false
            }
            sessionMirror = "IDLE"
            interruptedShutdown = false
            decideTransition(
                LifecycleState.ACTIVE,
                "user-initiated shutdown recovery (pre-destructive)",
                allowRecovery = true,
            ).first
        }
        if (ok) {
            LoomEventBus.post(
                LoomStateEvent.LifecycleChanged(
                    LifecycleState.SHUTTING_DOWN,
                    LifecycleState.ACTIVE,
                    "user-initiated shutdown recovery (pre-destructive)",
                ),
            )
            fireExplicitReactivation()
        }
        return ok
    }

    /** True when the last launch found a persisted SHUTTING_DOWN record. */
    fun wasShutdownInterrupted(): Boolean = synchronized(lock) { interruptedShutdown }

    // ==================================================================
    // Reported truths (wired by other workers' integration tracks)
    // ==================================================================

    /** Mic capture live? Reported by the voice pipeline owner. */
    fun reportMicLive(live: Boolean) = synchronized(lock) {
        micLive = live
        auditLocked("MIC_REPORT", "micLive=$live")
    }

    /** Overlay service alive? Reported via the independent liveness probe
     * (never the dying service reporting itself, never getRunningServices). */
    fun reportServiceLive(live: Boolean) = synchronized(lock) {
        serviceLive = live
        auditLocked("SERVICE_REPORT", "serviceLive=$live")
    }

    /** Any overlay view still attached to WindowManager? */
    fun reportViewsAttached(attached: Boolean) = synchronized(lock) {
        viewsAttached = attached
        auditLocked("VIEWS_REPORT", "viewsAttached=$attached")
    }

    /**
     * Record a destructive §4.3 teardown step (5–10: AuthGate purge,
     * delayed-callback cancel, temp-state clear, view removal, pipeline
     * release, service stop) as started. Called by the shutdown
     * orchestrator; blocks [requestShutdownRecovery].
     */
    fun noteDestructiveStep(step: String) = synchronized(lock) {
        destructiveSteps.add(step)
        auditLocked("DESTRUCTIVE_STEP", step)
    }

    /** Verified mode change observed (ModeController hook sink). Read-only
     * note for the audit trail; never drives lifecycle. */
    fun noteModeVerified(modeName: String) = synchronized(lock) {
        lastVerifiedMode = modeName
        auditLocked("MODE_VERIFIED", modeName)
    }

    /**
     * Single callback channel for session→controller repair: invoked
     * (outside the controller lock) when an impossible live-session state
     * is observed so the owning layer can tear the session down.
     */
    fun onSessionTeardownRequested(callback: (() -> Unit)?) = synchronized(lock) {
        sessionTeardownRequest = callback
    }

    /**
     * Register the explicit-user-return hook (see
     * [explicitReactivationCallback]). One callback slot, last wins;
     * null unregisters. Registered from MainActivity.onCreate, before
     * the launch routing (reactivation can fire it).
     */
    fun onExplicitReactivation(callback: (() -> Unit)?) = synchronized(lock) {
        explicitReactivationCallback = callback
    }

    // ==================================================================
    // Reads
    // ==================================================================

    /**
     * The live observable pair, computed on every call — never stored, so
     * it cannot go stale (impossible state #5, prevented structurally).
     */
    fun currentObservable(): LifecycleObservable = synchronized(lock) {
        LifecycleObservable(lifecycle = state, sessionMirror = sessionMirror)
    }

    fun lastVerifiedMode(): String? = synchronized(lock) { lastVerifiedMode }

    /** Copy of the audit log (oldest → newest), capped at 300 entries. */
    fun auditLog(): List<LifecycleAuditEntry> = synchronized(lock) { audit.toList() }

    /** Test-only direct state set (bypasses transition validation). For
     * cross-module harness tests that must force SHUTTING_DOWN/ACTIVE. */
    internal fun forceStateForTest(state: LifecycleState) = synchronized(lock) {
        this.state = state
    }

    /** Test-only reset. Visible to the same-module test harness. */
    internal fun resetForTest() = synchronized(lock) {
        state = LifecycleState.ACTIVE
        sessionMirror = "IDLE"
        micLive = false
        serviceLive = false
        viewsAttached = false
        lastVerifiedMode = null
        destructiveSteps.clear()
        interruptedShutdown = false
        sessionTeardownRequest = null
        explicitReactivationCallback = null
        persistence = null
        audit.clear()
        auditSeq = 0L
    }

    // ==================================================================
    // Internals
    // ==================================================================

    /**
     * Core decision, run under [lock]. Returns (transitioned, from, teardown).
     * Persistence is written here, inside the critical section, after the
     * state is updated; bus publication and teardown callbacks happen in
     * the callers, outside the lock.
     */
    private fun decideTransition(
        target: LifecycleState,
        reason: String,
        allowRecovery: Boolean = false,
    ): Triple<Boolean, LifecycleState?, Boolean> {
        val from = state
        if (from == target) return Triple(true, from, false) // no-op success

        // SHUTTING_DOWN has no matrix exits; its two exits are explicit.
        if (from == LifecycleState.SHUTTING_DOWN) {
            // → SHUT_DOWN only via the §4.3 verification gate: every
            // reported truth must be down.
            if (target == LifecycleState.SHUT_DOWN) {
                val blockers = mutableListOf<String>()
                if (micLive) blockers.add("mic still live")
                if (sessionMirror != "IDLE" && sessionMirror != "ENDING") {
                    blockers.add("session mirror=$sessionMirror")
                }
                if (serviceLive) blockers.add("overlay service still live")
                if (viewsAttached) blockers.add("overlay views still attached")
                if (blockers.isNotEmpty()) {
                    auditLocked(
                        "TRANSITION_REFUSED",
                        "SHUTTING_DOWN→SHUT_DOWN blocked: ${blockers.joinToString(", ")}",
                    )
                    return Triple(false, from, false)
                }
            } else if (allowRecovery && target == LifecycleState.ACTIVE) {
                // User-initiated pre-destructive recovery (requestShutdownRecovery
                // already verified no destructive step ran). Falls through to
                // the shared apply path below.
            } else {
                // → ACTIVE only via requestShutdownRecovery(); anything else
                // (incl. plain transitionTo) is refused — never silent.
                auditLocked("TRANSITION_REFUSED", "SHUTTING_DOWN→$target refused (reason=$reason)")
                return Triple(false, from, false)
            }
        } else if (target !in (allowed[from] ?: emptySet())) {
            auditLocked("TRANSITION_REFUSED", "$from→$target not in matrix (reason=$reason)")
            return Triple(false, from, false)
        }

        // REACTIVATING entry requires the session down (impossible #2).
        if (target == LifecycleState.REACTIVATING &&
            sessionMirror != "IDLE" && sessionMirror != "ENDING"
        ) {
            auditLocked(
                "TRANSITION_REFUSED",
                "→REACTIVATING refused: session mirror=$sessionMirror",
            )
            return Triple(false, from, false)
        }

        state = target
        if (target == LifecycleState.SHUTTING_DOWN) {
            destructiveSteps.clear()
            interruptedShutdown = false
        }
        var teardown = false
        if (target != LifecycleState.ACTIVE && sessionMirror in LIVE_SESSION_NAMES) {
            // Teardown ordering: sessions end before the lifecycle changes
            // (impossible #1 prevention on the transition path).
            auditLocked(
                "IMPOSSIBLE_STATE",
                "live session mirror $sessionMirror on $from→$target; mirror reset, teardown requested",
            )
            sessionMirror = "IDLE"
            teardown = true
        }
        auditLocked("TRANSITION", "$from→$target (reason=$reason)")
        persistLocked(target)
        return Triple(true, from, teardown)
    }

    private fun persistLocked(target: LifecycleState) {
        val p = persistence ?: return
        try {
            p.write(target)
        } catch (e: Exception) {
            auditLocked("PERSISTENCE_WRITE_FAILED", e::class.simpleName ?: "error")
        }
    }

    private fun forceStateLocked(target: LifecycleState, reason: String) {
        val from = state
        if (from == target) return
        state = target
        auditLocked("RECOVERY_FORCE", "$from→$target ($reason)")
        persistLocked(target)
    }

    private fun auditLocked(kind: String, detail: String) {
        auditSeq += 1
        audit.addLast(LifecycleAuditEntry(auditSeq, System.currentTimeMillis(), kind, detail))
        while (audit.size > auditCap) audit.removeFirst()
    }

    private fun requestSessionTeardown() {
        val cb = synchronized(lock) { sessionTeardownRequest }
        try {
            cb?.invoke()
        } catch (_: Exception) {
            // A failing teardown hook must never break the controller.
        }
    }

    /** Invoke the explicit-user-return hook, outside the lock. */
    private fun fireExplicitReactivation() {
        val cb = synchronized(lock) { explicitReactivationCallback }
        try {
            cb?.invoke()
        } catch (_: Exception) {
            // A failing hook must never break the controller.
        }
    }
}

/** One audit-log row. */
data class LifecycleAuditEntry(
    val seq: Long,
    val timeMs: Long,
    val kind: String,
    val detail: String,
)

/** Live observable pair — computed, never stored. */
data class LifecycleObservable(
    val lifecycle: LifecycleState,
    val sessionMirror: String,
)

/** Launch-recovery decision returned by [LifecycleController.onAppLaunch]. */
sealed interface LaunchDecision {
    /** Normal start; controller ACTIVE. */
    data object FreshStart : LaunchDecision

    /** Persisted INACTIVE or SHUT_DOWN: caller must drive the deliberate
     * launcher-icon reactivation path ([LifecycleController.requestReactivation]). */
    data object ReactivationRequired : LaunchDecision

    /** Persisted SHUTTING_DOWN: previous shutdown was interrupted. Caller
     * must run §4.3 verification READ-ONLY and report honestly. */
    data class InterruptedShutdown(val persisted: LifecycleState) : LaunchDecision

    /** Persisted REACTIVATING: fell back to ASSISTANT_MODE_INACTIVE. */
    data object RecoveredToInactive : LaunchDecision
}

/** One named verification step in the reactivation path. */
interface ReactivationCheck {
    val name: String
    fun verify(): ReactivationCheckResult
}

sealed interface ReactivationCheckResult {
    data object Pass : ReactivationCheckResult
    data class Fail(val reason: String) : ReactivationCheckResult
}

/** Outcome of [LifecycleController.requestReactivation]. */
sealed interface ReactivationResult {
    data object Success : ReactivationResult
    data class Failed(val failures: List<String>) : ReactivationResult
    data class Refused(val reason: String) : ReactivationResult
}
