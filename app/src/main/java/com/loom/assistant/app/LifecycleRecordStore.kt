package com.loom.assistant.app

import android.content.Context
import com.loom.assistant.lifecycle.LifecyclePersistence
import com.loom.assistant.lifecycle.LifecycleState

/**
 * SharedPreferences-backed [LifecyclePersistence] (plan §4.1).
 *
 * Writes use commit() (synchronous) so the record is durable before the
 * caller proceeds — the plan requires an atomic write after every verified
 * transition. Supersedes the old write-only `shutdown` pref in
 * LoomShutdown (which this store does not read or write).
 */
class LifecycleRecordStore(context: Context) : LifecyclePersistence {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS, Context.MODE_PRIVATE,
    )

    override fun read(): LifecycleState? {
        val name = prefs.getString(KEY_STATE, null) ?: return null
        return try {
            LifecycleState.valueOf(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun write(state: LifecycleState) {
        prefs.edit().putString(KEY_STATE, state.name).commit()
    }

    companion object {
        private const val PREFS = "loom_lifecycle"
        private const val KEY_STATE = "lifecycle_state"
    }
}
