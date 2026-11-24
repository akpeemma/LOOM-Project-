package com.loom.assistant.execution.interaction

/**
 * Stage 2C T2 — ActionPlan model tests (plan §6).
 *
 * Covers: ordered steps, ElementQuery/targetQuery retention, confirmClass
 * per step, sensitive/out-of-scope flagging via effectiveConfirmClass.
 * Pure data model — directly JVM-testable, no fakes.
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

private fun step(
    id: String,
    query: String = "toggle",
    targetPackage: String? = null,
    confirmClass: StepConfirmClass = StepConfirmClass.SEQUENCE,
) = PlanStep(
    id = id,
    targetQuery = query,
    instruction = "Tap $query",
    targetPackage = targetPackage,
    confirmClass = confirmClass,
)

private fun plan(vararg steps: PlanStep, demoApp: String = "com.demo.app") =
    ActionPlan(id = "p1", goal = "demo goal", demoAppPackage = demoApp, steps = steps.toList())

fun main() {
    // ---- ordered steps preserved ----
    val p = plan(step("s1"), step("s2"), step("s3"))
    check("steps keep insertion order", p.steps.map { it.id } == listOf("s1", "s2", "s3"))
    check("targetQuery retained", p.steps[1].targetQuery == "toggle")
    check("default kind is TAP", p.steps.all { it.kind == PlanStepKind.TAP })
    check("goal retained", p.goal == "demo goal")
    check("demoAppPackage retained", p.demoAppPackage == "com.demo.app")

    // ---- init guards ----
    var threw = false
    try {
        plan()
    } catch (_: IllegalArgumentException) {
        threw = true
    }
    check("empty plan rejected", threw)

    threw = false
    try {
        plan(step("s1"), step("s1"))
    } catch (_: IllegalArgumentException) {
        threw = true
    }
    check("duplicate step ids rejected", threw)

    // ---- effectiveConfirmClass: in-app, non-sensitive → SEQUENCE ----
    val inApp = step("a")
    check(
        "in-app step is SEQUENCE",
        p.effectiveConfirmClass(inApp, isSensitiveTarget = false) == StepConfirmClass.SEQUENCE,
    )

    // ---- out-of-scope (non-sensitive) → PER_STEP ----
    val oos = step("b", targetPackage = "com.other.app")
    check(
        "out-of-scope step is PER_STEP",
        p.effectiveConfirmClass(oos, isSensitiveTarget = false) == StepConfirmClass.PER_STEP,
    )
    val sameApp = step("c", targetPackage = "com.demo.app")
    check(
        "declared same-app target is SEQUENCE",
        p.effectiveConfirmClass(sameApp, isSensitiveTarget = false) == StepConfirmClass.SEQUENCE,
    )

    // ---- sensitive target → GUIDANCE_ONLY, even when out-of-scope ----
    // (hard rule: sensitive packages NEVER escalate to a confirmation card)
    check(
        "sensitive in-app step is GUIDANCE_ONLY",
        p.effectiveConfirmClass(inApp, isSensitiveTarget = true) == StepConfirmClass.GUIDANCE_ONLY,
    )
    check(
        "sensitive out-of-scope step is GUIDANCE_ONLY, not PER_STEP",
        p.effectiveConfirmClass(oos, isSensitiveTarget = true) == StepConfirmClass.GUIDANCE_ONLY,
    )

    // ---- REFUSED is sticky ----
    val refused = step("d", confirmClass = StepConfirmClass.REFUSED)
    check(
        "REFUSED stays REFUSED even when sensitive",
        p.effectiveConfirmClass(refused, isSensitiveTarget = true) == StepConfirmClass.REFUSED,
    )
    check(
        "REFUSED stays REFUSED when in-app",
        p.effectiveConfirmClass(refused, isSensitiveTarget = false) == StepConfirmClass.REFUSED,
    )

    println("ActionPlanTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
