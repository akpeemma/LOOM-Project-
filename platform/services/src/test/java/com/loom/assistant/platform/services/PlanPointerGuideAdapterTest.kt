package com.loom.assistant.platform.services

import android.graphics.Rect
import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.TargetBounds
import com.loom.assistant.intelligence.planner.TaskStepKind
import com.loom.assistant.interaction.pointer.GeomPoint
import com.loom.assistant.interaction.pointer.GuidanceRendererContract

/**
 * Stage 2D (Worker B) — PlanPointerGuideAdapter checks.
 *
 * Run (from loom-android):
 *   KOTLINC=$HOME/workspace/brahma/loom/toolchain/kotlin/kotlinc/bin/kotlinc
 *   JAVA=$HOME/workspace/brahma/loom/toolchain/jdk-17/bin/java
 *   STDLIB=$HOME/workspace/brahma/loom/toolchain/kotlin/kotlinc/lib/kotlin-stdlib.jar
 *   R=$HOME/workspace/brahma/loom/build/loom-android
 *   H=$HOME/workspace/brahma/loom/build/test-harness
 *   $KOTLINC -d out -jvm-target 17 -cp $STDLIB \
 *     $H/fakes/android/graphics/Rect.kt $H/fakes/android/graphics/RectF.kt \
 *     $R/core/common/src/main/java/com/loom/assistant/core/common/RequestOrigin.kt \
 *     $R/core/common/src/main/java/com/loom/assistant/core/common/PointerMove.kt \
 *     $R/execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     $R/execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenDataPolicy.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanContracts.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/TaskPlan.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanRequest.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanValidator.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/TaskPlanner.kt \
 *     $R/intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanFailureAnalyzer.kt \
 *     $R/interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     $R/interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerMotion.kt \
 *     $R/interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceRendererContract.kt \
 *     $R/execution/tasks/src/main/java/com/loom/assistant/execution/tasks/PlanVerify.kt \
 *     $R/platform/services/src/main/java/com/loom/assistant/platform/services/PlanPointerGuideAdapter.kt \
 *     $R/platform/services/src/test/java/com/loom/assistant/platform/services/PlanPointerGuideAdapterTest.kt \
 *   && $JAVA -cp out:$STDLIB com.loom.assistant.platform.services.PlanPointerGuideAdapterTestKt
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

private class FakeRenderer : GuidanceRendererContract {
    val previews = mutableListOf<RectF>()
    val parks = mutableListOf<Pair<RectF, String>>()
    val narrations = mutableListOf<String>()
    var throwOnPark = false
    var lastFailure: String? = null

    override fun showStep(anchorX: Float, anchorY: Float, target: RectF, stepIndex: Int, stepCount: Int, instruction: String) {}
    override fun hide() {}
    override fun previewMoveTo(target: RectF) {
        previews.add(target)
    }

    override fun pulseOnResult() {}
    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {}
    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean = true
    override fun parkPointer(target: RectF, label: String) {
        if (throwOnPark) throw IllegalStateException("fake draw threw")
        parks.add(target to label)
    }

    override fun setStatusNarration(text: String) {
        narrations.add(text)
    }

    override fun cursorPosition(): GeomPoint? = null

    override fun lastDrawFailure(): String? = lastFailure
}

private fun pEl(id: String) = ScreenElement(
    id = id,
    text = "Row",
    contentDescription = null,
    className = "android.widget.TextView",
    bounds = Rect(10, 120, 500, 200),
    role = ElementRole.TEXT,
    enabled = true,
    isPassword = false,
)

fun main() {
    val screen = ScreenModel(
        packageName = "com.google.android.apps.youtube.music",
        elements = listOf(pEl("row-fall"), pEl("row-if")),
    )

    // ---------- 1. moveTo glides to the bounds; highlight parks ----------
    run {
        val renderer = FakeRenderer()
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        adapter.moveTo(TargetBounds(10, 120, 500, 200), highlight = true)
        checkEq("moveTo previews the bounds", renderer.previews.singleOrNull()?.top, 120f)
        checkEq("highlight parks on the target", renderer.parks.singleOrNull()?.first?.top, 120f)
        checkEq("highlight parks with the target label", renderer.parks.singleOrNull()?.second, "Target")
    }

    // ---------- 2. moveTo without highlight only previews ----------
    run {
        val renderer = FakeRenderer()
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        adapter.moveTo(TargetBounds(10, 120, 500, 200), highlight = false)
        checkEq("plain move previews once", renderer.previews.size, 1)
        checkEq("plain move parks nothing", renderer.parks.size, 0)
    }

    // ---------- 3. no renderer: no crash, and the gap is recorded ----------
    run {
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = null,
        )
        adapter.moveTo(TargetBounds(10, 120, 500, 200), highlight = true)
        adapter.showIntended(
            PlanStep(0, TaskStepKind.TAP_TARGET, "Tap", mapOf(PlanParams.BOUNDS to "10,120,500,200")),
        )
        check("no-renderer move never crashes", true)
        check(
            "no-renderer move records a pointer failure instead of staying silent",
            adapter.lastPointerFailure()?.contains("floating assistant isn't running") == true,
        )
    }

    // ---------- 4. showIntended parks on the step's bounds ----------
    run {
        val renderer = FakeRenderer()
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        adapter.showIntended(
            PlanStep(
                2, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                mapOf(PlanParams.BOUNDS to "10,120,500,200"),
            ),
        )
        checkEq("showIntended parks the step target", renderer.parks.singleOrNull()?.first?.top, 120f)
        checkEq(
            "showIntended narrates the step",
            renderer.parks.singleOrNull()?.second,
            "Tap the first result for Davido",
        )
    }

    // ---------- 6. throwing renderer: failure recorded, never silent ----------
    run {
        val renderer = FakeRenderer().also { it.throwOnPark = true }
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        adapter.moveTo(TargetBounds(10, 120, 500, 200), highlight = true)
        check(
            "throwing draw records a pointer failure",
            adapter.lastPointerFailure()?.contains("could not be shown") == true,
        )
        check(
            "throwing draw never crashes the move",
            true,
        )
    }

    // ---------- 7. moveTo keeps the step's label after showIntended ----------
    run {
        val renderer = FakeRenderer()
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        adapter.showIntended(
            PlanStep(
                2, TaskStepKind.TAP_TARGET, "Tap the first result for Davido",
                mapOf(PlanParams.BOUNDS to "10,120,500,200"),
            ),
        )
        adapter.moveTo(TargetBounds(10, 120, 500, 200), highlight = true)
        checkEq(
            "park after showIntended keeps the step label",
            renderer.parks.lastOrNull()?.second,
            "Tap the first result for Davido",
        )
    }

    // ---------- 8. glide wait: 250-300ms with a live renderer, 0 without ----------
    run {
        val renderer = FakeRenderer()
        val live = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        check(
            "glide wait is the 250ms baseline to 300ms cap",
            live.glideWaitMs(TargetBounds(10, 120, 500, 200)) in 250L..300L,
        )
        val none = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = null,
        )
        checkEq("glide wait is 0 with no renderer", none.glideWaitMs(TargetBounds(10, 120, 500, 200)), 0L)
    }

    // ---------- 9. trackTarget never confuses a draw failure with gone ----------
    run {
        val renderer = FakeRenderer().also { it.throwOnPark = true }
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        adapter.moveTo(TargetBounds(10, 120, 500, 200), highlight = true)
        check("moveTo draw failure recorded", adapter.lastPointerFailure() != null)
        check("trackTarget still finds a live id after a draw failure", adapter.trackTarget("row-fall"))
        check("trackTarget still misses a gone id after a draw failure", !adapter.trackTarget("row-gone"))
    }

    // ---------- 10. inner draw failure (e.g. HIDE swallow) surfaces ----------
    run {
        val renderer = FakeRenderer().also {
            it.lastFailure = "the pointer is hidden by your pointer setting"
        }
        val adapter = PlanPointerGuideAdapter(
            observer = object : ScreenObserver {
                override fun observeNow(): ScreenModel = screen
            },
            rendererProvider = { renderer },
        )
        adapter.moveTo(TargetBounds(10, 120, 500, 200), highlight = true)
        checkEq(
            "inner draw failure surfaces as the pointer failure",
            adapter.lastPointerFailure(),
            "the pointer is hidden by your pointer setting",
        )
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
