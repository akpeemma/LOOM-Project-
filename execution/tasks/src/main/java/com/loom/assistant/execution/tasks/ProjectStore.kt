package com.loom.assistant.execution.tasks

import android.content.Context
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomJson
import java.io.File
import java.util.UUID

/**
 * File-backed project store (lockdown card 12): one JSON per project
 * under app-private `loom_projects/`. Real CRUD + search + status pills.
 * Nothing is seeded — projects are created by David.
 */
class ProjectStore private constructor(private val appContext: Context) {

    private val dir: File = File(appContext.filesDir, "loom_projects").apply { mkdirs() }
    private val log = ActivityLog.get(appContext)

    companion object {
        @Volatile
        private var instance: ProjectStore? = null

        fun get(context: Context): ProjectStore {
            return instance ?: synchronized(this) {
                instance ?: ProjectStore(context.applicationContext).also { instance = it }
            }
        }
    }

    @Synchronized
    fun create(name: String, description: String): LoomProject {
        val now = System.currentTimeMillis()
        val p = LoomProject(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled project" },
            description = description,
            status = ProjectStatus.ACTIVE,
            updatedAt = now,
            createdAt = now,
        )
        save(p)
        log.record("project", "Created project: ${p.name}")
        return p
    }

    @Synchronized
    fun get(id: String): LoomProject {
        val f = File(dir, "proj_$id.json")
        if (!f.exists()) throw IllegalArgumentException("project not found: $id")
        return parse(f.readText())
    }

    @Synchronized
    fun update(id: String, name: String, description: String, status: ProjectStatus): LoomProject {
        val old = get(id)
        val next = old.copy(
            name = name.ifBlank { "Untitled project" },
            description = description,
            status = status,
            updatedAt = System.currentTimeMillis(),
        )
        save(next)
        return next
    }

    @Synchronized
    fun delete(id: String) {
        val p = try {
            get(id)
        } catch (_: Exception) {
            null
        }
        File(dir, "proj_$id.json").delete()
        if (p != null) log.record("project", "Deleted project: ${p.name}")
    }

    @Synchronized
    fun list(): List<LoomProject> = readAll().sortedByDescending { it.updatedAt }

    /** Case-insensitive search over names and descriptions. */
    @Synchronized
    fun search(query: String): List<LoomProject> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        return readAll().filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }.sortedByDescending { it.updatedAt }
    }

    // ---- internals ----

    private fun save(p: LoomProject) {
        File(dir, "proj_${p.id}.json").writeText(toJson(p))
    }

    private fun readAll(): List<LoomProject> {
        return try {
            dir.listFiles { f -> f.name.startsWith("proj_") && f.name.endsWith(".json") }
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

    private fun toJson(p: LoomProject): String = LoomJson.obj(
        "id" to LoomJson.str(p.id),
        "name" to LoomJson.str(p.name),
        "description" to LoomJson.str(p.description),
        "status" to LoomJson.str(p.status.name),
        "updatedAt" to p.updatedAt.toString(),
        "createdAt" to p.createdAt.toString(),
    )

    private fun parse(json: String): LoomProject {
        val o = LoomJson.parseObject(json)
        val status = try {
            ProjectStatus.valueOf(LoomJson.getStr(o, "status"))
        } catch (_: IllegalArgumentException) {
            ProjectStatus.ACTIVE
        }
        return LoomProject(
            id = LoomJson.getStr(o, "id"),
            name = LoomJson.getStr(o, "name"),
            description = LoomJson.getStr(o, "description"),
            status = status,
            updatedAt = LoomJson.getLong(o, "updatedAt"),
            createdAt = LoomJson.getLong(o, "createdAt"),
        )
    }
}
