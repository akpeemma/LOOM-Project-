package com.loom.assistant.interaction.pointer

import android.graphics.Rect
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Master implementation §6 — ExecutionFeedback tests.
 *
 * Covers: screen summary counts + interactive labels; label redaction
 * inside sensitive packages; target / pointer / intended-action /
 * last-action / next-step round-trip through snapshot(); describe()
 * wording (honest when nothing is reported); clearTarget.
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 \
 *     interaction/pointer/src/test/java/android/graphics/Rect.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenDataPolicy.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/AssistantPlacement.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ExecutionFeedback.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/ExecutionFeedbackTest.kt
 *   java -cp out com.loom.assistant.interaction.pointer.ExecutionFeedbackTestKt
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
    text: String?,
    role: ElementRole,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): ScreenElement = ScreenElement(
    id = id,
    text = text,
    contentDescription = null,
    className = "android.widget.Button",
    bounds = Rect(left, top, right, bottom),
    role = role,
    enabled = true,
    isPassword = false,
)

fun main() {
    // 1. Fresh feedback reports nothing — honestly.
    run {
        val fb = ExecutionFeedback()
        val s = fb.snapshot()
        check("fresh snapshot has no screen", s.screen == null)
        check("fresh snapshot has no target", s.target == null)
        check("fresh snapshot has no last action", s.lastAction == null)
        check("fresh snapshot is inactive", !s.sequenceActive)
        check(
            "describe() says nothing is running",
            fb.describe().contains("Nothing is running right now."),
        )
    }

    // 2. Screen summary: counts + interactive labels.
    run {
        val fb = ExecutionFeedback()
        fb.updateScreen(
            ScreenModel(
                "com.demo.app",
                listOf(
                    el("b1", "Search", ElementRole.BUTTON, 100, 100, 300, 160),
                    el("b2", "Play", ElementRole.BUTTON, 100, 200, 300, 260),
                    el("t1", "Hello", ElementRole.TEXT, 100, 300, 500, 340),
                    el("i1", null, ElementRole.INPUT, 100, 400, 500, 460),
                ),
            ),
        )
        val scr = fb.snapshot().screen
        check("screen package kept", scr?.packageName == "com.demo.app")
        check("total elements counted", scr?.totalElements == 4)
        check("interactive counted (2 buttons + 1 input)", scr?.interactiveCount == 3)
        check(
            "interactive labels listed",
            scr?.interactiveLabels?.containsAll(listOf("Search", "Play")) == true,
        )
        check("labels not hidden outside sensitive apps", scr?.labelsHidden == false)
        val d = fb.describe()
        check("describe() names the package", d.contains("com.demo.app"))
        check("describe() names the buttons", d.contains("Search") && d.contains("Play"))
    }

    // 3. Sensitive package: labels withheld, counts kept.
    run {
        val fb = ExecutionFeedback()
        fb.updateScreen(
            ScreenModel(
                "com.kuda.app",
                listOf(
                    el("b1", "Send money", ElementRole.BUTTON, 100, 100, 300, 160),
                    el("t1", "Balance ₦5,000", ElementRole.TEXT, 100, 200, 500, 240),
                ),
            ),
        )
        val scr = fb.snapshot().screen
        check("sensitive package still counts", scr?.totalElements == 2)
        check("sensitive package still counts interactive", scr?.interactiveCount == 1)
        check("sensitive labels hidden", scr?.labelsHidden == true)
        check("sensitive labels list empty", scr?.interactiveLabels?.isEmpty() == true)
        check(
            "describe() says labels are hidden",
            fb.describe().contains("hides its labels"),
        )
        check(
            "describe() leaks no sensitive label",
            !fb.describe().contains("Send money") &&
                !fb.describe().contains("Balance"),
        )
    }

    // 4. Target / pointer / intended action / last action / next step.
    run {
        val fb = ExecutionFeedback()
        fb.setSequenceActive(true)
        fb.setIntendedAction("Demonstrate: open YouTube")
        fb.setTarget("Search", GeomRect(100f, 100f, 300f, 160f), "button")
        fb.setPointerPosition(GeomPoint(200f, 130f))
        fb.recordAction("tap Search", "completed")
        fb.setNextStep("Step 2 of 3: type the query")
        val s = fb.snapshot()
        check("sequence active", s.sequenceActive)
        check("intended action kept", s.intendedAction == "Demonstrate: open YouTube")
        check("target label kept", s.target?.label == "Search")
        check("target bounds kept", s.target?.bounds == GeomRect(100f, 100f, 300f, 160f))
        check("pointer kept", s.pointerPosition == GeomPoint(200f, 130f))
        check("last action kept", s.lastAction == "tap Search")
        check("last result kept", s.lastResult == "completed")
        check("next step kept", s.nextStep == "Step 2 of 3: type the query")
        val d = fb.describe()
        check("describe() names the target", d.contains("Search (button)"))
        check("describe() names the last action", d.contains("tap Search — completed"))
        check("describe() names the next step", d.contains("Step 2 of 3"))
        check("describe() names the pointer", d.contains("Pointer at (200, 130)"))
    }

    // 5. clearTarget + updateScreen(null) clear their fields only.
    run {
        val fb = ExecutionFeedback()
        fb.setTarget("X", GeomRect(0f, 0f, 10f, 10f), "button")
        fb.setPointerPosition(GeomPoint(5f, 5f))
        fb.updateScreen(ScreenModel("com.demo.app", emptyList()))
        fb.clearTarget()
        check("clearTarget clears target", fb.snapshot().target == null)
        fb.setPointerPosition(null)
        check("pointer cleared", fb.snapshot().pointerPosition == null)
        check("screen survives target clear", fb.snapshot().screen != null)
        fb.updateScreen(null)
        check("updateScreen(null) clears screen", fb.snapshot().screen == null)
        check(
            "describe() is honest when the screen goes away",
            fb.describe().contains("can't see the screen"),
        )
    }

    // 6. Snapshot is a copy: later updates don't rewrite an old snapshot.
    run {
        val fb = ExecutionFeedback()
        fb.setIntendedAction("first")
        val first = fb.snapshot()
        fb.setIntendedAction("second")
        check("old snapshot unchanged", first.intendedAction == "first")
        check("new snapshot updated", fb.snapshot().intendedAction == "second")
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
