package com.loom.assistant.interaction.pointer

/**
 * Stage 2C T7 — AnchorResolver tests (criterion F1).
 *
 * Covers: per-mode anchors (character center / orb center / screen-edge
 * midpoint for Invisible); anchor changes are EVENTS the renderer animates
 * (never teleports); the transition duration constant; the ease curve shape;
 * null view positions fall back to the edge.
 *
 * Run: kotlinc ObstructionLayout.kt AnchorResolver.kt AnchorResolverTest.kt
 *   -d out && java -cp out
 *   com.loom.assistant.interaction.pointer.AnchorResolverTestKt
 */

private class FakePositions(
    var character: GeomPoint? = GeomPoint(540f, 300f),
    var orb: GeomPoint? = GeomPoint(540f, 1600f),
    var size: GeomSize = GeomSize(1080f, 2400f),
) : AnchorPositions {
    override fun characterCenter(): GeomPoint? = character
    override fun orbCenter(): GeomPoint? = orb
    override fun windowSize(): GeomSize = size
}

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun approx(a: Float, b: Float, tol: Float = 0.01f): Boolean =
    kotlin.math.abs(a - b) <= tol

fun main() {
    // 1. Per-mode anchors.
    run {
        val r = AnchorResolver(FakePositions())
        check(
            "CHARACTER anchor is the character center",
            r.anchorFor(GuidanceMode.CHARACTER) == GeomPoint(540f, 300f),
        )
        check(
            "ORB anchor is the orb center",
            r.anchorFor(GuidanceMode.ORB) == GeomPoint(540f, 1600f),
        )
        val inv = r.anchorFor(GuidanceMode.INVISIBLE)
        check("INVISIBLE anchor is the left screen-edge midpoint", inv == GeomPoint(4f, 1200f))
    }

    // 2. Missing view positions fall back to the edge midpoint.
    run {
        val r = AnchorResolver(FakePositions(character = null, orb = null))
        check(
            "null character center falls back to edge",
            r.anchorFor(GuidanceMode.CHARACTER) == GeomPoint(4f, 1200f),
        )
        check(
            "null orb center falls back to edge",
            r.anchorFor(GuidanceMode.ORB) == GeomPoint(4f, 1200f),
        )
    }

    // 3. First resolution emits no event (placement, not a change).
    run {
        val r = AnchorResolver(FakePositions())
        var events = 0
        r.listener = { events++ }
        val a = r.resolveInitial(GuidanceMode.CHARACTER)
        check("resolveInitial returns the anchor", a == GeomPoint(540f, 300f))
        check("resolveInitial emits no event", events == 0)
        check("no event from onModeChanged for the same mode", r.onModeChanged(GuidanceMode.CHARACTER) == null)
        check("same-mode change emits nothing to the listener", events == 0)
    }

    // 4. Mode change emits a Moved event with the 300ms transition — the
    // renderer animates it; nothing teleports.
    run {
        val r = AnchorResolver(FakePositions())
        val seen = mutableListOf<AnchorEvent>()
        r.listener = { seen.add(it) }
        r.resolveInitial(GuidanceMode.CHARACTER)
        val ev = r.onModeChanged(GuidanceMode.ORB)
        check("mode change emits an event", ev is AnchorEvent.Moved)
        val moved = ev as AnchorEvent.Moved
        check("event carries the from anchor", moved.from == GeomPoint(540f, 300f))
        check("event carries the to anchor", moved.to == GeomPoint(540f, 1600f))
        check(
            "event duration is the transition constant",
            moved.durationMs == AnchorResolver.TRANSITION_MS,
        )
        check("listener received the event", seen.size == 1 && seen[0] == moved)
        check("last anchor tracks the move", r.lastResolved() == GeomPoint(540f, 1600f))
    }

    // 5. The transition constant is the choreography contract's ~300ms.
    run {
        check("TRANSITION_MS is 300", AnchorResolver.TRANSITION_MS == 300L)
    }

    // 6. Ease curve: starts at `from`, ends at `to`, eases (slow ends).
    run {
        val from = GeomPoint(0f, 0f)
        val to = GeomPoint(100f, 0f)
        val p0 = AnchorTransition.position(from, to, 0)
        val pEnd = AnchorTransition.position(from, to, 300)
        val pQuarter = AnchorTransition.position(from, to, 75)
        val pMid = AnchorTransition.position(from, to, 150)
        check("t=0 is the from point", p0 == from)
        check("t=duration is the to point", pEnd == to)
        check("midpoint is halfway", approx(pMid.x, 50f))
        // easeInOutCubic: first quarter covers less than a linear quarter.
        check("curve eases in (slow start)", pQuarter.x < 25f)
        check("curve eases out symmetrically", approx(pQuarter.x + AnchorTransition.position(from, to, 225).x, 100f))
        check("elapsed past duration clamps to `to`", AnchorTransition.position(from, to, 9999) == to)
        check("negative elapsed clamps to `from`", AnchorTransition.position(from, to, -50) == from)
        check("not finished before duration", !AnchorTransition.isFinished(299))
        check("finished at duration", AnchorTransition.isFinished(300))
    }

    // 7. Invisible -> Character also emits (edge glides back to the view).
    run {
        val r = AnchorResolver(FakePositions())
        r.resolveInitial(GuidanceMode.INVISIBLE)
        val ev = r.onModeChanged(GuidanceMode.CHARACTER) as AnchorEvent.Moved
        check("edge->character emits Moved", ev.from == GeomPoint(4f, 1200f))
        check("edge->character targets the character", ev.to == GeomPoint(540f, 300f))
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
