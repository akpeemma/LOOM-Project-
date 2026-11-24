package com.loom.assistant.interaction.pointer

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.loom.assistant.core.common.PointerDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pointer-drive fix (2026-11-06, David's direction): the ONE marshaling
 * point for pointer draws issued off the main thread.
 *
 * ROOT CAUSE (device evidence, Nov 6 17:48 — the pointer never moved
 * during a plan, on the planner build): the plan executor runs on a
 * background Thread ("loom-plan-runner"), and every pointer draw
 * funnels into GuidanceRenderer.invalidate() — which Android requires
 * on the UI thread (ViewRootImpl.checkThread() throws
 * CalledFromWrongThreadException, or on some paths the redraw is
 * simply lost). The executor swallowed the throw as a missed visual,
 * so the pointer sat idle like decoration while LOOM worked. This is
 * the same class of bug as the earlier no-Looper fix (69f2cfe:
 * WindowManager.addView from a no-Looper thread threw; fixed by
 * marshaling the show-pointer seam onto the main thread in
 * LoomOverlayService.showPointerNowDetailed) — but the PLAN path was
 * never marshaled.
 *
 * FIX: this decorator wraps any [GuidanceRendererContract] and runs
 * every call on the main thread — state mutation AND invalidate —
 * with a bounded wait (mirroring showPointerNowDetailed: mainHandler
 * + 2 s). A caller already on the main thread runs inline (no hop, no
 * wait). The draw itself is untouched: the glide animation still
 * starts from the main-thread run, so its 250ms / 300ms-cap timing is
 * preserved.
 *
 * The wrapper never throws: a marshal failure (timeout, post refused)
 * or a throw inside the draw is recorded as a draw failure (see
 * [lastDrawFailure]) and the call is skipped / answered fail-closed
 * (false / null). Display-only throughout — a missed visual never
 * fails a step; the caller (PlanPointerGuide / the adapter) reads
 * [lastDrawFailure] and reports it as a pointer failure instead of
 * misreporting it as a target failure. Every marshal failure is
 * logged under "LoomPointer" so a future failure names itself.
 *
 * [marshaller] is injected so JVM tests can drive the wrapper with a
 * fake main thread; production uses [AndroidMainThreadMarshaller].
 */
class MainThreadGuidanceRenderer(
    private val inner: GuidanceRendererContract,
    private val marshaller: MainThreadMarshaller = AndroidMainThreadMarshaller(),
) : GuidanceRendererContract {

    @Volatile
    private var marshalFailure: String? = null

    /**
     * The last draw failure: a marshal failure of the most recent call,
     * else the inner renderer's own last failure (e.g. a HIDE-policy
     * swallow, or a draw that threw). Null when the last call drew
     * fine. Read by the pointer guides after every draw call.
     */
    override fun lastDrawFailure(): String? = marshalFailure ?: inner.lastDrawFailure()

    private fun <T> onMain(action: () -> T): MainRun<T> {
        val run = try {
            marshaller.runOnMain(action)
        } catch (e: Exception) {
            MainRun.Failed("pointer draw threw: ${e.javaClass.simpleName}")
        }
        when (run) {
            is MainRun.Ran -> marshalFailure = null
            is MainRun.Failed -> {
                marshalFailure = run.reason
                Log.w("LoomPointer", "MainThreadGuidanceRenderer: marshal failed — ${run.reason}")
            }
        }
        return run
    }

    private fun draw(action: () -> Unit) {
        onMain(action)
    }

    override fun showStep(
        anchorX: Float,
        anchorY: Float,
        target: RectF,
        stepIndex: Int,
        stepCount: Int,
        instruction: String,
    ) {
        draw { inner.showStep(anchorX, anchorY, target, stepIndex, stepCount, instruction) }
    }

    override fun hide() {
        draw { inner.hide() }
    }

    override fun previewMoveTo(target: RectF) {
        draw { inner.previewMoveTo(target) }
    }

    override fun pulseOnResult() {
        draw { inner.pulseOnResult() }
    }

    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
        draw { inner.onAnchorChanged(newAnchorX, newAnchorY) }
    }

    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean =
        when (val run = onMain { inner.nudgePointer(direction, stepDp) }) {
            is MainRun.Ran -> run.value
            is MainRun.Failed -> false
        }

    override fun showIdleIndicator(): Boolean =
        when (val run = onMain { inner.showIdleIndicator() }) {
            is MainRun.Ran -> run.value
            is MainRun.Failed -> false
        }

    override fun showTapRipple() {
        draw { inner.showTapRipple() }
    }

    override fun showPressState(holdMs: Long) {
        draw { inner.showPressState(holdMs) }
    }

    override fun setStatusNarration(text: String) {
        draw { inner.setStatusNarration(text) }
    }

    override fun showSelection(target: RectF, label: String) {
        draw { inner.showSelection(target, label) }
    }

    override fun parkPointer(target: RectF, label: String) {
        draw { inner.parkPointer(target, label) }
    }

    override fun clearParked() {
        draw { inner.clearParked() }
    }

    override fun cursorPosition(): GeomPoint? =
        when (val run = onMain { inner.cursorPosition() }) {
            is MainRun.Ran -> run.value
            is MainRun.Failed -> null
        }

    override fun isCursorOver(target: RectF, tolerancePx: Float): Boolean =
        when (val run = onMain { inner.isCursorOver(target, tolerancePx) }) {
            is MainRun.Ran -> run.value
            is MainRun.Failed -> false
        }
}

/** The result of running one action on the main thread. */
sealed interface MainRun<out T> {
    /** The action ran; [value] is what it returned. */
    data class Ran<T>(val value: T) : MainRun<T>

    /** The action could not run; [reason] names why, in plain words. */
    data class Failed(val reason: String) : MainRun<Nothing>
}

/**
 * Runs [action] on the main thread and returns its result, or
 * [MainRun.Failed] when it could not run (timeout, post refused).
 * Never throws for a marshal failure — a throw inside [action] itself
 * is rethrown to the caller (the wrapper records it as a draw
 * failure instead of letting it escape). A plain interface (not a SAM)
 * because the method is generic.
 */
interface MainThreadMarshaller {
    fun <T> runOnMain(action: () -> T): MainRun<T>
}

/**
 * Production marshaller: the showPointerNowDetailed pattern — inline
 * when already on the main thread, else post to the main handler and
 * wait bounded ([timeoutMs], default 2 s, generous for a sub-100 ms
 * draw). A wait that times out reports Failed instead of eating the
 * caller's whole window.
 */
class AndroidMainThreadMarshaller(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = MAIN_THREAD_TIMEOUT_MS,
) : MainThreadMarshaller {

    override fun <T> runOnMain(action: () -> T): MainRun<T> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return MainRun.Ran(action())
        }
        val latch = CountDownLatch(1)
        var result: MainRun<T>? = null
        val posted = try {
            handler.post {
                result = try {
                    MainRun.Ran(action())
                } catch (t: Throwable) {
                    MainRun.Failed("pointer draw threw: ${t.javaClass.simpleName}")
                } finally {
                    latch.countDown()
                }
            }
        } catch (e: Exception) {
            Log.w(
                "LoomPointer",
                "AndroidMainThreadMarshaller: post threw: ${e.javaClass.simpleName}",
            )
            false
        }
        if (!posted) {
            return MainRun.Failed("could not post the pointer draw to the main thread")
        }
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                result ?: MainRun.Failed("pointer draw never ran on the main thread")
            } else {
                Log.w(
                    "LoomPointer",
                    "AndroidMainThreadMarshaller: main-thread marshal timed out " +
                        "after ${timeoutMs}ms — pointer draw skipped",
                )
                MainRun.Failed(
                    "the pointer draw timed out waiting for the main thread",
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            MainRun.Failed("the pointer draw was interrupted waiting for the main thread")
        }
    }

    companion object {
        /** Bounded wait for a draw to land on the main thread (mirrors showPointerNowDetailed). */
        const val MAIN_THREAD_TIMEOUT_MS = 2_000L
    }
}
