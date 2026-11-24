package com.loom.assistant.platform.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phase 6 — encrypted key store (arch §5: "EncryptedSharedPreferences +
 * AndroidKeyStore-backed MasterKey" pattern).
 *
 * The manual build has no AndroidX security-crypto on its classpath, so this
 * implements the same pattern directly on the platform Keystore (API 23+;
 * minSdk is 26): an AES-256-GCM key lives in the AndroidKeyStore and never
 * leaves it; values are encrypted with a fresh random 12-byte IV per write
 * and only IV+ciphertext touch disk (private SharedPreferences).
 *
 * Fail-closed: any Keystore failure (missing hardware, locked device,
 * corrupted entry) returns null/false — LOOM then reports "not configured"
 * honestly instead of crashing or falling back to plain storage.
 * Key material is NEVER logged.
 */
class SecureKeyStore private constructor(appContext: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_ALIAS = "loom_master_key_v1"
        private const val PREFS = "loom_secure_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** Well-known alias for the provider API key (Settings → AI Models). */
        const val ALIAS_API_KEY = "provider_api_key"

        /**
         * Per-provider key alias (Stage 1): one encrypted entry per provider id
         * ("google", "openrouter", …). The legacy single alias above is kept for
         * backward compatibility and migrated on first read.
         */
        fun apiKeyAlias(providerId: String): String = "loom_api_key_$providerId"

        @Volatile
        private var instance: SecureKeyStore? = null

        fun get(context: Context): SecureKeyStore {
            return instance ?: synchronized(this) {
                instance ?: SecureKeyStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (ks.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateMasterKey()
        } catch (_: Exception) {
            null
        }
    }

    private fun generateMasterKey(): SecretKey? {
        return try {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            kg.init(
                KeyGenParameterSpec.Builder(
                    MASTER_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .setKeySize(256)
                    .build(),
            )
            kg.generateKey()
        } catch (_: Exception) {
            null
        }
    }

    /** Store [value] under [alias]. Returns false on any failure. */
    @Synchronized
    fun put(alias: String, value: String): Boolean {
        return try {
            val key = masterKey() ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv // 12 random bytes from the provider
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(ct, Base64.NO_WRAP)
            prefs.edit().putString("k_$alias", packed).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Retrieve the value for [alias], or null when missing/unreadable. */
    @Synchronized
    fun get(alias: String): String? {
        return try {
            val packed = prefs.getString("k_$alias", null) ?: return null
            val parts = packed.split(".", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = masterKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun has(alias: String): Boolean = get(alias) != null

    @Synchronized
    fun delete(alias: String): Boolean {
        return try {
            prefs.edit().remove("k_$alias").apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Aliases currently stored (names only — never values). */
    @Synchronized
    fun aliases(): Set<String> {
        return try {
            prefs.all.keys.mapNotNull { k ->
                if (k.startsWith("k_")) k.removePrefix("k_") else null
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
