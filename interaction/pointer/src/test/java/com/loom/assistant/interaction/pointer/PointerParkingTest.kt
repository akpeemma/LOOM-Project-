package com.loom.assistant.interaction.pointer

/**
 * MASTER Wave 4 (F11 strengthening) — verified-only parking and
 * approval-card placement.
 *
 * Proves, without a phone:
 *  - the pointer never parks on an unverified element: no verified
 *    target → neutral home position (top-centre, off the content),
 *    never a random call-log row / post / banner;
 *  - an approval card never occludes the target being acted on: it
 *    docks on the opposite half from the target, and flags when the
 *    target must be re-verified after the card closes;
 *  - a detached / zero-size draw is a RECORDED failure, never a
 *    silent invalidate() no-op.
 *
 * Plain-main() pattern; see test-harness/run-2c-tests.sh `park`:
 *   kotlinc -d out -jvm-target 17 ObstructionLayout.kt PointerParking.kt PointerParkingTest.kt
 *   java -cp out com.loom.assistant.interaction.pointer.PointerParkingTestKt
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

fun main() {
    // ---- verified-only parking ----
    run {
        val target = GeomRect(100f, 500f, 400f, 560f)
        checkEq(
            "verified target → park on it",
            PointerParkingPolicy.decide(target),
            ParkingDecision.PARK_VERIFIED,
        )
        checkEq(
            "no verified target → park at home, never on a random element",
            PointerParkingPolicy.decide(null),
            ParkingDecision.PARK_HOME,
        )
    }

    // ---- neutral home position ----
    run {
        // 1080x2400 phone: home is top-centre, 10% down — clear of
        // the content centre where posts, contacts and results live.
        val home = PointerParkingPolicy.homeRect(1080f, 2400f)
        checkEq("home is horizontally centred", home.centerX, 540f)
        checkEq("home sits near the top", home.centerY, 240f)
        check("home is off the content centre", home.centerY < 2400f / 2f)
        check("home rect is small", home.width <= 60f && home.height <= 60f)
    }

    // ---- card placement: "My Mummy" case (video 540s) ----
    run {
        // The contact is mid-screen; the card must NOT sit on it.
        val target = GeomRect(60f, 1000f, 1020f, 1120f) // centre ~1060, just below mid (1200)? no: mid is 1200
        val placement = CardPlacement.decide(target, 1080f, 2400f, 500f)
        checkEq("target in top half → card docks at the bottom", placement.edge, CardEdge.BOTTOM)
        check("card band does not overlap the target", !placement.reverifyTargetAfterClose)
    }
    run {
        // The contact is in the BOTTOM half; the card docks at the top.
        val target = GeomRect(60f, 1800f, 1020f, 1920f)
        val placement = CardPlacement.decide(target, 1080f, 2400f, 500f)
        checkEq("target in bottom half → card docks at the top", placement.edge, CardEdge.TOP)
        check("card band does not overlap the target", !placement.reverifyTargetAfterClose)
    }
    run {
        // No target known: the card docks at the bottom (furthest
        // from the usual top-of-screen controls).
        val placement = CardPlacement.decide(null, 1080f, 2400f, 500f)
        checkEq("no target → card docks at the bottom", placement.edge, CardEdge.BOTTOM)
        checkEq("no target → no re-verify needed", placement.reverifyTargetAfterClose, false)
    }
    run {
        // A very tall target spans into the card's band: the card
        // still goes opposite, but the caller must re-verify the
        // target after the card closes instead of acting on a
        // possibly-covered element.
        val target = GeomRect(60f, 100f, 1020f, 2300f)
        val placement = CardPlacement.decide(target, 1080f, 2400f, 500f)
        check("tall target → re-verify after the card closes", placement.reverifyTargetAfterClose)
    }

    // ---- detached-draw guard ----
    run {
        checkEq(
            "attached + sized → no failure",
            DetachedDrawGuard.check(attached = true, widthPx = 1080, heightPx = 2400, method = "parkPointer"),
            null,
        )
        check(
            "detached draw → recorded failure naming the method",
            DetachedDrawGuard.check(attached = false, widthPx = 1080, heightPx = 2400, method = "parkPointer")
                ?.contains("parkPointer") == true,
        )
        check(
            "detached failure says the draw was dropped",
            DetachedDrawGuard.check(attached = false, widthPx = 1080, heightPx = 2400, method = "showStep")
                ?.contains("dropped") == true,
        )
        check(
            "zero-size window → recorded failure",
            DetachedDrawGuard.check(attached = true, widthPx = 0, heightPx = 0, method = "showStep") != null,
        )
    }

    println("PointerParkingTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        throw AssertionError("$failed test(s) failed")
    }
}
