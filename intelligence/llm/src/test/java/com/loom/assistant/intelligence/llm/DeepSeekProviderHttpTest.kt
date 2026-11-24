package com.loom.assistant.intelligence.llm

import android.content.Context
import org.json.JSONObject

/**
 * DeepSeek custom-provider fix (2026-11-10): proves the custom-provider path
 * works against a fake transport — chat via a DeepSeek-shaped
 * OpenAiCompatProvider, and verify() (the "Check & activate" path) with the
 * DeepSeek /models shapes. No network, no real keys: the test key is the
 * stub's fixed "test-key-123".
 *
 * Covers GAP 1 (verify() now uses the injectable transport + retry) and
 * GAP 2 (verify() maps 402 to the credits message).
 *
 * Run via test-harness/run-llm-tests.sh.
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
// CustomProviderDef: id, display name, base URL, removable, chat/test paths.
private val deepseek = OpenAiCompatProvider(
    id = "custom:deepseek",
    displayName = "DeepSeek",
    baseUrl = "https://api.deepseek.com/v1",
    removable = true,
    chatPath = "/chat/completions",
    testPath = "/models",
)

private const val MODELS_LIST = """{"object":"list","data":[{"id":"deepseek-chat","object":"model"},{"id":"deepseek-reasoner","object":"model"}]}"""
private const val MODELS_LIST_NO_CHAT = """{"object":"list","data":[{"id":"deepseek-reasoner","object":"model"}]}"""
private const val CHAT_REPLY = """{"choices":[{"message":{"role":"assistant","content":"hello from deepseek"}}]}"""

/** Scripted transport: answers from a queue, records every request. */
private class DeepSeekScriptedHttp(responses: List<Pair<Int, String>>) {
    private val queue = ArrayDeque(responses)
    var calls = 0
        private set
    val methods = mutableListOf<String>()
    val urls = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()
    val bodies = mutableListOf<JSONObject?>()
    val delays = mutableListOf<Long>()
    val fn: (String, String, Map<String, String>, JSONObject?) -> Pair<Int, String> =
        { method, url, hdrs, body ->
            calls++
            methods.add(method)
            urls.add(url)
            headers.add(hdrs)
            bodies.add(body)
            if (queue.isEmpty()) 200 to "ok" else queue.removeFirst()
        }
}

private fun newClient(scripted: DeepSeekScriptedHttp): ProviderHttp {
    val client = ProviderHttp(Context())
    client.httpCallFn = scripted.fn
    client.sleeper = { scripted.delays.add(it) }
    return client
}

fun main() {
    // ---- chat(): DeepSeek-shaped request + reply ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(200 to CHAT_REPLY))
        val result = newClient(scripted).chat(
            deepseek, "deepseek-chat",
            listOf(LlmMessage("user", "say hello")),
        )
        check("chat deepseek → Ok", result is LlmResult.Ok)
        checkEq("chat deepseek → reply text", (result as? LlmResult.Ok)?.text, "hello from deepseek")
        checkEq("chat deepseek → 1 call", scripted.calls, 1)
        checkEq("chat deepseek → POST", scripted.methods.firstOrNull(), "POST")
        checkEq("chat deepseek → URL", scripted.urls.firstOrNull(), "https://api.deepseek.com/v1/chat/completions")
        checkEq("chat deepseek → Bearer auth", scripted.headers.firstOrNull()?.get("Authorization"), "Bearer test-key-123")
        checkEq("chat deepseek → body model", scripted.bodies.firstOrNull()?.optString("model"), "deepseek-chat")
    }

    // ---- chat(): 402 → credits message (DeepSeek is pay-as-you-go) ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(402 to "insufficient balance"))
        val result = newClient(scripted).chat(deepseek, "deepseek-chat", listOf(LlmMessage("user", "hi")))
        checkEq("chat 402 → credits message", (result as? LlmResult.Err)?.message, "DeepSeek has run out of credits.")
        checkEq("chat 402 → 1 attempt", scripted.calls, 1)
    }

    // ---- verify(): DeepSeek /models with deepseek-chat → Ok, confirmed ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(200 to MODELS_LIST, 200 to MODELS_LIST))
        val result = newClient(scripted).verify(deepseek, "deepseek-chat")
        check("verify deepseek → Ok", result is LlmResult.Ok)
        checkEq(
            "verify deepseek → confirmed message",
            (result as? LlmResult.Ok)?.text,
            "Connected — key accepted by DeepSeek; model deepseek-chat confirmed available.",
        )
        checkEq("verify deepseek → 2 calls (probe + list)", scripted.calls, 2)
        checkEq("verify deepseek → probe GET", scripted.methods.firstOrNull(), "GET")
        checkEq("verify deepseek → probe URL", scripted.urls.firstOrNull(), "https://api.deepseek.com/v1/models")
        checkEq("verify deepseek → list URL", scripted.urls.getOrNull(1), "https://api.deepseek.com/v1/models")
    }

    // ---- verify(): 429 on the probe, then 200 → retried, Ok (GAP 1) ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(429 to "slow", 200 to MODELS_LIST, 200 to MODELS_LIST))
        val result = newClient(scripted).verify(deepseek, "deepseek-chat")
        check("verify 429→200 → Ok", result is LlmResult.Ok)
        checkEq("verify 429→200 → 3 calls", scripted.calls, 3)
        checkEq("verify 429→200 → one backoff of 1000ms", scripted.delays, listOf(1000L))
    }

    // ---- verify(): 402 on the probe → credits message, no retry (GAP 2) ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(402 to "insufficient balance"))
        val result = newClient(scripted).verify(deepseek, "deepseek-chat")
        val err = result as? LlmResult.Err
        check("verify 402 → Err", err != null)
        check("verify 402 → message names credits", err?.message?.contains("run out of credits") == true)
        checkEq("verify 402 → exactly 1 attempt", scripted.calls, 1)
    }

    // ---- verify(): 401 → authFailed ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(401 to "bad key"))
        val result = newClient(scripted).verify(deepseek, "deepseek-chat")
        val err = result as? LlmResult.Err
        check("verify 401 → Err", err != null)
        check("verify 401 → authFailed", err?.authFailed == true)
        checkEq("verify 401 → key message", err?.message, "The saved key was rejected — check the saved key.")
    }

    // ---- verify(): model list parses but lacks deepseek-chat → Err ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(200 to MODELS_LIST, 200 to MODELS_LIST_NO_CHAT))
        val result = newClient(scripted).verify(deepseek, "deepseek-chat")
        val err = result as? LlmResult.Err
        check("verify model absent → Err", err != null)
        check("verify model absent → names the model", err?.message?.contains("deepseek-chat") == true)
        check("verify model absent → lists what IS served", err?.message?.contains("deepseek-reasoner") == true)
    }

    // ---- verify(): model list unavailable (500) → lenient Ok, not confirmed ----
    run {
        val scripted = DeepSeekScriptedHttp(listOf(200 to MODELS_LIST, 500 to "error"))
        val result = newClient(scripted).verify(deepseek, "deepseek-chat")
        check("verify list 500 → Ok (lenient)", result is LlmResult.Ok)
        check("verify list 500 → says not confirmed", (result as? LlmResult.Ok)?.text?.contains("not confirmed") == true)
    }

    println("DeepSeekProviderHttpTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
