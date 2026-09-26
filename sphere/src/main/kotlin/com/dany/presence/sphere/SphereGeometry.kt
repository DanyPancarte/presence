package com.dany.presence.sphere

/**
 * Interleaved instance buffer, one filament segment per instance. Layout (16 floats):
 *
 *  0..2  p0.xyz      3  seed (0..1)
 *  4..6  p1.xyz      7  kind (see [Kind])
 *  8     dist0       9  dist1        (path length from root, drives flowing currents)
 * 10     brightness 11  heat (0 = deep orange, 1 = white-hot)
 * 12     width px   13  region (0..1, stable per-cluster value used by modules / bands)
 * 14     radius of the layer the segment lives on   15  branch depth
 *
 * The shaders in app/src/main/assets/shaders rely on this exact layout.
 */
class SphereGeometry(val data: FloatArray, val count: Int) {
    companion object {
        const val FLOATS = 16
        const val STRIDE_BYTES = FLOATS * 4
    }
}

object Kind {
    const val SHELL = 0f
    const val INNER = 1f
    const val CORE = 2f
    const val DOT = 3f
    const val CONDUIT = 4f
    const val OVERLAY = 5f
}

class SegmentSink(initial: Int = 1 shl 16) {
    var data = FloatArray(initial * SphereGeometry.FLOATS)
        private set
    var count = 0
        private set

    fun add(
        p0: V3, p1: V3, seed: Float, kind: Float,
        d0: Float, d1: Float, bright: Float, heat: Float,
        width: Float, region: Float, layer: Float, depth: Float = 0f,
    ) {
        if ((count + 1) * SphereGeometry.FLOATS > data.size) data = data.copyOf(data.size * 2)
        val o = count * SphereGeometry.FLOATS
        val d = data
        d[o] = p0.x; d[o + 1] = p0.y; d[o + 2] = p0.z; d[o + 3] = seed
        d[o + 4] = p1.x; d[o + 5] = p1.y; d[o + 6] = p1.z; d[o + 7] = kind
        d[o + 8] = d0; d[o + 9] = d1; d[o + 10] = bright; d[o + 11] = heat
        d[o + 12] = width; d[o + 13] = region; d[o + 14] = layer; d[o + 15] = depth
        count++
    }

    fun build() = SphereGeometry(data.copyOf(count * SphereGeometry.FLOATS), count)
}

/** Uniform grid hash over segments, used to stop walkers when they hit an existing road. */
class SegmentHash(private val cell: Float) {
    private val map = HashMap<Long, IntArrayList>()
    private val ax = ArrayList<V3>()
    private val bx = ArrayList<V3>()
    private val owner = IntArrayList()

    private fun key(ix: Int, iy: Int, iz: Int): Long =
        ((ix + 4096).toLong() shl 42) or ((iy + 4096).toLong() shl 21) or (iz + 4096).toLong()

    fun insert(a: V3, b: V3, walker: Int) {
        val id = ax.size
        ax.add(a); bx.add(b); owner.add(walker)
        val m = (a + b) * 0.5f
        val k = key(floorDiv(m.x / cell), floorDiv(m.y / cell), floorDiv(m.z / cell))
        map.getOrPut(k) { IntArrayList() }.add(id)
    }

    private fun floorDiv(v: Float) = kotlin.math.floor(v).toInt()

    /** Closest point of an existing segment within [radius] of [p] not owned by [walker], or null. */
    fun hit(p: V3, radius: Float, walker: Int): V3? {
        val cx = floorDiv(p.x / cell); val cy = floorDiv(p.y / cell); val cz = floorDiv(p.z / cell)
        var best: V3? = null
        var bestD = radius * radius
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val list = map[key(cx + dx, cy + dy, cz + dz)] ?: continue
            for (n in 0 until list.size) {
                val id = list[n]
                if (owner[id] == walker) continue
                val q = closest(p, ax[id], bx[id])
                val d = q - p
                val dd = d dot d
                if (dd < bestD) { bestD = dd; best = q }
            }
        }
        return best
    }

    private fun closest(p: V3, a: V3, b: V3): V3 {
        val ab = b - a
        val l = ab dot ab
        if (l < 1e-12f) return a
        val t = (((p - a) dot ab) / l).coerceIn(0f, 1f)
        return a + ab * t
    }
}

class IntArrayList {
    private var a = IntArray(4)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
}
