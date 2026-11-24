package com.loom.assistant.core.common

/**
 * Character-tap fix — pure-JVM checks for FloatingTapRouter.
 * Run: kotlinc FloatingTapRouter.kt FloatingTapRouterTest.kt -d out
 *   && java -cp out com.loom.assistant.core.common.FloatingTapRouterTestKt
 * (No android.* imports, so no fakes are needed.)
 *
 * Proves the device defect is fixed at the routing seam the service
 * actually consults: a character tap routes exactly like an orb tap
 * (toggle floating voice), while the mode panel survives on a
 * character long-press and the orb gains no panel.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

fun main() {
    check(
        "tapAction(CHARACTER) == TOGGLE_FLOATING_VOICE",
        FloatingTapRouter.tapAction(FloatingSurface.CHARACTER),
        TapAction.TOGGLE_FLOATING_VOICE,
    )
    check(
        "tapAction(ORB) == TOGGLE_FLOATING_VOICE",
        FloatingTapRouter.tapAction(FloatingSurface.ORB),
        TapAction.TOGGLE_FLOATING_VOICE,
    )
    check(
        "tapAction(CHARACTER) == tapAction(ORB)",
        FloatingTapRouter.tapAction(FloatingSurface.CHARACTER),
        FloatingTapRouter.tapAction(FloatingSurface.ORB),
    )
    check(
        "longPressAction(CHARACTER) == OPEN_PANEL",
        FloatingTapRouter.longPressAction(FloatingSurface.CHARACTER),
        TapAction.OPEN_PANEL,
    )
    check(
        "longPressAction(ORB) == NONE",
        FloatingTapRouter.longPressAction(FloatingSurface.ORB),
        TapAction.NONE,
    )
    // Tap and long-press must never collapse onto each other per surface:
    // a tap must not open the panel, a character long-press must not
    // toggle voice (the view itself also suppresses onTap after a
    // long-press, so the two paths stay disjoint end to end).
    check(
        "tapAction(CHARACTER) != longPressAction(CHARACTER)",
        FloatingTapRouter.tapAction(FloatingSurface.CHARACTER) !=
            FloatingTapRouter.longPressAction(FloatingSurface.CHARACTER),
        true,
    )
    println("FloatingTapRouterTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
