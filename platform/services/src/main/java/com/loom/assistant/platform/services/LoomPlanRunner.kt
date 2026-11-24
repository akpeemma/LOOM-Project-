package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.PlanStopRegistry
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStatus
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.PlanOutcome
import com.loom.assistant.intelligence.planner.TaskStatus
import com.loom.assistant.intelligence.planner.TaskStatusSink
import com.loom.assistant.intelligence.planner.TaskStatusUpdate
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.interaction.pointer.GuidanceSession
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState

/**
 * Stage 2D (Worker B) — the in-app plan runner, mirroring
 * LoomSearchRunner.
 *
 * The same executor path the floating host uses
 * (`LoomOverlayService.floatingVoiceHost.onPlanRequested`): build the
 * executor with the real adapters, run it on a background thread (it
 * BLOCKS on confirmation cards — the per-sequence cover card for
 * MODEL plans, then per-step cards where the cover does not span;
 * DIRECT_USER plans run under blanket advance approval, no card),
 * and deliver the honest outcome text to [onAck]. The floating host itself is NOT
 * reused — its ack is spoken through the floating session, which an
 * in-app session does not have; the in-app hosts (ChatScreenView,
 * HomeScreenView) speak/append the ack through their own live session
 * instead.
 *
 * Always returns true: the request is handled here. Failures arrive as
 * ack text ("That task stopped unexpectedly: …"), never as a refusal —
 * the manager only speaks its "can't do that from this session"
 * routing message when the host returns false.
 *
 * [rendererProvider] supplies the floating pointer renderer when one
 * exists (the floating host passes `ensureGatedRenderer()`); in-app
 * runs pass null and [runPlan] routes the pointer through the live
 * floating overlay service's renderer instead (starting it first when
 * it is not running and David did not stop it on purpose) — the
 * overlay service owns the only window the pointer can draw in. When
 * no renderer can be obtained, the plan still runs and the executor
 * names the pointer gap in the outcome (a display failure never fails
 * the task). [guideTo] is the floating host's GuidanceSession path;
 * in-app runs pass the default (guidance steps fail honestly there).
 * [guidanceSession] is the floating host's GuidanceSession when one
 * exists — the executor's pointer then runs through Worker C's
 * PlanPointerGuide over it; null (in-app runs) falls back to the
 * renderer adapter over the live-overlay provider. [stopSignal] is
 * the caller's combined halt + lifecycle signal; null means
 * lifecycle-only.
 *
 * Simple-task execution fix (2026-11-07): [onStatus] receives every
 * task-state update the executor posts (In progress at start, the
 * honest terminal state at the end). The host delivers each update
 * to BOTH the spoken ack and the shared conversation store — the
 * floating host appends to the store even when its voice session
 * has ended, so a failure can never again vanish from the chat.
 * The terminal outcome still arrives via [onAck] as well; hosts
 * speak the terminal state once (via onAck) and record it once
 * (via onStatus), never twice.
 */
object LoomPlanRunner {

    /**
     * AOIE Phase 5 (audit open question (b)) — true while a plan
     * run is executing on the loom-plan-runner thread. Backed by
     * core:common's PlanRunState so the voice module and the
     * overlay can read it with no new module edge. The Stop window
     * shows while this is true (it used to show only while an
     * interaction-layer sequence was active, so a plan could never
     * be stopped from the window).
     */
    var isPlanRunning: Boolean
        get() = com.loom.assistant.core.common.PlanRunState.isPlanRunning
        private set(value) {
            com.loom.assistant.core.common.PlanRunState.isPlanRunning = value
        }

    /** Bounded wait for a cold-started floating assistant to come up with a renderer (Home's poll). */
    private const val FLOATING_START_WAIT_MS = 10_000L
    private const val FLOATING_START_POLL_MS = 250L

    fun runPlan(
        context: Context,
        request: PlanRequest,
        onAck: (String) -> Unit,
        rendererProvider: (() -> GuidanceRendererContract?)? = null,
        guideTo: (target: String) -> Boolean = { false },
        stopSignal: PlanStopSignal? = null,
        guidanceSession: GuidanceSession? = null,
        onStatus: (TaskStatusUpdate) -> Unit = {},
        /**
         * MASTER Wave 4 (F10 strengthening, C-04) — the helper-down
         * gate. Hosts pass a lambda returning the plain-words refusal
         * when the helper cannot perform this task (DeviceControlCenter
         * per-capability liveness), or null when it can. When the
         * helper is down the plan NEVER starts: the refusal is posted
         * as a FAILED TaskStatus (durable, in the chat) and spoken
         * once via onAck. LOOM says so before attempting a task that
         * needs the helper — it never claims success for an action it
         * could not perform. Null (default) preserves the old
         * behaviour for callers/hosts that do not gate.
         */
        helperDownReason: (() -> String?)? = null,
    ): Boolean {
        // MASTER Wave 4 (F10): helper-down gate — checked BEFORE the
        // runner thread starts, so a dead helper fails honestly in
        // the chat instead of running a plan whose every step fails.
        val downReason = try {
            helperDownReason?.invoke()
        } catch (_: Exception) {
            null
        }
        if (downReason != null) {
            try {
                onStatus(
                    TaskStatusUpdate(
                        status = TaskStatus.FAILED,
                        message = downReason,
                        goal = request.goal,
                    ),
                )
            } catch (_: Exception) {
            }
            onAck(downReason)
            return true
        }
        // AOIE Phase 5: a stop from a previous run must never
        // kill this one — clear the shared flag at plan start.
        PlanStopRegistry.clear()
        Thread({
            isPlanRunning = true
            val outcome = try {
                // Pointer-drive fix (2026-11-06): in-app runs arrive
                // with no renderer — route the pointer through the
                // live floating overlay service instead of running
                // pointer-blind.
                val effectiveProvider = rendererProvider ?: floatingRendererProvider(context)
                val executor = PlanWiring.newExecutor(
                    context, request, stopSignal, effectiveProvider, guideTo, guidanceSession,
                    statusSink = TaskStatusSink { update -> onStatus(update) },
                )
                executor.execute(request)
            } catch (e: Exception) {
                val summary = "That task stopped unexpectedly: ${e.message ?: "unknown error"}"
                try {
                    onStatus(
                        TaskStatusUpdate(
                            status = TaskStatus.FAILED,
                            message = summary,
                            goal = request.goal,
                        ),
                    )
                } catch (_: Exception) {
                }
                PlanOutcome(
                    status = PlanStatus.FAILED,
                    verifiedSteps = 0,
                    totalSteps = 0,
                    failedStep = null,
                    summary = summary,
                )
            }
            isPlanRunning = false
            android.util.Log.i(
                "LoomPlan",
                "plan goal=\"${request.goal}\" origin=${request.origin} outcome=$outcome",
            )
            onAck(outcome.summary)
        }, "loom-plan-runner").start()
        return true
    }

    /**
     * The live overlay's renderer as a provider (re-resolved per call,
     * so a rebind picks up the fresh renderer — the H6 pattern). When
     * the floating assistant is not running, start it first — the same
     * [LoomOverlayService.start] the Home button uses — unless David
     * stopped it on purpose, and wait bounded for the renderer. When
     * it still cannot be shown, returns null: the plan runs anyway and
     * the executor names the pointer gap in the outcome (a display
     * failure never fails the task — §6).
     */
    private fun floatingRendererProvider(
        context: Context,
    ): (() -> GuidanceRendererContract?)? {
        if (LoomOverlayService.liveRenderer() != null) {
            return { LoomOverlayService.liveRenderer() }
        }
        if (isStoppedByChoice(context)) {
            android.util.Log.i(
                "LoomPlan",
                "in-app plan: floating assistant stopped by choice — running without a pointer",
            )
            return null
        }
        try {
            LoomOverlayService.start(context.applicationContext ?: context)
        } catch (e: Exception) {
            android.util.Log.w(
                "LoomPlan",
                "in-app plan: could not start the floating assistant: ${e.javaClass.simpleName}",
            )
            return null
        }
        val deadline = android.os.SystemClock.uptimeMillis() + FLOATING_START_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (LoomOverlayService.liveRenderer() != null) {
                return { LoomOverlayService.liveRenderer() }
            }
            try {
                Thread.sleep(FLOATING_START_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        android.util.Log.w(
            "LoomPlan",
            "in-app plan: floating assistant did not come up — running without a pointer",
        )
        return null
    }

    /**
     * The stopped-by-choice refusal (automatic recovery, Nov 6): an
     * intentional-stop marker is present AND the lifecycle is not
     * ACTIVE. The same read HomePointerControl.isStoppedByChoice
     * makes — replicated here because platform:services must not
     * depend on presentation:home. Either half alone is not a
     * refusal: a marker with an ACTIVE lifecycle is the plain STOP
     * case, where a start is allowed, and no marker means nothing was
     * stopped on purpose. A failed read means "not stopped by choice".
     */
    private fun isStoppedByChoice(context: Context): Boolean {
        return try {
            AutomaticRecoveryStore.get(context.applicationContext ?: context)
                .readIntentionalStop() != null &&
                LifecycleController.currentState() != LifecycleState.ACTIVE
        } catch (_: Exception) {
            false
        }
    }
}
