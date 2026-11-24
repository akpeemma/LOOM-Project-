package com.loom.assistant.platform.apps

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder

/**
 * Master implementation §1 — Android-backed sources for [AppCatalogue].
 *
 * Split from AppCatalogue.kt on purpose: the catalogue itself is pure
 * Kotlin (JVM-testable); everything here touches android.* APIs.
 *
 * Package visibility (Android 11+): [PackageManagerAppSource] queries
 * launcher intents. The app manifest declares BOTH visibility routes:
 *  - `<queries>` with ACTION_MAIN / CATEGORY_LAUNCHER — the supported
 *    route; makes every launchable app visible to the query.
 *  - `QUERY_ALL_PACKAGES` — also declared; granted for sideloaded LOOM,
 *    but Play-restricted, so a future Play build must not depend on it.
 * Without either, the query silently returns a filtered subset.
 */

/** Production [AppSource]: launcher intents from PackageManager. */
class PackageManagerAppSource(private val context: Context) : AppSource {
    override fun queryLaunchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(query, 0)
        } catch (_: Exception) {
            return emptyList()
        }
        return infos.mapNotNull { ri ->
            val activity = ri.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            AppInfo(
                label = label,
                packageName = pkg,
                className = activity.name,
                iconResId = try {
                    activity.applicationInfo?.icon ?: 0
                } catch (_: Exception) {
                    0
                },
            )
        }
    }
}

/**
 * Production [ForegroundSource].
 *
 * 1. Accessibility window state (via the screen-awareness snapshot's
 *    package — the same source DeviceControlCenter.foregroundPackage()
 *    uses). Needs the LOOM accessibility service enabled; no extra
 *    permission.
 * 2. UsageStats fallback: needs `PACKAGE_USAGE_STATS` declared (it is,
 *    in the app manifest) AND the user granting Usage access in
 *    Settings → Apps → Special access → Usage access. Checked honestly
 *    via AppOpsManager — a denied op reports Unavailable, never an
 *    empty or stale guess.
 */
class SystemForegroundSource(private val context: Context) : ForegroundSource {
    override fun probe(): ForegroundProbe {
        try {
            val pkg = ScreenAwarenessHolder.current.snapshot()?.packageName
            if (pkg != null) return ForegroundProbe.Found(pkg)
        } catch (_: Exception) {
            // Fall through to UsageStats.
        }
        usageStatsForeground()?.let { return ForegroundProbe.Found(it) }
        return ForegroundProbe.Unavailable(
            "I can't tell which app is in the foreground. Enable LOOM in " +
                "Settings → Accessibility, or grant Usage access in " +
                "Settings → Apps → Special access → Usage access.",
        )
    }

    @Suppress("DEPRECATION") // checkOpNoThrow(String, Int, String) is the
    // API available across the supported range (minSdk 26); the
    // attribution-tag overload needs API 30+ branching for no benefit.
    private fun usageStatsForeground(): String? {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return null
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            if (mode != AppOpsManager.MODE_ALLOWED) return null
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 15_000,
                now,
            ) ?: return null
            stats.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Package-change receiver: install / remove / replace → refresh the
 * shared catalogue. Registered dynamically by [AppCatalogueFactory]
 * (application context, process lifetime) so a dead app process is
 * never woken just to refresh a catalogue nobody holds; the next
 * [AppCatalogueHolder.getOrCreate] re-queries PackageManager fresh
 * anyway. A manifest-registered receiver was considered and rejected
 * for that wake-up reason.
 */
class AppPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            -> AppCatalogueHolder.current?.onPackageChanged()
        }
    }
}

/** Builds a production catalogue and wires its package-change refresh. */
object AppCatalogueFactory {
    fun create(context: Context): AppCatalogue {
        val appContext = context.applicationContext
        val catalogue = AppCatalogue(
            PackageManagerAppSource(appContext),
            SystemForegroundSource(appContext),
        )
        catalogue.refresh()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            appContext.registerReceiver(AppPackageReceiver(), filter)
        } catch (_: Exception) {
            // Receiver registration is freshness only: the catalogue still
            // works and re-queries on the next getOrCreate / refresh.
        }
        return catalogue
    }
}

/**
 * Process-wide shared catalogue (same holder pattern as
 * ScreenAwarenessHolder). The speech worker (§2) and LoomAppLauncher
 * reach the resolver through here — never by constructing their own
 * catalogue, so install/remove refreshes apply to every caller.
 */
object AppCatalogueHolder {
    @Volatile
    var current: AppCatalogue? = null
        private set

    fun getOrCreate(context: Context): AppCatalogue {
        current?.let { return it }
        synchronized(this) {
            current?.let { return it }
            val created = AppCatalogueFactory.create(context)
            current = created
            return created
        }
    }

    /** Seam for tests / controlled startup: bind a prebuilt catalogue. */
    fun bind(catalogue: AppCatalogue) {
        current = catalogue
    }

    fun unbind() {
        current = null
    }
}
