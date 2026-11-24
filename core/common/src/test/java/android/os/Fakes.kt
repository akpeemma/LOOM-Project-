package android.os

/**
 * Minimal android.os fakes — test-only. Copied from the presentation/modes
 * test fakes so core:common tests can drive LoomEventBus on plain JVM.
 */
open class Looper private constructor() {
    companion object {
        private val main = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = main

        @JvmStatic
        fun myLooper(): Looper = main
    }
}

/**
 * Handler fake: post() runs immediately (main-thread semantics);
 * postDelayed() queues for completeness.
 */
open class Handler(val looper: Looper) {
    companion object {
        private val queue = ArrayDeque<Runnable>()

        @JvmStatic
        fun runNext(): Boolean {
            val r = queue.removeFirstOrNull() ?: return false
            r.run()
            return true
        }

        @JvmStatic
        fun drain(max: Int = 1000): Int {
            var n = 0
            while (n < max && runNext()) n++
            return n
        }

        @JvmStatic
        fun clear() = queue.clear()
    }

    open fun post(r: Runnable): Boolean {
        r.run()
        return true
    }

    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        queue.add(r)
        return true
    }

    open fun removeCallbacks(r: Runnable) {
        queue.remove(r)
    }
}
