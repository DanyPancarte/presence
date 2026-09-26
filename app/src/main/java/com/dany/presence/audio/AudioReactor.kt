package com.dany.presence.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dany.presence.render.SphereState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Microphone → the organism. 16 kHz mono, 1024-sample windows (~64 ms), FFT + time-domain pitch.
 * Everything is smoothed with attack/release envelopes so silence is a slow exhale, never a cut.
 *
 * Writes into [SphereState]: amp, bands, tone (spectral centroid), pitch (relative to the speaker's
 * running median), and transient shocks.
 */
class AudioReactor(private val state: SphereState, private val clock: () -> Float) {
    private val rate = 16_000
    private val n = 1024
    private var thread: Thread? = null
    @Volatile private var running = false

    private val window = FloatArray(n) { 0.5f - 0.5f * cos(2.0 * PI * it / (n - 1)).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val mag = FloatArray(n / 2)
    private val prevMag = FloatArray(n / 2)

    private var amp = 0f
    private var noiseFloor = 0.01f
    private val bands = FloatArray(4)
    private var tone = 0f
    private var pitch = 0f
    private var pitchMedian = 160f
    private var lastShock = -10f

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(minBuf, n * 4 * 2))
            } catch (e: Exception) { running = false; return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { running = false; return@Thread }
            rec.startRecording()
            val buf = FloatArray(n)
            while (running) {
                val got = rec.read(buf, 0, n, AudioRecord.READ_BLOCKING)
                if (got > 0) analyse(buf)
            }
            rec.stop(); rec.release()
        }, "audio-reactor").apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread = null }

    private fun analyse(x: FloatArray) {
        // --- loudness ---
        var sum = 0f
        for (v in x) sum += v * v
        val rms = sqrt(sum / n)
        // Adaptive noise floor: room hum never counts as voice.
        noiseFloor = if (rms < noiseFloor) noiseFloor * 0.98f + rms * 0.02f else noiseFloor * 0.9995f + rms * 0.0005f
        val level = ((ln(max(rms, 1e-5f) / max(noiseFloor * 2.5f, 3e-3f))) / 3.5f).coerceIn(0f, 1.2f)
        amp = if (level > amp) amp + (level - amp) * 0.55f else amp + (level - amp) * 0.06f

        // --- spectrum ---
        for (i in 0 until n) { re[i] = x[i] * window[i]; im[i] = 0f }
        fft(re, im)
        var flux = 0f
        var centNum = 0f
        var centDen = 0f
        for (i in 1 until n / 2) {
            val m = sqrt(re[i] * re[i] + im[i] * im[i])
            mag[i] = m
            val d = m - prevMag[i]
            if (d > 0) flux += d
            prevMag[i] = m
            val f = i * rate / n.toFloat()
            if (f in 80f..6000f) { centNum += f * m; centDen += m }
        }
        // 4 bands: bass 80–250, low-mid 250–800, presence 800–2500, air 2500–7000 Hz.
        val edges = floatArrayOf(80f, 250f, 800f, 2500f, 7000f)
        for (b in 0 until 4) {
            var e = 0f; var c = 0
            val i0 = (edges[b] * n / rate).toInt(); val i1 = min((edges[b + 1] * n / rate).toInt(), n / 2 - 1)
            for (i in i0..i1) { e += mag[i]; c++ }
            val v = (ln(1f + e / max(c, 1) * 40f) / 3f * amp).coerceIn(0f, 1f)
            bands[b] = if (v > bands[b]) bands[b] + (v - bands[b]) * 0.5f else bands[b] + (v - bands[b]) * 0.08f
        }
        // Tone = spectral centroid on a log scale: 300 Hz → 0, 3000 Hz → 1.
        val cent = if (centDen > 1e-4f) centNum / centDen else 800f
        val toneTarget = ((ln(cent / 300f) / ln(10f))).coerceIn(0f, 1f)
        tone += (toneTarget - tone) * (if (amp > 0.15f) 0.2f else 0.03f)

        // --- transients: spectral flux over a running average ---
        val nowT = clock()
        fluxAvg = fluxAvg * 0.9f + flux * 0.1f
        if (flux > fluxAvg * 2.2f + 0.5f && amp > 0.2f && nowT - lastShock > 0.18f) {
            lastShock = nowT
            state.shockStart = nowT
            state.shockStrength = (flux / (fluxAvg + 1f)).coerceIn(0.3f, 1f)
        }

        // --- pitch: normalised autocorrelation, 70–500 Hz ---
        if (amp > 0.2f) {
            val p = autocorrPitch(x)
            if (p > 0f) {
                pitchMedian += (p - pitchMedian) * 0.01f
                val rel = (ln(p / pitchMedian) / ln(2f)).coerceIn(-1f, 1f) // ±1 octave
                pitch += (rel - pitch) * 0.3f
            }
        } else pitch *= 0.97f

        state.amp = amp.coerceIn(0f, 1f)
        state.bands = bands.copyOf()
        state.tone = tone
        state.pitch = pitch
    }

    private var fluxAvg = 0f

    private fun autocorrPitch(x: FloatArray): Float {
        val minLag = rate / 500; val maxLag = rate / 70
        var best = 0f; var bestLag = 0
        var e0 = 0f
        for (i in 0 until n - maxLag) e0 += x[i] * x[i]
        if (e0 < 1e-6f) return 0f
        for (lag in minLag..maxLag) {
            var c = 0f; var e1 = 0f
            var i = 0
            while (i < n - maxLag) { c += x[i] * x[i + lag]; e1 += x[i + lag] * x[i + lag]; i += 2 }
            val r = c / sqrt(e0 * e1 + 1e-9f)
            if (r > best) { best = r; bestLag = lag }
        }
        return if (best > 0.6f && bestLag > 0) rate / bestLag.toFloat() else 0f
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    @Suppress("unused")
    private fun soft(x: Float) = 1f - exp(-abs(x))
}
