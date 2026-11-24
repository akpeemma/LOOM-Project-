package com.loom.assistant.execution.interaction

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.interaction.pointer.ExecutionFeedback
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.interaction.pointer.GuidanceSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 2C (C2/D2, §3, §4) — the single gated demonstration-step runner.
 *
 * An [ActionQueue] runs one [ActionPlan] through the AuthGate:
 *
 *  1. Financial/security pre-scan over EVERY step BEFORE any token request
 *     (§4.3) — matches are refused outright, mapped to the DENY-class
 *     `tap.financial_transaction` key for audit, guidance-only offered.
 *  2. Per-sequence `demo.sequence` CONFIRM card; the sequence token is
 *     consumed EXACTLY ONCE at sequence start (single-use, 90s).
 *  3. Per step, in order:
 *     - halt-flag check (before every token request AND every dispatch);
 *     - sensitive-package pre-dispatch gate via
 *       [ScreenDataPolicy.isSensitivePackage] — guidance-only, no token, no
 *       card, no dispatch (§4.1.2, the hard rule);
 *     - TOCTOU re-resolution: `findElement()` immediately before dispatch;
 *       gone / moved beyond tolerance / tree unavailable → FAILED(target-lost),
 *       never taps stale bounds;
 *     - per-step token via the gate (sequence-covered ALLOW in-app,
 *       `tap.guidance.outofscope` CONFIRM card when out-of-scope — the queue
 *       pauses on NeedsConfirmation, resumes on approve, aborts on
 *       deny/timeout; §3.2 path 4);
 *     - choreography: `renderer.previewMoveTo` (~300ms intent preview) →
 *       dispatch → `renderer.pulseOnResult()` on async completion;
 *     - C5 landing verification: re-snapshot, tree readable + expected app
 *       still foreground.
 *  4. Async completion: COMPLETED → next; CANCELLED → INTERRUPTED (never
 *     "failed"); UNKNOWN (callback timeout) → FAILED, never success.
 *
 * Structural properties (constraint F):
 *  - The conversational model has no path here — plans are built only by
 *    deterministic voice commands, and every dispatch needs a gate-issued
 *    token. No token, no dispatch, end to end.
 *  - The tap sink (`LoomAccessibilityService.requestTap`) consumes the
 *    per-step token itself (single-use); the queue never dispatches without
 *    handing the sink a live token id.
 *
 * Testability: every Android-bound collaborator is an injected seam, so T3
 * drives the full state machine on the JVM with fakes.
 */

// ---------- injected seams ----------

/** Live-tree reads. Production: ScreenAwarenessHolder.current. */
interface TreeSource {
    fun snapshot(): ScreenModel?
    fun findElement(query: String): ScreenElement?
}

/** Gesture sink. Production: LoomAccessibilityService.requestTap. */
fun interface GestureDispatch {
    fun dispatch(element: ScreenElement, tokenId: String): DispatchAttempt
}

/**
 * POINTER MOUSE-VERBS (2026-11-05) — long-press sink. Production:
 * LoomAccessibilityService.requestLongPress (same `tap.guidance` key
 * as a tap — a long-press IS a tap held down). Null in hosts that
 * never wire it; a LONG_PRESS step then fails honestly instead of
 * dispatching as a tap.
 */
fun interface LongPressDispatch {
    fun dispatchLongPress(element: ScreenElement, tokenId: String, holdMsMs: Long): DispatchAttempt
}

sealed interface DispatchAttempt {
    /** The system accepted the gesture; completion arrives asynchronously. */
    data object Accepted : DispatchAttempt
    /** The system refused the dispatch call (or it threw). */
    data class Rejected(val reason: String) : DispatchAttempt
}

/** Async gesture result. UNKNOWN = the completion callback never arrived. */
enum class GestureOutcome { COMPLETED, CANCELLED, UNKNOWN }

/**
 * One-shot async completion waiter for the gesture just dispatched.
 * Production: subscribes to LoomStateEvent on the bus with a timeout
 * ([busCompletionSource]); tests: controllable fake.
 */
fun interface CompletionSource {
    fun awaitCompletion(cb: (GestureOutcome) -> Unit)
}

/**
 * Guidance choreography surface: the CANONICAL [GuidanceRendererContract]
 * defined in InteractionLayer.kt (Worker B). The queue drives it — never
 * draws itself, never redefines it.
 *
 * Anchor coordinates come from [AnchorProvider] (production: Worker B's
 * AnchorResolver via the host); the queue never invents the anchor.
 */
fun interface AnchorProvider {
    fun currentAnchor(): Pair<Float, Float>
}

/**
 * Sequences the ~300ms intent preview before dispatch. The canonical
 * renderer contract's previewMoveTo carries no completion callback, so the
 * queue owns the timing: production waits ~300ms on the main thread, tests
 * run it immediately ([PreviewDelayer.IMMEDIATE]).
 */
fun interface PreviewDelayer {
    fun afterPreview(action: () -> Unit)

    companion object {
        val IMMEDIATE: PreviewDelayer = PreviewDelayer { action -> action() }

        fun delayed(delayMs: Long = 300L): PreviewDelayer = PreviewDelayer { action ->
            try {
                Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs)
            } catch (_: Exception) {
                action()
            }
        }
    }
}

/** Halt flag owned by Worker B's InteractionLayer/HaltController. */
interface HaltProvider {
    fun isHalted(): Boolean
    fun haltSource(): String?
}

/**
 * Anchor-transition gate (F4): no tap fires while the beam anchor is
 * mid-flight (~300ms). Production waits; tests use [IDLE].
 */
fun interface TransitionGate {
    fun awaitSettled(onSettled: () -> Unit)

    companion object {
        val IDLE: TransitionGate = TransitionGate { onSettled -> onSettled() }
    }
}

// ---------- outcomes ----------

sealed class SequenceOutcome {
    data object Completed : SequenceOutcome()
    data class Interrupted(val stepIndex: Int, val source: String) : SequenceOutcome()
    data class AbortedByUser(val stepIndex: Int) : SequenceOutcome()
    data class Failed(val stepIndex: Int, val reason: String) : SequenceOutcome()
    data object NeverStarted : SequenceOutcome()
}

/** The live sequence cover recorded when the demo.sequence token is consumed. */
data class SequenceCover(
    val planId: String,
    val appPackage: String,
    val stepHash: Int,
)

/**
 * Production [CompletionSource]: one-shot LoomEventBus subscription with a
 * timeout. COMPLETED on [LoomStateEvent.Completed], CANCELLED on an
 * Error mentioning cancellation (the §5.1 in-flight-cancel path), UNKNOWN on
 * timeout — UNKNOWN is FAILED, never success (risk §10.1).
 */
fun busCompletionSource(timeoutMs: Long = 10_000L): CompletionSource =
    CompletionSource { cb ->
        val done = AtomicBoolean(false)
        lateinit var listener: (LoomStateEvent) -> Unit
        val finishOnce: (GestureOutcome) -> Unit = { outcome ->
            if (done.compareAndSet(false, true)) {
                try {
                    LoomEventBus.unsubscribe(listener)
                } catch (_: Exception) {
                }
                cb(outcome)
            }
        }
        listener = { event ->
            when (event) {
                is LoomStateEvent.Completed -> finishOnce(GestureOutcome.COMPLETED)
                is LoomStateEvent.Error ->
                    if (event.message.contains("cancell", ignoreCase = true)) {
                        finishOnce(GestureOutcome.CANCELLED)
                    }
                else -> Unit
            }
        }
        LoomEventBus.subscribe(listener)
        try {
            Handler(Looper.getMainLooper()).postDelayed(
                { finishOnce(GestureOutcome.UNKNOWN) },
                timeoutMs,
            )
        } catch (_: Exception) {
            finishOnce(GestureOutcome.UNKNOWN)
        }
    }

/**
 * The queue. Constructed by the host (Worker B's InteractionLayer); all
 * collaborators injected.
 *
 * @param strictness mirrors LoomPreferences.confirmStrictness (0/1/2).
 *   The gate's effectiveRisk() already escalates per strictness; the queue
 *   additionally labels the sequence card honestly in paranoid mode.
 * @param audit audit-log sink (Worker B's [AuditLogger]; production writes
 *   to ActivityLog with kind "auth").
 * @param bus event-bus sink (Worker B's [EventSink]; production posts to
 *   LoomEventBus).
 * @param anchors the beam anchor source (production: the host binds Worker
 *   B's AnchorResolver). Required — the queue never invents the anchor.
 * @param previewDelayer owns the ~300ms intent-preview timing before
 *   dispatch (the canonical contract has no preview callback).
 */
class ActionQueue(
    private val gate: AuthGate,
    private val trees: TreeSource,
    private val dispatcher: GestureDispatch,
    private val completions: CompletionSource,
    private val renderer: GuidanceRendererContract,
    private val halt: HaltProvider,
    private val anchors: AnchorProvider,
    private val transitions: TransitionGate = TransitionGate.IDLE,
    private val previewDelayer: PreviewDelayer = PreviewDelayer.delayed(),
    private val strictness: () -> Int = { 0 },
    private val audit: AuditLogger = AuditLogger { _, _ -> },
    private val bus: EventSink = EventSink {},
    /**
     * Master §6 — "Show me what you're doing" sink. Null in tests and in
     * hosts that never show execution feedback; when set, every step
     * reports its outcome here (never a claim without a dispatch).
     */
    private val feedback: ExecutionFeedback? = null,
    /**
     * POINTER MOUSE-VERBS (2026-11-05) — long-press sink for LONG_PRESS
     * steps. Production: LoomAccessibilityService.requestLongPress via
     * the host. Null in tests and hosts that never wire it; a LONG_PRESS
     * step then fails honestly instead of dispatching as a tap.
     */
    private val longPressDispatcher: LongPressDispatch? = null,
) {

    companion object {
        /** Re-resolution tolerance: bounds centre drift beyond this aborts. */
        const val TOCTOU_TOLERANCE_PX = 24
    }

    /** Observability for tests: the sequence token id consumed at start. */
    @Volatile
    var lastSequenceTokenId: String? = null
        private set

    /** Pending out-of-scope confirmations this queue parked (halt cancels them). */
    private val pendingConfirmations = mutableSetOf<String>()

    // ---------- genuine demo pause / resume (closure decision 3) ----------
    //
    // Distinct from halt: pause FREEZES the queue mid-sequence (zero
    // dispatches while paused, halt flag NOT set, pending confirmations
    // preserved), keeping the step index and the last resolved targets.
    // Resume performs TOCTOU re-resolution of the current step's target
    // against the live tree plus screen-context revalidation BEFORE any
    // dispatch. Halt-and-restart is NOT pause/resume ("stop" stays
    // halt-with-INTERRUPTED).
    //
    // The queue is continuation-driven (gate callbacks, preview delays,
    // completion callbacks), so every continuation point checks [demoPaused]
    // and stashes instead of proceeding; resume() re-enters runSteps().

    /** The live demonstration run, if any. Set at start(), cleared in finish(). */
    private data class DemoRun(
        val plan: ActionPlan,
        val refused: Set<PlanStep>,
        val cover: SequenceCover,
        var index: Int,
        val onDone: (SequenceOutcome) -> Unit,
        /**
         * MASTER F6b — provenance of this run: DIRECT_USER when the
         * demo was started by David's own command ("show me how
         * to…"), MODEL only for model-proposed demos. Carried on the
         * run (not the queue) so pause/resume keeps it, and so every
         * per-step gate request in the run carries the same origin.
         * The demo.sequence key itself stays non-grant-eligible by
         * design; the origin matters for the per-step out-of-scope
         * CONFIRM cards' grant offers.
         */
        val origin: RequestOrigin,
        /**
         * Last resolved target per target-query. Resume compares the fresh
         * resolution against the baseline for the SAME query — a target that
         * moved beyond tolerance while paused refuses honestly instead of
         * tapping the wrong place.
         */
        val previewedTargets: MutableMap<String, ScreenElement> = mutableMapOf(),
    )

    private var activeRun: DemoRun? = null

    @Volatile
    private var demoPaused: Boolean = false

    sealed interface PauseResult {
        /** Queue frozen; [stepIndex] is the step that will resume. */
        data class Paused(val stepIndex: Int) : PauseResult

        /** "Nothing is running." — harmless. */
        data object NothingRunning : PauseResult

        data object AlreadyPaused : PauseResult
    }

    sealed interface ResumeResult {
        /** Queue continuing from [stepIndex] after re-resolution. */
        data class Resumed(val stepIndex: Int) : ResumeResult

        /** Harmless no-op ack — the demo was not paused. */
        data object NotPaused : ResumeResult

        data object NothingRunning : ResumeResult

        /** Honest report — never guesses. Caller offers restart-from-step or abort. */
        data class TargetLost(val reason: String) : ResumeResult
    }

    /** True while a demonstration is frozen mid-sequence. */
    fun isDemoPaused(): Boolean = demoPaused

    /** True while a demonstration run is live (paused or running). */
    fun isDemoActive(): Boolean = activeRun != null

    /**
     * Freeze the live demonstration mid-sequence. Zero dispatches happen
     * while paused; the step index and resolved targets are preserved.
     */
    fun pause(): PauseResult {
        val run = activeRun ?: return PauseResult.NothingRunning
        if (demoPaused) return PauseResult.AlreadyPaused
        demoPaused = true
        audit.audit(
            "demo",
            "demonstration paused at step ${run.index} — queue frozen, " +
                "step index + resolved targets preserved",
        )
        return PauseResult.Paused(run.index)
    }

    /**
     * Continue a paused demonstration. Re-resolves the current step's target
     * against the live tree (TOCTOU) and revalidates the screen context
     * BEFORE any dispatch. Unresolvable target → [ResumeResult.TargetLost]
     * with the reason; the queue stays paused so the caller can offer
     * restart-from-step or abort — never guesses.
     */
    fun resume(): ResumeResult {
        val run = activeRun ?: return ResumeResult.NothingRunning
        if (!demoPaused) return ResumeResult.NotPaused
        val step = run.plan.steps.getOrNull(run.index)
            ?: return ResumeResult.TargetLost("step index ${run.index} out of range")
        // Screen-context revalidation: the expected app must still be foreground.
        val snap = readSnapshot()
        val expectedPkg = step.targetPackage ?: run.plan.demoAppPackage
        if (snap == null || snap.packageName != expectedPkg) {
            audit.audit(
                "demo",
                "resume refused at step ${run.index}: screen context changed " +
                    "(expected $expectedPkg, saw ${snap?.packageName})",
            )
            return ResumeResult.TargetLost(
                "screen context changed — expected $expectedPkg",
            )
        }
        // TOCTOU re-resolution of the current step's target.
        val el = safeFind(step.targetQuery)
            ?: return ResumeResult.TargetLost("target-lost: \"${step.targetQuery}\"")
        val prev = run.previewedTargets[step.targetQuery]
        if (prev != null && !sameTarget(el, prev)) {
            audit.audit(
                "demo",
                "resume refused at step ${run.index}: target moved beyond tolerance",
            )
            return ResumeResult.TargetLost("target moved beyond tolerance")
        }
        run.previewedTargets[step.targetQuery] = el
        demoPaused = false
        audit.audit("demo", "demonstration resumed at step ${run.index} — target re-resolved")
        runSteps(run.plan, run.refused, run.cover, run.index, run.onDone)
        return ResumeResult.Resumed(run.index)
    }

    /**
     * Resume from an explicit step (the "restart-from-step" offer after a
     * [ResumeResult.TargetLost]). The target is re-resolved fresh against
     * the preserved per-query baseline.
     */
    fun resumeFromStep(stepIndex: Int): ResumeResult {
        val run = activeRun ?: return ResumeResult.NothingRunning
        if (stepIndex !in run.plan.steps.indices) {
            return ResumeResult.TargetLost("step index $stepIndex out of range")
        }
        run.index = stepIndex
        return resume()
    }

    /**
     * Abort a paused demonstration (the "abort" offer). The queue is drained;
     * the caller is notified with AbortedByUser. Returns false when there was
     * no paused demo to abort.
     */
    fun abortPaused(): Boolean {
        val run = activeRun ?: return false
        if (!demoPaused) return false
        demoPaused = false
        audit.audit("demo", "paused demonstration aborted at step ${run.index}")
        finish(SequenceOutcome.AbortedByUser(run.index), run.onDone)
        return true
    }

    /**
     * Deactivation drain (§4.2-F): abandon the live run without dispatching
     * further. Unlike halt (which reports INTERRUPTED through the layer),
     * this is the queue-level guarantee that nothing enqueued dispatches
     * after the lifecycle leaves ACTIVE — the atomic consume+dispatch
     * boundary (§8.2) refuses any dispatch once the controller is not
     * ACTIVE, and this clears the run state itself.
     */
    fun drainForDeactivation(): Boolean {
        val run = activeRun ?: return false
        demoPaused = false
        cancelParked()
        audit.audit(
            "demo",
            "queue drained for deactivation at step ${run.index} — enqueued actions are dead",
        )
        finish(SequenceOutcome.Interrupted(run.index, "deactivation"), run.onDone)
        return true
    }

    /** Freeze check shared by every continuation point. True = stashed, do not proceed. */
    private fun freezeIfPaused(): Boolean = demoPaused

    // ---------- entry ----------

    /**
     * Runs [plan] to completion, invoking [onDone] exactly once with the
     * terminal outcome. Drives entirely through gate continuations, so a
     * parked CONFIRM card simply pauses the queue — nothing blocks a thread.
     *
     * MASTER F6b — [origin] is who started the demo: DIRECT_USER for
     * David's own command ("show me how to…"), MODEL only for a
     * model-proposed demo. It rides into the demo.sequence request and
     * every per-step gate request in the run. Default MODEL (safe:
     * always a card, no grant) for callers that cannot know.
     */
    fun start(
        plan: ActionPlan,
        origin: RequestOrigin = RequestOrigin.MODEL,
        onDone: (SequenceOutcome) -> Unit,
    ) {
        if (halt.isHalted()) {
            return finish(SequenceOutcome.Interrupted(-1, haltSource()), onDone)
        }
        // One live run at a time: a second start() while a demonstration is
        // active (running or paused) would corrupt the paused state.
        if (activeRun != null) {
            audit.audit("demo", "start refused — a demonstration is already active")
            return finish(
                SequenceOutcome.Failed(-1, "another demonstration is already running"),
                onDone,
            )
        }
        // §4.3: financial/security pre-scan BEFORE ANY token request —
        // including before the demo.sequence card is even requested.
        val refusedSteps = plan.steps.filter {
            FinancialActionDetector.matches(it.instruction, it.targetQuery)
        }
        for (step in refusedSteps) {
            val why = FinancialActionDetector.describe(step.instruction, step.targetQuery)
            audit.audit(
                "auth",
                "REFUSED ${AuthActionRegistry.ACTION_TAP_FINANCIAL} — " +
                    "step ${step.id} ($why). No card, no token, no dispatch.",
            )
        }

        val paranoid = strictness() == 2
        // Device-failure fix (2026-11-13, F4): the cover request
        // is scoped with the demo app's package, so a grant-covered
        // demo in an approved app does not card.
        val seqAction = AuthActionRegistry.demoSequence(
            goal = plan.goal,
            appLabel = plan.demoAppPackage,
            stepCount = plan.steps.size,
            paranoid = paranoid,
            scopePackage = plan.demoAppPackage,
        )
        audit.audit("auth", "demo.sequence requested for plan ${plan.id} (${plan.steps.size} steps)")
        var continued = false
        // MASTER F6b — AuthGate.request is (action, origin, onToken):
        // the trailing lambda binds to onToken. The origin is the
        // run's provenance — DIRECT_USER for David's own "show me"
        // command, MODEL for a model-proposed demo. (The old comment
        // claimed these requests are never direct user commands;
        // handleDemoSequence IS one.) The demo.sequence key stays
        // non-grant-eligible either way.
        gate.request(seqAction, origin = origin) { seqToken ->
            continued = true
            if (halt.isHalted()) {
                // Approval arrived but we were halted meanwhile: the token is
                // abandoned (single-use, expires in 90s) — never used.
                return@request finish(
                    SequenceOutcome.Interrupted(-1, haltSource()),
                    onDone,
                )
            }
            if (seqToken == null) {
                audit.audit("auth", "demo.sequence not approved — sequence never started")
                return@request finish(SequenceOutcome.NeverStarted, onDone)
            }
            // Consumed EXACTLY ONCE, immediately, at sequence start.
            val consumed = gate.consumeToken(seqToken.id, AuthActionRegistry.ACTION_DEMO_SEQUENCE)
            lastSequenceTokenId = seqToken.id
            audit.audit("auth", "demo.sequence token ${seqToken.id.take(8)} consumed once: $consumed")
            if (!consumed) {
                return@request finish(
                    SequenceOutcome.Failed(-1, "sequence token could not be consumed"),
                    onDone,
                )
            }
            val cover = SequenceCover(
                planId = plan.id,
                appPackage = plan.demoAppPackage,
                stepHash = plan.steps.hashCode(),
            )
            activeRun = DemoRun(plan, refusedSteps.toSet(), cover, 0, onDone, origin = origin)
            runSteps(plan, refusedSteps.toSet(), cover, 0, onDone)
        }
        // If the continuation did not run, the card is parked: the queue is
        // paused and resumes when the host resolves the confirmation.
        // SPEED (David, 2026-11-05 18:58): the pill says what the queue
        // is waiting on — never a silent wait.
        if (!continued) {
            audit.audit("auth", "demo.sequence awaiting David's decision — queue paused")
            renderer.setStatusNarration("Waiting for your approval — start this demo")
        }
    }

    // ---------- step loop ----------

    private fun runSteps(
        plan: ActionPlan,
        refused: Set<PlanStep>,
        cover: SequenceCover,
        index: Int,
        onDone: (SequenceOutcome) -> Unit,
    ) {
        // Stash the live step index for pause()/resume(), then freeze: a
        // paused queue stashes here and resume() re-enters at this point.
        activeRun?.let { it.index = index }
        if (freezeIfPaused()) return
        if (index >= plan.steps.size) {
            return finish(SequenceOutcome.Completed, onDone)
        }
        if (halt.isHalted()) {
            cancelParked()
            return finish(SequenceOutcome.Interrupted(index, haltSource()), onDone)
        }
        val step = plan.steps[index]
        if (step in refused) {
            guidanceOnly(step, index, plan.steps.size, "financial-refused")
            return runSteps(plan, refused, cover, index + 1, onDone)
        }
        // Master implementation §4: the tap queue executes TAP and
        // LONG_PRESS steps only. TYPE/SUBMIT steps belong to the search
        // sequence (SearchExecutor); the queue fails honestly here
        // instead of mistreating one as a tap. A LONG_PRESS step is a
        // tap held down (same `tap.guidance` key, same gate) — never a
        // tap in disguise: dispatchStep routes it to the long-press
        // sink with its hold, and the renderer holds the press state.
        if (step.kind != PlanStepKind.TAP && step.kind != PlanStepKind.LONG_PRESS) {
            audit.audit(
                "auth",
                "FAILED step ${step.id}: step kind ${step.kind} is not executable " +
                    "by the tap queue — TYPE/SUBMIT steps run in the search sequence.",
            )
            return finish(
                SequenceOutcome.Failed(
                    index,
                    "unsupported step kind ${step.kind} — the tap queue executes TAP and LONG_PRESS steps only",
                ),
                onDone,
            )
        }
        // Master §6: name the upcoming step in the shared feedback sink.
        feedback?.setNextStep(step.instruction)
        executeStep(plan, refused, cover, step, index, onDone)
    }

    private fun executeStep(
        plan: ActionPlan,
        refused: Set<PlanStep>,
        cover: SequenceCover,
        step: PlanStep,
        index: Int,
        onDone: (SequenceOutcome) -> Unit,
    ) {
        // Halt is checked before EVERY token request.
        if (halt.isHalted()) {
            cancelParked()
            return finish(SequenceOutcome.Interrupted(index, haltSource()), onDone)
        }
        // Fresh snapshot: feeds BOTH the sensitive gate and TOCTOU resolution.
        val snap = readSnapshot()
        val pkg = snap?.packageName

        // §4.1.2 pre-dispatch gate — the hard rule: sensitive package →
        // guidance-only. No token request, no confirmation card, no dispatch.
        // This runs per dispatch (the foreground app can change mid-sequence)
        // and BEFORE any token request.
        if (pkg != null && ScreenDataPolicy.isSensitivePackage(pkg)) {
            audit.audit(
                "auth",
                "GUIDANCE_ONLY ${AuthActionRegistry.ACTION_SENSITIVE_APP} — " +
                    "step ${step.id} in sensitive package $pkg: " +
                    "no token, no card, no dispatch.",
            )
            guidanceOnly(step, index, plan.steps.size, "sensitive-app")
            return runSteps(plan, refused, cover, index + 1, onDone)
        }

        // TOCTOU resolution #1 (for the intent preview highlight).
        val el = snap?.let { safeFind(step.targetQuery) }
        if (el == null) {
            val reason = if (snap == null) "tree unavailable" else "target-lost"
            audit.audit("auth", "FAILED step ${step.id}: $reason for \"${step.targetQuery}\"")
            return finish(SequenceOutcome.Failed(index, "$reason: \"${step.targetQuery}\""), onDone)
        }

        // §3.2 dispatch path selection.
        val outOfScope = step.targetPackage != null && step.targetPackage != plan.demoAppPackage
        val action = if (outOfScope) {
            AuthActionRegistry.tapGuidanceOutOfScope(
                instruction = step.instruction,
                demoApp = plan.demoAppPackage,
                otherApp = step.targetPackage!!,
            )
        } else {
            // Sequence-covered ALLOW — built ONLY because a live cover exists
            // (we hold it in this call chain); without a cover the CONFIRM
            // variant would be built instead. Paranoid strictness escalates
            // via the gate's effectiveRisk() regardless.
            AuthActionRegistry.tapGuidanceSequenced(step.instruction, cover.appPackage)
        }

        // D3 choreography: intent preview BEFORE any token request/dispatch.
        // The canonical contract's previewMoveTo carries no callback — the
        // queue owns the ~300ms preview timing via previewDelayer.
        val (ax, ay) = anchors.currentAnchor()
        renderer.showStep(ax, ay, rectF(el), index + 1, plan.steps.size, step.instruction)
        renderer.previewMoveTo(rectF(el))
        // POINTER FEW-CURSOR SPEC DELTAS (2026-11-05): the pill narrates
        // the step — "Moving to <target>…", not just "Guiding you —
        // Step N of M".
        renderer.setStatusNarration("Moving to ${step.targetQuery}…")
        previewDelayer.afterPreview {
            // Pause freeze: stash (index already stashed at runSteps entry);
            // resume() re-enters the loop. Pending confirmations are kept.
            if (freezeIfPaused()) return@afterPreview
            if (halt.isHalted()) {
                cancelParked()
                return@afterPreview finish(
                    SequenceOutcome.Interrupted(index, haltSource()),
                    onDone,
                )
            }
            // F4: never dispatch while the anchor is mid-transition.
            transitions.awaitSettled {
                requestTokenAndDispatch(plan, refused, cover, step, el, action, index, onDone)
            }
        }
    }

    private fun requestTokenAndDispatch(
        plan: ActionPlan,
        refused: Set<PlanStep>,
        cover: SequenceCover,
        step: PlanStep,
        previewed: ScreenElement,
        action: AuthGate.AuthAction,
        index: Int,
        onDone: (SequenceOutcome) -> Unit,
    ) {
        // Pause freeze: resume() re-enters via runSteps and requests fresh.
        if (freezeIfPaused()) return
        // Stash the TOCTOU-#1 resolution as the pause/resume baseline.
        activeRun?.previewedTargets?.put(step.targetQuery, previewed)
        // Halt checked before EVERY token request.
        if (halt.isHalted()) {
            cancelParked()
            return finish(SequenceOutcome.Interrupted(index, haltSource()), onDone)
        }
        // TOCTOU resolution #2 — immediately before the token request: the
        // element must still resolve, to the same target within tolerance.
        val fresh = safeFind(step.targetQuery)
        if (fresh == null || !sameTarget(fresh, previewed)) {
            audit.audit("auth", "FAILED step ${step.id}: target-lost before dispatch")
            return finish(
                SequenceOutcome.Failed(index, "target-lost: \"${step.targetQuery}\""),
                onDone,
            )
        }
        var continued = false
        // MASTER F6b — the per-step request carries the run's origin
        // (DIRECT_USER for David's own demo, MODEL for a
        // model-proposed one): out-of-scope CONFIRM cards offer a
        // grant only for his own commands. Covered steps use the
        // sequenced ALLOW variants, as the plan path does.
        val decision = gate.request(action, origin = activeRun?.origin ?: RequestOrigin.MODEL) { token ->
            continued = true
            // Pause freeze: a card approved while paused stashes here. The
            // issued token is abandoned (single-use, expires in 90s) — resume
            // requests a fresh one. Pending confirmations are preserved.
            if (freezeIfPaused()) {
                audit.audit("demo", "card resolved while paused at step $index — stashed")
                return@request
            }
            if (halt.isHalted()) {
                // Token abandoned (single-use, expires) — never dispatched.
                cancelParked()
                return@request finish(
                    SequenceOutcome.Interrupted(index, haltSource()),
                    onDone,
                )
            }
            if (token == null) {
                // Deny or timeout on a per-step card: abort, never proceed.
                audit.audit("auth", "per-step confirmation not granted for step ${step.id} — aborting")
                cancelParked()
                return@request finish(SequenceOutcome.AbortedByUser(index), onDone)
            }
            dispatchStep(plan, refused, cover, step, fresh, token.id, index, onDone)
        }
        if (!continued && decision is AuthGate.GateResult.NeedsConfirmation) {
            // Out-of-scope (or paranoid) card parked: queue paused.
            // SPEED (David, 2026-11-05 18:58): the pill says what the
            // step is waiting on — never a silent wait.
            pendingConfirmations.add(decision.pendingId)
            audit.audit(
                "auth",
                "step ${step.id} awaiting per-step confirmation " +
                    "(${decision.pendingId.take(8)}) — queue paused",
            )
            renderer.setStatusNarration("Waiting for your approval — ${step.instruction}")
        }
    }

    private fun dispatchStep(
        plan: ActionPlan,
        refused: Set<PlanStep>,
        cover: SequenceCover,
        step: PlanStep,
        el: ScreenElement,
        tokenId: String,
        index: Int,
        onDone: (SequenceOutcome) -> Unit,
    ) {
        // Halt checked before EVERY dispatch.
        if (halt.isHalted()) {
            cancelParked()
            return finish(SequenceOutcome.Interrupted(index, haltSource()), onDone)
        }
        // Pause freeze: the per-step token is abandoned (single-use, expires);
        // resume() requests a fresh one after re-resolution.
        if (freezeIfPaused()) {
            audit.audit("demo", "dispatch frozen while paused at step $index — token abandoned")
            return
        }
        // Stash the TOCTOU-#2 resolution as the pause/resume baseline.
        activeRun?.previewedTargets?.put(step.targetQuery, el)
        // POINTER MOUSE-VERBS (2026-11-05): a LONG_PRESS step dispatches
        // through the long-press sink with its hold (never as a tap);
        // a TAP step through the tap sink. Same gate key, same token.
        val isLongPress = step.kind == PlanStepKind.LONG_PRESS
        val holdMs = step.holdMs ?: GuidanceSession.DEFAULT_LONG_PRESS_HOLD_MS
        val verb = if (isLongPress) "Long-press" else "Tap"
        audit.audit("auth", "dispatching step ${step.id} with per-step token ${tokenId.take(8)}")
        val attempt = if (isLongPress) {
            safeDispatchLongPress(el, tokenId, holdMs)
        } else {
            safeDispatch(el, tokenId)
        }
        when (attempt) {
            is DispatchAttempt.Rejected -> {
                audit.audit("auth", "FAILED step ${step.id}: dispatch rejected — ${attempt.reason}")
                feedback?.recordAction(
                    "$verb \"${step.instruction}\"",
                    "failed — dispatch rejected: ${attempt.reason}",
                )
                finish(
                    SequenceOutcome.Failed(index, "dispatch-rejected: ${attempt.reason}"),
                    onDone,
                )
            }
            DispatchAttempt.Accepted -> {
                // Visible record that the action landed: the tap ripple
                // for a tap, the held press state (then ripple) for a
                // long-press — plus the pill narration for the phase.
                if (isLongPress) {
                    renderer.showPressState(holdMs)
                    renderer.setStatusNarration("Long-pressing ${step.targetQuery}…")
                } else {
                    renderer.showTapRipple()
                    renderer.setStatusNarration("Tapping ${step.targetQuery}…")
                }
                completions.awaitCompletion { outcome ->
                    when (outcome) {
                        GestureOutcome.COMPLETED -> {
                            renderer.pulseOnResult()
                            // Master §6: refresh the feedback screen from
                            // the live tree, then record the outcome.
                            feedback?.updateScreen(readSnapshot())
                            if (verifyLanding(plan, step)) {
                                audit.audit("auth", "step ${step.id} completed + landed")
                                feedback?.recordAction(
                                    "$verb \"${step.instruction}\"",
                                    "completed — landed",
                                )
                                feedback?.setNextStep(
                                    plan.steps.getOrNull(index + 1)?.instruction,
                                )
                                runSteps(plan, refused, cover, index + 1, onDone)
                            } else {
                                audit.audit("auth", "FAILED step ${step.id}: landing unverified")
                                feedback?.recordAction(
                                    "$verb \"${step.instruction}\"",
                                    "failed — landing unverified",
                                )
                                finish(
                                    SequenceOutcome.Failed(index, "landing-unverified"),
                                    onDone,
                                )
                            }
                        }
                        // §5.1: a cancelled in-flight gesture is INTERRUPTED, not failed.
                        GestureOutcome.CANCELLED ->
                            finish(
                                SequenceOutcome.Interrupted(index, haltSource()),
                                onDone,
                            )
                        // Risk §10.1: "unknown" is FAILED, never success.
                        GestureOutcome.UNKNOWN -> {
                            audit.audit("auth", "FAILED step ${step.id}: unknown-result (no completion callback)")
                            finish(
                                SequenceOutcome.Failed(index, "unknown-result"),
                                onDone,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- landing verification (C5) ----------

    /**
     * Re-snapshots after the tap: the tree must be readable and the expected
     * app (the step's target app, else the demonstrated app) still foreground.
     * "Tap landed" vs "tap accepted".
     */
    private fun verifyLanding(plan: ActionPlan, step: PlanStep): Boolean {
        val snap = readSnapshot() ?: return false
        val expected = step.targetPackage ?: plan.demoAppPackage
        return snap.packageName == expected
    }

    // ---------- guidance-only ----------

    /**
     * Guidance-only step: highlight + instruction, never a token or dispatch.
     * Used for sensitive packages (hard rule) and refused financial steps.
     */
    private fun guidanceOnly(step: PlanStep, index: Int, count: Int, reason: String) {
        val el = safeFind(step.targetQuery)
        val note = when (reason) {
            "sensitive-app" -> "guidance only in this app"
            else -> "complete this yourself"
        }
        if (el != null) {
            val (ax, ay) = anchors.currentAnchor()
            renderer.showStep(ax, ay, rectF(el), index + 1, count, "${step.instruction} ($note)")
        }
        audit.audit("auth", "step ${step.id} shown guidance-only ($reason)")
    }

    // ---------- tapDispatcher bridge ----------

    /**
     * The lambda Worker F wires to `GuidanceSession.tapDispatcher` — the only
     * bridge between the session's "tap it for me" path and the gated sink.
     *
     * Stage 2C (§8.2): this bridge performs NO token operation itself — its
     * pre-checks (halt, sensitive package, financial target, target still on
     * screen) refuse before any token is touched. The atomic consume+dispatch
     * boundary lives at the consuming sink: production routes
     * dispatcher.dispatch → LoomAccessibilityService.requestTap, which runs
     * AuthGate.consumeAndDispatch (check → probe → re-check → dispatchGesture
     * in one gate-monitor critical section). The bridge is deliberately NOT
     * wrapped in a second consumeAndDispatch — that would consume the token
     * twice and refuse every dispatch. A lifecycle flip or purgeAll() racing
     * the handoff is refused at the sink, never dispatched.
     *
     * The token was already issued by the gate for `tap.guidance` (the session
     * requested it); this bridge enforces the remaining safety properties
     * before handing the token to the sink, which consumes it:
     *  - halt flag → refuse;
     *  - sensitive foreground package → refuse, audited as
     *    `automation.sensitive_app` (the backstop for any path that bypassed
     *    the queue gate);
     *  - financial/security target label → refuse, audited as
     *    `tap.financial_transaction` (§4.3 applies to every tap path);
     *  - light TOCTOU: the element id must still be on screen.
     *
     * Returns true only when the sink accepted the gesture.
     */
    fun asTapDispatcher(): (ScreenElement, String) -> Boolean = { element, tokenId ->
        when {
            halt.isHalted() -> {
                audit.audit("auth", "tapDispatcher bridge refused: halted")
                false
            }
            else -> {
                val pkg = readSnapshot()?.packageName
                when {
                    pkg != null && ScreenDataPolicy.isSensitivePackage(pkg) -> {
                        audit.audit(
                            "auth",
                            "REFUSED ${AuthActionRegistry.ACTION_SENSITIVE_APP} — " +
                                "tapDispatcher bridge in sensitive package $pkg: no dispatch.",
                        )
                        false
                    }
                    FinancialActionDetector.matches(element.label()) -> {
                        audit.audit(
                            "auth",
                            "REFUSED ${AuthActionRegistry.ACTION_TAP_FINANCIAL} — " +
                                "tapDispatcher bridge on financial/security target " +
                                "\"${element.label()}\".",
                        )
                        false
                    }
                    !elementStillOnScreen(element) -> {
                        audit.audit("auth", "tapDispatcher bridge refused: target-lost")
                        false
                    }
                    else -> when (safeDispatch(element, tokenId)) {
                        DispatchAttempt.Accepted -> true
                        is DispatchAttempt.Rejected -> false
                    }
                }
            }
        }
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05) — the long-press bridge, wired by
     * the host to `GuidanceSession.longPressDispatcher`. Same pre-checks
     * as [asTapDispatcher] (halt, sensitive package, financial target,
     * target still on screen) and the same no-double-consume rule: the
     * token was already issued by the gate for `tap.guidance`; this
     * bridge enforces the safety properties before handing the token +
     * hold to the sink, which consumes it. Returns true only when the
     * sink accepted the hold gesture. A LONG_PRESS step with no sink
     * wired refuses here (via [safeDispatchLongPress]) — never as a tap.
     */
    fun asLongPressDispatcher(): (ScreenElement, String, Long) -> Boolean =
        { element, tokenId, holdMs ->
            when {
                halt.isHalted() -> {
                    audit.audit("auth", "longPressDispatcher bridge refused: halted")
                    false
                }
                else -> {
                    val pkg = readSnapshot()?.packageName
                    when {
                        pkg != null && ScreenDataPolicy.isSensitivePackage(pkg) -> {
                            audit.audit(
                                "auth",
                                "REFUSED ${AuthActionRegistry.ACTION_SENSITIVE_APP} — " +
                                    "longPressDispatcher bridge in sensitive package $pkg: no dispatch.",
                            )
                            false
                        }
                        FinancialActionDetector.matches(element.label()) -> {
                            audit.audit(
                                "auth",
                                "REFUSED ${AuthActionRegistry.ACTION_TAP_FINANCIAL} — " +
                                    "longPressDispatcher bridge on financial/security target " +
                                        "\"${element.label()}\".",
                            )
                            false
                        }
                        !elementStillOnScreen(element) -> {
                            audit.audit("auth", "longPressDispatcher bridge refused: target-lost")
                            false
                        }
                        else -> when (safeDispatchLongPress(element, tokenId, holdMs)) {
                            DispatchAttempt.Accepted -> true
                            is DispatchAttempt.Rejected -> false
                        }
                    }
                }
            }
        }

    // ---------- finish ----------

    private fun finish(outcome: SequenceOutcome, onDone: (SequenceOutcome) -> Unit) {
        // The run is over — clear the pause/resume state with it.
        activeRun = null
        demoPaused = false
        when (outcome) {
            is SequenceOutcome.Interrupted -> {
                renderer.hide()
                bus.post(
                    LoomStateEvent.Interrupted(
                        stepIndex = outcome.stepIndex,
                        totalSteps = -1,
                        source = outcome.source,
                    ),
                )
                audit.audit("auth", "INTERRUPTED at step ${outcome.stepIndex} (source=${outcome.source})")
            }
            is SequenceOutcome.Failed -> {
                renderer.hide()
                audit.audit("auth", "sequence FAILED at step ${outcome.stepIndex}: ${outcome.reason}")
            }
            is SequenceOutcome.AbortedByUser -> {
                renderer.hide()
                audit.audit("auth", "sequence aborted by David at step ${outcome.stepIndex}")
            }
            SequenceOutcome.NeverStarted -> {
                renderer.hide()
                audit.audit("auth", "sequence never started")
            }
            SequenceOutcome.Completed -> {
                // The final highlight stays for the host to dismiss; the host
                // owns end-of-demo visuals.
                audit.audit("auth", "sequence completed")
            }
        }
        onDone(outcome)
    }

    private fun cancelParked() {
        val ids = pendingConfirmations.toList()
        pendingConfirmations.clear()
        for (id in ids) {
            try {
                gate.cancelPending(id)
            } catch (_: Exception) {
            }
        }
    }

    private fun haltSource(): String = halt.haltSource() ?: "unknown"

    // ---------- small helpers ----------

    private fun readSnapshot(): ScreenModel? = try {
        trees.snapshot()
    } catch (_: Exception) {
        null
    }

    private fun safeFind(query: String): ScreenElement? = try {
        trees.findElement(query)
    } catch (_: Exception) {
        null
    }

    private fun safeDispatch(el: ScreenElement, tokenId: String): DispatchAttempt = try {
        dispatcher.dispatch(el, tokenId)
    } catch (e: Exception) {
        DispatchAttempt.Rejected(e.message ?: "dispatcher threw")
    }

    /**
     * POINTER MOUSE-VERBS (2026-11-05): long-press dispatch through the
     * wired sink. No sink → honest Rejected (never silently retried as
     * a tap — a long-press that lands as a tap would open the wrong
     * thing).
     */
    private fun safeDispatchLongPress(
        el: ScreenElement,
        tokenId: String,
        holdMs: Long,
    ): DispatchAttempt {
        val sink = longPressDispatcher
            ?: return DispatchAttempt.Rejected("long-press sink is not wired")
        return try {
            sink.dispatchLongPress(el, tokenId, holdMs)
        } catch (e: Exception) {
            DispatchAttempt.Rejected(e.message ?: "long-press dispatcher threw")
        }
    }

    private fun elementStillOnScreen(element: ScreenElement): Boolean = try {
        trees.snapshot()?.elements?.any { it.id == element.id } == true
    } catch (_: Exception) {
        false
    }

    /** Canonical-contract conversion: element bounds → RectF. */
    private fun rectF(el: ScreenElement): RectF = RectF(
        el.bounds.left.toFloat(),
        el.bounds.top.toFloat(),
        el.bounds.right.toFloat(),
        el.bounds.bottom.toFloat(),
    )

    /** TOCTOU sameness: centre within tolerance and size within 2x. */
    internal fun sameTarget(a: ScreenElement, b: ScreenElement): Boolean {
        val dcx = kotlin.math.abs(a.bounds.exactCenterX() - b.bounds.exactCenterX())
        val dcy = kotlin.math.abs(a.bounds.exactCenterY() - b.bounds.exactCenterY())
        if (dcx > TOCTOU_TOLERANCE_PX || dcy > TOCTOU_TOLERANCE_PX) return false
        val aw = a.bounds.width().toDouble()
        val ah = a.bounds.height().toDouble()
        val bw = b.bounds.width().toDouble().coerceAtLeast(1.0)
        val bh = b.bounds.height().toDouble().coerceAtLeast(1.0)
        if (aw < 1 || ah < 1) return false
        return aw / bw in 0.5..2.0 && ah / bh in 0.5..2.0
    }
}
