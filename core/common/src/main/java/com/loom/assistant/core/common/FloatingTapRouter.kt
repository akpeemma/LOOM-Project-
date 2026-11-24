package com.loom.assistant.core.common

/**
 * Character-tap fix (proven on device 2026-11-05): in Character mode,
 * tapping the floating character did NOT start a floating voice/chat
 * session — only the orb tap did, because the character tap opened the
 * small mode panel instead.
 *
 * This router is the single tested seam for floating-surface gestures.
 * LoomOverlayService consults it for every tap / long-press dispatch —
 * the behavior is NOT hardcoded in the service while this file sits
 * decorative. Pure Kotlin, no Android imports, so it compiles in the
 * JVM harness standalone alongside the other core:common sources.
 *
 * Routing (deliberate, panel-preserving):
 * - tap on EITHER surface -> TOGGLE_FLOATING_VOICE (character == orb).
 * - long-press on CHARACTER -> OPEN_PANEL (the panel's Voice chat /
 *   Open LOOM / mode-switch rows stay reachable, just on long-press).
 * - long-press on ORB -> NONE (the orb never had a panel; nothing added).
 */
enum class FloatingSurface { ORB, CHARACTER }

enum class TapAction { TOGGLE_FLOATING_VOICE, OPEN_PANEL, NONE }

object FloatingTapRouter {
    fun tapAction(surface: FloatingSurface): TapAction =
        when (surface) {
            FloatingSurface.ORB -> TapAction.TOGGLE_FLOATING_VOICE
            FloatingSurface.CHARACTER -> TapAction.TOGGLE_FLOATING_VOICE
        }

    fun longPressAction(surface: FloatingSurface): TapAction =
        when (surface) {
            FloatingSurface.CHARACTER -> TapAction.OPEN_PANEL
            FloatingSurface.ORB -> TapAction.NONE
        }
}
