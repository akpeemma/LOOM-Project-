package com.loom.assistant.interaction.voice

/**
 * Phase 2 (§C, §7-D) — mic-state grounding checks for MicGrounding.
 * Pure JVM: MicGrounding has no android.* imports.
 * Run: kotlinc VoiceSessionState.kt MicGrounding.kt MicGroundingTest.kt -d out
 * && java -cp out com.loom.assistant.interaction.voice.MicGroundingTestKt
 *
 * The §7-D requirement: drive the controller through mic-released → the
 * AI-supplied context reports released; session live → reports live;
 * deliberately desync (controller reports released while a stale flag claims
 * live) → the context follows the CONTROLLER, otherwise the test fails.
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

private fun liveInputs() = MicGrounding.Inputs(
    session = MicGrounding.SessionMic.LISTENING,
    recognizerLive = true,
    micOwned = true,
    permissionGranted = true,
)

fun main() {
    // ---- 1. mic released → context reports released ----
    val released = MicGrounding.derive(
        MicGrounding.Inputs(
            session = MicGrounding.SessionMic.IDLE,
            recognizerLive = false,
            micOwned = false,
            permissionGranted = true,
        )
    )
    check("released: micLive is false", !released.micLive)
    check("released: text says not live", released.text.contains("not live"))
    check("released: no live-listening claim", !released.text.contains("live and listening"))

    // ---- 2. session live → context reports live ----
    val live = MicGrounding.derive(liveInputs())
    check("live: micLive is true", live.micLive)
    check("live: text says live and listening", live.text.contains("live and listening"))
    check("live: no disagreement", !live.disagreement)

    // ---- 3. DESYNC (§7-D): controller released, stale flag claims live ----
    // The context MUST follow the controller. If this ever reports live,
    // the R4 contradiction is back and the test fails.
    val desync = MicGrounding.derive(
        liveInputs().copy(
            recognizerLive = false, // controller: recognizer released
            micOwned = false,       // controller: ownership cleared
            staleLiveClaim = true,  // stale flag: still claims live
        )
    )
    check("desync: micLive is false (follows controller)", !desync.micLive)
    check("desync: text says not live", desync.text.contains("not live"))
    check("desync: disagreement flagged", desync.disagreement)
    check(
        "desync: text never claims live listening",
        !desync.text.contains("live and listening"),
    )

    // ---- 4. permission unknown (never checked) → not live ----
    val unknownPerm = MicGrounding.derive(liveInputs().copy(permissionGranted = null))
    check("unknown permission: micLive is false", !unknownPerm.micLive)
    check("unknown permission: text says not live", unknownPerm.text.contains("not live"))

    // ---- 5. permission denied → not live ----
    val deniedPerm = MicGrounding.derive(liveInputs().copy(permissionGranted = false))
    check("denied permission: micLive is false", !deniedPerm.micLive)
    check("denied permission: disagreement flagged", deniedPerm.disagreement)

    // ---- 6. recognizer died while session thinks it listens → not live ----
    val deadRecognizer = MicGrounding.derive(liveInputs().copy(recognizerLive = false))
    check("dead recognizer: micLive is false", !deadRecognizer.micLive)
    check("dead recognizer: disagreement flagged", deadRecognizer.disagreement)

    // ---- 7. session lost ownership → not live ----
    val lostOwnership = MicGrounding.derive(liveInputs().copy(micOwned = false))
    check("lost ownership: micLive is false", !lostOwnership.micLive)

    // ---- 8. processing a live turn: not "listening now", but the turn's
    // provenance is honestly reported ----
    val processing = MicGrounding.derive(
        liveInputs().copy(session = MicGrounding.SessionMic.PROCESSING_TURN)
    )
    check("processing: micLive is false (not capturing now)", !processing.micLive)
    check(
        "processing: provenance reported",
        processing.text.contains("transcribed from live speech just now"),
    )

    // ---- 9. soft failures keep the retry sentence ----
    val soft = MicGrounding.derive(liveInputs().copy(softFailures = 2))
    check("soft failures: retry sentence present", soft.text.contains("needed retries"))

    // ---- 10. stale flag alone can NEVER produce a live verdict ----
    val staleOnly = MicGrounding.derive(
        MicGrounding.Inputs(
            session = MicGrounding.SessionMic.IDLE,
            recognizerLive = false,
            micOwned = false,
            permissionGranted = false,
            staleLiveClaim = true,
        )
    )
    check("stale flag alone: micLive is false", !staleOnly.micLive)

    println("MicGroundingTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
