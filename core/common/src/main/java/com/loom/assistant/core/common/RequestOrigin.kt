package com.loom.assistant.core.common

/**
 * Provenance of a capability request — who actually asked for the action.
 *
 * Carried from the entry point into [com.loom.assistant.intelligence.authgate.AuthGate]
 * so the gate can tell a command David spoke/typed himself apart from an
 * action the model proposed in a reply:
 *
 * - [DIRECT_USER]: matched by [VoiceCommands.match] (voice) or resolved by
 *   `CapabilityBridge.resolveChatInput` (typed chat) — David's own words,
 *   never model output.
 * - [MODEL]: parsed by `CapabilityBridge.parseModelAction` from a model
 *   reply and dispatched by `dispatchModelAction` / `dispatchChatCommand`
 *   with a model arg — the model proposed it; David has not (yet) endorsed
 *   this particular action.
 *
 * Lives in core:common (no Android imports) so every entry point —
 * interaction:voice, presentation:chat/home, platform:services — can
 * thread it without a module cycle. The default everywhere is [MODEL]:
 * an untagged request is always treated as model-proposed (card shown),
 * never as a direct user command.
 *
 * Grants (scoped "always allow" approvals) are honoured ONLY for
 * [DIRECT_USER]; a MODEL request with a stored grant still gets its card.
 */
enum class RequestOrigin {
    DIRECT_USER,
    MODEL,
}
