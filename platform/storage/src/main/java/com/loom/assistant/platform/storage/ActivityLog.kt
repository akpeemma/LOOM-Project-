package com.loom.assistant.platform.storage

import android.content.Context
import java.io.File

/**
 * Append-only activity log backing the Intelligence Dashboard's
 * "Recent Activity" section and the Activity card.
 *
 * Every entry is real: recorded by the store that performed the action
 * (tasks, skills, memory, projects). Nothing is fabricated; an empty
 * log renders as an empty state on the dashboard.
 */
class ActivityLog private constructor(private val appContext: Context) {

    data class Entry(val timestamp: Long, val kind: String, val text: String)

    private val file: File = File(appContext.filesDir, "loom_activity.jsonl")

    companion object {
        @Volatile
        private var instance: ActivityLog? = null

        fun get(context: Context): ActivityLog {
            return instance ?: synchronized(this) {
                instance ?: ActivityLog(context.applicationContext).also { instance = it }
            }
        }
    }

    @Synchronized
    fun record(kind: String, text: String) {
        val e = Entry(System.currentTimeMillis(), kind, text)
        val line = LoomJson.obj(
            "timestamp" to e.timestamp.toString(),
            "kind" to LoomJson.str(kind),
            "text" to LoomJson.str(text),
        )
        try {
            file.appendText(line + "\n")
        } catch (_: Exception) {
        }
        // Cap the log at 500 entries (drop oldest) so it can't grow unbounded.
        try {
            val lines = file.readLines()
            if (lines.size > 500) {
                file.writeText(lines.takeLast(500).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun list(limit: Int = 20): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().asReversed().take(limit).mapNotNull { line ->
                try {
                    val m = LoomJson.parseObject(line)
                    Entry(
                        timestamp = LoomJson.getLong(m, "timestamp"),
                        kind = LoomJson.getStr(m, "kind"),
                        text = LoomJson.getStr(m, "text"),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Recent entries of one kind (e.g. "auth" for the gate audit trail). */
    @Synchronized
    fun listByKind(kind: String, limit: Int = 20): List<Entry> =
        list(500).filter { it.kind == kind }.take(limit)

    @Synchronized
    fun totalCount(): Int {        if (!file.exists()) return 0
        return try {
            file.readLines().count { it.isNotBlank() }
        } catch (_: Exception) {
            0
        }
    }

    @Synchronized
    fun todayCount(): Int {
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return list(500).count { it.timestamp >= startOfDay }
    }

    @Synchronized
    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}
