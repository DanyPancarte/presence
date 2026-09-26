package com.dany.presence.render

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.dany.presence.sphere.FilamentStyle
import com.dany.presence.sphere.SphereGenerator
import com.dany.presence.sphere.SphereGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Scene (additive HDR filaments with geometric DOF) → bloom pyramid → composite (halo, dark body,
 * ACES, aberration, vignette, grain). tools/preview/renderer.js is a line-for-line WebGL2 mirror.
 */
class SphereRenderer(private val context: Context, val state: SphereState) : GLSurfaceView.Renderer {

    var renderScale = 0.85f
    private val gen = Executors.newSingleThreadExecutor()
    @Volatile private var pending: SphereGeometry? = null
    @Volatile var style = FilamentStyle.METROPOLE
        private set

    private lateinit var pFil: GlProgram
    private lateinit var pDown: GlProgram
    private lateinit var pUp: GlProgram
    private lateinit var pComp: GlProgram
    private var vao = 0
    private var emptyVao = 0
    private var instanceVbo = 0
    private var count = 0
    private var hdr = true

    private var outW = 1
    private var outH = 1
    private var scene: Target? = null
    private val mips = ArrayList<Target>()

    private val t0 = SystemClock.elapsedRealtimeNanos()
    private var last = 0f

    fun load(s: FilamentStyle) {
        style = s
        state.reveal.value = 0f
        state.reveal.target = 0f
        gen.execute {
            val g = SphereGenerator(s).generate()
            if (style == s) pending = g
        }
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        val a = context.assets
        pFil = GlProgram(a, "filament.vert", "filament.frag")
        pDown = GlProgram(a, "fullscreen.vert", "bloom_down.frag")
        pUp = GlProgram(a, "fullscreen.vert", "bloom_up.frag")
        pComp = GlProgram(a, "fullscreen.vert", "composite.frag")
        val ext = glGetString(GL_EXTENSIONS) ?: ""
        hdr = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")

        val ids = IntArray(2)
        glGenVertexArrays(2, ids, 0)
        vao = ids[0]; emptyVao = ids[1]
        val bufs = IntArray(2)
        glGenBuffers(2, bufs, 0)
        instanceVbo = bufs[1]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, bufs[0])
        val quad = floatArrayOf(0f, -1f, 1f, -1f, 0f, 1f, 1f, 1f)
        glBufferData(GL_ARRAY_BUFFER, quad.size * 4, quad.toBuffer(), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo)
        for (i in 0 until 4) {
            glEnableVertexAttribArray(1 + i)
            glVertexAttribPointer(1 + i, 4, GL_FLOAT, false, SphereGeometry.STRIDE_BYTES, i * 16)
            glVertexAttribDivisor(1 + i, 1)
        }
        glBindVertexArray(0)
        count = 0
        if (pending == null) load(style)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        outW = width; outH = height
        scene?.release(); mips.forEach { it.release() }; mips.clear()
        val w = (width * renderScale).toInt()
        val h = (height * renderScale).toInt()
        var s = Target(w, h, hdr)
        if (hdr && !s.complete) { s.release(); hdr = false; s = Target(w, h, false) }
        scene = s
        var mw = w / 2; var mh = h / 2
        while (mips.size < 7 && mw > 4 && mh > 4) { mips.add(Target(mw, mh, hdr)); mw /= 2; mh /= 2 }
    }

    override fun onDrawFrame(unused: GL10?) {
        pending?.let { upload(it); pending = null; state.reveal.target = 1f }
        val t = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9f
        val dt = (t - last).coerceIn(0f, 0.1f)
        last = t
        state.step(dt)
        draw(t)
    }

    private fun upload(g: SphereGeometry) {
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo)
        glBufferData(GL_ARRAY_BUFFER, g.data.size * 4, g.data.toBuffer(), GL_STATIC_DRAW)
        count = g.count
    }

    private fun draw(t: Float) {
        val scene = scene ?: return
        val W = scene.w; val H = scene.h
        val s = state

        // Camera: sphere diameter = FILL of the screen width.
        val fovY = Math.toRadians(30.0).toFloat()
        val aspect = W.toFloat() / H
        val tanX = tan(fovY / 2) * aspect
        val dist = sqrt(1f + (1f / (FILL * tanX)).let { it * it })
        val proj = perspective(fovY, aspect, 0.1f, 50f)
        val px = s.parallaxX; val py = s.parallaxY
        val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -px, -py, -dist, 1f)

        val yaw = t * 0.06f + drift(t, 0.37f) * 0.25f
        val pitch = 0.32f + drift(t, 0.53f) * 0.12f
        val roll = drift(t, 0.29f) * 0.09f
        val model = mul3(rotZ(roll), mul3(rotX(pitch), rotY(yaw)))
        val coreSpin = mul3(rotX(0.95f), axisAngle(0.2146f, 0.9755f, 0.1170f, t * 0.45f))

        // ---- scene ----
        glBindFramebuffer(GL_FRAMEBUFFER, scene.fbo)
        glViewport(0, 0, W, H)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        val f = pFil
        glUseProgram(f.id)
        glUniformMatrix4fv(f.u("uView"), 1, false, view, 0)
        glUniformMatrix4fv(f.u("uProj"), 1, false, proj, 0)
        glUniformMatrix3fv(f.u("uModel"), 1, false, model, 0)
        glUniformMatrix3fv(f.u("uCoreSpin"), 1, false, coreSpin, 0)
        glUniform2f(f.u("uViewport"), W.toFloat(), H.toFloat())
        glUniform1f(f.u("uTime"), t)
        glUniform1f(f.u("uPxScale"), H / 2400f)
        glUniform1f(f.u("uFocusDist"), dist - 0.75f)
        glUniform1f(f.u("uCocScale"), 7f)
        glUniform1f(f.u("uMaxCoc"), 18f)
        glUniform3f(f.u("uCenterView"), -px, -py, -dist)
        glUniform1f(f.u("uBodyRadius"), BODY)
        glUniform1f(f.u("uBodyDensity"), 2.6f)
        glUniform1f(f.u("uDilate"), s.dilate.value + s.amp * 0.12f)
        glUniform1f(f.u("uFlowSpeed"), s.flowSpeed.value)
        glUniform1f(f.u("uFlowAmt"), s.flowAmt.value)
        glUniform1f(f.u("uTwinkle"), s.twinkle.value)
        glUniform1f(f.u("uSweep"), s.sweep.value)
        glUniform1f(f.u("uSweepPos"), sin(t * 1.7f))
        glUniform3f(f.u("uSweepAxis"), 0.3f, 0.9f, 0.3f)
        glUniform1f(f.u("uGain"), s.gain.value * s.reveal.value)
        glUniform1f(f.u("uCoreGain"), s.coreGain.value)
        glUniform1f(f.u("uAlert"), s.alert.value)
        glUniform1f(f.u("uDensity"), s.density.value)
        glUniform1f(f.u("uTemp"), s.temp.value)
        glUniform1f(f.u("uBreath"), sin(t * 0.9f) * 0.5f + sin(t * 0.37f) * 0.5f)
        glUniform1f(f.u("uAmp"), s.amp)
        val b = s.bands
        glUniform4f(f.u("uBands"), b[0], b[1], b[2], b[3])
        val since = t - s.shockStart
        glUniform2f(f.u("uShock"), since * 1.1f, s.shockStrength * (1f - since / 1.3f).coerceIn(0f, 1f))
        val zones = s.zones
        val zp = s.zoneParams
        val zc = minOf(zones.size / 4, 12)
        glUniform1i(f.u("uZoneCount"), zc)
        if (zc > 0) {
            glUniform4fv(f.u("uZones"), zc, zones, 0)
            glUniform4fv(f.u("uZoneParams"), zc, zp, 0)
        }
        if (count > 0) {
            glBindVertexArray(vao)
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count)
        }
        glBindVertexArray(emptyVao)

        // ---- bloom down ----
        glDisable(GL_BLEND)
        val d = pDown
        glUseProgram(d.id)
        glUniform1i(d.u("uSrc"), 0)
        glUniform1f(d.u("uThreshold"), 0.9f)
        glUniform1f(d.u("uKnee"), 0.5f)
        glActiveTexture(GL_TEXTURE0)
        var src: Target = scene
        mips.forEachIndexed { i, m ->
            glBindFramebuffer(GL_FRAMEBUFFER, m.fbo)
            glViewport(0, 0, m.w, m.h)
            glBindTexture(GL_TEXTURE_2D, src.tex)
            glUniform2f(d.u("uTexel"), 1f / src.w, 1f / src.h)
            glUniform1f(d.u("uPrefilter"), if (i == 0) 1f else 0f)
            glDrawArrays(GL_TRIANGLES, 0, 3)
            src = m
        }
        // ---- bloom up ----
        val u = pUp
        glUseProgram(u.id)
        glUniform1i(u.u("uSrc"), 0)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        for (i in mips.size - 1 downTo 1) {
            val from = mips[i]; val to = mips[i - 1]
            glBindFramebuffer(GL_FRAMEBUFFER, to.fbo)
            glViewport(0, 0, to.w, to.h)
            glBindTexture(GL_TEXTURE_2D, from.tex)
            glUniform2f(u.u("uTexel"), 1f / from.w, 1f / from.h)
            glUniform1f(u.u("uRadius"), 1f)
            glUniform1f(u.u("uWeight"), 0.85f)
            glDrawArrays(GL_TRIANGLES, 0, 3)
        }
        glDisable(GL_BLEND)

        // ---- composite to screen ----
        val c = pComp
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, outW, outH)
        glUseProgram(c.id)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, scene.tex)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, mips[0].tex)
        glUniform1i(c.u("uScene"), 0)
        glUniform1i(c.u("uBloom"), 1)
        glUniform2f(c.u("uResolution"), outW.toFloat(), outH.toFloat())
        val fy = 1f / tan(fovY / 2)
        val cy = (-py) / dist * fy
        val cx = (-px) / dist * fy / aspect
        val rNdc = (1f / sqrt(dist * dist - 1f)) * fy
        glUniform2f(c.u("uSphereCenter"), (cx * 0.5f + 0.5f) * outW, (cy * 0.5f + 0.5f) * outH)
        glUniform1f(c.u("uSphereRadius"), rNdc * 0.5f * outH)
        glUniform1f(c.u("uBodyRadiusPx"), rNdc * 0.5f * outH * BODY)
        glUniform1f(c.u("uBloomStrength"), s.bloom.value)
        glUniform1f(c.u("uExposure"), s.exposure.value)
        glUniform1f(c.u("uTime"), t)
        glUniform1f(c.u("uHalo"), s.halo.value * s.reveal.value)
        glUniform1f(c.u("uAlert"), s.alert.value)
        glUniform1f(c.u("uHdrScale"), if (hdr) 1f else 6f)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glActiveTexture(GL_TEXTURE0)
    }

    companion object {
        const val FILL = 0.86f
        const val BODY = 0.96f

        private fun FloatArray.toBuffer() =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(this); it.position(0) }

        /** Smooth, non-repeating-in-practice drift (incommensurate sines). Mirrors renderer.js. */
        fun drift(t: Float, k: Float) = sin(t * 0.071f * k + 1.3f * k) * 0.6f + sin(t * 0.0313f * k + 0.7f) * 0.4f

        fun perspective(fovY: Float, aspect: Float, near: Float, far: Float): FloatArray {
            val f = 1f / tan(fovY / 2); val nf = 1f / (near - far)
            return floatArrayOf(f / aspect, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, (far + near) * nf, -1f, 0f, 0f, 2 * far * near * nf, 0f)
        }

        fun mul3(a: FloatArray, b: FloatArray): FloatArray {
            val o = FloatArray(9)
            for (c in 0 until 3) for (r in 0 until 3) {
                o[c * 3 + r] = a[r] * b[c * 3] + a[3 + r] * b[c * 3 + 1] + a[6 + r] * b[c * 3 + 2]
            }
            return o
        }

        fun rotX(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(1f, 0f, 0f, 0f, c, s, 0f, -s, c) }
        fun rotY(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(c, 0f, -s, 0f, 1f, 0f, s, 0f, c) }
        fun rotZ(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(c, s, 0f, -s, c, 0f, 0f, 0f, 1f) }
        fun axisAngle(x: Float, y: Float, z: Float, a: Float): FloatArray {
            val c = cos(a); val s = sin(a); val t = 1 - c
            return floatArrayOf(
                t * x * x + c, t * x * y + s * z, t * x * z - s * y,
                t * x * y - s * z, t * y * y + c, t * y * z + s * x,
                t * x * z + s * y, t * y * z - s * x, t * z * z + c,
            )
        }
    }
}
