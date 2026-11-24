package com.loom.assistant.execution.interaction

import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.authgate.AuthActionRegistry
import com.loom.assistant.intelligence.authgate.AuthGate

/**
 * Stage 2C T6 — sensitive-app policy tests (plan §6), INCLUDING the four
 * hard-rule negative tests:
 *  (i)   approved demo.sequence token + confirmed per-step token presented
 *         inside a sensitive package → dispatch still refused;
 *  (ii)  registry audit — no registered AuthGate key maps to sensitive-
 *         package execution; tap.guidance.sensitive does not exist;
 *  (iii) financial step in a NON-sensitive app → refused before any token
 *         request, no confirmation surface offered;
 *  (iv)  paranoid strictness opens no sensitive-app execution path.
 *
 * Also: fragment classifier coverage, redaction (strips text, keeps
 * bounds+roles), queue gate behavior, catalog refusal reason.
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

fun main() {
    initGateForTests()
    val gate = AuthGate.get()

    // ---------- classifier: fragment coverage ----------
    val sensitive = listOf(
        "com.kuda.app",
        "com.gtbank.mobile",
        "com.mtn.momo",
        "com.opay.app",
        "com.palmpay.app",
        "com.moniepoint.app",
        "com.piggyvest.app",
        "com.flutterwave.app",
        "com.yubico.yubioath",
        "com.okta.android.auth",
        "com.azure.authenticator",
        "com.lastpass.lpandroid",
        "com.zenithbank.mobile",
        "com.accessbank.app",
    )
    for (pkg in sensitive) {
        check("sensitive: $pkg", ScreenDataPolicy.isSensitivePackage(pkg))
    }
    val benign = listOf(
        "com.android.settings",
        "com.zhiliaoapp.musically",
        "com.google.android.youtube",
        "com.loom.assistant",
        "org.mozilla.firefox",
    )
    for (pkg in benign) {
        check("not sensitive: $pkg", !ScreenDataPolicy.isSensitivePackage(pkg))
    }

    // ---------- redaction: strips text, keeps bounds+roles ----------
    run {
        val bounds = android.graphics.Rect(0, 0, 100, 50)
        val els = listOf(
            ScreenElement("1", "Transfer", "Send money", "android.widget.Button", bounds, ElementRole.BUTTON, true, false),
            ScreenElement("2", "1234", null, "android.widget.EditText", bounds, ElementRole.INPUT, true, true),
        )
        val redacted = ScreenDataPolicy.redact(ScreenModel("com.kuda.app", els))
        check(
            "sensitive: all text stripped",
            redacted.elements.all { it.text == null && it.contentDescription == null },
        )
        check(
            "sensitive: bounds kept",
            redacted.elements.all { it.bounds == bounds },
        )
        check(
            "sensitive: roles kept",
            redacted.elements.map { it.role } == listOf(ElementRole.BUTTON, ElementRole.INPUT),
        )
        val normal = ScreenDataPolicy.redact(ScreenModel("com.demo.app", els))
        check(
            "non-sensitive: only password field redacted",
            normal.elements[0].text == "Transfer" && normal.elements[1].text == null,
        )
        val untouched = ScreenDataPolicy.redact(
            ScreenModel("com.demo.app", listOf(els[0])),
        )
        check(
            "non-sensitive non-password: model untouched",
            untouched.elements[0].text == "Transfer",
        )
    }

    // ---------- NEGATIVE (i): approved tokens still refused in sensitive pkg ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.kuda.app", el("b1", "Menu")) }
        // approved demo.sequence token (simulating the card approval)
        var seqToken: AuthGate.AuthToken? = null
        gate.request(AuthActionRegistry.demoSequence("demo goal", "com.kuda.app", 1, false)) { t ->
            seqToken = t
        }
        check("(i) sequence token issued", seqToken != null)
        // confirmed per-step token (the session's tap.guidance path)
        var stepToken: AuthGate.AuthToken? = null
        gate.request(AuthActionRegistry.tapGuidanceTarget("Tap the menu button")) { t ->
            stepToken = t
        }
        check("(i) per-step token issued", stepToken != null)
        val bridge = rig.queue.asTapDispatcher()
        val target = el("b1", "Menu")
        check(
            "(i) demo.sequence token → still refused in sensitive pkg",
            !bridge(target, seqToken!!.id),
        )
        check(
            "(i) confirmed per-step token → still refused in sensitive pkg",
            !bridge(target, stepToken!!.id),
        )
        check("(i) zero dispatches", rig.dispatcher.calls.isEmpty())
        check(
            "(i) refusal names automation.sensitive_app",
            rig.audit.any { it.contains(AuthActionRegistry.ACTION_SENSITIVE_APP) },
        )
        // The tokens were NOT burned by the refusal.
        check(
            "(i) refused tokens survive",
            gate.consumeToken(stepToken!!.id, AuthActionRegistry.ACTION_TAP_GUIDANCE),
        )
    }

    // ---------- NEGATIVE (ii): registry audit ----------
    run {
        val keys = mutableSetOf<String>()
        for (f in AuthActionRegistry::class.java.declaredFields) {
            if (f.name.startsWith("ACTION_") && f.type == String::class.java) {
                f.isAccessible = true
                keys.add(f.get(null) as String)
            }
        }
        for (m in AuthActionRegistry::class.java.declaredMethods) {
            if (m.parameterCount == 0 && m.returnType == AuthGate.AuthAction::class.java &&
                m.name.startsWith("get")
            ) {
                m.isAccessible = true
                keys.add((m.invoke(AuthActionRegistry) as AuthGate.AuthAction).key)
            }
        }
        keys.add(AuthActionRegistry.demoSequence("g", "a", 1, false).key)
        keys.add(AuthActionRegistry.tapGuidanceOutOfScope("i", "a", "b").key)
        keys.add(AuthActionRegistry.tapGuidanceSequenced("i", "a").key)
        keys.add(AuthActionRegistry.tapGuidanceTarget("i").key)

        check(
            "(ii) tap.guidance.sensitive is NOT registered",
            "tap.guidance.sensitive" !in keys,
        )
        check(
            "(ii) no key mentions sensitive-package execution",
            keys.none { it.contains("sensitive") && it != AuthActionRegistry.ACTION_SENSITIVE_APP },
        )
        check(
            "(ii) tap.financial_transaction registered",
            AuthActionRegistry.ACTION_TAP_FINANCIAL in keys,
        )
        check(
            "(ii) automation.sensitive_app registered",
            AuthActionRegistry.ACTION_SENSITIVE_APP in keys,
        )
        val known = setOf(
            "screen.snapshot", "memory.query", "task.resume.own",
            "tap.device", "tap.guidance", "demo.sequence",
            "tap.guidance.outofscope", "tap.financial_transaction",
            "automation.sensitive_app", "skill.install", "message.send",
            "external.publish", "settings.change", "file.write.external",
            "spend.money", "device.factory_reset", "device.security_change",
            "device.type", "device.scroll", "device.back", "app.launch",
            "device.submit", "call.place",
        )
        val unknown = keys - known
        check("(ii) no unlisted keys: $unknown", unknown.isEmpty())
        check(
            "(ii) financial key is DENY-class",
            AuthActionRegistry.FINANCIAL_TRANSACTION.riskClass == AuthGate.RiskClass.DENY,
        )
        check(
            "(ii) sensitive key is DENY-class",
            AuthActionRegistry.SENSITIVE_APP_AUTOMATION.riskClass == AuthGate.RiskClass.DENY,
        )
        check(
            "(ii) gate refuses tap.financial_transaction outright",
            gate.check(AuthActionRegistry.FINANCIAL_TRANSACTION) is AuthGate.GateResult.Deny,
        )
        check(
            "(ii) gate refuses automation.sensitive_app outright",
            gate.check(AuthActionRegistry.SENSITIVE_APP_AUTOMATION) is AuthGate.GateResult.Deny,
        )
    }

    // ---------- NEGATIVE (iii): financial step in NON-sensitive app ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = {
            model("com.demo.app", el("b1", "Settings"), el("b2", "Approve"))
        }
        rig.completions.autoComplete = GestureOutcome.COMPLETED
        var outcome: SequenceOutcome? = null
        rig.queue.start(
            demoPlan(
                planStep("s1", "Settings"),
                planStep("s2", "Approve", instruction = "approve login on this device"),
            ),
        ) { outcome = it }
        check("(iii) completes around the refused step", outcome == SequenceOutcome.Completed)
        check("(iii) financial step never dispatched", rig.dispatcher.calls.size == 1)
        check(
            "(iii) no confirmation surface for the financial step",
            rig.host.shown == 1 && rig.host.parked.isEmpty(),
        )
        check(
            "(iii) refused before any token request (audited)",
            rig.audit.any { it.contains(AuthActionRegistry.ACTION_TAP_FINANCIAL) },
        )
    }

    // ---------- NEGATIVE (iv): paranoid strictness opens no sensitive path ----------
    run {
        val rig = newRig(strictness = { 2 })
        rig.tree.modelProvider = { model("com.opay.app", el("b1", "Balance")) }
        var outcome: SequenceOutcome? = null
        rig.queue.start(demoPlan(planStep("s1", "Balance"))) { outcome = it }
        check("(iv) completes guidance-only", outcome == SequenceOutcome.Completed)
        check("(iv) zero dispatches in paranoid+sensitive", rig.dispatcher.calls.isEmpty())
        check(
            "(iv) no per-step card offered in sensitive app",
            rig.host.parked.isEmpty(),
        )
        check(
            "(iv) sensitive refusal audited",
            rig.audit.any { it.contains(AuthActionRegistry.ACTION_SENSITIVE_APP) },
        )
    }

    // ---------- queue never requests a token in a sensitive package ----------
    run {
        val rig = newRig()
        rig.tree.modelProvider = { model("com.palmpay.app", el("b1", "Send")) }
        var outcome: SequenceOutcome? = null
        rig.queue.start(demoPlan(planStep("s1", "Send"))) { outcome = it }
        check("sensitive → Completed (guidance-only)", outcome == SequenceOutcome.Completed)
        check("no dispatcher calls", rig.dispatcher.calls.isEmpty())
        // The only gate interaction was the sequence card itself.
        check("exactly one confirmation shown (the sequence card)", rig.host.shown == 1)
    }

    println("SensitiveAppPolicyTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
