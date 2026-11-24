package com.loom.assistant.interaction.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.loom.assistant.core.common.LoomEventBus
import com.loom.assistant.core.common.LoomStateEvent
import com.loom.assistant.core.common.SttHypothesisSelector
import java.util.Locale
import java.util.UUID

class DeviceVoicePipeline private constructor(private val appContext: Context) : VoicePipeline {

    companion object {
        @Volatile
        private var instance: DeviceVoicePipeline? = null

        fun get(context: Context): DeviceVoicePipeline {
            return instance ?: synchronized(this) {
                instance ?: DeviceVoicePipeline(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsQueue = ArrayDeque<() -> Unit>()

    override var state: VoiceState = VoiceState.IDLE
        private set

    override fun isSttAvailable(context: Context): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun startListening(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isSttAvailable(appContext)) {
            onError("Speech recognition is not available on this device.")
            return
        }
        stopListening()
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // MASTER Wave 4 (F16): silence-length tuning — the
            // recognizer's defaults used to decide when an utterance
            // ends, with no bound from LOOM (a short pause mid-command
            // could end the turn early). See VoicePipeline companion
            // for the values and the trade-off (short commands take
            // ~1 s longer to deliver; device check owed).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, VoicePipeline.COMPLETE_SILENCE_MS.toInt())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, VoicePipeline.POSSIBLY_COMPLETE_SILENCE_MS.toInt())
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setState(VoiceState.LISTENING)
                LoomEventBus.post(LoomStateEvent.Listening)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: return
                mainHandler.post { onPartial(text) }
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                // MASTER Wave 4 (F16) — read ALL hypotheses + the
                // confidence scores (Wave 3 found only the first
                // hypothesis was ever read, though 3 were requested,
                // and no scores were read at all). The best hypothesis
                // by confidence is the text LOOM acts on; when the
                // recognizer gives no scores, its first hypothesis
                // wins (the old behaviour). The full list + scores are
                // logged so the next replay can tell "heard garbage"
                // from "heard nothing" AND whether the right text was
                // ranked below a garbled first guess.
                val scores = try {
                    results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList()
                } catch (_: Exception) {
                    null
                }
                val selection = SttHypothesisSelector.select(list ?: emptyList(), scores)
                val text = selection.best
                android.util.Log.i(
                    "LoomStt",
                    "onResults hypotheses=${list?.size ?: 0} " +
                        "confidences=${scores?.joinToString(",") ?: "none"} " +
                        "chosen[${selection.bestIndex}]=\"$text\" " +
                        (selection.bestConfidence?.let { "confidence=$it" } ?: ""),
                )
                setState(VoiceState.THINKING)
                LoomEventBus.post(LoomStateEvent.Thinking)
                mainHandler.post { onResult(text) }
                cleanupRecognizer()
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
                    else -> "Recognition error ($error)."
                }
                // MASTER Wave 3 (F16) — log the RAW error code, not
                // just the mapped sentence: ERROR_NO_MATCH (7) vs
                // ERROR_SPEECH_TIMEOUT (6) vs ERROR_NETWORK (2) are
                // different causes, and the mapped sentence erases
                // the distinction the next diagnosis needs.
                android.util.Log.i("LoomStt", "onError code=$error msg=\"$msg\"")
                setState(VoiceState.IDLE)
                LoomEventBus.post(LoomStateEvent.Error(msg))
                mainHandler.post { onError(msg) }
                cleanupRecognizer()
            }

            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })
        try {
            sr.startListening(intent)
        } catch (e: SecurityException) {
            onError("Microphone permission denied.")
            cleanupRecognizer()
        } catch (e: Exception) {
            // Stage 2A: a busy/dead recognizer service must not crash the app.
            // Report it as a recognizer error so the session can retry honestly.
            onError("Recognition error (${e::class.simpleName}).")
            cleanupRecognizer()
        }
    }

    override fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        cleanupRecognizer()
        if (state == VoiceState.LISTENING) {
            setState(VoiceState.IDLE)
            LoomEventBus.post(LoomStateEvent.Idle)
        }
    }

    private fun cleanupRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val run = {
            val engine = tts
            if (engine == null) {
                onError("Speech engine not ready.")
            } else {
            setState(VoiceState.SPEAKING)
            LoomEventBus.post(LoomStateEvent.Speaking)
            val utteranceId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(doneId: String?) {
                    if (doneId == utteranceId) {
                        setState(VoiceState.IDLE)
                        LoomEventBus.post(LoomStateEvent.Completed)
                        mainHandler.post { onDone() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleUtteranceError(utteranceId)
                }

                override fun onError(doneId: String?, errorCode: Int) {
                    handleUtteranceError(doneId)
                }

                private fun handleUtteranceError(doneId: String?) {
                    if (doneId == utteranceId) {
                        setState(VoiceState.IDLE)
                        LoomEventBus.post(LoomStateEvent.Error("Speech failed."))
                        mainHandler.post { onError("Speech failed.") }
                    }
                }
            })
            val params = Bundle()
            val rc = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (rc == TextToSpeech.ERROR) {
                setState(VoiceState.IDLE)
                mainHandler.post { onError("Speech failed to start.") }
            }
            }
        }
        ensureTts(run, onError)
    }

    override fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        if (state == VoiceState.SPEAKING) {
            setState(VoiceState.IDLE)
            LoomEventBus.post(LoomStateEvent.Idle)
        }
    }

    /**
     * Phase 2 (E.3-2, mismatch #8): the TTS queue previously drained after
     * end() — queued utterances spoke into the next session (or after mic
     * off). Dropped utterances are cancelled, never re-queued.
     */
    override fun clearPendingSpeech() {
        ttsQueue.clear()
    }

    override fun shutdown() {
        stopListening()
        stopSpeaking()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
    }

    private fun ensureTts(run: () -> Unit, onError: (String) -> Unit) {
        if (ttsReady && tts != null) {
            run()
            return
        }
        if (tts == null) {
            tts = TextToSpeech(appContext) { status ->
                mainHandler.post {
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        tts?.language = Locale.getDefault()
                        val q = ArrayList(ttsQueue)
                        ttsQueue.clear()
                        q.forEach { it() }
                    } else {
                        val q = ArrayList(ttsQueue)
                        ttsQueue.clear()
                        q.forEach { onError("Speech engine failed to start.") }
                    }
                }
            }
        }
        ttsQueue.add(run)
    }

    private fun setState(s: VoiceState) {
        state = s
    }
}
