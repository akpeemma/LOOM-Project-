package com.loom.assistant.platform.services

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.search.AppResolution
import com.loom.assistant.execution.search.AppResolver
import com.loom.assistant.execution.search.GestureAcceptance
import com.loom.assistant.execution.search.SearchActionKeys
import com.loom.assistant.execution.search.SearchAuthAction
import com.loom.assistant.execution.search.SearchExecutor
import com.loom.assistant.execution.search.SearchGestures
import com.loom.assistant.execution.search.SearchLogger
import com.loom.assistant.execution.search.SearchTreeSource
import com.loom.assistant.execution.search.TokenProvider
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import com.loom.assistant.platform.apps.AppCatalogueHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Master implementation §4 — production wiring for the search sequence.
 *
 * Binds [SearchExecutor]'s pure-JVM seams to the real platform pieces:
 *  - [AppResolver] → Worker 1's shared AppCatalogue via AppCatalogueHolder;
 *  - [SearchTreeSource] → ScreenAwarenessHolder.current + the service's
 *    input-focus probe;
 *  - [SearchGestures] → the LoomAccessibilityService gesture extensions
 *    (launchApp / requestTap / typeText / submitSearch / scrollScreen /
 *    goBack) — each consumes its own single-use token inside the atomic
 *    consume+dispatch boundary;
 *  - [TokenProvider] → a BLOCKING AuthGate.request: the calling thread
 *    waits until David answers the confirmation card or the gate's 90s
 *    pending expiry lapses. The speech worker must therefore call
 *    SearchExecutor.execute() off the main thread.
 *
 * Call [install] once at startup (after the accessibility service has
 * connected). It returns false when the service is not running yet — the
 * caller should retry later; until then SearchExecutor.execute() fails
 * honestly instead of dispatching through unwired seams.
 */
object SearchExecutorWiring {

    /** Pending confirmations expire after 90s (AuthGate) — wait no longer. */
    const val TOKEN_WAIT_TIMEOUT_MS = 90_000L

    /** Parked pointer lingers this long after an accepted submit, then clears. */
    const val PARKED_CLEAR_DELAY_MS = 2_000L

    /**
     * Pointer choreography for SEARCH/TYPE (2026-11-05, David's
     * confirmation): the search sequence is a first-class pointer
     * operation — move to the search affordance → tap → wait for focus
     * → type the exact text → submit → verify results — and the pointer
     * must be SEEN doing it. [pointerProvider] re-resolves the live
     * gated renderer on every gesture (never a cached one); null =
     * no pointer choreography (LoomSearchRunner's standalone path),
     * gestures still run. The choreography is best-effort and wrapped
     * so it can never break a gesture: a failed draw is a missed
     * visual, never a failed search.
     *
     *  - tap: park the pointer on the tapped element + ripple on accept
     *    (the cursor stays parked on the field while typing);
     *  - type: pill narrates "Typing…";
     *  - submit: pill narrates "Searching…", parked pointer clears
     *    2s after an accepted submit (results have landed; a stale
     *    ring must not sit on screen indefinitely);
     *  - scroll: pill narrates the direction while parked.
     * Wait-for-focus and result verification live in SearchExecutor
     * (untouched here); this file only adds the visible pointer.
     */
    fun install(
        context: Context,
        /**
         * AOIE order (a) R2 — cover check for the plan path:
         * (package) -> true when a live plan cover spans a search
         * in that package. When set, the search sequence's tokens
         * come from [CoverAwareTokenProvider] (sequenced ALLOW
         * under the cover, plain CONFIRM otherwise). Null =
         * standalone search (current provider). PlanWiring passes
         * the plan cover's check; every other caller keeps the
         * current behaviour. This param sits BEFORE
         * [pointerProvider] on purpose: existing callers pass the
         * pointer provider as a trailing lambda, and a trailing
         * lambda always binds to the LAST function parameter.
         */
        coverCheck: ((packageName: String) -> Boolean)? = null,
        pointerProvider: (() -> GuidanceRendererContract?)? = null,
    ): Boolean {
        val service = LoomAccessibilityService.instance ?: return false
        val appContext = context.applicationContext ?: context
        // Pointer choreography helpers (best-effort; never throw — a
        // missed visual must never fail a gesture).
        val pointer: () -> GuidanceRendererContract? = {
            try {
                pointerProvider?.invoke()
            } catch (_: Exception) {
                null
            }
        }
        fun elementRect(element: ScreenElement): RectF = RectF(
            element.bounds.left.toFloat(),
            element.bounds.top.toFloat(),
            element.bounds.right.toFloat(),
            element.bounds.bottom.toFloat(),
        )
        fun shortLabel(element: ScreenElement): String =
            element.label().take(24).ifBlank { "this" }
        fun narrate(text: String) {
            try {
                pointer()?.setStatusNarration(text)
            } catch (_: Exception) {
            }
        }
        SearchExecutor.configure(
            apps = AppResolver { name ->
                // Worker 1's shared catalogue (Master §1): exact label
                // first, whole-word phrase matching, Ambiguous instead of
                // guessing — reached via the process-wide holder so
                // install/remove refreshes apply to every caller.
                val catalogue = AppCatalogueHolder.getOrCreate(appContext)
                when (val r = catalogue.resolveByName(name)) {
                    is com.loom.assistant.platform.apps.AppResolution.Found ->
                        AppResolution.Found(r.app.packageName, r.app.label)
                    is com.loom.assistant.platform.apps.AppResolution.NotFound ->
                        AppResolution.NotFound
                    is com.loom.assistant.platform.apps.AppResolution.Ambiguous ->
                        AppResolution.Ambiguous(r.candidates.map { it.label })
                }
            },
            tree = object : SearchTreeSource {
                override fun snapshot(): ScreenModel? = try {
                    ScreenAwarenessHolder.current.snapshot()
                } catch (_: Exception) {
                    null
                }

                override fun hasInputFocus(): Boolean = service.hasInputFocus()
            },
            gestures = object : SearchGestures {
                override fun launch(packageName: String, token: String?): GestureAcceptance {
                    narrate("Opening app…")
                    return when (val r = service.launchApp(packageName, token)) {
                        is GestureResult.Accepted -> GestureAcceptance.Accepted
                        is GestureResult.Refused -> GestureAcceptance.Refused(r.reason)
                    }
                }

                override fun tap(element: ScreenElement, token: String?): GestureAcceptance {
                    // Pointer choreography for SEARCH/TYPE: park the
                    // pointer on the tapped element (it glides there from
                    // wherever it was) and narrate — the cursor stays
                    // parked on the field while typing follows.
                    try {
                        val r = pointer()
                        if (r != null) {
                            r.parkPointer(elementRect(element), "Tap ${shortLabel(element)}")
                            r.setStatusNarration("Tapping ${shortLabel(element)}…")
                        }
                    } catch (_: Exception) {
                    }
                    return when (val r = service.requestTap(element, token)) {
                        is LoomAccessibilityService.TapResult.Dispatched -> {
                            try {
                                pointer()?.showTapRipple()
                            } catch (_: Exception) {
                            }
                            GestureAcceptance.Accepted
                        }
                        is LoomAccessibilityService.TapResult.GateRequired ->
                            GestureAcceptance.Refused(r.reason)
                        is LoomAccessibilityService.TapResult.Failed ->
                            GestureAcceptance.Refused(r.reason)
                    }
                }

                override fun type(text: String, token: String?): GestureAcceptance {
                    // The cursor is already parked on the field from the
                    // tap above; the pill narrates the typing phase.
                    narrate("Typing…")
                    return when (val r = service.typeText(text, token)) {
                        is GestureResult.Accepted -> GestureAcceptance.Accepted
                        is GestureResult.Refused -> GestureAcceptance.Refused(r.reason)
                    }
                }

                override fun submit(token: String?): GestureAcceptance {
                    narrate("Searching…")
                    return when (val r = service.submitSearch(token)) {
                        is GestureResult.Accepted -> {
                            // Results have landed: keep the parked pointer
                            // visible briefly so David sees where the
                            // search ran, then clear it — a stale ring
                            // must not sit on screen indefinitely.
                            try {
                                Handler(service.mainLooper).postDelayed({
                                    try {
                                        pointer()?.clearParked()
                                    } catch (_: Exception) {
                                    }
                                }, PARKED_CLEAR_DELAY_MS)
                            } catch (_: Exception) {
                            }
                            GestureAcceptance.Accepted
                        }
                        is GestureResult.Refused -> GestureAcceptance.Refused(r.reason)
                    }
                }

                /**
                 * App-aware execution (2026-11-07): submit mechanism 2
                 * — the keyboard's own Search / Go / Done key. Same
                 * `device.submit` key, same narration and parked-pointer
                 * clear as [submit].
                 */
                override fun submitViaKey(token: String?): GestureAcceptance {
                    narrate("Searching…")
                    return when (val r = service.submitSearchViaKeyboardKey(token)) {
                        is GestureResult.Accepted -> {
                            try {
                                Handler(service.mainLooper).postDelayed({
                                    try {
                                        pointer()?.clearParked()
                                    } catch (_: Exception) {
                                    }
                                }, PARKED_CLEAR_DELAY_MS)
                            } catch (_: Exception) {
                            }
                            GestureAcceptance.Accepted
                        }
                        is GestureResult.Refused -> GestureAcceptance.Refused(r.reason)
                    }
                }

                override fun scroll(forward: Boolean, token: String?): GestureAcceptance {
                    narrate(if (forward) "Scrolling down…" else "Scrolling up…")
                    return when (val r = service.scrollScreen(forward, token)) {
                        is GestureResult.Accepted -> GestureAcceptance.Accepted
                        is GestureResult.Refused -> GestureAcceptance.Refused(r.reason)
                    }
                }

                override fun back(token: String?): GestureAcceptance {
                    narrate("Going back…")
                    return when (val r = service.goBack(token)) {
                        is GestureResult.Accepted -> GestureAcceptance.Accepted
                        is GestureResult.Refused -> GestureAcceptance.Refused(r.reason)
                    }
                }
            },
            tokens = if (coverCheck != null) {
                CoverAwareTokenProvider(AuthGate.getOrNull(), coverCheck)
            } else {
                BlockingGateTokenProvider(AuthGate.getOrNull())
            },
            // AOIE Phase 6 — the standalone search path now reads
            // the shared, disk-backed registry too: the learned
            // submit order for this app, and its entry hints.
            submitOrderProvider = { pkg ->
                AppProfileStoreHolder.getOrCreate(appContext).preferredOrder(pkg)
            },
            searchEntryHints = { pkg ->
                AppProfileStoreHolder.getOrCreate(appContext).profileFor(pkg).searchEntryHints
            },
            // Device-evidence fix (2026-11-05, item 1): per-step search
            // outcomes to logcat (tag LoomSearch) so a failed search can
            // be read off the device instead of guessed at.
            log = SearchLogger { message ->
                android.util.Log.i("LoomSearch", message)
            },
        )
        return true
    }

    /**
     * Blocking token acquisition over AuthGate.request. Maps the
     * executor's [SearchAuthAction] to the real registry action, then
     * waits: ALLOW actions resolve immediately; CONFIRM actions park a
     * card and this thread waits for David's decision (or the 90s
     * expiry). Null = denied, timed out, unknown key, or the gate is
     * unavailable — the executor then fails honestly at that step
     * instead of dispatching.
     */
    class BlockingGateTokenProvider(
        private val gate: AuthGate?,
    ) : TokenProvider {
        override fun tokenFor(action: SearchAuthAction): String? =
            tokenFor(action, RequestOrigin.MODEL)

        override fun tokenFor(
            action: SearchAuthAction,
            origin: RequestOrigin,
        ): String? {
            val g = gate ?: return null
            // Device-evidence fix (2026-11-05, items 4+6): the executor
            // now carries the searched app's package ([scope]) and label
            // ([appLabel]) on every token request, so the wiring builds
            // the SCOPED factory action — scope + "Always allow…" offer
            // — instead of the static blank-scope action. The static
            // fallbacks stay only for callers that omit the scope
            // (legacy fakes); a blank scope can never offer or apply a
            // grant, so an unscoped request still cards, honestly.
            val registryAction = when (action.key) {
                SearchActionKeys.APP_LAUNCH ->
                    if (action.scope.isBlank()) AuthActionRegistry.APP_LAUNCH
                    else AuthActionRegistry.appLaunch(
                        action.scope,
                        action.label.removePrefix("Open ").trim().ifBlank { action.appLabel },
                    )
                SearchActionKeys.TAP_GUIDANCE ->
                    AuthActionRegistry.tapGuidanceTarget(action.label, action.scope, action.appLabel)
                SearchActionKeys.DEVICE_TYPE ->
                    if (action.scope.isBlank()) AuthActionRegistry.DEVICE_TYPE
                    else AuthActionRegistry.deviceType(action.scope, action.appLabel)
                SearchActionKeys.DEVICE_SUBMIT ->
                    if (action.scope.isBlank()) AuthActionRegistry.DEVICE_SUBMIT
                    else AuthActionRegistry.deviceSubmit(action.scope, action.appLabel)
                // AOIE order (a) R3: scoped scroll/back so a stored
                // per-app grant can apply (unscoped requests never
                // could). Blank scope keeps the old static action.
                SearchActionKeys.DEVICE_SCROLL ->
                    if (action.scope.isBlank()) AuthActionRegistry.DEVICE_SCROLL
                    else AuthActionRegistry.deviceScroll(action.scope, action.appLabel)
                SearchActionKeys.DEVICE_BACK ->
                    if (action.scope.isBlank()) AuthActionRegistry.DEVICE_BACK
                    else AuthActionRegistry.deviceBack(action.scope, action.appLabel)
                else -> return null
            }
            return SearchExecutorWiring.requestBlocking(g, registryAction, origin)
        }
    }

    /**
     * AOIE order (a) R2 — the cover-aware search token provider.
     * Inside a covered plan step, every token the search sequence
     * asks for (launch, field tap, type, submit, result tap,
     * scroll, back) is the sequenced ALLOW variant of the SAME
     * key — no card per action, no new keys. Outside a cover (or
     * for a package the cover does not span) it delegates to the
     * plain blocking provider, card and all. The cover is checked
     * per request against the live cover, so a cover that ends
     * mid-search stops issuing ALLOW tokens immediately.
     */
    class CoverAwareTokenProvider(
        private val gate: AuthGate?,
        private val isCoveredPackage: (packageName: String) -> Boolean,
    ) : TokenProvider {
        private val delegate = BlockingGateTokenProvider(gate)

        override fun tokenFor(action: SearchAuthAction): String? =
            tokenFor(action, RequestOrigin.MODEL)

        override fun tokenFor(
            action: SearchAuthAction,
            origin: RequestOrigin,
        ): String? {
            val g = gate ?: return delegate.tokenFor(action, origin)
            if (action.scope.isBlank() || !isCoveredPackage(action.scope)) {
                return delegate.tokenFor(action, origin)
            }
            val sequenced = when (action.key) {
                SearchActionKeys.APP_LAUNCH ->
                    AuthActionRegistry.appLaunchSequenced(
                        action.scope,
                        action.appLabel.ifBlank { action.label.removePrefix("Open ").trim() },
                    )
                SearchActionKeys.TAP_GUIDANCE ->
                    AuthActionRegistry.tapGuidanceSequenced(action.label, action.scope)
                SearchActionKeys.DEVICE_TYPE ->
                    AuthActionRegistry.deviceTypeSequenced(action.scope)
                SearchActionKeys.DEVICE_SUBMIT ->
                    AuthActionRegistry.deviceSubmitSequenced(action.scope)
                SearchActionKeys.DEVICE_SCROLL ->
                    AuthActionRegistry.deviceScrollSequenced(action.scope)
                SearchActionKeys.DEVICE_BACK ->
                    AuthActionRegistry.deviceBackSequenced(action.scope)
                else -> return delegate.tokenFor(action, origin)
            }
            return SearchExecutorWiring.requestBlocking(g, sequenced, origin)
        }
    }

    /**
     * Shared blocking request: waits for David's decision (or the
     * 90 s expiry) on a CONFIRM action, or resolves an ALLOW
     * action immediately. Null = denied, timed out, unknown key,
     * or no gate.
     */
    fun requestBlocking(
        gate: AuthGate,
        action: AuthGate.AuthAction,
        origin: RequestOrigin,
    ): String? {
        val latch = CountDownLatch(1)
        var tokenId: String? = null
        try {
            // onToken passed by NAME: request(action, onToken,
            // origin = MODEL) — a trailing or positional lambda binds
            // to origin instead. The origin is the caller's: the
            // voice host passes DIRECT_USER, so a stored scoped grant
            // (§8) applies and David is not re-carded for a command
            // he just spoke.
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

/** Input-focus probe for the search sequence's wait-for-focus step. */
fun LoomAccessibilityService.hasInputFocus(): Boolean {
    val node = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (_: Exception) {
        null
    } ?: return false
    return try {
        true
    } finally {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }
}
