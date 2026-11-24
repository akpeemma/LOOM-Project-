// Test-local stub — see CloudLlmRetryTest.kt. Declares ONLY the
// SecureKeyStore surface CloudLlmClient.kt actually uses (signatures
// copied from the real class); the real Gradle build compiles against
// the real Keystore-backed implementation. Kept in the test source set
// — never shipped. The key is a fixed test value; the harness never
// touches the network or the device keystore.
package com.loom.assistant.platform.storage

class SecureKeyStore private constructor() {

    companion object {
        const val ALIAS_API_KEY = "loom_api_key"

        fun apiKeyAlias(providerId: String): String = "loom_api_key_$providerId"

        fun get(context: android.content.Context): SecureKeyStore = SecureKeyStore()

        // ---- Test-only controls (DeepSeek fix, 2026-11-10) ----
        // Per-alias overrides: when an alias is set here (including null),
        // get() returns it instead of the default test key. Lets a test
        // simulate "provider key absent" and "stale legacy key present"
        // independently. Default behaviour is unchanged — any alias not
        // overridden returns "test-key-123", so existing tests are unaffected.
        private val overrides = mutableMapOf<String, String?>()
        val recordedPuts = mutableListOf<Pair<String, String>>()

        fun setOverride(alias: String, value: String?) {
            overrides[alias] = value
        }

        fun clearOverrides() {
            overrides.clear()
            recordedPuts.clear()
        }
    }

    fun get(alias: String): String? =
        if (overrides.containsKey(alias)) overrides[alias] else "test-key-123"

    fun has(alias: String): Boolean = get(alias) != null

    fun put(alias: String, value: String): Boolean {
        recordedPuts.add(alias to value)
        return true
    }

    fun delete(alias: String): Boolean = true
}
