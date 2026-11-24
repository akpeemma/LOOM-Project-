package com.loom.assistant.platform.services

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.lifecycle.AutomaticRecovery
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.lifecycle.RecoveryDecision
import com.loom.assistant.lifecycle.RecoveryRefusal
import com.loom.assistant.lifecycle.RecoverySignals
import com.loom.assistant.presentation.modes.AssistantMode
import com.loom.assistant.presentation.modes.ModeController

/**
 * Automatic recovery (Nov 6) — the Android runner.
 *
 * Trigger: app launch (MainActivity calls [attemptIfNeeded] after the
 * lifecycle routing). There is no trigger while the app is dead — an
 * ordinary app cannot run code after its own process is killed, and
 * Android's background-start limits forbid starting the floating
 * assistant from the background. What the phone itself restarts on
 * its own (START_STICKY) still applies; this runner covers the case
 * the phone did NOT restart, the next time David opens the app.
 *
 * What one attempt does: start the floating assistant (a no-op when
 * it is already running — the start re-binds the last look), then
 * wait up to 3 s — the same window Home uses — for the floating
 * assistant to be back in its last look and for the helper to
 * reconnect on its own. Success is claimed ONLY from that live
 * check, never from the start call. The helper is never switched on
 * by code: only the system reconnects it, and only David can turn
 * it on in Settings.
 *
 * Bounds: at most [AutomaticRecovery.MAX_ATTEMPTS] attempts, waits of
 * 1 s → 2 s → 4 s, failure count persisted across launches. A failed
 * attempt schedules the next one ONLY through the loop below — a
 * failure never starts a new run by itself. Toggling the setting off
 * cancels a pending run ([cancelPending] + the re-read at each step).
 *
 * Plain-words rule: no user-facing string lives here (reports are
 * built by [AutomaticRecovery]); log lines are diagnostics and carry
 * no jargon either.
 */
object AutomaticRecoveryRunner {

    /**
     * Wait for the floating assistant + helper after a start, polling
     * every [REBIND_POLL_MS] — the same 3 s window Home waits for a
     * system reconnect after the app opens.
     */
    private const val REBIND_WAIT_MS: Long = 3_000L
    private const val REBIND_POLL_MS: Long = 250L

    private const val LOG_TAG = "LoomRecovery"

    @Volatile
    private var worker: Thread? = null

    /** Bumped by [cancelPending]; a run whose generation moved stands down. */
    @Volatile
    private var generation = 0

    private sealed interface AttemptOutcome {
        data class Recovered(val helperLive: Boolean) : AttemptOutcome
        data object Failed : AttemptOutcome
    }

    /**
     * Launch-time check. Decides once from fresh signals; an allowed
     * attempt runs on a background thread (sleeps and waits never
     * touch the main thread). A run already in progress is left alone
     * — one run at a time, never stacked.
     */
    fun attemptIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val store = AutomaticRecoveryStore.get(appContext)
        val signals = readSignals(appContext, store)
        when (val decision = AutomaticRecovery.decide(signals)) {
            is RecoveryDecision.Attempt -> startWorker(appContext, store)
            is RecoveryDecision.Refused -> {
                log("recovery refused: ${decision.reason}")
                reportRefusal(store, decision.reason)
            }
            RecoveryDecision.NothingToDo -> {
                log("recovery not needed: floating assistant and helper are up")
            }
            RecoveryDecision.GiveUp -> {
                log("recovery gave up: failure count ${store.failureCount()}")
                reportGiveUp(store)
            }
        }
    }

    /**
     * Cancel any pending recovery run — called when the setting is
     * turned off. The worker thread is interrupted out of its sleep
     * or wait and stands down on its generation check; a run that
     * already finished is untouched.
     */
    fun cancelPending() {
        synchronized(this) {
            generation++
            worker?.interrupt()
            worker = null
        }
        log("pending recovery cancelled")
    }

    private fun startWorker(appContext: Context, store: AutomaticRecoveryStore) {
        val gen: Int
        synchronized(this) {
            if (worker?.isAlive == true) {
                log("recovery run already in progress")
                return
            }
            gen = generation
            val thread = Thread(
                { runLoop(appContext, store, gen) },
                "loom-auto-recovery",
            )
            worker = thread
            thread.start()
        }
    }

    /**
     * The bounded loop: sleep → re-read the world → attempt, at most
     * MAX_ATTEMPTS times. The re-read before every attempt is what
     * makes toggling the setting off stop a run mid-wait, and what
     * keeps a run from ever starting after David stopped LOOM himself.
     */
    private fun runLoop(
        appContext: Context,
        store: AutomaticRecoveryStore,
        gen: Int,
    ) {
        try {
            var attemptNumber = 1
            while (attemptNumber <= AutomaticRecovery.MAX_ATTEMPTS) {
                if (gen != generation) return
                val delayMs =
                    AutomaticRecovery.DELAY_BEFORE_ATTEMPT_MS[attemptNumber - 1]
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return
                }
                if (gen != generation) return
                val signals = readSignals(appContext, store)
                when (val decision = AutomaticRecovery.decide(signals)) {
                    is RecoveryDecision.Attempt -> {
                        log("attempt ${decision.attemptNumber}: starting the floating assistant")
                        when (val outcome = tryAttempt(appContext, signals)) {
                            is AttemptOutcome.Recovered -> {
                                AutomaticRecovery.noteRecovered(store)
                                reportRecovered(store, signals.lastModeName, outcome.helperLive)
                                log(
                                    "recovered in ${signals.lastModeName} look, " +
                                        "helper live=${outcome.helperLive}",
                                )
                                return
                            }
                            AttemptOutcome.Failed -> {
                                val more = AutomaticRecovery.noteAttemptFailed(store)
                                log("attempt failed: count=${store.failureCount()}")
                                if (!more) {
                                    reportGiveUp(store)
                                    return
                                }
                                attemptNumber++
                            }
                        }
                    }
                    is RecoveryDecision.Refused -> {
                        log("recovery refused mid-run: ${decision.reason}")
                        reportRefusal(store, decision.reason)
                        return
                    }
                    RecoveryDecision.NothingToDo -> {
                        log("recovery not needed any more: everything is back")
                        return
                    }
                    RecoveryDecision.GiveUp -> {
                        reportGiveUp(store)
                        return
                    }
                }
            }
        } finally {
            // Identity check, not name: a newer run may have started
            // after this one was cancelled — never clear its slot.
            if (worker === Thread.currentThread()) worker = null
        }
    }

    /**
     * One attempt: start the floating assistant, then poll for up to
     * 3 s. Recovered means the floating assistant is back in its last
     * look — verified live, never assumed from the start call. The
     * helper flag rides along for the report only: a missing helper
     * never fails the attempt, because only the system can reconnect
     * it and the user-facing fix is the toggle.
     */
    private fun tryAttempt(
        appContext: Context,
        signals: RecoverySignals,
    ): AttemptOutcome {
        try {
            LoomOverlayService.start(appContext)
        } catch (_: Exception) {
            return AttemptOutcome.Failed
        }
        val deadline = SystemClock.elapsedRealtime() + REBIND_WAIT_MS
        var floatingBack = false
        var helperLive = false
        while (SystemClock.elapsedRealtime() < deadline) {
            floatingBack = isAssistantRunning(appContext, signals.lastModeName)
            helperLive = isHelperLive()
            if (floatingBack && helperLive) break
            try {
                Thread.sleep(REBIND_POLL_MS)
            } catch (_: InterruptedException) {
                return AttemptOutcome.Failed
            }
        }
        floatingBack = isAssistantRunning(appContext, signals.lastModeName)
        helperLive = isHelperLive()
        return if (floatingBack) AttemptOutcome.Recovered(helperLive) else AttemptOutcome.Failed
    }

    /** Fresh signals for one decision — every read is live, never cached. */
    private fun readSignals(
        appContext: Context,
        store: AutomaticRecoveryStore,
    ): RecoverySignals {
        val lastModeName = ModeController.getMode(appContext).name
        return RecoverySignals(
            enabled = store.isAutoRecoverEnabled(),
            intentionalStop = store.readIntentionalStop(),
            failureCount = store.failureCount(),
            a11yFlagOn = isA11yFlagOn(appContext),
            overlayPermissionGranted = try {
                OverlayPermission.granted(appContext)
            } catch (_: Exception) {
                false
            },
            lifecycleActive = try {
                LifecycleController.currentState() == LifecycleState.ACTIVE
            } catch (_: Exception) {
                false
            },
            assistantRunning = isAssistantRunning(appContext, lastModeName),
            helperLive = isHelperLive(),
            lastModeName = lastModeName,
        )
    }

    /**
     * Floating assistant back in its last look: the assistant is
     * running AND the last look is the live one AND its view is
     * showing. Any one of the three missing means "not back".
     */
    private fun isAssistantRunning(appContext: Context, lastModeName: String): Boolean {
        if (!LoomOverlayService.isRunning) return false
        val mode = try {
            AssistantMode.valueOf(lastModeName)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            ModeController.boundMode() == mode &&
                ModeController.isModeViewAttached(mode)
        } catch (_: Exception) {
            false
        }
    }

    /** Live helper connection — never the Settings flag on its own. */
    private fun isHelperLive(): Boolean = try {
        LoomAccessibilityService.isLive()
    } catch (_: Exception) {
        false
    }

    /** The Settings flag ONLY — same read Home and Settings use. Never liveness by itself. */
    private fun isA11yFlagOn(appContext: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(
                appContext,
                LoomAccessibilityService::class.java,
            ).flattenToString()
            enabled.split(':').any { it.equals(me, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    private fun reportRecovered(
        store: AutomaticRecoveryStore,
        lastModeName: String,
        helperLive: Boolean,
    ) {
        val mode = try {
            AssistantMode.valueOf(lastModeName)
        } catch (_: IllegalArgumentException) {
            AssistantMode.ORB
        }
        val label = ModeController.shortLabel(mode)
        val (kind, text) = if (helperLive) {
            AutomaticRecovery.successReportPair(label)
        } else {
            AutomaticRecovery.partialReportPair(label)
        }
        store.setLastReport(text, kind)
        postReported()
    }

    private fun reportRefusal(store: AutomaticRecoveryStore, reason: RecoveryRefusal) {
        val pair = AutomaticRecovery.refusalReport(reason) ?: return
        store.setLastReport(pair.second, pair.first)
        postReported()
    }

    private fun reportGiveUp(store: AutomaticRecoveryStore) {
        val pair = AutomaticRecovery.giveUpReportPair()
        store.setLastReport(pair.second, pair.first)
        postReported()
    }

    /** Tell Home a fresh report is waiting in the store. Never throws. */
    private fun postReported() {
        try {
            LoomEventBus.post(LoomStateEvent.RecoveryReported)
        } catch (_: Exception) {
        }
    }

    private fun log(message: String) {
        try {
            android.util.Log.i(LOG_TAG, message)
        } catch (_: Exception) {
        }
    }
}
