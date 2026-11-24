package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.intelligence.planner.BackBehaviour
import com.loom.assistant.intelligence.planner.KnownControl
import com.loom.assistant.intelligence.planner.KnownNavTab
import com.loom.assistant.intelligence.planner.ScreenKind
import com.loom.assistant.intelligence.planner.ScreenKnowledge
import com.loom.assistant.intelligence.planner.ScreenKnowledgePersistence
import com.loom.assistant.intelligence.planner.ScreenKnowledgeStore
import com.loom.assistant.intelligence.planner.TargetBounds

/**
 * KNOWLEDGE BASE (2026-11-09, David's correction) — disk persistence
 * for the screen knowledge base.
 *
 * Same durable pattern as the app-profile store (one entry per key,
 * fields joined with "|" — package names, labels, mechanism names
 * and role names never contain "|" or ";" or ":", and every value
 * is sanitised on write). One entry per screen key
 * "$package|$structureHash". A corrupt entry decodes to null and is
 * skipped, never taking the whole load down. No passwords, no screen
 * content, no queries are ever stored here — only screen kinds,
 * structure hashes, affordances, control labels/roles/bounds, the
 * winning submit mechanism, and the back/clear behaviour.
 *
 * Stored bounds are last-seen information for the record only —
 * they are NEVER used as tap coordinates (every tap resolves from
 * the live screen).
 */
class SharedPreferencesScreenKnowledgePersistence(
    context: Context,
) : ScreenKnowledgePersistence {

    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "loom_screen_knowledge"
        const val PREFIX = "screen|"
    }

    override fun load(): Map<String, ScreenKnowledge> {
        val out = LinkedHashMap<String, ScreenKnowledge>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(PREFIX)) continue
            val mapKey = key.removePrefix(PREFIX)
            val raw = value as? String ?: continue
            decode(mapKey, raw)?.let { out[mapKey] = it }
        }
        return out
    }

    override fun save(knowledge: Map<String, ScreenKnowledge>) {
        val editor = prefs.edit()
        for ((key, entry) in knowledge) {
            editor.putString(PREFIX + key, encode(entry))
        }
        editor.apply()
    }

    private fun encode(k: ScreenKnowledge): String {
        val clear = k.clearControl
        val controls = k.controls.joinToString(";") { c ->
            "${sanitise(c.label)}:${c.role}:${c.bounds.left},${c.bounds.top}," +
                "${c.bounds.right},${c.bounds.bottom}:${c.howOperated}"
        }
        // NAV-SCREEN fix (2026-11-11, N10): nav tabs ride as the
        // 16th field — "label:position:role:bounds:selected" entries
        // joined with ";". Old entries without it decode to empty.
        val navTabs = k.navTabs.joinToString(";") { t ->
            "${sanitise(t.label)}:${t.position}:${t.role}:" +
                "${t.bounds.left},${t.bounds.top},${t.bounds.right},${t.bounds.bottom}:" +
                if (t.selected) "1" else "0"
        }
        return listOf(
            k.packageName,
            k.screenKind.name,
            k.structureHash.toString(),
            k.affordances.joinToString(","),
            sanitise(k.searchEntryHint ?: ""),
            k.winningSubmitMechanism?.name ?: "",
            sanitise(clear?.label ?: ""),
            clear?.let { "${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" } ?: "",
            clear?.role ?: "",
            k.backBehaviour.name,
            k.backButtonBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "",
            k.observations.toString(),
            k.lastSeenAt.toString(),
            if (k.backRecoveryFailed) "1" else "0",
            controls,
            navTabs,
        ).joinToString("|")
    }

    private fun decode(key: String, raw: String): ScreenKnowledge? {
        val parts = raw.split("|")
        if (parts.size < 15) return null
        return try {
            val bits = key.split("|", limit = 2)
            if (bits.size != 2) return null
            val pkg = bits[0]
            val hash = bits[1].toIntOrNull() ?: return null
            val clearBounds = parseBounds(parts[7])
            val clear = if (parts[6].isBlank() || clearBounds == null) null else KnownControl(
                label = parts[6],
                role = parts[8].ifBlank { "BUTTON" },
                bounds = clearBounds,
                howOperated = "tap",
            )
            ScreenKnowledge(
                packageName = pkg,
                screenKind = runCatching { ScreenKind.valueOf(parts[1]) }.getOrDefault(ScreenKind.UNKNOWN),
                structureHash = parts[2].toIntOrNull() ?: hash,
                affordances = parts[3].split(",").filter { it.isNotBlank() }.toSet(),
                searchEntryHint = parts[4].ifBlank { null },
                winningSubmitMechanism = parts[5].ifBlank { null }?.let {
                    runCatching { SubmitMechanism.valueOf(it) }.getOrNull()
                },
                clearControl = clear,
                backBehaviour = runCatching { BackBehaviour.valueOf(parts[9]) }
                    .getOrDefault(BackBehaviour.UNKNOWN),
                backButtonBounds = parseBounds(parts[10]),
                observations = parts[11].toIntOrNull() ?: 0,
                lastSeenAt = parts[12].toLongOrNull() ?: 0L,
                backRecoveryFailed = parts[13] == "1",
                controls = parts[14].split(";").mapNotNull { entry ->
                    if (entry.isBlank()) return@mapNotNull null
                    val c = entry.split(":")
                    if (c.size < 4) return@mapNotNull null
                    val b = parseBounds(c[2]) ?: return@mapNotNull null
                    KnownControl(label = c[0], role = c[1], bounds = b, howOperated = c[3])
                },
                // NAV-SCREEN fix (2026-11-11, N10): 16th field, absent
                // in entries written before this build (decode to empty).
                navTabs = if (parts.size > 15) parts[15].split(";").mapNotNull { entry ->
                    if (entry.isBlank()) return@mapNotNull null
                    val t = entry.split(":")
                    if (t.size < 5) return@mapNotNull null
                    val b = parseBounds(t[3]) ?: return@mapNotNull null
                    KnownNavTab(
                        label = t[0],
                        position = t[1].toIntOrNull() ?: return@mapNotNull null,
                        role = t[2],
                        bounds = b,
                        selected = t[4] == "1",
                    )
                } else emptyList(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBounds(raw: String): TargetBounds? {
        if (raw.isBlank()) return null
        val p = raw.split(",").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return null
        return TargetBounds(p[0], p[1], p[2], p[3])
    }

    private fun sanitise(v: String): String =
        v.replace("|", " ").replace(";", " ").replace(":", " ").replace("\n", " ")
}

/**
 * The process-wide knowledge base (mirrors AppProfileStoreHolder).
 * PlanWiring.newExecutor reads it for the planner (replan
 * recovery) and the executor (submit order + observation
 * recording); both share the one disk-backed store, so knowledge
 * learned on one path is consulted on the other — and survives a
 * restart.
 */
object ScreenKnowledgeStoreHolder {
    @Volatile
    private var instance: ScreenKnowledgeStore? = null

    fun getOrCreate(context: Context): ScreenKnowledgeStore {
        return instance ?: synchronized(this) {
            instance ?: ScreenKnowledgeStore(
                SharedPreferencesScreenKnowledgePersistence(context.applicationContext ?: context),
            ).also { instance = it }
        }
    }

    /** Test seam: replace the shared store. */
    fun setForTest(store: ScreenKnowledgeStore?) {
        instance = store
    }
}
