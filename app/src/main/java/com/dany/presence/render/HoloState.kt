package com.dany.presence.render

import kotlin.math.exp
import kotlin.math.min

/** The five behaviours of the hologram. The hologram IS the interface. */
enum class Mood(val shape: Int, val energy: Float, val glitch: Float) {
    VEILLE(0, 1f, 0f),
    ECOUTE(1, 1.3f, 0f),
    REFLEXION(2, 2.6f, 0.12f),
    REPONSE(3, 0.9f, 0f),
    ALERTE(4, 2.2f, 0.2f),
}

/** Written by UI/audio threads, read by the GL thread. Mirrors the state block of tools/preview/mockup.html. */
class HoloState {
    @Volatile var mood: Mood = Mood.VEILLE
        set(v) {
            if (v.shape != shapeB) {
                shapeA = if (blend >= 0.99f) shapeB else shapeA
                shapeB = v.shape
                blend = 0f
            }
            energyT = v.energy
            glitch = v.glitch
            alertT = if (v == Mood.ALERTE) 1f else 0f
            field = v
        }

    @Volatile var shapeA = 0
    @Volatile var shapeB = 0
    @Volatile var blend = 1f
    @Volatile var energy = 1f
    private var energyT = 1f
    @Volatile var alert = 0f
    private var alertT = 0f
    @Volatile var glitch = 0f

    // Audio (smoothed by the analyser or fed by the recognizer)
    @Volatile var amp = 0f
    @Volatile var bands = FloatArray(24)
    /** Transient pulse (0..1), decays. Drives a short glitch burst. */
    @Volatile var pulse = 0f

    // Touch in box coords (x ∈ ±aspect, y ∈ ±1); far away = no effect
    @Volatile var touchX = 99f
    @Volatile var touchY = 99f

    // Gyroscope parallax
    @Volatile var parallaxX = 0f
    @Volatile var parallaxY = 0f
    @Volatile var parallaxTX = 0f
    @Volatile var parallaxTY = 0f

    fun step(dt: Float) {
        blend = min(1f, blend + dt / 0.9f)
        energy += (energyT - energy) * (1 - exp(-dt * 3f))
        alert += (alertT - alert) * (1 - exp(-dt * 3.6f))
        parallaxX += (parallaxTX - parallaxX) * (1 - exp(-dt * 3f))
        parallaxY += (parallaxTY - parallaxY) * (1 - exp(-dt * 3f))
        pulse *= exp(-dt * 6f)
    }
}
