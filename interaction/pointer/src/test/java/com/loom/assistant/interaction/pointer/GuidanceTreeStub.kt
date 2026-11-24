package com.loom.assistant.interaction.pointer

import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * JVM test stub for the production [DefaultGuidanceTreeSource].
 *
 * Compiled INSTEAD of the real GuidanceTreeDefaults.kt (which pulls in the
 * Android-coupled ScreenAwarenessHolder). Reports "not bound" — every test
 * injects its own fake tree explicitly.
 */
object DefaultGuidanceTreeSource : GuidanceTreeSource {
    override val isBound: Boolean get() = false
    override fun snapshot(): ScreenModel? = null
}
