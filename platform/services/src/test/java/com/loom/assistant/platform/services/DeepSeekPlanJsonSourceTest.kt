package com.loom.assistant.platform.services

import com.loom.assistant.intelligence.llm.AuthKind
import com.loom.assistant.intelligence.llm.LlmResult
import com.loom.assistant.intelligence.llm.OpenAiCompatProvider

/**
 * DeepSeek custom-provider fix (2026-11-10): proves the planner's model-plan
 * source carries the ACTIVE provider — including a custom DeepSeek provider —
 * through to the chat call. The configProvider here returns exactly what
 * PlanJsonSource.forProduction builds from the ACTIVE pair (a ModelConfig
 * holding the resolved provider + model api id); the chat function is
 * injected and records what it receives, so no network and no keys.
 *
 * The forProduction resolution itself (providerById/modelsFor on the active
 * pair) is proven by DeepSeekModelSelectionTest; this test proves the plan
 * source uses whatever provider that resolution returns.
 *
 * Run via test-harness/run-2c-tests.sh (runner: deepseekjson).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) passed++ else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passed++ else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

// Built exactly the way ModelSelection.allProviders() builds one from a
// CustomProviderDef.
private val deepseek = OpenAiCompatProvider(
    id = "custom:deepseek",
    displayName = "DeepSeek",
    baseUrl = "https://api.deepseek.com/v1",
    removable = true,
    chatPath = "/chat/completions",
    testPath = "/models",
)

private const val PLAN = """{"goal":"open YouTube","steps":[{"kind":"LAUNCH_APP","description":"open","params":{"app":"YouTube"}}]}"""

private fun source(
    config: PlanJsonSource.ModelConfig? = PlanJsonSource.ModelConfig(deepseek, "deepseek-chat"),
    reply: String = PLAN,
    seen: MutableList<Triple<String, String, String>>? = null,
    throwOnChat: Boolean = false,
) = PlanJsonSource(
    configProvider = { config },
    grounding = { "Current screen: app=com.google.android.youtube" },
    chatFn = { provider, modelApiId, _ ->
        seen?.add(Triple(provider.id, modelApiId, provider.baseUrl))
        if (throwOnChat) throw RuntimeException("network down")
        LlmResult.Ok(reply)
    },
)

fun main() {
    // 1. Happy path: the plan source calls the DeepSeek provider with deepseek-chat.
    run {
        val seen = mutableListOf<Triple<String, String, String>>()
        val s = source(seen = seen)
        checkEq("plan via DeepSeek returns plan JSON", s.planJson("open YouTube"), PLAN)
        checkEq("one chat call", seen.size, 1)
        checkEq("chat went to the custom provider id", seen.firstOrNull()?.first, "custom:deepseek")
        checkEq("chat used deepseek-chat", seen.firstOrNull()?.second, "deepseek-chat")
        checkEq("chat went to the DeepSeek base URL", seen.firstOrNull()?.third, "https://api.deepseek.com/v1")
    }
    // 2. No ACTIVE pair → "" with no call.
    run {
        val seen = mutableListOf<Triple<String, String, String>>()
        val s = source(config = null, seen = seen)
        checkEq("no config → empty plan", s.planJson("open YouTube"), "")
        checkEq("no config → no call", seen.size, 0)
    }
    // 3. Chat throws → "" (rule fallback).
    run {
        val s = source(throwOnChat = true)
        checkEq("chat throws → empty plan", s.planJson("open YouTube"), "")
    }
    // 4. Non-JSON reply → "" (extractJsonObject finds no object).
    run {
        val s = source(reply = "not json at all")
        checkEq("non-JSON reply → empty plan", s.planJson("open YouTube"), "")
    }
    // 5. A different custom provider id is carried just the same (provider-agnostic).
    run {
        val other = OpenAiCompatProvider(
            id = "custom:other",
            displayName = "Other",
            baseUrl = "https://example.invalid/v1",
            removable = true,
            chatPath = "/chat/completions",
            testPath = "/models",
        )
        val seen = mutableListOf<Triple<String, String, String>>()
        val s = source(config = PlanJsonSource.ModelConfig(other, "other-model"), seen = seen)
        checkEq("other custom provider → plan JSON", s.planJson("open YouTube"), PLAN)
        checkEq("other provider id carried", seen.firstOrNull()?.first, "custom:other")
        checkEq("other model carried", seen.firstOrNull()?.second, "other-model")
    }

    println("DeepSeekPlanJsonSourceTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
