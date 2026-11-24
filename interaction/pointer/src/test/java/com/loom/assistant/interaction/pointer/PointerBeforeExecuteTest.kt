package com.loom.assistant.interaction.pointer

import android.graphics.Rect
import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.authgate.AuthGate

/**
 * Master implementation §6 — pointer-to-target-before-execute (session tap path).
 *
 * The ActionQueue demo path already previews per step (existing
 * ActionQueueTest: previews == steps, showStep per step). The gap was
 * [GuidanceSession.requestTap] — the "tap it for me" path — which
 * dispatched with no pointer preview at all. This test proves the hook:
 * requestTap() moves the pointer to the freshly verified target
 * (renderer.previewMoveTo with the element bounds) BEFORE the gate
 * request and BEFORE the dispatcher runs, on every gate outcome.
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 \
 *     interaction/pointer/src/test/java/android/graphics/Rect.kt \
 *     interaction/pointer/src/test/java/android/graphics/RectF.kt \
 *     core/common/src/main/java/com/loom/assistant/core/common/PointerMove.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenDataPolicy.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/core/common/FakeLoomBus.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/intelligence/authgate/FakeAuthGate.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerMotion.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerSelection.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceRendererContract.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceSession.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/PointerViewStubs.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/GuidanceTreeStub.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/PointerBeforeExecuteTest.kt
 *   java -cp out com.loom.assistant.interaction.pointer.PointerBeforeExecuteTestKt
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

private class FakeRenderer(val log: MutableList<String>) : GuidanceRendererContract {
    val previews = mutableListOf<RectF>()
    override fun showStep(
        anchorX: Float, anchorY: Float, target: RectF,
        stepIndex: Int, stepCount: Int, instruction: String,
    ) {
        log += "show"
    }

    override fun hide() {
        log += "hide"
    }

    override fun previewMoveTo(target: RectF) {
        previews += target
        log += "preview"
    }

    override fun pulseOnResult() {
        log += "pulse"
    }

    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
        log += "anchor"
    }

    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean = false
}

private class FakeTree(var model: ScreenModel?) : GuidanceTreeSource {
    override val isBound: Boolean get() = true
    override fun snapshot(): ScreenModel? = model
}

private fun searchButton() = ScreenElement(
    id = "search-btn",
    text = "Search",
    contentDescription = null,
    className = "android.widget.Button",
    bounds = Rect(100, 200, 300, 260),
    role = ElementRole.BUTTON,
    enabled = true,
    isPassword = false,
)

private fun modelWith(vararg els: ScreenElement) =
    ScreenModel("com.demo.app", els.toList())

private fun newSession(
    log: MutableList<String>,
    tree: FakeTree,
    renderer: FakeRenderer,
): GuidanceSession {
    val session = GuidanceSession(
        steps = listOf(
            GuidanceSession.GuideStep(
                targetQuery = "Search",
                instruction = "Tap Search",
            ),
        ),
        tree = tree,
        renderer = renderer,
        anchorProvider = { 540f to 300f },
    )
    session.tapDispatcher = { el, _ ->
        log += "dispatch:${el.id}"
        true
    }
    return session
}

fun main() {
    // 1. ALLOW: preview to the verified target happens BEFORE dispatch.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.Allow(
            com.loom.assistant.intelligence.authgate.AuthToken("tok-1"),
        )
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = newSession(log, tree, renderer)
            check("start shows the step", session.start() is GuidanceSession.StepResult.Shown)
            val result = session.requestTap()
            check("tap dispatched", result == GuidanceSession.TapResult.Dispatched)
            check("preview happened", renderer.previews.size == 1)
            check(
                "preview targets the verified element bounds",
                renderer.previews.firstOrNull() == RectF(100f, 200f, 300f, 260f),
            )
            check(
                "preview precedes dispatch in the event order",
                log.indexOf("preview") in 0 until log.indexOf("dispatch:search-btn"),
            )
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // 2. NEEDS CONFIRMATION: pointer still moves first; nothing dispatches.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.NeedsConfirmation("pending-1")
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            val result = session.requestTap()
            check(
                "confirmation awaited",
                result is GuidanceSession.TapResult.AwaitingConfirmation,
            )
            check("preview happened before the card", renderer.previews.size == 1)
            check("no dispatch while awaiting", log.none { it.startsWith("dispatch:") })
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // 3. DENY: pointer still moves first (intent shown); nothing dispatches.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.Deny("not allowed here")
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            val result = session.requestTap()
            check("denied honestly", result is GuidanceSession.TapResult.Unavailable)
            check("preview happened before the denial", renderer.previews.size == 1)
            check("no dispatch on deny", log.none { it.startsWith("dispatch:") })
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // 4. No live step: no preview, no dispatch.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.Allow(
            com.loom.assistant.intelligence.authgate.AuthToken("tok-1"),
        )
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = newSession(log, tree, renderer)
            val result = session.requestTap()
            check(
                "no step is unavailable",
                result is GuidanceSession.TapResult.Unavailable,
            )
            check("no preview without a step", renderer.previews.isEmpty())
            check("no dispatch without a step", log.none { it.startsWith("dispatch:") })
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // 5. Target gone at tap time: refused before any preview or dispatch.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.Allow(
            com.loom.assistant.intelligence.authgate.AuthToken("tok-1"),
        )
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            tree.model = modelWith() // target disappeared after the step showed
            val result = session.requestTap()
            check(
                "lost target is unavailable",
                result is GuidanceSession.TapResult.Unavailable,
            )
            check("no preview for a lost target", renderer.previews.isEmpty())
            check("no dispatch for a lost target", log.none { it.startsWith("dispatch:") })
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // 6. MASTER F6a: the tap request carries the session origin and
    // the real app label — that is what makes "Always allow"
    // renderable and stored grants applicable on the guidance path.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.Allow(
            com.loom.assistant.intelligence.authgate.AuthToken("tok-1"),
        )
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = GuidanceSession(
                steps = listOf(
                    GuidanceSession.GuideStep(
                        targetQuery = "Search",
                        instruction = "Tap Search",
                    ),
                ),
                tree = tree,
                renderer = renderer,
                origin = RequestOrigin.DIRECT_USER,
                appLabelProvider = { pkg -> if (pkg == "com.demo.app") "Demo App" else null },
                anchorProvider = { 540f to 300f },
            )
            session.tapDispatcher = { el, _ ->
                log += "dispatch:${el.id}"
                true
            }
            check("start shows the step", session.start() is GuidanceSession.StepResult.Shown)
            val result = session.requestTap()
            check("tap dispatched", result == GuidanceSession.TapResult.Dispatched)
            val action = gate.requestedActions.singleOrNull()
            check("one gate request", action != null)
            check("tap key", action?.key == "tap.guidance")
            check("tap scoped to the foreground package", action?.scope == "com.demo.app")
            check("tap carries the real app label", action?.appLabel == "Demo App")
            check(
                "tap carries DIRECT_USER origin",
                gate.requestedOrigins == listOf(RequestOrigin.DIRECT_USER),
            )
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // 7. MASTER F6a: without a label provider the label falls back
    // to the package name (never blank while the package is known),
    // and the default origin is DIRECT_USER.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.Allow(
            com.loom.assistant.intelligence.authgate.AuthToken("tok-1"),
        )
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = newSession(log, tree, renderer)
            check("start shows the step", session.start() is GuidanceSession.StepResult.Shown)
            val result = session.requestTap()
            check("tap dispatched", result == GuidanceSession.TapResult.Dispatched)
            val action = gate.requestedActions.singleOrNull()
            check("tap label falls back to the package", action?.appLabel == "com.demo.app")
            check(
                "default origin is DIRECT_USER",
                gate.requestedOrigins == listOf(RequestOrigin.DIRECT_USER),
            )
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // 8. MASTER F6a: the scroll request carries the same origin + label.
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.Allow(
            com.loom.assistant.intelligence.authgate.AuthToken("tok-1"),
        )
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = FakeRenderer(log)
            val session = GuidanceSession(
                steps = listOf(
                    GuidanceSession.GuideStep(
                        targetQuery = "Search",
                        instruction = "Scroll here",
                    ),
                ),
                tree = tree,
                renderer = renderer,
                origin = RequestOrigin.DIRECT_USER,
                appLabelProvider = { pkg -> if (pkg == "com.demo.app") "Demo App" else null },
                anchorProvider = { 540f to 300f },
            )
            session.scrollDispatcher = { _, _ -> true }
            val result = session.requestScroll(forward = true)
            check("scroll dispatched", result == GuidanceSession.TapResult.Dispatched)
            val action = gate.requestedActions.singleOrNull()
            check("scroll key", action?.key == "device.scroll")
            check("scroll scoped to the foreground package", action?.scope == "com.demo.app")
            check("scroll carries the real app label", action?.appLabel == "Demo App")
            check(
                "scroll carries DIRECT_USER origin",
                gate.requestedOrigins == listOf(RequestOrigin.DIRECT_USER),
            )
        } finally {
            AuthGate.setForTest(null)
        }
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
