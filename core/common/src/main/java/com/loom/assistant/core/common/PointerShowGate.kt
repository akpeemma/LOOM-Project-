package com.loom.assistant.core.common

/**
 * Accessibility fix (2026-11-05, audit §7.1 — words are not action).
 *
 * Every "show the pointer" confirmation is gated on a VERIFIED render.
 * The Nov 5 transcript failure was the app confirming a policy write
 * ("The pointer will now stay visible during guidance.") while nothing
 * was drawn — and the model narrating the request ("I've requested…")
 * as if it were the action. This object owns the ONLY confirmation
 * wording for a show-pointer request, chosen from the render result
 * and nothing else:
 *
 * - rendered == true: the idle indicator was verified on screen.
 * - rendered == false: nothing rendered. One plain sentence saying
 *   so, with what to tap — never "I've requested", never "done"
 *   without verification, never an internal state word.
 *
 * Pure Kotlin, no Android imports, so typed chat, voice and the JVM
 * harness all decide from the same strings. Callers supply the
 * verified boolean from their render seam
 * (LoomOverlayService.showPointerNow() for chat, the interaction
 * layer's showPointerIdle() for voice) and post/speak exactly what
 * comes back here.
 *
 * Plain-words rule: no string here may contain attached / detached /
 * anchored / overlay / guidance window.
 */
object PointerShowGate {

    /** The render was verified on screen. */
    const val RENDERED_ACK =
        "The pointer is now visible — you can see it on screen. " +
            "Say 'guide me to' something to point at it."

    /**
     * Nothing rendered. One plain sentence: what happened + what to
     * tap (turn LOOM on in Settings, under Accessibility).
     */
    const val NOT_RENDERED_ACK =
        "The pointer isn't on screen right now — LOOM needs to be " +
            "turned on in Settings, under Accessibility."

    /** The policy write itself failed — nothing was requested or rendered. */
    const val SAVE_FAILED_ACK =
        "I could not save that — the pointer setting is unchanged."

    /**
     * The ack for a POINTER_SHOW request, from the verified render
     * result only. Never call this with anything but the seam's
     * verified boolean.
     */
    fun ack(rendered: Boolean): String =
        if (rendered) RENDERED_ACK else NOT_RENDERED_ACK
}
