package com.loom.assistant.execution.interaction

import android.graphics.RectF
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.interaction.pointer.PointerPolicy

/**
 * Stage 2C T5 — halt primitive checks (HaltController + InteractionLayer).
 *
 * Run: kotlinc <prod sources> <fakes> HaltControllerTest.kt -d out && java
 * -cp out com.loom.assistant.execution.interaction.HaltControllerTestKt
 *
 * Covers §5.1: halt sets the flag; pending confirmations are cancelled;
 * renderer.hide() is called; INTERRUPTED carries step index + source; the
 * report is never "completed". Verdict: exit 0 + all checks passed.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name $detail")
    }
}

/** Fake renderer: records GuidanceRendererContract calls. */
private class FakeRenderer : GuidanceRendererContract {
    var hideCalls = 0
    var showStepCalls = 0
    var previewCalls = 0
    var pulseCalls = 0
    var anchorChanges = 0

    override fun showStep(
        anchorX: Float,
        anchorY: Float,
        target: RectF,
        stepIndex: Int,
        stepCount: Int,
        instruction: String,
    ) {
        showStepCalls++
    }

    override fun hide() {
        hideCalls++
    }

    override fun previewMoveTo(target: RectF) {
        previewCalls++
    }

    override fun pulseOnResult() {
        pulseCalls++
    }

    // "fix everything": contract member; halt tests never nudge.
    var nudges = 0
    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean =
        true.also { nudges++ }

    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
        anchorChanges++
    }
}

private class FakeHooks : HaltHooks {
    var cancelGestureCalls = 0
    var hideRendererCalls = 0
    var endSessionCalls = 0
    var cancelConfirmationsCalls = 0

    override fun cancelInFlightGesture() {
        cancelGestureCalls++
    }

    override fun hideRenderer() {
        hideRendererCalls++
    }

    override fun endSession() {
        endSessionCalls++
    }

    override fun cancelPendingConfirmations() {
        cancelConfirmationsCalls++
    }
}

private class FakeEventSink : EventSink {
    val events = mutableListOf<LoomStateEvent>()
    override fun post(event: LoomStateEvent) {
        events.add(event)
    }
}

private class FakeAudit : AuditLogger {
    val entries = mutableListOf<Pair<String, String>>()
    override fun audit(kind: String, detail: String) {
        entries.add(kind to detail)
    }
}

fun main() {
    // ---- HaltController: flag lifecycle ----
    run {
        val c = HaltController(FakeEventSink(), FakeAudit())
        check("flag starts unset", !c.haltRequested)
        check("dispatch allowed before halt", c.allowDispatch())
        c.requestHalt()
        check("requestHalt sets the flag", c.haltRequested)
        check("dispatch blocked after halt", !c.allowDispatch())
        c.reset()
        check("reset clears the flag", !c.haltRequested)
        check("dispatch allowed after reset", c.allowDispatch())
    }

    // ---- HaltController: the exact §5.1 sequence ----
    run {
        val events = FakeEventSink()
        val audit = FakeAudit()
        val hooks = FakeHooks()
        val c = HaltController(events, audit)
        val report = c.halt(HaltSource.VOICE, 2, 5, hooks)
        check("halt sets the flag", c.haltRequested)
        check("in-flight gesture cancelled", hooks.cancelGestureCalls == 1)
        check("renderer hidden", hooks.hideRendererCalls == 1)
        check("session ended / queue drained", hooks.endSessionCalls == 1)
        check("pending confirmations cancelled", hooks.cancelConfirmationsCalls == 1)
        check("report is INTERRUPTED", report.interrupted)
        check("report carries step index", report.stepIndex == 2)
        check("report carries total steps", report.totalSteps == 5)
        check("report carries source", report.source == HaltSource.VOICE)
        val interrupted = events.events.filterIsInstance<LoomStateEvent.Interrupted>()
        check("INTERRUPTED event posted", interrupted.size == 1)
        check(
            "INTERRUPTED carries step index + source",
            interrupted.firstOrNull()?.let {
                it.stepIndex == 2 && it.totalSteps == 5 && it.source == "VOICE"
            } == true,
        )
        check(
            "never reported completed",
            events.events.none { it is LoomStateEvent.Completed },
        )
        check(
            "audit log records INTERRUPTED",
            audit.entries.any { (kind, detail) ->
                kind == "INTERRUPTED" && "2" in detail && "VOICE" in detail
            },
        )
    }

    // ---- InteractionLayer: halt delegates with renderer + confirmations ----
    run {
        val events = FakeEventSink()
        val audit = FakeAudit()
        val layer = InteractionLayer(events, audit)
        val renderer = FakeRenderer()
        layer.renderer = renderer
        val cancelledGestures = mutableListOf<Int>()
        layer.gestureCanceller = GestureCanceller { cancelledGestures.add(1) }
        val cancelledIds = mutableListOf<String>()
        layer.confirmationCanceller = { cancelledIds.add(it) }
        var ended = 0
        layer.onSequenceEnded = { ended++ }

        layer.startGuidance("the wifi toggle")
        check("sequence active after startGuidance", layer.isSequenceActive)
        check("active query recorded", layer.activeQuery == "the wifi toggle")
        layer.trackPendingConfirmation("pending-1")
        layer.trackPendingConfirmation("pending-2")

        val report = layer.halt(HaltSource.NOTIFICATION)
        check("layer halt sets halt flag", layer.isHaltRequested())
        check("renderer.hide() called once", renderer.hideCalls == 1)
        check("in-flight gesture cancelled", cancelledGestures.size == 1)
        check(
            "each pending confirmation cancelled",
            cancelledIds == listOf("pending-1", "pending-2"),
        )
        check("sequence ended hook fired", ended == 1)
        check("sequence no longer active", !layer.isSequenceActive)
        check("layer report INTERRUPTED", report.interrupted)
        check("layer report source", report.source == HaltSource.NOTIFICATION)
        check(
            "layer INTERRUPTED event posted",
            events.events.filterIsInstance<LoomStateEvent.Interrupted>().size == 1,
        )
        check(
            "layer never reports completed",
            events.events.none { it is LoomStateEvent.Completed },
        )
        check("layer audit logs INTERRUPTED", audit.entries.any { it.first == "INTERRUPTED" })
    }

    // ---- InteractionLayer: halt with no active sequence ----
    run {
        val events = FakeEventSink()
        val layer = InteractionLayer(events, FakeAudit())
        val report = layer.halt(HaltSource.STOP_WINDOW)
        check("halt with no sequence still INTERRUPTED", report.interrupted)
        check("step index defaults to 0", report.stepIndex == 0)
        check("STOP_WINDOW source carried", report.source == HaltSource.STOP_WINDOW)
    }

    // ---- InteractionLayer: new sequence after halt ----
    run {
        val layer = InteractionLayer(FakeEventSink(), FakeAudit())
        layer.startGuidance("x")
        layer.halt(HaltSource.VOICE)
        check("halted", !layer.isSequenceActive)
        layer.startDemonstration("open the calculator")
        check("new sequence clears the halt flag", layer.isSequenceActive)
        check("demo query recorded", layer.activeQuery == "open the calculator")
    }

    // ---- InteractionLayer: blank queries never start a fake sequence ----
    run {
        val layer = InteractionLayer(FakeEventSink(), FakeAudit())
        layer.startGuidance("   ")
        check("blank guidance query ignored", !layer.isSequenceActive)
        layer.startDemonstration("")
        check("blank demo goal ignored", !layer.isSequenceActive)
    }

    // ---- InteractionLayer: policy + mode state ----
    run {
        val layer = InteractionLayer(FakeEventSink(), FakeAudit())
        check("default policy AUTO", layer.pointerPolicy == PointerPolicy.AUTO)
        layer.setPointerPolicy(PointerPolicy.SHOW)
        check("policy stored", layer.pointerPolicy == PointerPolicy.SHOW)
        layer.onModeChanged("orb")
        check("mode recorded", layer.currentMode == "orb")
    }

    // ---- all four halt sources are distinct ----
    run {
        val sources = HaltSource.values()
        check("four halt sources", sources.size == 4)
        check(
            "sources cover all paths",
            sources.toSet() == setOf(
                HaltSource.NOTIFICATION,
                HaltSource.VOICE,
                HaltSource.STOP_WINDOW,
                HaltSource.TOGGLE_REVOKED,
            ),
        )
    }

    println("HaltControllerTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
