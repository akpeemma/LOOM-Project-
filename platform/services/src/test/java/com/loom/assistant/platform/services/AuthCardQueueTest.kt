package com.loom.assistant.platform.services

/**
 * Master §8 — AuthCardQueue ordering tests (card-clobbering fix).
 *
 * Covers: first card shown, second card queued (not dropped, not
 * clobbering the first); hiding the current card advances the queue so
 * the next pending becomes answerable; hiding a queued card drops only
 * it and leaves the current card untouched; hiding an unknown id is a
 * no-op (a stale hide can never kill a live card); clearAll drops
 * everything (teardown only); the queue works again after clearAll.
 *
 * The queue is pure Kotlin (no Android imports) — the service maps its
 * events to gate calls (attach failure → AuthGate.cancelPending), which
 * the service's own lifecycle can't exercise in the JVM harness; the
 * gate half of the fix is covered by AuthGateApprovalTest.
 *
 * Run (from loom-android):
 *   kotlinc -d out -jvm-target 17 \
 *     platform/services/src/main/java/com/loom/assistant/platform/services/AuthCardQueue.kt \
 *     platform/services/src/test/java/com/loom/assistant/platform/services/AuthCardQueueTest.kt
 *   java -cp out com.loom.assistant.platform.services.AuthCardQueueTestKt
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

private fun card(id: String) = AuthCardQueue.Card(
    pendingId = id,
    title = "Open?",
    detail = "test card $id",
)

fun main() {
    val q = AuthCardQueue()

    // First card becomes current.
    check("first show is SHOWN", q.show(card("A")) == AuthCardQueue.ShowResult.SHOWN)
    check("current is A", q.current()?.pendingId == "A")
    check("size is 1", q.size() == 1)

    // Second card queues behind A — A stays current and answerable.
    check("second show is QUEUED", q.show(card("B")) == AuthCardQueue.ShowResult.QUEUED)
    check("current still A", q.current()?.pendingId == "A")
    check("one queued", q.queuedCount() == 1)
    check("size is 2", q.size() == 2)

    // Hiding a QUEUED card drops only it; current untouched.
    check("hide queued B returns null", q.hide("B") == null)
    check("current still A after dropping queued B", q.current()?.pendingId == "A")
    check("queue empty again", q.queuedCount() == 0)

    // Re-queue B and C; hiding current A advances to B.
    q.show(card("B"))
    q.show(card("C"))
    check("size is 3 with A+B+C", q.size() == 3)
    val next = q.hide("A")
    check("hide current A advances to B", next?.pendingId == "B")
    check("current is now B", q.current()?.pendingId == "B")
    check("C still queued", q.queuedCount() == 1)

    // Hiding B advances to C; hiding C empties the queue.
    check("hide current B advances to C", q.hide("B")?.pendingId == "C")
    check("current is now C", q.current()?.pendingId == "C")
    check("hide current C returns null", q.hide("C") == null)
    check("current is null", q.current() == null)
    check("size is 0", q.size() == 0)

    // Unknown id: stale hide is a no-op.
    q.show(card("D"))
    check("hide unknown returns null", q.hide("zzz") == null)
    check("current still D after stale hide", q.current()?.pendingId == "D")
    q.hide("D")

    // clearAll drops current + queue; the queue works again after.
    q.show(card("E"))
    q.show(card("F"))
    q.show(card("G"))
    check("size is 3 before clearAll", q.size() == 3)
    q.clearAll()
    check("clearAll empties current", q.current() == null)
    check("clearAll empties queue", q.queuedCount() == 0)
    check("show after clearAll is SHOWN", q.show(card("H")) == AuthCardQueue.ShowResult.SHOWN)
    check("current is H", q.current()?.pendingId == "H")

    // Device-evidence fix (2026-11-05, item 10): a queued card keeps
    // its OWN grant offer — queueing must never strip the opt-in or
    // copy another card's offer. A DIRECT_USER card (offer present)
    // queued behind a MODEL card (no offer) still offers when shown.
    val q2 = AuthCardQueue()
    val modelCard = AuthCardQueue.Card(
        pendingId = "M1", title = "Open?", detail = "model card", grantOffer = null,
    )
    val directCard = AuthCardQueue.Card(
        pendingId = "D1", title = "Open YouTube?",
        detail = "direct card", grantOffer = "Always allow opening YouTube",
    )
    check("model card is SHOWN", q2.show(modelCard) == AuthCardQueue.ShowResult.SHOWN)
    check("direct card is QUEUED", q2.show(directCard) == AuthCardQueue.ShowResult.QUEUED)
    check("current keeps the model card's null offer", q2.current()?.grantOffer == null)
    val advanced = q2.hide("M1")
    check("hide advances to the direct card", advanced?.pendingId == "D1")
    check("advanced card keeps its own offer", q2.current()?.grantOffer == "Always allow opening YouTube")
    check("offer survives the queue round-trip", advanced?.grantOffer == "Always allow opening YouTube")

    println("AuthCardQueueTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
