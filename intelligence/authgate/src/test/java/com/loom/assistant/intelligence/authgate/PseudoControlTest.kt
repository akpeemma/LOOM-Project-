package com.loom.assistant.intelligence.authgate

import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.platform.storage.LoomPreferences
import testutil.FakeContext

/**
 * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT) — policy,
 * store, and gate-integration tests.
 *
 * Covers:
 *  A. The policy table is data: exactly the ten approved apps, the
 *     seven reversible keys (six grant keys + tap.device), the
 *     plan-cover key, and the NEVER boundary (sends, calls,
 *     money, financial, sensitive) — no scope, no app, no key
 *     outside the table is ever covered.
 *  B. The store: fresh → off + prompt due; grant → on + prompt
 *     answered + persisted; revoke → off immediately + prompt due
 *     again; "Not now" → prompt answered, still off.
 *  C. The gate: with the grant active, a MODEL-origin in-scope
 *     action (the plan path — PlanWiring passes MODEL) is allowed
 *     with NO card shown; without the grant the same action cards;
 *     sends/calls/sensitive/financial still card or deny even with
 *     the grant; paranoid strictness disables pseudo; an unapproved
 *     app still cards.
 *
 * Run (from loom-android, compile WITHOUT android.jar — same pattern
 * as AuthGateApprovalTest, plus the two pseudo sources):
 *   kotlinc -d out -jvm-target 17 \
 *     <fakes>/android/content/Context.kt \
 *     <fakes>/android/content/SharedPreferences.kt \
 *     <fakes>/android/os/Looper.kt \
 *     <fakes>/android/os/Handler.kt \
 *     <fakes>/android/util/Log.kt \
 *     <fakes>/testutil/FakeContext.kt \
 *     <platform/storage: LoomPreferences, ActivityLog, LoomJson> \
 *     <core/common: LoomEventBus, LoomStateEvent> \
 *     <core/common/lifecycle: LifecycleState, LifecycleController> \
 *     <core/common: RequestOrigin> \
 *     AuthGate.kt AuthActionRegistry.kt AuthConfirmationHost.kt \
 *     AuthGrantStore.kt PseudoControlPolicy.kt PseudoControlStore.kt \
 *     PseudoControlTest.kt
 *   java -cp out com.loom.assistant.intelligence.authgate.PseudoControlTestKt
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

private class RecordingHost : AuthConfirmationHost {
    var shows = 0

    override fun showConfirmation(pending: AuthGate.PendingConfirmation): Boolean {
        shows++
        return true
    }

    override fun hideConfirmation() {}
    override fun hideConfirmation(pendingId: String) {}
    override fun hideAllConfirmations() {}
}

fun main() {
    val ctx = FakeContext()
    val gate = AuthGate.init(ctx)
    val prefs = LoomPreferences.get(ctx)
    val store = PseudoControlStore.get(ctx)
    val host = RecordingHost()
    gate.confirmationHost = host
    prefs.confirmStrictness = 0

    val yt = "com.google.android.youtube"
    val fb = "com.facebook.katana"
    val instagram = "com.instagram.android"

    // ---------- A. The policy table ----------
    check(
        "A: exactly the ten approved apps",
        PseudoControlPolicy.FREE_APPS.map { it.label } == listOf(
            "YouTube", "YouTube Music", "Chrome", "Facebook", "WhatsApp",
            "Phone", "Messages", "Gallery", "Clock", "Settings",
        ),
    )
    val coveredKeys = PseudoControlPolicy.COVERED_KEYS
    check(
        "A: seven reversible keys covered for YouTube",
        coveredKeys.all { PseudoControlPolicy.covers(it, yt) },
    )
    check(
        "A: covered keys are the grant-eligible set plus tap.device (same reversible class)",
        coveredKeys == AuthGrantStore.GRANT_ELIGIBLE_KEYS + AuthActionRegistry.ACTION_TAP,
    )
    check(
        "A: tap.device is covered in an approved app (the dispatcher tap key)",
        PseudoControlPolicy.covers(AuthActionRegistry.ACTION_TAP, yt),
    )
    check(
        "A: the plan-cover key is covered only when scoped to an approved app",
        PseudoControlPolicy.covers(AuthActionRegistry.ACTION_DEMO_SEQUENCE, yt) &&
            !PseudoControlPolicy.covers(AuthActionRegistry.ACTION_DEMO_SEQUENCE, "") &&
            !PseudoControlPolicy.covers(AuthActionRegistry.ACTION_DEMO_SEQUENCE, instagram),
    )
    check(
        "A: runtime role packages extend the approved set only via extras",
        PseudoControlPolicy.covers(
            AuthActionRegistry.ACTION_TAP_GUIDANCE, "com.example.dialer",
            setOf("com.example.dialer"),
        ) &&
            !PseudoControlPolicy.covers(AuthActionRegistry.ACTION_TAP_GUIDANCE, "com.example.dialer"),
    )
    check(
        "A: demoSequence factory carries the scope package",
        AuthActionRegistry.demoSequence("g", "YouTube", 3, scopePackage = yt).scope == yt &&
            AuthActionRegistry.demoSequence("g", "YouTube", 3).scope.isBlank(),
    )
    check(
        "A: tap.guidance covered in every approved package",
        PseudoControlPolicy.FREE_APPS.flatMap { it.packages }
            .all { PseudoControlPolicy.covers(AuthActionRegistry.ACTION_TAP_GUIDANCE, it) },
    )
    check(
        "A: NEVER keys never covered, even in YouTube",
        PseudoControlPolicy.NEVER_KEYS.none { PseudoControlPolicy.covers(it, yt) },
    )
    check(
        "A: blank scope never covered",
        !PseudoControlPolicy.covers(AuthActionRegistry.ACTION_TAP_GUIDANCE, ""),
    )
    check(
        "A: unapproved app never covered",
        !PseudoControlPolicy.covers(AuthActionRegistry.ACTION_TAP_GUIDANCE, instagram) &&
            !PseudoControlPolicy.covers(AuthActionRegistry.ACTION_APP_LAUNCH, instagram),
    )
    check(
        "A: settings.change not covered in Settings (show only)",
        !PseudoControlPolicy.covers("settings.change", "com.android.settings"),
    )
    check(
        "A: app labels resolve, unknown package is null",
        PseudoControlPolicy.appLabel(yt) == "YouTube" &&
            PseudoControlPolicy.appLabel(fb) == "Facebook" &&
            PseudoControlPolicy.appLabel("com.whatsapp") == "WhatsApp" &&
            PseudoControlPolicy.appLabel("com.kuda.app") == null,
    )

    // ---------- B. The store ----------
    check(
        "B: fresh store is off with the prompt due",
        !store.isGranted() && store.shouldShowPrompt() && store.statusLine() == "Off",
    )
    store.dismissPrompt()
    check(
        "B: Not now answers the prompt without granting",
        !store.isGranted() && !store.shouldShowPrompt(),
    )
    store.grant()
    check(
        "B: grant turns it on and answers the prompt",
        store.isGranted() && !store.shouldShowPrompt() &&
            store.statusLine() == "On — 10 apps",
    )
    check(
        "B: grant is persisted in SharedPreferences",
        ctx.prefs.getBoolean("granted", false),
    )
    check(
        "B: grant persists bound to the current list version (a restart reads the same prefs)",
        ctx.prefs.getInt("list_version", -1) == PseudoControlPolicy.PSEUDO_VERSION,
    )
    store.revoke()
    check(
        "B: revoke turns it off at once and the prompt is due again",
        !store.isGranted() && store.shouldShowPrompt() && store.statusLine() == "Off",
    )
    check(
        "B: revoke clears the persisted grant",
        !ctx.prefs.getBoolean("granted", true),
    )

    // ---------- C. The gate ----------
    // NOTE (finisher fix, 2026-11-11): gate.check() PARKS the
    // pending without presenting it — only request() calls
    // host.showConfirmation(). So "a card appears" is measured
    // here by the parked-pending count (pendingCount() +1),
    // not by host.shows; the request() check below still proves
    // the card is actually shown on the real dispatch path.
    val fbTap = AuthActionRegistry.tapGuidanceTarget(
        "Tap Reels", scope = fb, appLabel = "Facebook",
    )
    // No grant: the card appears.
    val pendBefore = gate.pendingCount()
    val noGrant = gate.check(fbTap, RequestOrigin.MODEL)
    check(
        "C: MODEL tap in Facebook without the grant still cards",
        noGrant is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == pendBefore + 1,
    )
    // Grant: MODEL-origin in-scope action allowed, NO card.
    store.grant()
    val pendGranted = gate.pendingCount()
    val granted = gate.check(fbTap, RequestOrigin.MODEL)
    check(
        "C: MODEL tap in Facebook with the grant is allowed, no card",
        granted is AuthGate.GateResult.Allow && gate.pendingCount() == pendGranted,
    )
    // request() path (the real dispatch path) honours it too.
    var requestToken: AuthGate.AuthToken? = null
    val showsRequest = host.shows
    gate.request(fbTap, RequestOrigin.MODEL) { requestToken = it }
    check(
        "C: request() with the grant issues a token, no card",
        requestToken != null && host.shows == showsRequest,
    )
    // Sends and calls still card with the grant active.
    val sendPend = gate.pendingCount()
    val send = gate.check(
        AuthActionRegistry.messageSend("Mum", "hello"), RequestOrigin.MODEL,
    )
    check(
        "C: WhatsApp message still cards with the grant active",
        send is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == sendPend + 1,
    )
    val callPend = gate.pendingCount()
    val call = gate.check(
        AuthActionRegistry.callPlace("Mum"), RequestOrigin.MODEL,
    )
    check(
        "C: call still cards with the grant active",
        call is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == callPend + 1,
    )
    // Sensitive + financial deny even with the grant.
    check(
        "C: sensitive-app automation denies with the grant active",
        gate.check(
            AuthActionRegistry.SENSITIVE_APP_AUTOMATION, RequestOrigin.MODEL,
        ) is AuthGate.GateResult.Deny,
    )
    check(
        "C: financial transaction denies with the grant active",
        gate.check(
            AuthActionRegistry.FINANCIAL_TRANSACTION, RequestOrigin.MODEL,
        ) is AuthGate.GateResult.Deny,
    )
    // Unapproved app still cards with the grant active.
    val igPend = gate.pendingCount()
    val ig = gate.check(
        AuthActionRegistry.appLaunch(instagram, "Instagram"), RequestOrigin.MODEL,
    )
    check(
        "C: unapproved app still cards with the grant active",
        ig is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == igPend + 1,
    )
    // Paranoid strictness disables pseudo control.
    prefs.confirmStrictness = 2
    val paranoid = gate.check(fbTap, RequestOrigin.MODEL)
    check(
        "C: paranoid strictness disables pseudo control",
        paranoid !is AuthGate.GateResult.Allow,
    )
    prefs.confirmStrictness = 0
    // Revoke: the next in-scope action cards again.
    store.revoke()
    val revokePend = gate.pendingCount()
    val afterRevoke = gate.check(fbTap, RequestOrigin.MODEL)
    check(
        "C: after revoke the next tap cards again",
        afterRevoke is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == revokePend + 1,
    )
    // DIRECT_USER launch in an approved app with the grant: allowed.
    store.grant()
    val direct = gate.check(
        AuthActionRegistry.appLaunch(yt, "YouTube"), RequestOrigin.DIRECT_USER,
    )
    check(
        "C: DIRECT_USER launch in YouTube with the grant is allowed",
        direct is AuthGate.GateResult.Allow,
    )

    // ---------- D. The plan cover under a grant (F4, 2026-11-13) ----------
    // Grant is active (re-granted above). A SCOPED cover request in
    // an approved app is allowed without a card; an unscoped cover
    // always cards; revoke restores the card.
    val scopedCover = AuthActionRegistry.demoSequence(
        "play Davido", "YouTube", 6, scopePackage = yt,
    )
    val coverPend = gate.pendingCount()
    check(
        "D: scoped plan cover in YouTube with the grant is allowed, no card",
        gate.check(scopedCover, RequestOrigin.MODEL) is AuthGate.GateResult.Allow &&
            gate.pendingCount() == coverPend,
    )
    val unscopedCover = AuthActionRegistry.demoSequence("play Davido", "YouTube", 6)
    val unscopedPend = gate.pendingCount()
    check(
        "D: unscoped plan cover with the grant still cards",
        gate.check(unscopedCover, RequestOrigin.MODEL) is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == unscopedPend + 1,
    )
    val outCover = AuthActionRegistry.demoSequence(
        "open Instagram", "Instagram", 3, scopePackage = instagram,
    )
    val outPend = gate.pendingCount()
    check(
        "D: scoped plan cover in an unapproved app with the grant still cards",
        gate.check(outCover, RequestOrigin.MODEL) is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == outPend + 1,
    )
    // Runtime role packages via the injected provider.
    gate.setPseudoRolePackagesProvider { setOf("com.example.dialer") }
    val dialerTap = AuthActionRegistry.tapGuidanceTarget(
        "Tap Mum", scope = "com.example.dialer", appLabel = "Phone",
    )
    val dialerPend = gate.pendingCount()
    check(
        "D: runtime-resolved dialer package is covered with the grant",
        gate.check(dialerTap, RequestOrigin.MODEL) is AuthGate.GateResult.Allow &&
            gate.pendingCount() == dialerPend,
    )
    gate.setPseudoRolePackagesProvider { throw RuntimeException("resolver down") }
    val throwPend = gate.pendingCount()
    check(
        "D: a throwing role provider fails safe (card, never a crash or a silent allow)",
        gate.check(dialerTap, RequestOrigin.MODEL) is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == throwPend + 1,
    )
    gate.setPseudoRolePackagesProvider { emptySet() }
    store.revoke()
    val revokeCoverPend = gate.pendingCount()
    check(
        "D: after revoke the scoped plan cover cards again",
        gate.check(scopedCover, RequestOrigin.MODEL) is AuthGate.GateResult.NeedsConfirmation &&
            gate.pendingCount() == revokeCoverPend + 1,
    )

    println("PseudoControlTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED")
}
