package com.loom.assistant.execution.interaction

/**
 * In-app routing (fix plan item 5, pointer H4) — JVM checks for the
 * registry route the typed-chat GUIDE_TO path and the in-app voice
 * sessions share: register a layer, startGuidance via the registry,
 * and the sequence is live. No layer registered → the route is
 * honestly absent (the screens check for null before claiming
 * guidance started).
 *
 * Compiles the REAL InteractionLayer + InteractionLayerRegistry
 * (see test-harness/run-2c-tests.sh, r_ixnreg).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected true, got false")
    }
}

private fun testRegistryStartsGuidance() {
    InteractionLayerRegistry.register(null)
    val layer = InteractionLayer()
    InteractionLayerRegistry.register(layer)
    try {
        check(
            "registry returns the registered layer",
            InteractionLayerRegistry.get() === layer,
        )
        InteractionLayerRegistry.get()?.startGuidance("Settings")
        check(
            "startGuidance via the registry starts a sequence",
            layer.isSequenceActive,
        )
        check("active query is the target", layer.activeQuery == "Settings")
        layer.endSequence()
        check("endSequence clears the sequence", !layer.isSequenceActive)
    } finally {
        InteractionLayerRegistry.register(null)
    }
}

private fun testRegistryEmptyIsNull() {
    InteractionLayerRegistry.register(null)
    check("no layer registered → get() is null", InteractionLayerRegistry.get() == null)
}

fun main() {
    testRegistryStartsGuidance()
    testRegistryEmptyIsNull()

    println("InteractionLayerRegistryTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
