package com.loom.assistant.platform.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.platform.apps.AppCatalogueHolder
import com.loom.assistant.platform.apps.AppResolution

/**
 * Device-evidence fix (2026-11-05): the single real app-launch path
 * shared by typed chat, in-app voice and floating voice.
 *
 * The defect: typed "can you open my Youtube" reached the LLM, which
 * first claimed "Opening YouTube." (nothing opened) and then, when
 * challenged, denied that launch exists at all — even though Stage 2C
 * wired a real launch path (LoomAccessibilityService.launchApp via
 * AuthGate). This helper is the reachable front door to that path:
 * resolve the app by label, refuse sensitive apps BEFORE any
 * confirmation card (a confirmed-then-refused launch would be worse),
 * require the accessibility service, request the CONFIRM-class
 * app.launch token, then launch — and report the honest outcome for
 * every branch. It never claims an open it did not perform, and it
 * never denies that launch exists.
 *
 * Sensitive apps (banking / financial / authenticator) are guidance
 * only per the Stage 2C hard rule: the refusal names the guidance
 * alternative instead of leaving the user with a dead end.
 *
 * MASTER F5 — foreground short-circuit: when the requested app is
 * already in the foreground, the launch is skipped entirely (no
 * card, no launch) and the report is "Already in <label>." The
 * check runs AFTER the sensitive pre-check and the service check,
 * and BEFORE any gate request — a sensitive app never reaches it,
 * and an already-foreground app never cards.
 *
 * Master §8 — [origin] is who asked: [RequestOrigin.DIRECT_USER] for
 * David's own spoken/typed command, [RequestOrigin.MODEL] for a
 * model-proposed launch. It rides into [AuthGate.request] so a stored
 * "always allow" grant can skip the card only for his own commands —
 * never for model requests. Default is MODEL (safe: always a card).
 * The sensitive pre-check below still runs BEFORE any grant could
 * apply, and the gate's hard-deny runs before the grant check — a
 * grant can never open a sensitive app.
 */
object LoomAppLauncher {

    /**
     * Screen confirmation (2026-11-10): how long to wait for the
     * launched app to reach the foreground before reporting. A
     * startActivity that merely does not throw is not proof the app
     * opened, so the report waits for the foreground check — 2 s
     * fits comfortably inside the dispatcher's LAUNCH_WAIT_MS.
     */
    const val FOREGROUND_CHECK_INTERVAL_MS = 250L
    const val FOREGROUND_CHECK_DEADLINE_MS = 2_000L

    /**
     * Screen confirmation (2026-11-10): the report for a launch, as
     * a pure decision — "Opened X." ONLY when the app was actually
     * seen in the foreground. The unconfirmed text deliberately does
     * NOT start with "Opened " so the dispatcher
     * (text.startsWith("Opened ")) and the launch promotion
     * (noteLaunchOutcomeFromSpeech) treat it as failure, never as
     * success.
     */
    fun openedReport(label: String, foregroundConfirmed: Boolean): String =
        if (foregroundConfirmed) "Opened $label."
        else "I tried to open $label but it didn't come to the foreground — " +
            "I couldn't confirm it opened."

    /**
     * Polls the foreground package on the main Handler until [pkg]
     * is foreground or the deadline passes, then reports through
     * [openedReport]. The root read and the Handler both live on
     * the main thread.
     */
    private fun reportOpenedWhenForeground(
        service: LoomAccessibilityService,
        pkg: String,
        label: String,
        report: (String) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + FOREGROUND_CHECK_DEADLINE_MS
        fun poll() {
            if (isAlreadyForeground(service, pkg)) {
                report(openedReport(label, true))
            } else if (SystemClock.uptimeMillis() >= deadline) {
                report(openedReport(label, false))
            } else {
                main.postDelayed({ poll() }, FOREGROUND_CHECK_INTERVAL_MS)
            }
        }
        poll()
    }

    /**
     * MASTER F5 — true when [pkg] is the foreground package right
     * now. Cheap: one root read, no tree walk; the node is recycled.
     * Any failure reads as "not foreground" (the launch proceeds
     * exactly as before).
     */
    private fun isAlreadyForeground(service: LoomAccessibilityService, pkg: String): Boolean {
        val root = try {
            service.safeRootInActiveWindow()
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            root.packageName?.toString() == pkg
        } catch (_: Exception) {
            false
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * AOIE order (a) — launch with a pre-issued token (the
     * dispatcher's sequenced app.launch ALLOW token for a covered
     * LAUNCH_APP step). Same resolve + sensitive pre-check +
     * service check as [launch], but NO second gate request: the
     * token was already issued under the live plan cover, and
     * requesting again would put a CONFIRM card on a covered step.
     * The token is consumed by the sink exactly once.
     */
    fun launchWithToken(
        context: Context,
        appName: String,
        tokenId: String,
        onResult: (String) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        fun report(msg: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) onResult(msg)
            else main.post { onResult(msg) }
        }
        val name = appName.trim()
        if (name.isBlank()) {
            report("Which app do you want me to open?")
            return
        }
        val catalogue = AppCatalogueHolder.getOrCreate(context)
        val app = when (val resolved = catalogue.resolveByName(name)) {
            is AppResolution.Found -> resolved.app
            is AppResolution.NotFound -> {
                report("I couldn't find an app called '$name' on this phone.")
                return
            }
            is AppResolution.Ambiguous -> {
                val labels = resolved.candidates.joinToString(", ") { it.label }
                report(
                    "I found more than one app matching '$name': $labels. " +
                        "Which one did you mean?",
                )
                return
            }
        }
        val pkg = app.packageName
        val label = app.label
        // Same sensitive pre-check as launch(): a covered step in a
        // sensitive app must never reach the sink, token or no token.
        if (ScreenDataPolicy.isSensitivePackage(pkg)) {
            report(
                "That's a banking, financial, or authenticator app, so I can " +
                    "only guide you to it — I can't open it for you. Say " +
                    "'guide me to $label' and I'll point to it.",
            )
            return
        }
        val service = LoomAccessibilityService.instance
        if (service == null) {
            report(
                "The accessibility service isn't running, so I can't open " +
                    "apps yet. Enable it in Settings → Accessibility → LOOM, " +
                    "then try again.",
            )
            return
        }
        // MASTER F5 — already foreground: skip the launch, report success.
        if (isAlreadyForeground(service, pkg)) {
            report("Already in $label.")
            return
        }
        when (val result = service.launchApp(pkg, tokenId)) {
            // Screen confirmation (2026-11-10): Accepted only means
            // startActivity did not throw — the app is reported
            // opened only once it is seen in the foreground.
            is GestureResult.Accepted -> reportOpenedWhenForeground(service, pkg, label, ::report)
            is GestureResult.Refused -> report("I couldn't open $label: ${result.reason}")
        }
    }

    fun launch(
        context: Context,
        appName: String,
        onResult: (String) -> Unit,
        origin: RequestOrigin = RequestOrigin.MODEL,
    ) {
        val main = Handler(Looper.getMainLooper())
        fun report(msg: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) onResult(msg)
            else main.post { onResult(msg) }
        }
        val name = appName.trim()
        if (name.isBlank()) {
            report("Which app do you want me to open?")
            return
        }
        // Master §1: resolve through the shared AppCatalogue — exact
        // label first, whole-word matching, Ambiguous asks instead of
        // guessing. The old exact→`contains` fallback is gone: it could
        // open YouTube Music for "YouTube" (and vice versa) depending on
        // query order.
        val catalogue = AppCatalogueHolder.getOrCreate(context)
        val app = when (val resolved = catalogue.resolveByName(name)) {
            is AppResolution.Found -> resolved.app
            is AppResolution.NotFound -> {
                report("I couldn't find an app called '$name' on this phone.")
                return
            }
            is AppResolution.Ambiguous -> {
                val labels = resolved.candidates.joinToString(", ") { it.label }
                report(
                    "I found more than one app matching '$name': $labels. " +
                        "Which one did you mean?",
                )
                return
            }
        }
        val pkg = app.packageName
        val label = app.label
        // Sensitive pre-check before any confirmation card: launchApp
        // would refuse anyway, but only after the user confirmed.
        if (ScreenDataPolicy.isSensitivePackage(pkg)) {
            report(
                "That's a banking, financial, or authenticator app, so I can " +
                    "only guide you to it — I can't open it for you. Say " +
                    "'guide me to $label' and I'll point to it.",
            )
            return
        }
        val service = LoomAccessibilityService.instance
        if (service == null) {
            report(
                "The accessibility service isn't running, so I can't open " +
                    "apps yet. Enable it in Settings → Accessibility → LOOM, " +
                    "then try again.",
            )
            return
        }
        // MASTER F5 — already foreground: skip the card AND the
        // launch, report success.
        if (isAlreadyForeground(service, pkg)) {
            report("Already in $label.")
            return
        }
        val gate = AuthGate.getOrNull()
        if (gate == null) {
            report("Authorization isn't available right now, so I can't open $label.")
            return
        }
        gate.request(AuthActionRegistry.appLaunch(pkg, label), origin = origin) { token ->
            if (token == null) {
                report("Not confirmed — I didn't open $label.")
                return@request
            }
            when (val result = service.launchApp(pkg, token.id)) {
                // Screen confirmation (2026-11-10): Accepted only
                // means startActivity did not throw — the app is
                // reported opened only once it is seen in the foreground.
                is GestureResult.Accepted -> reportOpenedWhenForeground(service, pkg, label, ::report)
                is GestureResult.Refused -> report("I couldn't open $label: ${result.reason}")
            }
        }
    }
}
