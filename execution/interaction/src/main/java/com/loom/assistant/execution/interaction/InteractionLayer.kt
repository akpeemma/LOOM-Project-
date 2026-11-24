package com.loom.assistant.execution.interaction

import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.interaction.pointer.ExecutionFeedback
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.interaction.pointer.PointerPolicy

/**
 * Stage 2C — the universal interaction layer skeleton.
 *
 * Owns all interaction state for guidance + demonstration across
 * Character/Orb/Invisible modes. The 2B design's InteractionLayer contract:
 * mode-independent, interrupt-first, single AuthGate dispatch path.
 *
 * 2C scope (Worker B): the layer skeleton, the halt path (§5), and the
 * voice routing seams. Guidance resolution, the ActionQueue, the
 * AnchorResolver and the GuidanceRenderer implementation arrive with
 * Workers C/D — this skeleton guarantees the contract they will drive:
 * every sequence routes through the halt flag and the renderer hooks, and
 * every halt follows the exact §5.1 sequence via [HaltController].
 *
 * Depends on interfaces, not services — the host (platform:services) wires
 * the delegates. The only android import is [RectF] in the renderer
 * contract, satisfied by a test fake on the JVM.
 *
 * The guidance rendering contract ([GuidanceRendererContract]) moved to
 * interaction:pointer so the module edge runs one direction only
 * (execution:interaction → interaction:pointer).
 */

/** Cancel an in-flight gesture without dispatching anything new. */
fun interface GestureCanceller {
    fun cancel()
}

enum class SequenceKind {
    GUIDANCE,
    DEMONSTRATION,
}

class InteractionLayer(
    events: EventSink = EventSink {},
    audit: AuditLogger = AuditLogger { _, _ -> },
) {

    private val haltController = HaltController(events, audit)

    /** Wired by the host (platform:services) once the renderer exists. */
    var renderer: GuidanceRendererContract? = null

    /**
     * Master implementation §6 — optional "Show me what you're doing"
     * feed. The host sets this alongside the renderer; the layer only
     * REPORTS sequence-level facts into it (intended action, sequence
     * live/cleared). Null until wired — reporting is a no-op then.
     */
    var feedback: ExecutionFeedback? = null

    /** Wired by the host to the accessibility gesture path. */
    var gestureCanceller: GestureCanceller? = null

    /**
     * Wired by the host to AuthGate.cancelPending. The queue registers each
     * pending confirmation id via [trackPendingConfirmation]; halt cancels
     * all of them (§5.1 step 5).
     */
    var confirmationCanceller: ((pendingId: String) -> Unit)? = null

    /** Host hook: release voice-relevant state on halt (§5.1 step 4). */
    var onSequenceEnded: (() -> Unit)? = null

    /**
     * Stage 2C (F): host hook — a display-only guidance session was
     * requested ("guide me to <x>"). The host (platform:services) drives the
     * GuidanceSession + trusted overlay; the layer only records state.
     */
    var onGuidanceRequested: ((query: String) -> Unit)? = null

    /**
     * Stage 2C (F): host hook — a demonstration sequence was requested
     * ("show me how to <x>" / "tap it for me"). The host builds the
     * ActionPlan from the active guidance target and runs the ActionQueue.
     */
    var onDemonstrationRequested: ((goal: String) -> Unit)? = null

    /**
     * Stage 2C (F): host hook for "guide next" / "guide back" step
     * navigation on the live guidance session. Returns a short spoken
     * status ("Step 2 of 3.", the honest failure reason, ...), or null when
     * the host has no live session.
     */
    var onGuideStep: ((next: Boolean) -> String?)? = null

    /** The stop path of the most recent halt(), for the queue's HaltProvider. */
    var lastHaltSource: HaltSource? = null
        private set

    /** Runtime pointer policy (persisted policy lives in PointerPolicyStore). */
    var pointerPolicy: PointerPolicy = PointerPolicy.AUTO
        private set

    /** Last mode reported by ModeController — anchor plumbing follows. */
    var currentMode: String = ""
        private set

    private data class SequenceState(
        val kind: SequenceKind,
        val query: String,
        val stepIndex: Int = 0,
        val totalSteps: Int = 0,
    )

    private var active: SequenceState? = null
    private val pendingConfirmations = mutableListOf<String>()

    /** True while a sequence is live and not halted (voice "stop" consults). */
    val isSequenceActive: Boolean
        get() = active != null && !haltController.haltRequested

    /** The current sequence's query/goal, for demo follow-ups. */
    val activeQuery: String?
        get() = active?.query

    /** The ActionQueue checks this before EVERY dispatch and token request. */
    fun isHaltRequested(): Boolean = haltController.haltRequested

    /**
     * Thin 2C entry: records the guidance sequence under the halt flag.
     * Resolution + queue arrive with Workers C/D; the renderer hooks are the
     * live seams they will drive.
     */
    fun startGuidance(query: String) {
        if (query.isBlank()) return
        haltController.reset()
        active = SequenceState(SequenceKind.GUIDANCE, query.trim())
        // §6: report the intended action for "Show me what you're doing".
        feedback?.setIntendedAction("Guide to \"${query.trim()}\"")
        feedback?.setSequenceActive(true)
        try {
            onGuidanceRequested?.invoke(query.trim())
        } catch (_: Exception) {
        }
    }

    /**
     * Thin 2C entry: records the demonstration sequence under the halt flag.
     * Per-sequence approval + the ActionQueue arrive with Worker D.
     */
    fun startDemonstration(goal: String) {
        if (goal.isBlank()) return
        haltController.reset()
        active = SequenceState(SequenceKind.DEMONSTRATION, goal.trim())
        // §6: report the intended action for "Show me what you're doing".
        feedback?.setIntendedAction("Demonstrate: ${goal.trim()}")
        feedback?.setSequenceActive(true)
        try {
            onDemonstrationRequested?.invoke(goal.trim())
        } catch (_: Exception) {
        }
    }

    /**
     * Stage 2C (F): a sequence finished on its own (not halted) — clear the
     * active state WITHOUT the §5.1 halt sequence and without an
     * INTERRUPTED report. The host calls this when the guidance session
     * ends normally or the queue completes.
     */
    fun endSequence() {
        // Guard: the halt path's endSession hook and the session's own
        // Finished listener both funnel here — only the first one posts.
        if (active == null) return
        active = null
        // §6: the sequence is over; the last action/result stay visible.
        feedback?.setSequenceActive(false)
        feedback?.setNextStep(null)
        try {
            onSequenceEnded?.invoke()
        } catch (_: Exception) {
        }
    }

    /** Voice "guide next" / "guide back" — routed to the host's live session. */
    fun guideStep(next: Boolean): String? = try {
        onGuideStep?.invoke(next)
    } catch (_: Exception) {
        null
    }

    /**
     * §5.1: delegates to [HaltController] with this layer's hooks —
     * halt flag → cancel in-flight gesture → renderer?.hide() immediately →
     * end session → cancel pending AuthGate confirmations → INTERRUPTED
     * {stepIndex, totalSteps, source}, never "completed".
     */
    fun halt(source: HaltSource): HaltReport {
        val seq = active
        lastHaltSource = source
        // §6: the sequence is over (interrupted, never "completed").
        feedback?.setSequenceActive(false)
        feedback?.setNextStep(null)
        val hooks = object : HaltHooks {
            override fun cancelInFlightGesture() {
                gestureCanceller?.cancel()
            }

            override fun hideRenderer() {
                renderer?.hide()
            }

            override fun endSession() {
                // NOTE: pending ids are NOT cleared here — §5.1 runs
                // cancelPendingConfirmations() after endSession(), and that
                // step owns the ids (clearing here would make step 5 a no-op).
                active = null
                onSequenceEnded?.invoke()
            }

            override fun cancelPendingConfirmations() {
                val ids = pendingConfirmations.toList()
                pendingConfirmations.clear()
                ids.forEach { confirmationCanceller?.invoke(it) }
            }
        }
        return haltController.halt(
            source,
            seq?.stepIndex ?: 0,
            seq?.totalSteps ?: 0,
            hooks,
        )
    }

    /** The queue registers each pending AuthGate confirmation id here. */
    fun trackPendingConfirmation(pendingId: String) {
        pendingConfirmations.add(pendingId)
    }

    fun setPointerPolicy(policy: PointerPolicy) {
        pointerPolicy = policy
    }

    /**
     * "fix everything": POINTER_MOVE — nudge the visible indicator one
     * 48dp step. Display-only: the offset never retargets taps (tap
     * paths still resolve the real target and need AuthGate
     * confirmation). Renderer absent → unavailable; renderer idle →
     * not visible. Both are honest reports, never claimed moves.
     */
    fun nudgePointer(direction: PointerDirection): String {
        val r = renderer ?: return "Pointer movement isn't available right now."
        return if (r.nudgePointer(direction, 48f)) {
            "Moved the pointer ${direction.label}."
        } else {
            "The pointer isn't visible right now — start guidance first, then I can move it."
        }
    }

    /**
     * Device-evidence fix (2026-11-05, pointer H3): "show the pointer"
     * renders the persistent idle indicator when no step is live.
     * Returns true when an indicator is now visible (a live step
     * already counts); false when there is no renderer or the gate
     * refused to draw — the voice layer then says so honestly instead
     * of claiming the pointer is showing.
     */
    fun showPointerIdle(): Boolean {
        val r = renderer ?: return false
        return try {
            r.showIdleIndicator()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Mode switches leave the session untouched; only the beam anchor moves.
     * Anchor-event plumbing (AnchorResolver) arrives with Workers C/D — this
     * records the mode so the seam is ready.
     */
    fun onModeChanged(mode: String) {
        currentMode = mode
    }
}
