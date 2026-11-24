package com.loom.assistant.intelligence.llm

import android.content.Context
import com.loom.assistant.platform.storage.SecureKeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 1 correction — provider-independent architecture.
 * Ported from Brahma's working Python clients (or_client.py, llm_client.py),
 * restructured so providers are PLUGGABLE, not hardcoded:
 *
 *   LlmProvider (interface — auth, endpoints, request format, response parser)
 *     ├─ OpenAiCompatProvider : OpenRouter + ANY custom OpenAI-style provider
 *     │                         (configurable base URL, paths, model ids)
 *     └─ GeminiProvider       : different-protocol adapter (query-param auth,
 *                               custom body/shape) — proves the interface fits
 *                               non-OpenAI protocols too.
 *
 * Adding a provider = one class + one registry entry. The chat engine never
 * changes. Each provider keeps its OWN key (loom_api_key_<providerId>).
 *
 * RULES: keys live in memory only for the call; NEVER logged (zero
 * android.util.Log calls in the dex), redacted from error text. No background
 * calls — invoked only from explicit user actions (send / validate).
 */

data class LlmMessage(val role: String, val content: String)

sealed class LlmResult {
    data class Ok(val text: String) : LlmResult()
    /**
     * Human-readable, key-free. [message] is the ONE plain sentence the
     * user sees — never an HTTP code. [detail] carries the technical
     * detail (code, provider, body snippet) for diagnostics only; it
     * is never displayed or spoken.
     */
    data class Err(
        val message: String,
        val authFailed: Boolean = false,
        val detail: String? = null,
    ) : LlmResult()
}

enum class AuthKind { BEARER_HEADER, QUERY_PARAM }

interface LlmProvider {
    val id: String
    val displayName: String
    val baseUrl: String
    val authKind: AuthKind
    /** false for built-ins (Google, OpenRouter); true for user-added providers. */
    val removable: Boolean
    fun chatPath(modelApiId: String): String
    fun testPath(): String
    /** Endpoint that proves the KEY (cheap, no reply). Defaults to the model list. */
    fun verifyPath(): String = testPath()
    /** Endpoint that lists served model ids. */
    fun modelsPath(): String = testPath()
    /**
     * Parse a model-list response into served model ids. A key that passes but
     * names a retired/unknown model must NOT activate — this is what enforces it.
     */
    fun availableModelIds(body: String): List<String>
    /** True for providers whose list shape is known — an unreadable list fails validation. */
    val strictModelList: Boolean get() = false
    fun buildChatBody(modelApiId: String, messages: List<LlmMessage>, maxTokens: Int): JSONObject
    /** Returns the reply text, or null when the body is unparseable. */
    fun parseChatReply(body: String): String?
    fun testOk(code: Int, @Suppress("UNUSED_PARAMETER") body: String): Boolean
    fun keyMissingMessage(): String =
        "No $displayName API key is saved. Add one under Settings → AI Connections."
}

/** OpenAI-compatible chat API: OpenRouter and any custom endpoint. */
open class OpenAiCompatProvider(
    override val id: String,
    override val displayName: String,
    override val baseUrl: String,
    override val removable: Boolean = false,
    private val chatPath: String = "/chat/completions",
    private val testPath: String = "/models",
) : LlmProvider {
    override val authKind: AuthKind = AuthKind.BEARER_HEADER
    override fun chatPath(modelApiId: String): String = chatPath
    override fun testPath(): String = testPath
    override fun buildChatBody(modelApiId: String, messages: List<LlmMessage>, maxTokens: Int) =
        JSONObject().apply {
            put("model", modelApiId)
            put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                for (m in messages) put(JSONObject().put("role", m.role).put("content", m.content))
            })
        }
    override fun parseChatReply(body: String): String? = try {
        val message = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message")
        // Device-test failures (2026-11-11, D-03): a reply whose
        // message content is empty/null (a reasoning-only turn, a
        // truncated body) is not a dead end when the completions
        // shape carries text instead — take it before giving up.
        message.optString("content", "").trim().takeIf { it.isNotEmpty() }
            ?: JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .optString("text", "").trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
    override fun testOk(code: Int, body: String): Boolean = code == 200
    override fun availableModelIds(body: String): List<String> = try {
        val arr = JSONObject(body).getJSONArray("data")
        (0 until arr.length())
            .map { arr.getJSONObject(it).optString("id", "") }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}

/** OpenRouter: /models is public, so the key is proven at /auth/key instead. */
class OpenRouterProvider : OpenAiCompatProvider(
    id = "openrouter",
    displayName = "OpenRouter",
    baseUrl = "https://openrouter.ai/api/v1",
) {
    override fun verifyPath(): String = "/auth/key"
    override val strictModelList: Boolean = true
}

/** Gemini: different protocol (query-param auth, custom paths/body). */
class GeminiProvider : LlmProvider {
    override val id: String = "google"
    override val displayName: String = "Google"
    override val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
    override val authKind: AuthKind = AuthKind.QUERY_PARAM
    override val removable: Boolean = false
    override fun chatPath(modelApiId: String): String = "/models/$modelApiId:generateContent"
    override fun testPath(): String = "/models"
    override fun buildChatBody(modelApiId: String, messages: List<LlmMessage>, maxTokens: Int) =
        JSONObject().apply {
            val contents = JSONArray()
            var pendingSystem: String? = null
            for (m in messages) {
                when (m.role) {
                    "system" -> pendingSystem =
                        listOfNotNull(pendingSystem, m.content).joinToString("\n")
                    else -> {
                        var text = m.content
                        if (pendingSystem != null && m.role == "user") {
                            text = "$pendingSystem\n\n$text"
                            pendingSystem = null
                        }
                        contents.put(
                            JSONObject().put("role", if (m.role == "assistant") "model" else "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", text))),
                        )
                    }
                }
            }
            put("contents", contents)
            put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        }
    override fun parseChatReply(body: String): String? = try {
        val parts = JSONObject(body).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        sb.toString().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
    override fun testOk(code: Int, body: String): Boolean = code == 200
    override val strictModelList: Boolean = true
    override fun availableModelIds(body: String): List<String> = try {
        val arr = JSONObject(body).getJSONArray("models")
        (0 until arr.length())
            .map { arr.getJSONObject(it).optString("name", "").removePrefix("models/") }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000

/** Per-provider key lookup. Null = not configured. Credential isolation: the alias
 *  embeds the provider id, so switching providers can never mix credentials. */
internal fun providerKey(context: Context, providerId: String): String? {
    return try {
        SecureKeyStore.get(context.applicationContext)
            .get(SecureKeyStore.apiKeyAlias(providerId))?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

internal fun redactKey(text: String, key: String): String =
    if (key.isNotEmpty()) text.replace(key, "•••") else text

internal fun httpCall(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: JSONObject?,
): Pair<Int, String> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        if (body != null) {
            doOutput = true
            for ((k, v) in headers) setRequestProperty(k, v)
        } else {
            for ((k, v) in headers) setRequestProperty(k, v)
        }
    }
    return try {
        body?.let { conn.outputStream.use { os -> os.write(it.toString().toByteArray(Charsets.UTF_8)) } }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        code to (stream?.bufferedReader()?.readText().orEmpty())
    } finally {
        conn.disconnect()
    }
}

/**
 * Accessibility fix (2026-11-05, §7.3): bounded retry for the chat call.
 * At most [MAX_CHAT_ATTEMPTS] attempts in total (first try + retries).
 */
internal const val MAX_CHAT_ATTEMPTS = 3

/**
 * Statuses worth retrying: provider overload (503) and rate limit (429).
 * 500 is NOT retried — nothing in the codebase treats it as transient;
 * it fails at once like any other non-retryable status.
 */
internal fun isRetryableHttpStatus(code: Int): Boolean =
    code == 429 || code == 503

/** Backoff between attempts: 1s, then 2s. Bounded — never grows past that. */
internal fun retryDelayMs(attempt: Int): Long =
    (1000L shl attempt.coerceIn(0, 1))

/**
 * One attempt plus bounded retry with backoff on [isRetryableHttpStatus].
 * Returns the last (code, body) — the caller's error mapping decides what
 * the user sees. Exceptions (timeouts, IO) are NOT retried: they
 * propagate to the caller's catch, exactly as a single attempt would.
 * Pure Kotlin over an injected [call], so the harness proves the retry
 * counts and delays without the network.
 */
internal fun callWithRetry(
    call: () -> Pair<Int, String>,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
    maxAttempts: Int = MAX_CHAT_ATTEMPTS,
): Pair<Int, String> {
    var last: Pair<Int, String> = 0 to ""
    repeat(maxAttempts) { attempt ->
        last = call()
        if (!isRetryableHttpStatus(last.first) || attempt == maxAttempts - 1) {
            return last
        }
        sleeper(retryDelayMs(attempt))
    }
    return last
}

/**
 * Map a final (non-retried, or retries-exhausted) chat HTTP status to the
 * user-facing result. The message is ONE plain sentence with no HTTP
 * code — the Nov 5 503 read as "Cloud error: Google is temporarily
 * overloaded (503)…" in chat and aloud. The technical detail (code,
 * provider, body snippet) rides in [LlmResult.Err.detail], never in
 * the message. 429 and 503 share the agreed plain sentence: the
 * provider is busy, LOOM will try again in a moment.
 */
internal fun chatErrorFor(
    code: Int,
    displayName: String,
    modelApiId: String,
    safeBody: String,
): LlmResult.Err = when (code) {
    400 -> LlmResult.Err(
        "$displayName rejected the request.",
        detail = "HTTP 400: ${safeBody.take(200)}",
    )
    401, 403 -> LlmResult.Err(
        "$displayName rejected the saved key — check the saved key.",
        authFailed = true,
        detail = "HTTP $code",
    )
    402 -> LlmResult.Err(
        "$displayName has run out of credits.",
        detail = "HTTP 402",
    )
    404 -> LlmResult.Err(
        "Model $modelApiId was not found on $displayName.",
        detail = "HTTP 404: $modelApiId",
    )
    429, 503 -> LlmResult.Err(
        "$displayName is busy right now — I'll try again in a moment.",
        detail = "HTTP $code (retries exhausted)",
    )
    else -> LlmResult.Err(
        "$displayName returned an unexpected error.",
        detail = "HTTP $code: ${safeBody.take(220)}",
    )
}

/** Shared HTTP plumbing for every provider. */
class ProviderHttp(private val appContext: Context) {

    /**
     * Test seams: the HTTP transport and the retry sleeper. Production
     * uses the real [httpCall] + Thread.sleep; the harness injects a
     * scripted transport and a recording sleeper so retry/backoff is
     * provable without the network.
     */
    internal var httpCallFn:
        (String, String, Map<String, String>, JSONObject?) -> Pair<Int, String> = ::httpCall
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    private fun urlFor(provider: LlmProvider, path: String, key: String): String {
        val base = provider.baseUrl.trimEnd('/')
        return when (provider.authKind) {
            AuthKind.BEARER_HEADER -> base + path
            AuthKind.QUERY_PARAM -> base + path + if ("?" in path) "&key=$key" else "?key=$key"
        }
    }

    private fun headersFor(provider: LlmProvider, key: String): Map<String, String> {
        val h = mutableMapOf("Content-Type" to "application/json")
        if (provider.authKind == AuthKind.BEARER_HEADER) {
            h["Authorization"] = "Bearer $key"
            h["X-Title"] = "LOOM"
        }
        return h
    }

    fun chat(
        provider: LlmProvider,
        modelApiId: String,
        messages: List<LlmMessage>,
        maxTokens: Int = 1024,
    ): LlmResult {
        val key = providerKey(appContext, provider.id)
            ?: return LlmResult.Err(provider.keyMissingMessage(), authFailed = true)
        val url = urlFor(provider, provider.chatPath(modelApiId), key)
        val body = provider.buildChatBody(modelApiId, messages, maxTokens)
        return try {
            // Accessibility fix (2026-11-05, §7.3): a transient overload
            // is retried with backoff inside chat() — the Nov 5 503
            // reached David as a raw error on the FIRST attempt. Only
            // 429/503 retry; 400/401/403/404/500 fail at once.
            val (code, text) = callWithRetry(
                call = {
                    httpCallFn("POST", url, headersFor(provider, key), body)
                },
                sleeper = sleeper,
            )
            val safe = redactKey(text, key)
            when (code) {
                200 -> {
                    val reply = provider.parseChatReply(safe)
                        ?: run {
                            // Device-test failures (2026-11-11, D-03):
                            // a 200 whose body does not parse is
                            // retried ONCE (a transient empty/reasoning-
                            // only turn), and if it still does not parse
                            // the user gets one plain sentence — never
                            // "unreadable reply" as the answer. A plain
                            // question must get a plain answer.
                            val (retryCode, retryText) = callWithRetry(
                                call = {
                                    httpCallFn("POST", url, headersFor(provider, key), body)
                                },
                                sleeper = sleeper,
                            )
                            if (retryCode == 200) {
                                provider.parseChatReply(redactKey(retryText, key))
                            } else {
                                null
                            }
                        }
                    if (reply == null) {
                        LlmResult.Err(
                            "I couldn't make sense of that reply — please say it again.",
                            detail = safe.take(200),
                        )
                    } else LlmResult.Ok(reply)
                }
                else -> chatErrorFor(code, provider.displayName, modelApiId, safe)
            }
        } catch (e: java.net.SocketTimeoutException) {
            LlmResult.Err("${provider.displayName} connection timed out — check your network and retry.")
        } catch (_: Exception) {
            // Blank-reply fix (2026-11-06): the raw exception text can
            // carry hostnames and technical detail — the user gets one
            // plain sentence, never the raw error.
            LlmResult.Err("Couldn't reach ${provider.displayName} — check your network and try again.")
        }
    }

    /**
     * Full validation: proves the KEY, then confirms the MODEL is actually served.
     * A key that passes but names a retired/unknown model does NOT activate —
     * this is the check that would have caught "gemini-2.0-flash" before chat.
     */
    fun verify(provider: LlmProvider, modelApiId: String): LlmResult {
        val key = providerKey(appContext, provider.id)
            ?: return LlmResult.Err("No ${provider.displayName} API key saved.", authFailed = true)
        return try {
            // 1. Key probe — same injectable transport + retry as chat()
            // (DeepSeek fix, 2026-11-10): verify() used to call raw httpCall,
            // so a 429/503 during "Check & activate" failed even with a good
            // key, and the path could not be unit-tested at all.
            val (vCode, vText) = callWithRetry(
                call = {
                    httpCallFn(
                        "GET", urlFor(provider, provider.verifyPath(), key),
                        headersFor(provider, key), null,
                    )
                },
                sleeper = sleeper,
            )
            if (!provider.testOk(vCode, vText)) {
                return when {
                    vCode == 401 || vCode == 403 ->
                        LlmResult.Err("The saved key was rejected — check the saved key.", authFailed = true)
                    vCode == 402 ->
                        LlmResult.Err("${provider.displayName} has run out of credits — top up the account and try again.")
                    vCode == 429 ->
                        LlmResult.Err("Too many tries just now — the key works; try again shortly.")
                    else ->
                        // Blank-reply fix (2026-11-06): no HTTP code and no
                        // raw body in a user-facing message — the status
                        // row already says the check failed.
                        LlmResult.Err("The check didn't work — try again in a moment.")
                }
            }
            // 2. Model availability (same transport + retry as the probe).
            val (mCode, mText) = callWithRetry(
                call = {
                    httpCallFn(
                        "GET", urlFor(provider, provider.modelsPath(), key),
                        headersFor(provider, key), null,
                    )
                },
                sleeper = sleeper,
            )
            if (mCode != 200) {
                return LlmResult.Ok(
                    "Connected — key accepted by ${provider.displayName}. " +
                        "Model list unavailable; $modelApiId not confirmed.",
                )
            }
            val ids = provider.availableModelIds(mText)
            when {
                ids.any { it.equals(modelApiId, ignoreCase = true) } ->
                    LlmResult.Ok(
                        "Connected — key accepted by ${provider.displayName}; " +
                            "model $modelApiId confirmed available.",
                    )
                ids.isEmpty() && provider.strictModelList ->
                    LlmResult.Err(
                        "Connected — key accepted, but no model list came back; not activated.",
                    )
                ids.isEmpty() ->
                    LlmResult.Ok(
                        "Connected — key accepted by ${provider.displayName}. " +
                            "Model list unreadable; $modelApiId not confirmed.",
                    )
                else ->
                    LlmResult.Err(
                        "Model \"$modelApiId\" is not served for this key. " +
                            "Available: ${ids.take(8).joinToString(", ")}",
                    )
            }
        } catch (e: java.net.SocketTimeoutException) {
            LlmResult.Err("Probe timed out — check your network.")
        } catch (_: Exception) {
            LlmResult.Err("The check didn't work — try again in a moment.")
        }
    }
}
