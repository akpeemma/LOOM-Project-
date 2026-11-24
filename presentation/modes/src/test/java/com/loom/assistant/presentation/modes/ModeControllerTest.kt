package com.loom.assistant.presentation.modes

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.provider.Settings

/**
 * Stage 2A.1 (D1) — pure-JVM checks for ModeController with a fake backend.
 * Run: kotlinc <prod sources> <fakes> ModeControllerTest.kt -d out && java
 * -cp out com.loom.assistant.presentation.modes.ModeControllerTestKt
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) passed++
    else {
        failed++
        println("FAIL: $name $detail")
    }
}

/** In-memory SharedPreferences fake. */
class FakePrefs : SharedPreferences {
    val strings = mutableMapOf<String, String?>()
    val bools = mutableMapOf<String, Boolean>()

    override fun getString(key: String, defValue: String?): String? =
        if (strings.containsKey(key)) strings[key] else defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        bools.getOrDefault(key, defValue)

    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { strings[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { bools[key] = value }

            override fun apply() {}
        }
}

class FakeContext : Context() {
    val prefs = FakePrefs()
    var startServiceThrows = false
    val startedIntents = mutableListOf<android.content.Intent>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs

    override fun startService(intent: android.content.Intent): android.content.ComponentName? {
        if (startServiceThrows) throw RuntimeException("startService failed")
        startedIntents.add(intent)
        return null
    }

    override fun startForegroundService(intent: android.content.Intent): android.content.ComponentName? {
        if (startServiceThrows) throw RuntimeException("startForegroundService failed")
        startedIntents.add(intent)
        return null
    }
}

/** Simulates a healthy overlay service: boundMode tracks the persisted pref. */
class FakeBackend(
    var permission: Boolean = true,
    var attached: Boolean = true,
    private val ctx: FakeContext,
) : ModeBackend {
    override fun hasOverlayPermission(): Boolean = permission
    override fun requestServiceStart(context: Context): Boolean = true
    override fun boundMode(): AssistantMode? = ModeController.getMode(ctx)
    override fun isViewAttached(mode: AssistantMode): Boolean = attached
}

private fun reset() {
    ModeController.registerBackend(null)
    Handler.clear()
    Settings.canDrawOverlaysResult = true
    android.os.Build.VERSION.SDK_INT = 34
}

private fun applyOnce(ctx: FakeContext, target: AssistantMode): List<ModeResult> {
    val results = mutableListOf<ModeResult>()
    var calls = 0
    ModeController.applyMode(ctx, target) {
        calls++
        results.add(it)
    }
    check("callback invoked exactly once ($target)", calls == 1, "calls=$calls")
    return results
}

fun main() {
    // ---- 1. all six transitions (each target from a live backend) -> Success ----
    for (target in AssistantMode.values()) {
        reset()
        val ctx = FakeContext()
        ModeController.registerBackend(FakeBackend(ctx = ctx))
        val results = applyOnce(ctx, target)
        check("$target -> Success", results == listOf(ModeResult.Success), "got=$results")
        check("boundMode() == $target after switch", ModeController.boundMode() == target)
    }
    // chained: ORB -> CHARACTER -> INVISIBLE
    reset()
    run {
        val ctx = FakeContext()
        ModeController.registerBackend(FakeBackend(ctx = ctx))
        for (target in listOf(AssistantMode.ORB, AssistantMode.CHARACTER, AssistantMode.INVISIBLE)) {
            val results = applyOnce(ctx, target)
            check("chain $target -> Success", results == listOf(ModeResult.Success), "got=$results")
        }
    }

    // ---- 2. permission denied -> PermissionRequired, NO pref write ----
    reset()
    run {
        val ctx = FakeContext()
        ModeController.registerBackend(FakeBackend(permission = false, ctx = ctx))
        val results = applyOnce(ctx, AssistantMode.ORB)
        check(
            "denied -> PermissionRequired",
            results == listOf(ModeResult.PermissionRequired),
            "got=$results",
        )
        check(
            "no pref write on denial",
            !ctx.prefs.strings.containsKey("active_mode"),
            "prefs=${ctx.prefs.strings}",
        )
    }

    // ---- 3. backend reports unattached view -> Failed ----
    reset()
    run {
        val ctx = FakeContext()
        ModeController.registerBackend(FakeBackend(attached = false, ctx = ctx))
        val results = applyOnce(ctx, AssistantMode.CHARACTER)
        check(
            "unattached -> Failed",
            results.singleOrNull() is ModeResult.Failed,
            "got=$results",
        )
    }

    // ---- 4. service start throws -> Unavailable("the floating assistant failed to start") ----
    reset()
    run {
        val ctx = FakeContext().apply { startServiceThrows = true }
        // no backend registered -> cold start path
        val results = applyOnce(ctx, AssistantMode.ORB)
        check(
            "start failure -> Unavailable",
            results == listOf(ModeResult.Unavailable("the floating assistant failed to start")),
            "got=$results",
        )
        check("no poll queued after start failure", Handler.pending() == 0)
    }

    // ---- 5. cold start: backend registers mid-poll -> Success ----
    reset()
    run {
        val ctx = FakeContext()
        val results = mutableListOf<ModeResult>()
        var calls = 0
        ModeController.applyMode(ctx, AssistantMode.INVISIBLE) {
            calls++
            results.add(it)
        }
        check("start intent sent", ctx.startedIntents.size == 1, "intents=${ctx.startedIntents.size}")
        check("no result before backend registers", results.isEmpty(), "got=$results")
        Handler.runNext() // poll 1: no backend yet
        check("still waiting after poll 1", results.isEmpty(), "got=$results")
        ModeController.registerBackend(FakeBackend(ctx = ctx))
        Handler.runNext() // poll 2: backend present -> verify
        check("cold start -> Success", results == listOf(ModeResult.Success), "got=$results")
        check("callback invoked exactly once (cold)", calls == 1, "calls=$calls")
    }

    // ---- 6. cold start timeout -> Unavailable("the floating assistant did not start") ----
    reset()
    run {
        val ctx = FakeContext()
        val results = mutableListOf<ModeResult>()
        var calls = 0
        ModeController.applyMode(ctx, AssistantMode.ORB) {
            calls++
            results.add(it)
        }
        Handler.drain(30) // more than the 20-poll budget
        check(
            "timeout -> Unavailable",
            results == listOf(ModeResult.Unavailable("the floating assistant did not start")),
            "got=$results",
        )
        check("callback invoked exactly once (timeout)", calls == 1, "calls=$calls")
    }

    reset()
    println("ModeControllerTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
