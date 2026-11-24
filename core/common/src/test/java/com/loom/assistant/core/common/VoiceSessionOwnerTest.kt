package com.loom.assistant.core.common

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2 (E.4) — atomic mic-ownership checks for VoiceSessionOwner.
 * Pure JVM: VoiceSessionOwner has no android.* imports.
 * Run: kotlinc VoiceSessionOwner.kt VoiceSessionOwnerTest.kt -d out && java -cp out
 * com.loom.assistant.core.common.VoiceSessionOwnerTestKt
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected true, got false")
    }
}

private fun reset() {
    // Best-effort release from any state (tests run sequentially).
    VoiceSessionOwner.releaseInApp()
    VoiceSessionOwner.releaseFloating()
}

fun main() {
    reset()

    // ---- initial state: unowned ----
    check("initially unowned", !VoiceSessionOwner.isOwned)
    check("inAppActive false initially", !VoiceSessionOwner.inAppActive)
    check("floatingActive false initially", !VoiceSessionOwner.floatingActive)

    // ---- claim / release semantics ----
    check("claim in-app succeeds when free", VoiceSessionOwner.claimInApp())
    check("owned after in-app claim", VoiceSessionOwner.isOwned)
    check("inAppActive true after claim", VoiceSessionOwner.inAppActive)
    check("floatingActive false while in-app owns", !VoiceSessionOwner.floatingActive)
    check("floating claim fails while in-app owns", !VoiceSessionOwner.claimFloating())
    check("second in-app claim fails", !VoiceSessionOwner.claimInApp())
    check("release by non-owner fails", !VoiceSessionOwner.releaseFloating())
    check("owner unchanged after failed release", VoiceSessionOwner.inAppActive)
    check("release in-app succeeds", VoiceSessionOwner.releaseInApp())
    check("unowned after release", !VoiceSessionOwner.isOwned)
    check("double release fails", !VoiceSessionOwner.releaseInApp())

    // ---- floating side is symmetric ----
    check("claim floating succeeds when free", VoiceSessionOwner.claimFloating())
    check("floatingActive true after claim", VoiceSessionOwner.floatingActive)
    check("in-app claim fails while floating owns", !VoiceSessionOwner.claimInApp())
    check("release floating succeeds", VoiceSessionOwner.releaseFloating())
    check("unowned after floating release", !VoiceSessionOwner.isOwned)

    // ---- concurrency: N threads race one claim — exactly one winner ----
    reset()
    val threads = 32
    val winners = AtomicInteger(0)
    val startGate = CountDownLatch(1)
    val doneGate = CountDownLatch(threads)
    val pool = Executors.newFixedThreadPool(threads)
    repeat(threads) {
        pool.submit {
            try {
                startGate.await()
                if (VoiceSessionOwner.claimInApp()) winners.incrementAndGet()
            } finally {
                doneGate.countDown()
            }
        }
    }
    startGate.countDown()
    doneGate.await()
    pool.shutdown()
    check("exactly one claim winner out of $threads racers", winners.get() == 1)
    check("owner is IN_APP after the race", VoiceSessionOwner.currentOwner() == VoiceSessionOwner.Owner.IN_APP)

    // ---- mixed race: in-app vs floating claim — exactly one owner ----
    reset()
    val mixedWinners = AtomicInteger(0)
    val start2 = CountDownLatch(1)
    val done2 = CountDownLatch(threads)
    val pool2 = Executors.newFixedThreadPool(threads)
    repeat(threads) { i ->
        pool2.submit {
            try {
                start2.await()
                val won = if (i % 2 == 0) VoiceSessionOwner.claimInApp()
                          else VoiceSessionOwner.claimFloating()
                if (won) mixedWinners.incrementAndGet()
            } finally {
                done2.countDown()
            }
        }
    }
    start2.countDown()
    done2.await()
    pool2.shutdown()
    check("exactly one winner in mixed in-app/floating race", mixedWinners.get() == 1)
    check("mic owned after mixed race", VoiceSessionOwner.isOwned)

    reset()
    check("clean state after reset", !VoiceSessionOwner.isOwned)

    println("VoiceSessionOwnerTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
