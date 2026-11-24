// Test-local stub — see VoiceSessionManagerTestStubs.kt.
package com.loom.assistant.presentation.modes

enum class AssistantMode { CHARACTER, ORB, INVISIBLE }

/**
 * Test-local shadow of the production sealed ModeResult: only the
 * variants the manager's handlers branch on. Production adds no other
 * variants; if it does, this stub must gain them or vmgr stops compiling.
 */
sealed interface ModeResult {
    data object Success : ModeResult
    data object PermissionRequired : ModeResult
    data class Failed(val reason: String) : ModeResult
    data class Unavailable(val reason: String) : ModeResult
}

object ModeController {
    fun applyMode(
        context: android.content.Context,
        target: AssistantMode,
        cb: (ModeResult) -> Unit,
    ) {
        cb(ModeResult.Success)
    }

    fun ackText(result: ModeResult, shortLabel: String): String =
        "Switched to $shortLabel."

    fun shortLabel(mode: AssistantMode): String = mode.name

    /** Test default: service not running (activation plans StartService). */
    fun isOverlayRunning(context: android.content.Context): Boolean = false

    /** Test default: no live backend. */
    fun boundMode(): AssistantMode? = null

    /** Mirrors the production getMode() default: ORB when nothing stored. */
    fun getMode(context: android.content.Context): AssistantMode = AssistantMode.ORB
}
