package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.MessageChannel
import com.loom.assistant.core.common.PlanStopRegistry
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.messaging.CallExecutor
import com.loom.assistant.execution.messaging.MessageExecutor
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.search.SearchExecutor
import com.loom.assistant.execution.tasks.TaskPlanExecutor
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.intelligence.planner.AppContextProvider
import com.loom.assistant.intelligence.planner.AppResolution
import com.loom.assistant.intelligence.planner.AppResolver
import com.loom.assistant.intelligence.planner.TaskPlanner
import com.loom.assistant.intelligence.planner.PlanAuditSink
import com.loom.assistant.intelligence.planner.PlanRequest
import com.loom.assistant.intelligence.planner.PlanStopSignal
import com.loom.assistant.intelligence.planner.PointerGuide
import com.loom.assistant.intelligence.planner.ScreenObserver
import com.loom.assistant.intelligence.planner.TaskStatusSink
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.interaction.pointer.GuidanceSession
import com.loom.assistant.interaction.pointer.PlanPointerGuide
import com.loom.assistant.lifecycle.LifecycleController
import com.loom.assistant.lifecycle.LifecycleState
import com.loom.assistant.platform.apps.AppCatalogueHolder
import com.loom.assistant.platform.storage.ActivityLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stage 2D (Worker B) — production wiring for the plan executor.
 *
 * Binds the executor's pure seams to the real platform pieces (the same
 * pattern as SearchExecutorWiring / MessageExecutorWiring):
 *  - AppResolver → AppCatalogue via AppCatalogueHolder (the one-line
 *    adapter Worker A's contracts were shaped for).
 *  - ScreenObserver → ScreenAwarenessHolder.current (fresh redacted
 *    snapshot per call; a null snapshot throws so the executor treats
 *    it as "screen unreadable", never as an empty screen).
 *  - StepDispatcher → PlanStepDispatcher with the blocking gate token
 *    provider, the accessibility gesture sinks, LoomAppLauncher, the
 *    search/message/call executors, and the caller's guideTo/nudge.
 *  - PointerGuide → Worker C's [PlanPointerGuide] over the caller's
 *    GuidanceSession when one is supplied (the floating host reuses
 *    its live session, or builds an empty-steps one); without a
 *    session (in-app hosts) the fallback is PlanPointerGuideAdapter
 *    over the same renderer provider — see that file.
 *  - PlanAuthCover → PlanCover over the real AuthGate.
 *  - PlanStopSignal → the caller's signal, or lifecycle-only when
 *    none is given (dispatch is refused outside ACTIVE anyway).
 *  - PlanAuditSink → ActivityLog ("plan" kind; step names/outcomes
 *    only, never screen content).
 *
 * Call [newExecutor] once per plan run (fresh cover + fresh dispatcher
 * per run — the origin and the cover are per-plan, never shared).
 * Installs the search/message wiring first (retried here because the
 * accessibility service may have connected after startup).
 */
object PlanWiring {

    /** Pending confirmations expire after 90 s (AuthGate) — wait no longer. */
    const val TOKEN_WAIT_TIMEOUT_MS = 90_000L

    /** The planner's AppResolver over the shared AppCatalogue. */
    fun appResolver(context: Context): AppResolver = AppResolver { name ->
        val catalogue = AppCatalogueHolder.getOrCreate(context.applicationContext ?: context)
        when (val r = catalogue.resolveByName(name)) {
            is com.loom.assistant.platform.apps.AppResolution.Found ->
                AppResolution.Found(r.app.packageName, r.app.label)
            is com.loom.assistant.platform.apps.AppResolution.NotFound ->
                AppResolution.NotFound
            is com.loom.assistant.platform.apps.AppResolution.Ambiguous ->
                AppResolution.Ambiguous(r.candidates.map { it.label })
        }
    }

    fun newExecutor(
        context: Context,
        request: PlanRequest,
        stopSignal: PlanStopSignal?,
        rendererProvider: (() -> GuidanceRendererContract?)? = null,
        guideTo: (target: String) -> Boolean = { false },
        guidanceSession: GuidanceSession? = null,
        /**
         * Simple-task execution fix (2026-11-07): the ONE task-state
         * sink. The executor posts In progress at start and the
         * honest terminal state at the end; the caller (via
         * LoomPlanRunner's onStatus) delivers each update to BOTH
         * the spoken ack and the shared conversation store.
         */
        statusSink: TaskStatusSink? = null,
    ): TaskPlanExecutor {
        val appContext = context.applicationContext ?: context
        MessageExecutorWiring.install(appContext)
        val resolver = appResolver(appContext)
        val observer = object : ScreenObserver {
            override fun observeNow(): ScreenModel =
                ScreenAwarenessHolder.current.snapshot()
                    ?: throw IllegalStateException("Could not read the screen — the accessibility tree is unavailable.")
        }
        // (ScreenObserver / PlanStopSignal / PlanAuditSink are plain
        // interfaces in the planner contracts, not fun interfaces — so
        // these are object expressions, never SAM lambdas.)
        // AOIE Phase 5 (audit open question (b)): in-app runs pass
        // no stopSignal, so the old fallback was lifecycle-only and
        // voice "stop" / the Stop window could not halt them. The
        // fallback is now lifecycle OR the shared PlanStopRegistry,
        // which both stop mechanisms set.
        val stop: PlanStopSignal = stopSignal ?: object : PlanStopSignal {
            override fun isStopped(): Boolean =
                LifecycleController.currentState() != LifecycleState.ACTIVE ||
                    PlanStopRegistry.isStopped()
        }
        val gate = AuthGate.getOrNull()
        // NAV-SCREEN fix (2026-11-11, N5): read the live service
        // ONCE — the gate and the capture source must belong to the
        // same service instance.
        val liveService = LoomAccessibilityService.instance
        val cover = PlanCover(
            requestToken = CoverTokenRequest { goal, appLabel, stepCount, origin, scopePackage ->
                PlanBlockingGateTokenProvider.requestDemoSequence(
                    gate, goal, appLabel, stepCount, origin, scopePackage,
                )
            },
            consumeToken = CoverTokenConsumer { tokenId ->
                try {
                    gate?.consumeToken(tokenId, AuthActionRegistry.ACTION_DEMO_SEQUENCE) == true
                } catch (_: Exception) {
                    false
                }
            },
            stop = stop,
            apps = resolver,
            onEvent = { event ->
                try {
                    ActivityLog.get(appContext).record("plan", event)
                } catch (_: Exception) {
                }
            },
        )
        // AOIE order (a) R2: the plan path's search install is
        // cover-aware — the search sequence's per-action tokens
        // become sequenced ALLOW while this plan's cover spans the
        // searched app. Installed here (after the cover exists),
        // not at the top of this function.
        SearchExecutorWiring.install(
            appContext,
            coverCheck = { pkg -> cover.isCoveredPackage(pkg) },
            pointerProvider = { rendererProvider?.invoke() },
        )
        val audit = object : PlanAuditSink {
            override fun log(planId: String, stepIndex: Int, event: String, detail: String) {
                try {
                    ActivityLog.get(appContext).record(
                        "plan",
                        "plan=$planId step=$stepIndex $event $detail",
                    )
                } catch (_: Exception) {
                }
            }
        }
        val dispatcher = PlanStepDispatcher(
            origin = request.origin,
            cover = cover,
            observer = observer,
            // Mark 57 transfer (2026-11-11, M6): the dispatcher
            // consults the same shared screen knowledge the planner
            // and executor use, so a nav destination's preferred tab
            // is checked against the live tree before the normal tiers.
            knowledge = ScreenKnowledgeStoreHolder.getOrCreate(appContext),
            apps = resolver,
            tokens = PlanBlockingGateTokenProvider(gate, request.origin),
            gestures = PlanGesturesAdapter,
            hasInputFocus = {
                try {
                    LoomAccessibilityService.instance?.hasInputFocus() == true
                } catch (_: Exception) {
                    false
                }
            },
            launchApp = { appName, origin, onResult ->
                LoomAppLauncher.launch(appContext, appName, onResult, origin)
            },
            // AOIE order (a): a covered LAUNCH_APP step launches
            // with its sequenced ALLOW token, not a fresh card.
            launchAppWithToken = { appName, token, onResult ->
                LoomAppLauncher.launchWithToken(appContext, appName, token, onResult)
            },
            searchInApp = { appName, query, origin ->
                SearchExecutor.execute(appName, query, origin)
            },
            // App-aware execution (2026-11-07): the order-aware
            // hand-off the executor's TRY_ALTERNATIVE retry uses.
            searchInAppWithOrder = { appName, query, origin, order ->
                SearchExecutor.execute(appName, query, origin, submitMechanisms = order)
            },
            // Universal Console (2026-11-07): the goal+order hand-off
            // — the one the dispatcher actually uses. The goal rides
            // with the order, so a retry of a plain search still
            // stops at the results list instead of opening a result.
            searchInAppFull = { appName, query, origin, goal, order ->
                SearchExecutor.execute(
                    appName, query, origin,
                    submitMechanisms = order, goal = goal,
                )
            },
            sendMessage = { channel, recipient, text, origin ->
                val messageChannel = if (channel == "whatsapp") {
                    MessageChannel.WHATSAPP
                } else {
                    MessageChannel.SMS
                }
                MessageExecutor.execute(messageChannel, recipient, text, origin)
            },
            placeCall = { target, origin ->
                CallExecutor.execute(target, origin)
            },
            guideTo = guideTo,
            nudgePointer = { direction ->
                val renderer = try {
                    rendererProvider?.invoke()
                } catch (_: Exception) {
                    null
                } ?: return@PlanStepDispatcher false
                val pointerDirection = when (direction.lowercase()) {
                    "left" -> PointerDirection.LEFT
                    "right" -> PointerDirection.RIGHT
                    "up" -> PointerDirection.UP
                    "down" -> PointerDirection.DOWN
                    else -> return@PlanStepDispatcher false
                }
                try {
                    renderer.nudgePointer(pointerDirection, 48f)
                } catch (_: Exception) {
                    false
                }
            },
            isStepCovered = { step -> cover.checkCover(step) },
            outOfScopeInfo = { step ->
                val coveredPkg = cover.coveredPackage
                val stepPkg = step.params[com.loom.assistant.intelligence.planner.PlanParams.PACKAGE]
                if (coveredPkg.isNullOrBlank() || stepPkg.isNullOrBlank() || stepPkg == coveredPkg) {
                    null
                } else {
                    Pair(cover.coveredAppLabel ?: coveredPkg, stepPkg)
                }
            },
        )
        // Pointer: Worker C's real PlanPointerGuide over the caller's
        // GuidanceSession when one exists (it owns the glide/park/idle
        // rules and re-resolves against the session's live tree); the
        // adapter is the fallback for hosts with no session.
        // MASTER Wave 4 (F11): the guide takes the renderer PROVIDER,
        // re-invoked on every draw — the old code captured
        // `rendererProvider?.invoke()` once here, so the plan path
        // could draw into a detached view silently for a whole run.
        val pointer: PointerGuide = if (guidanceSession != null) {
            PlanPointerGuide(guidanceSession, rendererProvider = rendererProvider)
        } else {
            PlanPointerGuideAdapter(observer, rendererProvider)
        }
        // AOIE Phase 5 (M2–M4) — the model actually proposes
        // structured steps now. The PlanJsonSource calls the
        // current-tier cloud model (no tier change, no new spend
        // beyond one planning call) with the plan-JSON prompt +
        // live-screen grounding; the planner validates every
        // proposal and falls back to rules on any failure. The
        // grounding is built lazily inside the lambda (on the plan
        // thread): foreground package/kind/affordances, focused
        // field, keyboard state, and the task app's package when
        // the request already carries one.
        val planJsonSource = PlanJsonSource.forProduction(
            context = appContext,
            grounding = {
                val snapshot = try {
                    observer.observeNow()
                } catch (_: Exception) {
                    null
                }
                val contextNow = snapshot?.let { AppContextProvider.fromSnapshot(it) }
                val taskAppPkg = request.taskRecord?.app?.let { label ->
                    try {
                        when (val r = resolver.resolve(label)) {
                            is AppResolution.Found -> r.packageName
                            else -> null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                contextNow?.let { AppContextProvider.groundingText(it, taskAppPkg) } ?: ""
            },
        )
        return TaskPlanExecutor(
            apps = resolver,
            planner = TaskPlanner(
                resolver,
                llmPlanJson = { goal -> planJsonSource.planJson(goal) },
                // KNOWLEDGE BASE (2026-11-09): the planner's replan
                // consults the same shared store the executor reads
                // and writes — one disk-backed base for both paths.
                knowledge = ScreenKnowledgeStoreHolder.getOrCreate(appContext),
            ),
            dispatcher = dispatcher,
            observer = observer,
            pointer = pointer,
            cover = cover,
            stop = stop,
            audit = audit,
            // AOIE Phase 6: the shared, disk-backed registry — the
            // plan path and the search path learn from the same
            // profiles now, and wins survive a restart.
            profiles = AppProfileStoreHolder.getOrCreate(appContext),
            // KNOWLEDGE BASE (2026-11-09): the shared, disk-backed
            // screen knowledge — read before planning (submit order
            // for a known screen), written on every observation and
            // outcome, surviving restarts.
            knowledge = ScreenKnowledgeStoreHolder.getOrCreate(appContext),
            // AOIE Phase 7: completed plans are offered as promotion
            // candidates only — nothing installs without approval.
            verifiedWorkflowSink = com.loom.assistant.intelligence.skilllab.SkillIntegrationAdapter.get(appContext),
            // NAV-SCREEN fix (2026-11-11, N5): the executor's
            // screenshot channel reads through the live service's
            // gate + capture — one shared gate, so a capture is only
            // ever granted per navigation task and revoked after.
            // No live service (executor built before the service
            // connected) → a fresh gate with no source: capture
            // refuses and the plan proceeds tree-only.
            screenshotGate = liveService?.screenshotGate
                ?: com.loom.assistant.execution.screenawareness.ScreenshotCaptureGate(),
            visualSource = liveService?.let { service ->
                { service.captureScreenshotFrame() }
            },
            statusSink = statusSink,
        )
    }

    /** The gesture sinks over the live accessibility service. */
    object PlanGesturesAdapter : PlanGestures {
        override fun tap(
            element: com.loom.assistant.execution.screenawareness.ScreenElement,
            token: String?,
        ): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.requestTap(element, token)) {
                is LoomAccessibilityService.TapResult.Dispatched -> PlanGestureResult.Accepted
                is LoomAccessibilityService.TapResult.GateRequired ->
                    PlanGestureResult.Refused(r.reason)
                is LoomAccessibilityService.TapResult.Failed ->
                    PlanGestureResult.Refused(r.reason)
            }
        }

        override fun type(text: String, token: String?): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.typeText(text, token)) {
                is GestureResult.Accepted -> PlanGestureResult.Accepted
                is GestureResult.Refused -> PlanGestureResult.Refused(r.reason)
            }
        }

        override fun submit(token: String?): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.submitSearch(token)) {
                is GestureResult.Accepted -> PlanGestureResult.Accepted
                is GestureResult.Refused -> PlanGestureResult.Refused(r.reason)
            }
        }

        /**
         * App-aware execution (2026-11-07): submit mechanism 2 — the
         * keyboard's own Search / Go / Done key. Same `device.submit`
         * key as [submit].
         */
        override fun submitViaKey(token: String?): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.submitSearchViaKeyboardKey(token)) {
                is GestureResult.Accepted -> PlanGestureResult.Accepted
                is GestureResult.Refused -> PlanGestureResult.Refused(r.reason)
            }
        }

        override fun scroll(forward: Boolean, token: String?): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.scrollScreen(forward, token)) {
                is GestureResult.Accepted -> PlanGestureResult.Accepted
                is GestureResult.Refused -> PlanGestureResult.Refused(r.reason)
            }
        }

        override fun back(token: String?): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.goBack(token)) {
                is GestureResult.Accepted -> PlanGestureResult.Accepted
                is GestureResult.Refused -> PlanGestureResult.Refused(r.reason)
            }
        }

        /**
         * Universal Console (2026-11-07) — long-press. Same tap
         * keys as [tap] (the service sink runs under
         * `tap.guidance` / `tap.device`); no new action key.
         */
        override fun longPress(
            element: com.loom.assistant.execution.screenawareness.ScreenElement,
            token: String?,
        ): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.requestLongPress(element, token)) {
                is LoomAccessibilityService.TapResult.Dispatched -> PlanGestureResult.Accepted
                is LoomAccessibilityService.TapResult.GateRequired ->
                    PlanGestureResult.Refused(r.reason)
                is LoomAccessibilityService.TapResult.Failed ->
                    PlanGestureResult.Refused(r.reason)
            }
        }

        /**
         * Universal Console (2026-11-07) — swipe. Same
         * `device.scroll` key as [scroll] (a swipe is scrolling by
         * gesture); no new action key.
         */
        override fun swipe(direction: String, token: String?): PlanGestureResult {
            val service = LoomAccessibilityService.instance
                ?: return PlanGestureResult.Refused("The accessibility service isn't running.")
            return when (val r = service.swipeScreen(direction, token)) {
                is GestureResult.Accepted -> PlanGestureResult.Accepted
                is GestureResult.Refused -> PlanGestureResult.Refused(r.reason)
            }
        }
    }

    /**
     * Blocking token acquisition over AuthGate.request (the
     * SearchExecutorWiring pattern). Maps each [PlanTokenRequest] to the
     * real registry action, then waits for David's decision (or the 90 s
     * expiry). Null = denied, timed out, unknown key, or no gate — the
     * dispatcher then fails honestly instead of dispatching.
     *
     * The [RequestOrigin] rides through to AuthGate.request, so a stored
     * scoped grant applies only for DIRECT_USER — MODEL requests always
     * get their card. The sequenced tap action is the ALLOW variant the
     * ActionQueue builds under a live demo.sequence cover; without a
     * cover the same key builds the CONFIRM variant instead.
     */
    class PlanBlockingGateTokenProvider(
        private val gate: AuthGate?,
        private val origin: RequestOrigin = RequestOrigin.MODEL,
    ) : PlanTokenProvider {

        override fun tokenFor(request: PlanTokenRequest): String? {
            val g = gate ?: return null
            // AOIE order (a) R1: the sequenced ALLOW variant of
            // the SAME key for every ordinary step kind when a live
            // plan cover spans the step — not just taps. The shared
            // CONFIRM registry entries are never flipped; these
            // ALLOW actions are built only here, at the
            // cover-aware provider layer. Out-of-scope steps still
            // get their CONFIRM escalation (taps); other kinds
            // fall back to the plain request.
            val registryAction = when (request.key) {
                PlanActionKeys.APP_LAUNCH -> when {
                    request.sequenced -> AuthActionRegistry.appLaunchSequenced(
                        request.scope,
                        request.appLabel.ifBlank { request.label.removePrefix("Open ").trim() },
                    )
                    request.scope.isBlank() -> AuthActionRegistry.APP_LAUNCH
                    else -> AuthActionRegistry.appLaunch(
                        request.scope,
                        request.appLabel.ifBlank { request.label.removePrefix("Open ").trim() },
                    )
                }
                PlanActionKeys.TAP_GUIDANCE -> when {
                    request.sequenced ->
                        AuthActionRegistry.tapGuidanceSequenced(request.label, request.scope)
                    request.outOfScope ->
                        AuthActionRegistry.tapGuidanceOutOfScope(
                            request.label,
                            request.outOfScopeApp,
                            request.outOfScopeOtherApp,
                        )
                    else ->
                        AuthActionRegistry.tapGuidanceTarget(
                            request.label,
                            request.scope,
                            request.appLabel,
                        )
                }
                PlanActionKeys.DEVICE_TYPE -> when {
                    request.sequenced ->
                        AuthActionRegistry.deviceTypeSequenced(request.scope)
                    request.scope.isBlank() -> AuthActionRegistry.DEVICE_TYPE
                    else -> AuthActionRegistry.deviceType(request.scope, request.appLabel)
                }
                PlanActionKeys.DEVICE_SUBMIT -> when {
                    request.sequenced ->
                        AuthActionRegistry.deviceSubmitSequenced(request.scope)
                    request.scope.isBlank() -> AuthActionRegistry.DEVICE_SUBMIT
                    else -> AuthActionRegistry.deviceSubmit(request.scope, request.appLabel)
                }
                PlanActionKeys.DEVICE_SCROLL -> when {
                    request.sequenced ->
                        AuthActionRegistry.deviceScrollSequenced(request.scope)
                    request.scope.isBlank() -> AuthActionRegistry.DEVICE_SCROLL
                    else -> AuthActionRegistry.deviceScroll(request.scope, request.appLabel)
                }
                PlanActionKeys.DEVICE_BACK -> when {
                    request.sequenced ->
                        AuthActionRegistry.deviceBackSequenced(request.scope)
                    request.scope.isBlank() -> AuthActionRegistry.DEVICE_BACK
                    else -> AuthActionRegistry.deviceBack(request.scope, request.appLabel)
                }
                else -> return null
            }
            return requestBlocking(g, registryAction, origin)
        }

        companion object {
            /**
             * The demo.sequence token for a plan cover. The origin is the
             * plan's own origin — MODEL never inherits a stored grant
             * (the gate enforces it). The request is scoped with the
             * plan's package (device-failure fix 2026-11-13, F4): a
             * scoped cover in an approved app is allowed by pseudo
             * control without a card when the grant is active; an
             * unscoped cover always cards.
             */
            fun requestDemoSequence(
                gate: AuthGate?,
                goal: String,
                appLabel: String,
                stepCount: Int,
                origin: RequestOrigin,
                scopePackage: String = "",
            ): String? {
                val g = gate ?: return null
                val action = AuthActionRegistry.demoSequence(
                    goal, appLabel, stepCount, scopePackage = scopePackage,
                )
                return requestBlocking(g, action, origin)
            }

            private fun requestBlocking(
                gate: AuthGate,
                action: AuthGate.AuthAction,
                origin: RequestOrigin,
            ): String? {
                val latch = CountDownLatch(1)
                var tokenId: String? = null
                try {
                    gate.request(action, origin = origin, onToken = { token ->
                        tokenId = token?.id
                        latch.countDown()
                    })
                } catch (_: Exception) {
                    return null
                }
                return try {
                    if (latch.await(TOKEN_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) tokenId else null
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                }
            }
        }
    }
}
