package com.loom.assistant.platform.services

/**
 * JVM tests for the overlay notification variants (closure decision 1,
 * mismatch #5, §7-B).
 *
 * The security-critical invariant: the DEACTIVATED notification carries
 * ZERO actions AND no content intent, so no notification path can reactivate
 * LOOM or bypass launcher-icon-only reactivation (§4.4). The HIDDEN variant
 * offers SHOW ASSISTANT only (restores views, never starts the mic, never
 * transitions the lifecycle).
 */
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

fun main() {
    // DEACTIVATED — strictly action-free, no content intent.
    run {
        val spec = overlayNotificationSpec(OverlayNotificationVariant.DEACTIVATED)
        check("deactivated: zero actions", spec.actions.isEmpty(), "actions=${spec.actions}")
        check("deactivated: no content intent", !spec.hasContentIntent)
        check(
            "deactivated: text directs to launcher icon",
            spec.text.contains("launcher icon"),
            spec.text,
        )
    }

    // HIDDEN — SHOW ASSISTANT only.
    run {
        val spec = overlayNotificationSpec(OverlayNotificationVariant.HIDDEN)
        check(
            "hidden: exactly one action",
            spec.actions.size == 1,
            "actions=${spec.actions}",
        )
        check(
            "hidden: the action is SHOW ASSISTANT",
            spec.actions.single() is OverlayNotificationAction.ShowAssistant,
        )
        check(
            "hidden: no halt action",
            spec.actions.none { it is OverlayNotificationAction.HaltSequence },
        )
    }

    // ACTIVE — halt action only (the 2C Stop action lives here, not in the
    // deactivation variant).
    run {
        val spec = overlayNotificationSpec(OverlayNotificationVariant.ACTIVE)
        check(
            "active: halt action present",
            spec.actions.single() is OverlayNotificationAction.HaltSequence,
            "actions=${spec.actions}",
        )
        check(
            "active: no SHOW ASSISTANT action",
            spec.actions.none { it is OverlayNotificationAction.ShowAssistant },
        )
        check("active: content intent present", spec.hasContentIntent)
    }

    // No variant may offer a reactivation path: only HIDDEN/ACTIVE keep the
    // content intent, and neither transitions the lifecycle.
    run {
        val deactivated = overlayNotificationSpec(OverlayNotificationVariant.DEACTIVATED)
        check(
            "no reactivation path from deactivated notification",
            deactivated.actions.isEmpty() && !deactivated.hasContentIntent,
        )
    }

    println("notification-spec: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILURES: $failed")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
