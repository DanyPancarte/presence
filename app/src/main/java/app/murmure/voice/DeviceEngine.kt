package app.murmure.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Reconnaissance vocale Android (sans clé), relancée en continu. */
class DeviceEngine(
    private val context: Context,
    private val language: String,
    private val sink: TranscriptSink,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var active = false
    private var softErrors = 0

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() = main.post {
        if (!available) {
            sink.onFailure("Aucun moteur de reconnaissance vocale sur cet appareil.", recoverable = false)
            return@post
        }
        active = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
        sink.onReady("Appareil")
        listen()
    }

    private fun listen() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
        runCatching { recognizer?.startListening(intent) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = sink.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) sink.onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            softErrors = 0
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            sink.onPartial("")
            if (!t.isNullOrBlank()) sink.onFinal(" " + t.trim().replaceFirstChar { it.uppercase() } + ".")
            listen()
        }

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    softErrors++
                    main.postDelayed({ listen() }, if (softErrors > 5) 600L else 120L)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    sink.onFailure("Permission micro refusée.", recoverable = false)
                else -> {
                    softErrors++
                    if (softErrors > 8) sink.onFailure("Reconnaissance interrompue (code $error).", recoverable = false)
                    else main.postDelayed({ listen() }, 400)
                }
            }
        }
    }

    fun stop() = main.post {
        active = false
        runCatching { recognizer?.stopListening() }
        main.postDelayed({ recognizer?.destroy(); recognizer = null }, 600)
    }

    fun cancel() = main.post {
        active = false
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
    }
}
