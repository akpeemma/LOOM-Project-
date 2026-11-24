// Test-local stub — see VoiceSessionManagerTestStubs.kt.
package com.loom.assistant.interaction.pointer

enum class PointerPolicy { SHOW, HIDE, AUTO }

object PointerPolicyStore {
    sealed interface SetResult {
        object Applied : SetResult
        object Unchanged : SetResult
        object Failed : SetResult
    }

    fun set(context: android.content.Context, policy: PointerPolicy): SetResult =
        SetResult.Applied

    fun label(policy: PointerPolicy): String = policy.name
}
