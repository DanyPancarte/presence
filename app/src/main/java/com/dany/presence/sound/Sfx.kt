package com.dany.presence.sound

import com.dany.presence.core.Module
import kotlin.random.Random

/** The instrument's sound effects: the mockup's `SFX`, plus one signature per module. Levels are the mockup's. */
object Sfx {
    /** One word captured: a short quiet tick, 1.2–1.8 kHz. */
    fun tick(s: Synth, rng: Random) = s.tone(1200.0 + rng.nextDouble() * 600.0, 0.05, Wave.SINE, 0.05)

    /** The ears open: a 330 → 660 Hz sweep. */
    fun listen(s: Synth) = s.tone(330.0, 0.5, Wave.SINE, 0.08, slide = 660.0)

    /** The mind scans: a very quiet sawtooth rising 220 → 880 Hz over 1.2 s. */
    fun scan(s: Synth) = s.tone(220.0, 1.2, Wave.SAWTOOTH, 0.03, slide = 880.0)

    /** Something went wrong: two low rough thuds, 80 → 40 Hz. */
    fun alert(s: Synth) {
        s.tone(80.0, 0.6, Wave.SAWTOOTH, 0.14, slide = 40.0)
        s.tone(80.0, 0.6, Wave.SAWTOOTH, 0.12, slide = 40.0, delay = 0.4)
    }

    /** One signature per module, played when an action lands. Returns its length in seconds. */
    fun signature(s: Synth, module: Module): Double = when (module) {
        // The mockup's `done`: two rising triangle notes (C5, G5).
        Module.TACHES -> {
            s.tone(523.25, 0.18, Wave.TRIANGLE, 0.1)
            s.tone(783.99, 0.3, Wave.TRIANGLE, 0.1, delay = 0.09)
            0.39
        }
        // A single bell: a fundamental with a slightly inharmonic, quieter partial.
        Module.NOTES -> {
            s.tone(1046.5, 0.5, Wave.SINE, 0.09)
            s.tone(1046.5 * 2.76, 0.22, Wave.SINE, 0.025)
            0.5
        }
        // Two calm sine notes on the same pitch (E5).
        Module.MEDS -> {
            s.tone(659.26, 0.15, Wave.SINE, 0.09)
            s.tone(659.26, 0.28, Wave.SINE, 0.09, delay = 0.16)
            0.44
        }
        // A low triangle slide, one octave down.
        Module.BUDGET -> {
            s.tone(196.0, 0.5, Wave.TRIANGLE, 0.1, slide = 98.0)
            0.5
        }
        // A soft sine glide down.
        Module.MOOD -> {
            s.tone(880.0, 0.6, Wave.SINE, 0.07, slide = 440.0)
            0.6
        }
        // A double bip, like a watch.
        Module.AGENDA -> {
            s.tone(1318.5, 0.06, Wave.SINE, 0.08)
            s.tone(1318.5, 0.06, Wave.SINE, 0.08, delay = 0.12)
            0.18
        }
        // No module: a single short triangle note.
        Module.AUCUN -> {
            s.tone(523.25, 0.18, Wave.TRIANGLE, 0.08)
            0.18
        }
    }
}
