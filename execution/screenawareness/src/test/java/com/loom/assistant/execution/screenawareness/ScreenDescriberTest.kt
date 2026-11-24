package com.loom.assistant.execution.screenawareness

import android.graphics.Rect

/**
 * Device-test failures (2026-11-11, D-04) — pure-JVM checks for the
 * screen description: which app, what is on it, where the named
 * things are. The description can only name what the tree actually
 * exposed, never an invented screen.
 *
 * Run: kotlinc <fakes>/Rect.kt ScreenModel.kt ScreenDataPolicy.kt
 *   ScreenDescriber.kt ScreenDescriberTest.kt -d out && java -cp out
 *   com.loom.assistant.execution.screenawareness.ScreenDescriberTestKt
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun el(id: String, text: String?, top: Int, role: ElementRole = ElementRole.TEXT) =
    ScreenElement(
        id = id, text = text, contentDescription = null,
        className = "android.widget.TextView",
        bounds = Rect(100, top, 500, top + 60),
        role = role, enabled = true, isPassword = false,
    )

fun main() {
    val youtube = ScreenModel(
        packageName = "com.google.android.youtube",
        elements = listOf(
            el("search", "Search YouTube", 100, ElementRole.SEARCH_FIELD),
            el("home", "Home", 2200),
            el("shorts", "Shorts", 2200),
        ),
        screenBounds = Rect(0, 0, 1080, 2400),
    )
    val description = ScreenDescriber.describe(ScreenRead.Available(youtube))
    check("description names the app", description.contains("YouTube"))
    check("description names the search field", description.contains("Search YouTube"))
    check("description names Home", description.contains("Home"))
    check("description gives a position", description.contains("top") || description.contains("bottom"))
    check("description counts the elements", description.contains("3 things"))

    // Zones come from the screen bounds in thirds.
    checkEq(
        "top left zone",
        ScreenDescriber.zoneOf(el("a", "A", 100), Rect(0, 0, 1080, 2400)),
        "top left",
    )
    checkEq(
        "bottom left zone",
        ScreenDescriber.zoneOf(
            ScreenElement(
                id = "b", text = "B", contentDescription = null,
                className = "android.widget.TextView",
                bounds = Rect(100, 2000, 300, 2100),
                role = ElementRole.TEXT, enabled = true, isPassword = false,
            ),
            Rect(0, 0, 1080, 2400),
        ),
        "bottom left",
    )

    // An unreadable screen says so with the exact reason — never a
    // guessed description.
    val insufficient = ScreenDescriber.describe(
        ScreenRead.Insufficient("the accessibility tree is unavailable."),
    )
    check("insufficient says it cannot see", insufficient.contains("can't see the screen"))
    check("insufficient carries the reason", insufficient.contains("accessibility tree is unavailable"))

    // An empty screen says it is empty — never an invented list.
    val empty = ScreenDescriber.describe(
        ScreenRead.Available(ScreenModel(packageName = "com.facebook.katana", elements = emptyList())),
    )
    check("empty screen names the app", empty.contains("Facebook"))
    check("empty screen says nothing readable", empty.contains("nothing readable"))

    // App labels for the apps David uses; unknown packages fall back
    // to the last segment, never blank.
    checkEq("facebook label", ScreenDescriber.appLabel("com.facebook.katana"), "Facebook")
    checkEq("loom label", ScreenDescriber.appLabel("com.loom.assistant"), "LOOM")
    checkEq(
        "unknown package falls back to its last segment",
        ScreenDescriber.appLabel("com.example.something"),
        "something",
    )

    // Screen fix (2026-11-11): the "135 things" defect. The head
    // count is the number of distinct LABELLED visible items — never
    // the raw tree size — and class-name-only nodes are unnamed and
    // uncounted (their label() falls back to "FrameLayout" etc.).
    val feed = ScreenModel(
        packageName = "com.facebook.katana",
        elements = listOf(
            el("post1", "Good morning everyone", 100),
            el("post2", "Look at this photo", 400),
            // No text / description / hint: class-name fallback only.
            ScreenElement(
                id = "frame1", text = null, contentDescription = null,
                className = "android.widget.FrameLayout",
                bounds = Rect(0, 700, 1080, 900),
                role = ElementRole.OTHER, enabled = true, isPassword = false,
            ),
            ScreenElement(
                id = "group1", text = null, contentDescription = null,
                className = "android.view.ViewGroup",
                bounds = Rect(0, 1000, 1080, 1200),
                role = ElementRole.OTHER, enabled = true, isPassword = false,
            ),
            // Disabled and zero-size nodes never count either.
            ScreenElement(
                id = "off", text = "Hidden button", contentDescription = null,
                className = "android.widget.Button",
                bounds = Rect(0, 1300, 200, 1400),
                role = ElementRole.BUTTON, enabled = false, isPassword = false,
            ),
        ),
        screenBounds = Rect(0, 0, 1080, 2400),
    )
    val feedDescription = ScreenDescriber.describe(ScreenRead.Available(feed))
    check("head count is the labelled count, never the raw size", feedDescription.contains("2 things"))
    check("head never claims the raw tree size", !feedDescription.contains("5 things"))
    check("class-name fallback never named", !feedDescription.contains("FrameLayout") && !feedDescription.contains("ViewGroup"))
    check("labelled items still named", feedDescription.contains("Good morning everyone"))

    // A screen with elements but no readable label says so — and its
    // count is 0, not the tree size.
    val noLabels = ScreenModel(
        packageName = "com.facebook.katana",
        elements = listOf(
            ScreenElement(
                id = "f", text = null, contentDescription = null,
                className = "android.widget.FrameLayout",
                bounds = Rect(0, 0, 1080, 2400),
                role = ElementRole.OTHER, enabled = true, isPassword = false,
            ),
        ),
        screenBounds = Rect(0, 0, 1080, 2400),
    )
    val noLabelsDescription = ScreenDescriber.describe(ScreenRead.Available(noLabels))
    check("no readable labels → count is 0", noLabelsDescription.contains("0 things"))
    check("no readable labels → said plainly", noLabelsDescription.contains("nothing with a readable label"))

    // The description can never run long: 30 long labels cap at 500 chars.
    val longLabels = (1..30).map { i ->
        el("item$i", "A very long post title number $i that keeps going on and on", 100 + i * 60)
    }
    val longDescription = ScreenDescriber.describe(
        ScreenRead.Available(
            ScreenModel(
                packageName = "com.facebook.katana",
                elements = longLabels,
                screenBounds = Rect(0, 0, 1080, 2400),
            ),
        ),
    )
    check("description length capped at 500 chars", longDescription.length <= 500)
    check("capped description still counts all labelled items", longDescription.contains("30 things"))

    // Elements outside the viewport are not visible and not counted.
    val belowFold = ScreenModel(
        packageName = "com.facebook.katana",
        elements = listOf(
            el("visible", "On screen now", 100),
            el("below", "Below the fold", 3000),
        ),
        screenBounds = Rect(0, 0, 1080, 2400),
    )
    val belowDescription = ScreenDescriber.describe(ScreenRead.Available(belowFold))
    check("below-fold element not counted", belowDescription.contains("1 thing"))
    check("below-fold element not named", !belowDescription.contains("Below the fold"))

    println("ScreenDescriberTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
