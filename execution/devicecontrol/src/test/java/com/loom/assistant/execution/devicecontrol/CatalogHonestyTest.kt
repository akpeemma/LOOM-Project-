package com.loom.assistant.execution.devicecontrol

import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.loom.assistant.execution.screenawareness.A11yNodeProvider
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.platform.services.LoomAccessibilityService
import testutil.FakeContext

/**
 * Stage 2C T10 — catalog honesty (decision 4, plan §6).
 *
 * The availability() matrix: every claimed capability is Ready or
 * Refused-with-reason across device states (a11y on/off, gate ready/not,
 * sensitive foreground package). No silent gaps, no phantom claims.
 *
 * Drives the REAL DeviceControlCenter + the real LoomAccessibilityService
 * companion (authGateReady) + the real ScreenAwarenessHolder, with JVM
 * fakes for the Android framework surface.
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

private const val SERVICE_COMPONENT =
    "com.loom.assistant/com.loom.assistant.platform.services.LoomAccessibilityService"

private fun setA11y(enabled: Boolean) {
    if (enabled) {
        Settings.Secure.values[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] = SERVICE_COMPONENT
    } else {
        Settings.Secure.values.remove(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    }
}

/** Binds a fake provider whose root reports [packageName]. Null = unbound. */
private fun bindTree(packageName: String?) {
    if (packageName == null) {
        ScreenAwarenessHolder.unbind()
        return
    }
    val node = object : AccessibilityNodeInfo() {}
    node.packageName = packageName
    ScreenAwarenessHolder.bind(object : A11yNodeProvider {
        override fun safeRootInActiveWindow(): AccessibilityNodeInfo = node
    })
}

private fun avail(center: DeviceControlCenter, id: String): DeviceControlCenter.ActionResult {
    val action = DeviceControlCenter.ACTIONS.first { it.id == id }
    return center.availability(action)
}

private fun isReady(r: DeviceControlCenter.ActionResult) = r is DeviceControlCenter.ActionResult.Ready

private fun refusedWith(r: DeviceControlCenter.ActionResult, fragment: String): Boolean =
    r is DeviceControlCenter.ActionResult.Refused && r.reason.contains(fragment)

fun main() {
    val center = DeviceControlCenter(FakeContext())
    val ids = listOf("launch", "tap", "type", "scroll", "back", "screenshot-context")

    // ---------- state 1: a11y off → everything refused with the exact piece ----------
    setA11y(false)
    LoomAccessibilityService.authGateReady = false
    bindTree(null)
    for (id in ids) {
        val r = avail(center, id)
        check("a11y off: $id refused", r is DeviceControlCenter.ActionResult.Refused)
    }
    check(
        "a11y off: tap names the missing piece",
        refusedWith(avail(center, "tap"), "the helper is off"),
    )
    check(
        "a11y off: launch names the helper being off",
        refusedWith(avail(center, "launch"), "the helper is off"),
    )

    // ---------- state 1b: a11y ON but helper not running -> different fix ----------
    // The switch reads on while the helper is not actually running (David
    // Nov 6: "Service bound: Not bound" with the switch on). The reason
    // must say so and name the off-and-on-again fix, not the enable fix.
    setA11y(true)
    bindTree(null)
    check(
        "a11y on, helper down: launch names on-but-not-running",
        refusedWith(avail(center, "launch"), "switched on but not running"),
    )
    check(
        "a11y on, helper down: launch names the off-and-on fix",
        refusedWith(avail(center, "launch"), "turn LOOM off and on again"),
    )
    check(
        "a11y on, helper down: tap names on-but-not-running",
        refusedWith(avail(center, "tap"), "switched on but not running"),
    )

    // ---------- state 2: a11y on, gate NOT ready → gestures refused, read ready ----------
    setA11y(true)
    LoomAccessibilityService.authGateReady = false
    bindTree("com.demo.app")
    check("gate not ready: tap refused (approval check)", refusedWith(avail(center, "tap"), "approval check"))
    check("gate not ready: type refused (approval check)", refusedWith(avail(center, "type"), "approval check"))
    check("gate not ready: scroll refused (approval check)", refusedWith(avail(center, "scroll"), "approval check"))
    check("gate not ready: back refused (approval check)", refusedWith(avail(center, "back"), "approval check"))
    check("gate not ready: launch refused (approval check)", refusedWith(avail(center, "launch"), "approval check"))
    check("gate not ready: screenshot-context ready", isReady(avail(center, "screenshot-context")))

    // ---------- state 3: all on → every wired action Ready (no phantom claims) ----------
    LoomAccessibilityService.authGateReady = true
    for (id in ids) {
        check("all on: $id Ready", isReady(avail(center, id)))
    }

    // ---------- state 4: sensitive foreground → tap/type/scroll/launch refused ----------
    bindTree("com.kuda.app")
    val exact = "Inside banking and finance apps, LOOM only explains the steps — " +
        "it never taps, types, scrolls or opens things there."
    for (id in listOf("tap", "type", "scroll", "launch")) {
        val r = avail(center, id)
        check(
            "sensitive: $id refused with exact reason",
            r is DeviceControlCenter.ActionResult.Refused && r.reason == exact,
        )
    }
    // Back is OS-level navigation (leaving the app), not automation inside it.
    check("sensitive: back still Ready", isReady(avail(center, "back")))
    // Screenshot is a redacted read, which guidance needs.
    check("sensitive: screenshot-context still Ready", isReady(avail(center, "screenshot-context")))

    // ---------- unknown action: honest refusal, never a silent gap ----------
    val unknown = center.availability(DeviceControlCenter.ControlAction("nope", "Nope", ""))
    check(
        "unknown action refused",
        refusedWith(unknown, "Unknown action"),
    )

    // ---------- every catalog entry is covered by the matrix (no silent gaps) ----------
    val covered = DeviceControlCenter.ACTIONS.map { it.id }.toSet()
    check(
        "catalog ids stable",
        covered == setOf("launch", "tap", "type", "scroll", "back", "screenshot-context"),
    )

    // cleanup
    bindTree(null)
    setA11y(false)
    LoomAccessibilityService.authGateReady = false

    println("CatalogHonestyTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
