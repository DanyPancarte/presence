package com.dany.presence.render

import kotlin.math.exp

/** The five behaviours of the sphere. The sphere IS the interface. */
enum class Mood { VEILLE, ECOUTE, REFLEXION, REPONSE, ALERTE }

/**
 * Every animated knob of the sphere. Values are shader uniforms (see filament.vert / composite.frag).
 * tools/preview/renderer.js DEFAULTS mirrors [IDLE]; the live preview page mirrors every state.
 */
data class Look(
    val dilate: Float = 0f,
    val energy: Float = 1f,      // speeds up the turbulence and the vortex
    val twinkle: Float = 1f,
    val sweep: Float = 0f,
    val gain: Float = 0.4f,
    val coreGain: Float = 1f,
    val alert: Float = 0f,
    val bloom: Float = 0.9f,
    val halo: Float = 1f,
    val exposure: Float = 1f,
) {
    companion object {
        val IDLE = Look()
        val LISTEN = Look(dilate = 0.05f, energy = 1.4f, gain = 0.5f, coreGain = 1.3f, halo = 1.25f)
        val THINK = Look(energy = 2.6f, sweep = 1f, twinkle = 0.6f, gain = 0.45f, coreGain = 1.5f)
        val ANSWER = Look(energy = 0.55f, twinkle = 0.35f, gain = 0.55f, coreGain = 1.8f, bloom = 1.1f, halo = 1.35f)
        val ALERT = Look(energy = 2.2f, gain = 0.5f, coreGain = 2f, alert = 1f, bloom = 1.2f, halo = 1.5f)

        fun of(m: Mood) = when (m) {
            Mood.VEILLE -> IDLE
            Mood.ECOUTE -> LISTEN
            Mood.REFLEXION -> THINK
            Mood.REPONSE -> ANSWER
            Mood.ALERTE -> ALERT
        }
    }
}

/** A value that eases toward its target with a critically damped spring (no overshoot, no cuts). */
class Spring(var value: Float, private val halfLife: Float = 0.28f) {
    private var vel = 0f
    var target = value

    fun step(dt: Float) {
        // Critically damped spring, ~0.9 s to settle with the default half-life: ≥ 800 ms morphs.
        val omega = 1.3862944f / halfLife
        val x = value - target
        val e = exp(-omega * dt)
        val tmp = (vel + omega * x) * dt
        vel = (vel - omega * tmp) * e
        value = target + (x + tmp) * e
    }
}

/** Thread-safe(ish) bag of smoothed behaviour values, written by UI/audio, read by the GL thread. */
class SphereState {
    @Volatile var mood: Mood = Mood.VEILLE
        set(v) { field = v; apply(Look.of(v)) }

    val dilate = Spring(0f)
    val energy = Spring(1f)
    val twinkle = Spring(1f)
    val sweep = Spring(0f)
    val gain = Spring(0.4f)
    val coreGain = Spring(1f)
    val alert = Spring(0f)
    val bloom = Spring(0.9f)
    val halo = Spring(1f)
    val exposure = Spring(1f)
    // Module-driven values
    val density = Spring(1f, 0.6f)
    val temp = Spring(0f, 0.6f)

    // Audio (already smoothed by the analyser)
    @Volatile var amp = 0f
    @Volatile var bands = FloatArray(4)
    @Volatile var shockStart = -10f
    @Volatile var shockStrength = 0f

    /** Module zones: 4 floats per zone (dir xyz, cos radius) + 4 params (brightness, blink, cos hole, -). */
    @Volatile var zones = FloatArray(0)
    @Volatile var zoneParams = FloatArray(0)

    // Gyroscope parallax in view units
    @Volatile var parallaxX = 0f
    @Volatile var parallaxY = 0f

    private val all = listOf(dilate, energy, twinkle, sweep, gain, coreGain, alert, bloom, halo, exposure, density, temp)

    private fun apply(l: Look) {
        dilate.target = l.dilate; energy.target = l.energy
        twinkle.target = l.twinkle; sweep.target = l.sweep; gain.target = l.gain
        coreGain.target = l.coreGain; alert.target = l.alert; bloom.target = l.bloom
        halo.target = l.halo; exposure.target = l.exposure
    }

    fun step(dt: Float) = all.forEach { it.step(dt) }
}
