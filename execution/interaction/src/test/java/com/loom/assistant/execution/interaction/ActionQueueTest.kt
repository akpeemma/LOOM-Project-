package com.loom.assistant.execution.interaction

/**
 * Stage 2C T3 — ActionQueue tests (plan §6).
 *
 * Covers (C2/C5/E1): halt before every dispatch and every token request
 * (mid-plan halt → zero further dispatches); TOCTOU re-resolution
 * (bounds-change → abort, no dispatch; tree-unavailable → abort); "unknown"
 * callback → FAILED (never success); cancelled → INTERRUPTED (never failed);
 * sequence token consumed exactly once; per-step tokens single-use;
 * out-of-scope parks on NeedsConfirmation and resumes/aborts (D4);
 * sensitive-package steps never reach the dispatcher; financial steps refused
 * pre-token; landing verification (C5); the asTapDispatcher bridge gates.
 *
 * Run (from loom-android, per 6.1 — compile WITHOUT android.jar; the
 * src/test android fakes satisfy the imports). Sketch:
 *
 *   kotlinc -d out -jvm-target 17 [android fakes] [testutil fakes] \
 *     [platform/storage: LoomPreferences, ActivityLog, LoomJson] \
 *     [core/common: LoomEventBus, LoomStateEvent] \
 *     [execution/screenawareness: ScreenAwareness, ScreenModel, ScreenDataPolicy] \
 *     [intelligence/authgate: AuthGate, AuthActionRegistry, AuthConfirmationHost] \
 *     [execution/interaction: InteractionLayer, HaltController, ActionPlan, \
 *       FinancialActionDetector, ActionQueue] \
 *     [interaction/pointer: PointerPolicy] \
 *     QueueTestFakes.kt ActionQueueTest.kt
 *   java -cp out com.loom.assistant.execution.interaction.ActionQueueTestKt
 *
 * (A runnable form of this recipe is /tmp/loom-2c-work/run-tests.sh t3.)
 */

import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate

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

private fun runPlan(rig: QueueRig, plan: ActionPlan): SequenceOutcome? {
    var outcome: SequenceOutcome? = null
    rig.queue.start(plan) { outcome = it }
    return outcome
}

fun main() {
    initGateForTests()

    // ---------- 1. happy path: two steps, full choreography ----------
    run {
        val rig = newRig()
        val target = el("b1", "Calculator")
        rig.tree.modelProvider = { model("com.demo.app", target) }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        val outcome = runPlan(
            rig,
            demoPlan(planStep("s1", "Calculator"), planStep("s2", "Calculator")),
        )
        check("happy path completes", outcome == SequenceOutcome.Completed)
        check("two dispatches", rig.dispatcher.calls.size == 2)
        check("preview per step", rig.renderer.previews == 2)
        check("pulse per completion", rig.renderer.pulses == 2)
        check("showStep per step", rig.renderer.shown.size == 2)
        check(
            "showStep carries the anchor from the provider",
            rig.renderer.shown.all { it.anchorX == 540f && it.anchorY == 300f },
        )
        check(
            "preview targets are the element bounds as RectF",
            rig.renderer.previewTargets.all { it.left == 10f && it.top == 10f },
        )
        // per-step tokens single-use: the sink consumed them; re-consume fails
        val ids = rig.dispatcher.calls.map { it.tokenId }.toSet()
        check("per-step tokens unique", ids.size == 2)
        check(
            "per-step tokens single-use",
            ids.all { !rig.gate.consumeToken(it, AuthActionRegistry.ACTION_TAP_GUIDANCE) },
        )
        // sequence token consumed exactly once
        val seqId = rig.queue.lastSequenceTokenId
        check("sequence token recorded", seqId != null)
        check(
            "sequence token consumed once (re-consume refused)",
            !rig.gate.consumeToken(seqId, AuthActionRegistry.ACTION_DEMO_SEQUENCE),
        )
        // token still key-bound: a demo.sequence token is not valid for tap keys
        check(
            "no stray pending confirmations",
            rig.gate.pendingCount() == 0,
        )
    }

    // ---------- 2. halt before start: nothing dispatches, no card ----------
    run {
        val rig = newRig()
        rig.halt.halted = true
        rig.halt.source = "notification"
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        val outcome = runPlan(rig, demoPlan(planStep("s1", "X")))
        check(
            "halted-before-start → Interrupted(-1)",
            outcome == SequenceOutcome.Interrupted(-1, "notification"),
        )
        check("no confirmation shown", rig.host.shown == 0)
        check("no dispatches", rig.dispatcher.calls.isEmpty())
        check("renderer hidden", rig.renderer.hides == 1)
        check(
            "INTERRUPTED on bus",
            rig.busEvents.any { it is LoomStateEvent.Interrupted && it.stepIndex == -1 },
        )
    }

    // ---------- 3. halt mid-plan: zero further dispatches ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        rig.dispatcher.onDispatch = { rig.halt.halted = true; rig.halt.source = "voice" }
        val outcome = runPlan(
            rig,
            demoPlan(planStep("s1", "X"), planStep("s2", "X"), planStep("s3", "X")),
        )
        check(
            "mid-plan halt → Interrupted(1)",
            outcome == SequenceOutcome.Interrupted(1, "voice"),
        )
        check("exactly one dispatch before halt", rig.dispatcher.calls.size == 1)
        check("no second token request", rig.renderer.previews == 1)
    }

    // ---------- 4. TOCTOU: bounds moved beyond tolerance → abort, no dispatch ----------
    run {
        val rig = newRig()
        val before = el("b1", "Toggle", android.graphics.Rect(10, 10, 110, 60))
        val after = el("b1", "Toggle", android.graphics.Rect(500, 500, 600, 560))
        rig.tree.modelProvider = { model("com.demo.app", before) }
        rig.tree.findOverride = { call, _ -> if (call == 1) before else after }
        val outcome = runPlan(rig, demoPlan(planStep("s1", "Toggle")))
        check(
            "moved target → Failed(target-lost)",
            outcome is SequenceOutcome.Failed && outcome.reason.contains("target-lost"),
        )
        check("no dispatch on stale bounds", rig.dispatcher.calls.isEmpty())
    }

    // ---------- 5. TOCTOU: tree unavailable at dispatch → abort ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "Toggle")) }
        rig.tree.findOverride = { call, q ->
            if (call == 1) rig.tree.modelProvider()?.elements?.firstOrNull() else null
        }
        val outcome = runPlan(rig, demoPlan(planStep("s1", "Toggle")))
        check(
            "vanished target → Failed(target-lost)",
            outcome is SequenceOutcome.Failed && outcome.reason.contains("target-lost"),
        )
        check("no dispatch when tree lost", rig.dispatcher.calls.isEmpty())
    }

    // ---------- 6. unknown callback → FAILED, never success ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        var outcome: SequenceOutcome? = null
        rig.queue.start(demoPlan(planStep("s1", "X"))) { outcome = it }
        check("dispatched, awaiting completion", rig.dispatcher.calls.size == 1 && outcome == null)
        rig.completions.complete(GestureOutcome.UNKNOWN)
        check(
            "unknown → Failed(unknown-result)",
            outcome is SequenceOutcome.Failed && (outcome as SequenceOutcome.Failed).reason == "unknown-result",
        )
    }

    // ---------- 7. cancelled → INTERRUPTED, never failed ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        rig.halt.source = "notification"
        var outcome: SequenceOutcome? = null
        rig.queue.start(demoPlan(planStep("s1", "X"))) { outcome = it }
        rig.completions.complete(GestureOutcome.CANCELLED)
        check(
            "cancelled → Interrupted, not Failed",
            outcome is SequenceOutcome.Interrupted,
        )
        check(
            "INTERRUPTED on bus with step index",
            rig.busEvents.any { it is LoomStateEvent.Interrupted && it.stepIndex == 0 },
        )
    }

    // ---------- 8. dispatch rejected by sink → FAILED ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        rig.dispatcher.reject = "system refused"
        val outcome = runPlan(rig, demoPlan(planStep("s1", "X")))
        check(
            "rejected → Failed(dispatch-rejected)",
            outcome is SequenceOutcome.Failed &&
                (outcome as SequenceOutcome.Failed).reason.contains("dispatch-rejected"),
        )
    }

    // ---------- 9. out-of-scope parks, resumes on approve (D4) ----------
    run {
        val rig = newRig()
        rig.host.parkKeys.add(AuthActionRegistry.ACTION_TAP_GUIDANCE_OUTOFSCOPE)
        val inApp = el("b1", "Open", android.graphics.Rect(10, 10, 110, 60))
        val other = el("b2", "Share", android.graphics.Rect(10, 200, 110, 260))
        var otherActive = false
        rig.tree.modelProvider = {
            if (otherActive) model("com.other.app", other) else model("com.demo.app", inApp)
        }
        // Flip to the other app's model when step 2 starts resolving (step 1
        // used find calls 1-2; its landing check runs before call 3).
        rig.tree.findOverride = { _, q ->
            if (rig.tree.findCalls.size >= 3) otherActive = true
            rig.tree.defaultFind(q)
        }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        var outcome: SequenceOutcome? = null
        rig.queue.start(
            demoPlan(
                planStep("s1", "Open"),
                planStep("s2", "Share", targetPackage = "com.other.app"),
            ),
        ) { outcome = it }
        check("paused on out-of-scope card", outcome == null)
        check("step 1 dispatched", rig.dispatcher.calls.size == 1)
        check("out-of-scope pending parked", rig.host.parked.size == 1)
        // David approves → resumes with the out-of-scope token
        rig.gate.resolve(rig.host.parked.first(), true)
        check("resumed → Completed", outcome == SequenceOutcome.Completed)
        check("step 2 dispatched after approve", rig.dispatcher.calls.size == 2)
        val step2Token = rig.dispatcher.calls[1].tokenId
        check(
            "out-of-scope token single-use",
            !rig.gate.consumeToken(step2Token, AuthActionRegistry.ACTION_TAP_GUIDANCE_OUTOFSCOPE),
        )
    }

    // ---------- 10. out-of-scope deny → aborts ----------
    run {
        val rig = newRig()
        rig.host.denyKeys.add(AuthActionRegistry.ACTION_TAP_GUIDANCE_OUTOFSCOPE)
        val inApp = el("b1", "Open", android.graphics.Rect(10, 10, 110, 60))
        val other = el("b2", "Share", android.graphics.Rect(10, 200, 110, 260))
        var otherActive = false
        rig.tree.modelProvider = {
            if (otherActive) model("com.other.app", other) else model("com.demo.app", inApp)
        }
        rig.tree.findOverride = { _, q ->
            if (rig.tree.findCalls.size >= 3) otherActive = true
            rig.tree.defaultFind(q)
        }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        val outcome = runPlan(
            rig,
            demoPlan(
                planStep("s1", "Open"),
                planStep("s2", "Share", targetPackage = "com.other.app"),
            ),
        )
        check(
            "deny on escalation → AbortedByUser(1)",
            outcome == SequenceOutcome.AbortedByUser(1),
        )
        check("step 2 never dispatched", rig.dispatcher.calls.size == 1)
    }

    // ---------- 11. financial step refused BEFORE any token request ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "Settings"), el("b2", "Pay")) }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        val outcome = runPlan(
            rig,
            demoPlan(
                planStep("s1", "Settings"),
                planStep("s2", "Pay", instruction = "Tap the Pay button"),
            ),
        )
        check("sequence still completes around it", outcome == SequenceOutcome.Completed)
        check("financial step never dispatched", rig.dispatcher.calls.size == 1)
        check("only the sequence card shown", rig.host.shown == 1)
        check(
            "refusal audited as tap.financial_transaction",
            rig.audit.any { it.contains(AuthActionRegistry.ACTION_TAP_FINANCIAL) },
        )
        check(
            "financial step shown guidance-only",
            rig.renderer.shown.any { it.instruction.contains("complete this yourself") },
        )
    }

    // ---------- 12. sensitive package: guidance-only, no token, no dispatch ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.kuda.app", el("b1", "Menu")) }
        val outcome = runPlan(
            rig,
            demoPlan(planStep("s1", "Menu", instruction = "Tap the menu button")),
        )
        check("sequence completes (guidance-only)", outcome == SequenceOutcome.Completed)
        check("zero dispatches in sensitive package", rig.dispatcher.calls.isEmpty())
        check("only the sequence card shown (no per-step card)", rig.host.shown == 1)
        check(
            "audited as automation.sensitive_app",
            rig.audit.any { it.contains(AuthActionRegistry.ACTION_SENSITIVE_APP) },
        )
        check(
            "tooltip says guidance only",
            rig.renderer.shown.any { it.instruction.contains("guidance only in this app") },
        )
    }

    // ---------- 13. landing verification: wrong app foreground → FAILED ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        rig.dispatcher.onDispatch = {
            rig.tree.modelProvider = { model("com.evil.app", el("b9", "Y")) }
        }
        val outcome = runPlan(rig, demoPlan(planStep("s1", "X")))
        check(
            "unexpected foreground app → Failed(landing-unverified)",
            outcome is SequenceOutcome.Failed &&
                (outcome as SequenceOutcome.Failed).reason == "landing-unverified",
        )
    }

    // ---------- 14. asTapDispatcher bridge ----------
    run {
        // (a) sensitive package → refused, token NOT burned
        val rig = newRig()
        rig.tree.modelProvider = { model("com.kuda.app", el("b1", "Transfer")) }
        val token = (rig.gate.check(
            AuthActionRegistry.tapGuidanceSequenced("Tap Transfer", "com.kuda.app"),
        ) as AuthGate.GateResult.Allow).token
        val bridge = rig.queue.asTapDispatcher()
        check("bridge refuses in sensitive package", !bridge(el("b1", "Transfer"), token.id))
        check(
            "refused bridge does not burn the token",
            rig.gate.consumeToken(token.id, AuthActionRegistry.ACTION_TAP_GUIDANCE),
        )
        check(
            "bridge refusal audited as automation.sensitive_app",
            rig.audit.any { it.contains(AuthActionRegistry.ACTION_SENSITIVE_APP) },
        )
    }
    run {
        // (b) financial target label → refused
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "Pay now")) }
        val token = (rig.gate.check(
            AuthActionRegistry.tapGuidanceSequenced("tap", "com.demo.app"),
        ) as AuthGate.GateResult.Allow).token
        check(
            "bridge refuses financial target",
            !rig.queue.asTapDispatcher()(el("b1", "Pay now"), token.id),
        )
        check("bridge made no dispatch", rig.dispatcher.calls.isEmpty())
    }
    run {
        // (c) halted → refused
        val rig = newRig()
        rig.halt.halted = true
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        val token = (rig.gate.check(
            AuthActionRegistry.tapGuidanceSequenced("tap", "com.demo.app"),
        ) as AuthGate.GateResult.Allow).token
        check("bridge refuses when halted", !rig.queue.asTapDispatcher()(el("b1", "X"), token.id))
    }
    run {
        // (d) happy: dispatches, sink consumes the token
        val rig = newRig()
        val target = el("b1", "Open")
        rig.tree.modelProvider = { model("com.demo.app", target) }
        val token = (rig.gate.check(
            AuthActionRegistry.tapGuidanceSequenced("tap", "com.demo.app"),
        ) as AuthGate.GateResult.Allow).token
        check("bridge dispatches", rig.queue.asTapDispatcher()(target, token.id))
        check("sink got the token", rig.dispatcher.calls.size == 1)
    }

    // ---------- 15. paranoid strictness: per-step CONFIRM, still no sensitive path ----------
    run {
        // The gate reads the REAL LoomPreferences singleton for effectiveRisk,
        // so the test sets it (production binds the same value into the queue).
        com.loom.assistant.platform.storage.LoomPreferences.get(testutil.FakeContext())
            .confirmStrictness = 2
        val rig = newRig(strictness = { 2 })
        rig.host.parkKeys.add(AuthActionRegistry.ACTION_TAP_GUIDANCE)
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        var outcome: SequenceOutcome? = null
        rig.queue.start(demoPlan(planStep("s1", "X"))) { outcome = it }
        check("paranoid pauses on per-step card", outcome == null && rig.host.parked.size == 1)
        check("no dispatch before per-step approval", rig.dispatcher.calls.isEmpty())
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        rig.gate.resolve(rig.host.parked.first(), true)
        check("approved → dispatches → Completed", outcome == SequenceOutcome.Completed)
        com.loom.assistant.platform.storage.LoomPreferences.get(testutil.FakeContext())
            .confirmStrictness = 0
    }

    // ---------- 16. sameTarget tolerance ----------
    run {
        val rig = newRig()
        val a = el("b1", "X", android.graphics.Rect(10, 10, 110, 60))
        val near = el("b1", "X", android.graphics.Rect(14, 12, 114, 62))
        val far = el("b1", "X", android.graphics.Rect(200, 200, 300, 260))
        check("near bounds count as same target", rig.queue.sameTarget(a, near))
        check("far bounds do not", !rig.queue.sameTarget(a, far))
    }

    // ---------- 17. MASTER F6b: demo origin rides the gate requests ----------
    run {
        // A user-started demo ("show me how to…") carries
        // DIRECT_USER on the demo.sequence card AND on the
        // out-of-scope per-step card; the default stays MODEL.
        // (Behaviour note: none of the queue's keys is
        // grant-eligible — demo.sequence and tap.guidance.outofscope
        // are excluded by design, in-scope steps are sequenced
        // ALLOW — so the origin changes no card today. What this
        // pins is the provenance: the pending carries the right
        // origin, so a future grant-eligibility change behaves
        // correctly and the audit trail names the true requester.)
        val rig = newRig()
        rig.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        var outcome: SequenceOutcome? = null
        rig.queue.start(
            demoPlan(planStep("s1", "X")),
            onDone = { outcome = it },
            origin = RequestOrigin.DIRECT_USER,
        )
        check("user-started demo completes", outcome == SequenceOutcome.Completed)
        check(
            "user-started demo.sequence card carries DIRECT_USER",
            rig.host.shownOrigins == listOf(RequestOrigin.DIRECT_USER),
        )
        val rig2 = newRig()
        rig2.tree.modelProvider = { model("com.demo.app", el("b1", "X")) }
        rig2.completions.autoComplete = GestureOutcome.COMPLETED
        var outcome2: SequenceOutcome? = null
        rig2.queue.start(demoPlan(planStep("s1", "X"))) { outcome2 = it }
        check("default demo completes", outcome2 == SequenceOutcome.Completed)
        check(
            "default demo.sequence card carries MODEL",
            rig2.host.shownOrigins == listOf(RequestOrigin.MODEL),
        )
    }
    run {
        // Out-of-scope per-step card carries the run's origin too.
        val rig = newRig()
        rig.host.parkKeys.add(AuthActionRegistry.ACTION_TAP_GUIDANCE_OUTOFSCOPE)
        val inApp = el("b1", "Open", android.graphics.Rect(10, 10, 110, 60))
        val other = el("b2", "Share", android.graphics.Rect(10, 200, 110, 260))
        var otherActive = false
        rig.tree.modelProvider = {
            if (otherActive) model("com.other.app", other) else model("com.demo.app", inApp)
        }
        rig.tree.findOverride = { _, q ->
            if (rig.tree.findCalls.size >= 3) otherActive = true
            rig.tree.defaultFind(q)
        }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        var outcome: SequenceOutcome? = null
        rig.queue.start(
            demoPlan(
                planStep("s1", "Open"),
                planStep("s2", "Share", targetPackage = "com.other.app"),
            ),
            onDone = { outcome = it },
            origin = RequestOrigin.DIRECT_USER,
        )
        check("user demo pauses on out-of-scope card", outcome == null)
        check(
            "out-of-scope card carries DIRECT_USER",
            rig.host.shownOrigins == listOf(RequestOrigin.DIRECT_USER, RequestOrigin.DIRECT_USER),
        )
    }

    println("ActionQueueTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
