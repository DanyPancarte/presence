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
    val radio = RadioVoice(context) { if (ttsReady) tts else null }
    private var ttsReady = false
    private var pendingSpeak: Pair<String, () -> Unit>? = null

    /** Live RMS from the recognizer (dB-ish, -2..10), drives the organism while it listens. */
    var onLevel: (Float) -> Unit = {}
    /** Live partial transcript while listening. */
    var onPartial: (String) -> Unit = {}
    /** Fires when speech actually starts (the mic was open before, silently). */
    var onBegin: () -> Unit = {}

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

    private var listener: RecognitionListener? = null

    /** One recognizer for the whole session (recreating it per utterance makes the service drop: error 11). */
    private fun recognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = onBegin()
            override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                val l = listener ?: return
                listener = null
                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CLIENT) {
                    // Service gone: drop this instance, the next listen() rebuilds it.
                    recognizer?.destroy(); recognizer = null
                }
                l.onError(error)
            }
            override fun onResults(results: Bundle?) { val l = listener ?: return; listener = null; l.onResults(results) }
            override fun onPartialResults(partialResults: Bundle?) { listener?.onPartialResults(partialResults) }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer = r
        return r
    }

    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit, onEnd: () -> Unit = {}) {
        listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onEnd()
            override fun onError(error: Int) {
                onEnd()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> ""
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission micro refusée"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Reconnaissance : réseau"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Télécharge le pack vocal Français (Canada) hors ligne"
                    else -> "Reconnaissance vocale : erreur $error"
                }
                onError(msg)
            }
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (best.isEmpty()) onError("") else onResult(best)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
        recognizer().startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-CA")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        })
    }

    fun stopListening() {
        listener = null
        recognizer?.cancel()
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        val t = tts
        if (t == null || !ttsReady) { pendingSpeak = text to onDone; return }
        radio.speak(text, onDone)
    }

    fun stopSpeaking() { tts?.stop(); radio.stop() }

    fun release() {
        stopListening()
        recognizer?.destroy(); recognizer = null
        tts?.shutdown()
        tts = null
    }
}
