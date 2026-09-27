package com.dany.presence.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Second-order IIR filter (RBJ audio-EQ cookbook), transposed direct form II.
 * Band-pass is the constant 0 dB peak-gain variant, i.e. the Web Audio `BiquadFilterNode` the mockup relies on.
 * Allocation-free after construction; `process` runs once per sample.
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    /** Band-pass centred on [freq] Hz with quality factor [q]. */
    fun bandPass(sampleRate: Int, freq: Double, q: Double) {
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        set(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * cos(w0), 1.0 - alpha)
    }

    /** Low-pass with cutoff [freq] Hz and resonance [q] (0.707 = flat). */
    fun lowPass(sampleRate: Int, freq: Double, q: Double = 0.7071) {
        val w0 = 2.0 * PI * freq / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        set((1.0 - c) / 2.0, 1.0 - c, (1.0 - c) / 2.0, 1.0 + alpha, -2.0 * c, 1.0 - alpha)
    }

    private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        this.b0 = b0 / a0
        this.b1 = b1 / a0
        this.b2 = b2 / a0
        this.a1 = a1 / a0
        this.a2 = a2 / a0
    }

    /** Clears the delay line; call before reusing the filter for a new sound. */
    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}
