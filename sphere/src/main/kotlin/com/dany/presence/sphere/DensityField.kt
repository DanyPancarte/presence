package com.dany.presence.sphere

import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln

/**
 * "Night map" density on the unit sphere: a handful of metropolises of wildly different sizes,
 * continents vs dark oceans, and fine-grained texture. Output in [0, 1].
 */
class DensityField(seed: Long, cityCount: Int = 46) {
    class City(val c: V3, val sigma: Float, val weight: Float, val region: Float)

    private val noise = Noise3(seed * 31 + 7)
    val cities: List<City>

    init {
        val r = Rng(seed * 7919 + 3)
        cities = List(cityCount) {
            // Log-normal sizes: a few giants, many towns.
            val sigma = exp(ln(0.07f) + r.gauss() * 0.55f).coerceIn(0.025f, 0.34f)
            City(r.unitVector(), sigma, r.f(0.45f, 1f), r.f())
        }
    }

    fun continent(p: V3): Float = smoothstep(-0.18f, 0.22f, noise.fbm(p, 1.25f, 3))

    fun at(p: V3): Float {
        var sum = 0f
        for (c in cities) {
            val d = 1f - (p dot c.c) // ~ angle^2 / 2 for small angles
            val s2 = c.sigma * c.sigma
            if (d < s2 * 8f) sum += c.weight * exp(-d / s2)
        }
        val land = continent(p)
        val grain = 0.5f + 0.5f * noise.fbm(p, 7f, 3)
        val v = sum * (0.2f + 0.8f * land) * (0.55f + 0.9f * grain) + 0.12f * land * grain
        return (1f - exp(-v * 1.7f)).coerceIn(0f, 1f)
    }

    /** Stable per-cluster value: the region of the closest city. */
    fun region(p: V3): Float {
        var best = -2f
        var reg = 0f
        for (c in cities) {
            val d = (p dot c.c) + c.sigma // bigger cities win ties
            if (d > best) { best = d; reg = c.region }
        }
        return reg
    }

    fun angle(a: V3, b: V3) = acos((a dot b).coerceIn(-1f, 1f))
}
