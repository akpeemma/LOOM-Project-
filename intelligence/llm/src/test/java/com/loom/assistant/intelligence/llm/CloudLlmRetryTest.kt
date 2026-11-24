package com.loom.assistant.intelligence.llm

import android.content.Context
import org.json.JSONObject

/**
 * Accessibility fix (2026-11-05, §7.3): JVM checks for the 503 retry +
 * plain error. The Nov 5 transcript showed the raw first-attempt error
 * ("Cloud error: Google is temporarily overloaded (503)…") in chat and
 * aloud. ProviderHttp.chat() now retries 429/503 with backoff and the
 * final error is one plain sentence with no HTTP code.
 *
 * Compiles the REAL CloudLlmClient.kt against the android.content fake
 * plus a test-local SecureKeyStore stub (fixed test key) and the real
 * org.json jar (android.jar's org.json is a runtime stub that throws).
 * The HTTP transport and the retry sleeper are injected, so retry
 * counts and delays are provable without the network.
 *
 * Plain-main() pattern — see test-harness/run-llm-tests.sh.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected true, got false")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private val NO_CODE = Regex("\\(\\d{3}\\)|HTTP\\s*\\d{3}|\\b\\d{3}\\b")

private fun hasNoHttpCode(text: String): Boolean = !NO_CODE.containsMatchIn(text)

/** A minimal provider: Google-shaped, scripted replies. */
private class FakeProvider(
    override val displayName: String = "Google",
) : LlmProvider {
    override val id: String = "google"
    override val baseUrl: String = "https://example.invalid/v1beta"
    override val removable: Boolean = false
    override val authKind: AuthKind = AuthKind.QUERY_PARAM
    override fun chatPath(modelApiId: String): String = "/models/$modelApiId:generateContent"
    override fun testPath(): String = "/models"
    override fun buildChatBody(modelApiId: String, messages: List<LlmMessage>, maxTokens: Int): JSONObject =
        JSONObject().put("model", modelApiId)
    override fun parseChatReply(body: String): String? =
        if (body.contains("ok")) "hello there" else null
    override fun testOk(code: Int, body: String): Boolean = code == 200
    override fun availableModelIds(body: String): List<String> = emptyList()
}

/** Scripted transport: answers from a queue, records attempts + delays. */
private class ScriptedHttp(responses: List<Pair<Int, String>>) {
    private val queue = ArrayDeque(responses)
    var calls = 0
        private set
    val delays = mutableListOf<Long>()
    val fn: (String, String, Map<String, String>, JSONObject?) -> Pair<Int, String> =
        { _, _, _, _ ->
            calls++
            if (queue.isEmpty()) 200 to "ok" else queue.removeFirst()
        }
}

private fun newClient(scripted: ScriptedHttp): ProviderHttp {
    val client = ProviderHttp(Context())
    client.httpCallFn = scripted.fn
    client.sleeper = { scripted.delays.add(it) }
    return client
}

private fun chat(client: ProviderHttp, provider: LlmProvider = FakeProvider()): LlmResult =
    client.chat(provider, "gemini-2.0-flash", listOf(LlmMessage("user", "hi")))

fun main() {
    // ---- retryable statuses + backoff (pure units) ----
    check("429 is retryable", isRetryableHttpStatus(429))
    check("503 is retryable", isRetryableHttpStatus(503))
    check("400 is not retryable", !isRetryableHttpStatus(400))
    check("401 is not retryable", !isRetryableHttpStatus(401))
    check("404 is not retryable", !isRetryableHttpStatus(404))
    check(
        "500 is NOT retryable — nothing treats it as transient",
        !isRetryableHttpStatus(500),
    )
    checkEq("backoff attempt 0 → 1000ms", retryDelayMs(0), 1000L)
    checkEq("backoff attempt 1 → 2000ms", retryDelayMs(1), 2000L)
    checkEq("backoff never exceeds 2000ms", retryDelayMs(9), 2000L)

    // ---- callWithRetry directly: counts + delays ----
    var directCalls = 0
    val directDelays = mutableListOf<Long>()
    val directResult = callWithRetry(
        call = {
            directCalls++
            if (directCalls < 3) 503 to "overloaded" else 200 to "ok"
        },
        sleeper = { directDelays.add(it) },
    )
    checkEq("callWithRetry: 503,503,200 → Ok", directResult, 200 to "ok")
    checkEq("callWithRetry: 3 attempts", directCalls, 3)
    checkEq("callWithRetry: delays 1000,2000", directDelays, listOf(1000L, 2000L))

    // ---- chat(): 503 then 200 → recovered, retried once ----
    var scripted = ScriptedHttp(listOf(503 to "overloaded", 200 to "ok"))
    var result = chat(newClient(scripted))
    check("503 then 200 → Ok", result is LlmResult.Ok)
    checkEq(
        "503 then 200 → reply text",
        (result as? LlmResult.Ok)?.text,
        "hello there",
    )
    checkEq("503 then 200 → 2 attempts", scripted.calls, 2)
    checkEq("503 then 200 → one backoff of 1000ms", scripted.delays, listOf(1000L))

    // ---- chat(): 429 then 200 → recovered, retried once ----
    scripted = ScriptedHttp(listOf(429 to "slow down", 200 to "ok"))
    result = chat(newClient(scripted))
    check("429 then 200 → Ok", result is LlmResult.Ok)
    checkEq("429 then 200 → 2 attempts", scripted.calls, 2)

    // ---- chat(): 503 x3 → plain error, 3 attempts, delays 1000+2000 ----
    scripted = ScriptedHttp(listOf(503 to "overloaded", 503 to "overloaded", 503 to "overloaded"))
    result = chat(newClient(scripted))
    val err503 = result as? LlmResult.Err
    check("503 x3 → Err", err503 != null)
    checkEq(
        "503 x3 → exact plain sentence",
        err503?.message,
        "Google is busy right now — I'll try again in a moment.",
    )
    check("503 x3 → message has no HTTP code", err503?.message?.let(::hasNoHttpCode) == true)
    check("503 x3 → technical detail kept out of the message", err503?.detail?.contains("503") == true)
    checkEq("503 x3 → 3 attempts, then stops", scripted.calls, 3)
    checkEq("503 x3 → delays 1000,2000", scripted.delays, listOf(1000L, 2000L))

    // ---- chat(): 429 x3 → same plain sentence ----
    scripted = ScriptedHttp(listOf(429 to "slow", 429 to "slow", 429 to "slow"))
    result = chat(newClient(scripted))
    val err429 = result as? LlmResult.Err
    checkEq(
        "429 x3 → exact plain sentence",
        err429?.message,
        "Google is busy right now — I'll try again in a moment.",
    )
    checkEq("429 x3 → 3 attempts", scripted.calls, 3)

    // ---- chat(): non-retryable statuses fail at once, code-free ----
    scripted = ScriptedHttp(listOf(400 to "bad request"))
    result = chat(newClient(scripted))
    check("400 → Err", result is LlmResult.Err)
    checkEq("400 → exactly 1 attempt", scripted.calls, 1)
    check("400 → message has no HTTP code", (result as LlmResult.Err).message.let(::hasNoHttpCode))
    checkEq("400 → plain message", (result as LlmResult.Err).message, "Google rejected the request.")

    scripted = ScriptedHttp(listOf(500 to "server error"))
    result = chat(newClient(scripted))
    check("500 → Err", result is LlmResult.Err)
    checkEq("500 → exactly 1 attempt (not retried)", scripted.calls, 1)
    check("500 → message has no HTTP code", (result as LlmResult.Err).message.let(::hasNoHttpCode))

    scripted = ScriptedHttp(listOf(404 to "no such model"))
    result = chat(newClient(scripted))
    checkEq(
        "404 → plain message, no code",
        (result as? LlmResult.Err)?.message,
        "Model gemini-2.0-flash was not found on Google.",
    )

    // ---- chat(): 200 with an unreadable body → retried ONCE, then a
    // plain sentence (device-test failures 2026-11-11, D-03). A plain
    // question must get a plain answer — never "unreadable reply".
    scripted = ScriptedHttp(listOf(200 to "not-json-at-all", 200 to "still-not-json"))
    result = chat(newClient(scripted))
    check("200 unreadable x2 → Err", result is LlmResult.Err)
    checkEq("200 unreadable x2 → exactly 2 attempts", scripted.calls, 2)
    checkEq(
        "200 unreadable x2 → plain message, no provider name, no 'unreadable'",
        (result as? LlmResult.Err)?.message,
        "I couldn't make sense of that reply — please say it again.",
    )
    check(
        "200 unreadable x2 → message names no provider",
        (result as? LlmResult.Err)?.message?.contains("Google") != true,
    )

    // ---- chat(): 200 unreadable, retry parses → recovered ----
    scripted = ScriptedHttp(listOf(200 to "not-json-at-all", 200 to "ok"))
    result = chat(newClient(scripted))
    check("200 unreadable then ok → Ok", result is LlmResult.Ok)
    checkEq("200 unreadable then ok → 2 attempts", scripted.calls, 2)
    checkEq(
        "200 unreadable then ok → reply text",
        (result as? LlmResult.Ok)?.text,
        "hello there",
    )

    // ---- chatErrorFor mapping directly ----
    val err401 = chatErrorFor(401, "OpenRouter", "m", "")
    check("401 → authFailed", err401.authFailed)
    check("401 → message has no HTTP code", hasNoHttpCode(err401.message))
    checkEq(
        "402 → plain message",
        chatErrorFor(402, "OpenRouter", "m", "").message,
        "OpenRouter has run out of credits.",
    )

    println("CloudLlmRetryTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
