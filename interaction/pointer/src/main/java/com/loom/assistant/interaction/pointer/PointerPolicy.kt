package com.loom.assistant.interaction.pointer

/**
 * Stage 2B (D3) — pointer visibility policy, David's decision 3.
 *
 * The pointer is independent of automation: SHOW always draws it during a
 * guidance/demonstration sequence, HIDE never draws it, AUTO shows it for
 * guided/demonstration contexts (and will hide it for autonomous ones in
 * 2D). Persisted user setting, DEFAULT = AUTO. Changeable by voice
 * ("show the pointer", "hide the pointer", "pointer auto") and in
 * Settings → Automation.
 *
 * Stage 2C: the persistence logic is pure Kotlin ([PointerPolicyRepository]
 * over an injected [PointerPolicyStorage]) so it is JVM-testable (T1).
 * The Android SharedPreferences adapter lives in PointerPolicyStore.kt and
 * keeps the exact API existing callers use.
 */
enum class PointerPolicy {
    SHOW,
    HIDE,
    AUTO,
}

/**
 * Pure storage seam: implemented by SharedPreferences on device, by a fake
 * in tests. Stores the enum [name], or null when nothing was persisted.
 */
interface PointerPolicyStorage {
    fun load(): String?
    fun save(name: String): Boolean
}

/**
 * Pure pointer-policy logic. Writes are verified by read-back: [set]
 * reports [SetResult.Applied] only when the persisted value actually
 * changed, so voice acknowledgements confirm real state — the Stage 2A.1
 * truthfulness rule.
 *
 * This repository knows NOTHING about automation state: "hide the pointer"
 * never disables automation and "stop automation" never hides the pointer —
 * those are separate flags owned by their own components.
 */
class PointerPolicyRepository(private val storage: PointerPolicyStorage) {

    /** Truthful write outcome — callers only confirm [Applied]. */
    sealed interface SetResult {
        object Applied : SetResult
        object Unchanged : SetResult
        object Failed : SetResult
    }

    fun get(): PointerPolicy = try {
        val name = storage.load()
        if (name != null) PointerPolicy.valueOf(name) else PointerPolicy.AUTO
    } catch (_: Exception) {
        PointerPolicy.AUTO
    }

    fun set(policy: PointerPolicy): SetResult {
        return try {
            if (get() == policy) return SetResult.Unchanged
            if (!storage.save(policy.name)) return SetResult.Failed
            if (get() == policy) SetResult.Applied else SetResult.Failed
        } catch (_: Exception) {
            SetResult.Failed
        }
    }

    companion object {
        /** Plain-words label for spoken acknowledgements and Settings rows. */
        fun label(policy: PointerPolicy): String = when (policy) {
            PointerPolicy.SHOW -> "always show"
            PointerPolicy.HIDE -> "hidden"
            PointerPolicy.AUTO -> "automatic"
        }

        /** Settings row title-case label. */
        fun titleLabel(policy: PointerPolicy): String = when (policy) {
            PointerPolicy.SHOW -> "Always show"
            PointerPolicy.HIDE -> "Hidden"
            PointerPolicy.AUTO -> "Automatic"
        }
    }
}
