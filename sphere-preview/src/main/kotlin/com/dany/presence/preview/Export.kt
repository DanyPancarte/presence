package com.dany.presence.preview

import com.dany.presence.sphere.FilamentStyle
import com.dany.presence.sphere.SphereGenerator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dumps every filament variant as raw little-endian float32 for the WebGL preview harness. */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "tools/preview/data" }).apply { mkdirs() }
    for (style in FilamentStyle.entries) {
        val t0 = System.nanoTime()
        val g = SphereGenerator(style).generate()
        val bb = ByteBuffer.allocate(g.data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(g.data)
        File(dir, "${style.name.lowercase()}.bin").writeBytes(bb.array())
        println("${style.label}: ${g.count} segments in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
