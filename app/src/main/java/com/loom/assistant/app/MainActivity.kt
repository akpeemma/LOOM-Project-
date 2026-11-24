package com.loom.assistant.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import com.loom.assistant.R
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomIntents
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.lifecycle.LaunchDecision
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.lifecycle.ReactivationCheck
import com.loom.assistant.lifecycle.ReactivationCheckResult
import com.loom.assistant.lifecycle.ReactivationResult
import com.loom.assistant.platform.services.AutomaticRecoveryRunner
import com.loom.assistant.platform.services.AutomaticRecoveryStore
import com.loom.assistant.platform.services.LoomAccessibilityService
import com.loom.assistant.platform.services.LoomOverlayService
import com.loom.assistant.presentation.chat.ChatScreenView
import com.loom.assistant.presentation.chat.ConversationsScreenView
import com.loom.assistant.presentation.dashboard.DashboardScreenView
import com.loom.assistant.presentation.designsystem.LoomBottomNav
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomNavigator
import com.loom.assistant.presentation.designsystem.LoomScaffoldScreen
import com.loom.assistant.presentation.home.HomeScreenView
import com.loom.assistant.presentation.home.PseudoControlScreenView
import com.loom.assistant.presentation.modes.AssistantModesScreen
import com.loom.assistant.presentation.panels.DeviceControlScreenView
import com.loom.assistant.presentation.panels.MemoryScreenView
import com.loom.assistant.presentation.panels.MoreScreenView
import com.loom.assistant.presentation.panels.NotificationsScreenView
import com.loom.assistant.presentation.panels.ProjectsScreenView
import com.loom.assistant.presentation.panels.SkillLabScreenView
import com.loom.assistant.presentation.panels.TasksScreenView
import com.loom.assistant.presentation.panels.WhatLoomSeesScreenView
import com.loom.assistant.presentation.settings.SettingsScreenView

/**
 * LOOM entry activity.
 * Phase 3: Home, Quick Voice, Chat/Voice and Conversations are REAL working
 * screens. Phase 4: Assistant Modes is a REAL screen; remaining Phase 5
 * routes still show the honest scaffold.
 */
class MainActivity : Activity() {

    private lateinit var navigator: LoomNavigator
    private lateinit var bottomNav: LoomBottomNav

    private var micPermissionCallback: ((Boolean) -> Unit)? = null
    private var imagePickCallback: ((Uri?) -> Unit)? = null
    private var notificationPermissionCallback: ((Boolean) -> Unit)? = null

    /**
     * Device-test failures (2026-11-11, D-10): the activity never
     * finished on verified SHUT_DOWN — LoomShutdown's docstring
     * claims it finishes when the controller reaches SHUT_DOWN
     * (observed via LifecycleChanged), but no code subscribed to
     * the event, so the dead chat screen (and its ended voice
     * session, whose pipeline was shut down under it) stayed on
     * screen after shutdown. Finish here, so the next open is a
     * clean activity with fresh session objects.
     */
    private val lifecycleListener: (LoomStateEvent) -> Unit = { event ->
        if (event is LoomStateEvent.LifecycleChanged &&
            event.to == LifecycleState.SHUT_DOWN
        ) {
            finish()
        }
    }

    companion object {
        private const val REQ_MIC = 9001
        private const val REQ_IMAGE = 9002
        private const val REQ_OVERLAY = 9003
        private const val REQ_NOTIFICATIONS = 9004

        /** Overlay tap → open LOOM straight into a voice chat. */
        const val EXTRA_START_VOICE = LoomIntents.EXTRA_START_VOICE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Automatic recovery (Nov 6, opt-in, default off): a deliberate
        // return to ACTIVE clears the intentional-stop marker — and resets
        // the failure count — so a later start is never blocked by a stop
        // David himself undid. Registered once, on the application
        // context, so the controller never holds this activity. Registered
        // BEFORE the launch routing below: reactivation can fire it.
        val appContext = applicationContext
        LifecycleController.onExplicitReactivation {
            try {
                val store = AutomaticRecoveryStore.get(appContext)
                store.clearIntentionalStop()
                store.setFailureCount(0)
            } catch (_: Exception) {
            }
        }

        // Lifecycle plan §4.4/§4.6 + map E.7: launcher-icon-only reactivation.
        // The persisted lifecycle_state record is READ for recovery routing
        // (never trusted as proof of SHUT_DOWN); the old write-only shutdown
        // pref is superseded by the record and no longer cleared here.
        LoomEventBus.subscribe(lifecycleListener)
        routeLifecycleOnLaunch()

        // Automatic recovery (Nov 6): if the phone stopped LOOM on its
        // own and the setting is on, try to bring it back now — bounded
        // retries, verified, reported on Home. No-op when the setting is
        // off, when David stopped LOOM himself, or when everything is
        // already running. Runs after the routing above, so the
        // lifecycle state it reads is settled.
        AutomaticRecoveryRunner.attemptIfNeeded(this)

        // Phase 6 — AuthGate wiring (arch §2.14): the gate is initialized once
        // per process; the confirmation host prefers the in-overlay card and
        // falls back to a notification. The accessibility service's structural
        // token check is armed here — no token, no gesture dispatch.
        try {
            AuthGate.init(this)
            AuthGate.get().confirmationHost = OverlayFirstAuthHost(applicationContext)
            LoomAccessibilityService.authGateReady = true
        } catch (_: Exception) {
            // AuthGate stays uninitialized → every gated sink refuses honestly.
        }

        setContentView(R.layout.activity_main)

        val container = findViewById<FrameLayout>(R.id.loom_main_content)
        bottomNav = findViewById(R.id.loom_main_bottom_nav)

        navigator = LoomNavigator(container) { destination ->
            when (destination) {
                // Phase 3 — core interface and conversation (working).
                is LoomDestination.Home ->
                    HomeScreenView(this, navigator::navigate, ::requestMicPermission)
                is LoomDestination.HomeQuickVoice ->
                    HomeScreenView(this, navigator::navigate, ::requestMicPermission)
                        .also { it.startQuickVoice() }
                is LoomDestination.HomeListening ->
                    HomeScreenView(this, navigator::navigate, ::requestMicPermission)
                        .also { it.startQuickVoice() }
                is LoomDestination.ChatText ->
                    ChatScreenView(
                        this, navigator::navigate,
                        ::requestMicPermission, ::pickImage, "text",
                    )
                is LoomDestination.ChatVoice ->
                    ChatScreenView(
                        this, navigator::navigate,
                        ::requestMicPermission, ::pickImage, "voice",
                    )
                is LoomDestination.ChatSwitching ->
                    ChatScreenView(
                        this, navigator::navigate,
                        ::requestMicPermission, ::pickImage, "switching",
                    )
                is LoomDestination.Conversations ->
                    ConversationsScreenView(this, navigator::navigate)
                // Phase 4 — Assistant Modes: REAL screen (mode switching,
                // overlay permission, floating overlay start/stop).
                is LoomDestination.ModeCharacter,
                is LoomDestination.ModeOrb,
                is LoomDestination.ModeInvisible,
                is LoomDestination.PointerGuidance,
                -> AssistantModesScreen(this, ::openOverlaySettings)
                // Phase 5 — intelligence and features: REAL working screens.
                is LoomDestination.Tasks ->
                    TasksScreenView(this, navigator::navigate)
                is LoomDestination.Skills ->
                    SkillLabScreenView(this, navigator::navigate)
                is LoomDestination.Memory ->
                    MemoryScreenView(this, navigator::navigate)
                is LoomDestination.More ->
                    MoreScreenView(this, navigator::navigate)
                is LoomDestination.Projects ->
                    ProjectsScreenView(this, navigator::navigate)
                is LoomDestination.Intelligence ->
                    DashboardScreenView(this, navigator::navigate)
                is LoomDestination.Settings ->
                    SettingsScreenView(this, navigator::navigate, ::openOverlaySettings)
                is LoomDestination.DeviceControl ->
                    DeviceControlScreenView(this, navigator::navigate, ::openOverlaySettings)
                is LoomDestination.WhatLoomSees ->
                    WhatLoomSeesScreenView(this, navigator::navigate)
                is LoomDestination.PseudoControl ->
                    PseudoControlScreenView(this, navigator::navigate)
                is LoomDestination.Notifications ->
                    NotificationsScreenView(this, navigator::navigate, ::requestNotificationPermission)
                // Anything else: honest scaffold.
                else -> LoomScaffoldScreen(this, destination)
            }
        }
        navigator.onDestinationChanged = { destination ->
            if (destination in LoomDestination.bottomNav) {
                bottomNav.select(destination, notify = false)
            }
        }
        bottomNav.onTabSelected = { destination ->
            navigator.navigateTab(destination)
        }

        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
                navigator.navigate(LoomDestination.ChatVoice)
            } else {
                navigator.navigateTab(LoomDestination.start)
            }
        } else {
            // Stage 2A black-screen fix: on recreation (rotation, multi-window,
            // process-death restore) the nav chrome was rebuilt but render()
            // was never called, leaving a black content area under a live
            // bottom nav. Restore the start tab; the user can navigate onward.
            navigator.navigateTab(LoomDestination.start)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Launcher tap while the task is alive: same deliberate path.
        routeLifecycleOnLaunch()
        if (intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) {
            navigator.navigate(LoomDestination.ChatVoice)
        }
    }

    /**
     * Binding survival (2026-11-06): every time LOOM comes to the
     * foreground, log the helper's live state — flag on/off plus
     * actually-live — under the LoomPointer tag. This is the
     * lifecycle rebind hook: Android binds an enabled accessibility
     * service into a live app process on its own, so opening the app
     * IS the rebind attempt (no API exists for the app to bind its
     * own service). The log line is the evidence: it shows whether
     * the system rebind landed after the app opened, and Home's
     * delayed re-checks (HomeScreenView) act on the same signal.
     * Nothing is started here — starting the overlay service would
     * not rebind the helper, and claiming otherwise would be wrong.
     */
    override fun onResume() {
        super.onResume()
        // D-10: returning via Recents (or the app switcher) fires
        // neither onCreate nor onNewIntent — without this, a SHUT_DOWN
        // lifecycle was never reactivated on that path, and every mic
        // tap refused with "Assistant is not active right now."
        // Routing here is idempotent: FreshStart is a no-op while
        // ACTIVE, and beginReactivation is guarded while in flight.
        routeLifecycleOnLaunch()
        try {
            val flagOn = isA11yFlagOn()
            val hostLive = LoomAccessibilityService.isLive()
            android.util.Log.i(
                "LoomPointer",
                "app opened: a11yFlag=$flagOn hostLive=$hostLive " +
                    "lastUnbindAtMs=${LoomAccessibilityService.lastUnbindAtMs} " +
                    "lastUnbindWhy=${LoomAccessibilityService.lastUnbindWhy}",
            )
        } catch (_: Exception) {
            // A logging hook must never break resume.
        }
    }

    override fun onDestroy() {
        try {
            LoomEventBus.unsubscribe(lifecycleListener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** The Settings.Secure flag ONLY — same read Home uses. Never liveness by itself. */
    private fun isA11yFlagOn(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(
                this,
                "com.loom.assistant.platform.services.LoomAccessibilityService",
            ).flattenToString()
            enabled.split(':').any { it.equals(me, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- lifecycle launch routing (plan §4.4/§4.6, map E.7) ----------

    private var reactivationInFlight = false

    /**
     * Deliberate launcher-icon-only reactivation path:
     * INACTIVE/SHUT_DOWN → REACTIVATING → verification → ACTIVE
     * (or safe fallback to ASSISTANT_MODE_INACTIVE — never half-active).
     * No in-app reactivate button, no notification action, no widget.
     */
    private fun routeLifecycleOnLaunch() {
        when (val decision = LifecycleController.onAppLaunch()) {
            is LaunchDecision.ReactivationRequired -> beginReactivation()
            is LaunchDecision.InterruptedShutdown -> recoverFromInterruptedShutdown()
            is LaunchDecision.RecoveredToInactive ->
                android.util.Log.i(
                    "MainActivity",
                    "interrupted reactivation fell back to ASSISTANT_MODE_INACTIVE",
                )
            is LaunchDecision.FreshStart -> {
                // Normal start; the controller is already ACTIVE.
            }
        }
    }

    /**
     * Device-failure fix (2026-11-13, F1): the stranded-SHUTTING_DOWN
     * recovery caller. [LifecycleController.requestShutdownRecovery]
     * was the ONLY exit from a persisted SHUTTING_DOWN state and had
     * NO production caller — a degraded/interrupted shutdown left the
     * app refusing mic taps ("not active") with no in-app way out.
     * This is its caller, on the deliberate launcher-icon path only.
     *
     * Why this does NOT violate "never recover after explicit STOP /
     * SHUT DOWN" (§15.2): a COMPLETED intentional shutdown persists
     * SHUT_DOWN and routes to [beginReactivation], never here. A
     * persisted SHUTTING_DOWN means the teardown did NOT complete or
     * verify — the shutdown is stranded MID-teardown, not finished.
     * Recovery is also guarded inside the controller itself: it
     * refuses when a destructive teardown step already ran in this
     * process. After a process restart that in-memory guard is empty,
     * so a recovered launch is treated as a fresh assistant start:
     * the overlay service is restarted (D-10 pattern — the shutdown
     * stopped it), AuthGate starts empty (tokens were purged before
     * the SHUTTING_DOWN flip), and the session mirror is forced IDLE
     * by the recovery itself. Attempted once per activity instance:
     * a refused recovery stays SHUTTING_DOWN and says so on screen
     * instead of toast-spamming on every resume.
     */
    private var strandedRecoveryAttempted = false

    private fun recoverFromInterruptedShutdown() {
        if (strandedRecoveryAttempted) return
        strandedRecoveryAttempted = true
        android.util.Log.w(
            "MainActivity",
            "launch during interrupted SHUTTING_DOWN — attempting " +
                "pre-destructive shutdown recovery (launch path only)",
        )
        if (LifecycleController.requestShutdownRecovery()) {
            android.util.Log.i(
                "MainActivity",
                "shutdown recovery → ACTIVE after interrupted shutdown; restarting overlay",
            )
            try {
                LoomOverlayService.start(this)
            } catch (e: Exception) {
                android.util.Log.w(
                    "MainActivity",
                    "shutdown recovery: overlay restart failed: ${e.message}",
                )
            }
            Toast.makeText(
                this,
                "LOOM recovered after an interrupted shutdown.",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            android.util.Log.w(
                "MainActivity",
                "shutdown recovery refused — staying SHUTTING_DOWN; " +
                    "mic taps will refuse until a clean shutdown completes",
            )
            Toast.makeText(
                this,
                "LOOM stopped part-way through shutting down and could not " +
                    "recover — say \"shut down\" again to retry a clean shutdown.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun beginReactivation() {
        if (reactivationInFlight) return
        reactivationInFlight = true
        // §4.4: confirm mic permission before any listening.
        requestMicPermission { granted ->
            val checks = listOf(
                object : ReactivationCheck {
                    override val name = "session mirror idle"
                    override fun verify(): ReactivationCheckResult {
                        val mirror = LifecycleController.currentObservable().sessionMirror
                        return if (mirror == "IDLE" || mirror == "ENDING") {
                            ReactivationCheckResult.Pass
                        } else {
                            ReactivationCheckResult.Fail("session mirror=$mirror")
                        }
                    }
                },
                object : ReactivationCheck {
                    override val name = "mic permission"
                    override fun verify(): ReactivationCheckResult =
                        if (granted) ReactivationCheckResult.Pass
                        else ReactivationCheckResult.Fail("mic permission not granted")
                },
            )
            when (val result = LifecycleController.requestReactivation(checks)) {
                is ReactivationResult.Success -> {
                    android.util.Log.i("MainActivity", "reactivation → ACTIVE")
                    // D-10: reactivation restores the ASSISTANT, not
                    // just the state flag — the shutdown stopped the
                    // overlay service, and nothing restarted it, so
                    // the floating assistant stayed dead after a
                    // successful reactivation. Start it again here.
                    try {
                        LoomOverlayService.start(this)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "MainActivity",
                            "reactivation: overlay restart failed: ${e.message}",
                        )
                    }
                }
                is ReactivationResult.Failed -> {
                    // D-10: a failed reactivation used to be log-only
                    // — David tapped the mic and got a silent refusal
                    // with no reason anywhere. Say so on screen; the
                    // next launcher tap retries (routeLifecycleOnLaunch
                    // runs on every create / new-intent / resume).
                    val reason = result.failures.joinToString("; ")
                    android.util.Log.w(
                        "MainActivity",
                        "reactivation failed safely: $reason",
                    )
                    Toast.makeText(
                        this,
                        "LOOM couldn't reactivate: $reason — tap the icon again to retry.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is ReactivationResult.Refused -> {
                    android.util.Log.w(
                        "MainActivity",
                        "reactivation refused: ${result.reason}",
                    )
                    Toast.makeText(
                        this,
                        "LOOM couldn't reactivate: ${result.reason} — tap the icon again to retry.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            reactivationInFlight = false
        }
    }

    /** Opens the system overlay-permission screen; refresh on return. */
    fun openOverlaySettings() {
        startActivityForResult(OverlayPermission.settingsIntent(this), REQ_OVERLAY)
    }

    override fun onBackPressed() {
        if (!navigator.goBack()) super.onBackPressed()
    }

    // ---------- runtime permission + image pick plumbing ----------

    fun requestMicPermission(callback: (Boolean) -> Unit) {
        micPermissionCallback = callback
        requestPermissions(
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            REQ_MIC,
        )
    }

    fun requestNotificationPermission(callback: (Boolean) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            callback(true)
            return
        }
        notificationPermissionCallback = callback
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFICATIONS,
        )
    }

    fun pickImage(callback: (Uri?) -> Unit) {
        imagePickCallback = callback
        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, REQ_IMAGE)
        } else {
            imagePickCallback?.invoke(null)
            imagePickCallback = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            micPermissionCallback?.invoke(granted)
            micPermissionCallback = null
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            notificationPermissionCallback?.invoke(granted)
            notificationPermissionCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            imagePickCallback?.invoke(uri)
            imagePickCallback = null
        }
    }
}
