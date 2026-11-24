package com.loom.assistant.intelligence.llm

import android.content.Context
import android.content.SharedPreferences
import com.loom.assistant.platform.storage.SecureKeyStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model catalog + selection (lockdown: "Choose model" picker, NO fixed name,
 * default chosen later after comparing models).
 *
 * Entries name the provider clients the architecture preserves
 * (Gemini / OpenRouter). Wiring a key happens in Settings (Phase 5);
 * until then the picker persists the choice and the local engine serves.
 */
data class LoomModel(
    val id: String,
    val displayName: String,
    val provider: String,
    /** Provider id used for key lookup + client routing: "google" | "openrouter". */
    val providerId: String,
    /** Real model id sent to the provider API (David-visible mapping). */
    val apiModelId: String,
    val note: String,
)

object ModelRegistry {
    val models: List<LoomModel> = listOf(
        LoomModel("gemini-flash", "Gemini Flash", "Google", "google", "gemini-3.8-flash", "Current and fast — good default"),
        LoomModel("gemini-flash-lite", "Gemini Flash-Lite", "Google", "google", "gemini-3.5-flash-lite", "Free tier · lowest latency"),
        LoomModel("openrouter-frontier", "GPT-6.1 Terra", "OpenRouter", "openrouter", "openai/gpt-6.1-terra", "Strong all-rounder via OpenRouter"),
        LoomModel("openrouter-efficient", "Flint (free)", "OpenRouter", "openrouter", "recursive/flint-v1.0:free", "Free via OpenRouter — good default"),
    )

    fun byId(id: String?): LoomModel? = models.firstOrNull { it.id == id }

    /** Distinct providers in registry order — the provider selector source. */
    val providers: List<Pair<String, String>> =
        models.map { it.providerId to it.provider }.distinct()

    fun modelsFor(providerId: String): List<LoomModel> = models.filter { it.providerId == providerId }
}

/**
 * Explicit engine control (Stage 1, David's directive):
 * the user picks Local or Cloud — LOOM NEVER silently substitutes one for the other.
 * Cloud with no key / failed call = the actual error, never a scripted local reply.
 */
enum class EngineMode { LOCAL, CLOUD }

/**
 * Activation state machine (Stage 1 correction).
 * Saving a key is NOT activation: a provider is CONNECTED only after a real
 * validation in THIS session, and a model is ACTIVE only after its provider
 * validated. CONNECTED is never restored from a previous session automatically.
 */
enum class ProviderStatus { NO_KEY, KEY_SAVED, VALIDATING, AUTH_FAILED, CONNECTED }

enum class ModelStatus { NO_MODEL, SELECTED, ACTIVE, REQUEST_FAILED }

/** Unified model view across built-in registry entries and custom manual ids. */
data class ProviderModel(
    val id: String,
    val displayName: String,
    val apiModelId: String,
    val note: String,
)

data class CustomProviderDef(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val authKind: AuthKind,
    val chatPath: String,
    val testPath: String,
    val modelIds: List<String>,
)

private const val PREFS = "loom_model_prefs"
private const val KEY_MODEL_ID = "selected_model_id"
private const val KEY_ENGINE_MODE = "engine_mode"
private const val KEY_LAST_TEST = "last_test_result"
private const val KEY_LAST_TEST_AT = "last_test_at"
private const val KEY_PROV_STATUS = "prov_status_"
private const val KEY_PROV_VALIDATED_AT = "prov_validated_at_"
private const val KEY_ACTIVE_PROVIDER = "active_provider"
private const val KEY_ACTIVE_MODEL = "active_model"
private const val KEY_CUSTOM_PROVIDERS = "custom_providers_json"
private const val KEY_LAST_CHAT_FAILED = "last_chat_failed"

/** Persists the user's model choice. Null = "Choose model" (no default). */
class ModelSelection(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var selectedId: String?
        get() = prefs.getString(KEY_MODEL_ID, null)
        set(value) {
            prefs.edit().putString(KEY_MODEL_ID, value).apply()
        }

    val selected: LoomModel? get() = ModelRegistry.byId(selectedId)

    /** Lockdown-correct label: never a fixed model name. */
    val label: String get() = selected?.displayName ?: "Choose model"

    /** Explicit engine mode. Default LOCAL — LOOM never auto-switches to Cloud. */
    var engineMode: EngineMode
        get() = try {
            EngineMode.valueOf(prefs.getString(KEY_ENGINE_MODE, EngineMode.LOCAL.name)!!)
        } catch (_: Exception) {
            EngineMode.LOCAL
        }
        set(value) {
            prefs.edit().putString(KEY_ENGINE_MODE, value.name).apply()
        }

    /** Last connection-test outcome, for the status displays. Null = never tested. */
    var lastTest: Pair<Boolean, Long>?
        get() {
            if (!prefs.contains(KEY_LAST_TEST)) return null
            return (prefs.getBoolean(KEY_LAST_TEST, false)) to prefs.getLong(KEY_LAST_TEST_AT, 0L)
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) {
                e.remove(KEY_LAST_TEST).remove(KEY_LAST_TEST_AT)
            } else {
                e.putBoolean(KEY_LAST_TEST, value.first).putLong(KEY_LAST_TEST_AT, value.second)
            }
            e.apply()
        }

    private fun store(): SecureKeyStore = SecureKeyStore.get(appContext)

    /**
     * Whether an API key is configured for a provider. Per-provider keys only.
     * (DeepSeek fix, 2026-11-10): the legacy single-key migration is removed —
     * nothing in current code writes the old alias, and adopting it for the
     * first provider that asked could store the WRONG key (e.g. a Gemini key)
     * under a new custom provider such as DeepSeek, so validation would fail
     * 401 with no visible cause.
     */
    fun hasKeyFor(providerId: String): Boolean {
        return try {
            store().has(SecureKeyStore.apiKeyAlias(providerId))
        } catch (_: Exception) {
            false
        }
    }

    /** Back-compat for the selected provider (dashboard + older call sites). */
    val hasKeyConfigured: Boolean
        get() = selected?.let { hasKeyFor(it.providerId) } ?: run {
            // No model picked yet: any provider key counts.
            ModelRegistry.providers.any { hasKeyFor(it.first) }
        }

    /** Store a provider key (encrypted). Returns false when storage failed. */
    fun setApiKey(providerId: String, rawKey: String): Boolean {
        return try {
            val ok = store().put(SecureKeyStore.apiKeyAlias(providerId), rawKey)
            if (ok) {
                // A new key invalidates the old verdict: AUTH_FAILED applied to the
                // previous key, not this one.
                if (providerStatus(providerId) == ProviderStatus.AUTH_FAILED) {
                    setProviderStatus(providerId, ProviderStatus.KEY_SAVED)
                }
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Remove the stored provider key. */
    fun clearApiKey(providerId: String): Boolean {
        return try {
            store().delete(SecureKeyStore.apiKeyAlias(providerId))
        } catch (_: Exception) {
            false
        }
    }

    // Legacy single-key shims (kept so older screens compile during migration).
    fun setApiKey(rawKey: String): Boolean =
        setApiKey(selected?.providerId ?: "openrouter", rawKey)
    fun clearApiKey(): Boolean =
        clearApiKey(selected?.providerId ?: "openrouter")

    // ---------- activation state machine ----------

    init {
        // Persistent activation rule: a CONNECTED label from a previous session is
        // NEVER restored automatically. This session starts at KEY_SAVED until a
        // real validation runs.
        for (pid in allProviderIds()) {
            val raw = prefs.getString(KEY_PROV_STATUS + pid, null)
            if (raw == ProviderStatus.CONNECTED.name) {
                prefs.edit().putString(KEY_PROV_STATUS + pid, ProviderStatus.KEY_SAVED.name).apply()
            }
        }
    }

    private fun allProviderIds(): List<String> =
        listOf("google", "openrouter") + customProviders().map { it.id }

    fun providerStatus(providerId: String): ProviderStatus {
        if (!hasKeyFor(providerId)) return ProviderStatus.NO_KEY
        val raw = prefs.getString(KEY_PROV_STATUS + providerId, null)
            ?: return ProviderStatus.KEY_SAVED
        return try {
            ProviderStatus.valueOf(raw)
        } catch (_: Exception) {
            ProviderStatus.KEY_SAVED
        }
    }

    private fun setProviderStatus(providerId: String, s: ProviderStatus) {
        val e = prefs.edit().putString(KEY_PROV_STATUS + providerId, s.name)
        if (s == ProviderStatus.CONNECTED) {
            e.putLong(KEY_PROV_VALIDATED_AT + providerId, System.currentTimeMillis())
        }
        e.apply()
    }

    fun lastValidatedAt(providerId: String): Long =
        prefs.getLong(KEY_PROV_VALIDATED_AT + providerId, 0L)

    var activeProviderId: String?
        get() = prefs.getString(KEY_ACTIVE_PROVIDER, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROVIDER, value).apply()

    var activeModelId: String?
        get() = prefs.getString(KEY_ACTIVE_MODEL, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_MODEL, value).apply()

    /** Display name of the validated ACTIVE model, or null. */
    fun activeModelName(): String? {
        val pid = activeProviderId ?: return null
        val mid = activeModelId ?: return null
        return modelsFor(pid).firstOrNull { it.id == mid }?.displayName
    }

    /** True when the last cloud request failed (drives the REQUEST_FAILED status). */
    var lastChatFailed: Boolean
        get() = prefs.getBoolean(KEY_LAST_CHAT_FAILED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_CHAT_FAILED, value).apply()

    fun modelStatus(providerId: String, modelId: String): ModelStatus {
        if (activeProviderId == providerId && activeModelId == modelId) return ModelStatus.ACTIVE
        if (lastChatFailed) return ModelStatus.REQUEST_FAILED
        val models = modelsFor(providerId)
        if (models.none { it.id == modelId }) return ModelStatus.NO_MODEL
        return ModelStatus.SELECTED
    }

    /**
     * Validate & activate: runs the REAL probe. Only success persists the ACTIVE
     * pair and the CONNECTED label. Auth failures clear a stale ACTIVE pair.
     * [done] runs on a background thread — the caller posts to the UI thread.
     */
    fun validateAndActivate(
        context: Context,
        provider: LlmProvider,
        model: ProviderModel,
        http: ProviderHttp? = null,
        done: (ok: Boolean, message: String) -> Unit,
    ) {
        // No key → nothing to validate. This must NOT become AUTHENTICATION FAILED.
        if (!hasKeyFor(provider.id)) {
            done(false, "Save a ${provider.displayName} API key first — nothing to validate.")
            return
        }
        setProviderStatus(provider.id, ProviderStatus.VALIDATING)
        Thread {
            val result = try {
                // Full validation: key probe + model-availability check.
                // [http] is a test seam only — production passes nothing and
                // the real ProviderHttp is built (DeepSeek fix, 2026-11-10).
                (http ?: ProviderHttp(context.applicationContext)).verify(provider, model.apiModelId)
            } catch (e: Exception) {
                LlmResult.Err("Probe failed: ${e.message ?: "unknown error"}")
            }
            val ok = result is LlmResult.Ok
            val msg = when (result) {
                is LlmResult.Ok -> result.text
                is LlmResult.Err -> result.message
            }
            if (ok) {
                setProviderStatus(provider.id, ProviderStatus.CONNECTED)
                activeProviderId = provider.id
                activeModelId = model.id
                lastTest = true to System.currentTimeMillis()
                lastChatFailed = false
            } else {
                if ((result as? LlmResult.Err)?.authFailed == true) {
                    setProviderStatus(provider.id, ProviderStatus.AUTH_FAILED)
                } else {
                    setProviderStatus(provider.id, ProviderStatus.KEY_SAVED)
                }
                if (activeProviderId == provider.id) {
                    activeProviderId = null
                    activeModelId = null
                }
                lastTest = false to System.currentTimeMillis()
            }
            done(ok, msg)
        }.start()
    }

    /** A successful real request counts as validation (keeps CONNECTED honest). */
    fun markRequestOk(providerId: String) {
        setProviderStatus(providerId, ProviderStatus.CONNECTED)
        lastChatFailed = false
    }

    fun markRequestFailed(providerId: String, authFailed: Boolean) {
        if (authFailed) {
            setProviderStatus(providerId, ProviderStatus.AUTH_FAILED)
            if (activeProviderId == providerId) {
                activeProviderId = null
                activeModelId = null
            }
        }
        lastChatFailed = true
    }

    // ---------- provider-independent registry ----------

    /** Built-ins + user-added custom providers. */
    fun allProviders(): List<LlmProvider> {
        val list = mutableListOf<LlmProvider>(
            GeminiProvider(),
            OpenRouterProvider(),
        )
        for (c in customProviders()) {
            list.add(
                OpenAiCompatProvider(
                    id = c.id, displayName = c.displayName, baseUrl = c.baseUrl,
                    removable = true, chatPath = c.chatPath, testPath = c.testPath,
                ),
            )
        }
        return list
    }

    fun providerById(providerId: String): LlmProvider? =
        allProviders().firstOrNull { it.id == providerId }

    /** Models for a provider: registry entries for built-ins, manual ids for customs. */
    fun modelsFor(providerId: String): List<ProviderModel> {
        val builtin = ModelRegistry.models.filter { it.providerId == providerId }
        if (builtin.isNotEmpty()) {
            return builtin.map { ProviderModel(it.id, it.displayName, it.apiModelId, it.note) }
        }
        return customProviders().firstOrNull { it.id == providerId }
            ?.modelIds?.map { mid ->
                ProviderModel("custom:$providerId:$mid", mid, mid, "Custom model id")
            } ?: emptyList()
    }

    fun customProviders(): List<CustomProviderDef> {
        val raw = prefs.getString(KEY_CUSTOM_PROVIDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val mids = o.optJSONArray("modelIds")
                CustomProviderDef(
                    id = o.getString("id"),
                    displayName = o.getString("displayName"),
                    baseUrl = o.getString("baseUrl"),
                    authKind = try {
                        AuthKind.valueOf(o.optString("authKind", "BEARER_HEADER"))
                    } catch (_: Exception) {
                        AuthKind.BEARER_HEADER
                    },
                    chatPath = o.optString("chatPath", "/chat/completions"),
                    testPath = o.optString("testPath", "/models"),
                    modelIds = (0 until (mids?.length() ?: 0)).map { mids!!.getString(it) },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustomProvider(
        displayName: String,
        baseUrl: String,
        authKind: AuthKind,
        chatPath: String,
        testPath: String,
    ): String {
        val id = "custom:" + System.currentTimeMillis().toString(36)
        val next = customProviders() + CustomProviderDef(
            id, displayName.trim(), baseUrl.trim().trimEnd('/'), authKind,
            chatPath.trim().ifBlank { "/chat/completions" },
            testPath.trim().ifBlank { "/models" }, emptyList(),
        )
        saveCustomProviders(next)
        return id
    }

    fun removeCustomProvider(providerId: String): Boolean {
        val next = customProviders().filterNot { it.id == providerId }
        if (next.size == customProviders().size) return false
        saveCustomProviders(next)
        // Credential isolation: removing a provider also removes its key and
        // clears it from the ACTIVE pair — other providers are untouched.
        try {
            SecureKeyStore.get(appContext).delete(SecureKeyStore.apiKeyAlias(providerId))
        } catch (_: Exception) {
        }
        prefs.edit().remove(KEY_PROV_STATUS + providerId)
            .remove(KEY_PROV_VALIDATED_AT + providerId).apply()
        if (activeProviderId == providerId) {
            activeProviderId = null
            activeModelId = null
        }
        return true
    }

    fun addCustomModelId(providerId: String, modelId: String): Boolean {
        val mid = modelId.trim()
        if (mid.isEmpty()) return false
        val defs = customProviders()
        val def = defs.firstOrNull { it.id == providerId } ?: return false
        if (def.modelIds.contains(mid)) return false
        saveCustomProviders(defs.map {
            if (it.id == providerId) it.copy(modelIds = it.modelIds + mid) else it
        })
        return true
    }

    private fun saveCustomProviders(defs: List<CustomProviderDef>) {
        val arr = JSONArray()
        for (d in defs) {
            arr.put(
                JSONObject().put("id", d.id).put("displayName", d.displayName)
                    .put("baseUrl", d.baseUrl).put("authKind", d.authKind.name)
                    .put("chatPath", d.chatPath).put("testPath", d.testPath)
                    .put("modelIds", JSONArray(d.modelIds)),
            )
        }
        prefs.edit().putString(KEY_CUSTOM_PROVIDERS, arr.toString()).apply()
    }
}

/** Task types the router distinguishes (Phase 3 §5). */
enum class LoomTaskType { CLASSIFY, PLAN, CODE, VISION, TRANSCRIBE, CHAT }

interface ModelRouter {
    /** Returns the model id to use, or null when no model is configured. */
    fun route(task: LoomTaskType, selection: ModelSelection): String?
}

/** Default routing policy: chat/classify stay cheap; plan/code/vision go frontier. */
class DefaultModelRouter : ModelRouter {
    override fun route(task: LoomTaskType, selection: ModelSelection): String? {
        val chosen = selection.selectedId ?: return null
        // v1: honor the user's pick for everything; tiering arrives with the
        // provider clients in Phase 5. The policy hook is here, not hardcoded.
        return chosen
    }
}

/**
 * Produces the assistant's reply for a user message.
 *
 * Honesty contract: an engine must never pretend to be a capability it is
 * not. [LocalResponseEngine] is Tier 0 (Phase 3 §5 degraded tiers) — scripted,
 * useful, and explicitly labeled offline. [LlmResponseEngine] is the seam for
 * the real provider clients; without a key it says so instead of faking.
 */
interface ResponseEngine {
    val engineLabel: String
    /**
     * Stage 2A.1 (D3): optional per-turn device-state grounding ("the user's
     * message was transcribed from live speech just now"). Prompt context
     * only — never written to the conversation store. The local engine
     * accepts and ignores it (scripted; contradiction cannot arise).
     */
    fun generate(
        userText: String,
        historySize: Int,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        systemContext: String? = null,
    )
}

class LocalResponseEngine : ResponseEngine {
    override val engineLabel: String = "Offline · local mode"

    override fun generate(
        userText: String,
        historySize: Int,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        systemContext: String?,
    ) {
        // systemContext accepted and ignored by design — see interface docs.
        val reply = buildReply(userText.trim())
        // Stream in word chunks so the UI renders progressively (real behavior).
        // Chunked delivery pacing happens in the view via Handler posts.
        val words = reply.split(' ')
        val acc = StringBuilder()
        deliver(words, 0, acc, onChunk, onDone)
    }

    private fun deliver(
        words: List<String>,
        idx: Int,
        acc: StringBuilder,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
    ) {
        if (idx >= words.size) {
            onDone(acc.toString().trim())
            return
        }
        // Deliver 3 words per chunk on the calling thread; the view posts
        // these to the main thread with pacing for the streaming effect.
        val end = minOf(idx + 3, words.size)
        val chunk = words.subList(idx, end).joinToString(" ") + " "
        acc.append(chunk)
        onChunk(chunk)
        deliver(words, end, acc, onChunk, onDone)
    }

    private fun buildReply(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.isBlank() -> "I didn't catch that — try again?"
            lower.contains("what can you do") || lower.contains("help") ->
                "In local mode I can: keep this conversation going, " +
                    "save and search your chat history, take voice input, and accept image attachments. " +
                    "For full intelligence, add an API key under Settings → AI Connections."
            lower.startsWith("hello") || lower.startsWith("hi") || lower == "hey" ->
                "Hello. I'm LOOM, running in local mode — my full intelligence unlocks " +
                    "once an API key is added. What would you like to do?"
            lower.contains("who are you") || lower.contains("your name") ->
                "I'm LOOM — your personal AI companion. Right now I'm in local mode, " +
                    "so my answers are simple and honest about what I can do."
            else ->
                "Noted: \"$text\". I'm in local mode, so I can't reason over this yet — " +
                    "my full intelligence needs an API key (Settings → AI Connections). " +
                    "Your message is saved in this conversation either way."
        }
    }
}

/**
 * Explicit engine resolution (Stage 1):
 * LOCAL → scripted local engine, always.
 * CLOUD → the real provider engine; missing key/model or a failed call
 *         surfaces the ACTUAL error — never a silent local substitution.
 */
fun resolveEngine(
    context: Context,
    selection: ModelSelection,
    history: () -> List<LlmMessage> = { emptyList() },
): ResponseEngine {
    return when (selection.engineMode) {
        EngineMode.LOCAL -> LocalResponseEngine()
        EngineMode.CLOUD -> CloudResponseEngine(context, selection, history)
    }
}

/**
 * Real cloud engine (Stage 1). Runs the provider call off the main thread and
 * posts the genuine provider reply — or the genuine provider error — back.
 * It never invents a reply and never falls back to local scripting.
 */
class CloudResponseEngine(
    context: Context,
    private val selection: ModelSelection,
    private val history: () -> List<LlmMessage> = { emptyList() },
) : ResponseEngine {
    private val appContext = context.applicationContext
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override val engineLabel: String
        get() {
            val pid = selection.activeProviderId
            val mid = selection.activeModelId
            val name = if (pid != null && mid != null) {
                selection.modelsFor(pid).firstOrNull { it.id == mid }?.displayName
            } else null
            return "Cloud · ${name ?: "no active model"}"
        }

    override fun generate(
        userText: String,
        historySize: Int,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        systemContext: String?,
    ) {
        Thread {
            // Device-evidence fix (2026-11-05): a typed shutdown attempt
            // ended in a hanging "..." bubble — a thread dying before
            // onDone leaves the chat bubble unresolved forever. runCloud
            // catches failures around the HTTP call, but anything else
            // throwing (history read, status writes) would kill this
            // thread silently. This outer catch guarantees onDone fires
            // on every path, so the UI always resolves.
            val result = try {
                runCloud(userText, systemContext)
            } catch (e: Exception) {
                LlmResult.Err("I couldn't reach the cloud service — try again in a moment.")
            }
            mainHandler.post {
                when (result) {
                    is LlmResult.Ok -> {
                        // Stream in word chunks so the UI renders progressively.
                        val words = result.text.split(' ')
                        val acc = StringBuilder()
                        var i = 0
                        while (i < words.size) {
                            val end = minOf(i + 3, words.size)
                            val chunk = words.subList(i, end).joinToString(" ") + " "
                            acc.append(chunk); onChunk(chunk); i = end
                        }
                        onDone(acc.toString().trim())
                    }
                    is LlmResult.Err -> {
                        // Accessibility fix (2026-11-05, §7.3): the error
                        // is already one plain sentence with no HTTP code
                        // (ProviderHttp maps every chat failure that way)
                        // — surface it as-is, never with a "Cloud error:"
                        // prefix or a code.
                        val msg = result.message
                        onChunk(msg); onDone(msg)
                    }
                }
            }
        }.start()
    }

    private fun runCloud(userText: String, systemContext: String?): LlmResult {
        // Explicit activation: the engine uses ONLY the validated ACTIVE pair —
        // never the merely-selected model, never a silent substitution.
        val pid = selection.activeProviderId
        val mid = selection.activeModelId
        if (pid == null || mid == null) {
            return LlmResult.Err(
                "Cloud mode is on, but no validated model is active. " +
                    "Open Settings → AI Connections and tap Validate & activate.",
            )
        }
        val provider = selection.providerById(pid)
            ?: return LlmResult.Err("Provider '$pid' is no longer configured.")
        val model = selection.modelsFor(pid).firstOrNull { it.id == mid }
            ?: return LlmResult.Err("The active model is no longer configured.")
        val messages = mutableListOf(
            LlmMessage("system", "You are LOOM, a personal AI companion. Be concise and helpful."),
        )
        // Stage 2A.1 (D3): device-state grounding as a second system message
        // — merged by the Gemini builder into the first user turn, passed
        // through as-is by OpenAI-compat providers. Never stored.
        if (!systemContext.isNullOrBlank()) {
            messages.add(LlmMessage("system", systemContext))
        }
        val hist = history()
        messages.addAll(hist)
        // Stage 2A: call sites append the user message to the store BEFORE
        // generate(), and history() re-reads the store — so the just-appended
        // turn is already the last history item. Only add it when missing.
        val last = hist.lastOrNull()
        if (last?.role != "user" || last.content != userText) {
            messages.add(LlmMessage("user", userText))
        }
        val result = try {
            ProviderHttp(appContext).chat(provider, model.apiModelId, messages)
        } catch (e: Exception) {
            LlmResult.Err("I couldn't reach the cloud service — try again in a moment.")
        }
        // A real request is real validation: success keeps CONNECTED honest,
        // auth failure revokes the ACTIVE pair.
        when (result) {
            is LlmResult.Ok -> selection.markRequestOk(pid)
            is LlmResult.Err -> selection.markRequestFailed(pid, result.authFailed)
        }
        return result
    }
}
