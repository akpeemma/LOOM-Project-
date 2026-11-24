package com.loom.assistant.core.common

/**
 * Pure-JVM checks for AssistantActivation (the ACTIVATE ASSISTANT MODE
 * planner). No android.* imports, so no fakes are needed.
 * Run: kotlinc AssistantActivation.kt AssistantActivationTest.kt -d out
 *   && java -cp out com.loom.assistant.core.common.AssistantActivationTestKt
 *
 * Matrix (each row is one test):
 * 1. stopped + permission + lifecycle active + lastMode CHARACTER
 *    -> StartService("CHARACTER")
 * 2. stopped + permission + lifecycle active + lastMode null
 *    -> StartService("ORB") — the ModeController.getMode() default path
 * 3. stopped + permission + lifecycle active + lastMode blank
 *    -> StartService("ORB")
 * 4. stopped + NO permission -> Refused naming Settings
 *    ("Display over other apps")
 * 5. lifecycle INACTIVE -> Refused regardless of permission/running/mode
 *    (three rows: all-permission-running combos refused)
 * 6. running + permission + lifecycle active -> AlreadyRunning
 *    (regardless of lastBoundMode, incl. null)
 * 7. running + NO permission -> Refused (permission beats running)
 * 8. ack helpers: success ack names the verified mode; failure ack never
 *    claims active; short labels map ORB/CHARACTER/INVISIBLE.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun checkTrue(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL(bool): $name — expected true, got false")
    }
}

private fun plan(
    lifecycleActive: Boolean,
    permission: Boolean,
    running: Boolean,
    mode: String?,
): AssistantActivation.ActivationPlan =
    AssistantActivation.plan(lifecycleActive, permission, running, mode)

fun main() {
    // 1. Stopped + permission + active -> StartService(lastMode).
    check(
        "stopped+permission+active+CHARACTER -> StartService(CHARACTER)",
        plan(true, true, false, "CHARACTER"),
        AssistantActivation.ActivationPlan.StartService("CHARACTER"),
    )
    check(
        "stopped+permission+active+ORB -> StartService(ORB)",
        plan(true, true, false, "ORB"),
        AssistantActivation.ActivationPlan.StartService("ORB"),
    )
    check(
        "stopped+permission+active+INVISIBLE -> StartService(INVISIBLE)",
        plan(true, true, false, "INVISIBLE"),
        AssistantActivation.ActivationPlan.StartService("INVISIBLE"),
    )
    // 2/3. Null / blank lastMode -> the documented default path (ORB).
    check(
        "stopped+permission+active+null -> StartService(ORB)",
        plan(true, true, false, null),
        AssistantActivation.ActivationPlan.StartService("ORB"),
    )
    check(
        "stopped+permission+active+blank -> StartService(ORB)",
        plan(true, true, false, "   "),
        AssistantActivation.ActivationPlan.StartService("ORB"),
    )
    checkTrue(
        "DEFAULT_MODE_NAME is ORB",
        AssistantActivation.DEFAULT_MODE_NAME == "ORB",
    )
    // 4. Stopped + no permission -> Refused naming Settings.
    val noPerm = plan(true, false, false, "ORB")
    checkTrue(
        "stopped+no-permission -> Refused",
        noPerm is AssistantActivation.ActivationPlan.Refused,
    )
    checkTrue(
        "permission refusal names Display over other apps",
        (noPerm as? AssistantActivation.ActivationPlan.Refused)
            ?.reason?.contains("Display over other apps") == true,
    )
    checkTrue(
        "permission refusal names Settings",
        (noPerm as? AssistantActivation.ActivationPlan.Refused)
            ?.reason?.contains("Settings") == true,
    )
    // 5. Inactive lifecycle -> Refused regardless of other inputs.
    val inactiveAll = plan(false, true, true, "ORB")
    checkTrue(
        "inactive+permission+running -> Refused",
        inactiveAll is AssistantActivation.ActivationPlan.Refused,
    )
    val inactiveStopped = plan(false, true, false, "CHARACTER")
    checkTrue(
        "inactive+permission+stopped -> Refused",
        inactiveStopped is AssistantActivation.ActivationPlan.Refused,
    )
    val inactiveNoPerm = plan(false, false, false, null)
    checkTrue(
        "inactive+no-permission+stopped+null -> Refused",
        inactiveNoPerm is AssistantActivation.ActivationPlan.Refused,
    )
    checkTrue(
        "lifecycle refusal names launcher reactivation",
        (inactiveAll as? AssistantActivation.ActivationPlan.Refused)
            ?.reason?.contains("launcher") == true,
    )
    checkTrue(
        "lifecycle refusal never claims active",
        (inactiveAll as? AssistantActivation.ActivationPlan.Refused)
            ?.reason?.contains("active in") != true,
    )
    // 6. Running + permission + active -> AlreadyRunning.
    check(
        "running+permission+active -> AlreadyRunning",
        plan(true, true, true, "ORB"),
        AssistantActivation.ActivationPlan.AlreadyRunning,
    )
    check(
        "running+permission+active+null mode -> AlreadyRunning",
        plan(true, true, true, null),
        AssistantActivation.ActivationPlan.AlreadyRunning,
    )
    // 7. Running + no permission -> Refused (permission beats running).
    checkTrue(
        "running+no-permission -> Refused",
        plan(true, false, true, "ORB") is AssistantActivation.ActivationPlan.Refused,
    )
    // 8. Ack helpers.
    check(
        "success ack names verified mode",
        AssistantActivation.successAck("Orb"),
        "Floating assistant active in Orb mode.",
    )
    check(
        "success ack for Character",
        AssistantActivation.successAck("Character"),
        "Floating assistant active in Character mode.",
    )
    checkTrue(
        "failure ack never claims active",
        !AssistantActivation.failureAck("view did not attach")
            .contains("active in"),
    )
    check(
        "failure ack carries reason",
        AssistantActivation.failureAck("view did not attach"),
        "I couldn't start the floating assistant — view did not attach.",
    )
    check("shortLabel ORB", AssistantActivation.shortLabel("ORB"), "Orb")
    check(
        "shortLabel CHARACTER",
        AssistantActivation.shortLabel("CHARACTER"),
        "Character",
    )
    check(
        "shortLabel INVISIBLE",
        AssistantActivation.shortLabel("INVISIBLE"),
        "Invisible",
    )

    println("AssistantActivationTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
