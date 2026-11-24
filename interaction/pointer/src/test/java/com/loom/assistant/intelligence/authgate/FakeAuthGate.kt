package com.loom.assistant.intelligence.authgate

import com.loom.assistant.core.common.RequestOrigin

/**
 * JVM test fakes for the AuthGate surface. Member shapes mirror the real
 * intelligence:authgate sources exactly (only the members the pointer
 * tests compile against: GuidanceSession.requestTap).
 */
class AuthToken(val id: String)

/** MASTER F6a — the fake action carries the scope + label the real
 *  registry puts on the action, so tests can assert the guidance
 *  path requests a scoped, labelled action (grant-offerable). */
class AuthAction(
    val key: String,
    val scope: String = "",
    val appLabel: String = "",
)

object AuthActionRegistry {
    const val ACTION_TAP = "tap.device"
    const val ACTION_TAP_GUIDANCE = "tap.guidance"
    const val ACTION_DEVICE_SCROLL = "device.scroll"

    fun tapGuidanceTarget(instruction: String) = AuthAction(ACTION_TAP_GUIDANCE)

    /** AOIE order (a) R3: the scoped overload — the fake keeps the
     *  key AND the scope/label (MASTER F6a: the label is what makes
     *  "Always allow" renderable). */
    fun tapGuidanceTarget(instruction: String, scope: String, appLabel: String) =
        AuthAction(ACTION_TAP_GUIDANCE, scope = scope, appLabel = appLabel)

    /** AOIE order (a) R3: the scoped scroll factory. */
    fun deviceScroll(packageName: String, appLabel: String) =
        AuthAction(ACTION_DEVICE_SCROLL, scope = packageName, appLabel = appLabel)

    /** Mirrors the real static val — the existing `device.scroll` action. */
    val DEVICE_SCROLL = AuthAction(ACTION_DEVICE_SCROLL)
}

class AuthGate private constructor() {

    sealed class GateResult {
        data class Allow(val token: AuthToken) : GateResult()
        data class NeedsConfirmation(val pendingId: String) : GateResult()
        data class Deny(val reason: String) : GateResult()
    }

    /** Scripted decision returned by [request]. */
    var scripted: GateResult = GateResult.Deny("unscripted fake gate")

    val requestedActions = mutableListOf<AuthAction>()
    /** MASTER F6a — the origin of every request, in order. */
    val requestedOrigins = mutableListOf<RequestOrigin>()

    fun request(
        action: AuthAction,
        origin: RequestOrigin = RequestOrigin.MODEL,
        onToken: (AuthToken?) -> Unit,
    ): GateResult {
        requestedActions.add(action)
        requestedOrigins.add(origin)
        val r = scripted
        if (r is GateResult.Allow) onToken(r.token)
        return r
    }

    companion object {
        private var instance: AuthGate? = null

        /** Test factory — the private constructor mirrors the real singleton. */
        fun createForTest(): AuthGate = AuthGate()

        fun getOrNull(): AuthGate? = instance

        fun setForTest(gate: AuthGate?) {
            instance = gate
        }
    }
}
