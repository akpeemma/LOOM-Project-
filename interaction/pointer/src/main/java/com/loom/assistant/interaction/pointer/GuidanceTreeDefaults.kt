package com.loom.assistant.interaction.pointer

import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Stage 2C — production [GuidanceTreeSource]: the live accessibility tree
 * via [ScreenAwarenessHolder].
 *
 * Split out of GuidanceSession.kt so JVM tests can compile the session with
 * a fake tree: the holder pulls in Android-coupled classes
 * (AccessibilityScreenAwareness -> AccessibilityNodeInfo) that have no JVM
 * fake here.
 */
object DefaultGuidanceTreeSource : GuidanceTreeSource {
    override val isBound: Boolean get() = ScreenAwarenessHolder.isBound
    override fun snapshot(): ScreenModel? = ScreenAwarenessHolder.current.snapshot()
}
