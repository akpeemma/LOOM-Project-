package com.loom.assistant.core.common

/**
 * First-start attach policy — the single pure decision unit for
 * floating-surface binding (device defect refined 2026-11-05).
 *
 * Background: the first-start character-tap failure was first read as
 * "the tap binding is missing on the initial attach path". Code
 * inspection disproved that reading: LoomOverlayService has exactly ONE
 * character-creation site (addCharacter), and the initial attach
 * (onStartCommand else-branch) and the switch attach (busListener
 * ModeChanged, showFloatingViews) all funnel through the same bindMode
 * → addCharacter, which assigns onTap / onLongPress / onDrag BEFORE
 * wm.addView. There is no second, handler-less initial path to fix.
 *
 * What WAS structurally loose, and what this policy closes:
 * 1. bindMode's skip check was kind-blind: it skipped whenever the
 *    requested mode equalled activeMode AND *either* floating view was
 *    non-null. A stale/wrong-kind attached view under the right mode
 *    name would have been kept and reported as bound, while the
 *    requested surface was never (re)bound. The skip is now kind-exact:
 *    skip ONLY when the requested kind is active AND that same kind is
 *    the attached kind.
 * 2. Nothing ever verified that an attached view actually carries its
 *    gestures. bindMode now post-attach verifies, through
 *    [bindingComplete], that the attached surface carries every gesture
 *    its kind requires, and re-applies the surface's single binder when
 *    one is missing; a view that still fails verification is NOT
 *    reported as attached (activeMode stays null, so ModeController
 *    verification honestly fails instead of claiming a handler-less
 *    view). Initial attach and switch attach therefore cannot diverge:
 *    both run add → verify → rebind-or-fail through this one policy.
 *
 * Pure Kotlin, no Android imports — runs on the plain JVM harness
 * alongside FloatingTapRouter. The service maps AssistantMode to
 * [FloatingAttachKind] (CHARACTER / ORB / INVISIBLE → EDGE_LIGHT);
 * core:common cannot import presentation:modes (module deps stay
 * acyclic), so the mapping lives in the service, not here.
 */
enum class FloatingAttachKind { CHARACTER, ORB, EDGE_LIGHT }

enum class FloatingGesture { TAP, LONG_PRESS, DRAG }

object FloatingAttachPolicy {

    /**
     * Whether bindMode must (re)bind [requested].
     *
     * Fresh service state is (activeKind = null, attachedKind = null) —
     * the very first attach — and always binds: there is no prior bind
     * to skip. A skip happens only for the exact already-bound state:
     * requested kind == active kind == attached kind.
     */
    fun shouldBind(
        requested: FloatingAttachKind,
        activeKind: FloatingAttachKind?,
        attachedKind: FloatingAttachKind?,
    ): Boolean {
        if (requested == activeKind && attachedKind == requested) return false
        return true
    }

    /**
     * Gestures a correctly bound surface of [kind] must carry.
     * Mirrors the service contract: character tap = voice, character
     * long-press = panel, both surfaces drag; the edge light is
     * NOT_TOUCHABLE and carries no gestures.
     */
    fun requiredGestures(kind: FloatingAttachKind): Set<FloatingGesture> =
        when (kind) {
            FloatingAttachKind.CHARACTER ->
                setOf(FloatingGesture.TAP, FloatingGesture.LONG_PRESS, FloatingGesture.DRAG)
            FloatingAttachKind.ORB ->
                setOf(FloatingGesture.TAP, FloatingGesture.DRAG)
            FloatingAttachKind.EDGE_LIGHT -> emptySet()
        }

    /**
     * Post-attach verification: true only when every required gesture
     * for [kind] is present in [present]. A missing gesture means the
     * attach is incomplete and the service must re-apply the binder
     * (or fail the bind honestly) — never report it as attached.
     */
    fun bindingComplete(
        kind: FloatingAttachKind,
        present: Set<FloatingGesture>,
    ): Boolean = requiredGestures(kind).all { it in present }
}
