package com.loom.assistant.interaction.pointer

import android.graphics.Rect
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement

/**
 * Master implementation §7 — AssistantPlacement tests.
 *
 * Covers: interactive-role classification; check-before-move overlap
 * (strict edge-touching does not count); findFreeSpot (free desired,
 * nearest ring neighbour, corners/edges fallback, null when fully
 * covered); largestFreeRect (empty tree centres, buttons-on-one-side
 * picks the free side, fully covered returns null); clampRect.
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 \
 *     interaction/pointer/src/test/java/android/graphics/Rect.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/AssistantPlacement.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/AssistantPlacementTest.kt
 *   java -cp out com.loom.assistant.interaction.pointer.AssistantPlacementTestKt
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun el(
    id: String,
    role: ElementRole,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): ScreenElement = ScreenElement(
    id = id,
    text = id,
    contentDescription = null,
    className = "android.widget.Button",
    bounds = Rect(left, top, right, bottom),
    role = role,
    enabled = true,
    isPassword = false,
)

private val SAFE = GeomRect(0f, 0f, 1080f, 2400f)
private val ASST = GeomRect(100f, 100f, 268f, 292f) // 168 x 192

fun main() {
    // 1. Interactive roles: controls block; passive content + containers don't.
    run {
        for (role in listOf(
            ElementRole.BUTTON, ElementRole.TOGGLE, ElementRole.INPUT,
            ElementRole.MENU_ITEM, ElementRole.ICON, ElementRole.SEARCH_FIELD,
        )) {
            check("interactive: $role", AssistantPlacement.isInteractive(role))
        }
        for (role in listOf(
            ElementRole.TEXT, ElementRole.IMAGE, ElementRole.OTHER,
            ElementRole.DIALOG, ElementRole.NAVIGATION, ElementRole.LIST,
        )) {
            check("not interactive: $role", !AssistantPlacement.isInteractive(role))
        }
    }

    // 2. coversInteractive: overlap blocks; edge-touching does not.
    run {
        val button = el("b1", ElementRole.BUTTON, 200, 200, 400, 300)
        check(
            "overlap covers",
            AssistantPlacement.coversInteractive(ASST, listOf(button)) == button,
        )
        check(
            "edge-touch does not cover",
            AssistantPlacement.coversInteractive(
                GeomRect(400f, 100f, 568f, 292f), listOf(button),
            ) == null,
        )
        check(
            "text under the rect does not block",
            AssistantPlacement.coversInteractive(
                ASST, listOf(el("t1", ElementRole.TEXT, 100, 100, 268, 292)),
            ) == null,
        )
        check(
            "empty bounds never block",
            AssistantPlacement.coversInteractive(
                ASST, listOf(el("b0", ElementRole.BUTTON, 0, 0, 0, 0)),
            ) == null,
        )
    }

    // 3. findFreeSpot: free desired spot is returned unchanged.
    run {
        val far = el("b1", ElementRole.BUTTON, 800, 2000, 1000, 2200)
        check(
            "free desired spot returned as-is",
            AssistantPlacement.findFreeSpot(ASST, SAFE, listOf(far), 144f) == ASST,
        )
    }

    // 4. findFreeSpot: covered desired spot moves to the nearest free ring spot.
    run {
        val button = el("b1", ElementRole.BUTTON, 100, 100, 268, 292)
        val spot = AssistantPlacement.findFreeSpot(ASST, SAFE, listOf(button), 144f)
        check("covered spot finds a free spot", spot != null)
        check(
            "free spot covers nothing interactive",
            spot != null && AssistantPlacement.coversInteractive(spot, listOf(button)) == null,
        )
        // Nearest ring neighbour: one 144px step right (100 -> 244) clears
        // the button (right edge 268 -> 244+168=412 still overlaps? no:
        // candidate left 244 < 268 and right 412 > 100, top overlaps ->
        // still covers). The first free cardinal is LEFT? left goes to
        // -44, clamped to 0 -> same rect, skipped as tried. DOWN one step:
        // top 244 < 292 and bottom 436 > 100 -> still covers. RIGHT two
        // rings (288px): left 388 > 268 -> free. Assert the spot is a
        // ring step away, not a corner.
        check(
            "nearest free spot is a ring step, not a corner",
            spot != null && spot.left in listOf(388f) && spot.top == 100f,
        )
    }

    // 5. findFreeSpot: corners/edges fallback when rings are covered.
    run {
        // Buttons cover the desired spot and every ring neighbour around it.
        val els = mutableListOf<ScreenElement>()
        els += el("c", ElementRole.BUTTON, 100, 100, 268, 292)
        els += el("r1", ElementRole.BUTTON, 0, 0, 1080, 600) // top band
        els += el("r2", ElementRole.BUTTON, 0, 500, 1080, 2400) // rest, but
        // leave the bottom strip free: r2 ends at y=2000.
        els[2] = el("r2", ElementRole.BUTTON, 0, 500, 1080, 2000)
        val spot = AssistantPlacement.findFreeSpot(ASST, SAFE, els, 144f)
        check("fallback finds a free corner", spot != null)
        check(
            // Corners are tried TL, TR, BL, BR: the top band covers TL/TR,
            // BL (0, 2208) is the first free corner.
            "fallback spot is the bottom-left corner",
            spot != null && spot.left == 0f && spot.top == 2400f - 192f,
        )
    }

    // 6. findFreeSpot: null when the whole safe area is covered.
    run {
        val wall = el("wall", ElementRole.BUTTON, 0, 0, 1080, 2400)
        check(
            "fully covered screen returns null",
            AssistantPlacement.findFreeSpot(ASST, SAFE, listOf(wall), 144f) == null,
        )
    }

    // 7. largestFreeRect: empty tree centres the assistant.
    run {
        val spot = AssistantPlacement.largestFreeRect(SAFE, 168f, 192f, emptyList(), 72f)
        check("empty tree centres", spot != null)
        check(
            "centred spot",
            spot != null && spot.left == 540f - 84f && spot.top == 1200f - 96f,
        )
    }

    // 8. largestFreeRect: buttons on the left two-thirds -> free right side.
    run {
        val els = listOf(
            el("b1", ElementRole.BUTTON, 0, 0, 700, 1200),
            el("b2", ElementRole.BUTTON, 0, 1200, 700, 2400),
        )
        val spot = AssistantPlacement.largestFreeRect(SAFE, 168f, 192f, els, 72f)
        check("free side found", spot != null)
        check(
            "spot covers nothing interactive",
            spot != null && AssistantPlacement.coversInteractive(spot, els) == null,
        )
        check(
            "spot is on the free right side",
            spot != null && spot.left >= 700f,
        )
        // Max clearance in the free strip (700..1080): the buttons span
        // the full height, so clearance is horizontal only and the
        // farthest grid column wins (x=864: 72px grid, 864+168=1032;
        // the next column, 936, would overflow the safe area).
        check(
            "spot maximises clearance (farthest free column)",
            spot != null && spot.left == 864f && spot.top == 0f,
        )
    }

    // 9. largestFreeRect: fully covered -> null.
    run {
        val wall = el("wall", ElementRole.BUTTON, 0, 0, 1080, 2400)
        check(
            "fully covered returns null",
            AssistantPlacement.largestFreeRect(SAFE, 168f, 192f, listOf(wall), 72f) == null,
        )
    }

    // 10. clampRect: keeps size, clamps inside the safe area.
    run {
        val over = GeomRect(-50f, 2300f, 118f, 2492f)
        val c = AssistantPlacement.clampRect(over, SAFE)
        check("clamped left", c.left == 0f)
        check("clamped top", c.top == 2400f - 192f)
        check("size preserved", c.width == 168f && c.height == 192f)
        val fits = GeomRect(100f, 100f, 268f, 292f)
        check("fitting rect unchanged", AssistantPlacement.clampRect(fits, SAFE) == fits)
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
