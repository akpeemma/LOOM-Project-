package com.loom.assistant.execution.interaction

/**
 * Stage 2C (F) — host-side registry for the single live [InteractionLayer].
 *
 * The host (platform:services `LoomOverlayService`) registers the layer it
 * constructs; `LoomAccessibilityService` (the §5.2 P4 toggle-revocation
 * path) reads it back. This keeps the module dependency one-way —
 * platform:services already depends on execution:interaction, and the
 * interaction module never depends back on services.
 *
 * Pure Kotlin: no android.* imports.
 */
object InteractionLayerRegistry {

    @Volatile
    private var layer: InteractionLayer? = null

    fun register(layer: InteractionLayer?) {
        this.layer = layer
    }

    fun get(): InteractionLayer? = layer
}
