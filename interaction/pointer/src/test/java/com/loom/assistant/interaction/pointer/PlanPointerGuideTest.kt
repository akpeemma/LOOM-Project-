package com.loom.assistant.interaction.pointer

import android.graphics.Rect
import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.planner.PlanParams
import com.loom.assistant.intelligence.planner.PlanStep
import com.loom.assistant.intelligence.planner.TargetBounds
import com.loom.assistant.intelligence.planner.TaskStepKind

/**
 * Stage 2D (Worker C) — pure-JVM checks for PlanPointerGuide.
 *
 * Covers: moveTo parks/glides to the target (lead dart on the centre,
 * 250–300ms glide); showIntended renders outline + plain-words label
 * before any tap and draws nothing when the target cannot be resolved;
 * trackTarget follows a moved target and reports a gone target as
 * false (never pretends to track it); the no-idle rule (pointer stays
 * on the plan target, idle pulse only with no active target).
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 \
 *     interaction/pointer/src/test/java/android/graphics/Rect.kt \
 *     interaction/pointer/src/test/java/android/graphics/RectF.kt \
 *     core/common/src/main/java/com/loom/assistant/core/common/PointerMove.kt \
 *     core/common/src/main/java/com/loom/assistant/core/common/RequestOrigin.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenDataPolicy.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/core/common/FakeLoomBus.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/intelligence/authgate/FakeAuthGate.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerMotion.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerSelection.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceRendererContract.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceSession.kt \
 *     intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/PlanContracts.kt \
 *     intelligence/planner/src/main/java/com/loom/assistant/intelligence/planner/TaskPlan.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PlanPointerGuide.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/PointerViewStubs.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/GuidanceTreeStub.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/PlanPointerGuideTest.kt
 *   java -cp out com.loom.assistant.interaction.pointer.PlanPointerGuideTestKt
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

private class FakeRenderer : GuidanceRendererContract {
    val parks = mutableListOf<Pair<RectF, String>>()
    val previews = mutableListOf<RectF>()
    val narrations = mutableListOf<String>()
    var idleCalls = 0
    var lastFailure: String? = null
    var throwOnPark = false
    private var cursor: GeomPoint? = null

    override fun showStep(
        anchorX: Float, anchorY: Float, target: RectF,
        stepIndex: Int, stepCount: Int, instruction: String,
    ) {
    }

    override fun hide() {}

    override fun previewMoveTo(target: RectF) {
        previews += target
        cursor = GeomPoint(target.centerX(), target.centerY())
    }

    override fun pulseOnResult() {}

    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {}

    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean = false

    override fun parkPointer(target: RectF, label: String) {
        if (throwOnPark) throw IllegalStateException("fake draw threw")
        parks += target to label
        cursor = GeomPoint(target.centerX(), target.centerY())
    }

    override fun setStatusNarration(text: String) {
        narrations += text
    }

    override fun showIdleIndicator(): Boolean {
        idleCalls++
        return true
    }

    override fun cursorPosition(): GeomPoint? = cursor

    override fun lastDrawFailure(): String? = lastFailure
}

private class FakeTree(var model: ScreenModel?, var bound: Boolean = true) : GuidanceTreeSource {
    override val isBound: Boolean get() = bound
    override fun snapshot(): ScreenModel? = model
}

private fun searchButton(bounds: Rect = Rect(100, 200, 300, 260)) = ScreenElement(
    id = "search-btn",
    text = "Search",
    contentDescription = null,
    className = "android.widget.Button",
    bounds = bounds,
    role = ElementRole.BUTTON,
    enabled = true,
    isPassword = false,
)

private fun newGuide(
    tree: FakeTree,
    renderer: FakeRenderer,
): Pair<GuidanceSession, PlanPointerGuide> {
    val session = GuidanceSession(
        steps = emptyList(),
        tree = tree,
        renderer = renderer,
        anchorProvider = { 0f to 0f },
    )
    return session to PlanPointerGuide(session, renderer)
}

fun main() {
    val target = TargetBounds(100, 200, 300, 260)
    val targetRect = RectF(100f, 200f, 300f, 260f)

    // 1. moveTo with highlight parks the pointer on the target.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        guide.moveTo(target, highlight = true)
        check("moveTo parks on the target bounds", renderer.parks.lastOrNull()?.first == targetRect)
        check("moveTo records the active target", guide.activeTarget == target)
        check(
            "lead dart sits on the target centre after moveTo",
            renderer.cursorPosition() == GeomPoint(200f, 230f),
        )
        check(
            "glide duration is the 250ms baseline to 300ms cap",
            guide.glideDurationMs(TargetBounds(900, 1500, 1100, 1600)) in 250L..300L,
        )
        // 2. A second move without highlight glides from the live pointer.
        val parksBefore = renderer.parks.size
        guide.moveTo(TargetBounds(400, 500, 600, 560), highlight = false)
        check("second moveTo without highlight glides (preview)", renderer.previews.lastOrNull() == RectF(400f, 500f, 600f, 560f))
        check("second moveTo without highlight does not re-park", renderer.parks.size == parksBefore)
        check("active target follows the second move", guide.activeTarget == TargetBounds(400, 500, 600, 560))
        // 3. No-idle: the pointer stays on the plan target.
        check("idle pulse refused while a plan target is active", !guide.showIdleIfAllowed())
        check("idle pulse never reached the renderer while active", renderer.idleCalls == 0)
        guide.clearPlanTarget()
        check("idle pulse allowed once the plan target clears", guide.showIdleIfAllowed())
        check("idle pulse reached the renderer once", renderer.idleCalls == 1)
        check("clearPlanTarget clears the active target", guide.activeTarget == null)
    }

    // 4. showIntended from BOUNDS params: outline + label before any tap.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        val step = PlanStep(
            index = 0,
            kind = TaskStepKind.TAP_TARGET,
            description = "Tap Search",
            params = mapOf(PlanParams.BOUNDS to "100,200,300,260"),
        )
        guide.showIntended(step)
        check("showIntended parks the BOUNDS outline", renderer.parks.lastOrNull()?.first == targetRect)
        check("showIntended puts the plain-words label in the pill", renderer.parks.lastOrNull()?.second == "Tap Search")
        check("showIntended narrates the step description", renderer.narrations.lastOrNull() == "Tap Search")
        check("showIntended records the active target", guide.activeTarget == target)
    }

    // 5. showIntended from a TARGET query resolves against the live tree.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        val step = PlanStep(
            index = 0,
            kind = TaskStepKind.TAP_TARGET,
            description = "Tap Search",
            params = mapOf(PlanParams.TARGET to "Search"),
        )
        guide.showIntended(step)
        check("showIntended resolves TARGET against the tree", renderer.parks.lastOrNull()?.first == targetRect)
        val guideStep = PlanStep(
            index = 0,
            kind = TaskStepKind.GUIDE_TO,
            description = "Guide to Search",
            params = mapOf(PlanParams.GUIDE_TARGET to "Search"),
        )
        guide.showIntended(guideStep)
        check("showIntended resolves GUIDE_TARGET against the tree", renderer.parks.lastOrNull()?.first == targetRect)
    }

    // 6. showIntended with no resolvable target draws nothing — never a fake outline.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        val step = PlanStep(
            index = 0,
            kind = TaskStepKind.TAP_TARGET,
            description = "Tap something missing",
        )
        guide.showIntended(step)
        check("showIntended with no bounds draws nothing", renderer.parks.isEmpty())
        check("showIntended with no bounds sets no active target", guide.activeTarget == null)
    }

    // 7. trackTarget follows a moved target and reports a gone target as false.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        guide.moveTo(target, highlight = true)
        tree.model = ScreenModel("com.demo.app", listOf(searchButton(Rect(400, 500, 600, 560))))
        check("trackTarget returns true on a moved target", guide.trackTarget("search-btn"))
        check(
            "trackTarget glides to the new bounds",
            guide.activeTarget == TargetBounds(400, 500, 600, 560),
        )
        check(
            "trackTarget parks/previews the new bounds",
            renderer.parks.lastOrNull()?.first == RectF(400f, 500f, 600f, 560f),
        )
        tree.model = ScreenModel("com.demo.app", emptyList())
        check("trackTarget returns false on a gone target", !guide.trackTarget("search-btn"))
        check("gone target clears the active target", guide.activeTarget == null)
        tree.bound = false
        check("trackTarget returns false when the tree is unbound", !guide.trackTarget("search-btn"))
    }

    // 8. Pointer-drive fix (2026-11-06): trackTarget distinguishes a
    // draw failure from a genuinely gone target — a draw failure keeps
    // tracking (the target is there) and is reported as a pointer
    // failure, never as "target gone".
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer().also { it.throwOnPark = true }
        val (_, guide) = newGuide(tree, renderer)
        guide.moveTo(target, highlight = true)
        check(
            "moveTo with a throwing draw records a pointer failure",
            guide.lastPointerFailure()?.contains("could not be shown") == true,
        )
        check(
            "trackTarget returns true when only the draw failed",
            guide.trackTarget("search-btn"),
        )
        check(
            "draw failure inside trackTarget is a pointer failure, not target-gone",
            guide.lastPointerFailure()?.contains("could not be shown") == true,
        )
        check(
            "draw failure keeps the active target (the target is there)",
            guide.activeTarget == target,
        )
        tree.model = ScreenModel("com.demo.app", emptyList())
        check("trackTarget returns false on a genuinely gone target", !guide.trackTarget("search-btn"))
        check(
            "genuinely gone target is NOT a pointer failure",
            guide.lastPointerFailure() == null,
        )
    }

    // 9. Pointer-drive fix (2026-11-06): moveTo with no renderer
    // records a pointer failure instead of silently doing nothing.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val session = GuidanceSession(
            steps = emptyList(),
            tree = tree,
            renderer = null,
            anchorProvider = { 0f to 0f },
        )
        val guide = PlanPointerGuide(session, null)
        guide.moveTo(target, highlight = true)
        check(
            "moveTo with no renderer records a pointer failure",
            guide.lastPointerFailure()?.contains("floating assistant isn't running") == true,
        )
        check(
            "moveTo with no renderer still records the target",
            guide.activeTarget == target,
        )
        guide.clearPlanTarget()
        check("clearPlanTarget clears the pointer failure", guide.lastPointerFailure() == null)
    }

    // 10. Pointer-drive fix (2026-11-06): showIntended with no
    // resolvable target narrates the step in the pill — still never a
    // fake outline.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        val step = PlanStep(
            index = 0,
            kind = TaskStepKind.LAUNCH_APP,
            description = "Open YouTube Music",
        )
        guide.showIntended(step)
        check("showIntended with no bounds draws no outline", renderer.parks.isEmpty())
        check(
            "showIntended with no bounds narrates the step",
            renderer.narrations.lastOrNull() == "Open YouTube Music",
        )
        check("showIntended with no bounds records no failure", guide.lastPointerFailure() == null)
    }

    // 11. Pointer-drive fix (2026-11-06): the glide wait is the
    // 250ms baseline, capped at 300ms — the pointer arrives before
    // the action dispatches.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        guide.moveTo(target, highlight = true)
        check(
            "glide wait is the 250ms baseline to 300ms cap",
            guide.glideWaitMs(TargetBounds(900, 1500, 1100, 1600)) in 250L..300L,
        )
        check(
            "glide wait is zero-length for a zero move",
            guide.glideWaitMs(target) == 250L,
        )
    }

    // 12. Pointer-drive fix (2026-11-06): an inner draw failure (e.g.
    // a HIDE-policy swallow) surfaces through lastPointerFailure.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer().also {
            it.lastFailure = "the pointer is hidden by your pointer setting"
        }
        val (_, guide) = newGuide(tree, renderer)
        guide.moveTo(target, highlight = true)
        check(
            "inner draw failure surfaces as the pointer failure",
            guide.lastPointerFailure() == "the pointer is hidden by your pointer setting",
        )
    }

    // 13. MASTER Wave 4 (F11): the renderer provider is re-invoked
    // on every draw — the old once-captured override could keep
    // drawing into a detached view for a whole run. Swap the
    // renderer mid-run and the new one is used.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val session = GuidanceSession(
            steps = emptyList(),
            tree = tree,
            renderer = null,
            anchorProvider = { 0f to 0f },
        )
        val rendererA = FakeRenderer()
        val rendererB = FakeRenderer()
        var current: GuidanceRendererContract? = rendererA
        val guide = PlanPointerGuide(session, rendererProvider = { current })
        guide.moveTo(target, highlight = true)
        check("first draw goes to the first renderer", rendererA.parks.size == 1)
        check("second renderer untouched so far", rendererB.parks.isEmpty())
        current = rendererB
        guide.moveTo(TargetBounds(400, 500, 600, 560), highlight = true)
        check("after the swap the new renderer is used", rendererB.parks.size == 1)
        check("old renderer gets no further draws", rendererA.parks.size == 1)
    }

    // 14. MASTER Wave 4 (F11): a failed draw is a RECORDED failure
    // (lastPointerFailure names it) and parks nothing — but the
    // INTENDED target is still recorded for tracking (Nov 6 rule),
    // and the pointer does not count as live.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer().also { it.throwOnPark = true }
        val (_, guide) = newGuide(tree, renderer)
        guide.moveTo(target, highlight = true)
        check("failed draw parks nothing", renderer.parks.isEmpty())
        check(
            "failed draw is recorded, not swallowed",
            guide.lastPointerFailure()?.contains("could not be shown") == true,
        )
        check("intended target still recorded for tracking", guide.activeTarget == target)
        check("no-idle rule still holds on the intended target", !guide.showIdleIfAllowed())
    }

    // 15. MASTER Wave 4 (F11 strengthening): verified-only parking.
    run {
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(searchButton())))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        check(
            "unverified target is NOT parked on",
            !guide.parkVerified(target, "Some random row", verified = false),
        )
        check("unverified target leaves no park", renderer.parks.isEmpty())
        check(
            "verified target parks and reports true",
            guide.parkVerified(target, "Search", verified = true),
        )
        check("verified target parks on its bounds", renderer.parks.lastOrNull()?.first == targetRect)
        // A verified target whose draw fails reports false.
        val throwing = FakeRenderer().also { it.throwOnPark = true }
        val (_, guide2) = newGuide(tree, throwing)
        check(
            "verified target with a failed draw reports false",
            !guide2.parkVerified(target, "Search", verified = true),
        )
        check(
            "failed verified park is recorded",
            guide2.lastPointerFailure() != null,
        )
    }

    // 16. MASTER Wave 4 (F11 strengthening): neutral home park —
    // no verified target → top-centre, off the content, never a
    // random element; activeTarget stays null (nothing claimed).
    run {
        val fullScreen = ScreenElement(
            id = "root",
            text = null,
            contentDescription = null,
            className = "android.widget.FrameLayout",
            bounds = Rect(0, 0, 1080, 2400),
            role = ElementRole.LIST,
            enabled = true,
            isPassword = false,
        )
        val tree = FakeTree(ScreenModel("com.demo.app", listOf(fullScreen)))
        val renderer = FakeRenderer()
        val (_, guide) = newGuide(tree, renderer)
        guide.parkAtNeutralHome()
        val home = renderer.parks.lastOrNull()
        check("home park draws once", home != null)
        check("home park is top-centre", home?.first?.centerX() == 540f)
        check("home park sits near the top", home?.first?.centerY() == 240f)
        check("home park labels itself LOOM", home?.second == "LOOM")
        check("home park claims no target", guide.activeTarget == null)
    }

    // Device-test failures (2026-11-11, D-08): on LOOM's own chat
    // screen the transcript echoes David's words — a bubble saying
    // "tap my story" must never be a guidance target. The pointer
    // parked on the chat echo in the Nov 11 screenshots.
    run {
        val echo = ScreenElement(
            id = "bubble",
            text = "tap my story",
            contentDescription = null,
            className = "android.widget.TextView",
            bounds = Rect(100, 500, 900, 560),
            role = ElementRole.TEXT,
            enabled = true,
            isPassword = false,
        )
        val chatScreen = ScreenModel("com.loom.assistant", listOf(echo))
        val tree = FakeTree(chatScreen)
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = emptyList(),
            tree = tree,
            renderer = renderer,
            ownPackageName = "com.loom.assistant",
            anchorProvider = { 0f to 0f },
        )
        val result = session.start("my story")
        check("own-screen chat echo is not a target", result is GuidanceSession.StepResult.Unavailable)
        check(
            "own-screen failure says the screen is LOOM's chat",
            (result as? GuidanceSession.StepResult.Unavailable)?.reason?.contains("LOOM's own chat screen") == true,
        )
        check("own-screen echo draws nothing", renderer.parks.isEmpty())
        // Without the own-package filter the same echo resolves —
        // the filter is what keeps the pointer off the chat.
        val unfiltered = GuidanceSession(
            steps = emptyList(),
            tree = tree,
            renderer = FakeRenderer(),
            anchorProvider = { 0f to 0f },
        )
        check(
            "unfiltered echo resolves (the filter does the work)",
            unfiltered.start("my story") is GuidanceSession.StepResult.Shown,
        )
        // A BUTTON on the own screen still resolves — only TEXT
        // echoes are filtered, not real controls.
        val micButton = echo.copy(id = "mic", role = ElementRole.BUTTON, text = "my story")
        val buttonTree = FakeTree(ScreenModel("com.loom.assistant", listOf(micButton)))
        val buttonSession = GuidanceSession(
            steps = emptyList(),
            tree = buttonTree,
            renderer = FakeRenderer(),
            ownPackageName = "com.loom.assistant",
            anchorProvider = { 0f to 0f },
        )
        check(
            "own-screen button still resolves",
            buttonSession.start("my story") is GuidanceSession.StepResult.Shown,
        )
    }
    // Corrected 2026-11-11 01:54: a not-found answer names where LOOM
    // looked — never a list of what is on the screen, never a question
    // asking the user where the thing is.
    run {
        val other = ScreenElement(
            id = "home-row",
            text = "Home",
            contentDescription = null,
            className = "android.widget.TextView",
            bounds = Rect(100, 200, 500, 260),
            role = ElementRole.TEXT,
            enabled = true,
            isPassword = false,
        )
        val tree = FakeTree(ScreenModel("com.facebook.katana", listOf(other)))
        val session = GuidanceSession(
            steps = emptyList(),
            tree = tree,
            renderer = FakeRenderer(),
            anchorProvider = { 0f to 0f },
        )
        val result = session.start("my story")
        check("not-found is Unavailable", result is GuidanceSession.StepResult.Unavailable)
        val reason = (result as? GuidanceSession.StepResult.Unavailable)?.reason.orEmpty()
        check("not-found names what was looked for", reason.contains("my story"))
        check("not-found names where it looked", reason.contains("Facebook"))
        check("not-found never lists screen contents", !reason.contains("I can see:"))
    }

    println("PlanPointerGuideTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
