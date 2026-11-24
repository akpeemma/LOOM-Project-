package com.loom.assistant.intelligence.authgate

import android.content.Context

/**
 * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT) — the
 * persistent grant state. Modelled on [AuthGrantStore]
 * (SharedPreferences, narrow key, explicit opt-in, revocable,
 * audit-logged by the gate).
 *
 * Rules (David's requirement):
 *  - Granting is explicit: a deliberate tap on the in-app grant
 *    screen ("Pseudo control" on the home screen). Never default-on,
 *    never by voice, never by the model — the store has no grant
 *    path reachable from a plan, a voice command, or a model request.
 *  - Revocation is one tap and immediate: [revoke] clears the grant
 *    at once; the next in-scope action asks again (the gate reads
 *    this store on every decision — no cached "granted" survives a
 *    revoke).
 *  - The grant persists across restarts (SharedPreferences) and is
 *    visible: the home-screen entry shows the state ("On — 10 apps"
 *    / "Off") and the grant screen shows it.
 *  - LOOM asks the grant question ONCE: [shouldShowPrompt] is true
 *    only until David answers for the current list version — a grant
 *    answers it, a "Not now" answers it too. It asks again only when
 *    the list changes ([PseudoControlPolicy.PSEUDO_VERSION] bump —
 *    the old grant lapses with it) or when he revokes (a revoke
 *    clears the answered flag, so the question can be asked once
 *    more).
 *
 * Storage: one SharedPreferences file "loom_pseudo_control":
 *  - "granted" (boolean), "granted_at" (millis),
 *    "list_version" (int), "prompt_answered_version" (int, -1 = never).
 */
class PseudoControlStore private constructor(context: Context) {

    private val prefs =
        context.getSharedPreferences("loom_pseudo_control", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GRANTED = "granted"
        private const val KEY_GRANTED_AT = "granted_at"
        private const val KEY_LIST_VERSION = "list_version"
        private const val KEY_PROMPT_ANSWERED_VERSION = "prompt_answered_version"

        @Volatile
        private var instance: PseudoControlStore? = null

        fun get(context: Context): PseudoControlStore {
            return instance ?: synchronized(this) {
                instance ?: PseudoControlStore(context.applicationContext).also { instance = it }
            }
        }
    }

    /** True only when granted AND for the current list version. An
     *  old-version grant lapses (the list changed — ask again). */
    @Synchronized
    fun isGranted(): Boolean {
        if (!prefs.getBoolean(KEY_GRANTED, false)) return false
        return prefs.getInt(KEY_LIST_VERSION, -1) == PseudoControlPolicy.PSEUDO_VERSION
    }

    /** The deliberate grant (in-app grant screen tap only). Marks
     *  the prompt answered for this list version. */
    @Synchronized
    fun grant() {
        prefs.edit()
            .putBoolean(KEY_GRANTED, true)
            .putLong(KEY_GRANTED_AT, System.currentTimeMillis())
            .putInt(KEY_LIST_VERSION, PseudoControlPolicy.PSEUDO_VERSION)
            .putInt(KEY_PROMPT_ANSWERED_VERSION, PseudoControlPolicy.PSEUDO_VERSION)
            .apply()
    }

    /** One-tap revoke, immediate. Also clears the answered flag so
     *  the grant question may be asked once more (never silently
     *  re-granted, never nagging: one ask, then it waits). */
    @Synchronized
    fun revoke() {
        prefs.edit()
            .putBoolean(KEY_GRANTED, false)
            .putLong(KEY_GRANTED_AT, 0L)
            .putInt(KEY_LIST_VERSION, -1)
            .putInt(KEY_PROMPT_ANSWERED_VERSION, -1)
            .apply()
    }

    /** "Not now" on the prompt: answered for this version, not granted. */
    @Synchronized
    fun dismissPrompt() {
        prefs.edit()
            .putInt(KEY_PROMPT_ANSWERED_VERSION, PseudoControlPolicy.PSEUDO_VERSION)
            .apply()
    }

    /** True when the one-time grant question should be shown: not
     *  granted, and not yet answered for the current list version. */
    @Synchronized
    fun shouldShowPrompt(): Boolean {
        if (isGranted()) return false
        return prefs.getInt(KEY_PROMPT_ANSWERED_VERSION, -1) !=
            PseudoControlPolicy.PSEUDO_VERSION
    }

    /** Plain-words state line for the home screen / grant screen. */
    @Synchronized
    fun statusLine(): String =
        if (isGranted()) "On — ${PseudoControlPolicy.FREE_APPS.size} apps"
        else "Off"
}
