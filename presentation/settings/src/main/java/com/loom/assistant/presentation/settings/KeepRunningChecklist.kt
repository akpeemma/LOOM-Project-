package com.loom.assistant.presentation.settings

/**
 * Keep LOOM running — battery / autostart survival checklist
 * (2026-11-06, device evidence: the helper was Bound Nov 5 22:05 and
 * Not bound Nov 6 08:01 with the Settings switch still Enabled — the
 * phone put LOOM to sleep overnight and the binding did not survive).
 *
 * Pure Kotlin, no Android imports, so the harness can test it on the
 * JVM. The Android half (reading the real battery state, opening the
 * system screens) lives in [SettingsScreenView]; this object owns only
 * the decision: given the live states, what does each row say, and
 * which system screen (if any) does tapping it open.
 *
 * Honesty rules, enforced here:
 * - Only the battery row and the helper row have a LIVE state (the
 *   system reports them). Autostart, Recents-lock and Cleaner have NO
 *   API on this phone — their rows say "Check this" and give plain
 *   steps, never a check mark the app cannot verify.
 * - No row claims LOOM "will stay on" — the list is what keeps it
 *   alive, not a guarantee.
 * - Plain words only: no attached / detached / anchored / overlay /
 *   guidance window in any user-facing string.
 */
object KeepRunningChecklist {

    /** What the row's value label means — DONE is a verified state, never a guess. */
    enum class ItemState {
        /** Verified live right now (battery / helper only). */
        DONE,

        /** Verified live state is wrong — tapping opens the fix. */
        NEEDS_ACTION,

        /** No live check exists on this phone — plain steps, user verifies. */
        STEPS_ONLY,
    }

    /** Which system screen a tap opens, if any. */
    enum class ChecklistAction {
        NONE,
        OPEN_BATTERY_SETTINGS,
        OPEN_APP_INFO,
        OPEN_ACCESSIBILITY_SETTINGS,
    }

    data class ChecklistItem(
        val title: String,
        val detail: String,
        val valueLabel: String,
        val state: ItemState,
        val action: ChecklistAction,
    )

    /** Intro shown at the top of the checklist. */
    const val INTRO_TEXT =
        "If your phone puts LOOM to sleep to save battery, the helper can " +
            "switch itself off overnight — and the pointer, typing and " +
            "scrolling stop working until you turn it back on. Work " +
            "through this list once; it is what keeps LOOM alive."

    fun batteryItem(ignoringOptimizations: Boolean): ChecklistItem =
        if (ignoringOptimizations) {
            ChecklistItem(
                title = "Battery",
                detail = "Your phone is letting LOOM keep running in the background. " +
                    "Leave it like this.",
                valueLabel = "No restrictions",
                state = ItemState.DONE,
                action = ChecklistAction.NONE,
            )
        } else {
            ChecklistItem(
                title = "Battery",
                detail = "Your phone may switch LOOM off overnight to save battery. " +
                    "Tap here to open the battery settings list, find LOOM, " +
                    "and choose No restrictions.",
                valueLabel = "Tap to fix",
                state = ItemState.NEEDS_ACTION,
                action = ChecklistAction.OPEN_BATTERY_SETTINGS,
            )
        }

    fun autostartItem(): ChecklistItem = ChecklistItem(
        title = "Autostart",
        detail = "On this phone: open LOOM's app info, tap Autostart, and turn " +
            "it on. Without this, LOOM may not start again after the phone " +
            "restarts. LOOM cannot check this itself — look at the setting " +
            "and confirm it is on.",
        valueLabel = "Check this",
        state = ItemState.STEPS_ONLY,
        action = ChecklistAction.OPEN_APP_INFO,
    )

    fun recentsItem(): ChecklistItem = ChecklistItem(
        title = "Locked in recent apps",
        detail = "Open the recent apps screen, find LOOM, and lock it. A " +
            "locked app is not closed when you swipe the others away.",
        valueLabel = "Check this",
        state = ItemState.STEPS_ONLY,
        action = ChecklistAction.NONE,
    )

    fun cleanerItem(): ChecklistItem = ChecklistItem(
        title = "Cleaner and Boost apps",
        detail = "If your phone has a Cleaner or Boost app, do not let it " +
            "force-stop LOOM. A force-stop switches the helper off until " +
            "you turn it back on.",
        valueLabel = "Check this",
        state = ItemState.STEPS_ONLY,
        action = ChecklistAction.NONE,
    )

    /**
     * The helper row — the live state, in the same words Home uses.
     * [flagOn] is the Settings switch; [hostLive] is the live binding
     * (LoomAccessibilityService.isLive()). Flag on but not live is the
     * Nov 6 overnight symptom, and its fix is the toggle, not this list.
     */
    fun helperItem(flagOn: Boolean, hostLive: Boolean): ChecklistItem = when {
        !flagOn -> ChecklistItem(
            title = "Helper",
            detail = "LOOM's helper is switched off. Tap here to open " +
                "Accessibility settings and turn LOOM on.",
            valueLabel = "Off — tap to fix",
            state = ItemState.NEEDS_ACTION,
            action = ChecklistAction.OPEN_ACCESSIBILITY_SETTINGS,
        )
        !hostLive -> ChecklistItem(
            title = "Helper",
            detail = "LOOM is switched on in Settings, but the helper itself " +
                "isn't running right now. Tap here, turn LOOM off and on " +
                "again, then come back.",
            valueLabel = "On but not running",
            state = ItemState.NEEDS_ACTION,
            action = ChecklistAction.OPEN_ACCESSIBILITY_SETTINGS,
        )
        else -> ChecklistItem(
            title = "Helper",
            detail = "LOOM's helper is on and running.",
            valueLabel = "On and running",
            state = ItemState.DONE,
            action = ChecklistAction.NONE,
        )
    }

    /** The full list, in the order David should work through it. */
    fun items(
        ignoringOptimizations: Boolean,
        flagOn: Boolean,
        hostLive: Boolean,
    ): List<ChecklistItem> = listOf(
        helperItem(flagOn, hostLive),
        batteryItem(ignoringOptimizations),
        autostartItem(),
        recentsItem(),
        cleanerItem(),
    )

    /** Category-list subtitle — the one live state worth surfacing. */
    fun subtitle(ignoringOptimizations: Boolean): String =
        if (ignoringOptimizations) "Battery: No restrictions"
        else "Battery: Restricted — tap to fix"
}
