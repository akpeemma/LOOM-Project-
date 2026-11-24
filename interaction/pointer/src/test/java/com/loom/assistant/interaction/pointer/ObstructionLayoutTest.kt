package com.loom.assistant.interaction.pointer

/**
 * Stage 2C T11 — ObstructionLayout tests (criterion A3).
 *
 * Covers: tooltip flips above/below the target; the full choreography
 * (beam endpoint + ring + tooltip) is clamped inside the safe window
 * bounds; the beam's target-end dot offsets to the target corner so it
 * never parks over the target's own label.
 *
 * Run: kotlinc ObstructionLayout.kt ObstructionLayoutTest.kt -d out &&
 *   java -cp out com.loom.assistant.interaction.pointer.ObstructionLayoutTestKt
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

private val window = GeomRect(0f, 0f, 1080f, 2400f)
private const val density = 1f

private fun layout(
    target: GeomRect,
    anchor: GeomPoint,
    labelW: Float = 100f,
    instrW: Float = 200f,
): ObstructionLayout.Placement =
    ObstructionLayout.layout(target, anchor, window, density, labelW, instrW)

fun main() {
    val midTarget = GeomRect(440f, 1100f, 640f, 1200f) // center (540, 1150)

    // 1. Tooltip sits above the target when there is room.
    run {
        val p = layout(midTarget, GeomPoint(540f, 300f))
        check("tooltip above when room", p.tooltipAbove)
        check(
            "tooltip bottom is above the target top",
            p.tooltip.bottom <= midTarget.top - 8f,
        )
    }

    // 2. Tooltip flips below when there is no room above.
    run {
        val topTarget = GeomRect(440f, 10f, 640f, 110f)
        val p = layout(topTarget, GeomPoint(540f, 300f))
        check("tooltip below when no room above", !p.tooltipAbove)
        check(
            "tooltip top is below the target bottom",
            p.tooltip.top >= topTarget.bottom + 8f,
        )
    }

    // 3. Tooltip is clamped horizontally inside the window.
    run {
        val edgeTarget = GeomRect(980f, 1100f, 1070f, 1200f)
        val p = layout(edgeTarget, GeomPoint(540f, 300f), labelW = 100f, instrW = 400f)
        check("tooltip left inside window", p.tooltip.left >= 12f)
        check("tooltip right inside window", p.tooltip.right <= 1080f - 12f)
    }

    // 4. Beam endpoint and anchor are clamped to the safe window.
    run {
        val p = layout(midTarget, GeomPoint(-500f, -500f))
        check("beamFrom.x clamped", p.beamFrom.x >= 4f)
        check("beamFrom.y clamped", p.beamFrom.y >= 4f)
        check("beamTo inside window x", p.beamTo.x in 4f..1076f)
        check("beamTo inside window y", p.beamTo.y in 4f..2396f)
    }

    // 5. The target-end dot never parks over the target's label: for
    // anchors in all four directions it lands outside the target interior.
    run {
        val anchors = listOf(
            GeomPoint(0f, 1150f), // left
            GeomPoint(1080f, 1150f), // right
            GeomPoint(540f, 0f), // top
            GeomPoint(540f, 2400f), // bottom
        )
        var allOutside = true
        for (a in anchors) {
            val p = layout(midTarget, a)
            if (midTarget.containsInterior(p.beamTo)) {
                allOutside = false
                println("  dot inside target for anchor $a -> ${p.beamTo}")
            }
        }
        check("beam dot outside target interior (all directions)", allOutside)
    }

    // 6. The dot offsets toward the NEAREST corner to the anchor.
    run {
        val p = layout(midTarget, GeomPoint(0f, 1150f)) // anchor to the left
        check(
            "dot near the left side for a left anchor",
            p.beamTo.x < midTarget.centerX,
        )
        val q = layout(midTarget, GeomPoint(1080f, 1150f)) // anchor to the right
        check(
            "dot near the right side for a right anchor",
            q.beamTo.x > midTarget.centerX,
        )
    }

    // 7. Ring pads the target and stays inside the safe window.
    run {
        val p = layout(midTarget, GeomPoint(540f, 300f))
        check("ring pads left", p.ring.left <= midTarget.left - 4f)
        check("ring pads top", p.ring.top <= midTarget.top - 4f)
        check("ring pads right", p.ring.right >= midTarget.right + 4f)
        check("ring pads bottom", p.ring.bottom >= midTarget.bottom + 4f)
        check("ring inside window", p.ring.left >= 4f && p.ring.top >= 4f &&
            p.ring.right <= 1076f && p.ring.bottom <= 2396f)
    }

    // 8. Tiny window: nothing escapes the window bounds even when the
    // tooltip cannot fit above or below.
    run {
        val small = GeomRect(0f, 0f, 300f, 120f)
        val target = GeomRect(100f, 40f, 200f, 80f)
        val p = ObstructionLayout.layout(
            target, GeomPoint(150f, 10f), small, density, 100f, 500f,
        )
        check("tooltip inside tiny window", p.tooltip.left >= 0f && p.tooltip.right <= 300f &&
            p.tooltip.top >= 0f && p.tooltip.bottom <= 120f)
        check("ring inside tiny window", p.ring.left >= 0f && p.ring.right <= 300f)
    }

    // 9. Density scales the padding (dp -> px).
    run {
        val p1 = ObstructionLayout.layout(midTarget, GeomPoint(540f, 300f), window, 1f, 100f, 200f)
        val p3 = ObstructionLayout.layout(midTarget, GeomPoint(540f, 300f), window, 3f, 100f, 200f)
        check(
            "higher density widens the tooltip box",
            p3.tooltip.width > p1.tooltip.width,
        )
        check(
            "higher density pads the ring more",
            (midTarget.left - p3.ring.left) > (midTarget.left - p1.ring.left),
        )
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
