package com.dany.presence.sound

import com.dany.presence.core.Prosody
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Oscillator shapes, as in the mockup's `tone()`. */
enum class Wave { SINE, TRIANGLE, SAWTOOTH }

/**
 * Pure-Kotlin real-time synthesizer: a fixed pool of [Voice]s mixed into float blocks, plus the bed drone.
 * Scheduling is sample-accurate on the audio clock [now]. Triggers and [render] share one monitor;
 * nothing allocates once the pool exists, so the audio thread never wakes the GC.
 */
class Synth(val sampleRate: Int = 48_000) {
    private val voices = Array(POLYPHONY) { Voice(sampleRate) }

    /** Samples rendered so far: the audio clock every trigger is scheduled against. */
    @Volatile var now = 0L
        private set

    /** Output gain 0..1, applied before the soft limiter. */
    @Volatile var masterGain = 1f

    // The bed: a barely audible 48 Hz sine, faded linearly (2 s in, 0.5 s out in the mockup).
    private var bedPhase = 0.0
    private var bedGain = 0.0
    private var bedTarget = 0.0
    private var bedStep = 0.0
    private val bedInc = BED_HZ / sampleRate

    /** Fades the bed drone in or out. */
    fun bed(on: Boolean, fadeSeconds: Double = if (on) 2.0 else 0.5) {
        synchronized(this) {
            bedTarget = if (on) BED_LEVEL else 0.0
            bedStep = abs(bedTarget - bedGain) / max(1.0, fadeSeconds * sampleRate)
        }
    }

    /**
     * The mockup's `tone(f, dur, type, vol, slide)`: 10 ms exponential attack, exponential decay to
     * silence at [dur], optional exponential frequency slide to [slide] Hz. Starts [delay] s from now.
     */
    fun tone(freq: Double, dur: Double, wave: Wave = Wave.SINE, vol: Double = 0.1, slide: Double = 0.0, delay: Double = 0.0) {
        synchronized(this) {
            free().tone(now + (delay * sampleRate).toLong(), freq, dur, wave, vol, slide)
        }
    }

    /**
     * One wordless syllable, the mockup's `syl()`: sawtooth source through two band-pass formants
     * (Q 6 and 9) plus a sine one octave below, a 30 ms band-passed noise consonant at onset, envelope
     * attack 30 ms / hold to 60 % / exponential release, a small linear pitch drift to [f0End],
     * and for [rough] a 28 Hz vibrato of ±12 % on the source.
     */
    fun syllable(
        delay: Double, f0: Double, f0End: Double, dur: Double, vowel: DoubleArray,
        vol: Double, rough: Boolean, consonantHz: Double, noiseSeed: Int,
    ) {
        synchronized(this) {
            free().syllable(now + (delay * sampleRate).toLong(), f0, f0End, dur, vowel, vol, rough, consonantHz, noiseSeed)
        }
    }

    /** True when nothing is sounding and the bed is fully faded out. */
    fun isSilent(): Boolean {
        synchronized(this) {
            if (bedGain > 0.0 || bedTarget > 0.0) return false
            for (v in voices) if (v.active) return false
            return true
        }
    }

    /** Silences every voice at once (the bed keeps its fade). */
    fun cut() {
        synchronized(this) { for (v in voices) v.active = false }
    }

    /** Renders [n] mono samples into [out] and advances the clock. */
    fun render(out: FloatArray, n: Int) {
        synchronized(this) {
            for (i in 0 until n) {
                if (bedGain != bedTarget) {
                    bedGain = if (bedGain < bedTarget) min(bedTarget, bedGain + bedStep) else max(bedTarget, bedGain - bedStep)
                }
                out[i] = if (bedGain > 0.0) (sin(TWO_PI * bedPhase) * bedGain).toFloat() else 0f
                bedPhase += bedInc
                if (bedPhase >= 1.0) bedPhase -= 1.0
            }
            for (v in voices) if (v.active) v.render(out, n, now)
            val g = masterGain
            for (i in 0 until n) out[i] = limit(out[i] * g)
            now += n
        }
    }

    /** A free voice, or the one that ends soonest. */
    private fun free(): Voice {
        var best = voices[0]
        for (v in voices) {
            if (!v.active) return v
            if (v.endAt < best.endAt) best = v
        }
        return best
    }

    /** Soft knee above 0.8, asymptotic to 1.0: the mix can never clip. */
    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val over = a - 0.8f
        val y = 0.8f + over / (1f + over * 5f)
        return if (x < 0f) -y else y
    }

    companion object {
        const val POLYPHONY = 24
        const val BED_HZ = 48.0
        const val BED_LEVEL = 0.045
        const val TWO_PI = 2.0 * PI

        /**
         * Offline path for tests: renders one wordless phrase of [prosody] with a deterministic [seed]
         * (same seed, same samples) into a mono 48 kHz float buffer, including a 100 ms tail.
         */
        fun renderPhrase(prosody: Prosody, seed: Long, text: String = ""): FloatArray {
            val synth = Synth(48_000)
            val rng = Random(seed)
            val start = 0.02
            val dur = WordlessVoice.speak(synth, prosody, text.length, rng, start)
            val total = ((start + dur + 0.1) * synth.sampleRate).toInt()
            val out = FloatArray(total)
            val block = FloatArray(960)
            var off = 0
            while (off < total) {
                val n = min(block.size, total - off)
                synth.render(block, n)
                System.arraycopy(block, 0, out, off, n)
                off += n
            }
            return out
        }
    }
}

/**
 * One sound in the pool: an oscillator with an exponential envelope and a frequency slide, optionally
 * with a sub-octave sine, two formant band-passes, a vibrato LFO and a noise consonant.
 * All state is primitive; [render] adds into the block without allocating.
 */
class Voice(private val sampleRate: Int) {
    var active = false
    var startAt = 0L
    var endAt = 0L

    private var wave = SINE
    private var phase = 0.0
    private var freq = 0.0
    private var slideExp = true
    private var slideMul = 1.0
    private var slideAdd = 0.0

    private var durS = 0
    private var attackS = 0
    private var holdS = 0
    private var vol = 0.0
    private var gain = 0.0
    private var attackMul = 1.0
    private var releaseMul = 1.0

    private var sub = false
    private var subPhase = 0.0
    private var subInc = 0.0

    private var formant = false
    private val f1 = Biquad()
    private val f2 = Biquad()

    private var rough = false
    private var lfoPhase = 0.0
    private var lfoInc = 0.0
    private var lfoDepth = 0.0

    private var noiseS = 0
    private var noiseAmp = 0.0
    private val nf = Biquad()
    private var rng = 0x9E3779B9.toInt()

    fun tone(startAt: Long, f: Double, dur: Double, wave: Wave, vol: Double, slide: Double) {
        begin(startAt, dur, vol, attack = 0.01, holdFraction = 0.0)
        this.wave = wave.ordinal
        freq = f
        slideExp = true
        slideMul = if (slide > 0.0) (slide / f).pow(1.0 / durS) else 1.0
        active = true
    }

    fun syllable(
        startAt: Long, f0: Double, f0End: Double, dur: Double, vowel: DoubleArray,
        vol: Double, rough: Boolean, consonantHz: Double, noiseSeed: Int,
    ) {
        begin(startAt, dur, vol, attack = 0.03, holdFraction = 0.6)
        wave = SAWTOOTH
        freq = f0
        slideExp = false
        slideAdd = (f0End - f0) / durS
        sub = true
        subInc = (f0 / 2.0) / sampleRate
        formant = true
        f1.bandPass(sampleRate, vowel[0], 6.0)
        f2.bandPass(sampleRate, vowel[1], 9.0)
        this.rough = rough
        if (rough) {
            lfoInc = 28.0 / sampleRate
            lfoDepth = f0 * 0.12
        }
        noiseS = (0.03 * sampleRate).toInt()
        noiseAmp = vol * 0.6
        nf.bandPass(sampleRate, consonantHz, 1.2)
        rng = if (noiseSeed == 0) 0x9E3779B9.toInt() else noiseSeed
        active = true
    }

    /** Common reset + envelope: exponential ramp 0.0001 → vol over [attack] s, hold, exponential release to 0.0001 at dur. */
    private fun begin(startAt: Long, dur: Double, vol: Double, attack: Double, holdFraction: Double) {
        this.startAt = startAt
        durS = max(4, (dur * sampleRate).toInt())
        endAt = startAt + durS
        attackS = min(durS - 2, max(1, (attack * sampleRate).toInt()))
        holdS = max(attackS, min(durS - 1, (durS * holdFraction).toInt()))
        this.vol = vol
        gain = FLOOR
        attackMul = (vol / FLOOR).pow(1.0 / attackS)
        releaseMul = (FLOOR / vol).pow(1.0 / (durS - holdS))
        phase = 0.0
        subPhase = 0.0
        lfoPhase = 0.0
        sub = false
        formant = false
        rough = false
        noiseS = 0
        slideMul = 1.0
        slideAdd = 0.0
        f1.reset()
        f2.reset()
        nf.reset()
    }

    /** Adds this voice's samples for the block starting at [blockStart] (audio clock). */
    fun render(out: FloatArray, n: Int, blockStart: Long) {
        var i = if (startAt > blockStart) (startAt - blockStart).toInt() else 0
        if (i >= n) return
        var t = (blockStart + i - startAt).toInt()
        while (i < n) {
            if (t >= durS) {
                active = false
                return
            }
            // Envelope.
            if (t < attackS) gain *= attackMul else if (t < holdS) gain = vol else gain *= releaseMul

            // Frequency, with vibrato on rough voices.
            var f = freq
            if (rough) {
                f += lfoDepth * sin(Synth.TWO_PI * lfoPhase)
                lfoPhase += lfoInc
                if (lfoPhase >= 1.0) lfoPhase -= 1.0
            }
            val dt = f / sampleRate
            phase += dt
            if (phase >= 1.0) phase -= 1.0

            var s = when (wave) {
                SINE -> sin(Synth.TWO_PI * phase)
                TRIANGLE -> if (phase < 0.5) 4.0 * phase - 1.0 else 3.0 - 4.0 * phase
                else -> 2.0 * phase - 1.0 - polyBlep(phase, dt)
            }
            if (formant) s = f1.process(s) + f2.process(s)
            if (sub) {
                subPhase += subInc
                if (subPhase >= 1.0) subPhase -= 1.0
                s += sin(Synth.TWO_PI * subPhase)
            }
            var y = s * gain
            if (t < noiseS) {
                // Smooth hump over the consonant window, independent of the vowel's slow attack.
                val u = t.toDouble() / noiseS
                y += nf.process(noise()) * noiseAmp * 4.0 * u * (1.0 - u)
            }
            out[i] += y.toFloat()

            if (slideExp) freq *= slideMul else freq += slideAdd
            i++
            t++
        }
    }

    /** xorshift32 white noise in -1..1. */
    private fun noise(): Double {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return x / 2147483648.0
    }

    /** Band-limits the sawtooth's discontinuity (PolyBLEP), like a Web Audio oscillator. */
    private fun polyBlep(t: Double, dt: Double): Double {
        if (dt <= 0.0) return 0.0
        if (t < dt) {
            val x = t / dt
            return x + x - x * x - 1.0
        }
        if (t > 1.0 - dt) {
            val x = (t - 1.0) / dt
            return x * x + x + x + 1.0
        }
        return 0.0
    }

    private companion object {
        const val SINE = 0
        const val TRIANGLE = 1
        const val SAWTOOTH = 2
        const val FLOOR = 0.0001
    }
}
