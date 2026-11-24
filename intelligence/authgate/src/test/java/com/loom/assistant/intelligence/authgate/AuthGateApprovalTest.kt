package com.loom.assistant.intelligence.authgate

import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.platform.storage.LoomPreferences
import testutil.FakeContext

/**
 * Master §8 — AuthGate approval + security tests.
 *
 * Covers:
 *  A. Card clobbering (gate half): two pendings parked at once stay
 *     independently answerable — resolving/cancelling one hides only
 *     its card (pending-aware hide), never a blanket hide that would
 *     orphan the other's card. (The service-side queue ordering is
 *     covered by AuthCardQueueTest.)
 *  B. Silent no-card hole (gate half): when the host cannot show the
 *     confirmation (returns false, throws, or no host bound) the gate
 *     denies immediately, invokes the continuation with null, and parks
 *     nothing.
 *  C. Scoped revocable grants: a direct user command with a stored
 *     grant skips the card and still gets an ALLOW-class token; a model
 *     request with the same grant still gets its card; grants are
 *     revocable; sensitive/financial actions never skip; paranoid
 *     strictness escalates past grants; the "always allow" opt-in is
 *     offered only to direct user commands and only creates a grant on
 *     approval.
 *  D. No duplicate confirmations: one direct user command produces at
 *     most one pending / one card.
 *
 * Run (from loom-android, compile WITHOUT android.jar — same pattern
 * as AuthGateDenyTest, plus the real LifecycleController and the
 * RequestOrigin + AuthGrantStore sources; fakes live at
 * ~/workspace/brahma/loom/build/loom-w6-fakes, outside the repo so the
 * android.* stubs never collide with the real Android Gradle build):
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
 *     AuthGrantStore.kt AuthGateApprovalTest.kt
 *   java -cp out com.loom.assistant.intelligence.authgate.AuthGateApprovalTestKt
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
    var showResult = true
    var throwOnShow = false
    val shownOffers = mutableListOf<String?>()
    val shownOrigins = mutableListOf<RequestOrigin>()
    val hideIds = mutableListOf<String>()
    var hideAllCalls = 0
    var blanketHides = 0

    override fun showConfirmation(pending: AuthGate.PendingConfirmation): Boolean {
        shows++
        shownOffers.add(pending.grantOffer)
        shownOrigins.add(pending.origin)
        if (throwOnShow) throw IllegalStateException("boom")
        return showResult
    }

    override fun hideConfirmation() {
        blanketHides++
    }

    override fun hideConfirmation(pendingId: String) {
        hideIds.add(pendingId)
    }

    override fun hideAllConfirmations() {
        hideAllCalls++
    }
}

private fun needsId(result: AuthGate.GateResult): String {
    check("result is NeedsConfirmation", result is AuthGate.GateResult.NeedsConfirmation)
    return (result as? AuthGate.GateResult.NeedsConfirmation)?.pendingId ?: ""
}

fun main() {
    val ctx = FakeContext()
    val gate = AuthGate.init(ctx)
    val prefs = LoomPreferences.get(ctx)
    val store = AuthGrantStore.get(ctx)
    val host = RecordingHost()
    gate.confirmationHost = host
    prefs.confirmStrictness = 0

    val yt = AuthActionRegistry.appLaunch("com.google.android.youtube", "YouTube")
    val ytOther = AuthActionRegistry.appLaunch("com.google.android.apps.youtube.music", "YouTube Music")

    // ---------- A. Clobbering: two pendings both answerable ----------
    var tokenA: AuthGate.AuthToken? = null
    var tokenB: AuthGate.AuthToken? = null
    val idA = needsId(gate.request(yt, RequestOrigin.DIRECT_USER) { tokenA = it })
    val idB = needsId(gate.request(ytOther, RequestOrigin.DIRECT_USER) { tokenB = it })
    check("A: two distinct pendings", idA.isNotBlank() && idB.isNotBlank() && idA != idB)
    check("A: pendingCount is 2", gate.pendingCount() == 2)
    check("A: no hide before any resolve", host.hideIds.isEmpty() && host.blanketHides == 0)
    gate.resolve(idA, accepted = true)
    check("A: first pending resolves to a token", tokenA != null)
    check("A: resolving A hides only A's card", host.hideIds == listOf(idA))
    check("A: second pending still parked", gate.pendingCount() == 1)
    gate.resolve(idB, accepted = false)
    check("A: second pending resolves to null on deny", tokenB == null)
    check("A: resolving B hides only B's card", host.hideIds == listOf(idA, idB))
    check("A: no pendings left", gate.pendingCount() == 0)

    // Cancel path: cancelling one leaves the other answerable.
    var tokenC: AuthGate.AuthToken? = null
    var tokenD: AuthGate.AuthToken? = null
    val idC = needsId(gate.request(yt, RequestOrigin.DIRECT_USER) { tokenC = it })
    val idD = needsId(gate.request(ytOther, RequestOrigin.DIRECT_USER) { tokenD = it })
    val hidesBefore = host.hideIds.size
    gate.cancelPending(idC)
    check("A: cancel invokes continuation with null", tokenC == null)
    check("A: cancel hides only C's card", host.hideIds.size == hidesBefore + 1 && host.hideIds.last() == idC)
    check("A: D still parked after C cancelled", gate.pendingCount() == 1)
    gate.resolve(idD, accepted = true)
    check("A: D still answerable after C cancelled", tokenD != null)

    // ---------- B. Silent no-card hole: fail honestly, park nothing ----------
    val showsBefore = host.shows
    host.showResult = false
    var tokenE: AuthGate.AuthToken? = null
    val denied = gate.request(yt, RequestOrigin.DIRECT_USER) { tokenE = it }
    check("B: host-false denies", denied is AuthGate.GateResult.Deny)
    check("B: host-false continuation gets null", tokenE == null)
    check("B: host-false parks nothing", gate.pendingCount() == 0)
    check("B: host was still asked once", host.shows == showsBefore + 1)
    host.showResult = true
    host.throwOnShow = true
    var tokenF: AuthGate.AuthToken? = null
    val denied2 = gate.request(yt, RequestOrigin.DIRECT_USER) { tokenF = it }
    check("B: host-throw denies", denied2 is AuthGate.GateResult.Deny)
    check("B: host-throw continuation gets null", tokenF == null)
    check("B: host-throw parks nothing", gate.pendingCount() == 0)
    host.throwOnShow = false
    gate.confirmationHost = null
    var tokenG: AuthGate.AuthToken? = null
    val denied3 = gate.request(yt, RequestOrigin.DIRECT_USER) { tokenG = it }
    check("B: no-host denies", denied3 is AuthGate.GateResult.Deny)
    check("B: no-host continuation gets null", tokenG == null)
    check("B: no-host parks nothing", gate.pendingCount() == 0)
    gate.confirmationHost = host

    // ---------- C. Scoped revocable grants ----------
    check("C: grant stores", store.grant(yt.key, yt.scope, yt.grantOffer!!))
    check("C: grant listed", store.hasGrant(yt.key, yt.scope))

    // C1: direct user + grant → ALLOW token, no card.
    val showsC1 = host.shows
    var tokenH: AuthGate.AuthToken? = null
    val allowH = gate.request(yt, RequestOrigin.DIRECT_USER) { tokenH = it }
    check("C1: direct+grant allows", allowH is AuthGate.GateResult.Allow)
    check("C1: direct+grant issues a token", tokenH != null)
    check("C1: direct+grant shows no card", host.shows == showsC1)

    // C2: pure decision agrees.
    val checkH = gate.check(yt, RequestOrigin.DIRECT_USER)
    check("C2: check() direct+grant allows", checkH is AuthGate.GateResult.Allow)

    // C3: model + same grant → card, and no opt-in offered.
    val modelApp = AuthActionRegistry.appLaunch("com.example.modelapp", "ModelApp")
    val showsC3 = host.shows
    var tokenI: AuthGate.AuthToken? = null
    val idI = needsId(gate.request(modelApp, RequestOrigin.MODEL) { tokenI = it })
    check("C3: model request parks despite any grant", host.shows == showsC3 + 1)
    check("C3: model pending offers no always-allow", host.shownOffers.last() == null)
    check("C3: model pending origin is MODEL", host.shownOrigins.last() == RequestOrigin.MODEL)
    // C4: approving with the opt-in ticked as MODEL creates no grant.
    gate.resolve(idI, accepted = true, alwaysAllow = true)
    check("C4: model approval issues a token", tokenI != null)
    check("C4: model approval creates no grant", !store.hasGrant(modelApp.key, modelApp.scope))

    // C5: direct user, other app, no grant → exactly one card (D).
    val showsC5 = host.shows
    val countC5 = gate.pendingCount()
    var tokenJ: AuthGate.AuthToken? = null
    val idJ = needsId(gate.request(ytOther, RequestOrigin.DIRECT_USER) { tokenJ = it })
    check("D: one direct command → one card", host.shows == showsC5 + 1)
    check("D: one direct command → one pending", gate.pendingCount() == countC5 + 1)
    check("C5: other app gets the opt-in offer", host.shownOffers.last() == "Always allow opening YouTube Music")
    gate.resolve(idJ, accepted = false)
    check("C5: deny leaves no grant", !store.hasGrant(ytOther.key, ytOther.scope))

    // C6: blank scope never matches (no wildcard grants).
    var tokenK: AuthGate.AuthToken? = null
    val rK = gate.request(AuthActionRegistry.APP_LAUNCH, RequestOrigin.DIRECT_USER) { tokenK = it }
    check("C6: blank-scope request parks (no wildcard)", rK is AuthGate.GateResult.NeedsConfirmation)
    gate.resolve((rK as AuthGate.GateResult.NeedsConfirmation).pendingId, accepted = false)

    // C7: revoke → card again.
    check("C7: revoke removes the grant", store.revoke(yt.key, yt.scope))
    val showsC7 = host.shows
    var tokenL: AuthGate.AuthToken? = null
    val idL = needsId(gate.request(yt, RequestOrigin.DIRECT_USER) { tokenL = it })
    check("C7: after revoke the card returns", host.shows == showsC7 + 1)
    gate.resolve(idL, accepted = false)

    // C8: sensitive / financial never skip — and grants can't be stored for them.
    check("C8: sensitive key is not grant-eligible", !store.grant(AuthActionRegistry.ACTION_SENSITIVE_APP, "x", "y"))
    check("C8: financial key is not grant-eligible", !store.grant(AuthActionRegistry.ACTION_TAP_FINANCIAL, "x", "y"))
    val showsC8 = host.shows
    val denyS = gate.request(AuthActionRegistry.SENSITIVE_APP_AUTOMATION, RequestOrigin.DIRECT_USER) {}
    check("C8: sensitive action denied even for direct user", denyS is AuthGate.GateResult.Deny)
    val denyF = gate.request(AuthActionRegistry.FINANCIAL_TRANSACTION, RequestOrigin.DIRECT_USER) {}
    check("C8: financial action denied even for direct user", denyF is AuthGate.GateResult.Deny)
    check("C8: hard-deny shows no card", host.shows == showsC8)
    check("C8: hard-deny parks nothing", gate.pendingCount() == 0)
    // Forged milder risk class on a hard-deny key still denies.
    val forged = AuthGate.AuthAction(
        key = AuthActionRegistry.ACTION_SENSITIVE_APP,
        title = "forged",
        detail = "bypass attempt",
        riskClass = AuthGate.RiskClass.ALLOW,
    )
    val denyForged = gate.request(forged, RequestOrigin.DIRECT_USER) {}
    check("C8: forged sensitive key still denied", denyForged is AuthGate.GateResult.Deny)

    // C9: paranoid escalates past grants and never offers the opt-in.
    check("C9: grant re-stores", store.grant(yt.key, yt.scope, yt.grantOffer!!))
    prefs.confirmStrictness = 2
    val showsC9 = host.shows
    var tokenM: AuthGate.AuthToken? = null
    val idM = needsId(gate.request(yt, RequestOrigin.DIRECT_USER) { tokenM = it })
    check("C9: paranoid shows the card despite the grant", host.shows == showsC9 + 1)
    check("C9: paranoid offers no always-allow", host.shownOffers.last() == null)
    gate.resolve(idM, accepted = true, alwaysAllow = true)
    check("C9: paranoid approval issues a token", tokenM != null)
    prefs.confirmStrictness = 0
    check("C9: grant survived paranoid (not revoked by it)", store.hasGrant(yt.key, yt.scope))

    // C10: strict (1) still honours a direct-user grant.
    prefs.confirmStrictness = 1
    val showsC10 = host.shows
    var tokenN: AuthGate.AuthToken? = null
    val allowN = gate.request(yt, RequestOrigin.DIRECT_USER) { tokenN = it }
    check("C10: strict still honours a direct-user grant", allowN is AuthGate.GateResult.Allow)
    check("C10: strict grant shows no card", host.shows == showsC10)
    prefs.confirmStrictness = 0

    // C11: grant creation via the card opt-in, then the skip works.
    val fresh = AuthActionRegistry.appLaunch("com.example.freshapp", "FreshApp")
    var tokenO: AuthGate.AuthToken? = null
    val idO = needsId(gate.request(fresh, RequestOrigin.DIRECT_USER) { tokenO = it })
    check("C11: fresh app offers the opt-in", host.shownOffers.last() == "Always allow opening FreshApp")
    gate.resolve(idO, accepted = true, alwaysAllow = true)
    check("C11: approval with opt-in issues a token", tokenO != null)
    check("C11: approval with opt-in stores the grant", store.hasGrant(fresh.key, fresh.scope))
    val showsC11 = host.shows
    var tokenP: AuthGate.AuthToken? = null
    val allowP = gate.request(fresh, RequestOrigin.DIRECT_USER) { tokenP = it }
    check("C11: next direct command skips the card", allowP is AuthGate.GateResult.Allow)
    check("C11: skip still issues a token", tokenP != null)
    check("C11: skip shows no card", host.shows == showsC11)
    // A grant for one app never covers another app.
    var tokenQ: AuthGate.AuthToken? = null
    val rQ = gate.request(ytOther, RequestOrigin.DIRECT_USER) { tokenQ = it }
    check("C11: grant is app-scoped (other app still parks)", rQ is AuthGate.GateResult.NeedsConfirmation)
    gate.resolve((rQ as AuthGate.GateResult.NeedsConfirmation).pendingId, accepted = false)

    // C12: deny with the opt-in ticked stores nothing.
    val fresh2 = AuthActionRegistry.appLaunch("com.example.freshapp2", "FreshApp2")
    var tokenR: AuthGate.AuthToken? = null
    val idR = needsId(gate.request(fresh2, RequestOrigin.DIRECT_USER) { tokenR = it })
    gate.resolve(idR, accepted = false, alwaysAllow = true)
    check("C12: deny with opt-in stores no grant", !store.hasGrant(fresh2.key, fresh2.scope))

    // ---------- E. Device-evidence fix (2026-11-05, items 4+6+10) ----------
    // E1: the STATIC blank-scope action never offers a grant, even for a
    // direct user command — the search flow used to request exactly this.
    var tokenS0: AuthGate.AuthToken? = null
    val idS0 = needsId(gate.request(AuthActionRegistry.APP_LAUNCH, RequestOrigin.DIRECT_USER) { tokenS0 = it })
    check("E1: static blank-scope action offers no always-allow", host.shownOffers.last() == null)
    gate.resolve(idS0, accepted = false)

    // E2: the search sequence's keys are grant-eligible now — but a MODEL
    // request for a scoped search step is never offered a grant, and
    // approving it with the opt-in ticked stores nothing.
    val ytPkg = "com.google.android.youtube"
    check("E2: tap.guidance is grant-eligible", AuthGrantStore.isGrantEligible(AuthActionRegistry.ACTION_TAP_GUIDANCE))
    check("E2: device.type is grant-eligible", AuthGrantStore.isGrantEligible(AuthActionRegistry.ACTION_DEVICE_TYPE))
    check("E2: device.submit is grant-eligible", AuthGrantStore.isGrantEligible(AuthActionRegistry.ACTION_DEVICE_SUBMIT))
    check("E2: tap.guidance grant stores", store.grant(AuthActionRegistry.ACTION_TAP_GUIDANCE, ytPkg, "Always allow search steps in YouTube"))
    check("E2: device.type grant stores", store.grant(AuthActionRegistry.ACTION_DEVICE_TYPE, ytPkg, "Always allow typing in YouTube"))
    check("E2: device.submit grant stores", store.grant(AuthActionRegistry.ACTION_DEVICE_SUBMIT, ytPkg, "Always allow submitting searches in YouTube"))
    store.revoke(AuthActionRegistry.ACTION_TAP_GUIDANCE, ytPkg)
    store.revoke(AuthActionRegistry.ACTION_DEVICE_TYPE, ytPkg)
    store.revoke(AuthActionRegistry.ACTION_DEVICE_SUBMIT, ytPkg)
    val scopedTap = AuthActionRegistry.tapGuidanceTarget("Tap the search box in YouTube", ytPkg, "YouTube")
    var tokenS1: AuthGate.AuthToken? = null
    val idS1 = needsId(gate.request(scopedTap, RequestOrigin.MODEL) { tokenS1 = it })
    check("E2: MODEL scoped tap parks", host.shows > 0)
    check("E2: MODEL scoped tap offers no always-allow", host.shownOffers.last() == null)
    gate.resolve(idS1, accepted = true, alwaysAllow = true)
    check("E2: MODEL scoped tap approval issues a token", tokenS1 != null)
    check("E2: MODEL approval creates no search-step grant", !store.hasGrant(AuthActionRegistry.ACTION_TAP_GUIDANCE, ytPkg))

    // E3: a stored search-step grant suppresses the card for a DIRECT_USER
    // scoped request — but an UNSCOPED tap (guidance sessions, other
    // callers) still cards, and its offer stays null.
    check("E3: grant re-stores for suppression check", store.grant(AuthActionRegistry.ACTION_TAP_GUIDANCE, ytPkg, "Always allow search steps in YouTube"))
    val showsE3 = host.shows
    var tokenS2: AuthGate.AuthToken? = null
    val allowS2 = gate.request(scopedTap, RequestOrigin.DIRECT_USER) { tokenS2 = it }
    check("E3: direct+grant scoped tap allows", allowS2 is AuthGate.GateResult.Allow)
    check("E3: direct+grant scoped tap issues a token", tokenS2 != null)
    check("E3: direct+grant scoped tap shows no card", host.shows == showsE3)
    var tokenS3: AuthGate.AuthToken? = null
    val idS3 = needsId(gate.request(AuthActionRegistry.tapGuidanceTarget("Tap the search box in YouTube"), RequestOrigin.DIRECT_USER) { tokenS3 = it })
    check("E3: unscoped tap still parks despite the grant", host.shows == showsE3 + 1)
    check("E3: unscoped tap offers no always-allow", host.shownOffers.last() == null)
    gate.resolve(idS3, accepted = false)
    check("E3: scoped type grant suppresses its card", run {
        store.grant(AuthActionRegistry.ACTION_DEVICE_TYPE, ytPkg, "Always allow typing in YouTube")
        val before = host.shows
        var t: AuthGate.AuthToken? = null
        val r = gate.request(AuthActionRegistry.deviceType(ytPkg, "YouTube"), RequestOrigin.DIRECT_USER) { t = it }
        r is AuthGate.GateResult.Allow && t != null && host.shows == before
    })
    check("E3: scoped submit grant suppresses its card", run {
        store.grant(AuthActionRegistry.ACTION_DEVICE_SUBMIT, ytPkg, "Always allow submitting searches in YouTube")
        val before = host.shows
        var t: AuthGate.AuthToken? = null
        val r = gate.request(AuthActionRegistry.deviceSubmit(ytPkg, "YouTube"), RequestOrigin.DIRECT_USER) { t = it }
        r is AuthGate.GateResult.Allow && t != null && host.shows == before
    })
    store.revoke(AuthActionRegistry.ACTION_TAP_GUIDANCE, ytPkg)
    store.revoke(AuthActionRegistry.ACTION_DEVICE_TYPE, ytPkg)
    store.revoke(AuthActionRegistry.ACTION_DEVICE_SUBMIT, ytPkg)

    // E4: message.send and call.place can NEVER become grant-eligible —
    // a grant would let later sends/calls skip their per-message card.
    check("E4: message.send is not grant-eligible", !AuthGrantStore.isGrantEligible("message.send"))
    check("E4: message.send grant stores nothing", !store.grant("message.send", "x", "y"))
    check("E4: call.place is not grant-eligible", !AuthGrantStore.isGrantEligible("call.place"))
    check("E4: call.place grant stores nothing", !store.grant("call.place", "x", "y"))

    // E5: grant retroactivity — resolving card 1 with the opt-in ticked
    // cancels an already-parked DIRECT_USER card 2 for the same
    // (key + scope); a MODEL pending for the same key+scope survives.
    val retroApp = AuthActionRegistry.appLaunch("com.example.retro", "RetroApp")
    var tokenT1: AuthGate.AuthToken? = null
    var tokenT2: AuthGate.AuthToken? = null
    var calledT2 = false
    var tokenT3: AuthGate.AuthToken? = null
    val idT1 = needsId(gate.request(retroApp, RequestOrigin.DIRECT_USER) { tokenT1 = it })
    val idT2 = needsId(gate.request(retroApp, RequestOrigin.DIRECT_USER) { tokenT2 = it; calledT2 = true })
    val idT3 = needsId(gate.request(retroApp, RequestOrigin.MODEL) { tokenT3 = it })
    check("E5: three pendings parked", gate.pendingCount() == 3)
    gate.resolve(idT1, accepted = true, alwaysAllow = true)
    check("E5: first approval issues a token", tokenT1 != null)
    check("E5: grant stored by the first approval", store.hasGrant(retroApp.key, retroApp.scope))
    check("E5: parked direct card 2 was auto-cancelled", calledT2 && tokenT2 == null)
    check("E5: auto-cancel hid card 2", host.hideIds.contains(idT2))
    check("E5: MODEL pending survives the grant", gate.pendingCount() == 1)
    gate.resolve(idT3, accepted = true)
    check("E5: MODEL pending still answerable after the grant", tokenT3 != null)
    check("E5: no pendings left", gate.pendingCount() == 0)
    store.revoke(retroApp.key, retroApp.scope)

    // Cleanup: no pendings left behind.
    check("cleanup: no pendings left", gate.pendingCount() == 0)
    store.revokeAll()

    println("AuthGateApprovalTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
