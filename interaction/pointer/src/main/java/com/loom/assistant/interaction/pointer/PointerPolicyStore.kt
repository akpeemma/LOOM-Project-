package com.loom.assistant.interaction.pointer

import android.content.Context

/**
 * Stage 2C — Android adapter for the pointer policy.
 *
 * API-identical to the Stage 2B `PointerPolicyStore` object it replaces:
 * existing callers (VoiceSessionManager, SettingsScreenView,
 * LoomOverlayService) are untouched. All logic now lives in the pure
 * [PointerPolicyRepository]; this object only binds it to
 * SharedPreferences.
 */
object PointerPolicyStore {

    private const val PREFS = "loom_pointer"
    private const val KEY_POLICY = "pointer_policy"

    /** Truthful write outcome — callers only confirm [Applied]. */
    sealed interface SetResult {
        object Applied : SetResult
        object Unchanged : SetResult
        object Failed : SetResult
    }

    private class PrefsStorage(context: Context) : PointerPolicyStorage {
        private val prefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun load(): String? = try {
            prefs.getString(KEY_POLICY, null)
        } catch (_: Exception) {
            null
        }

        override fun save(name: String): Boolean = try {
            prefs.edit().putString(KEY_POLICY, name).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun get(context: Context): PointerPolicy =
        PointerPolicyRepository(PrefsStorage(context)).get()

    fun set(context: Context, policy: PointerPolicy): SetResult =
        when (PointerPolicyRepository(PrefsStorage(context)).set(policy)) {
            PointerPolicyRepository.SetResult.Applied -> SetResult.Applied
            PointerPolicyRepository.SetResult.Unchanged -> SetResult.Unchanged
            PointerPolicyRepository.SetResult.Failed -> SetResult.Failed
        }

    /** Plain-words label for spoken acknowledgements and Settings rows. */
    fun label(policy: PointerPolicy): String = PointerPolicyRepository.label(policy)

    /** Settings row title-case label. */
    fun titleLabel(policy: PointerPolicy): String = PointerPolicyRepository.titleLabel(policy)
}
