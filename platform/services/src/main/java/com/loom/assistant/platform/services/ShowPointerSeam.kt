package com.loom.assistant.platform.services

/**
 * Accessibility fix (2026-11-05, audit plan step 2 — the keystone seam):
 * the pure, JVM-testable decision behind [LoomOverlayService.showPointerNow].
 *
 * The service itself is an Android Service and cannot run in the JVM test
 * harness, so the verified-render decision lives here as pure logic over a
 * [ShowPointerHost] the service adapts its real collaborators to. The
 * public seam on the service is a thin wiring over [run]/[runDetailed];
 * the tests in ShowPointerSeamTest exercise exactly the decision the
 * service runs.
 *
 * The rule (audit §7.1 — words are not action): the pointer counts as
 * shown ONLY when every step verified — the SHOW policy persisted, a
 * renderer resolved and verified attached to its window, the idle render
 * issued through the interaction layer, and the renderer still attached
 * afterwards. Any failure returns false; the caller (Home button, typed
 * chat, voice ack) gates its confirmation on that boolean and says one
 * plain sentence when it is false. This unit never speaks or posts text.
 *
 * Pointer failure fix (2026-11-05, device evidence: Home "Show pointer"
 * on build cfdb8fb returned false with no reason — a11y flag on, service
 * started, render never verified): the seam now also names the failing
 * step. [runDetailed] returns [ShowPointerResult] carrying the
 * [ShowPointerFailure] for the first step that failed, so Home can show
 * a distinct plain-words sentence per cause instead of one collapsed
 * "couldn't show it". [run] keeps the old boolean contract for existing
 * callers. The failure is also reported to the optional [onFailure]
 * callback so the Android wiring can log the step under the LoomPointer
 * tag — this pure unit never touches android.util.Log itself, so the JVM
 * harness can still compile it with no Android fakes.
 */
object ShowPointerSeam {

    /**
     * Runs the verified show-pointer sequence against [host].
     *
     * False when: the SHOW policy could not be persisted; no renderer
     * resolved (host unavailable — a11y service off — or attach
     * refused); the resolved renderer is not attached to its window
     * (stale cache dropped by ensureGuidanceRenderer, or detached
     * mid-call); the interaction layer has no renderer or the gate
     * refused the idle draw; or the renderer left the window during
     * the draw. True only when the idle render was issued against a
     * renderer verified attached before and after.
     */
    fun run(host: ShowPointerHost): Boolean = runDetailed(host).shown

    /**
     * Reason-carrying variant of [run]: the first failing step, or
     * null on success. [onFailure] is invoked exactly once, with the
     * failing reason, whenever the result is not shown — the service
     * passes a logger there; tests pass a recorder. The steps run in
     * the same order and short-circuit the same way as [run]: once a
     * step fails, no later step is attempted.
     */
    fun runDetailed(
        host: ShowPointerHost,
        onFailure: ((ShowPointerFailure) -> Unit)? = null,
    ): ShowPointerResult {
        fun fail(reason: ShowPointerFailure): ShowPointerResult {
            onFailure?.invoke(reason)
            return ShowPointerResult(shown = false, failure = reason)
        }
        if (!host.setPolicyShow()) return fail(ShowPointerFailure.POLICY_FAILED)
        val renderer = host.ensureRenderer() ?: return fail(host.rendererFailureReason())
        if (!renderer.isAttachedToWindow()) return fail(ShowPointerFailure.NOT_ATTACHED)
        if (!host.showPointerIdle()) return fail(host.idleFailureReason())
        if (!renderer.isAttachedToWindow()) return fail(ShowPointerFailure.NOT_ATTACHED)
        return ShowPointerResult(shown = true, failure = null)
    }
}

/**
 * Why a show-pointer attempt did not produce a verified render.
 * Exactly one value is reported per attempt — the FIRST step that
 * failed — so the caller can name it in plain words and in logs.
 *
 * The seam itself decides every value except [TIMEOUT]: the seam runs
 * synchronously and never waits, so a wait that expired with no seam
 * result at all is decided by the caller polling it (Home's retry
 * loop), which reports TIMEOUT instead.
 */
enum class ShowPointerFailure {
    /** The SHOW pointer policy could not be persisted. */
    POLICY_FAILED,

    /** No trusted host: the accessibility service is not live
     * (instance null / not bound), so no renderer could resolve. */
    NO_HOST,

    /** A live host exists but refused the view (addGuidanceOverlayView
     * returned false, or the renderer could not be created). */
    HOST_REFUSED,

    /** A renderer resolved but was not attached to its window when
     * checked — before the draw, or it left the window during it. */
    NOT_ATTACHED,

    /** The interaction layer is absent (null), so no idle render
     * could be issued at all. */
    NO_INTERACTION,

    /** The interaction layer exists but refused the idle draw
     * (policy gate / layer said no). */
    GATE_REFUSED,

    /** The caller waited the full bounded retry window and no seam
     * attempt ever ran (service instance / live host never appeared)
     * or none succeeded. Decided by the polling caller, never here. */
    TIMEOUT,
}

/** The result of [ShowPointerSeam.runDetailed]: shown only on a
 * verified render; [failure] names the first failing step, null on
 * success. */
data class ShowPointerResult(
    val shown: Boolean,
    val failure: ShowPointerFailure?,
)

/**
 * The collaborators [ShowPointerSeam.run] needs, adapted by
 * LoomOverlayService.showPointerNow from the real service state: the
 * pointer policy store, the service's gated-renderer resolution plus the
 * cached raw renderer, and the interaction layer's showPointerIdle.
 */
interface ShowPointerHost {

    /** Persist the SHOW pointer policy. False when the write failed. */
    fun setPolicyShow(): Boolean

    /**
     * Resolve and attach the gated renderer. Null when the trusted host
     * is unavailable (a11y service off) or the host refused the view.
     * Which of the two it was is reported by [rendererFailureReason];
     * the seam calls it only when this returned null, so an adapter
     * can read live state at that moment.
     */
    fun ensureRenderer(): ShowPointerRenderer?

    /** Render the idle indicator through the interaction layer. */
    fun showPointerIdle(): Boolean

    /**
     * Why [ensureRenderer] returned null. Default [ShowPointerFailure.HOST_REFUSED]:
     * a host that cannot tell "no host" from "host refused" reports the
     * refusal. The service adapter overrides it — NO_HOST when the
     * accessibility service is not live, HOST_REFUSED otherwise.
     */
    fun rendererFailureReason(): ShowPointerFailure = ShowPointerFailure.HOST_REFUSED

    /**
     * Why [showPointerIdle] returned false. Default
     * [ShowPointerFailure.GATE_REFUSED]: a layer that cannot tell "no
     * layer" from "layer refused" reports the refusal. The service
     * adapter overrides it — NO_INTERACTION when the interaction layer
     * is null, GATE_REFUSED otherwise.
     */
    fun idleFailureReason(): ShowPointerFailure = ShowPointerFailure.GATE_REFUSED
}

/** A resolved renderer whose live window-attach state can be read. */
interface ShowPointerRenderer {
    fun isAttachedToWindow(): Boolean

    /**
     * Pointer failure fix (2026-11-05): the renderer's current size
     * as "WxH", for the verify-time size log in
     * LoomOverlayService.runShowPointerDetailedNow. DIAGNOSTIC ONLY
     * — the seam never decides on size: a view added in the same
     * call reads 0x0 until its first layout pass runs. Default ""
     * for hosts that cannot report a size.
     */
    fun sizeDescription(): String = ""
}
