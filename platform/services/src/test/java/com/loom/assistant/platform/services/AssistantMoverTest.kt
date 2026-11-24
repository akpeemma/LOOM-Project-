package com.loom.assistant.platform.services

import android.graphics.Rect
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Master implementation §7 — AssistantMover tests.
 *
 * Covers: region moves (corners/edges) + nudges at the exact expected
 * coordinates; clamping to the safe area; check-before-move (a covering
 * spot is refused and the nearest free spot is picked; a fully covered
 * screen fails honestly with no apply); OUT_OF_WAY placement from a fake
 * tree; honest failure when there is no backend / no overlay / no tree /
 * the view detaches mid-move; persistence of the final position.
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 \
 *     interaction/pointer/src/test/java/android/graphics/Rect.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/AssistantPlacement.kt \
 *     platform/services/src/main/java/com/loom/assistant/platform/services/AssistantMover.kt \
 *     platform/services/src/test/java/com/loom/assistant/platform/services/AssistantMoverTest.kt
 *   java -cp out com.loom.assistant.platform.services.AssistantMoverTestKt
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

private class FakeBackend(
    var rect: AssistantMover.AssistantRect? =
        AssistantMover.AssistantRect(300, 400, 468, 592),
    var safe: AssistantMover.AssistantRect =
        AssistantMover.AssistantRect(0, 0, 1080, 2400),
    var density: Float = 3f,
    var model: ScreenModel? = ScreenModel("com.demo.app", emptyList()),
    var applyOk: Boolean = true,
) : AssistantMover.Backend {
    val applied = mutableListOf<Pair<Int, Int>>()
    val persisted = mutableListOf<Pair<Int, Int>>()

    override fun currentRect(): AssistantMover.AssistantRect? = rect
    override fun safeArea(): AssistantMover.AssistantRect = safe
    override fun density(): Float = density
    override fun screenModel(): ScreenModel? = model
    override fun applyMove(x: Int, y: Int): Boolean {
        if (!applyOk) return false
        applied += x to y
        rect = AssistantMover.AssistantRect(x, y, x + 168, y + 192)
        return true
    }

    override fun persistPosition(x: Int, y: Int) {
        persisted += x to y
    }
}

private fun withBackend(b: FakeBackend, block: () -> Unit) {
    AssistantMover.register(b)
    try {
        block()
    } finally {
        AssistantMover.register(null)
    }
}

fun main() {
    // 1. Corner moves land at the safe-area corners (8dp margin = 24px).
    run {
        val b = FakeBackend()
        withBackend(b) {
            check(
                "TOP_LEFT -> (24, 24)",
                AssistantMover.move(AssistantMover.AssistantRegion.TOP_LEFT)
                    is AssistantMover.MoveResult.Ok &&
                    b.applied.last() == (24 to 24),
            )
        }
    }
    run {
        val b = FakeBackend()
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.TOP_RIGHT)
            check("TOP_RIGHT -> (888, 24)", b.applied.last() == (888 to 24))
        }
    }
    run {
        val b = FakeBackend()
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.BOTTOM_LEFT)
            check("BOTTOM_LEFT -> (24, 2184)", b.applied.last() == (24 to 2184))
        }
    }
    run {
        val b = FakeBackend()
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.BOTTOM_RIGHT)
            check("BOTTOM_RIGHT -> (888, 2184)", b.applied.last() == (888 to 2184))
        }
    }

    // 2. Edge moves centre vertically on the edge.
    run {
        val b = FakeBackend()
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.LEFT_EDGE)
            check("LEFT_EDGE -> (24, 1104)", b.applied.last() == (24 to 1104))
            AssistantMover.move(AssistantMover.AssistantRegion.RIGHT_EDGE)
            check("RIGHT_EDGE -> (888, 1104)", b.applied.last() == (888 to 1104))
        }
    }

    // 3. Nudges move one 48dp step (144px at density 3).
    run {
        val b = FakeBackend()
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.RIGHT)
            check("nudge RIGHT +144px", b.applied.last() == (444 to 400))
            AssistantMover.move(AssistantMover.AssistantRegion.DOWN)
            check("nudge DOWN +144px", b.applied.last() == (444 to 544))
            AssistantMover.move(AssistantMover.AssistantRegion.LEFT)
            check("nudge LEFT -144px", b.applied.last() == (300 to 544))
            AssistantMover.move(AssistantMover.AssistantRegion.UP)
            check("nudge UP -144px", b.applied.last() == (300 to 400))
        }
    }

    // 4. Clamping: a nudge past the safe edge stops at the edge.
    run {
        val b = FakeBackend(rect = AssistantMover.AssistantRect(10, 10, 178, 202))
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.LEFT)
            check("nudge LEFT clamps to safe.left", b.applied.last() == (0 to 10))
            AssistantMover.move(AssistantMover.AssistantRegion.UP)
            check("nudge UP clamps to safe.top", b.applied.last() == (0 to 0))
        }
    }
    run {
        val b = FakeBackend(rect = AssistantMover.AssistantRect(300, 2200, 468, 2392))
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.DOWN)
            check(
                "nudge DOWN clamps to safe.bottom",
                b.applied.last() == (300 to 2208),
            )
        }
    }

    // 5. Check-before-move: a button on the TOP_RIGHT spot reroutes to the
    // nearest free spot (600, 24) instead of covering the button.
    run {
        val b = FakeBackend(
            model = ScreenModel(
                "com.demo.app",
                listOf(el("b1", ElementRole.BUTTON, 888, 24, 1056, 216)),
            ),
        )
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.TOP_RIGHT)
            check("covered corner still moves (Ok)", r is AssistantMover.MoveResult.Ok)
            check("rerouted to nearest free spot", b.applied.last() == (600 to 24))
            check(
                "rerouted spot covers nothing interactive",
                AssistantPlacementCoversCheck(b),
            )
        }
    }

    // 6. Check-before-move: a nudge onto a button reroutes two rings out.
    run {
        val b = FakeBackend(
            model = ScreenModel(
                "com.demo.app",
                listOf(el("b1", ElementRole.BUTTON, 444, 400, 612, 592)),
            ),
        )
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.RIGHT)
            check("covered nudge still moves (Ok)", r is AssistantMover.MoveResult.Ok)
            check("nudge rerouted past the button", b.applied.last() == (732 to 400))
        }
    }

    // 7. Check-before-move: fully covered screen fails honestly, no apply.
    run {
        val b = FakeBackend(
            model = ScreenModel(
                "com.demo.app",
                listOf(el("wall", ElementRole.BUTTON, 0, 0, 1080, 2400)),
            ),
        )
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.TOP_LEFT)
            check("fully covered move fails", r is AssistantMover.MoveResult.Failed)
            check(
                "failure names the reason",
                (r as AssistantMover.MoveResult.Failed).reason.contains("cover"),
            )
            check("no apply on failure", b.applied.isEmpty())
            check("no persist on failure", b.persisted.isEmpty())
        }
    }

    // 8. OUT_OF_WAY: buttons on the left two-thirds -> free right side.
    run {
        val b = FakeBackend(
            model = ScreenModel(
                "com.demo.app",
                listOf(el("b1", ElementRole.BUTTON, 0, 0, 700, 2400)),
            ),
        )
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.OUT_OF_WAY)
            check("OUT_OF_WAY succeeds", r is AssistantMover.MoveResult.Ok)
            // Grid step 72px: farthest free column is x=864 (864+168=1032).
            check("OUT_OF_WAY lands far from content", b.applied.last() == (864 to 0))
            check(
                "OUT_OF_WAY spot covers nothing interactive",
                AssistantPlacementCoversCheck(b),
            )
        }
    }

    // 9. OUT_OF_WAY with no tree fails honestly.
    run {
        val b = FakeBackend(model = null)
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.OUT_OF_WAY)
            check("OUT_OF_WAY without tree fails", r is AssistantMover.MoveResult.Failed)
            check("no apply without tree", b.applied.isEmpty())
        }
    }

    // 10. Honest failures: no backend, no overlay rect, no tree.
    run {
        AssistantMover.register(null)
        val r = AssistantMover.move(AssistantMover.AssistantRegion.TOP_LEFT)
        check("no backend fails", r is AssistantMover.MoveResult.Failed)
        check(
            "no-backend reason names the assistant",
            (r as AssistantMover.MoveResult.Failed).reason.contains("assistant"),
        )
    }
    run {
        val b = FakeBackend(rect = null)
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.TOP_LEFT)
            check("no overlay rect fails", r is AssistantMover.MoveResult.Failed)
            check("no apply without overlay", b.applied.isEmpty())
        }
    }
    run {
        val b = FakeBackend(model = null)
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.BOTTOM_LEFT)
            check("no tree fails", r is AssistantMover.MoveResult.Failed)
            check(
                "no-tree reason names screen reading",
                (r as AssistantMover.MoveResult.Failed).reason.contains("read the screen"),
            )
        }
    }

    // 11. View detaches mid-move: applyMove false -> Failed.
    run {
        val b = FakeBackend(applyOk = false)
        withBackend(b) {
            val r = AssistantMover.move(AssistantMover.AssistantRegion.TOP_LEFT)
            check("detached view fails", r is AssistantMover.MoveResult.Failed)
            check("no persist on detached failure", b.persisted.isEmpty())
        }
    }

    // 12. Persistence: the final position is persisted via the backend.
    run {
        val b = FakeBackend()
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.TOP_LEFT)
            check("position persisted", b.persisted.last() == (24 to 24))
            check("persist matches apply", b.persisted.last() == b.applied.last())
        }
    }

    // 13. OUT_OF_WAY on an empty screen centres the assistant.
    run {
        val b = FakeBackend(model = ScreenModel("com.demo.app", emptyList()))
        withBackend(b) {
            AssistantMover.move(AssistantMover.AssistantRegion.OUT_OF_WAY)
            check(
                "empty screen centres",
                b.applied.last() == (540 - 84 to 1200 - 96),
            )
        }
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}

/** Helper: the last applied spot covers no interactive element in the model. */
private fun AssistantPlacementCoversCheck(b: FakeBackend): Boolean {
    val (x, y) = b.applied.last()
    val model = b.model ?: return false
    val cand = com.loom.assistant.interaction.pointer.GeomRect(
        x.toFloat(), y.toFloat(), (x + 168).toFloat(), (y + 192).toFloat(),
    )
    return com.loom.assistant.interaction.pointer.AssistantPlacement
        .coversInteractive(cand, model.elements) == null
}
