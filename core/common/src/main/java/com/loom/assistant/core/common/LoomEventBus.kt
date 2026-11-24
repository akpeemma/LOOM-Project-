package com.loom.assistant.core.common

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Minimal main-thread event bus (no coroutines on the manual build classpath).
 * All subscribers are invoked on the main thread.
 */
object LoomEventBus {
    private val listeners = CopyOnWriteArraySet<(LoomStateEvent) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun subscribe(listener: (LoomStateEvent) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (LoomStateEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun post(event: LoomStateEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch(event)
        } else {
            mainHandler.post { dispatch(event) }
        }
    }

    private fun dispatch(event: LoomStateEvent) {
        for (l in listeners) {
            try {
                l(event)
            } catch (e: Exception) {
                // A failing subscriber must never break the bus.
                // Stage 2A diagnosability: log the event + exception class only.
                // Never log payload content (no message text, no keys).
                android.util.Log.w(
                    "LoomEventBus",
                    "listener failed on ${event::class.simpleName}: ${e::class.simpleName}",
                )
            }
        }
    }
}
