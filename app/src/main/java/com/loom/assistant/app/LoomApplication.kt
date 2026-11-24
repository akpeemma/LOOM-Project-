package com.loom.assistant.app

import android.app.Application
import com.loom.assistant.core.common.HelperStartupRecord
import com.loom.assistant.core.common.runStartupSection
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.platform.services.PseudoControlPackages
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.presentation.modes.AssistantMode
import com.loom.assistant.presentation.modes.ModeController

/**
 * LOOM Application subclass (lifecycle plan §4.1, correction 5).
 *
 * Owns the application-scoped [LifecycleController]: the controller
 * coordinates shutdown and therefore must survive the overlay service's
 * teardown, so it lives here — not in any service or activity.
 * Registered in AndroidManifest.xml (`android:name=".app.LoomApplication"`).
 */
class LoomApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // MASTER Wave 4 (F10): every section guarded separately.
        // A failure in one section used to be able to kill the whole
        // Application create (LifecycleController.init and the mode
        // observer ran unguarded — only the AuthGate block had a
        // try/catch). Each failure is now recorded in
        // HelperStartupRecord and the next section still runs, so a
        // broken lifecycle store or mode observer can never take the
        // helper down with it. Device Control surfaces the failed
        // sections; the app keeps running degraded, never dead.
        runStartupSection("lifecycle-init") {
            // Wire the persisted lifecycle_state record (atomic writes after
            // every verified transition; read on launch for recovery).
            LifecycleController.init(LifecycleRecordStore(this))
        }

        runStartupSection("mode-observer") {
            // ModeController hook registration ONLY (plan §E.8): observe verified
            // mode changes for the audit trail. applyMode internals untouched;
            // the existing single-slot onVerifiedModeChange listener owned by
            // LoomOverlayService keeps working — this is an additive observer.
            val modeObserver: (AssistantMode) -> Unit = { mode ->
                LifecycleController.noteModeVerified(mode.name)
            }
            ModeController.addVerifiedModeObserver(modeObserver)
        }

        runStartupSection("authgate-host") {
            // Device-evidence fix (2026-11-05, item 7): bind the AuthGate
            // confirmation host at APPLICATION scope. Until now it was bound
            // only in MainActivity.onCreate, so after a HyperOS kill + service
            // restart with no MainActivity visit, every CONFIRM was denied
            // with no card at all (the overlay service's lazy gate init is
            // host-less). Application.onCreate runs before any service or
            // activity in the process, so the host is bound whenever the gate
            // can be reached at all. MainActivity re-binds the same host on
            // its own launch — idempotent, left in place.
            AuthGate.init(this)
            AuthGate.get().confirmationHost = OverlayFirstAuthHost(applicationContext)
            // Device-failure fix (2026-11-13, F4): the gate's
            // pseudo-control check also covers the runtime-resolved
            // default-app packages (dialer / SMS / gallery / clock)
            // for THIS phone. Bound here, at application scope, so
            // it is in place whenever the gate can be reached.
            AuthGate.get().setPseudoRolePackagesProvider {
                PseudoControlPackages.resolveRolePackages(applicationContext)
            }
        }
        // AuthGate stays uninitialized when its section fails → every
        // gated sink refuses honestly (the gate's own contract).
        if (HelperStartupRecord.failedSections().isNotEmpty()) {
            android.util.Log.w(
                "LoomApp",
                "onCreate sections failed: ${HelperStartupRecord.failedSections()}",
            )
        }
    }
}
