package com.dany.presence.sphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the city network on the shell is grown. */
enum class FilamentStyle(val label: String) {
    ROUTES("A · Réseau routier"),
    CIRCUIT("B · Circuit imprimé"),
    LICHTENBERG("C · Lichtenberg"),
    METROPOLE("D · Métropole"),
}

/**
 * Procedural megalopolis-in-a-sphere. Everything is expressed in model space with the shell at
 * radius 1. Deterministic for a given (style, seed).
 */
class SphereGenerator(
    private val style: FilamentStyle,
    private val seed: Long = 20_260_926L,
    private val budget: Int = 170_000,
) {
    private val rng = Rng(seed)
    private val field = DensityField(seed)
    private val out = SegmentSink(budget + 20_000)
    private var walkerId = 0

    fun generate(): SphereGeometry {
        val shellBudget = (budget * 0.66f).toInt()
        val innerBudget = (budget * 0.16f).toInt()
        val midBudget = (budget * 0.06f).toInt()

        grow(layer = 1f, kind = Kind.SHELL, target = shellBudget, bright = 1f, style = style)
        grow(layer = 0.74f, kind = Kind.INNER, target = innerBudget, bright = 0.75f, style = style)
        grow(layer = 0.5f, kind = Kind.INNER, target = midBudget, bright = 0.6f, style = style)
        conduits(260)
        cityDots(7_000)
        core()
        return out.build()
    }

    // ---------------------------------------------------------------------------------------
    // Layers

    private fun grow(layer: Float, kind: Float, target: Int, bright: Float, style: FilamentStyle) {
        val start = out.count
        val hash = SegmentHash(0.025f)
        val ctx = Layer(layer, kind, bright, hash, start + target)
        when (style) {
            FilamentStyle.ROUTES -> routes(ctx, highways = true)
            FilamentStyle.CIRCUIT -> circuit(ctx)
            FilamentStyle.LICHTENBERG -> lichtenberg(ctx)
            FilamentStyle.METROPOLE -> metropole(ctx)
        }
    }

    private inner class Layer(
        val radius: Float,
        val kind: Float,
        val bright: Float,
        val hash: SegmentHash,
        val limit: Int,
    ) {
        val full get() = out.count >= limit

        /** Emit a surface segment between unit directions a and b. */
        fun emit(
            a: V3, b: V3, d0: Float, d1: Float, bright: Float, heat: Float, width: Float,
            depth: Float, walker: Int, collide: Boolean = true,
        ) {
            val pa = a * radius
            val pb = b * radius
            out.add(
                pa, pb, rng.f(), kind, d0, d1, bright * this.bright, heat,
                width * (0.55f + 0.45f * radius), field.region(a), radius, depth,
            )
            if (collide) hash.insert(a, b, walker)
        }
    }

    private fun sampleByDensity(power: Float = 1.6f): V3 {
        while (true) {
            val p = rng.unitVector()
            if (rng.f() < field.at(p).pow(power)) return p
        }
    }

    // ---------------------------------------------------------------------------------------
    // A · Road network: walkers with angular turns, T-junctions, branches and gaps.

    private fun routes(l: Layer, highways: Boolean) {
        if (highways && l.radius > 0.9f) highways(l)
        val queue = ArrayDeque<Walker>()
        var guard = 0
        while (!l.full && guard++ < 200_000) {
            if (queue.isEmpty()) {
                val p = sampleByDensity()
                val base = anyPerp(p)
                val grid = rng.f(0f, PI.toFloat())
                val n = 2 + rng.i(3)
                for (k in 0 until n) {
                    val h = rotateAround(base, p, grid + k * (PI.toFloat() / 2f))
                    queue.addLast(Walker(p, h, 0f, 0, walkerId++))
                }
            }
            roadWalk(l, queue.removeFirst(), queue)
        }
    }

    private class Walker(val p: V3, val h: V3, val dist: Float, val depth: Int, val id: Int)

    private fun roadWalk(l: Layer, w0: Walker, queue: ArrayDeque<Walker>) {
        var p = w0.p
        var h = w0.h
        var dist = w0.dist
        val maxSteps = 20 + rng.i(90)
        for (step in 0 until maxSteps) {
            if (l.full) return
            val dens = field.at(p)
            // Deserts kill walkers, cities keep them alive.
            if (rng.chance(0.006f + (1f - dens).pow(2f) * 0.08f)) return
            val len = rng.f(0.006f, 0.017f) * (1.25f - dens * 0.65f)
            when {
                rng.chance(0.22f) -> h = rotateAround(h, p, rng.sign() * rng.f(0.12f, 0.55f))
                rng.chance(0.045f) -> h = rotateAround(h, p, rng.sign() * PI.toFloat() / 2f)
            }
            val q = (p + h * len).normalized()
            val hit = l.hash.hit(q, len * 0.7f, w0.id)
            val end = hit?.normalized() ?: q
            val d1 = dist + len
            if (!rng.chance(0.07f)) {
                val b = 0.35f + dens * 0.95f + rng.gauss() * 0.12f
                l.emit(p, end, dist, d1, max(0.08f, b), 0.25f + dens * 0.5f, rng.f(1.0f, 1.5f), w0.depth.toFloat(), w0.id)
            }
            if (hit != null) return // T-junction
            h = tangent(h, end)
            p = end
            dist = d1
            if (rng.chance(0.03f + dens * 0.07f) && w0.depth < 7) {
                val a = if (rng.chance(0.7f)) PI.toFloat() / 2f else PI.toFloat() / 4f
                queue.addLast(Walker(p, rotateAround(h, p, rng.sign() * a), dist, w0.depth + 1, walkerId++))
            }
        }
    }

    /** Jagged bright arteries between nearby big cities. */
    private fun highways(l: Layer) {
        val big = field.cities.filter { it.sigma > 0.06f }
        for (a in big) for (b in big) {
            if (a === b || a.c.x > b.c.x) continue
            val ang = field.angle(a.c, b.c)
            if (ang > 0.95f || ang < 0.15f || !rng.chance(0.55f)) continue
            val id = walkerId++
            val steps = (ang / 0.011f).roundToInt()
            var prev = a.c
            var off = 0f
            var dist = 0f
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val base = slerp(a.c, b.c, t)
                val side = (b.c - a.c).let { tangent(it, base) } cross base
                // Quantized lateral drift: gives the angular, "surveyed" look.
                if (rng.chance(0.18f)) off += rng.sign() * 0.004f
                off *= 0.97f
                val q = (base + side * off).normalized()
                val len = (q - prev).length()
                if (!rng.chance(0.05f)) {
                    l.emit(prev, q, dist, dist + len, 1.25f, 0.75f, 1.7f, 0f, id)
                }
                dist += len
                prev = q
            }
        }
    }

    private fun slerp(a: V3, b: V3, t: Float): V3 = (a * (1f - t) + b * t).normalized()

    // ---------------------------------------------------------------------------------------
    // B · Printed circuit: traces snapped to a cube-face frame, 45°/90° bends, buses and pads.

    private fun faceFrame(p: V3): Pair<V3, V3> {
        val ax = abs(p.x); val ay = abs(p.y); val az = abs(p.z)
        val u = when {
            ax >= ay && ax >= az -> V3(0f, 1f, 0f)
            ay >= az -> V3(0f, 0f, 1f)
            else -> V3(1f, 0f, 0f)
        }
        val e1 = tangent(u, p)
        return e1 to (p cross e1)
    }

    private fun snap(p: V3, h: V3, dir: Int): V3 {
        val (e1, e2) = faceFrame(p)
        val a = dir * (PI.toFloat() / 4f)
        return (e1 * cos(a) + e2 * sin(a)).normalized().let { if ((it dot h) < -0.99f) h else it }
    }

    private fun circuit(l: Layer) {
        var guard = 0
        while (!l.full && guard++ < 60_000) {
            val p = sampleByDensity(1.3f)
            val dens = field.at(p)
            val lanes = if (rng.chance(0.45f + dens * 0.3f)) 2 + rng.i(5) else 1
            val spacing = rng.f(0.0035f, 0.0055f)
            trace(l, p, rng.i(8), lanes, spacing, 0f, 0)
        }
    }

    private fun trace(l: Layer, start: V3, startDir: Int, lanes: Int, spacing: Float, dist0: Float, depth: Int) {
        var p = start
        var dir = startDir
        var h = snap(p, anyPerp(p), dir)
        var run = 4 + rng.i(22)
        var dist = dist0
        val ids = IntArray(lanes) { walkerId++ }
        val laneBright = FloatArray(lanes) { rng.f(0.5f, 1.25f) }
        val steps = 30 + rng.i(130)
        val len = 0.011f
        val prevLanes = Array(lanes) { k -> (p + (p cross h) * ((k - (lanes - 1) / 2f) * spacing)).normalized() }
        for (s in 0 until steps) {
            if (l.full) return
            if (--run <= 0) {
                dir = (dir + (if (rng.chance(0.62f)) 1 else 2) * rng.sign().toInt() + 8) % 8
                run = 3 + rng.i(26)
            }
            h = snap(p, h, dir)
            val q = (p + h * len).normalized()
            val dens = field.at(q)
            if (rng.chance((1f - dens).pow(2.5f) * 0.09f)) break
            val center = l.hash.hit(q, len * 0.8f, ids[0])
            val side = q cross tangent(h, q)
            var blocked = center != null
            for (k in 0 until lanes) {
                val o = (k - (lanes - 1) / 2f) * spacing
                val lq = (q + side * o).normalized()
                if (!blocked && lanes > 1 && l.hash.hit(lq, len * 0.6f, ids[k]) != null) blocked = true
                if (!rng.chance(0.03f)) {
                    l.emit(prevLanes[k], lq, dist, dist + len, laneBright[k] * (0.45f + dens * 0.8f), 0.3f + dens * 0.45f, 1.1f, depth.toFloat(), ids[k])
                }
                prevLanes[k] = lq
            }
            dist += len
            p = q
            h = tangent(h, p)
            if (blocked) break
            if (depth < 3 && rng.chance(0.012f)) {
                trace(l, p, (dir + 2 * rng.sign().toInt() + 8) % 8, max(1, lanes / 2), spacing, dist, depth + 1)
                h = snap(p, h, dir)
            }
        }
        // Pads / vias at the end of every lane.
        for (k in 0 until lanes) {
            val pad = prevLanes[k]
            l.emit(pad, pad, dist, dist, 1.1f, 0.8f, 2.0f, depth.toFloat(), ids[k], collide = false)
        }
    }

    // ---------------------------------------------------------------------------------------
    // C · Lichtenberg: recursive branching discharge trees rooted in cities.

    private fun lichtenberg(l: Layer) {
        var guard = 0
        while (!l.full && guard++ < 40_000) {
            val p = sampleByDensity(2f)
            val h = anyPerp(p).let { rotateAround(it, p, rng.f(0f, 6.283f)) }
            val arms = 2 + rng.i(4)
            for (a in 0 until arms) {
                branch(l, p, rotateAround(h, p, a * 6.283f / arms + rng.f(-0.3f, 0.3f)), 0f, 0, 1f, 40 + rng.i(60), walkerId++)
            }
        }
    }

    private fun branch(l: Layer, start: V3, h0: V3, dist0: Float, depth: Int, bright: Float, steps: Int, id: Int) {
        var p = start
        var h = h0
        var dist = dist0
        for (s in 0 until steps) {
            if (l.full) return
            h = rotateAround(h, p, rng.sign() * rng.f(0.05f, 0.62f) * rng.f())
            val len = rng.f(0.005f, 0.013f) * (1f - depth * 0.08f)
            val q = (p + h * len).normalized()
            val hit = l.hash.hit(q, len * 0.5f, id)
            val dens = field.at(q)
            if (!rng.chance(0.05f)) {
                l.emit(p, hit?.normalized() ?: q, dist, dist + len, bright * (0.55f + dens * 0.9f), 0.35f + dens * 0.5f - depth * 0.04f, 1.25f - depth * 0.08f, depth.toFloat(), id)
            }
            if (hit != null) return
            dist += len
            h = tangent(h, q)
            p = q
            if (depth < 6 && rng.chance(0.085f + dens * 0.05f)) {
                val child = rotateAround(h, p, rng.sign() * rng.f(0.45f, 1.25f))
                branch(l, p, child, dist, depth + 1, bright * 0.8f, (steps - s) / 2 + rng.i(12), walkerId++)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // D · Metropolis: jittered street grids per city + suburbs (road walkers) + highways.

    private fun metropole(l: Layer) {
        if (l.radius > 0.9f) highways(l)
        val cities = field.cities.sortedByDescending { it.sigma * it.weight }
        for (c in cities) {
            if (l.full) break
            streetGrid(l, c)
        }
        routes(l, highways = false) // suburbs fill the remaining budget
    }

    private fun streetGrid(l: Layer, c: DensityField.City) {
        val e1 = rotateAround(anyPerp(c.c), c.c, rng.f(0f, 6.283f))
        val e2 = c.c cross e1
        val extent = c.sigma * 1.8f
        val spacing = rng.f(0.007f, 0.012f)
        val n = (extent / spacing).toInt()
        val id = walkerId++
        // Districts rotate the grid a bit: organic but still orthogonal.
        for (axis in 0..1) for (i in -n..n) {
            var dist = rng.f(0f, 4f)
            for (j in -n until n) {
                if (l.full) return
                val u0 = i * spacing; val v0 = j * spacing; val v1 = (j + 1) * spacing
                val r = sqrt(u0 * u0 + v0 * v0) / extent
                val keep = exp(-r * r * 2.2f) * (0.55f + 0.45f * field.continent(c.c))
                if (!rng.chance(keep)) { dist += spacing; continue }
                val jit = spacing * 0.22f
                val (a, b) = if (axis == 0) {
                    gridPoint(c.c, e1, e2, u0 + rng.gauss() * jit * 0.3f, v0) to gridPoint(c.c, e1, e2, u0 + rng.gauss() * jit * 0.3f, v1)
                } else {
                    gridPoint(c.c, e1, e2, v0, u0 + rng.gauss() * jit * 0.3f) to gridPoint(c.c, e1, e2, v1, u0 + rng.gauss() * jit * 0.3f)
                }
                val dens = field.at(a)
                val main = (i % 5 == 0)
                l.emit(a, b, dist, dist + spacing, (if (main) 1.35f else 0.8f) * (0.4f + dens * 0.9f) * rng.f(0.6f, 1.2f), 0.35f + dens * 0.55f, if (main) 1.5f else 1.05f, 0f, id)
                dist += spacing
            }
        }
    }

    private fun gridPoint(c: V3, e1: V3, e2: V3, u: Float, v: Float) = (c + e1 * u + e2 * v).normalized()

    // ---------------------------------------------------------------------------------------
    // Shared structure: conduits, city light dots, the core spiral.

    /** Broken radial data lines from the core to the shell. */
    private fun conduits(n: Int) {
        for (i in 0 until n) {
            val dir = sampleByDensity(1f)
            var p = dir * rng.f(0.12f, 0.25f)
            var dist = 0f
            val id = walkerId++
            val bright = rng.f(0.2f, 0.55f)
            while (p.length() < 0.98f) {
                val step = rng.f(0.02f, 0.045f)
                val lateral = anyPerp(dir).let { rotateAround(it, dir, rng.f(0f, 6.283f)) } * rng.f(0f, 0.012f)
                val q = p + dir * step + lateral
                if (!rng.chance(0.3f)) {
                    out.add(p, q, rng.f(), Kind.CONDUIT, dist, dist + step, bright, 0.55f, 0.9f, field.region(dir), q.length(), 0f)
                }
                dist += step
                p = q
            }
        }
    }

    /** Point-like city lights sprinkled by density on the shell. */
    private fun cityDots(n: Int) {
        for (i in 0 until n) {
            val p = sampleByDensity(1.4f)
            val dens = field.at(p)
            val b = rng.f(0.4f, 1.6f) * (0.5f + dens)
            out.add(p, p, rng.f(), Kind.DOT, 0f, 0f, b, 0.5f + dens * 0.4f, rng.f(1.6f, 2.8f), field.region(p), 1f, 0f)
        }
    }

    /**
     * The core: a tight two-arm spiral bundle in a tilted plane, plus a white-hot knot.
     * Local radius goes into the layer slot so the shader can spin it independently.
     */
    private fun core() {
        val axis = V3(0.22f, 1f, 0.12f).normalized()
        val e1 = anyPerp(axis)
        val e2 = axis cross e1
        val rMax = 0.28f
        val pitch = 0.2f
        for (arm in 0 until 2) {
            for (strand in 0 until 4) {
                val phase = arm * PI.toFloat() + rng.f(-0.1f, 0.1f)
                val lift = rng.gauss() * 0.006f
                var theta = rng.f(0f, 0.4f)
                var prev: V3? = null
                var dist = 0f
                while (true) {
                    val r = 0.006f * exp(pitch * theta)
                    if (r > rMax) break
                    val a = theta + phase
                    val wob = rng.gauss() * 0.0025f
                    val y = lift * (1f + r * 12f) + rng.gauss() * 0.004f * r * 10f
                    val q = e1 * ((r + wob) * cos(a)) + e2 * ((r + wob) * sin(a)) + axis * y
                    if (prev != null && !rng.chance(0.08f)) {
                        val t = r / rMax
                        val b = 5f * (1f - t).pow(2f) + 0.3f
                        out.add(prev, q, rng.f(), Kind.CORE, dist, dist + 0.004f, b, 1f - t * 0.75f, 1.15f, 0.5f, r, 0f)
                    }
                    dist += 0.004f
                    prev = q
                    theta += 0.004f / max(r, 0.01f) // constant arc length
                }
            }
        }
        // White-hot knot: short random segments packed at the very center.
        for (i in 0 until 2400) {
            val r = 0.05f * rng.f().pow(1.8f)
            val p = rng.unitVector() * r
            val q = p + rng.unitVector() * rng.f(0.002f, 0.012f)
            out.add(p, q, rng.f(), Kind.CORE, 0f, 0.01f, 7f * (1f - r / 0.05f) + 0.6f, 1f, 1.1f, 0.5f, r, 0f)
        }
        // Loose orbiting debris between core and inner shells, sparse.
        for (i in 0 until 1400) {
            val r = rng.f(0.2f, 0.42f)
            val p = (e1 * rng.gauss() + e2 * rng.gauss() + axis * (rng.gauss() * 0.35f)).normalized() * r
            val t = tangent(axis cross p, p.normalized())
            val q = p + t * rng.f(0.006f, 0.02f)
            out.add(p, q, rng.f(), Kind.CORE, 0f, 0.02f, rng.f(0.15f, 0.6f), 0.7f, 1f, 0.5f, r, 0f)
        }
    }
}
