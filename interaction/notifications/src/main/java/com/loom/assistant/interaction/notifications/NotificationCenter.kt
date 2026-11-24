package com.loom.assistant.interaction.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.loom.assistant.core.common.LoomIntents
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomJson
import java.io.File

/**
 * Disciplined outbound notifications (arch §2.17).
 * Meaningful only: every post needs a reason code from the allowed set,
 * and rate limits are enforced per group. All posts are logged to a
 * file-backed history the Notifications screen shows.
 */
class NotificationCenter private constructor(private val appContext: Context) {

    enum class Group(val title: String) {
        TASKS("Tasks"),
        AUTHORIZATIONS("Authorizations"),
        SYSTEM("System"),
        INTELLIGENCE("Intelligence"),
    }

    /** The only allowed reason codes (arch §2.17 NotificationPolicy). */
    enum class Reason(val label: String) {
        TASK_COMPLETED("Task completed"),
        TASK_FAILED("Task failed"),
        AUTH_REQUIRED("Authorization required"),
        ERROR_ATTENTION("Error needs attention"),
        PROGRESS_MILESTONE("Progress milestone"),
    }

    data class LogEntry(
        val timestamp: Long,
        val group: Group,
        val reason: Reason,
        val title: String,
        val text: String,
        val delivered: Boolean,
    )

    companion object {
        private const val CHANNEL_ID = "loom_updates"
        private const val MAX_PER_HOUR_PER_GROUP = 5

        @Volatile
        private var instance: NotificationCenter? = null

        fun get(context: Context): NotificationCenter {
            return instance ?: synchronized(this) {
                instance ?: NotificationCenter(context.applicationContext).also { instance = it }
            }
        }
    }

    private val logFile: File = File(appContext.filesDir, "loom_notifications.jsonl")
    private val activityLog = ActivityLog.get(appContext)

    /** POST_NOTIFICATIONS is runtime-gated on Android 13+. */
    fun hasPostPermission(): Boolean {
        return if (Build.VERSION.SDK_INT < 33) {
            true
        } else {
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Post a notification. Returns true when actually delivered.
     * Refuses (returns false + logs the refusal) when the reason is not
     * allowed, the group is over its hourly rate limit, or the permission
     * is missing — never posts silently-unprompted.
     */
    @Synchronized
    fun post(group: Group, reason: Reason, title: String, text: String): Boolean {
        if (!rateAllows(group)) {
            appendLog(group, reason, title, text, delivered = false)
            return false
        }
        var delivered = false
        if (hasPostPermission()) {
            try {
                ensureChannel()
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val n = android.app.Notification.Builder(appContext, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build()
                nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
                delivered = true
            } catch (_: Exception) {
                delivered = false
            }
        }
        appendLog(group, reason, title, text, delivered)
        if (delivered) {
            activityLog.record("notification", "${group.title}: $title")
        }
        return delivered
    }

    /**
     * Phase 6 — AuthGate confirmation fallback (arch §2.14).
     * Posts an AUTH_REQUIRED notification with Approve/Deny actions when the
     * in-overlay card cannot be shown. This is a direct response to David's
     * own pending action — NOT a broadcast — so it bypasses the hourly rate
     * limit (dropping a confirmation request would be a silent failure).
     * Still logged to history with the AUTH_REQUIRED reason code.
     *
     * Device-evidence fix (2026-11-05, item 10): when the gate offered
     * an always-allow grant for this pending ([grantOffer] non-null),
     * the notification also carries an "Always allow" action — the
     * fallback could never create a grant before, so a notification
     * approval could never suppress the next card. Without an offer
     * (MODEL origin, ineligible key, blank scope, paranoid) no such
     * action appears, and the receiver never sets alwaysAllow for it.
     */
    @Synchronized
    fun postAuthRequest(
        pendingId: String,
        title: String,
        text: String,
        grantOffer: String? = null,
    ): Boolean {
        var delivered = false
        if (hasPostPermission()) {
            try {
                ensureChannel()
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val approve = PendingIntent.getBroadcast(
                    appContext,
                    pendingId.hashCode(),
                    Intent(LoomIntents.ACTION_AUTH_APPROVE).apply {
                        setClassName(appContext.packageName, LoomIntents.AUTH_RECEIVER)
                        putExtra(LoomIntents.EXTRA_AUTH_PENDING_ID, pendingId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val deny = PendingIntent.getBroadcast(
                    appContext,
                    pendingId.hashCode() + 1,
                    Intent(LoomIntents.ACTION_AUTH_DENY).apply {
                        setClassName(appContext.packageName, LoomIntents.AUTH_RECEIVER)
                        putExtra(LoomIntents.EXTRA_AUTH_PENDING_ID, pendingId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val builder = android.app.Notification.Builder(appContext, CHANNEL_ID)
                    .setContentTitle("LOOM needs your approval")
                    .setContentText("$title — $text")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .addAction(
                        android.app.Notification.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            "Deny",
                            deny,
                        ).build(),
                    )
                    .addAction(
                        android.app.Notification.Action.Builder(
                            android.R.drawable.ic_menu_send,
                            "Approve",
                            approve,
                        ).build(),
                    )
                if (grantOffer != null) {
                    val approveAlways = PendingIntent.getBroadcast(
                        appContext,
                        pendingId.hashCode() + 2,
                        Intent(LoomIntents.ACTION_AUTH_APPROVE_ALWAYS).apply {
                            setClassName(appContext.packageName, LoomIntents.AUTH_RECEIVER)
                            putExtra(LoomIntents.EXTRA_AUTH_PENDING_ID, pendingId)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    builder.addAction(
                        android.app.Notification.Action.Builder(
                            android.R.drawable.ic_menu_send,
                            "Always allow",
                            approveAlways,
                        ).build(),
                    )
                }
                val n = builder.build()
                nm.notify(("auth$pendingId").hashCode(), n)
                delivered = true
            } catch (_: Exception) {
                delivered = false
            }
        }
        appendLog(Group.AUTHORIZATIONS, Reason.AUTH_REQUIRED, title, text, delivered)
        if (delivered) {
            activityLog.record("notification", "Authorizations: $title")
        }
        return delivered
    }

    /** Dismiss the fallback notification for a resolved pending confirmation. */
    fun dismissAuthRequest(pendingId: String) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(("auth$pendingId").hashCode())
        } catch (_: Exception) {
        }
    }

    /** Recent outbound history, newest first. */
    @Synchronized
    fun history(limit: Int = 30): List<LogEntry> {        if (!logFile.exists()) return emptyList()
        return try {
            logFile.readLines().asReversed().take(limit).mapNotNull { line ->
                try {
                    val m = LoomJson.parseObject(line)
                    LogEntry(
                        timestamp = LoomJson.getLong(m, "timestamp"),
                        group = Group.valueOf(LoomJson.getStr(m, "group")),
                        reason = Reason.valueOf(LoomJson.getStr(m, "reason")),
                        title = LoomJson.getStr(m, "title"),
                        text = LoomJson.getStr(m, "text"),
                        delivered = LoomJson.getBool(m, "delivered"),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clearHistory() {
        try {
            logFile.delete()
        } catch (_: Exception) {
        }
    }

    // ---- internals ----

    private fun rateAllows(group: Group): Boolean {
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        return history(100).count { it.group == group && it.timestamp >= hourAgo } <
            MAX_PER_HOUR_PER_GROUP
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "LOOM updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Meaningful LOOM updates only — never a notification speaker." },
            )
        }
    }

    private fun appendLog(
        group: Group,
        reason: Reason,
        title: String,
        text: String,
        delivered: Boolean,
    ) {
        val line = LoomJson.obj(
            "timestamp" to System.currentTimeMillis().toString(),
            "group" to LoomJson.str(group.name),
            "reason" to LoomJson.str(reason.name),
            "title" to LoomJson.str(title),
            "text" to LoomJson.str(text),
            "delivered" to delivered.toString(),
        )
        try {
            logFile.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }
}
