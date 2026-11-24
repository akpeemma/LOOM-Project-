package com.loom.assistant.presentation.chat

import com.loom.assistant.core.common.CapabilityBridge
import com.loom.assistant.core.common.ModelAction

/**
 * Chat turn grounding (2026-11-06, blank-reply fix).
 *
 * Pure Kotlin, no Android imports, so the JVM harness proves the turn
 * rules without the app: every user message gets an answer, never a
 * blank bubble, and every reply that sends David to Settings carries
 * the button (the button itself renders in ChatScreenView.bubbleFor,
 * driven by [replyNeedsSettingsButton]).
 *
 * The defects this closes (device evidence, David Nov 6 08:04):
 * - "move to the corner of my screen" and "yesterday now" got blank
 *   reply bubbles. Mechanism: the model reply was an action block and
 *   nothing else, and the block failed validation (e.g. an
 *   ASSISTANT_MOVE with arg="CORNER" — no such region), so
 *   stripModelActions left an empty string, parseModelAction returned
 *   null, and the empty string was stored as the assistant message —
 *   which renders as a bubble with no text.
 * - The 'service bound' answer explained the term but gave no how-to
 *   and no button. [groundHelperAnswer] appends the live helper state
 *   and the concrete steps whenever David asks about the helper.
 */

/** The one plain sentence a failed turn gets — never a blank bubble. */
const val CHAT_FALLBACK_REPLY = "I couldn't answer that, try again."

/** How a finished engine turn resolves in typed chat. */
sealed class ChatTurnResolution {
    /** Store this text as the assistant message (never blank). */
    data class StoreText(val text: String) : ChatTurnResolution()
    /** Dispatch this parsed capability; its own ack is the turn's message. */
    data class DispatchAction(val action: ModelAction) : ChatTurnResolution()
}

/**
 * Resolve a raw engine reply into the turn's outcome. An action-only
 * reply whose block fails validation leaves no prose — that used to be
 * stored as a blank message; it is now the fallback sentence. A valid
 * block dispatches (the dispatch ack is the turn's only message, as
 * before). Ordinary prose stores as-is.
 */
fun resolveChatTurn(rawReply: String): ChatTurnResolution {
    val full = CapabilityBridge.stripModelActions(rawReply)
    val action = CapabilityBridge.parseModelAction(rawReply)
    return if (action != null) ChatTurnResolution.DispatchAction(action)
    else ChatTurnResolution.StoreText(full.ifBlank { CHAT_FALLBACK_REPLY })
}

/**
 * True when an assistant reply sends David to Settings — the reply must
 * carry the tappable "Open Accessibility settings" button underneath
 * it (ChatScreenView.bubbleFor). A message that sends him to Settings
 * without a button is a defect (David, Nov 6 09:24: "there's no such
 * button inside the Loom app").
 *
 * Covered replies (all the real ones that direct him there):
 * - "LOOM needs to be turned on in Settings, under Accessibility."
 *   (pointer show failure)
 * - "Enable it in Settings → Accessibility → LOOM, then try again."
 *   (app launch, helper down)
 * - "turn on LOOM in Settings → Accessibility → Device Control…"
 *   (assistant move, no screen to read)
 * - "open Settings to grant it for LOOM, then try again."
 *   (assistant activation, overlay permission missing)
 * - the helper grounding suffix in [groundHelperAnswer].
 *
 * The rule is deliberately narrow: the reply must name Settings AND an
 * action verb (open / turn / enable / switch). A reply that merely
 * mentions Settings ("Your settings look fine") gets no button.
 */
fun replyNeedsSettingsButton(text: String): Boolean {
    val t = text.lowercase()
    if ("settings" !in t) return false
    return "open" in t || "turn" in t || "enable" in t || "switch" in t
}

/**
 * Ground a helper/service question in live state. Only fires when David
 * asks about the helper ('what does this service bound represent',
 * "how do I turn on the helper", anything naming the accessibility
 * helper) — ordinary chat passes through untouched.
 *
 * The reply keeps the model's explanation and gains the live state plus
 * the concrete steps, so the answer is never generic and never a dead
 * end: the steps name "Settings, under Accessibility", which is exactly
 * the marker [replyNeedsSettingsButton] matches, so the button renders
 * under the reply. When the helper is already running there are no
 * steps — just the live state, so the answer stays short.
 */
fun groundHelperAnswer(
    userText: String,
    reply: String,
    helperOn: Boolean,
    helperRunning: Boolean,
): String {
    val t = userText.lowercase()
    val asksAboutHelper = "service bound" in t || "accessibility" in t ||
        "helper" in t
    if (!asksAboutHelper) return reply
    val state = when {
        !helperOn -> "Right now LOOM's helper is switched off."
        !helperRunning -> "Right now LOOM's helper is switched on but not running."
        else -> "Right now LOOM's helper is on and running."
    }
    val steps = when {
        !helperOn ->
            " To turn it on, open Settings, under Accessibility, and switch LOOM on."
        !helperRunning ->
            " To get it running, open Settings, under Accessibility, and turn LOOM off and on again."
        else -> ""
    }
    return "$reply\n\n$state$steps"
}
