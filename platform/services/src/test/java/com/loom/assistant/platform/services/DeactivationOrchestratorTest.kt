package com.loom.assistant.platform.services

/**
 * JVM tests for the deactivation orchestration (§4.2-F, §4.5).
 *
 * Proves: the 8 deactivation steps run in plan order, purgeAll("deactivate")
 * is called BEFORE the ASSISTANT_MODE_INACTIVE transition (purge-before-flip
 * ordering — no stale authorization survives), every step failure is named,
 * one bounded retry applies, and reachedInactive is true only when every
 * step passed.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name $detail")
    }
}

private val DEACTIVATION_STEP_NAMES = listOf(
    "end voice session",
    "microphone off",
    "stop TTS + clear queue",
    "halt live sequences",
    "AuthGate purgeAll",
    "drain action queue",
    "hide floating views",
    "enter ASSISTANT_MODE_INACTIVE",
)

/** Builds the 8-step deactivation sequence with injectable effects. */
private fun deactivationSteps(
    effects: MutableList<String>,
    failStep: String? = null,
): List<TeardownStep> {
    var purgeReason: String? = null
    return DEACTIVATION_STEP_NAMES.map { name ->
        TeardownStep(
            name = name,
            destructive = name == "AuthGate purgeAll" ||
                name == "drain action queue" ||
                name == "hide floating views" ||
                name == "enter ASSISTANT_MODE_INACTIVE",
            expected = when (name) {
                "AuthGate purgeAll" -> "pending confirmations cancelled, issued tokens revoked"
                "enter ASSISTANT_MODE_INACTIVE" -> "lifecycle == ASSISTANT_MODE_INACTIVE"
                else -> "done"
            },
            run = {
                effects.add(name)
                if (name == "AuthGate purgeAll") {
                    purgeReason = "deactivate"
                    effects.add("purgeReason=$purgeReason")
                }
                if (name == failStep) throw IllegalStateException("injected: $name refuses")
            },
            verify = { name != failStep },
        )
    }
}

fun main() {
    // 1. All steps pass → reachedInactive, plan order preserved.
    run {
        val effects = mutableListOf<String>()
        val report = DeactivationOrchestrator.execute(deactivationSteps(effects))
        check("all-pass: reachedInactive", report.reachedInactive)
        check(
            "all-pass: plan order",
            effects.filter { it in DEACTIVATION_STEP_NAMES } == DEACTIVATION_STEP_NAMES,
            effects.toString(),
        )
        check(
            "all-pass: purge reason is 'deactivate'",
            effects.contains("purgeReason=deactivate"),
            effects.toString(),
        )
        check(
            "all-pass: purge ran BEFORE the inactive transition",
            effects.indexOf("AuthGate purgeAll") < effects.indexOf("enter ASSISTANT_MODE_INACTIVE"),
            effects.toString(),
        )
    }

    // 2. A failing step → named, one retry, NOT reachedInactive.
    run {
        val effects = mutableListOf<String>()
        val report = DeactivationOrchestrator.execute(
            deactivationSteps(effects, failStep = "hide floating views"),
        )
        check("fail: not reachedInactive", !report.reachedInactive)
        check("fail: exactly one failed step", report.failed.size == 1)
        check(
            "fail: component named",
            report.failed.single().name == "hide floating views",
            report.failed.single().name,
        )
        check(
            "fail: retried once",
            report.failed.single().attempts == 2,
            "attempts=${report.failed.single().attempts}",
        )
        check(
            "fail: later steps still attempted (report completeness)",
            report.steps.size == DEACTIVATION_STEP_NAMES.size,
        )
    }

    // 3. Purge failure → inactive transition must not be claimed.
    run {
        val effects = mutableListOf<String>()
        val report = DeactivationOrchestrator.execute(
            deactivationSteps(effects, failStep = "AuthGate purgeAll"),
        )
        check("purge-fail: not reachedInactive", !report.reachedInactive)
        check(
            "purge-fail: purge named",
            report.failed.any { it.name == "AuthGate purgeAll" },
        )
    }

    println("deactivation: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILURES: $failed")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
