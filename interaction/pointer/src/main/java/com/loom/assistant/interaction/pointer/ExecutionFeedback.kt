package com.loom.assistant.interaction.pointer

import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Master implementation §6 — "Show me what you're doing": a READ-ONLY
 * view over the current execution state.
 *
 * This component executes nothing and dispatches nothing. It only holds
 * the latest facts, fed in by the components that own them:
 *  - what LOOM sees — [updateScreen], fed by the host from the live
 *    [ScreenModel] (redacted summary; labels are withheld inside
 *    sensitive packages, per [ScreenDataPolicy]);
 *  - identified target + pointer position — fed by [GuidanceRenderer]
 *    on showStep / previewMoveTo / nudgePointer / hide;
 *  - intended action + sequence state — fed by [InteractionLayer] on
 *    startGuidance / startDemonstration / endSequence / halt;
 *  - last action + result, next step — fed by the demonstration runner
 *    (ActionQueue) via [recordAction] / [setNextStep]; until that wiring
 *    lands those fields honestly stay null ("not reported yet").
 *
 * [snapshot] returns an immutable copy; [describe] renders it in plain
 * words for speech or a "What LOOM sees" panel. Thread-safe for the
 * single-writer / many-reader use here (@Volatile fields).
 */
class ExecutionFeedback {

    /** Redacted summary of the latest screen read. */
    data class ScreenSummary(
        val packageName: String,
        val totalElements: Int,
        val interactiveCount: Int,
        /** Labels of interactive elements; empty when labels are hidden. */
        val interactiveLabels: List<String>,
        /** True when the foreground app hides its labels (sensitive package). */
        val labelsHidden: Boolean,
    )

    /** The element the pointer is currently identifying. */
    data class TargetInfo(
        /** Human-readable label, or the step instruction when no label is known. */
        val label: String,
        val bounds: GeomRect,
        val role: String,
    )

    data class ExecutionSnapshot(
        val screen: ScreenSummary?,
        val target: TargetInfo?,
        val intendedAction: String?,
        val pointerPosition: GeomPoint?,
        val lastAction: String?,
        val lastResult: String?,
        val nextStep: String?,
        val sequenceActive: Boolean,
        val updatedAtMs: Long,
    )

    @Volatile
    private var screen: ScreenSummary? = null

    @Volatile
    private var target: TargetInfo? = null

    @Volatile
    private var intendedAction: String? = null

    @Volatile
    private var pointerPosition: GeomPoint? = null

    @Volatile
    private var lastAction: String? = null

    @Volatile
    private var lastResult: String? = null

    @Volatile
    private var nextStep: String? = null

    @Volatile
    private var sequenceActive: Boolean = false

    @Volatile
    private var updatedAtMs: Long = 0L

    /** Replace the screen summary from a fresh [ScreenModel]; null clears it. */
    fun updateScreen(model: ScreenModel?) {
        screen = model?.let {
            val interactive = it.elements.filter { el ->
                AssistantPlacement.isInteractive(el.role) && !el.bounds.isEmpty
            }
            val hidden = ScreenDataPolicy.isSensitivePackage(it.packageName)
            ScreenSummary(
                packageName = it.packageName,
                totalElements = it.elements.size,
                interactiveCount = interactive.size,
                interactiveLabels = if (hidden) emptyList()
                else interactive.map { el -> el.label() }.distinct().take(12),
                labelsHidden = hidden,
            )
        }
        touch()
    }

    fun setTarget(label: String, bounds: GeomRect, role: String) {
        target = TargetInfo(label, bounds, role)
        touch()
    }

    fun clearTarget() {
        target = null
        touch()
    }

    fun setIntendedAction(action: String?) {
        intendedAction = action
        touch()
    }

    fun setPointerPosition(position: GeomPoint?) {
        pointerPosition = position
        touch()
    }

    /** Last executed action and its outcome ("completed", "failed: …", …). */
    fun recordAction(action: String, result: String) {
        lastAction = action
        lastResult = result
        touch()
    }

    fun setNextStep(step: String?) {
        nextStep = step
        touch()
    }

    fun setSequenceActive(active: Boolean) {
        sequenceActive = active
        touch()
    }

    /** Immutable read-only view of the current state. */
    fun snapshot(): ExecutionSnapshot = ExecutionSnapshot(
        screen = screen,
        target = target,
        intendedAction = intendedAction,
        pointerPosition = pointerPosition,
        lastAction = lastAction,
        lastResult = lastResult,
        nextStep = nextStep,
        sequenceActive = sequenceActive,
        updatedAtMs = updatedAtMs,
    )

    /**
     * Plain-words rendering for speech or a "What LOOM sees" panel.
     * Never invents: anything unreported is said to be unreported.
     */
    fun describe(): String {
        val s = snapshot()
        val out = StringBuilder()
        val scr = s.screen
        if (scr == null) {
            out.append("I can't see the screen right now.")
        } else {
            out.append("I can see ${scr.totalElements} elements")
            out.append(" in ${scr.packageName}")
            if (scr.interactiveCount > 0) {
                out.append(", ${scr.interactiveCount} interactive")
                if (scr.labelsHidden) {
                    out.append(", but this app hides its labels")
                } else if (scr.interactiveLabels.isNotEmpty()) {
                    out.append(" including ${scr.interactiveLabels.joinToString(", ")}")
                }
            }
            out.append(".")
        }
        val t = s.target
        if (t != null) {
            out.append(" I'm pointing at ${t.label} (${t.role}).")
        } else if (s.sequenceActive) {
            out.append(" I haven't identified a target yet.")
        }
        s.intendedAction?.let { out.append(" Intended action: $it.") }
        s.pointerPosition?.let {
            out.append(" Pointer at (${it.x.toInt()}, ${it.y.toInt()}).")
        }
        if (s.lastAction != null) {
            out.append(" Last action: ${s.lastAction} — ${s.lastResult ?: "result not reported"}.")
        }
        s.nextStep?.let { out.append(" Next step: $it.") }
        if (!s.sequenceActive && s.lastAction == null && s.intendedAction == null) {
            out.append(" Nothing is running right now.")
        }
        return out.toString()
    }

    private fun touch() {
        updatedAtMs = System.currentTimeMillis()
    }
}
