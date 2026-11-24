package com.loom.assistant.platform.services

/**
 * Accessibility fix (2026-11-05, audit plan step 2): ShowPointerSeam tests.
 *
 * Covers the verified-render decision behind LoomOverlayService
 * .showPointerNow(): true only when the SHOW policy persisted, a renderer
 * resolved and verified attached, the idle render issued, and the renderer
 * still attached afterwards; false when the host is unavailable (a11y
 * service off), attach was refused, the cached renderer was stale /
 * detached, the policy write failed, or the idle draw was refused — with
 * the idle render never attempted once an earlier step has failed.
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 \
 *     platform/services/src/main/java/com/loom/assistant/platform/services/ShowPointerSeam.kt \
 *     platform/services/src/test/java/com/loom/assistant/platform/services/ShowPointerSeamTest.kt
 *   java -cp out com.loom.assistant.platform.services.ShowPointerSeamTestKt
 */

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

private class FakeRenderer(var attached: Boolean) : ShowPointerRenderer {
    override fun isAttachedToWindow(): Boolean = attached
}

/** Attached on the first read, detached on every read after — the
 * window died mid-call. */
private class DetachMidCallRenderer : ShowPointerRenderer {
    private var reads = 0
    override fun isAttachedToWindow(): Boolean {
        reads++
        return reads == 1
    }
}

private class FakeHost(
    var policyOk: Boolean = true,
    var renderer: ShowPointerRenderer? = FakeRenderer(attached = true),
    var idleOk: Boolean = true,
    var rendererFailure: ShowPointerFailure = ShowPointerFailure.HOST_REFUSED,
    var idleFailure: ShowPointerFailure = ShowPointerFailure.GATE_REFUSED,
) : ShowPointerHost {
    val calls = mutableListOf<String>()

    override fun setPolicyShow(): Boolean {
        calls += "policy"
        return policyOk
    }

    override fun ensureRenderer(): ShowPointerRenderer? {
        calls += "ensure"
        return renderer
    }

    override fun showPointerIdle(): Boolean {
        calls += "idle"
        return idleOk
    }

    override fun rendererFailureReason(): ShowPointerFailure = rendererFailure

    override fun idleFailureReason(): ShowPointerFailure = idleFailure
}

/**
 * A host that implements only the three original methods — the
 * default reason methods must report HOST_REFUSED / GATE_REFUSED.
 */
private class MinimalHost(
    var renderer: ShowPointerRenderer? = FakeRenderer(attached = true),
    var idleOk: Boolean = true,
) : ShowPointerHost {
    override fun setPolicyShow(): Boolean = true
    override fun ensureRenderer(): ShowPointerRenderer? = renderer
    override fun showPointerIdle(): Boolean = idleOk
}

fun main() {
    // ---- happy path: verified render ----
    run {
        val host = FakeHost()
        check("verified render returns true", ShowPointerSeam.run(host))
        check(
            "happy path runs policy -> ensure -> idle in order",
            host.calls == listOf("policy", "ensure", "idle"),
        )
    }

    // ---- host unavailable: a11y service off, no renderer resolves ----
    run {
        val host = FakeHost(renderer = null)
        check("host unavailable returns false", !ShowPointerSeam.run(host))
        check("host unavailable never attempts the idle render", host.calls == listOf("policy", "ensure"))
    }

    // ---- attach refused: same null from ensureRenderer, distinct cause ----
    run {
        val host = FakeHost(renderer = null)
        check("attach refused returns false", !ShowPointerSeam.run(host))
        check("attach refused never attempts the idle render", "idle" !in host.calls)
    }

    // ---- stale cache dropped: renderer resolved but not attached ----
    run {
        val host = FakeHost(renderer = FakeRenderer(attached = false))
        check("stale/detached renderer returns false", !ShowPointerSeam.run(host))
        check("stale renderer never attempts the idle render", host.calls == listOf("policy", "ensure"))
    }

    // ---- detached mid-call: attached before the draw, gone after ----
    run {
        val host = FakeHost(renderer = DetachMidCallRenderer())
        check("detached mid-call returns false", !ShowPointerSeam.run(host))
        check("detached mid-call still issued the idle render", host.calls == listOf("policy", "ensure", "idle"))
    }

    // ---- idle draw refused: renderer attached, gate/layer said no ----
    run {
        val host = FakeHost(idleOk = false)
        check("idle refused returns false", !ShowPointerSeam.run(host))
        check("idle refused ran every step", host.calls == listOf("policy", "ensure", "idle"))
    }

    // ---- policy write failed: nothing else runs ----
    run {
        val host = FakeHost(policyOk = false)
        check("policy write failed returns false", !ShowPointerSeam.run(host))
        check("policy write failed stops before ensure", host.calls == listOf("policy"))
    }

    // ---- no layer / no renderer behind the gate: idle false ----
    run {
        val host = FakeHost(renderer = FakeRenderer(attached = true), idleOk = false)
        check("no layer returns false", !ShowPointerSeam.run(host))
    }

    // ---- runDetailed: one case per reason (pointer failure fix) ----
    run {
        // Happy path: shown, no failure, logger never called.
        val seen = mutableListOf<ShowPointerFailure>()
        val ok = ShowPointerSeam.runDetailed(FakeHost()) { seen += it }
        check("detailed happy path shown", ok.shown)
        check("detailed happy path failure null", ok.failure == null)
        check("detailed happy path logs nothing", seen.isEmpty())
    }
    run {
        val seen = mutableListOf<ShowPointerFailure>()
        val r = ShowPointerSeam.runDetailed(FakeHost(policyOk = false)) { seen += it }
        check("detailed policy failure not shown", !r.shown)
        check("detailed policy failure reason", r.failure == ShowPointerFailure.POLICY_FAILED)
        check("detailed policy failure logged once", seen == listOf(ShowPointerFailure.POLICY_FAILED))
        check("run() agrees with detailed on policy", !ShowPointerSeam.run(FakeHost(policyOk = false)))
    }
    run {
        // No host (a11y service not live) vs host refused: same null,
        // distinct reasons — the cfdb8fb bug collapsed them into one.
        val noHost = ShowPointerSeam.runDetailed(
            FakeHost(renderer = null, rendererFailure = ShowPointerFailure.NO_HOST),
        )
        check("detailed no-host reason", noHost.failure == ShowPointerFailure.NO_HOST)
        check("detailed no-host not shown", !noHost.shown)
        val refused = ShowPointerSeam.runDetailed(
            FakeHost(renderer = null, rendererFailure = ShowPointerFailure.HOST_REFUSED),
        )
        check("detailed host-refused reason", refused.failure == ShowPointerFailure.HOST_REFUSED)
        check("detailed host-refused not shown", !refused.shown)
    }
    run {
        val stale = ShowPointerSeam.runDetailed(FakeHost(renderer = FakeRenderer(attached = false)))
        check("detailed stale renderer reason", stale.failure == ShowPointerFailure.NOT_ATTACHED)
        val midCall = ShowPointerSeam.runDetailed(FakeHost(renderer = DetachMidCallRenderer()))
        check("detailed detached-mid-call reason", midCall.failure == ShowPointerFailure.NOT_ATTACHED)
    }
    run {
        // Gate refused vs no interaction layer: same false, distinct reasons.
        val gate = ShowPointerSeam.runDetailed(
            FakeHost(idleOk = false, idleFailure = ShowPointerFailure.GATE_REFUSED),
        )
        check("detailed gate-refused reason", gate.failure == ShowPointerFailure.GATE_REFUSED)
        val noLayer = ShowPointerSeam.runDetailed(
            FakeHost(idleOk = false, idleFailure = ShowPointerFailure.NO_INTERACTION),
        )
        check("detailed no-interaction reason", noLayer.failure == ShowPointerFailure.NO_INTERACTION)
    }
    run {
        // Default reason methods: a host that only implements the
        // three original methods reports HOST_REFUSED / GATE_REFUSED.
        val noHostDefault = ShowPointerSeam.runDetailed(MinimalHost(renderer = null))
        check("default renderer reason is HOST_REFUSED", noHostDefault.failure == ShowPointerFailure.HOST_REFUSED)
        val idleDefault = ShowPointerSeam.runDetailed(MinimalHost(idleOk = false))
        check("default idle reason is GATE_REFUSED", idleDefault.failure == ShowPointerFailure.GATE_REFUSED)
    }
    run {
        // runDetailed short-circuits exactly like run(): a policy
        // failure never reaches ensure, a null renderer never
        // reaches idle — the first failure is the only failure.
        val seen = mutableListOf<ShowPointerFailure>()
        val host = FakeHost(policyOk = false)
        ShowPointerSeam.runDetailed(host) { seen += it }
        check("detailed policy failure stops before ensure", host.calls == listOf("policy"))
        check("detailed policy failure logs exactly one reason", seen.size == 1)
    }

    run {
        // sizeDescription: default "" for hosts that cannot report a
        // size; an override reports "WxH". Diagnostic only — the
        // seam never decides on size.
        val plain = object : ShowPointerRenderer {
            override fun isAttachedToWindow(): Boolean = true
        }
        check("default sizeDescription is empty", plain.sizeDescription() == "")
        val sized = object : ShowPointerRenderer {
            override fun isAttachedToWindow(): Boolean = true
            override fun sizeDescription(): String = "1080x2400"
        }
        check("overridden sizeDescription reports WxH", sized.sizeDescription() == "1080x2400")
    }

    println("ShowPointerSeamTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    } else {
        kotlin.system.exitProcess(1)
    }
}
