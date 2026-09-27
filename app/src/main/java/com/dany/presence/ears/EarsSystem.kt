package com.dany.presence.ears

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.dany.presence.core.Bus
import com.dany.presence.core.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

/**
 * The always-open microphone.
 *
 * One on-device [SpeechRecognizer] for the whole started session (a new instance per utterance makes
 * the service drop: error 11), relaunched after every result, silence or benign error, closed while
 * the synthetic voice plays so it never transcribes itself.
 *
 * Emits: [Signal.SpeechStart], [Signal.SpeechPartial], [Signal.SpeechFinal], [Signal.SpeechIdle]
 * (a cycle ended without a final transcript), [Signal.Level] (~30 Hz, always, decays to 0 in silence)
 * and [Signal.Failed] (French, rate-limited). Listens: [Signal.Speaking].
 *
 * Gating (who deserves an answer) is the Mind's job: every final transcript goes on the bus.
 * The recognizer is main-thread only, so everything but the Level ticker runs on the main looper;
 * [start] and [stop] hop there when called from elsewhere.
 */
class EarsSystem(private val context: Context, private val bus: Bus, private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())

    // ---- main-thread state
    private var running = false
    private var speaking = false
    private var listening = false      // a startListening cycle is in flight
    private var ready = false          // the service accepted this cycle (onReadyForSpeech)
    private var heard = false          // SpeechStart emitted for this cycle
    private var lastPartial = ""
    private var recognizer: SpeechRecognizer? = null
    private var restart: Runnable? = null
    private var speakingJob: Job? = null
    private var levelJob: Job? = null
    private var lastFail = ""
    private var lastFailAt = 0L

    /** Bumped on stop() and on every rebuild: a callback from an older instance is dropped. */
    private val epoch = AtomicInteger()

    /** Latest onRmsChanged mapped to 0..1, and when it arrived (uptime ms). Read by the ticker. */
    @Volatile private var rmsLevel = 0f
    @Volatile private var rmsAt = 0L

    /** Opens the mic for good. Idempotent. Needs RECORD_AUDIO already granted. */
    fun start() = onMain { startOnMain() }

    /** Closes the mic and silences every callback. Idempotent. */
    fun stop() {
        epoch.incrementAndGet()
        onMain { stopOnMain() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === main.looper) block() else main.post { block() }
    }

    private fun startOnMain() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bus.emit(Signal.Failed("Permission micro refusée"))
            return
        }
        running = true
        speaking = false
        speakingJob = scope.launch(Dispatchers.Main.immediate) {
            bus.of<Signal.Speaking>().collect { onSpeaking(it.on) }
        }
        levelJob = scope.launch(Dispatchers.Default) {
            val meter = MicMeter()
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                // No fresh RMS (between cycles, or while the voice plays) → the meter exhales to 0.
                val target = if (now - rmsAt <= RMS_STALE_MS) rmsLevel else 0f
                meter.step(target, now)
                bus.emit(meter.snapshot())
                delay(TICK_MS)
            }
        }
        listenSoon(0)
    }

    private fun stopOnMain() {
        if (!running) return
        running = false
        restart?.let(main::removeCallbacks); restart = null
        speakingJob?.cancel(); speakingJob = null
        levelJob?.cancel(); levelJob = null
        if (listening) { recognizer?.cancel(); endCycle(finalDone = false) }
        recognizer?.destroy(); recognizer = null
        rmsLevel = 0f
        bus.emit(Signal.Level(0f, FloatArray(BANDS), 0f))
    }

    // ---- the voice plays: mic closed -------------------------------------------------------------

    private fun onSpeaking(on: Boolean) {
        if (!running) return
        if (on) {
            speaking = true
            restart?.let(main::removeCallbacks); restart = null
            if (listening) { recognizer?.cancel(); endCycle(finalDone = false) }
            rmsLevel = 0f
        } else if (speaking) {
            speaking = false
            listenSoon(REOPEN_AFTER_VOICE_MS)
        }
    }

    // ---- the listen loop -----------------------------------------------------------------------

    private fun listenSoon(delayMs: Long) {
        restart?.let(main::removeCallbacks)
        val r = Runnable { restart = null; listenNow() }
        restart = r
        main.postDelayed(r, delayMs)
    }

    private fun listenNow() {
        if (!running || speaking || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("Reconnaissance vocale indisponible"); listenSoon(RETRY_ERROR_MS); return
        }
        val r = recognizer ?: build() ?: run { fail("Reconnaissance vocale indisponible"); listenSoon(RETRY_ERROR_MS); return }
        listening = true; ready = false; heard = false; lastPartial = ""
        try {
            r.startListening(recognizeIntent())
        } catch (e: Exception) {
            listening = false
            rebuild()
            listenSoon(RESTART_BENIGN_MS)
        }
    }

    private fun build(): SpeechRecognizer? {
        val r = try {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) { return null }
        r.setRecognitionListener(Listener(epoch.get()))
        recognizer = r
        return r
    }

    /** The service went away (error 11) or the client is confused: drop the instance, the next cycle builds a fresh one. */
    private fun rebuild() {
        epoch.incrementAndGet()
        recognizer?.destroy(); recognizer = null
    }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-CA")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
    }

    private fun speechStarted() {
        if (heard) return
        heard = true
        bus.emit(Signal.SpeechStart)
    }

    /** The cycle is over: settle the meter and give the Mind closure when nothing final came. */
    private fun endCycle(finalDone: Boolean) {
        listening = false
        rmsLevel = 0f
        if (!finalDone && (ready || heard)) bus.emit(Signal.SpeechIdle)
        ready = false; heard = false; lastPartial = ""
    }

    private fun fail(message: String) {
        val now = SystemClock.uptimeMillis()
        if (message == lastFail && now - lastFailAt < FAIL_REPEAT_MS) return
        lastFail = message; lastFailAt = now
        bus.emit(Signal.Failed(message))
    }

    private fun message(error: Int) = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission micro refusée"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Reconnaissance : réseau"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Télécharge le pack vocal Français (Canada) hors ligne"
        SpeechRecognizer.ERROR_AUDIO -> "Reconnaissance : micro indisponible"
        SpeechRecognizer.ERROR_SERVER -> "Reconnaissance : serveur"
        else -> "Reconnaissance vocale : erreur $error"
    }

    /** Callbacks arrive on the main looper. Anything from a stale instance or a closed cycle is ignored. */
    private inner class Listener(private val myEpoch: Int) : RecognitionListener {
        private fun live() = running && myEpoch == epoch.get()

        override fun onReadyForSpeech(params: Bundle?) { if (live() && listening) ready = true }

        override fun onBeginningOfSpeech() { if (live() && listening) speechStarted() }

        override fun onRmsChanged(rmsdB: Float) {
            if (!live() || !listening) return
            rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            rmsAt = SystemClock.uptimeMillis()
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() { if (live()) rmsLevel = 0f }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live() || !listening) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty() || text == lastPartial) return
            lastPartial = text
            speechStarted()
            bus.emit(Signal.SpeechPartial(text))
        }

        override fun onResults(results: Bundle?) {
            if (!live() || !listening) return
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (best.isEmpty()) {
                endCycle(finalDone = false)
            } else {
                speechStarted()
                endCycle(finalDone = true)
                bus.emit(Signal.SpeechFinal(best))
            }
            listenSoon(RESTART_RESULT_MS)
        }

        override fun onError(error: Int) {
            if (!live() || !listening) return
            endCycle(finalDone = false)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSoon(RESTART_RESULT_MS)
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT -> { rebuild(); listenSoon(RESTART_BENIGN_MS) }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> listenSoon(RESTART_BENIGN_MS)
                else -> { fail(message(error)); listenSoon(RETRY_ERROR_MS) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/**
 * Turns the recognizer's coarse RMS into the Level the scene breathes with: an attack/release
 * envelope on amp and 8 plausible bands shaped from it (voice energy sits low, the top flickers).
 * Runs on the ticker thread only.
 */
private class MicMeter {
    var amp = 0f
        private set
    private val bands = FloatArray(BANDS)

    fun step(target: Float, nowMs: Long) {
        amp += (target - amp) * (if (target > amp) ATTACK else RELEASE)
        if (amp < 1e-3f) amp = 0f
        val t = nowMs / 1000.0
        for (i in 0 until BANDS) {
            val v = (amp * (0.3 + 0.7 * abs(sin(t * (2.0 + i * 0.37) + i))) * (1.0 - i / 12.0)).toFloat().coerceIn(0f, 1f)
            bands[i] += (v - bands[i]) * (if (v > bands[i]) 0.5f else 0.1f)
        }
    }

    fun snapshot() = Signal.Level(amp, bands.copyOf(), 0f)
}

private const val BANDS = 8
private const val TICK_MS = 33L
private const val RMS_STALE_MS = 300L
private const val ATTACK = 0.55f
private const val RELEASE = 0.06f
private const val SILENCE_MS = 1300L
private const val RESTART_RESULT_MS = 250L
private const val RESTART_BENIGN_MS = 300L
private const val RETRY_ERROR_MS = 1500L
private const val REOPEN_AFTER_VOICE_MS = 150L
private const val FAIL_REPEAT_MS = 10_000L
