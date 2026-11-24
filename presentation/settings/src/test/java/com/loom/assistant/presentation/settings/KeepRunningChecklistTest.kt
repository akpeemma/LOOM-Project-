package com.loom.assistant.presentation.settings

/**
 * Binding survival (Nov 6) — JVM checks for the Keep LOOM running
 * checklist ([KeepRunningChecklist]). Plain-main() pattern, same
 * toolchain as run-home-tests.sh. Run: run-home-tests.sh keep-running
 *
 * What is tested here is the decision the Settings rows show: which
 * rows carry a verified live state and which carry steps only, which
 * tap opens which real system screen, and that no row claims a state
 * the app cannot verify. The Android half (PowerManager read,
 * Settings.Secure read, system intents) lives in SettingsScreenView
 * and is verified on device.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) passed++
    else {
        failed++
        println("FAIL: $name $detail")
    }
}

fun main() {
    // Battery: the one live battery state, both ways.
    val batteryOk = KeepRunningChecklist.batteryItem(ignoringOptimizations = true)
    check("battery unrestricted is DONE", batteryOk.state == KeepRunningChecklist.ItemState.DONE)
    check("battery unrestricted says No restrictions", batteryOk.valueLabel == "No restrictions")
    check("battery unrestricted opens nothing", batteryOk.action == KeepRunningChecklist.ChecklistAction.NONE)
    val batteryBad = KeepRunningChecklist.batteryItem(ignoringOptimizations = false)
    check("battery restricted is NEEDS_ACTION", batteryBad.state == KeepRunningChecklist.ItemState.NEEDS_ACTION)
    check("battery restricted says Tap to fix", batteryBad.valueLabel == "Tap to fix")
    check(
        "battery restricted opens battery settings",
        batteryBad.action == KeepRunningChecklist.ChecklistAction.OPEN_BATTERY_SETTINGS,
    )
    check("battery restricted names No restrictions", batteryBad.detail.contains("No restrictions"))

    // Helper: all three live states.
    val helperOff = KeepRunningChecklist.helperItem(flagOn = false, hostLive = false)
    check("helper off is NEEDS_ACTION", helperOff.state == KeepRunningChecklist.ItemState.NEEDS_ACTION)
    check("helper off says Off", helperOff.valueLabel.contains("Off"))
    check(
        "helper off opens accessibility settings",
        helperOff.action == KeepRunningChecklist.ChecklistAction.OPEN_ACCESSIBILITY_SETTINGS,
    )
    val helperStale = KeepRunningChecklist.helperItem(flagOn = true, hostLive = false)
    check("helper on-but-not-live is NEEDS_ACTION", helperStale.state == KeepRunningChecklist.ItemState.NEEDS_ACTION)
    check("helper on-but-not-live says so", helperStale.valueLabel == "On but not running")
    check(
        "helper on-but-not-live tells the user to toggle off and on",
        helperStale.detail.contains("turn LOOM off and on again"),
    )
    check(
        "helper on-but-not-live opens accessibility settings",
        helperStale.action == KeepRunningChecklist.ChecklistAction.OPEN_ACCESSIBILITY_SETTINGS,
    )
    val helperLive = KeepRunningChecklist.helperItem(flagOn = true, hostLive = true)
    check("helper live is DONE", helperLive.state == KeepRunningChecklist.ItemState.DONE)
    check("helper live says On and running", helperLive.valueLabel == "On and running")
    check("helper live opens nothing", helperLive.action == KeepRunningChecklist.ChecklistAction.NONE)

    // No-API rows: steps only, never a verified check mark.
    val autostart = KeepRunningChecklist.autostartItem()
    check("autostart is STEPS_ONLY", autostart.state == KeepRunningChecklist.ItemState.STEPS_ONLY)
    check("autostart says Check this", autostart.valueLabel == "Check this")
    check("autostart names the Autostart setting", autostart.detail.contains("tap Autostart"))
    check(
        "autostart opens app info",
        autostart.action == KeepRunningChecklist.ChecklistAction.OPEN_APP_INFO,
    )
    check("autostart admits LOOM cannot check it", autostart.detail.contains("LOOM cannot check this itself"))
    val recents = KeepRunningChecklist.recentsItem()
    check("recents lock is STEPS_ONLY", recents.state == KeepRunningChecklist.ItemState.STEPS_ONLY)
    check("recents lock says lock it", recents.detail.contains("lock it"))
    check("recents lock opens nothing (no intent exists)", recents.action == KeepRunningChecklist.ChecklistAction.NONE)
    val cleaner = KeepRunningChecklist.cleanerItem()
    check("cleaner is STEPS_ONLY", cleaner.state == KeepRunningChecklist.ItemState.STEPS_ONLY)
    check("cleaner warns against force-stop", cleaner.detail.contains("force-stop LOOM"))
    check("cleaner opens nothing (no intent exists)", cleaner.action == KeepRunningChecklist.ChecklistAction.NONE)

    // The full list: helper first, five rows, in work-through order.
    val items = KeepRunningChecklist.items(
        ignoringOptimizations = false, flagOn = true, hostLive = false,
    )
    check("checklist has five rows", items.size == 5)
    check("helper row is first", items[0].title == "Helper")
    check("battery row is second", items[1].title == "Battery")
    check("autostart row is third", items[2].title == "Autostart")
    check("recents row is fourth", items[3].title == "Locked in recent apps")
    check("cleaner row is fifth", items[4].title == "Cleaner and Boost apps")

    // Subtitle: the live battery state, in one line.
    check(
        "subtitle unrestricted",
        KeepRunningChecklist.subtitle(ignoringOptimizations = true) == "Battery: No restrictions",
    )
    check(
        "subtitle restricted",
        KeepRunningChecklist.subtitle(ignoringOptimizations = false) == "Battery: Restricted — tap to fix",
    )

    // Plain words: no internal state words anywhere user-facing.
    val banned = listOf("attached", "detached", "anchored", "overlay", "guidance window")
    val allText = items.map { it.title + " " + it.detail + " " + it.valueLabel } +
        KeepRunningChecklist.INTRO_TEXT
    for (text in allText) {
        for (word in banned) {
            check("no jargon '$word' in checklist text", !text.lowercase().contains(word), "text=[$text]")
        }
    }
    check("intro explains the overnight symptom", KeepRunningChecklist.INTRO_TEXT.contains("switch itself off overnight"))

    println("")
    if (failed == 0) println("ALL $passed TESTS PASSED")
    else println("$passed passed, $failed failed")
}
