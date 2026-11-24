package com.loom.assistant.platform.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * All persisted user preferences, backing the Settings taxonomy
 * (lockdown card 11 → 8 categories). Every value is real and read back
 * by the feature it configures.
 */
class LoomPreferences private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("loom_preferences", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: LoomPreferences? = null

        fun get(context: Context): LoomPreferences {
            return instance ?: synchronized(this) {
                instance ?: LoomPreferences(context.applicationContext).also { instance = it }
            }
        }
    }

    // ---- profile ----
    var profileName: String
        get() = prefs.getString("profile_name", "") ?: ""
        set(v) = prefs.edit().putString("profile_name", v).apply()

    // ---- appearance ----
    /** Dark premium is the locked identity; stored so a future theme has a slot. */
    var theme: String
        get() = prefs.getString("theme", "dark") ?: "dark"
        set(v) = prefs.edit().putString("theme", v).apply()

    var animationsEnabled: Boolean
        get() = prefs.getBoolean("animations_enabled", true)
        set(v) = prefs.edit().putBoolean("animations_enabled", v).apply()

    // ---- voice & speech ----
    /** 0.5x–2.0x, persisted for the TTS engine. */
    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1.0f)
        set(v) = prefs.edit().putFloat("speech_rate", v.coerceIn(0.5f, 2.0f)).apply()

    var speechPitch: Float
        get() = prefs.getFloat("speech_pitch", 1.0f)
        set(v) = prefs.edit().putFloat("speech_pitch", v.coerceIn(0.5f, 2.0f)).apply()

    /** Tap-to-talk default; always-listening only on explicit opt-in. */
    var tapToTalk: Boolean
        get() = prefs.getBoolean("tap_to_talk", true)
        set(v) = prefs.edit().putBoolean("tap_to_talk", v).apply()

    // ---- automation ----
    var autoResumeTasks: Boolean
        get() = prefs.getBoolean("auto_resume_tasks", false)
        set(v) = prefs.edit().putBoolean("auto_resume_tasks", v).apply()

    // ---- notifications ----
    var digestMode: Boolean
        get() = prefs.getBoolean("digest_mode", false)
        set(v) = prefs.edit().putBoolean("digest_mode", v).apply()

    // ---- privacy & security ----
    /** Master switch for screen-content analysis (denylist always enforced). */
    var screenAnalysisAllowed: Boolean
        get() = prefs.getBoolean("screen_analysis_allowed", true)
        set(v) = prefs.edit().putBoolean("screen_analysis_allowed", v).apply()

    // ---- confirmation strictness (auth gates, Phase 6) ----
    /** 0 = standard, 1 = strict (confirm more), 2 = paranoid. */
    var confirmStrictness: Int
        get() = prefs.getInt("confirm_strictness", 0)
        set(v) = prefs.edit().putInt("confirm_strictness", v.coerceIn(0, 2)).apply()

    fun strictnessLabel(): String = when (confirmStrictness) {
        1 -> "Strict"
        2 -> "Paranoid"
        else -> "Standard"
    }
}
