package com.loom.assistant.execution.interaction

import android.graphics.Rect
import android.graphics.RectF
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthConfirmationHost
import com.loom.assistant.intelligence.authgate.AuthGate
import com.loom.assistant.interaction.pointer.GuidanceRendererContract
import testutil.FakeContext

/**
 * Stage 2C — shared JVM fakes for ActionQueueTest (T3) and
 * SensitiveAppPolicyTest (T6). Compiled alongside the runner that needs them.
 */

fun el(
    id: String,
    text: String?,
    bounds: Rect = Rect(10, 10, 110, 60),
    className: String = "android.widget.Button",
): ScreenElement = ScreenElement(
    id = id,
    text = text,
    contentDescription = null,
    className = className,
    bounds = bounds,
    role = ElementRole.BUTTON,
    enabled = true,
    isPassword = false,
)

fun model(pkg: String, vararg els: ScreenElement): ScreenModel =
    ScreenModel(pkg, els.toList())

fun planStep(
    id: String,
    query: String,
    instruction: String = "Tap $query",
    targetPackage: String? = null,
): PlanStep = PlanStep(
    id = id,
    targetQuery = query,
    instruction = instruction,
    targetPackage = targetPackage,
)

fun demoPlan(vararg steps: PlanStep, demoApp: String = "com.demo.app"): ActionPlan =
    ActionPlan(id = "plan-1", goal = "demo goal", demoAppPackage = demoApp, steps = steps.toList())

/** Controllable tree. findOverride lets a test change resolution per call. */
class FakeTree(var modelProvider: () -> ScreenModel?) : TreeSource {
    var findOverride: ((call: Int, query: String) -> ScreenElement?)? = null
    val findCalls = mutableListOf<String>()
    private var calls = 0

    override fun snapshot(): ScreenModel? = modelProvider()

    override fun findElement(query: String): ScreenElement? {
        findCalls.add(query)
        calls++
        findOverride?.let { return it(calls, query) }
        return defaultFind(query)
    }

    /** The default matching logic (mirrors AccessibilityScreenAwareness). */
    fun defaultFind(query: String): ScreenElement? {
        val q = query.lowercase()
        return modelProvider()?.elements?.firstOrNull { e ->
            e.enabled && !e.bounds.isEmpty &&
                ((e.text?.lowercase()?.contains(q) == true) ||
                    (e.contentDescription?.lowercase()?.contains(q) == true) ||
                    (e.className.lowercase().contains(q)))
        }
    }
}

/**
 * Controllable gesture sink. Mirrors LoomAccessibilityService.requestTap's
 * token probing: consumes the token against the tap-family keys (single-use).
 */
class FakeDispatcher(val gate: AuthGate) : GestureDispatch {
    data class Call(val element: ScreenElement, val tokenId: String)

    val calls = mutableListOf<Call>()
    var reject: String? = null
    var onDispatch: ((Call) -> Unit)? = null

    override fun dispatch(element: ScreenElement, tokenId: String): DispatchAttempt {
        val call = Call(element, tokenId)
        calls.add(call)
        onDispatch?.invoke(call)
        val ok = gate.consumeToken(tokenId, AuthActionRegistry.ACTION_TAP_GUIDANCE) ||
            gate.consumeToken(tokenId, AuthActionRegistry.ACTION_TAP_GUIDANCE_OUTOFSCOPE) ||
            gate.consumeToken(tokenId, AuthActionRegistry.ACTION_TAP)
        if (!ok) return DispatchAttempt.Rejected("no valid token")
        reject?.let { return DispatchAttempt.Rejected(it) }
        return DispatchAttempt.Accepted
    }
}

/** Controllable async completion. */
class FakeCompletions : CompletionSource {
    var awaits = 0
    var autoComplete: GestureOutcome? = null
    private var cb: ((GestureOutcome) -> Unit)? = null

    override fun awaitCompletion(cb: (GestureOutcome) -> Unit) {
        awaits++
        autoComplete?.let { cb(it); return }
        this.cb = cb
    }

    fun complete(outcome: GestureOutcome) {
        val c = cb
        cb = null
        c?.invoke(outcome)
    }
}

/** Implements the CANONICAL GuidanceRendererContract (InteractionLayer.kt). */
class FakeRenderer : GuidanceRendererContract {
    data class Shown(
        val anchorX: Float,
        val anchorY: Float,
        val target: RectF,
        val index: Int,
        val count: Int,
        val instruction: String,
    )

    val shown = mutableListOf<Shown>()
    val previewTargets = mutableListOf<RectF>()
    var previews = 0
    var pulses = 0
    var hides = 0
    var anchorChanges = 0

    override fun showStep(
        anchorX: Float,
        anchorY: Float,
        target: RectF,
        stepIndex: Int,
        stepCount: Int,
        instruction: String,
    ) {
        shown.add(Shown(anchorX, anchorY, target, stepIndex, stepCount, instruction))
    }

    override fun previewMoveTo(target: RectF) {
        previews++
        previewTargets.add(target)
    }

    override fun pulseOnResult() {
        pulses++
    }

    override fun hide() {
        hides++
    }

    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
        anchorChanges++
    }

    // "fix everything": contract member; queue tests never nudge.
    var nudges = 0
    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean =
        true.also { nudges++ }
}

class FakeHalt(var halted: Boolean = false, var source: String? = null) : HaltProvider {
    override fun isHalted(): Boolean = halted
    override fun haltSource(): String? = source
}

/**
 * Controllable confirmation host. Approves everything by default; parkKeys /
 * denyKeys select per-action-key behavior (drives the out-of-scope D4 paths).
 */
class FakeHost(val gate: AuthGate) : AuthConfirmationHost {
    val parkKeys = mutableSetOf<String>()
    val denyKeys = mutableSetOf<String>()
    val parked = mutableListOf<String>()
    var shown = 0
    /** MASTER F6b — the origin of every card shown, in order. */
    val shownOrigins = mutableListOf<RequestOrigin>()

    override fun showConfirmation(pending: AuthGate.PendingConfirmation): Boolean {
        shown++
        shownOrigins.add(pending.origin)
        val key = pending.action.key
        return when {
            key in denyKeys -> {
                gate.resolve(pending.id, false)
                true
            }
            key in parkKeys -> {
                parked.add(pending.id)
                true
            }
            else -> {
                gate.resolve(pending.id, true)
                true
            }
        }
    }

    override fun hideConfirmation() {}
}

/** Wires a queue with all fakes. Returns the queue plus the fake bundle. */
class QueueRig(
    val gate: AuthGate,
    val tree: FakeTree,
    val dispatcher: FakeDispatcher,
    val completions: FakeCompletions,
    val renderer: FakeRenderer,
    val halt: FakeHalt,
    val host: FakeHost,
    val audit: MutableList<String>,
    val busEvents: MutableList<LoomStateEvent>,
    val queue: ActionQueue,
)

fun newRig(
    strictness: () -> Int = { 0 },
    anchor: Pair<Float, Float> = 540f to 300f,
): QueueRig {
    val gate = AuthGate.get()
    val tree = FakeTree({ null })
    val dispatcher = FakeDispatcher(gate)
    val completions = FakeCompletions()
    val renderer = FakeRenderer()
    val halt = FakeHalt()
    val host = FakeHost(gate)
    gate.confirmationHost = host
    val audit = mutableListOf<String>()
    val busEvents = mutableListOf<LoomStateEvent>()
    val queue = ActionQueue(
        gate = gate,
        trees = tree,
        dispatcher = dispatcher,
        completions = completions,
        renderer = renderer,
        halt = halt,
        anchors = AnchorProvider { anchor },
        transitions = TransitionGate.IDLE,
        previewDelayer = PreviewDelayer.IMMEDIATE,
        strictness = strictness,
        audit = AuditLogger { kind, text -> audit.add("$kind: $text") },
        bus = EventSink { busEvents.add(it) },
    )
    return QueueRig(gate, tree, dispatcher, completions, renderer, halt, host, audit, busEvents, queue)
}

/** Initializes the AuthGate singleton for a runner (call once in main()). */
fun initGateForTests(): AuthGate {
    val gate = AuthGate.init(FakeContext())
    gate.confirmationHost = null
    return gate
}
