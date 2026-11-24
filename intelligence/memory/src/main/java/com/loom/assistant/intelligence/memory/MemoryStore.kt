package com.loom.assistant.intelligence.memory

import android.content.Context
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomJson
import java.io.File
import java.util.UUID

/**
 * File-backed memory store: one JSON file per item under
 * app-private `loom_memory/`. Reviewable, editable, deletable —
 * wired to the Phase 3 conversation store only in the sense that both
 * are real stores the dashboard counts (no cross-writes).
 */
class MemoryStore private constructor(private val appContext: Context) {

    private val dir: File = File(appContext.filesDir, "loom_memory").apply { mkdirs() }
    private val log = ActivityLog.get(appContext)

    companion object {
        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore {
            return instance ?: synchronized(this) {
                instance ?: MemoryStore(context.applicationContext).also { instance = it }
            }
        }
    }

    @Synchronized
    fun add(category: MemoryCategory, title: String, body: String): MemoryItem {
        val now = System.currentTimeMillis()
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title.ifBlank { "Untitled memory" },
            body = body,
            createdAt = now,
            updatedAt = now,
        )
        save(item)
        log.record("memory", "Saved to ${category.title}: ${item.title}")
        return item
    }

    @Synchronized
    fun get(id: String): MemoryItem {
        val f = File(dir, "mem_$id.json")
        if (!f.exists()) throw IllegalArgumentException("memory not found: $id")
        return parse(f.readText())
    }

    @Synchronized
    fun update(id: String, title: String, body: String): MemoryItem {
        val old = get(id)
        val item = old.copy(
            title = title.ifBlank { "Untitled memory" },
            body = body,
            updatedAt = System.currentTimeMillis(),
        )
        save(item)
        return item
    }

    @Synchronized
    fun delete(id: String) {
        val item = try {
            get(id)
        } catch (_: Exception) {
            null
        }
        File(dir, "mem_$id.json").delete()
        if (item != null) {
            log.record("memory", "Deleted from ${item.category.title}: ${item.title}")
        }
    }

    /** All items, newest first. */
    @Synchronized
    fun list(): List<MemoryItem> = readAll().sortedByDescending { it.updatedAt }

    @Synchronized
    fun listByCategory(category: MemoryCategory): List<MemoryItem> =
        readAll().filter { it.category == category }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun count(): Int = readAll().size

    @Synchronized
    fun countByCategory(category: MemoryCategory): Int =
        readAll().count { it.category == category }

    /** Case-insensitive search over titles and bodies. */
    @Synchronized
    fun search(query: String): List<MemoryItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        return readAll().filter {
            it.title.lowercase().contains(q) || it.body.lowercase().contains(q)
        }.sortedByDescending { it.updatedAt }
    }

    /** Total bytes of the memory store (dashboard "Knowledge" figure). */
    @Synchronized
    fun storageBytes(): Long {
        return try {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // ---- internals ----

    private fun save(item: MemoryItem) {
        File(dir, "mem_${item.id}.json").writeText(toJson(item))
    }

    private fun readAll(): List<MemoryItem> {
        return try {
            dir.listFiles { f -> f.name.startsWith("mem_") && f.name.endsWith(".json") }
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

    private fun toJson(m: MemoryItem): String = LoomJson.obj(
        "id" to LoomJson.str(m.id),
        "category" to LoomJson.str(m.category.name),
        "title" to LoomJson.str(m.title),
        "body" to LoomJson.str(m.body),
        "createdAt" to m.createdAt.toString(),
        "updatedAt" to m.updatedAt.toString(),
    )

    private fun parse(json: String): MemoryItem {
        val o = LoomJson.parseObject(json)
        val catName = LoomJson.getStr(o, "category")
        val category = try {
            MemoryCategory.valueOf(catName)
        } catch (_: IllegalArgumentException) {
            MemoryCategory.DECISIONS
        }
        return MemoryItem(
            id = LoomJson.getStr(o, "id"),
            category = category,
            title = LoomJson.getStr(o, "title"),
            body = LoomJson.getStr(o, "body"),
            createdAt = LoomJson.getLong(o, "createdAt"),
            updatedAt = LoomJson.getLong(o, "updatedAt"),
        )
    }
}
