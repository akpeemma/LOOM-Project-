package com.loom.assistant.platform.services

import android.content.Context
import com.loom.assistant.core.common.CapabilityPrompt
import com.loom.assistant.core.common.PlanJsonPrompt
import com.loom.assistant.intelligence.llm.EngineMode
import com.loom.assistant.intelligence.llm.LlmMessage
import com.loom.assistant.intelligence.llm.LlmProvider
import com.loom.assistant.intelligence.llm.LlmResult
import com.loom.assistant.intelligence.llm.ModelSelection
import com.loom.assistant.intelligence.llm.ProviderHttp

/**
 * AOIE Phase 5 — the PlanJsonSource adapter (audit §C.5 M2).
 *
 * The `(goal: String) -> String` the planner calls first, before
 * its rule-based decomposition. The model PROPOSES structured
 * steps; the deterministic layer disposes (PlanValidator, then
 * dispatch-time verification). Any failure here returns "" and the
 * planner falls back to rules silently — never a raise, never a
 * user-facing error for a planning miss.
 *
 * Cost guard (David's order): the adapter reuses the CURRENT model
 * tier and provider path (the validated ACTIVE pair — any provider,
 * including a custom one such as DeepSeek) — it never selects a
 * stronger model and never spends
 * outside the one planning call per plan task. When the engine is
 * LOCAL or no ACTIVE pair is validated, it returns "" immediately:
 * no call, no spend, no error. Max 512 tokens for the plan reply.
 *
 * Grounding: [grounding] supplies the compact redacted screen
 * snapshot at plan time (package, screen kind, affordances,
 * focused field, keyboard state + the resolved task-app package),
 * built by PlanWiring from its ScreenObserver. Without it the
 * model would guess targets blind; with it the proposal is
 * grounded in the live screen. The grounding string never carries
 * element text beyond the focused field's hint, and never
 * passwords or sensitive-app content (the snapshot layer already
 * redacts those).
 *
 * Testability: the primary constructor takes a [ModelConfig]
 * provider + a chat function, so the harness can drive it with no
 * Android Context and no network; [forProduction] wires the real
 * ModelSelection + ProviderHttp.
 */
class PlanJsonSource(
    private val configProvider: () -> ModelConfig?,
    private val grounding: () -> String = { "" },
    private val chatFn: (provider: LlmProvider, modelApiId: String, messages: List<LlmMessage>) -> LlmResult,
) {

    /** The validated model to plan with (provider + API model id). */
    data class ModelConfig(val provider: LlmProvider, val modelApiId: String)

    companion object {
        /** Small reply budget: a plan is steps, not prose. */
        const val MAX_TOKENS = 512

        /**
         * Extracts the first JSON object from a model reply,
         * stripping ```json fences and surrounding prose. The
         * validator's parser rejects trailing characters, so
         * extraction must happen here. Returns "" when no balanced
         * object is found. Brace-aware inside strings.
         */
        fun extractJsonObject(reply: String): String {
            val start = reply.indexOf('{')
            if (start < 0) return ""
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until reply.length) {
                val c = reply[i]
                if (inString) {
                    if (escaped) escaped = false
                    else when (c) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return reply.substring(start, i + 1)
                    }
                }
            }
            return ""
        }

        /** Production wiring: the current-tier ACTIVE pair, or null
         *  (→ "") when the engine is LOCAL or nothing is validated. */
        fun forProduction(
            context: Context,
            grounding: () -> String = { "" },
        ): PlanJsonSource {
            val appContext = context.applicationContext ?: context
            val selection = ModelSelection(appContext)
            return PlanJsonSource(
                configProvider = {
                    if (selection.engineMode != EngineMode.CLOUD) null
                    else {
                        val pid = selection.activeProviderId ?: return@PlanJsonSource null
                        val mid = selection.activeModelId ?: return@PlanJsonSource null
                        val provider = selection.providerById(pid) ?: return@PlanJsonSource null
                        val model = selection.modelsFor(pid).firstOrNull { it.id == mid }
                            ?: return@PlanJsonSource null
                        ModelConfig(provider, model.apiModelId)
                    }
                },
                grounding = grounding,
                chatFn = { provider, modelApiId, messages ->
                    ProviderHttp(appContext).chat(provider, modelApiId, messages, MAX_TOKENS)
                },
            )
        }
    }

    /** Returns raw plan JSON, or "" on any failure (→ rule fallback). */
    fun planJson(goal: String): String {
        if (goal.isBlank()) return ""
        // No cloud engine or no validated model: no call, no spend.
        val config = try {
            configProvider()
        } catch (_: Exception) {
            null
        } ?: return ""
        val ground = try {
            grounding()
        } catch (_: Exception) {
            ""
        }
        val system = PlanJsonPrompt.text +
            (if (ground.isNotBlank()) "\n\nLive screen (grounding — what is actually showing now):\n$ground" else "") +
            "\n\n" + CapabilityPrompt.text
        val messages = listOf(
            LlmMessage("system", system),
            LlmMessage("user", goal),
        )
        val result = try {
            chatFn(config.provider, config.modelApiId, messages)
        } catch (_: Exception) {
            return ""
        }
        return when (result) {
            is LlmResult.Ok -> extractJsonObject(result.text)
            is LlmResult.Err -> ""
        }
    }
}
