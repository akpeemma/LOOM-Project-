package com.loom.assistant.execution.interaction

import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate

/**
 * JVM tests proving the converted dispatch sinks refuse after purgeAll
 * (§8.2 follow-up): token issued → purgeAll → dispatch attempted → REFUSED.
 *
 * Sink graph (production): ActionQueue.asTapDispatcher / dispatchStep →
 * GestureDispatch → LoomAccessibilityService.requestTap, and requestTap is
 * converted to AuthGate.consumeAndDispatch (check → probe → re-check →
 * handoff in one gate-monitor critical section). The AtomicSinkDispatcher
 * below models the converted requestTap faithfully — consumption and the
 * dispatch handoff inside one consumeAndDispatch call; requestTap itself is
 * Android-bound and is proven by the same primitive's gate-level tests
 * (AuthorizationAtomicityTest: token → SHUTTING_DOWN → dispatch → REFUSED).
 *
 * asTapDispatcher is deliberately NOT double-wrapped: the bridge performs
 * no token operation itself (halt / sensitive-app / financial / on-screen
 * pre-checks only) and delegates to the dispatcher, so the atomic boundary
 * lives at the consuming sink. Wrapping the bridge too would consume the
 * token twice and refuse every dispatch.
 */
class AtomicSinkDispatcher(val gate: AuthGate) : GestureDispatch {
    data class Call(val element: ScreenElement, val tokenId: String)

    val calls = mutableListOf<Call>()
    var lastRefusal: String? = null

    /** Models the converted requestTap: consume+dispatch in one boundary. */
    override fun dispatch(element: ScreenElement, tokenId: String): DispatchAttempt {
        calls.add(Call(element, tokenId))
        return when (
            val r = gate.consumeAndDispatch(
                tokenId,
                listOf(
                    AuthActionRegistry.ACTION_TAP_GUIDANCE,
                    AuthActionRegistry.ACTION_TAP,
                ),
            ) {
                // handoff: the system took the gesture
                DispatchAttempt.Accepted
            }
        ) {
            is AuthGate.DispatchResult.Dispatched -> r.value
            is AuthGate.DispatchResult.Refused -> {
                lastRefusal = r.reason
                DispatchAttempt.Rejected("refused: ${r.reason}")
            }
        }
    }
}

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

/** Issues a real tap.guidance token through the gate (ALLOW → synchronous). */
private fun issueTapToken(gate: AuthGate): String {
    var tokenId: String? = null
    gate.request(AuthActionRegistry.tapGuidanceSequenced("Tap X", "com.demo.app")) { token ->
        tokenId = token?.id
    }
    return tokenId ?: error("tap token was not issued")
}

/** A queue whose dispatcher is the atomic sink model. */
private fun atomicRig(): Triple<ActionQueue, AtomicSinkDispatcher, AuthGate> {
    val gate = AuthGate.get()
    val tree = FakeTree({ null })
    val sink = AtomicSinkDispatcher(gate)
    val queue = ActionQueue(
        gate = gate,
        trees = tree,
        dispatcher = sink,
        completions = FakeCompletions(),
        renderer = FakeRenderer(),
        halt = FakeHalt(),
        anchors = AnchorProvider { 540f to 300f },
        transitions = TransitionGate.IDLE,
        previewDelayer = PreviewDelayer.IMMEDIATE,
        audit = AuditLogger { _, _ -> },
        bus = EventSink {},
    )
    val element = el("b1", "Open")
    tree.modelProvider = { model("com.demo.app", element) }
    return Triple(queue, sink, gate)
}

fun main() {
    initGateForTests()

    // 1. Positive: issued token dispatches while ACTIVE, exactly once.
    run {
        val (queue, sink, gate) = atomicRig()
        val element = el("b1", "Open")
        val tokenId = issueTapToken(gate)
        val ok = queue.asTapDispatcher()(element, tokenId)
        check("positive: bridge dispatches", ok)
        check("positive: sink attempted once", sink.calls.size == 1, "calls=${sink.calls.size}")
        check("positive: no refusal recorded", sink.lastRefusal == null, sink.lastRefusal ?: "")
        // Single-use: the same token cannot dispatch twice.
        val again = queue.asTapDispatcher()(element, tokenId)
        check("positive: token single-use", !again)
    }

    // 2. THE required scenario: token issued → purgeAll → dispatch → REFUSED.
    run {
        val (queue, sink, gate) = atomicRig()
        val element = el("b1", "Open")
        val tokenId = issueTapToken(gate)
        val purged = gate.purgeAll("test")
        check("purge: token revoked", purged.tokensRevoked >= 1, "revoked=${purged.tokensRevoked}")
        val ok = queue.asTapDispatcher()(element, tokenId)
        check("purge: bridge refuses dispatch", !ok)
        check("purge: sink attempted the dispatch", sink.calls.size == 1, "calls=${sink.calls.size}")
        check(
            "purge: refusal names the cause",
            sink.lastRefusal != null && sink.lastRefusal!!.contains("unknown token"),
            sink.lastRefusal ?: "null",
        )
    }

    // 3. Fail-closed probe: wrong-key probe does not destroy the token
    //    (mismatch #6 — the two-probe pattern).
    run {
        val (_, _, gate) = atomicRig()
        val tokenId = issueTapToken(gate)
        val wrongKey = gate.consumeAndDispatch(
            tokenId,
            listOf(AuthActionRegistry.ACTION_TAP),
        ) { "handoff-ran" }
        check(
            "probe: wrong key refused",
            wrongKey is AuthGate.DispatchResult.Refused,
            wrongKey.toString(),
        )
        // Token survived the wrong-key probe: it still dispatches.
        val rightKey = gate.consumeAndDispatch(
            tokenId,
            listOf(AuthActionRegistry.ACTION_TAP_GUIDANCE, AuthActionRegistry.ACTION_TAP),
        ) { "handoff-ran" }
        check(
            "probe: token survived wrong-key probe",
            rightKey is AuthGate.DispatchResult.Dispatched &&
                (rightKey as AuthGate.DispatchResult.Dispatched).value == "handoff-ran",
            rightKey.toString(),
        )
    }

    // 4. Bridge pre-checks still refuse before any gate touch (sensitive app).
    run {
        val gate = AuthGate.get()
        val tree = FakeTree({ null })
        val sink = AtomicSinkDispatcher(gate)
        val queue = ActionQueue(
            gate = gate,
            trees = tree,
            dispatcher = sink,
            completions = FakeCompletions(),
            renderer = FakeRenderer(),
            halt = FakeHalt(),
            anchors = AnchorProvider { 540f to 300f },
            transitions = TransitionGate.IDLE,
            previewDelayer = PreviewDelayer.IMMEDIATE,
            audit = AuditLogger { _, _ -> },
            bus = EventSink {},
        )
        val element = el("b1", "Open")
        // A genuinely sensitive package (ScreenDataPolicy's list).
        tree.modelProvider = { model("com.android.bank", element) }
        val tokenId = issueTapToken(gate)
        val ok = queue.asTapDispatcher()(element, tokenId)
        check("sensitive: bridge refuses", !ok)
        check("sensitive: sink never attempted", sink.calls.isEmpty(), "calls=${sink.calls.size}")
        // The token was never spent on the refused action.
        val stillValid = gate.consumeToken(tokenId, AuthActionRegistry.ACTION_TAP_GUIDANCE)
        check("sensitive: token not spent on refusal", stillValid)
    }

    println("sink-atomicity: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILURES: $failed")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
