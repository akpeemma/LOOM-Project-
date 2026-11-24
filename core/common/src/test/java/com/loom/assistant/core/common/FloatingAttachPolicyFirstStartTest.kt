package com.loom.assistant.core.common

/**
 * First-start attach proof — pure-JVM checks for FloatingAttachPolicy +
 * FloatingTapRouter.
 * Run: kotlinc FloatingTapRouter.kt FloatingAttachPolicy.kt
 *   FloatingAttachPolicyFirstStartTest.kt -d out
 *   && java -cp out:kotlin-stdlib.jar
 *   com.loom.assistant.core.common.FloatingAttachPolicyFirstStartTestKt
 * (No android.* imports, so no fakes are needed.)
 *
 * What this proves — FIRST START, not tap-after-switch:
 * FloatingAttachPolicy is stateless: "fresh service state" is the input
 * tuple (activeKind = null, attachedKind = null), i.e. a service that
 * has never bound anything. The FIRST assertions in main() evaluate
 * exactly that tuple and the very first router call in the process:
 * fresh state binds CHARACTER, and that first attach's tap routes to
 * TOGGLE_FLOATING_VOICE. NO mode switch is simulated anywhere in this
 * test — there is no bind-then-switch-then-tap sequence at all. The
 * later cases are independent input tuples to the pure policy (not a
 * flow): they pin the kind-exact skip, the hidden-state rebind, and
 * the post-attach verification that catches a handler-less attach.
 * A test that switched first and then asserted tap works would NOT
 * satisfy this task and is deliberately not written here.
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

fun main() {
    // ---- 1. FRESH first attach: zero prior binds, zero switches. ----
    // Fresh service state: no active kind, no attached view.
    check(
        "FIRST-START: fresh state binds CHARACTER (never skipped away)",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.CHARACTER,
            null,
            null,
        ),
        true,
    )
    check(
        "FIRST-START: fresh state binds ORB",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.ORB,
            null,
            null,
        ),
        true,
    )
    check(
        "FIRST-START: fresh state binds INVISIBLE",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.EDGE_LIGHT,
            null,
            null,
        ),
        true,
    )

    // ---- 2. The first attach's tap routes to voice — first router call. ----
    check(
        "FIRST-START: first character tap routes to TOGGLE_FLOATING_VOICE",
        FloatingTapRouter.tapAction(FloatingSurface.CHARACTER),
        TapAction.TOGGLE_FLOATING_VOICE,
    )
    check(
        "FIRST-START: first character tap routes exactly like orb tap",
        FloatingTapRouter.tapAction(FloatingSurface.CHARACTER),
        FloatingTapRouter.tapAction(FloatingSurface.ORB),
    )
    check(
        "FIRST-START: first character long-press still opens the panel",
        FloatingTapRouter.longPressAction(FloatingSurface.CHARACTER),
        TapAction.OPEN_PANEL,
    )

    // ---- 3. Post-attach verification on the first attach. ----
    // A fully bound character view passes verification.
    check(
        "FIRST-START: fully bound character passes verification",
        FloatingAttachPolicy.bindingComplete(
            FloatingAttachKind.CHARACTER,
            setOf(
                FloatingGesture.TAP,
                FloatingGesture.LONG_PRESS,
                FloatingGesture.DRAG,
            ),
        ),
        true,
    )
    // A character view missing long-press FAILS verification — this is
    // the handler-less attach the service must rebind, never report.
    check(
        "FIRST-START: character missing LONG_PRESS fails verification",
        FloatingAttachPolicy.bindingComplete(
            FloatingAttachKind.CHARACTER,
            setOf(FloatingGesture.TAP, FloatingGesture.DRAG),
        ),
        false,
    )
    // A character view with no gestures at all FAILS verification.
    check(
        "FIRST-START: character with no gestures fails verification",
        FloatingAttachPolicy.bindingComplete(
            FloatingAttachKind.CHARACTER,
            emptySet<FloatingGesture>(),
        ),
        false,
    )
    // Required-gesture sets are exactly the service contract.
    check(
        "character requires TAP + LONG_PRESS + DRAG",
        FloatingAttachPolicy.requiredGestures(FloatingAttachKind.CHARACTER),
        setOf(
            FloatingGesture.TAP,
            FloatingGesture.LONG_PRESS,
            FloatingGesture.DRAG,
        ),
    )
    check(
        "orb requires TAP + DRAG (no panel gesture)",
        FloatingAttachPolicy.requiredGestures(FloatingAttachKind.ORB),
        setOf(FloatingGesture.TAP, FloatingGesture.DRAG),
    )
    check(
        "edge light requires no gestures",
        FloatingAttachPolicy.requiredGestures(FloatingAttachKind.EDGE_LIGHT),
        emptySet<FloatingGesture>(),
    )
    check(
        "orb missing DRAG fails verification",
        FloatingAttachPolicy.bindingComplete(
            FloatingAttachKind.ORB,
            setOf(FloatingGesture.TAP),
        ),
        false,
    )
    check(
        "edge light always verifies (nothing to carry)",
        FloatingAttachPolicy.bindingComplete(
            FloatingAttachKind.EDGE_LIGHT,
            emptySet<FloatingGesture>(),
        ),
        true,
    )

    // ---- 4. Kind-exact skip table (independent input tuples, no flow). ----
    // The exact already-bound state is the ONLY skip.
    check(
        "already-bound CHARACTER is skipped",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.CHARACTER,
            FloatingAttachKind.CHARACTER,
            FloatingAttachKind.CHARACTER,
        ),
        false,
    )
    // THE OLD HOLE, CLOSED: same mode name but the WRONG kind attached
    // must rebind, not skip. The old kind-blind check skipped here.
    check(
        "CHARACTER requested but ORB attached must REBIND (old hole)",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.CHARACTER,
            FloatingAttachKind.CHARACTER,
            FloatingAttachKind.ORB,
        ),
        true,
    )
    check(
        "ORB requested but CHARACTER attached must REBIND",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.ORB,
            FloatingAttachKind.ORB,
            FloatingAttachKind.CHARACTER,
        ),
        true,
    )
    // Hidden state (mode remembered, no view attached) must rebind —
    // SHOW/HIDE restore is preserved, not skipped away.
    check(
        "CHARACTER with no attached view must REBIND (hidden-state restore)",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.CHARACTER,
            FloatingAttachKind.CHARACTER,
            null,
        ),
        true,
    )
    // A different requested mode always binds, even over an attached view.
    check(
        "ORB requested over attached CHARACTER must BIND",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.ORB,
            FloatingAttachKind.CHARACTER,
            FloatingAttachKind.CHARACTER,
        ),
        true,
    )
    check(
        "already-bound INVISIBLE edge light is skipped (no remove+re-add)",
        FloatingAttachPolicy.shouldBind(
            FloatingAttachKind.EDGE_LIGHT,
            FloatingAttachKind.EDGE_LIGHT,
            FloatingAttachKind.EDGE_LIGHT,
        ),
        false,
    )

    println("FloatingAttachPolicyFirstStartTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
