package com.dany.presence.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Native STT (offline on Pixel) + TTS fr-CA, slightly fast. Main-thread only. */
class Speech(private val context: Context) {
    private val locale = Locale("fr", "CA")
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Pair<String, () -> Unit>? = null

    /** Live RMS from the recognizer (dB-ish, -2..10), drives the organism while it listens. */
    var onLevel: (Float) -> Unit = {}

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = locale
                tts?.setSpeechRate(1.15f)
                tts?.setPitch(0.95f)
                ttsReady = true
                pendingSpeak?.let { (t, done) -> speak(t, done) }
                pendingSpeak = null
            }
        }
    }

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit, onEnd: () -> Unit = {}) {
        stopListening()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onEnd()
            override fun onError(error: Int) {
                onEnd()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission micro refusée"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Reconnaissance : réseau"
                    else -> "Reconnaissance vocale : erreur $error"
                }
                onError(msg)
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull()?.trim().orEmpty()
                if (best.isEmpty()) onError("") else onResult(best)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-CA")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        })
    }

    fun stopListening() {
        recognizer?.destroy()
        recognizer = null
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        val t = tts
        if (t == null || !ttsReady) { pendingSpeak = text to onDone; return }
        val id = "p${System.nanoTime()}"
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) onDone() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) onDone() }
        })
        // STREAM_MUSIC routes to Bluetooth headphones when connected.
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC) }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    fun stopSpeaking() { tts?.stop() }

    fun release() {
        stopListening()
        tts?.shutdown()
        tts = null
    }
}
