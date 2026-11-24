package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.intelligence.planner.AppProfile
import com.loom.assistant.intelligence.planner.AppProfilePersistence
import com.loom.assistant.intelligence.planner.AppProfileStore
import com.loom.assistant.intelligence.planner.AppRecipe
import com.loom.assistant.intelligence.planner.ProfileVerificationStatus

/**
 * AOIE Phase 6 — disk persistence for the app registry.
 *
 * Before this the only AppProfilePersistence was in-memory: every
 * learned win died with the process and the registry restarted
 * from seeds on every launch. This SharedPreferences implementation
 * is the seam's disk form — one entry per package, fields joined
 * with "|" (package names, labels and mechanism names never
 * contain "|" or ";", and recipe evidence is sanitised on write).
 * Seeds still overlay first (the store's init order), and a
 * persisted profile outranks its seed; a package with no entry
 * loads as its seed. No passwords, no screen content, no queries
 * are ever stored here — only submit orders, win counts,
 * structure hashes, verification state and recipe evidence.
 */
class SharedPreferencesAppProfilePersistence(
    context: Context,
) : AppProfilePersistence {

    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "loom_app_profiles"
        const val PREFIX = "profile|"
    }

    override fun load(): Map<String, AppProfile> {
        val out = LinkedHashMap<String, AppProfile>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(PREFIX)) continue
            val pkg = key.removePrefix(PREFIX)
            val raw = value as? String ?: continue
            decode(pkg, raw)?.let { out[pkg] = it }
        }
        return out
    }

    override fun save(profiles: Map<String, AppProfile>) {
        val editor = prefs.edit()
        for ((pkg, profile) in profiles) {
            editor.putString(PREFIX + pkg, encode(profile))
        }
        editor.apply()
    }

    private fun encode(p: AppProfile): String {
        val wins = p.mechanismWins.entries
            .joinToString(",") { "${it.key.name}=${it.value}" }
        val recipes = p.recipes.joinToString(";") { r ->
            "${r.name}:${if (r.verified) 1 else 0}:${sanitise(r.evidence)}"
        }
        return listOf(
            sanitise(p.label),
            p.category.name,
            p.preferredSubmitOrder.joinToString(",") { it.name },
            p.observations.toString(),
            wins,
            (p.lastSignatureHash ?: "").toString(),
            p.verificationStatus.name,
            (p.lastValidatedAt ?: "").toString(),
            sanitise(p.appVersion ?: ""),
            p.knownLimitations.joinToString(";") { sanitise(it) },
            recipes,
            p.searchEntryHints.joinToString(";") { sanitise(it) },
        ).joinToString("|")
    }

    private fun decode(pkg: String, raw: String): AppProfile? {
        val parts = raw.split("|")
        if (parts.size < 12) return null
        return try {
            val order = parts[2].split(",").mapNotNull {
                runCatching { SubmitMechanism.valueOf(it.trim()) }.getOrNull()
            }.ifEmpty { com.loom.assistant.intelligence.planner.DEFAULT_SUBMIT_ORDER }
            val wins = parts[4].split(",").mapNotNull { entry ->
                val kv = entry.split("=")
                if (kv.size != 2) return@mapNotNull null
                val mech = runCatching { SubmitMechanism.valueOf(kv[0]) }.getOrNull()
                    ?: return@mapNotNull null
                mech to (kv[1].toIntOrNull() ?: 0)
            }.toMap()
            val recipes = parts[10].split(";").mapNotNull { entry ->
                if (entry.isBlank()) return@mapNotNull null
                val bits = entry.split(":", limit = 3)
                if (bits.size < 2) return@mapNotNull null
                // Steps/preconditions are seed data: the persisted
                // form stores only the verification state, and the
                // seed recipe (same package + name) supplies the
                // steps back. A recipe with no seed keeps empty steps
                // and is never executed from them.
                val seedRecipe = AppProfileStore.SEED_PROFILES
                    .firstOrNull { it.packageName == pkg }
                    ?.recipes?.firstOrNull { it.name == bits[0] }
                AppRecipe(
                    name = bits[0],
                    steps = seedRecipe?.steps ?: emptyList(),
                    preconditions = seedRecipe?.preconditions ?: emptyList(),
                    verified = bits[1] == "1",
                    evidence = bits.getOrNull(2).orEmpty(),
                )
            }
            AppProfile(
                packageName = pkg,
                label = parts[0].ifBlank { pkg },
                category = runCatching {
                    com.loom.assistant.intelligence.planner.AppCategory.valueOf(parts[1])
                }.getOrDefault(com.loom.assistant.intelligence.planner.AppCategory.OTHER),
                preferredSubmitOrder = order,
                searchEntryHints = parts[11].split(";").filter { it.isNotBlank() },
                observations = parts[3].toIntOrNull() ?: 0,
                mechanismWins = wins,
                lastSignatureHash = parts[5].toIntOrNull(),
                verificationStatus = runCatching {
                    ProfileVerificationStatus.valueOf(parts[6])
                }.getOrDefault(ProfileVerificationStatus.UNVERIFIED),
                lastValidatedAt = parts[7].toLongOrNull(),
                appVersion = parts[8].ifBlank { null },
                knownLimitations = parts[9].split(";").filter { it.isNotBlank() },
                recipes = recipes,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitise(v: String): String =
        v.replace("|", " ").replace(";", " ").replace("\n", " ")
}

/**
 * The process-wide profile store (AOIE Phase 6). The plan executor
 * and the search sequence previously each owned a fresh in-memory
 * store, so a win learned in one run never reached the other path
 * — and died on restart. One shared, disk-backed store now serves
 * both: PlanWiring.newExecutor and SearchExecutorWiring.install
 * read it, the learner writes through it.
 */
object AppProfileStoreHolder {
    @Volatile
    private var instance: AppProfileStore? = null

    fun getOrCreate(context: Context): AppProfileStore {
        return instance ?: synchronized(this) {
            instance ?: AppProfileStore(
                SharedPreferencesAppProfilePersistence(context.applicationContext ?: context),
            ).also { instance = it }
        }
    }

    /** Test seam: replace the shared store. */
    fun setForTest(store: AppProfileStore?) {
        instance = store
    }
}
