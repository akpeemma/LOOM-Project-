package com.loom.assistant.execution.tasks

import android.content.Context
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomJson
import java.io.File
import java.util.UUID

/**
 * File-backed task store: one JSON per task under app-private
 * `loom_tasks/`. Real CRUD, progress, and status transitions.
 *
 * Scheduling hook: [schedule] stores the run time; [dueTasks] returns
 * tasks whose time has come. Actual background execution (WorkManager /
 * foreground service) is Phase 6 scope — the hook is the stored schedule,
 * never a fake "scheduled" claim.
 */
class TaskStore private constructor(private val appContext: Context) {

    private val dir: File = File(appContext.filesDir, "loom_tasks").apply { mkdirs() }
    private val log = ActivityLog.get(appContext)

    companion object {
        @Volatile
        private var instance: TaskStore? = null

        fun get(context: Context): TaskStore {
            return instance ?: synchronized(this) {
                instance ?: TaskStore(context.applicationContext).also { instance = it }
            }
        }
    }

    @Synchronized
    fun create(title: String, detail: String = ""): LoomTask {
        val now = System.currentTimeMillis()
        val task = LoomTask(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Untitled task" },
            detail = detail,
            status = TaskStatus.ACTIVE,
            progress = 0,
            scheduledAt = 0L,
            createdAt = now,
            updatedAt = now,
            attempts = 0,
        )
        save(task)
        log.record("task", "Created task: ${task.title}")
        return task
    }

    /** Schedule for later: status becomes SCHEDULED with a stored run time. */
    @Synchronized
    fun schedule(id: String, runAtMillis: Long): LoomTask {
        val task = get(id)
        require(runAtMillis > System.currentTimeMillis()) { "Schedule time must be in the future." }
        val next = task.copy(
            status = TaskStatus.SCHEDULED,
            scheduledAt = runAtMillis,
            updatedAt = System.currentTimeMillis(),
        )
        save(next)
        log.record("task", "Scheduled task: ${task.title}")
        return next
    }

    @Synchronized
    fun setProgress(id: String, progress: Int): LoomTask {
        val task = get(id)
        val next = task.copy(
            progress = progress.coerceIn(0, 100),
            updatedAt = System.currentTimeMillis(),
        )
        save(next)
        return next
    }

    @Synchronized
    fun complete(id: String): LoomTask {
        val task = get(id)
        val next = task.copy(
            status = TaskStatus.COMPLETED,
            progress = 100,
            updatedAt = System.currentTimeMillis(),
        )
        save(next)
        log.record("task", "Completed task: ${task.title}")
        return next
    }

    @Synchronized
    fun fail(id: String, reason: String = ""): LoomTask {
        val task = get(id)
        val next = task.copy(
            status = TaskStatus.FAILED,
            attempts = task.attempts + 1,
            updatedAt = System.currentTimeMillis(),
        )
        save(next)
        log.record("task", "Task failed: ${task.title}${if (reason.isNotBlank()) " — $reason" else ""}")
        return next
    }

    /** Retry a failed task: back to ACTIVE with attempts preserved. */
    @Synchronized
    fun retry(id: String): LoomTask {
        val task = get(id)
        require(task.status == TaskStatus.FAILED) { "Only failed tasks can be retried." }
        val next = task.copy(status = TaskStatus.ACTIVE, updatedAt = System.currentTimeMillis())
        save(next)
        log.record("task", "Retrying task: ${task.title}")
        return next
    }

    /** Cancel a schedule: SCHEDULED back to ACTIVE, run time cleared. */
    @Synchronized
    fun unschedule(id: String): LoomTask {
        val task = get(id)
        require(task.status == TaskStatus.SCHEDULED) { "Only scheduled tasks can be unscheduled." }
        val next = task.copy(
            status = TaskStatus.ACTIVE,
            scheduledAt = 0L,
            updatedAt = System.currentTimeMillis(),
        )
        save(next)
        log.record("task", "Unscheduled task: ${task.title}")
        return next
    }

    @Synchronized
    fun rename(id: String, title: String, detail: String): LoomTask {
        val task = get(id)
        val next = task.copy(
            title = title.ifBlank { "Untitled task" },
            detail = detail,
            updatedAt = System.currentTimeMillis(),
        )
        save(next)
        return next
    }

    @Synchronized
    fun delete(id: String) {
        val task = try {
            get(id)
        } catch (_: Exception) {
            null
        }
        File(dir, "task_$id.json").delete()
        if (task != null) log.record("task", "Deleted task: ${task.title}")
    }

    @Synchronized
    fun get(id: String): LoomTask {
        val f = File(dir, "task_$id.json")
        if (!f.exists()) throw IllegalArgumentException("task not found: $id")
        return parse(f.readText())
    }

    @Synchronized
    fun list(): List<LoomTask> = readAll().sortedByDescending { it.updatedAt }

    @Synchronized
    fun listByStatus(status: TaskStatus): List<LoomTask> =
        readAll().filter { it.status == status }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun countByStatus(status: TaskStatus): Int = readAll().count { it.status == status }

    /** Tasks whose scheduled time has arrived and are still SCHEDULED. */
    @Synchronized
    fun dueTasks(now: Long = System.currentTimeMillis()): List<LoomTask> =
        readAll().filter { it.status == TaskStatus.SCHEDULED && it.scheduledAt <= now }

    // ---- internals ----

    private fun save(t: LoomTask) {
        File(dir, "task_${t.id}.json").writeText(toJson(t))
    }

    private fun readAll(): List<LoomTask> {
        return try {
            dir.listFiles { f -> f.name.startsWith("task_") && f.name.endsWith(".json") }
                ?.mapNotNull { f ->
                    try {
                        parse(f.readText())
                    } catch (_: Exception) {
                        null
                    }
                } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toJson(t: LoomTask): String = LoomJson.obj(
        "id" to LoomJson.str(t.id),
        "title" to LoomJson.str(t.title),
        "detail" to LoomJson.str(t.detail),
        "status" to LoomJson.str(t.status.name),
        "progress" to t.progress.toString(),
        "scheduledAt" to t.scheduledAt.toString(),
        "createdAt" to t.createdAt.toString(),
        "updatedAt" to t.updatedAt.toString(),
        "attempts" to t.attempts.toString(),
    )

    private fun parse(json: String): LoomTask {
        val o = LoomJson.parseObject(json)
        val status = try {
            TaskStatus.valueOf(LoomJson.getStr(o, "status"))
        } catch (_: IllegalArgumentException) {
            TaskStatus.ACTIVE
        }
        return LoomTask(
            id = LoomJson.getStr(o, "id"),
            title = LoomJson.getStr(o, "title"),
            detail = LoomJson.getStr(o, "body", LoomJson.getStr(o, "detail")),
            status = status,
            progress = LoomJson.getInt(o, "progress"),
            scheduledAt = LoomJson.getLong(o, "scheduledAt"),
            createdAt = LoomJson.getLong(o, "createdAt"),
            updatedAt = LoomJson.getLong(o, "updatedAt"),
            attempts = LoomJson.getInt(o, "attempts"),
        )
    }
}
