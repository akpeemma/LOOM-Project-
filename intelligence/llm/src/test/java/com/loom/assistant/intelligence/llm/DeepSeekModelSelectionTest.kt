package com.loom.assistant.intelligence.llm

import android.content.Context
import android.content.SharedPreferences
import com.loom.assistant.platform.storage.SecureKeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DeepSeek custom-provider fix (2026-11-10): proves the app-side registry
 * path — add a DeepSeek custom provider, add the deepseek-chat model id,
 * validate-and-activate it against a fake transport, and confirm the ACTIVE
 * pair becomes the custom provider (which is exactly what
 * PlanJsonSource.forProduction resolves for planning). Also proves GAP 3:
 * hasKeyFor no longer adopts a stale legacy key for a new provider, and
 * that removing a provider removes its key and clears the ACTIVE pair.
 *
 * No network, no real keys. The keystore is the test-local stub and the
 * preferences are in-memory. Run via test-harness/run-llm-tests.sh.
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

private const val MODELS_LIST = """{"object":"list","data":[{"id":"deepseek-chat","object":"model"},{"id":"deepseek-reasoner","object":"model"}]}"""

/** In-memory SharedPreferences (the harness fake is interface-shape only). */
private class MapPrefs : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    override val all: Map<String, *> get() = data.toMap()
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun getString(key: String, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun edit(): SharedPreferences.Editor = MapEditor()

    private inner class MapEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removed.add(key); return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            for (k in removed) data.remove(k)
            data.putAll(pending)
            pending.clear()
            removed.clear()
        }
    }
}

private class TestContext : Context() {
    private val prefsByName = mutableMapOf<String, SharedPreferences>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsByName.getOrPut(name) { MapPrefs() }
}

/** Minimal scripted transport for validateAndActivate (injected ProviderHttp). */
private class SelectionScriptedHttp(responses: List<Pair<Int, String>>) {
    private val queue = ArrayDeque(responses)
    var calls = 0
        private set
    val fn: (String, String, Map<String, String>, org.json.JSONObject?) -> Pair<Int, String> =
        { _, _, _, _ ->
            calls++
            if (queue.isEmpty()) 200 to "ok" else queue.removeFirst()
        }
}

private fun addDeepSeek(sel: ModelSelection): String {
    val id = sel.addCustomProvider(
        "DeepSeek", "https://api.deepseek.com/v1/", AuthKind.BEARER_HEADER,
        "/chat/completions", "/models",
    )
    sel.addCustomModelId(id, "deepseek-chat")
    return id
}

fun main() {
    SecureKeyStore.clearOverrides()

    // ---- add provider + model id; registry resolves them ----
    run {
        val sel = ModelSelection(TestContext())
        val id = addDeepSeek(sel)
        check("add provider → custom id", id.startsWith("custom:"))
        val provider = sel.providerById(id)
        check("providerById → found", provider != null)
        checkEq("provider display name", provider?.displayName, "DeepSeek")
        checkEq("provider base URL trimmed", provider?.baseUrl, "https://api.deepseek.com/v1")
        val models = sel.modelsFor(id)
        checkEq("modelsFor → one model", models.size, 1)
        checkEq("model api id", models.firstOrNull()?.apiModelId, "deepseek-chat")
        checkEq("model id is custom-scoped", models.firstOrNull()?.id, "custom:$id:deepseek-chat")
        check("duplicate model id → false", !sel.addCustomModelId(id, "deepseek-chat"))
    }

    // ---- validate-and-activate with a DeepSeek-shaped fake endpoint ----
    run {
        val ctx = TestContext()
        val sel = ModelSelection(ctx)
        sel.engineMode = EngineMode.CLOUD
        val id = addDeepSeek(sel)
        check("key saved", sel.setApiKey(id, "sk-test-fake-key"))
        val provider = sel.providerById(id)!!
        val model = sel.modelsFor(id).first()
        val scripted = SelectionScriptedHttp(listOf(200 to MODELS_LIST, 200 to MODELS_LIST))
        val http = ProviderHttp(ctx)
        http.httpCallFn = scripted.fn
        http.sleeper = { }
        val latch = CountDownLatch(1)
        var okResult: Boolean? = null
        var msgResult: String? = null
        sel.validateAndActivate(ctx, provider, model, http = http, done = { ok, msg ->
            okResult = ok
            msgResult = msg
            latch.countDown()
        })
        check("validate callback fired", latch.await(5, TimeUnit.SECONDS))
        checkEq("validate deepseek → ok", okResult, true)
        check("validate message confirms model", msgResult?.contains("deepseek-chat") == true)
        checkEq("validate deepseek → 2 calls", scripted.calls, 2)
        checkEq("active provider is the custom DeepSeek provider", sel.activeProviderId, id)
        checkEq("active model is deepseek-chat", sel.activeModelId, model.id)
        // Mirror of PlanJsonSource.forProduction's resolution: the active pair
        // must resolve back to the same provider + model for planning.
        checkEq("forProduction mirror: provider resolves", sel.providerById(sel.activeProviderId!!)?.id, id)
        check(
            "forProduction mirror: model resolves",
            sel.modelsFor(sel.activeProviderId!!).any { it.id == sel.activeModelId },
        )
        checkEq("status is CONNECTED", sel.providerStatus(id), ProviderStatus.CONNECTED)
    }

    // ---- 401 on re-validation clears a stale ACTIVE pair ----
    run {
        val ctx = TestContext()
        val sel = ModelSelection(ctx)
        val id = addDeepSeek(sel)
        val provider = sel.providerById(id)!!
        val model = sel.modelsFor(id).first()
        sel.activeProviderId = id
        sel.activeModelId = model.id
        val scripted = SelectionScriptedHttp(listOf(401 to "bad key"))
        val http = ProviderHttp(ctx)
        http.httpCallFn = scripted.fn
        http.sleeper = { }
        val latch = CountDownLatch(1)
        var okResult: Boolean? = null
        sel.validateAndActivate(ctx, provider, model, http = http, done = { ok, _ ->
            okResult = ok
            latch.countDown()
        })
        check("re-validate callback fired", latch.await(5, TimeUnit.SECONDS))
        checkEq("re-validate 401 → not ok", okResult, false)
        checkEq("401 clears active provider", sel.activeProviderId, null)
        checkEq("401 clears active model", sel.activeModelId, null)
        checkEq("401 status is AUTH_FAILED", sel.providerStatus(id), ProviderStatus.AUTH_FAILED)
    }

    // ---- removing the provider removes its key + clears the ACTIVE pair ----
    run {
        val sel = ModelSelection(TestContext())
        val id = addDeepSeek(sel)
        sel.activeProviderId = id
        sel.activeModelId = "custom:$id:deepseek-chat"
        check("remove provider → true", sel.removeCustomProvider(id))
        checkEq("provider gone", sel.providerById(id), null)
        checkEq("active provider cleared", sel.activeProviderId, null)
        checkEq("active model cleared", sel.activeModelId, null)
    }

    // ---- GAP 3: a stale legacy key is NOT adopted for a new provider ----
    run {
        SecureKeyStore.clearOverrides()
        val legacyAlias = SecureKeyStore.ALIAS_API_KEY
        val providerAlias = SecureKeyStore.apiKeyAlias("custom:deepseek")
        SecureKeyStore.setOverride(legacyAlias, "legacy-key-abc")
        SecureKeyStore.setOverride(providerAlias, null)
        val sel = ModelSelection(TestContext())
        checkEq("hasKeyFor (no key) → false", sel.hasKeyFor("custom:deepseek"), false)
        check(
            "no key copied into the new provider alias",
            SecureKeyStore.recordedPuts.none { it.first == providerAlias },
        )
        SecureKeyStore.clearOverrides()
    }

    println("DeepSeekModelSelectionTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
