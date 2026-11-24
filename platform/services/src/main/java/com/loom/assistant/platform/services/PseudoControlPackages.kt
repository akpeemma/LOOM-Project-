package com.loom.assistant.platform.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Device-failure fix (2026-11-13, F4) — runtime resolution of the
 * default-app packages for the pseudo-control roles whose static
 * table entries are UNCONFIRMED (Phone, Messages, Gallery, Clock).
 *
 * The static table in PseudoControlPolicy lists the vendor variants
 * the author could name; the device itself knows which app actually
 * holds each role. The application layer binds this resolver into
 * AuthGate (LoomApplication.onCreate), and the gate passes the
 * resolved set into the policy as extra approved packages, so a
 * grant covers the real dialer / SMS / gallery / clock on THIS
 * phone instead of carding because the static variant was wrong.
 *
 * What is resolved, and how (each step guarded — a failure yields
 * no package for that role, never a crash, and the static table
 * remains the fallback):
 *  - Default dialer: [TelecomManager.getDefaultDialerPackage].
 *  - Default SMS app: [Telephony.Sms.getDefaultSmsPackage].
 *  - Gallery: the default handler for viewing an image
 *    (ACTION_VIEW for the image MIME type).
 *  - Clock: the default handler for ACTION_SHOW_ALARMS.
 *
 * Safety: role resolution is the Android system's own statement of
 * which app holds the role — a banking app cannot hold the dialer
 * or SMS role. Execution inside a sensitive app is still refused
 * independently (the planner's sensitive pre-gate and the
 * hard-deny keys in AuthGate run before any pseudo check), so a
 * resolved package can never authorize sensitive automation. The
 * resolved set is cached per process: a changed default app takes
 * effect on the next LOOM start, and the cache keeps token
 * requests off the PackageManager hot path.
 *
 * Anything still unresolvable stays UNCONFIRMED honestly: the card
 * appears (fails safe) and device test PC-01 names the package.
 *
 * This lives in platform:services (not intelligence:authgate) so
 * the gate module never depends on the telecom/telephony APIs —
 * the harness compiles the gate on the JVM without Android.
 */
object PseudoControlPackages {

    @Volatile
    private var cached: Set<String>? = null

    /** The runtime-resolved role packages (empty when nothing resolved). Cached per process. */
    fun resolveRolePackages(context: Context): Set<String> {
        cached?.let { return it }
        val resolved = mutableSetOf<String>()
        runCatching {
            context.getSystemService(TelecomManager::class.java)
                ?.defaultDialerPackage?.takeIf { it.isNotBlank() }?.let { resolved.add(it) }
        }
        runCatching {
            Telephony.Sms.getDefaultSmsPackage(context)
                ?.takeIf { it.isNotBlank() }?.let { resolved.add(it) }
        }
        runCatching {
            resolveDefaultHandler(
                context,
                Intent(Intent.ACTION_VIEW).apply { type = "image/*" },
            )?.let { resolved.add(it) }
        }
        runCatching {
            resolveDefaultHandler(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))
                ?.let { resolved.add(it) }
        }
        val result = resolved.toSet()
        cached = result
        return result
    }

    private fun resolveDefaultHandler(context: Context, intent: Intent): String? {
        val info = context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return null
        return info.activityInfo?.packageName?.takeIf { it.isNotBlank() }
    }
}
