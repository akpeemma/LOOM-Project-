// Test-local stub — see VoiceSessionManagerTestStubs.kt.
package com.loom.assistant.execution.interaction

import com.loom.assistant.core.common.PointerDirection

enum class HaltSource { VOICE }

open class InteractionLayer {
    open val isSequenceActive: Boolean = false
    open val activeQuery: String? = null
    open fun halt(source: HaltSource) {}
    open fun startGuidance(query: String) {}
    open fun guideStep(next: Boolean): String? = null
    open fun startDemonstration(goal: String) {}
    // "fix everything": mirrors the real layer's success text so the
    // manager test can prove forwarding end-to-end.
    open fun nudgePointer(direction: PointerDirection): String =
        "Moved the pointer ${direction.label}."
    // Pointer H3: the real layer returns false with no renderer — the
    // stub has no renderer, so it mirrors that honestly.
    open fun showPointerIdle(): Boolean = false
}
