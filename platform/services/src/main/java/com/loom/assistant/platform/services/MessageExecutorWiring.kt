package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.messaging.CallExecutor
import com.loom.assistant.execution.messaging.CallGestures
import com.loom.assistant.execution.messaging.MessageActionKeys
import com.loom.assistant.execution.messaging.MessageAppResolution
import com.loom.assistant.execution.messaging.MessageAppResolver
import com.loom.assistant.execution.messaging.MessageAuthAction
import com.loom.assistant.execution.messaging.MessageExecutor
import com.loom.assistant.execution.messaging.MessageGestureAcceptance
import com.loom.assistant.execution.messaging.MessageGestures
import com.loom.assistant.execution.messaging.MessageLogger
import com.loom.assistant.execution.messaging.MessageTokenProvider
import com.loom.assistant.execution.messaging.MessageTreeSource
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.platform.apps.AppCatalogueHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) —
 * production wiring for the message and call sequences.
 *
 * Mirrors SearchExecutorWiring exactly:
 *  - [MessageAppResolver] → Worker 1's shared AppCatalogue via
 *    AppCatalogueHolder;
 *  - [MessageTreeSource] → ScreenAwarenessHolder.current + the
 *    service's input-focus probe ([hasInputFocus]);
 *  - [MessageGestures]/[CallGestures] → the
 *    LoomAccessibilityService gesture extensions (launchApp /
 *    requestTap / typeText / scrollScreen / goBack / sendTap /
 *    callTap / dialNumber) — each consumes its own single-use token
 *    inside the atomic consume+dispatch boundary;
 *  - [MessageTokenProvider] → a BLOCKING AuthGate.request: the
 *    calling thread waits until David answers the confirmation card
 *    or the gate's 90s pending expiry lapses.
 *
 * The `message.send` / `call.place` keys map to the factories that
 * put the exact text / contact on the card
 * ([AuthActionRegistry.messageSend]/[AuthActionRegistry.callPlace]) —
 * never the static generic actions. Neither key is grant-eligible,
 * so the wiring never offers or applies a grant for them; the scoped
 * launch/type/tap actions keep the search sequence's grant behaviour.
 *
 * Call [install] once at startup (after the accessibility service has
 * connected). It returns false when the service is not running yet —
 * the caller should retry later; until then the executors fail
 * honestly instead of dispatching through unwired seams.
 */
object MessageExecutorWiring {

    /** Pending confirmations expire after 90s (AuthGate) — wait no longer. */
    const val TOKEN_WAIT_TIMEOUT_MS = 90_000L

    fun install(context: Context): Boolean {
        val service = LoomAccessibilityService.instance ?: return false
        val appContext = context.applicationContext ?: context
        val apps = MessageAppResolver { name ->
            val catalogue = AppCatalogueHolder.getOrCreate(appContext)
            when (val r = catalogue.resolveByName(name)) {
                is com.loom.assistant.platform.apps.AppResolution.Found ->
                    MessageAppResolution.Found(r.app.packageName, r.app.label)
                is com.loom.assistant.platform.apps.AppResolution.NotFound ->
                    MessageAppResolution.NotFound
                is com.loom.assistant.platform.apps.AppResolution.Ambiguous ->
                    MessageAppResolution.Ambiguous(r.candidates.map { it.label })
            }
        }
        val tree = object : MessageTreeSource {
            override fun snapshot(): ScreenModel? = try {
                ScreenAwarenessHolder.current.snapshot()
            } catch (_: Exception) {
                null
            }

            override fun hasInputFocus(): Boolean = service.hasInputFocus()
        }
        val tokens = MessageBlockingGateTokenProvider(AuthGate.getOrNull())
        val messageGestures = object : MessageGestures {
            override fun launch(packageName: String, token: String?): MessageGestureAcceptance =
                when (val r = service.launchApp(packageName, token)) {
                    is GestureResult.Accepted -> MessageGestureAcceptance.Accepted
                    is GestureResult.Refused -> MessageGestureAcceptance.Refused(r.reason)
                }

            override fun tap(element: ScreenElement, token: String?): MessageGestureAcceptance =
                when (val r = service.requestTap(element, token)) {
                    is LoomAccessibilityService.TapResult.Dispatched ->
                        MessageGestureAcceptance.Accepted
                    is LoomAccessibilityService.TapResult.GateRequired ->
                        MessageGestureAcceptance.Refused(r.reason)
                    is LoomAccessibilityService.TapResult.Failed ->
                        MessageGestureAcceptance.Refused(r.reason)
                }

            override fun type(text: String, token: String?): MessageGestureAcceptance =
                when (val r = service.typeText(text, token)) {
                    is GestureResult.Accepted -> MessageGestureAcceptance.Accepted
                    is GestureResult.Refused -> MessageGestureAcceptance.Refused(r.reason)
                }

            override fun scroll(forward: Boolean, token: String?): MessageGestureAcceptance =
                when (val r = service.scrollScreen(forward, token)) {
                    is GestureResult.Accepted -> MessageGestureAcceptance.Accepted
                    is GestureResult.Refused -> MessageGestureAcceptance.Refused(r.reason)
                }

            override fun back(token: String?): MessageGestureAcceptance =
                when (val r = service.goBack(token)) {
                    is GestureResult.Accepted -> MessageGestureAcceptance.Accepted
                    is GestureResult.Refused -> MessageGestureAcceptance.Refused(r.reason)
                }

            override fun sendTap(element: ScreenElement, token: String?): MessageGestureAcceptance =
                when (val r = service.sendTap(element, token)) {
                    is GestureResult.Accepted -> MessageGestureAcceptance.Accepted
                    is GestureResult.Refused -> MessageGestureAcceptance.Refused(r.reason)
                }
        }
        val callGestures = object : CallGestures {
            override fun launch(packageName: String, token: String?): MessageGestureAcceptance =
                messageGestures.launch(packageName, token)

            override fun tap(element: ScreenElement, token: String?): MessageGestureAcceptance =
                messageGestures.tap(element, token)

            override fun type(text: String, token: String?): MessageGestureAcceptance =
                messageGestures.type(text, token)

            override fun scroll(forward: Boolean, token: String?): MessageGestureAcceptance =
                messageGestures.scroll(forward, token)

            override fun back(token: String?): MessageGestureAcceptance =
                messageGestures.back(token)

            override fun callTap(element: ScreenElement, token: String?): MessageGestureAcceptance =
                when (val r = service.callTap(element, token)) {
                    is GestureResult.Accepted -> MessageGestureAcceptance.Accepted
                    is GestureResult.Refused -> MessageGestureAcceptance.Refused(r.reason)
                }

            override fun dial(telUri: String, token: String?): MessageGestureAcceptance =
                when (val r = service.dialNumber(telUri, token)) {
                    is GestureResult.Accepted -> MessageGestureAcceptance.Accepted
                    is GestureResult.Refused -> MessageGestureAcceptance.Refused(r.reason)
                }
        }
        MessageExecutor.configure(
            apps = apps,
            tree = tree,
            gestures = messageGestures,
            tokens = tokens,
            log = MessageLogger { message ->
                android.util.Log.i("LoomMessaging", message)
            },
        )
        CallExecutor.configure(
            apps = apps,
            tree = tree,
            gestures = callGestures,
            tokens = tokens,
            log = MessageLogger { message ->
                android.util.Log.i("LoomMessaging", message)
            },
        )
        return true
    }

    /**
     * Blocking token acquisition over AuthGate.request. Maps the
     * executor's [MessageAuthAction] to the real registry action, then
     * waits: ALLOW actions resolve immediately; CONFIRM actions park a
     * card and this thread waits for David's decision (or the 90s
     * expiry). Null = denied, timed out, unknown key, or the gate is
     * unavailable — the executor then fails honestly at that step
     * instead of dispatching.
     */
    class MessageBlockingGateTokenProvider(
        private val gate: AuthGate?,
    ) : MessageTokenProvider {
        override fun tokenFor(action: MessageAuthAction): String? =
            tokenFor(action, RequestOrigin.MODEL)

        override fun tokenFor(
            action: MessageAuthAction,
            origin: RequestOrigin,
        ): String? {
            val g = gate ?: return null
            val registryAction = when (action.key) {
                MessageActionKeys.APP_LAUNCH ->
                    if (action.scope.isBlank()) AuthActionRegistry.APP_LAUNCH
                    else AuthActionRegistry.appLaunch(
                        action.scope,
                        action.label.removePrefix("Open ").trim().ifBlank { action.appLabel },
                    )
                MessageActionKeys.TAP_GUIDANCE ->
                    AuthActionRegistry.tapGuidanceTarget(action.label, action.scope, action.appLabel)
                MessageActionKeys.DEVICE_TYPE ->
                    if (action.scope.isBlank()) AuthActionRegistry.DEVICE_TYPE
                    else AuthActionRegistry.deviceType(action.scope, action.appLabel)
                MessageActionKeys.DEVICE_SCROLL -> AuthActionRegistry.DEVICE_SCROLL
                MessageActionKeys.DEVICE_BACK -> AuthActionRegistry.DEVICE_BACK
                // The exact-words factories: the card shows the
                // recipient + the exact text (send) or the contact +
                // number (call). No scope, no grantOffer — neither key
                // is grant-eligible, so these always card.
                MessageActionKeys.MESSAGE_SEND ->
                    AuthActionRegistry.messageSend(action.recipient, action.text)
                MessageActionKeys.CALL_PLACE ->
                    AuthActionRegistry.callPlace(action.recipient, action.text)
                else -> return null
            }
            val latch = CountDownLatch(1)
            var tokenId: String? = null
            try {
                g.request(registryAction, origin = origin, onToken = { token ->
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
