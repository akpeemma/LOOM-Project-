package com.loom.assistant.interaction.pointer

import android.graphics.Rect
import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Stage 2C T8 — ModeParityTest (criterion F3).
 *
 * Drives GuidanceSession + AnchorResolver with a fake tree and asserts:
 * the same session under Character/Orb/Invisible resolves the same steps,
 * the same target bounds and the same instruction text — only the anchor
 * path differs (per-mode anchors, emitted as animated change events, never
 * teleports).
 *
 * Also covers the B1/B2/B3/B4 verification paths wired into
 * GuidanceSession.showCurrent(): tree unavailable, no match, multi-match
 * refusal, stability re-verification, and sensitive-app honest
 * identification.
 *
 * The FakeRenderer implements GuidanceRendererContract with its EXACT
 * members, so this test also pins the contract Worker B defined.
 *
 * Compile (JVM, no android.jar):
 *   kotlinc ObstructionLayout.kt AnchorResolver.kt PointerPolicy.kt
 *     GuidanceSession.kt ScreenModel.kt ScreenDataPolicy.kt
 *     InteractionLayer.kt HaltController.kt
 *     <fakes...> ModeParityTest.kt -d out
 *   java -cp out com.loom.assistant.interaction.pointer.ModeParityTestKt
 */

// ---- fakes ----

private class FakeTree(
    var bound: Boolean = true,
    var packageName: String = "com.example.settings",
    var elements: List<ScreenElement> = emptyList(),
    /** When true, the target shifts between snapshot 1 and 2 (jitter). */
    var shiftOnSecondSnapshot: Boolean = false,
    /** When true, the target vanishes on the second snapshot. */
    var vanishOnSecondSnapshot: Boolean = false,
) : GuidanceTreeSource {
    var snapshots = 0
    override val isBound: Boolean get() = bound
    override fun snapshot(): ScreenModel? {
        if (!bound) return null
        snapshots++
        var els = elements
        if (snapshots >= 2 && shiftOnSecondSnapshot) {
            els = els.map {
                it.copy(
                    id = it.id,
                    bounds = Rect(
                        it.bounds.left + 50, it.bounds.top,
                        it.bounds.right + 50, it.bounds.bottom,
                    ),
                )
            }
        }
        if (snapshots >= 2 && vanishOnSecondSnapshot) els = emptyList()
        return ScreenModel(packageName, els, capturedAt = 1000L * snapshots)
    }
}

/** Records GuidanceRendererContract calls with the contract's EXACT signatures. */
private class FakeRenderer : GuidanceRendererContract {
    data class Shown(
        val anchorX: Float,
        val anchorY: Float,
        val target: RectF,
        val stepIndex: Int,
        val stepCount: Int,
        val instruction: String,
    )

    val shown = mutableListOf<Shown>()
    var hides = 0
    val previews = mutableListOf<RectF>()
    var pulses = 0
    val anchorChanges = mutableListOf<Pair<Float, Float>>()

    override fun showStep(
        anchorX: Float,
        anchorY: Float,
        target: RectF,
        stepIndex: Int,
        stepCount: Int,
        instruction: String,
    ) {
        shown.add(Shown(anchorX, anchorY, RectF(target), stepIndex, stepCount, instruction))
    }

    override fun hide() {
        hides++
    }

    override fun previewMoveTo(target: RectF) {
        previews.add(RectF(target))
    }

    override fun pulseOnResult() {
        pulses++
    }

    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
        anchorChanges.add(newAnchorX to newAnchorY)
    }

    // "fix everything": records nudges; true only while a step is shown,
    // mirroring the real renderer's idle-false contract.
    val nudges = mutableListOf<PointerDirection>()
    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean {
        if (shown.isEmpty()) return false
        nudges.add(direction)
        return true
    }

    // Device-evidence fix (2026-11-05, pointer H3): records idle
    // indicator renders; true when the fake can draw (always — it has no
    // window to be detached from).
    var idleShown = 0
    override fun showIdleIndicator(): Boolean {
        idleShown++
        return true
    }
}

private class FakePositions(
    var character: GeomPoint? = GeomPoint(540f, 300f),
    var orb: GeomPoint? = GeomPoint(540f, 1600f),
    var size: GeomSize = GeomSize(1080f, 2400f),
) : AnchorPositions {
    override fun characterCenter(): GeomPoint? = character
    override fun orbCenter(): GeomPoint? = orb
    override fun windowSize(): GeomSize = size
}

private fun wifiElement() = ScreenElement(
    id = "switch@wifi",
    text = "Wi-Fi",
    contentDescription = null,
    className = "android.widget.Switch",
    bounds = Rect(100, 200, 300, 260),
    role = ElementRole.TOGGLE,
    enabled = true,
    isPassword = false,
)

private fun bluetoothElement() = ScreenElement(
    id = "switch@bt",
    text = "Bluetooth",
    contentDescription = null,
    className = "android.widget.Switch",
    bounds = Rect(100, 300, 300, 360),
    role = ElementRole.TOGGLE,
    enabled = true,
    isPassword = false,
)

// ---- runner ----

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

fun main() {
    // F3: one scripted session under each of the three modes.
    run {
        val modes = listOf(
            GuidanceMode.CHARACTER to GeomPoint(540f, 300f),
            GuidanceMode.ORB to GeomPoint(540f, 1600f),
            GuidanceMode.INVISIBLE to GeomPoint(4f, 1200f),
        )
        val anchors = mutableListOf<GeomPoint>()
        for ((mode, expectedAnchor) in modes) {
            val tree = FakeTree(elements = listOf(wifiElement(), bluetoothElement()))
            val renderer = FakeRenderer()
            val resolver = AnchorResolver(FakePositions())
            var currentMode = mode
            val session = GuidanceSession(
                steps = listOf(
                    GuidanceSession.GuideStep("wi-fi", "Tap the Wi-Fi toggle"),
                    GuidanceSession.GuideStep("bluetooth", "Tap the Bluetooth toggle"),
                ),
                anchorProvider = {
                    val a = resolver.anchorFor(currentMode)
                    a.x to a.y
                },
                tree = tree,
                renderer = renderer,
            )
            resolver.resolveInitial(mode)

            val r1 = session.start()
            check(
                "$mode step 1 shown",
                r1 is GuidanceSession.StepResult.Shown &&
                    r1.stepIndex == 1 && r1.stepCount == 2,
            )
            val r2 = session.next()
            check(
                "$mode step 2 shown",
                r2 is GuidanceSession.StepResult.Shown &&
                    r2.stepIndex == 2 && r2.stepCount == 2,
            )
            val r3 = session.next()
            check("$mode finishes after last step", r3 is GuidanceSession.StepResult.Finished)

            check("$mode drove the renderer twice", renderer.shown.size == 2)
            val s1 = renderer.shown[0]
            val s2 = renderer.shown[1]
            // Same bounds under every mode.
            check(
                "$mode step 1 bounds",
                s1.target == RectF(100f, 200f, 300f, 260f),
            )
            check(
                "$mode step 2 bounds",
                s2.target == RectF(100f, 300f, 300f, 360f),
            )
            // Same instructions under every mode.
            check("$mode step 1 instruction", s1.instruction == "Tap the Wi-Fi toggle")
            check("$mode step 2 instruction", s2.instruction == "Tap the Bluetooth toggle")
            check(
                "$mode step indices",
                s1.stepIndex == 1 && s1.stepCount == 2 && s2.stepIndex == 2,
            )
            // Only the anchor path differs.
            check(
                "$mode anchor is the mode anchor",
                GeomPoint(s1.anchorX, s1.anchorY) == expectedAnchor &&
                    GeomPoint(s2.anchorX, s2.anchorY) == expectedAnchor,
            )
            anchors.add(GeomPoint(s1.anchorX, s1.anchorY))
            // Two snapshots per step: initial verify + stability re-verify.
            check("$mode re-verified bounds (2 snapshots/step)", tree.snapshots == 4)
        }
        check(
            "anchors differ across modes",
            anchors.toSet().size == 3,
        )
    }

    // F1/F3: mid-session mode switch emits an animated anchor change, and
    // the session continues with the new anchor (no restart).
    run {
        val tree = FakeTree(elements = listOf(wifiElement()))
        val renderer = FakeRenderer()
        val resolver = AnchorResolver(FakePositions())
        val events = mutableListOf<AnchorEvent>()
        resolver.listener = { events.add(it) }
        var currentMode = GuidanceMode.CHARACTER
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("wi-fi", "Tap the Wi-Fi toggle")),
            anchorProvider = {
                val a = resolver.anchorFor(currentMode)
                a.x to a.y
            },
            tree = tree,
            renderer = renderer,
        )
        resolver.resolveInitial(GuidanceMode.CHARACTER)
        session.start()
        // Switch mid-session: the resolver emits the animated event; the
        // host forwards it to renderer.onAnchorChanged (recorded here).
        val ev = resolver.onModeChanged(GuidanceMode.INVISIBLE)
        check("mode switch emits an anchor event, not a teleport", ev is AnchorEvent.Moved)
        val moved = ev as AnchorEvent.Moved
        check(
            "event duration is the 300ms transition",
            moved.durationMs == AnchorResolver.TRANSITION_MS,
        )
        renderer.onAnchorChanged(moved.to.x, moved.to.y)
        check(
            "renderer received the animated anchor change",
            renderer.anchorChanges == listOf(4f to 1200f),
        )
        // Session continues: back() re-shows step 1 with the NEW anchor.
        currentMode = GuidanceMode.INVISIBLE
        val r = session.back()
        check("session continues after mode switch", r is GuidanceSession.StepResult.Shown)
        val last = renderer.shown.last()
        check(
            "re-shown step uses the new anchor, same target",
            GeomPoint(last.anchorX, last.anchorY) == GeomPoint(4f, 1200f) &&
                last.target == RectF(100f, 200f, 300f, 260f),
        )
        check("anchor change was an event (listener saw it)", events.size == 1)
    }

    // B1: tree unavailable -> honest Unavailable, never a fake step.
    run {
        val tree = FakeTree(bound = false)
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("wi-fi", "Tap it")),
            anchorProvider = { 0f to 0f },
            tree = tree,
            renderer = renderer,
        )
        val r = session.start()
        check(
            "unbound tree reports Unavailable",
            r is GuidanceSession.StepResult.Unavailable &&
                (r as GuidanceSession.StepResult.Unavailable).reason.contains("not enabled"),
        )
        check("no renderer call on unavailable tree", renderer.shown.isEmpty())
    }

    // B1: no match -> honest Unavailable.
    run {
        val tree = FakeTree(elements = listOf(wifiElement()))
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("purple elephant", "Tap it")),
            anchorProvider = { 0f to 0f },
            tree = tree,
            renderer = renderer,
        )
        val r = session.start()
        check(
            "no match reports Unavailable with the query",
            r is GuidanceSession.StepResult.Unavailable &&
                (r as GuidanceSession.StepResult.Unavailable).reason.contains("purple elephant"),
        )
        check("no renderer call on no match", renderer.shown.isEmpty())
    }

    // B3: multi-match -> refuse to guess.
    run {
        val tree = FakeTree(elements = listOf(wifiElement(), bluetoothElement()))
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("switch", "Tap it")),
            anchorProvider = { 0f to 0f },
            tree = tree,
            renderer = renderer,
        )
        val r = session.start()
        check(
            "multi-match refuses with 'N matches, be more specific'",
            r is GuidanceSession.StepResult.Unavailable &&
                (r as GuidanceSession.StepResult.Unavailable).reason == "2 matches for \"switch\" — be more specific.",
        )
        check("no renderer call on multi-match", renderer.shown.isEmpty())
    }

    // B2: target moves between the two snapshots -> stability refusal.
    run {
        val tree = FakeTree(elements = listOf(wifiElement()), shiftOnSecondSnapshot = true)
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("wi-fi", "Tap it")),
            anchorProvider = { 0f to 0f },
            tree = tree,
            renderer = renderer,
        )
        val r = session.start()
        check(
            "moving target is refused honestly",
            r is GuidanceSession.StepResult.Unavailable &&
                (r as GuidanceSession.StepResult.Unavailable).reason.contains("moving"),
        )
        check("no renderer call on jitter", renderer.shown.isEmpty())
    }

    // B2: target vanishes on re-verify -> honest refusal.
    run {
        val tree = FakeTree(elements = listOf(wifiElement()), vanishOnSecondSnapshot = true)
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("wi-fi", "Tap it")),
            anchorProvider = { 0f to 0f },
            tree = tree,
            renderer = renderer,
        )
        val r = session.start()
        check(
            "vanished target is refused honestly",
            r is GuidanceSession.StepResult.Unavailable &&
                (r as GuidanceSession.StepResult.Unavailable).reason.contains("disappeared"),
        )
    }

    // B4: sensitive package -> bounds verified, label honestly withheld.
    run {
        val bankButton = ScreenElement(
            id = "button@transfer",
            text = null, // redacted: ScreenDataPolicy.redact strips text
            contentDescription = null,
            className = "android.widget.Button",
            bounds = Rect(100, 500, 400, 580),
            role = ElementRole.BUTTON,
            enabled = true,
            isPassword = false,
        )
        val tree = FakeTree(
            packageName = "com.example.bankapp",
            elements = listOf(bankButton),
        )
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("button", "Tap the Transfer button")),
            anchorProvider = { 0f to 0f },
            tree = tree,
            renderer = renderer,
        )
        val r = session.start()
        check("sensitive app still shows the step", r is GuidanceSession.StepResult.Shown)
        val shown = renderer.shown.single()
        check(
            "sensitive app keeps verified bounds",
            shown.target == RectF(100f, 500f, 400f, 580f),
        )
        check(
            "sensitive app withholds the label honestly",
            shown.instruction == "I can see a button here, but the app hides its label.",
        )
    }

    // A4: start(query) builds a single-target session; blank query refuses.
    run {
        val tree = FakeTree(elements = listOf(wifiElement()))
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = emptyList(),
            anchorProvider = { 7f to 9f },
            tree = tree,
            renderer = renderer,
        )
        val r = session.start("wi-fi")
        check("start(query) shows the resolved target", r is GuidanceSession.StepResult.Shown)
        val shown = renderer.shown.single()
        check("start(query) bounds", shown.target == RectF(100f, 200f, 300f, 260f))
        check("start(query) anchor", GeomPoint(shown.anchorX, shown.anchorY) == GeomPoint(7f, 9f))
        val blank = GuidanceSession(
            steps = emptyList(),
            anchorProvider = { 0f to 0f },
            tree = tree,
            renderer = FakeRenderer(),
        ).start("   ")
        check(
            "blank query is refused honestly",
            blank is GuidanceSession.StepResult.Unavailable,
        )
    }

    // Host path (no injected renderer): falls back to the holder + listener.
    run {
        val tree = FakeTree(elements = listOf(wifiElement()))
        PointerGuideViewHolder.view = PointerGuideView()
        try {
            var observed: PointerGuideView.Step? = null
            val session = GuidanceSession(
                steps = listOf(GuidanceSession.GuideStep("wi-fi", "Tap it")),
                anchorProvider = { 1f to 2f },
                tree = tree,
            )
            session.listener = object : GuidanceSession.Listener {
                override fun onStepShown(view: PointerGuideView, step: PointerGuideView.Step) {
                    observed = step
                }

                override fun onSessionEvent(result: GuidanceSession.StepResult) {}
            }
            val r = session.start()
            check("host path shows the step", r is GuidanceSession.StepResult.Shown)
            check(
                "host path delivers view+step to the listener",
                observed != null && observed!!.instruction == "Tap it" &&
                    observed!!.target == RectF(100f, 200f, 300f, 260f),
            )
        } finally {
            PointerGuideViewHolder.view = null
        }
    }

    // Device-evidence fix (2026-11-05, pointer H6): the session
    // re-resolves its renderer on EVERY step via rendererProvider — a
    // host rebind between steps must move the draw to the new renderer,
    // never keep drawing into the stale one.
    run {
        val tree = FakeTree(elements = listOf(wifiElement(), bluetoothElement()))
        val rendererA = FakeRenderer()
        val rendererB = FakeRenderer()
        var current: FakeRenderer = rendererA
        val session = GuidanceSession(
            steps = listOf(
                GuidanceSession.GuideStep("wi-fi", "Tap the Wi-Fi toggle"),
                GuidanceSession.GuideStep("bluetooth", "Tap the Bluetooth toggle"),
            ),
            anchorProvider = { 1f to 2f },
            tree = tree,
            renderer = rendererA,
            rendererProvider = { current },
        )
        val r1 = session.start()
        check("provider step 1 shown", r1 is GuidanceSession.StepResult.Shown)
        check("provider step 1 drew on renderer A", rendererA.shown.size == 1)
        check("provider step 1 drew nothing on B", rendererB.shown.isEmpty())
        current = rendererB // host rebound between steps
        val r2 = session.next()
        check("provider step 2 shown", r2 is GuidanceSession.StepResult.Shown)
        check("provider step 2 drew on renderer B", rendererB.shown.size == 1)
        check(
            "provider step 2 drew on B with the right target",
            rendererB.shown.single().target == RectF(100f, 300f, 300f, 360f),
        )
        check("provider step 2 drew nothing more on A", rendererA.shown.size == 1)
    }

    // Device-evidence fix (2026-11-05, pointer H6): a null provider
    // result falls back to the injected renderer — no crash, no lost step.
    run {
        val tree = FakeTree(elements = listOf(wifiElement()))
        val renderer = FakeRenderer()
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep("wi-fi", "Tap it")),
            anchorProvider = { 1f to 2f },
            tree = tree,
            renderer = renderer,
            rendererProvider = { null },
        )
        val r = session.start()
        check("null provider falls back to injected renderer", r is GuidanceSession.StepResult.Shown)
        check("fallback drew on the injected renderer", renderer.shown.size == 1)
    }

    // Device-evidence fix (2026-11-05, pointer H3): InteractionLayer
    // .showPointerIdle renders the idle indicator when no step is live,
    // and reports honestly when there is no renderer at all.
    run {
        val layer = com.loom.assistant.execution.interaction.InteractionLayer()
        val renderer = FakeRenderer()
        layer.renderer = renderer
        check("showPointerIdle draws with a renderer", layer.showPointerIdle())
        check("showPointerIdle reached the renderer", renderer.idleShown == 1)
        val bare = com.loom.assistant.execution.interaction.InteractionLayer()
        check("showPointerIdle is false with no renderer", !bare.showPointerIdle())
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
