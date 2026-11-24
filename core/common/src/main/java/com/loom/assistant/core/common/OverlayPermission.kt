package com.loom.assistant.core.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phase 4 — SYSTEM_ALERT_WINDOW permission flow.
 * The overlay host checks this before adding any view; when revoked at
 * runtime the modes degrade gracefully instead of crashing.
 *
 * Lives in core:common (moved from platform:services in Stage 2A.1) so
 * ModeController — the single authoritative mode applier — can check the
 * permission FIRST with no pref write on denial, without a module cycle.
 */
object OverlayPermission {

    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
