package com.dany.presence.sound

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.dany.presence.core.Bus
import com.dany.presence.core.Prosody
import com.dany.presence.core.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.random.Random

/**
 * The sound of Présence. A [Synth] streamed through AudioTrack on its own thread (48 kHz, mono,
 * float, ~20 ms blocks, USAGE_ASSISTANT so Bluetooth earbuds get it), driven by the bus:
 * the bed drone, the mockup's SFX, one signature per module, the alert, and the wordless voice.
 * Emits [Signal.Speaking] around every phrase so the ears close the mic meanwhile.
 */
class SoundSystem(context: Context, private val bus: Bus, private val scope: CoroutineScope) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val synth = Synth(SAMPLE_RATE)
    private val rng = Random.Default

    @Volatile private var thread: Thread? = null
    @Volatile private var stopping = false
    private var collector: Job? = null
    private var speakJob: Job? = null
    /** Audio-clock sample at which the voice falls silent. */
    @Volatile private var speakUntil = 0L
    /** The voice waits for a signature or an alert to land first (300 ms, as in the mockup). */
    @Volatile private var voiceHoldUntil = 0L

    /** Mute switch, persisted as `sound_on`. Muted: no SFX, no voice, the bed fades out, the track pauses once silent. */
    @Volatile var muted: Boolean = !prefs.getBoolean(KEY_SOUND_ON, true)
        set(value) {
            if (field == value) return
            field = value
            prefs.edit().putBoolean(KEY_SOUND_ON, !value).apply()
            if (thread != null && !stopping) synth.bed(!value)
        }

    /** Master gain 0..1. */
    var masterGain: Float
        get() = synth.masterGain
        set(value) { synth.masterGain = value.coerceIn(0f, 1f) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == KEY_SOUND_ON) muted = !p.getBoolean(KEY_SOUND_ON, true)
    }

    fun start() {
        thread?.let { old -> if (old.isAlive) old.join(1200) }
        stopping = false
        synth.bed(!muted)
        thread = Thread(::loop, "presence-sound").also { it.start() }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        collector = scope.launch { bus.signals.collect { on(it) } }
    }

    /** Fades the bed out and lets the thread drain (at most ~1.5 s), without blocking the caller. */
    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        collector?.cancel()
        collector = null
        val speaking = synchronized(this) {
            val j = speakJob
            speakJob = null
            j?.cancel()
            j != null
        }
        if (speaking) bus.emit(Signal.Speaking(false))
        synth.bed(false)
        stopping = true
    }

    private fun on(s: Signal) {
        if (muted) return
        when (s) {
            is Signal.Captured -> Sfx.tick(synth, rng)
            is Signal.SpeechStart -> Sfx.listen(synth)
            is Signal.Thinking -> {
                Sfx.scan(synth)
                say(Prosody.THINK, "")
            }
            is Signal.Executed -> {
                Sfx.signature(synth, s.module)
                holdVoice()
            }
            is Signal.Alert -> {
                Sfx.alert(synth)
                holdVoice()
            }
            is Signal.Said -> say(s.prosody, s.text)
            is Signal.Proactive -> say(s.prosody, s.text)
            else -> Unit
        }
    }

    private fun holdVoice() {
        voiceHoldUntil = synth.now + (0.3 * SAMPLE_RATE).toLong()
    }

    /** Schedules a phrase and keeps [Signal.Speaking] true until the last scheduled phrase ends. */
    private fun say(prosody: Prosody, text: String) {
        val now = synth.now
        val at = max(0.02, (voiceHoldUntil - now).toDouble() / SAMPLE_RATE)
        val dur = WordlessVoice.speak(synth, prosody, text.length, rng, at)
        val end = now + ((at + dur + 0.05) * SAMPLE_RATE).toLong()
        synchronized(this) {
            if (end > speakUntil) speakUntil = end
            if (speakJob != null) return
            speakJob = scope.launch {
                while (true) {
                    val left = speakUntil - synth.now
                    if (left > 0) {
                        delay(max(10L, left * 1000L / SAMPLE_RATE))
                        continue
                    }
                    val done = synchronized(this@SoundSystem) {
                        if (speakUntil > synth.now) false else { speakJob = null; true }
                    }
                    if (done) break
                }
                bus.emit(Signal.Speaking(false))
            }
        }
        bus.emit(Signal.Speaking(true))
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = try {
            buildTrack()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack unavailable", e)
            thread = null
            return
        }
        val block = FloatArray(BLOCK)
        var playing = false
        var drain = 0
        try {
            while (true) {
                if (stopping) {
                    if (synth.isSilent() || ++drain > DRAIN_BLOCKS) break
                } else if (muted && synth.isSilent()) {
                    if (playing) {
                        track.pause()
                        track.flush()
                        playing = false
                    }
                    LockSupport.parkNanos(50_000_000L)
                    continue
                }
                if (!playing) {
                    track.play()
                    playing = true
                }
                synth.render(block, BLOCK)
                if (track.write(block, 0, BLOCK, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "sound loop ended", e)
        } finally {
            synth.cut()
            runCatching { track.stop() }
            track.release()
            thread = null
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBytes, BLOCK * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    companion object {
        private const val TAG = "SoundSystem"
        const val PREFS = "presence"
        const val KEY_SOUND_ON = "sound_on"
        const val SAMPLE_RATE = 48_000
        /** 20 ms blocks. */
        const val BLOCK = 960
        private const val DRAIN_BLOCKS = 75
    }
}
