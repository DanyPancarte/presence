package com.dany.presence.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The voice of Présence: Google's TTS synthesised to a file, then run through a radio chain
 * (band-pass 300–3400 Hz, soft saturation, subtle bit-crush, hiss, squelch clicks) and played
 * with AudioTrack. Adds ~150 ms before the first word; that's the price of the character.
 */
class RadioVoice(private val context: Context, private val tts: () -> TextToSpeech?) {
    @Volatile private var track: AudioTrack? = null
    /** Live output level 0..1, for the hologram while it speaks. */
    @Volatile var level = 0f
        private set

    fun speak(text: String, onDone: () -> Unit) {
        val t = tts() ?: return onDone()
        val file = File(context.cacheDir, "voice_${System.nanoTime()}.wav")
        val id = "r${file.name}"
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) Thread { play(file, onDone) }.start() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) { file.delete(); onDone() } }
        })
        if (t.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) onDone()
    }

    fun stop() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        level = 0f
    }

    private fun play(file: File, onDone: () -> Unit) {
        val (rate, pcm) = try { readWav(file) } catch (e: Exception) { file.delete(); onDone(); return }
        file.delete()
        val out = process(pcm, rate)
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(out.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        stop()
        track = at
        at.write(out, 0, out.size)
        at.play()
        // Level meter: walk the buffer in step with playback.
        val chunk = rate / 30
        var i = 0
        while (i < out.size && track === at) {
            var s = 0f
            val end = minOf(out.size, i + chunk)
            for (k in i until end) s += abs(out[k].toFloat())
            level = ((s / (end - i)) / 6000f).coerceIn(0f, 1f)
            i = end
            Thread.sleep(33)
        }
        level = 0f
        if (track === at) { runCatching { at.release() }; track = null; onDone() }
    }

    /** 16-bit PCM WAV (what synthesizeToFile writes). */
    private fun readWav(f: File): Pair<Int, ShortArray> {
        RandomAccessFile(f, "r").use { r ->
            val hdr = ByteArray(12); r.readFully(hdr)
            var rate = 24000; var data: ShortArray? = null
            while (data == null) {
                val ch = ByteArray(8); r.readFully(ch)
                val bb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                val id = String(ch, 0, 4); val size = bb.getInt(4)
                when (id) {
                    "fmt " -> { val fm = ByteArray(size); r.readFully(fm); rate = ByteBuffer.wrap(fm).order(ByteOrder.LITTLE_ENDIAN).getInt(4) }
                    "data" -> { val raw = ByteArray(size); r.readFully(raw); data = ShortArray(size / 2).also { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) } }
                    else -> r.skipBytes(size)
                }
            }
            return rate to data!!
        }
    }

    private fun process(pcm: ShortArray, rate: Int): ShortArray {
        val n = pcm.size
        val pre = (rate * 0.12).toInt(); val post = (rate * 0.14).toInt()
        val out = ShortArray(pre + n + post)
        val rnd = Random(7)
        // Biquad band-pass (Butterworth-ish), two stages: 300 Hz high-pass, 3.4 kHz low-pass.
        val hp = Biquad.highPass(300f, rate); val lp = Biquad.lowPass(3400f, rate)
        val hp2 = Biquad.highPass(300f, rate); val lp2 = Biquad.lowPass(3400f, rate)
        val drive = 2.2f
        for (i in 0 until n) {
            var x = pcm[i] / 32768f
            x = lp2.run(hp2.run(lp.run(hp.run(x))))
            x = tanh(x * drive) / tanh(drive)                         // saturation
            x = (Math.round(x * 512f) / 512f)                          // subtle crush
            x += (rnd.nextFloat() - 0.5f) * 0.006f                     // hiss
            x += 0.02f * sin(2 * PI.toFloat() * 60f * i / rate) * 0.3f // faint hum
            out[pre + i] = (x.coerceIn(-1f, 1f) * 32000f * 0.9f).toInt().toShort()
        }
        // Squelch open / close: short filtered noise bursts with a click.
        squelch(out, 0, pre, rate, rnd, rising = true)
        squelch(out, pre + n, post, rate, rnd, rising = false)
        return out
    }

    private fun squelch(buf: ShortArray, at: Int, len: Int, rate: Int, rnd: Random, rising: Boolean) {
        val bp = Biquad.lowPass(2500f, rate); val hp = Biquad.highPass(600f, rate)
        for (i in 0 until len) {
            val t = i.toFloat() / len
            val env = if (rising) exp(-(1 - t) * 6f) * (1 - exp(-t * 40f)) else exp(-t * 7f)
            var x = hp.run(bp.run((rnd.nextFloat() - 0.5f) * 2f)) * env * 0.35f
            if ((rising && i < rate / 400) || (!rising && i < rate / 500)) x += 0.6f * (1 - i / (rate / 400f)) // click
            buf[at + i] = (x.coerceIn(-1f, 1f) * 32000f).toInt().toShort()
        }
    }

    private class Biquad(private val b0: Float, private val b1: Float, private val b2: Float, private val a1: Float, private val a2: Float) {
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
        fun run(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        companion object {
            private fun coef(f: Float, rate: Int) = (2 * PI * f / rate).toFloat().let { w -> Triple(sin(w), kotlin.math.cos(w), sin(w) / (2 * (1 / sqrt(2f)))) }
            fun lowPass(f: Float, rate: Int): Biquad { val (s, c, a) = coef(f, rate); val a0 = 1 + a; return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - a) / a0).also { s.hashCode() } }
            fun highPass(f: Float, rate: Int): Biquad { val (s, c, a) = coef(f, rate); val a0 = 1 + a; return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - a) / a0).also { s.hashCode() } }
        }
    }
}
