package com.loom.assistant.execution.search

import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SubmitVerification
import com.loom.assistant.execution.screenawareness.SubmitVerifier

/**
 * App-aware execution (2026-11-07, David's directive §1) — the
 * keyboard / submit engine.
 *
 * One submit is a CHAIN, not a single dispatch: mechanisms are tried
 * in order ([SubmitMechanism]), each with a FRESH token, a brief
 * settle wait, a fresh snapshot, and a [SubmitVerifier] judgement.
 * The chain stops on [SubmitVerification.Executed] and returns the
 * full attempt log. Dispatch alone is NEVER success — an Accepted
 * dispatch with an unchanged screen is recorded as NotExecuted /
 * Unknown and the chain moves on. A denied token (null) stops the
 * chain: the user declined a confirmation, so no further mechanism
 * is attempted.
 *
 * [tapTarget] resolves the tap target for the tap mechanisms
 * ([SubmitMechanism.SUGGESTION_TAP] / [SubmitMechanism.SEARCH_BUTTON])
 * against the LATEST snapshot, per attempt — never a cached element.
 * Null means that mechanism is unavailable on this screen (recorded
 * as Refused, no dispatch).
 */
data class SubmitAttempt(
    val mechanism: SubmitMechanism,
    val dispatch: GestureAcceptance,
    val verification: SubmitVerification,
)

data class SubmitChainResult(
    val verification: SubmitVerification,
    val attempts: List<SubmitAttempt>,
)

class SubmitChain(
    private val gestures: SearchGestures,
    private val tokenFor: (SubmitMechanism) -> String?,
    private val snapshot: () -> ScreenModel?,
    private val tapTarget: (SubmitMechanism) -> ScreenElement?,
    private val sleeper: Sleeper = Sleeper.THREAD,
    private val waitAfterDispatchMs: Long = 500,
) {

    companion object {
        /** Profile default order: IME Enter, keyboard key, suggestion, button. */
        val DEFAULT_ORDER: List<SubmitMechanism> = listOf(
            SubmitMechanism.IME_ENTER,
            SubmitMechanism.KEYBOARD_ACTION_KEY,
            SubmitMechanism.SUGGESTION_TAP,
            SubmitMechanism.SEARCH_BUTTON,
        )
    }

    fun submit(
        query: String,
        mechanisms: List<SubmitMechanism>,
        before: ScreenModel,
    ): SubmitChainResult {
        val attempts = mutableListOf<SubmitAttempt>()
        for (mechanism in mechanisms) {
            val needsTarget = mechanism == SubmitMechanism.SUGGESTION_TAP ||
                mechanism == SubmitMechanism.SEARCH_BUTTON
            // Tap mechanisms resolve their target FIRST — no token is
            // ever requested for a tap that cannot run.
            if (needsTarget && safeTapTarget(mechanism) == null) {
                attempts += SubmitAttempt(
                    mechanism = mechanism,
                    dispatch = GestureAcceptance.Refused(
                        if (mechanism == SubmitMechanism.SUGGESTION_TAP) {
                            "No suggestion row on screen to tap."
                        } else {
                            "No on-screen search button to tap."
                        },
                    ),
                    verification = verifyNow(before, query),
                )
                continue
            }
            val token = tokenFor(mechanism)
            if (token == null) {
                // No token: David declined (or the gate is unavailable).
                // Stop — never keep asking through another mechanism.
                val attempt = SubmitAttempt(
                    mechanism = mechanism,
                    dispatch = GestureAcceptance.Refused("Not confirmed — no token for the submit."),
                    verification = SubmitVerification.NotExecuted(
                        "Not confirmed — I didn't run the search.",
                    ),
                )
                attempts += attempt
                return SubmitChainResult(attempt.verification, attempts)
            }
            val dispatch = when (mechanism) {
                SubmitMechanism.IME_ENTER -> safeSubmit(token)
                SubmitMechanism.KEYBOARD_ACTION_KEY -> safeSubmitViaKey(token)
                SubmitMechanism.SUGGESTION_TAP, SubmitMechanism.SEARCH_BUTTON -> {
                    // Re-resolve AFTER the token: the confirmation card
                    // may have taken seconds, and a stale element must
                    // never be tapped (TOCTOU).
                    val target = safeTapTarget(mechanism)
                    if (target == null) {
                        GestureAcceptance.Refused("The target left the screen before the tap.")
                    } else {
                        safeTap(target, token)
                    }
                }
            }
            sleeper.sleep(waitAfterDispatchMs)
            val verification = verifyNow(before, query)
            attempts += SubmitAttempt(mechanism, dispatch, verification)
            if (verification is SubmitVerification.Executed) {
                return SubmitChainResult(verification, attempts)
            }
        }
        val last = attempts.lastOrNull()?.verification ?: SubmitVerification.Unknown
        return SubmitChainResult(last, attempts)
    }

    /** Fresh snapshot + verifier judgement; Unknown when unreadable. */
    private fun verifyNow(before: ScreenModel, query: String): SubmitVerification {
        val after = try {
            snapshot()
        } catch (_: Exception) {
            null
        } ?: return SubmitVerification.Unknown
        return SubmitVerifier.verify(before, after, query)
    }

    private fun safeTapTarget(mechanism: SubmitMechanism): ScreenElement? = try {
        tapTarget(mechanism)
    } catch (_: Exception) {
        null
    }

    private fun safeSubmit(token: String?): GestureAcceptance = try {
        gestures.submit(token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "submit threw")
    }

    private fun safeSubmitViaKey(token: String?): GestureAcceptance = try {
        gestures.submitViaKey(token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "keyboard-key submit threw")
    }

    private fun safeTap(el: ScreenElement, token: String?): GestureAcceptance = try {
        gestures.tap(el, token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "tap threw")
    }
}
