package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.lifecycle.AutomaticRecovery
import com.loom.assistant.lifecycle.IntentionalStop
import com.loom.assistant.lifecycle.IntentionalStopCommand
import com.loom.assistant.lifecycle.RecoveryStore

/**
 * Automatic recovery (Nov 6) — the Android side of [RecoveryStore].
 *
 * One SharedPreferences file ("loom_recovery"), kept apart from
 * LoomPreferences on purpose: the intentional-stop marker must survive
 * process death and be readable before any other state is trusted, and
 * it must never be wiped by a preferences clear that touches the
 * settings file. Values are plain booleans / strings / longs — no
 * credentials, no tokens, nothing sensitive.
 *
 * Plain-words rule: no user-facing string lives here (reports are
 * built by [AutomaticRecovery]); the only literals are preference
 * keys and the file name.
 */
class AutomaticRecoveryStore private constructor(private val appContext: Context) :
    RecoveryStore {

    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "loom_recovery"
        private const val KEY_ENABLED = "auto_recover_enabled"
        private const val KEY_STOP_COMMAND = "intentional_stop_command"
        private const val KEY_STOP_AT_MS = "intentional_stop_at_ms"
        private const val KEY_FAILURE_COUNT = "recovery_failure_count"
        private const val KEY_LAST_REPORT = "last_recovery_report"
        private const val KEY_LAST_REPORT_KIND = "last_recovery_report_kind"
        private const val KEY_LAST_REPORT_AT_MS = "last_recovery_report_at_ms"

        @Volatile
        private var instance: AutomaticRecoveryStore? = null

        fun get(context: Context): AutomaticRecoveryStore {
            return instance ?: synchronized(this) {
                instance ?: AutomaticRecoveryStore(context.applicationContext)
                    .also { instance = it }
            }
        }
    }

    override fun isAutoRecoverEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLED, false)

    override fun setAutoRecoverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun readIntentionalStop(): IntentionalStop? {
        val name = prefs.getString(KEY_STOP_COMMAND, null) ?: return null
        val command = try {
            IntentionalStopCommand.valueOf(name)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val atMs = prefs.getLong(KEY_STOP_AT_MS, 0L)
        if (atMs <= 0L) return null
        return IntentionalStop(command, atMs)
    }

    override fun writeIntentionalStop(stop: IntentionalStop) {
        // W2 fix (Nov 6): commit(), not apply(). The ACTION_STOP path
        // calls stopSelf() immediately after this write — if the
        // process dies before an async apply() flushes, the marker is
        // lost and a later launch could auto-recover after an
        // intentional STOP. A synchronous commit costs one small disk
        // write on a path that runs only when David stops LOOM.
        prefs.edit()
            .putString(KEY_STOP_COMMAND, stop.command.name)
            .putLong(KEY_STOP_AT_MS, stop.atMs)
            .commit()
    }

    override fun clearIntentionalStop() {
        // Same durability as the write: a lost clear leaves a stale
        // marker that would wrongly refuse a later auto-recovery
        // after a legitimate reactivation.
        prefs.edit()
            .remove(KEY_STOP_COMMAND)
            .remove(KEY_STOP_AT_MS)
            .commit()
    }

    override fun failureCount(): Int = prefs.getInt(KEY_FAILURE_COUNT, 0)

    override fun setFailureCount(count: Int) {
        prefs.edit().putInt(KEY_FAILURE_COUNT, count.coerceAtLeast(0)).apply()
    }

    override fun lastReport(): String? = prefs.getString(KEY_LAST_REPORT, null)

    override fun lastReportKind(): String? = prefs.getString(KEY_LAST_REPORT_KIND, null)

    override fun lastReportAtMs(): Long = prefs.getLong(KEY_LAST_REPORT_AT_MS, 0L)

    override fun setLastReport(report: String?, kind: String?) {
        val edit = prefs.edit()
        if (report == null) {
            edit.remove(KEY_LAST_REPORT)
                .remove(KEY_LAST_REPORT_KIND)
                .remove(KEY_LAST_REPORT_AT_MS)
        } else {
            edit.putString(KEY_LAST_REPORT, report)
                .putString(KEY_LAST_REPORT_KIND, kind)
                .putLong(KEY_LAST_REPORT_AT_MS, System.currentTimeMillis())
        }
        edit.apply()
    }
}
