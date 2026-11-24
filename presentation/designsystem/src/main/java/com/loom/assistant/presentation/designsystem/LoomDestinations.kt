package com.loom.assistant.presentation.designsystem

/**
 * LOOM navigation graph — the 17 locked destinations from Design Lockdown v1.1.
 * Names/routes only. Screen CONTENT and functionality land in Phases 3–5;
 * nothing here is a completed feature.
 */
sealed class LoomDestination(
    val route: String,
    val label: String,
    /** Which implementation phase delivers this screen's content. */
    val phase: Int,
) {
    // Phase 3 — core interface and conversation
    data object Home : LoomDestination("home", "Home", 3)
    data object HomeQuickVoice : LoomDestination("home/quick-voice", "Home — Quick Voice", 3)
    data object HomeListening : LoomDestination("home/listening", "Home — Listening", 3)
    data object ChatText : LoomDestination("chat", "Chat — Text", 3)
    data object ChatVoice : LoomDestination("chat/voice", "Chat — Voice", 3)
    data object ChatSwitching : LoomDestination("chat/switching", "Chat — Switching", 3)
    data object Conversations : LoomDestination("conversations", "Conversations", 3)

    // Phase 5 — intelligence and features
    data object Tasks : LoomDestination("tasks", "Tasks", 5)
    data object Skills : LoomDestination("skills", "Skills", 5)
    data object Memory : LoomDestination("memory", "Memory", 5)
    data object More : LoomDestination("more", "More", 5)
    data object Projects : LoomDestination("projects", "Projects", 5)
    data object Intelligence : LoomDestination("intelligence", "LOOM Intelligence", 5)

    // Phase 5 sub-screens — reached from More/Settings. They are panels of
    // the locked destinations above, not new top-level screens.
    data object Settings : LoomDestination("settings", "Settings", 5)
    data object DeviceControl : LoomDestination("device-control", "Device Control", 5)
    data object Notifications : LoomDestination("notifications", "Notifications", 5)
    /** Master §3 — "What LOOM Sees": read-only render of the redacted screen model. */
    data object WhatLoomSees : LoomDestination("what-loom-sees", "What LOOM Sees", 5)
    /**
     * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT) — the
     * in-app grant screen. Reached from the plain-words "Pseudo
     * control" entry on Home (never buried in Settings): the
     * approved app list, a deliberate grant control, one-tap revoke.
     */
    data object PseudoControl : LoomDestination("pseudo-control", "Pseudo control", 5)

    // Phase 4 — interaction modes (system-wide overlays)
    data object ModeCharacter : LoomDestination("mode/character", "Character Mode", 4)
    data object ModeOrb : LoomDestination("mode/orb", "Orb Mode", 4)
    data object ModeInvisible : LoomDestination("mode/invisible", "Invisible Mode", 4)
    data object PointerGuidance : LoomDestination("pointer-guidance", "Pointer Guidance", 4)

    companion object {
        val all: List<LoomDestination> = listOf(
            Home, HomeQuickVoice, HomeListening,
            ChatText, ChatVoice, ChatSwitching, Conversations,
            Tasks, Skills, Memory, More, Projects, Intelligence,
            Settings, DeviceControl, Notifications, WhatLoomSees, PseudoControl,
            ModeCharacter, ModeOrb, ModeInvisible, PointerGuidance,
        )

        val start: LoomDestination = Home

        fun fromRoute(route: String): LoomDestination? = all.firstOrNull { it.route == route }

        /** The 5 bottom-nav tabs — the only bottom-nav destinations, per lockdown. */
        val bottomNav: List<LoomDestination> = listOf(Home, Tasks, Skills, Memory, More)
    }
}
