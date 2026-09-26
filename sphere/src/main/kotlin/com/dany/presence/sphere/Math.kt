package com.dany.presence.sphere

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class V3(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 {
        val l = length()
        return if (l < 1e-9f) V3(0f, 1f, 0f) else V3(x / l, y / l, z / l)
    }

    companion object {
        val ZERO = V3(0f, 0f, 0f)
    }
}

/** Rotate tangent heading [h] around surface normal [n] by [a] radians. */
fun rotateAround(h: V3, n: V3, a: Float): V3 = h * cos(a) + (n cross h) * sin(a)

/** Project [v] on the tangent plane of unit normal [n]. */
fun tangent(v: V3, n: V3): V3 = (v - n * (v dot n)).normalized()

/** Any unit vector perpendicular to [n]. */
fun anyPerp(n: V3): V3 =
    if (abs(n.y) < 0.9f) tangent(V3(0f, 1f, 0f), n) else tangent(V3(1f, 0f, 0f), n)

/** xorshift64* — deterministic, fast, good enough for procedural geometry. */
class Rng(seed: Long) {
    private var s: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextLong(): Long {
        var x = s
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        s = x
        return x * 2685821657736338717L
    }

    fun f(): Float = ((nextLong() ushr 40).toFloat() / (1L shl 24).toFloat())
    fun f(a: Float, b: Float) = a + (b - a) * f()
    fun i(n: Int): Int = ((nextLong() ushr 33) % n).toInt()
    fun chance(p: Float) = f() < p
    fun sign() = if (f() < 0.5f) -1f else 1f
    fun gauss(): Float {
        // Irwin–Hall approximation, plenty for jitter.
        return (f() + f() + f() + f() + f() + f() - 3f) * 1.41f
    }

    fun unitVector(): V3 {
        while (true) {
            val v = V3(f(-1f, 1f), f(-1f, 1f), f(-1f, 1f))
            val l = v.length()
            if (l in 0.05f..1f) return v * (1f / l)
        }
    }
}

/** Seeded 3D gradient noise (Perlin improved) + fbm. Output roughly in [-1, 1]. */
class Noise3(seed: Long) {
    private val perm = IntArray(512)

    init {
        val r = Rng(seed)
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = r.i(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)
    private fun grad(h: Int, x: Float, y: Float, z: Float): Float {
        val hh = h and 15
        val u = if (hh < 8) x else y
        val v = if (hh < 4) y else if (hh == 12 || hh == 14) x else z
        return (if (hh and 1 == 0) u else -u) + (if (hh and 2 == 0) v else -v)
    }

    fun at(x: Float, y: Float, z: Float): Float {
        val fx = floor(x); val fy = floor(y); val fz = floor(z)
        val xi = fx.toInt() and 255; val yi = fy.toInt() and 255; val zi = fz.toInt() and 255
        val xf = x - fx; val yf = y - fy; val zf = z - fz
        val u = fade(xf); val v = fade(yf); val w = fade(zf)
        val a = perm[xi] + yi; val aa = perm[a] + zi; val ab = perm[a + 1] + zi
        val b = perm[xi + 1] + yi; val ba = perm[b] + zi; val bb = perm[b + 1] + zi
        return lerp(
            lerp(
                lerp(grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1, yf, zf), u),
                lerp(grad(perm[ab], xf, yf - 1, zf), grad(perm[bb], xf - 1, yf - 1, zf), u), v
            ),
            lerp(
                lerp(grad(perm[aa + 1], xf, yf, zf - 1), grad(perm[ba + 1], xf - 1, yf, zf - 1), u),
                lerp(grad(perm[ab + 1], xf, yf - 1, zf - 1), grad(perm[bb + 1], xf - 1, yf - 1, zf - 1), u), v
            ), w
        )
    }

    fun at(p: V3, freq: Float) = at(p.x * freq + 17.3f, p.y * freq - 4.1f, p.z * freq + 9.7f)

    fun fbm(p: V3, freq: Float, octaves: Int = 4): Float {
        var sum = 0f; var amp = 0.5f; var f = freq
        for (o in 0 until octaves) {
            sum += amp * at(p, f)
            f *= 2.03f; amp *= 0.5f
        }
        return sum * 1.6f
    }
}

fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}
