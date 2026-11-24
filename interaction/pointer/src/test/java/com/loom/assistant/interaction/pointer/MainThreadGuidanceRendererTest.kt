package com.loom.assistant.interaction.pointer

import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pointer-drive fix (2026-11-06) — MainThreadGuidanceRenderer checks.
 *
 * Covers: a draw issued from a background thread lands on the main
 * thread (and the caller blocks until it ran — no fire-and-forget);
 * a draw issued on the main thread runs inline (production marshaller
 * + fake Looper); a marshal timeout skips the draw, records a named
 * failure and never throws; a throwing inner draw is recorded, never
 * propagated; an inner draw failure (e.g. a HIDE swallow) surfaces
 * through lastDrawFailure; a successful draw clears a previous
 * failure; cursor reads marshal too.
 *
 * Run (from loom-android):
 *   KOTLINC=$HOME/workspace/brahma/loom/toolchain/kotlin/kotlinc/bin/kotlinc
 *   JAVA=$HOME/workspace/brahma/loom/toolchain/jdk-17/bin/java
 *   STDLIB=$HOME/workspace/brahma/loom/toolchain/kotlin/kotlinc/lib/kotlin-stdlib.jar
 *   R=$HOME/workspace/brahma/loom/build/loom-android
 *   H=$HOME/workspace/brahma/loom/build/test-harness
 *   $KOTLINC -d out -jvm-target 17 -cp $STDLIB \
 *     $H/fakes/android/graphics/Rect.kt $H/fakes/android/graphics/RectF.kt \
 *     $H/fakes/android/os/Handler.kt $H/fakes/android/os/Looper.kt \
 *     $H/fakes/android/util/Log.kt \
 *     $R/core/common/src/main/java/com/loom/assistant/core/common/PointerMove.kt \
 *     $R/interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     $R/interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerMotion.kt \
 *     $R/interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceRendererContract.kt \
 *     $R/interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/MainThreadGuidanceRenderer.kt \
 *     $R/interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/MainThreadGuidanceRendererTest.kt \
 *   && $JAVA -cp out:$STDLIB com.loom.assistant.interaction.pointer.MainThreadGuidanceRendererTestKt
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

/** A fake main thread: a single thread named "fake-main" that runs posted actions. */
private class FakeMainMarshaller : MainThreadMarshaller {
    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }

    /** When non-null, the action never runs — Failed with this reason (a marshal timeout). */
    var failWith: String? = null

    /** Threads the actions actually ran on, in order. */
    val runThreads = mutableListOf<String>()

    override fun <T> runOnMain(action: () -> T): MainRun<T> {
        val failure = failWith
        if (failure != null) return MainRun.Failed(failure)
        val future = mainExecutor.submit<T> {
            runThreads.add(Thread.currentThread().name)
            action()
        }
        return try {
            MainRun.Ran(future.get(5, TimeUnit.SECONDS))
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    fun shutdown() {
        mainExecutor.shutdownNow()
    }
}

private class FakeInner : GuidanceRendererContract {
    val calls = mutableListOf<String>()
    val callThreads = mutableListOf<String>()
    var throwOn: String? = null
    var lastFailure: String? = null
    var cursor: GeomPoint? = GeomPoint(200f, 230f)

    private fun record(name: String) {
        calls.add(name)
        callThreads.add(Thread.currentThread().name)
        if (throwOn == name) throw IllegalStateException("fake inner threw on $name")
    }

    override fun showStep(anchorX: Float, anchorY: Float, target: RectF, stepIndex: Int, stepCount: Int, instruction: String) = record("showStep")
    override fun hide() = record("hide")
    override fun previewMoveTo(target: RectF) = record("previewMoveTo")
    override fun pulseOnResult() = record("pulseOnResult")
    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) = record("onAnchorChanged")
    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean {
        record("nudgePointer")
        return true
    }

    override fun showIdleIndicator(): Boolean {
        record("showIdleIndicator")
        return true
    }

    override fun showTapRipple() = record("showTapRipple")
    override fun showPressState(holdMs: Long) = record("showPressState")
    override fun setStatusNarration(text: String) = record("setStatusNarration")
    override fun showSelection(target: RectF, label: String) = record("showSelection")
    override fun parkPointer(target: RectF, label: String) = record("parkPointer")
    override fun clearParked() = record("clearParked")
    override fun cursorPosition(): GeomPoint? {
        record("cursorPosition")
        return cursor
    }

    override fun lastDrawFailure(): String? = lastFailure
}

private val TARGET = RectF(100f, 200f, 300f, 260f)

fun main() {
    // ---------- 1. background-thread draw lands on the main thread ----------
    run {
        val inner = FakeInner()
        val marshaller = FakeMainMarshaller()
        val wrapper = MainThreadGuidanceRenderer(inner, marshaller)
        var callerThread = ""
        var threw: Throwable? = null
        val done = CountDownLatch(1)
        Thread({
            try {
                callerThread = Thread.currentThread().name
                wrapper.parkPointer(TARGET, "Tap the song")
            } catch (t: Throwable) {
                threw = t
            } finally {
                done.countDown()
            }
        }, "loom-plan-runner").start()
        check("background draw returned", done.await(5, TimeUnit.SECONDS))
        checkEq("background draw did not throw", threw, null)
        checkEq("background draw ran on fake-main", marshaller.runThreads, listOf("fake-main"))
        checkEq("background draw reached the inner renderer", inner.calls, listOf("parkPointer"))
        check("caller was not the main thread", callerThread == "loom-plan-runner")
        checkEq("no draw failure after a landed draw", wrapper.lastDrawFailure(), null)
        marshaller.shutdown()
    }

    // ---------- 2. every draw method marshals ----------
    run {
        val inner = FakeInner()
        val marshaller = FakeMainMarshaller()
        val wrapper = MainThreadGuidanceRenderer(inner, marshaller)
        val done = CountDownLatch(1)
        Thread({
            wrapper.showStep(0f, 0f, TARGET, 1, 3, "Tap the song")
            wrapper.hide()
            wrapper.previewMoveTo(TARGET)
            wrapper.pulseOnResult()
            wrapper.onAnchorChanged(1f, 2f)
            wrapper.nudgePointer(PointerDirection.RIGHT, 48f)
            wrapper.showIdleIndicator()
            wrapper.showTapRipple()
            wrapper.showPressState(500L)
            wrapper.setStatusNarration("Tapping…")
            wrapper.showSelection(TARGET, "Song")
            wrapper.parkPointer(TARGET, "Tap the song")
            wrapper.clearParked()
            done.countDown()
        }, "worker").start()
        check("all draws returned", done.await(5, TimeUnit.SECONDS))
        checkEq(
            "every draw reached the inner renderer",
            inner.calls,
            listOf(
                "showStep", "hide", "previewMoveTo", "pulseOnResult",
                "onAnchorChanged", "nudgePointer", "showIdleIndicator",
                "showTapRipple", "showPressState", "setStatusNarration",
                "showSelection", "parkPointer", "clearParked",
            ),
        )
        check("every draw ran on fake-main", marshaller.runThreads.all { it == "fake-main" })
        checkEq("no draw failure after landed draws", wrapper.lastDrawFailure(), null)
        marshaller.shutdown()
    }

    // ---------- 3. production marshaller runs inline on the main thread ----------
    run {
        // The harness fake Looper reports myLooper() == main always, so
        // the production marshaller takes its inline path here.
        val inner = FakeInner()
        val wrapper = MainThreadGuidanceRenderer(inner, AndroidMainThreadMarshaller())
        wrapper.parkPointer(TARGET, "Tap the song")
        checkEq("inline draw reached the inner renderer", inner.calls, listOf("parkPointer"))
        checkEq("inline draw ran on the calling thread", inner.callThreads, listOf(Thread.currentThread().name))
        checkEq("no draw failure after an inline draw", wrapper.lastDrawFailure(), null)
    }

    // ---------- 4. marshal timeout: draw skipped, failure named, never throws ----------
    run {
        val inner = FakeInner()
        val marshaller = FakeMainMarshaller().also {
            it.failWith = "the pointer draw timed out waiting for the main thread"
        }
        val wrapper = MainThreadGuidanceRenderer(inner, marshaller)
        var threw: Throwable? = null
        try {
            wrapper.parkPointer(TARGET, "Tap the song")
            wrapper.showTapRipple()
        } catch (t: Throwable) {
            threw = t
        }
        checkEq("timed-out draw did not throw", threw, null)
        checkEq("timed-out draw never reached the inner renderer", inner.calls, emptyList<String>())
        check(
            "timed-out draw names the timeout",
            wrapper.lastDrawFailure()?.contains("timed out") == true,
        )
        checkEq("timed-out boolean draw answers false", wrapper.nudgePointer(PointerDirection.RIGHT, 48f), false)
        checkEq("timed-out idle draw answers false", wrapper.showIdleIndicator(), false)
        checkEq("timed-out cursor read answers null", wrapper.cursorPosition(), null)
        marshaller.shutdown()
    }

    // ---------- 5. throwing inner draw is recorded, never propagated ----------
    run {
        val inner = FakeInner().also { it.throwOn = "parkPointer" }
        val marshaller = FakeMainMarshaller()
        val wrapper = MainThreadGuidanceRenderer(inner, marshaller)
        var threw: Throwable? = null
        try {
            wrapper.parkPointer(TARGET, "Tap the song")
        } catch (t: Throwable) {
            threw = t
        }
        checkEq("throwing draw did not propagate", threw, null)
        check(
            "throwing draw names itself",
            wrapper.lastDrawFailure()?.contains("IllegalStateException") == true,
        )
        marshaller.shutdown()
    }

    // ---------- 6. inner draw failure (e.g. HIDE swallow) surfaces ----------
    run {
        val inner = FakeInner().also {
            it.lastFailure = "the pointer is hidden by your pointer setting"
        }
        val marshaller = FakeMainMarshaller()
        val wrapper = MainThreadGuidanceRenderer(inner, marshaller)
        wrapper.parkPointer(TARGET, "Tap the song")
        checkEq(
            "inner failure surfaces through the wrapper",
            wrapper.lastDrawFailure(),
            "the pointer is hidden by your pointer setting",
        )
        marshaller.shutdown()
    }

    // ---------- 7. a successful draw clears a previous failure ----------
    run {
        val inner = FakeInner()
        val marshaller = FakeMainMarshaller().also {
            it.failWith = "the pointer draw timed out waiting for the main thread"
        }
        val wrapper = MainThreadGuidanceRenderer(inner, marshaller)
        wrapper.parkPointer(TARGET, "Tap the song")
        check("failure recorded after the timeout", wrapper.lastDrawFailure() != null)
        marshaller.failWith = null
        wrapper.parkPointer(TARGET, "Tap the song")
        checkEq("successful draw clears the failure", wrapper.lastDrawFailure(), null)
        checkEq("retried draw reached the inner renderer", inner.calls, listOf("parkPointer"))
        marshaller.shutdown()
    }

    // ---------- 8. cursor read from a background thread returns the inner value ----------
    run {
        val inner = FakeInner()
        val marshaller = FakeMainMarshaller()
        val wrapper = MainThreadGuidanceRenderer(inner, marshaller)
        var seen: GeomPoint? = null
        val done = CountDownLatch(1)
        Thread({
            seen = wrapper.cursorPosition()
            done.countDown()
        }, "worker").start()
        check("cursor read returned", done.await(5, TimeUnit.SECONDS))
        checkEq("cursor read returns the inner position", seen, GeomPoint(200f, 230f))
        checkEq("cursor read ran on fake-main", marshaller.runThreads, listOf("fake-main"))
        checkEq(
            "cursor read over the target passes",
            wrapper.isCursorOver(TARGET),
            true,
        )
        marshaller.shutdown()
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$passed passed, $failed failed")
}
