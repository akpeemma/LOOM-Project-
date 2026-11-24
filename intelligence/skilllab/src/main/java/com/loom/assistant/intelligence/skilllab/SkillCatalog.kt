package com.loom.assistant.intelligence.skilllab

import android.content.Context
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.platform.storage.LoomJson
import java.io.File
import java.util.UUID

/**
 * File-backed skill catalog: one JSON per skill under app-private
 * `loom_skills/`. Real lifecycle with validated transitions
 * (arch §2.10). Install requires explicit user approval at the UI layer.
 *
 * Ships with exactly one real entry: the preserved product-research /
 * price-comparison skill from the old `shopping/` package
 * (Preservation Report §B) — optional, default OFF (DISCOVERED, not
 * installed). Nothing else is seeded; the crucible/GitHub discovery
 * that fills the catalog further is M5 scope.
 */
class SkillCatalog private constructor(private val appContext: Context) {

    private val dir: File = File(appContext.filesDir, "loom_skills").apply { mkdirs() }
    private val log = ActivityLog.get(appContext)

    companion object {
        const val SHOPPING_SKILL_ID = "shopping-research"

        @Volatile
        private var instance: SkillCatalog? = null

        fun get(context: Context): SkillCatalog {
            return instance ?: synchronized(this) {
                instance ?: SkillCatalog(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        ensureSeeded()
    }

    /** The preserved shopping skill — present, optional, default OFF. */
    @Synchronized
    private fun ensureSeeded() {
        if (getOrNull(SHOPPING_SKILL_ID) != null) return
        val now = System.currentTimeMillis()
        val skill = SkillRecord(
            id = SHOPPING_SKILL_ID,
            name = "Product Research",
            version = "1.0",
            description = "Price comparison across merchants (Amazon.in, Flipkart) " +
                "with a comparison verdict. Preserved from Brahma — optional.",
            status = SkillStatus.DISCOVERED,
            source = "Preserved: Brahma shopping/ package",
            installedAt = 0L,
            updatedAt = now,
        )
        save(skill)
    }

    // ---- reads ----

    @Synchronized
    fun list(): List<SkillRecord> = readAll().sortedByDescending { it.updatedAt }

    @Synchronized
    fun listByStatus(status: SkillStatus): List<SkillRecord> =
        readAll().filter { it.status == status }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(id: String): SkillRecord =
        getOrNull(id) ?: throw IllegalArgumentException("skill not found: $id")

    @Synchronized
    fun countByStatus(status: SkillStatus): Int = readAll().count { it.status == status }

    // ---- lifecycle (validated transitions) ----

    /**
     * Install a skill. The caller must have obtained the user's explicit
     * approval (arch §2.10: install = approval). Allowed from DISCOVERED,
     * APPROVED, or DISABLED.
     */
    @Synchronized
    fun install(id: String): SkillRecord =
        transition(id, setOf(SkillStatus.DISCOVERED, SkillStatus.APPROVED, SkillStatus.DISABLED)) {
            it.copy(status = SkillStatus.INSTALLED, installedAt = System.currentTimeMillis())
        }.also { log.record("skill", "Installed: ${it.name} v${it.version}") }

    @Synchronized
    fun disable(id: String): SkillRecord =
        transition(id, setOf(SkillStatus.INSTALLED)) {
            it.copy(status = SkillStatus.DISABLED)
        }.also { log.record("skill", "Disabled: ${it.name}") }

    @Synchronized
    fun enable(id: String): SkillRecord =
        transition(id, setOf(SkillStatus.DISABLED)) {
            it.copy(status = SkillStatus.INSTALLED)
        }.also { log.record("skill", "Enabled: ${it.name}") }

    @Synchronized
    fun markTesting(id: String): SkillRecord =
        transition(id, setOf(SkillStatus.DISCOVERED, SkillStatus.IN_DEVELOPMENT)) {
            it.copy(status = SkillStatus.TESTING)
        }.also { log.record("skill", "Moved to testing: ${it.name}") }

    @Synchronized
    fun approve(id: String): SkillRecord =
        transition(id, setOf(SkillStatus.TESTING)) {
            it.copy(status = SkillStatus.APPROVED)
        }.also { log.record("skill", "Approved: ${it.name}") }

    @Synchronized
    fun flagNeedsImprovement(id: String): SkillRecord =
        transition(id, SkillStatus.values().toSet() - SkillStatus.NEEDS_IMPROVEMENT) {
            it.copy(status = SkillStatus.NEEDS_IMPROVEMENT)
        }.also { log.record("skill", "Flagged for improvement: ${it.name}") }

    @Synchronized
    fun uninstall(id: String) {
        val skill = get(id)
        if (skill.id == SHOPPING_SKILL_ID) {
            // The preserved skill may be disabled but never deleted
            // (Preservation Report §B non-removal rule).
            throw IllegalStateException("The preserved Product Research skill cannot be deleted — disable it instead.")
        }
        File(dir, "skill_$id.json").delete()
        log.record("skill", "Removed: ${skill.name}")
    }

    /** Register a skill draft (crucible/GitHub discovery lands here in M5). */
    @Synchronized
    fun register(name: String, version: String, description: String, source: String): SkillRecord {
        val now = System.currentTimeMillis()
        val skill = SkillRecord(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Unnamed skill" },
            version = version.ifBlank { "0.1" },
            description = description,
            status = SkillStatus.DISCOVERED,
            source = source,
            installedAt = 0L,
            updatedAt = now,
        )
        save(skill)
        log.record("skill", "Discovered: ${skill.name} v${skill.version}")
        return skill
    }

    // ---- internals ----

    private fun transition(
        id: String,
        allowedFrom: Set<SkillStatus>,
        apply: (SkillRecord) -> SkillRecord,
    ): SkillRecord {
        val skill = get(id)
        if (skill.status !in allowedFrom) {
            throw IllegalStateException(
                "Cannot transition ${skill.name} from ${skill.status.label}",
            )
        }
        val next = apply(skill).copy(updatedAt = System.currentTimeMillis())
        save(next)
        return next
    }

    private fun getOrNull(id: String): SkillRecord? {
        val f = File(dir, "skill_$id.json")
        if (!f.exists()) return null
        return try {
            parse(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun save(s: SkillRecord) {
        File(dir, "skill_${s.id}.json").writeText(toJson(s))
    }

    private fun readAll(): List<SkillRecord> {
        return try {
            dir.listFiles { f -> f.name.startsWith("skill_") && f.name.endsWith(".json") }
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

    private fun toJson(s: SkillRecord): String = LoomJson.obj(
        "id" to LoomJson.str(s.id),
        "name" to LoomJson.str(s.name),
        "version" to LoomJson.str(s.version),
        "description" to LoomJson.str(s.description),
        "status" to LoomJson.str(s.status.name),
        "source" to LoomJson.str(s.source),
        "installedAt" to s.installedAt.toString(),
        "updatedAt" to s.updatedAt.toString(),
    )

    private fun parse(json: String): SkillRecord {
        val o = LoomJson.parseObject(json)
        val status = try {
            SkillStatus.valueOf(LoomJson.getStr(o, "status"))
        } catch (_: IllegalArgumentException) {
            SkillStatus.DISCOVERED
        }
        return SkillRecord(
            id = LoomJson.getStr(o, "id"),
            name = LoomJson.getStr(o, "name"),
            version = LoomJson.getStr(o, "version"),
            description = LoomJson.getStr(o, "description"),
            status = status,
            source = LoomJson.getStr(o, "source"),
            installedAt = LoomJson.getLong(o, "installedAt"),
            updatedAt = LoomJson.getLong(o, "updatedAt"),
        )
    }
}
