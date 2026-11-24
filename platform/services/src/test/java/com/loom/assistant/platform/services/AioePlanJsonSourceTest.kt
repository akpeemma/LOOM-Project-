package com.loom.assistant.platform.services

import com.loom.assistant.intelligence.llm.GeminiProvider
import com.loom.assistant.intelligence.llm.LlmResult

/**
 * AOIE Phase 5 — PlanJsonSource: the model proposes structured
 * steps; any failure returns "" and the planner falls back to
 * rules. LOCAL engine / no ACTIVE pair → "" with NO call and NO
 * spend. A provider error → "".
 *
 * The provider is a real GeminiProvider (concrete, no fake
 * needed); the chat function is injected, so no network and no
 * keys are touched.
 *
 * Run via test-harness/run-2c-tests.sh (runner: aioejson).
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

private val gemini = GeminiProvider()
private const val PLAN = """{"goal":"open YouTube Music","steps":[{"kind":"LAUNCH_APP","description":"open","params":{"app":"YouTube Music"}}]}"""

private fun source(
    config: PlanJsonSource.ModelConfig? = PlanJsonSource.ModelConfig(gemini, "gemini-2.0-flash-lite"),
    grounding: String = "",
    reply: String = PLAN,
    calls: MutableList<String>? = null,
    throwOnChat: Boolean = false,
) = PlanJsonSource(
    configProvider = { config },
    grounding = { grounding },
    chatFn = { _, _, messages ->
        calls?.add(messages.joinToString("|") { it.role + ":" + it.content })
        if (throwOnChat) throw RuntimeException("network down")
        LlmResult.Ok(reply)
    },
)

fun main() {
    // 1. Happy path: valid plan JSON returned, grounding included.
    run {
        val calls = mutableListOf<String>()
        val s = source(grounding = "Current screen: app=com.example", calls = calls)
        checkEq("happy path returns plan JSON", s.planJson("open YouTube Music"), PLAN)
        checkEq("one chat call", calls.size, 1)
        check("grounding sent to model", calls[0].contains("Current screen"))
    }
    // 2. No config (LOCAL engine / no ACTIVE pair) → "" and NO call.
    run {
        val calls = mutableListOf<String>()
        val s = source(config = null, calls = calls)
        checkEq("no config → empty", s.planJson("open YouTube Music"), "")
        checkEq("no config → no call, no spend", calls.size, 0)
    }
    // 3. Provider error → "" (planner falls back to rules).
    run {
        val s = PlanJsonSource(
            configProvider = { PlanJsonSource.ModelConfig(gemini, "gemini-2.0-flash-lite") },
            chatFn = { _, _, _ -> LlmResult.Err("key missing", authFailed = true) },
        )
        checkEq("provider error → empty", s.planJson("open YouTube Music"), "")
    }
    // 4. Chat throws → "".
    run {
        val s = source(throwOnChat = true)
        checkEq("chat throw → empty", s.planJson("open YouTube Music"), "")
    }
    // 5. Blank goal → "" with no call.
    run {
        val calls = mutableListOf<String>()
        val s = source(calls = calls)
        checkEq("blank goal → empty", s.planJson("   "), "")
        checkEq("blank goal → no call", calls.size, 0)
    }
    // 6. extractJsonObject: fences + prose stripped; no object → "".
    run {
        checkEq(
            "fenced JSON extracted",
            PlanJsonSource.extractJsonObject("Here is the plan:\n```json\n$PLAN\n```\nGood luck."),
            PLAN,
        )
        checkEq(
            "prose-wrapped JSON extracted",
            PlanJsonSource.extractJsonObject("Sure! $PLAN Done."),
            PLAN,
        )
        checkEq("no object → empty", PlanJsonSource.extractJsonObject("I cannot do that."), "")
        checkEq("unbalanced → empty", PlanJsonSource.extractJsonObject("""{"goal":"x","steps":["""), "")
        checkEq(
            "braces inside strings handled",
            PlanJsonSource.extractJsonObject("""{"goal":"a {b} c","steps":[]} trailing"""),
            """{"goal":"a {b} c","steps":[]}""",
        )
    }
    // 7. Grounding failure → still plans (empty grounding).
    run {
        val s = PlanJsonSource(
            configProvider = { PlanJsonSource.ModelConfig(gemini, "gemini-2.0-flash-lite") },
            grounding = { throw RuntimeException("observer down") },
            chatFn = { _, _, _ -> LlmResult.Ok(PLAN) },
        )
        checkEq("grounding failure still plans", s.planJson("open YouTube Music"), PLAN)
    }
    // 8. Config provider throwing → "" with no call.
    run {
        val calls = mutableListOf<String>()
        val s = PlanJsonSource(
            configProvider = { throw RuntimeException("prefs broken") },
            chatFn = { _, _, _ ->
                calls.add("called")
                LlmResult.Ok(PLAN)
            },
        )
        checkEq("config throw → empty", s.planJson("open YouTube Music"), "")
        checkEq("config throw → no call", calls.size, 0)
    }

    println("AioePlanJsonSourceTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILED: $failed")
}
