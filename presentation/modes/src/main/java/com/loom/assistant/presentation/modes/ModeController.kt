package com.loom.assistant.presentation.modes

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomIntents
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.OverlayPermission

/**
 * Phase 4 — appearance-mode state machine (arch §2.3).
 *
 * CHARACTER | ORB | INVISIBLE — one active at a time. Switching is a
 * presentation-layer state change only: no intelligence restart, services
 * keep running headless in invisible mode. Persisted to SharedPreferences.
 *
 * Stage 2A.1 (D1): this object is the SINGLE authoritative applier for every
 * mode switch — Settings cards, the Modes screen, the floating panel,
 * in-app voice, floating voice, and service rebinds all delegate here. The
 * old three implementations (service companion applyMode, floating host
 * switchOverlayMode, raw ACTION_REFRESH_OVERLAY intent) are gone.
 */
enum class AssistantMode { CHARACTER, ORB, INVISIBLE }

/**
 * Stage 2A.1: the five honest outcomes of a mode switch. Spoken and UI
 * acknowledgements are produced ONLY from these — never from a prefs
 * read-back (R1's vacuous verification).
 */
sealed interface ModeResult {
    data object Success : ModeResult
    data class Failed(val reason: String) : ModeResult
    data object PermissionRequired : ModeResult
    data class Unavailable(val reason: String) : ModeResult
}

/**
 * Stage 2A.1: the live service behind the controller (test seam). The
 * LoomOverlayService registers in onCreate and unregisters in onDestroy;
 * the controller never imports platform:services (module deps stay acyclic).
 */
interface ModeBackend {
    /** SYSTEM_ALERT_WINDOW currently granted? */
    fun hasOverlayPermission(): Boolean
    /** Best-effort service start; false when the start call itself threw. */
    fun requestServiceStart(context: Context): Boolean
    /** Live bound mode — the honest state, not prefs. Null if unknown. */
    fun boundMode(): AssistantMode?
    /** The mode's view is actually attached to the window. */
    fun isViewAttached(mode: AssistantMode): Boolean
}

object ModeController {

    private const val PREFS = "loom_modes"
    private const val KEY_MODE = "active_mode"

    /** Registered by LoomOverlayService.onCreate; null when the service is dead. */
    @Volatile
    private var backend: ModeBackend? = null

    /**
     * Stage 2C (F2): post-verification hook. Invoked ONLY after a mode
     * switch has been verified against live bound state (the ok path of
     * [verifyAndReport]) — never on a failed or unverified switch. The
     * single authoritative applier ([applyMode]) is unchanged; this is a
     * notification for the interaction layer's anchor plumbing, not a
     * second applier.
     */
    @Volatile
    var onVerifiedModeChange: ((AssistantMode) -> Unit)? = null

    /**
     * Lifecycle plan §E.8 — additive observer hook. Invoked (verified only,
     * alongside [onVerifiedModeChange]) after a mode switch has been verified
     * against live bound state. Lets the lifecycle controller observe mode
     * changes without touching [applyMode] or the single-slot listener owned
     * by LoomOverlayService.
     */
    private val verifiedModeObservers = java.util.concurrent.CopyOnWriteArrayList<(AssistantMode) -> Unit>()

    fun addVerifiedModeObserver(observer: (AssistantMode) -> Unit) {
        verifiedModeObservers.add(observer)
    }

    fun removeVerifiedModeObserver(observer: (AssistantMode) -> Unit) {
        verifiedModeObservers.remove(observer)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun registerBackend(b: ModeBackend?) {
        backend = b
    }

    /**
     * Stage 2A.1 (D6): the honest "active" read — live bound state from the
     * running service. Null when the service isn't running or can't say.
     * UI "● Active" rows and service cards must read this, not getMode().
     */
    fun boundMode(): AssistantMode? =
        try {
            backend?.boundMode()
        } catch (_: Exception) {
            null
        }

    /**
     * Automatic recovery (Nov 6): is [mode]'s view actually showing —
     * the second half of the "assistant is back" check, next to
     * [boundMode]. False when the service isn't running or can't say.
     */
    fun isModeViewAttached(mode: AssistantMode): Boolean =
        try {
            backend?.isViewAttached(mode) ?: false
        } catch (_: Exception) {
            false
        }

    fun getMode(context: Context): AssistantMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AssistantMode.ORB.name)
        return try {
            AssistantMode.valueOf(name ?: AssistantMode.ORB.name)
        } catch (_: IllegalArgumentException) {
            AssistantMode.ORB
        }
    }

    fun setMode(context: Context, mode: AssistantMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
        LoomEventBus.post(LoomStateEvent.ModeChanged(mode.name))
    }

    /**
     * Stage 2A.1 (D1): the one authoritative mode applier. Steps:
     * 1. Permission check FIRST — denial reports PermissionRequired with NO
     *    pref write and no claim (R7).
     * 2. Persist + bus (dispatch is synchronous on the main thread, so a live
     *    backend has already rebound when step 3 runs — load-bearing; see
     *    LoomEventBus.post).
     * 3. Verify against LIVE bound state (R1): boundMode() == target AND the
     *    view is attached. Anything else is Failed.
     * Cold start (no live backend): best-effort service start, then a
     * non-blocking handler poll for backend registration; timeout or a
     * failed start reports Unavailable. The callback always runs on the main
     * thread, exactly once.
     */
    fun applyMode(context: Context, target: AssistantMode, cb: (ModeResult) -> Unit) {
        val appContext = context.applicationContext
        mainHandler.post {
            val live = backend
            val permitted = try {
                live?.hasOverlayPermission() ?: OverlayPermission.granted(appContext)
            } catch (_: Exception) {
                false
            }
            if (!permitted) {
                cb(ModeResult.PermissionRequired)
                return@post
            }
            setMode(appContext, target)
            val b = backend
            if (b == null) {
                coldStart(appContext, target, cb)
                return@post
            }
            verifyAndReport(b, target, cb)
        }
    }

    private fun verifyAndReport(b: ModeBackend, target: AssistantMode, cb: (ModeResult) -> Unit) {
        val ok = try {
            b.boundMode() == target && b.isViewAttached(target)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            cb(ModeResult.Success)
            // Stage 2C (F2): post-verification anchor hook — verified only.
            try {
                onVerifiedModeChange?.invoke(target)
            } catch (_: Exception) {
            }
            // Lifecycle plan §E.8: additive observers (controller audit trail).
            for (observer in verifiedModeObservers) {
                try {
                    observer(target)
                } catch (_: Exception) {
                }
            }
        } else cb(ModeResult.Failed("the new look didn't appear"))
    }

    private fun coldStart(context: Context, target: AssistantMode, cb: (ModeResult) -> Unit) {
        val started = try {
            val i = android.content.Intent(LoomIntents.ACTION_START_OVERLAY).apply {
                setClassName(
                    context.packageName,
                    "com.loom.assistant.platform.services.LoomOverlayService",
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!started) {
            cb(ModeResult.Unavailable("the floating assistant failed to start"))
            return
        }
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                val b = backend
                when {
                    b != null -> verifyAndReport(b, target, cb)
                    attempts >= 20 -> cb(ModeResult.Unavailable("the floating assistant did not start"))
                    else -> {
                        attempts++
                        mainHandler.postDelayed(this, 100)
                    }
                }
            }
        }
        mainHandler.postDelayed(poll, 100)
    }

    /** Human label for UI. */
    fun label(mode: AssistantMode): String = when (mode) {
        AssistantMode.CHARACTER -> "Character Mode"
        AssistantMode.ORB -> "Orb Mode"
        AssistantMode.INVISIBLE -> "Invisible Mode"
    }

    /**
     * Stage 2A.1 (D2): the single wording source for the five outcomes —
     * spoken acks (voice manager) and UI toasts (modes screen) say the same
     * thing. Short label ("Orb") without the " Mode" suffix.
     *
     * Blank-reply fix (2026-11-06): fixed plain sentences — the old Failed
     * branch echoed the internal reason verbatim ("view did not attach"),
     * so any future internal reason could leak jargon to David. The
     * reason still rides in ModeResult for diagnostics; the user never
     * sees it.
     */
    fun ackText(result: ModeResult, shortLabel: String): String = when (result) {
        ModeResult.Success -> "$shortLabel mode active."
        is ModeResult.Failed -> "Couldn't switch modes — the new look didn't appear. Try again in a moment."
        ModeResult.PermissionRequired ->
            "I need the 'display over other apps' permission first — open Assistant Modes to grant it."
        is ModeResult.Unavailable -> "LOOM's floating assistant isn't available right now — try again in a moment."
    }

    /** Short spoken label ("Orb" / "Character" / "Invisible"). */
    fun shortLabel(mode: AssistantMode): String = label(mode).removeSuffix(" Mode")

    /**
     * Whether the overlay service marked itself running (written by the
     * service; read here so the UI never imports platform:services).
     */
    fun isOverlayRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("overlay_running", false)
}
