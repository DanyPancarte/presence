package com.dany.presence.sound

import com.dany.presence.core.Prosody
import kotlin.math.sin
import kotlin.random.Random

/**
 * The voice that never says a word: 2–6 syllables shaped by a prosody contour, ported from the
 * mockup's `speak()`. Every phrase differs a little (base pitch, vowels, syllable lengths, drift).
 */
object WordlessVoice {
    /** Formant pairs (F1, F2 in Hz): a, i, e, o, u, ə — the mockup's `VOW`. */
    val VOW: Array<DoubleArray> = arrayOf(
        doubleArrayOf(730.0, 1090.0),
        doubleArrayOf(270.0, 2290.0),
        doubleArrayOf(530.0, 1840.0),
        doubleArrayOf(570.0, 840.0),
        doubleArrayOf(300.0, 870.0),
        doubleArrayOf(440.0, 1020.0),
    )

    /** Band-pass centres for the onset consonant: a breath, a soft "t", a soft "s". */
    private val CONSONANTS = doubleArrayOf(1800.0, 2600.0, 3400.0)

    /** A prosody: nominal syllable count, nominal syllable length, level, roughness and the pitch contour (× base). */
    class Plan(val count: Int, val sylDur: Double, val vol: Double, val rough: Boolean, val contour: (Int) -> Double)

    /** The mockup's `P` table, plus NOTED (two short falling syllables). */
    fun plan(prosody: Prosody): Plan = when (prosody) {
        Prosody.CONFIRM -> Plan(3, 0.16, 0.09, false) { i -> 1.25 - i * 0.14 }
        Prosody.QUESTION -> Plan(4, 0.15, 0.09, false) { i -> 0.95 + i * 0.13 }
        Prosody.ALERT -> Plan(5, 0.14, 0.12, true) { i -> 0.55 + (i % 2) * 0.08 }
        Prosody.THINK -> Plan(6, 0.09, 0.05, false) { i -> 0.85 + 0.05 * sin(i.toDouble()) }
        Prosody.HELLO -> Plan(2, 0.20, 0.09, false) { i -> 1.0 + i * 0.35 }
        Prosody.NOTED -> Plan(2, 0.11, 0.09, false) { i -> 1.15 - i * 0.2 }
    }

    /** Syllable count: the plan's, nudged by the text's length (short lines lose one, long lines gain one), clamped 2..6. */
    fun syllables(plan: Plan, textLength: Int): Int {
        val shift = when {
            textLength <= 0 -> 0
            textLength < 15 -> -1
            textLength > 60 -> 1
            else -> 0
        }
        return (plan.count + shift).coerceIn(2, 6)
    }

    /**
     * Schedules a phrase on [synth], starting [at] seconds from now. Returns the phrase's duration in
     * seconds (from [at]), so the caller knows when the voice falls silent.
     */
    fun speak(synth: Synth, prosody: Prosody, textLength: Int, rng: Random, at: Double = 0.02): Double {
        val plan = plan(prosody)
        val n = syllables(plan, textLength)
        val base = 200.0 + rng.nextDouble() * 30.0
        var t = at
        for (i in 0 until n) {
            val dur = plan.sylDur * (0.8 + rng.nextDouble() * 0.5)
            val f0 = base * plan.contour(i)
            val f0End = f0 * (1.0 + (rng.nextDouble() - 0.5) * 0.06)
            val vowel = VOW[rng.nextInt(VOW.size)]
            val consonant = CONSONANTS[rng.nextInt(CONSONANTS.size)]
            synth.syllable(t, f0, f0End, dur, vowel, plan.vol, plan.rough, consonant, rng.nextInt())
            t += dur + 0.03
        }
        return t - at
    }
}
