package com.loom.assistant.execution.screenawareness

/**
 * NAV-SCREEN fix (2026-11-11, David's order ~19:29 WAT) — N5, the
 * screenshot / visual capture channel (blueprint §5.3).
 *
 * David ordered LOOM to be able to screenshot whatever app is open.
 * This file is the pure-Kotlin half of that channel: the consent
 * gate, the capture lifecycle state machine, and the visual
 * observation carried alongside the accessibility tree. The Android
 * half (AccessibilityService.takeScreenshot, API 30+) lives in
 * LoomAccessibilityService.requestScreenshot and only ever runs
 * through this gate.
 *
 * Privacy boundaries (blueprint §5.3, documented here and in the
 * final report — the capture is NEVER silent):
 *  - No capture without a grant. [ScreenshotCaptureGate.capture]
 *    refuses when no grant is held, and nothing is captured, stored
 *    or logged on refusal.
 *  - Grants are task-scoped: a grant covers ONE navigation capture
 *    for the task that asked LOOM to navigate, and is consumed by
 *    that capture. There is no standing "always capture" grant and
 *    no background capture.
 *  - Frames are held in memory only, downscaled at capture time,
 *    never written to disk, never attached to logs, and discarded
 *    after the step that requested them.
 *  - A screenshot supplements the tree; it never replaces it. The
 *    tree still gives roles, labels, bounds and state; the frame
 *    gives what the tree cannot (e.g. an icon-only tab glyph).
 *  - Banking / financial / authenticator apps stay guidance-only
 *    (§16.2): a frame may be captured there, but LOOM must never
 *    tap, type or submit inside them — the gate does not authorize
 *    any action, only the observation.
 *  - OWN-OVERLAY GUARANTEE (Mark 57 transfer, 2026-11-11, M4):
 *    LOOM's own overlay window is NEVER vision input. Pixels are
 *    never an input to target resolution: [VisualDetection] and
 *    [NavigationResolver] read the accessibility tree only, so a
 *    pixel of the LOOM orb (or of LOOM's own chat) in a frame can
 *    never become a tap target — a model shown the assistant's
 *    own UI is being asked a leading question. The frame's
 *    pixelBytes is carried for a future vision tier only, and
 *    that tier must mask the overlay rects before reading it.
 *    Enforced by construction (no resolver takes pixels) and by
 *    test (NavigationScreenTest: identical detection with and
 *    without pixelBytes).
 *  - Icon / OCR understanding of a frame is NOT built in this
 *    round: the channel carries pixels; turning a Reels glyph into
 *    the label "Reels" from pixels alone is listed as not built in
 *    the final report. The tab resolver works from the tree first
 *    (label → synonym → ordinal → positional); the frame exists so
 *    a future vision tier has a source to read.
 */

/** One visual frame, carried on [ScreenModel.visual]. */
data class VisualObservation(
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
    /** Downscaled RGB bytes (width*height*3), or null when the frame could not be decoded. */
    val pixelBytes: ByteArray? = null,
    /** Provenance — always "screenshot" for frames from this channel. */
    val source: String = "screenshot",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualObservation) return false
        return width == other.width && height == other.height &&
            capturedAtMs == other.capturedAtMs && source == other.source &&
            (pixelBytes == null && other.pixelBytes == null ||
                pixelBytes != null && other.pixelBytes != null &&
                pixelBytes.contentEquals(other.pixelBytes))
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + capturedAtMs.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (pixelBytes?.contentHashCode() ?: 0)
        return result
    }
}

/** Lifecycle of the screenshot channel. */
sealed class ScreenshotCaptureState {
    /** No grant held and no capture in flight. */
    data object Idle : ScreenshotCaptureState()

    /** A grant is held for one capture for [taskId]. Consumed by the capture. */
    data class Granted(val taskId: String) : ScreenshotCaptureState()

    /** A capture is running for [taskId]. */
    data class Capturing(val taskId: String) : ScreenshotCaptureState()

    /** The last capture produced [frame]. */
    data class Captured(val frame: VisualObservation) : ScreenshotCaptureState()

    /** The last capture attempt failed — [reason] names why. Nothing was captured. */
    data class Failed(val reason: String) : ScreenshotCaptureState()
}

/**
 * The consent gate for screen capture. Pure Kotlin, no Android
 * coupling — the service and the executor share this one gate.
 *
 * Rules, enforced by construction:
 *  - [capture] without a grant returns Failed and captures nothing.
 *  - A grant is task-scoped and single-use: the first capture
 *    consumes it; a second capture needs a new grant.
 *  - [revoke] drops any grant immediately (shutdown / stop paths).
 */
class ScreenshotCaptureGate {
    private var state: ScreenshotCaptureState = ScreenshotCaptureState.Idle

    fun state(): ScreenshotCaptureState = state

    /** True when a capture for [taskId] may run right now. */
    fun isGrantedFor(taskId: String): Boolean {
        val s = state
        return s is ScreenshotCaptureState.Granted && s.taskId == taskId
    }

    /**
     * Grants ONE capture for [taskId] — the navigation task David
     * asked LOOM to do, under its approved plan cover. The executor
     * calls this immediately before the NAVIGATE_TAB capture and the
     * capture consumes the grant. This is the consent record: a
     * capture is always tied to an explicit navigation instruction,
     * never to a background timer or a standing permission.
     */
    fun grantForTask(taskId: String) {
        state = ScreenshotCaptureState.Granted(taskId)
    }

    /** Drops any grant. Called on stop / shutdown / task end. */
    fun revoke() {
        state = ScreenshotCaptureState.Idle
    }

    /**
     * Runs [acquire] (the platform capture) under the gate. Without
     * a grant for [taskId] this returns Failed and [acquire] is never
     * called — nothing is captured. On success the grant is consumed
     * and the state holds the frame; on failure the state holds the
     * reason and the grant is consumed too (a failed capture is not
     * a standing permission to retry silently — the caller must
     * re-grant, i.e. David must ask again).
     */
    fun capture(taskId: String, acquire: () -> VisualObservation?): ScreenshotCaptureState {
        if (!isGrantedFor(taskId)) {
            state = ScreenshotCaptureState.Failed(
                "Screenshot capture needs an explicit navigation task first — " +
                    "nothing was captured.",
            )
            return state
        }
        state = ScreenshotCaptureState.Capturing(taskId)
        val frame = try {
            acquire()
        } catch (e: Exception) {
            state = ScreenshotCaptureState.Failed(
                "Screenshot capture failed: ${e.message ?: "unknown error"}",
            )
            return state
        }
        state = if (frame != null) {
            ScreenshotCaptureState.Captured(frame)
        } else {
            ScreenshotCaptureState.Failed(
                "Screenshot capture returned no frame — nothing was captured.",
            )
        }
        return state
    }
}
