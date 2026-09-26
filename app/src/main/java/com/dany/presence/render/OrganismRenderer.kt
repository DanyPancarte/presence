package com.dany.presence.render

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * The living sphere: GPU particle organism (transform feedback) → velocity streaks with trail
 * feedback and geometric DOF → bloom pyramid → composite (halo, dark body, ACES, aberration,
 * vignette, grain). tools/preview/renderer.js is a line-for-line WebGL2 mirror.
 */
class OrganismRenderer(private val context: Context, val state: SphereState) : GLSurfaceView.Renderer {

    var renderScale = 0.85f
    val particles = 150_000
    @Volatile var preset = Organism.VORTEX

    private lateinit var pSim: GlProgram
    private lateinit var pPart: GlProgram
    private lateinit var pFade: GlProgram
    private lateinit var pDown: GlProgram
    private lateinit var pUp: GlProgram
    private lateinit var pComp: GlProgram
    private val bufs = IntArray(2)
    private val simVao = IntArray(2)
    private val drawVao = IntArray(2)
    private var emptyVao = 0
    private var tf = 0
    private var cur = 0
    private var hdr = true

    private var outW = 1
    private var outH = 1
    private var scene: Target? = null
    private val mips = ArrayList<Target>()

    private var simTime = 0f
    private var frame = 0
    private var acc = 0f
    private var lastNs = 0L

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        val a = context.assets
        pSim = GlProgram(a, "sim.vert", "sim.frag", arrayOf("oPos", "oVel"))
        pPart = GlProgram(a, "particle.vert", "streak.frag")
        pFade = GlProgram(a, "fullscreen.vert", "fade.frag")
        pDown = GlProgram(a, "fullscreen.vert", "bloom_down.frag")
        pUp = GlProgram(a, "fullscreen.vert", "bloom_up.frag")
        pComp = GlProgram(a, "fullscreen.vert", "composite.frag")
        val ext = glGetString(GL_EXTENSIONS) ?: ""
        hdr = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")

        // Initial state: particles scattered in the ball with random ages (no synchronised births).
        val rnd = Random(SystemClock.elapsedRealtimeNanos())
        val data = FloatArray(particles * 8)
        for (i in 0 until particles) {
            var x: Float; var y: Float; var z: Float
            do { x = rnd.nextFloat() * 2 - 1; y = rnd.nextFloat() * 2 - 1; z = rnd.nextFloat() * 2 - 1 } while (x * x + y * y + z * z > 1f)
            val o = i * 8
            data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 3] = rnd.nextFloat() * 12f
            data[o + 7] = rnd.nextFloat() * 1000f
        }
        val quadBuf = IntArray(1)
        glGenBuffers(1, quadBuf, 0)
        glBindBuffer(GL_ARRAY_BUFFER, quadBuf[0])
        val quad = floatArrayOf(0f, -1f, 1f, -1f, 0f, 1f, 1f, 1f)
        glBufferData(GL_ARRAY_BUFFER, quad.size * 4, quad.toBuffer(), GL_STATIC_DRAW)
        glGenBuffers(2, bufs, 0)
        val db = data.toBuffer()
        for (b in bufs) {
            glBindBuffer(GL_ARRAY_BUFFER, b)
            glBufferData(GL_ARRAY_BUFFER, data.size * 4, db, GL_DYNAMIC_COPY)
        }
        glGenVertexArrays(2, simVao, 0)
        glGenVertexArrays(2, drawVao, 0)
        for (i in 0..1) {
            glBindVertexArray(simVao[i])
            glBindBuffer(GL_ARRAY_BUFFER, bufs[i])
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 4, GL_FLOAT, false, 32, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, false, 32, 16)

            glBindVertexArray(drawVao[i])
            glBindBuffer(GL_ARRAY_BUFFER, quadBuf[0])
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
            glBindBuffer(GL_ARRAY_BUFFER, bufs[i])
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, false, 32, 0); glVertexAttribDivisor(1, 1)
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 32, 16); glVertexAttribDivisor(2, 1)
        }
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0); emptyVao = ids[0]
        glGenTransformFeedbacks(1, ids, 0); tf = ids[0]
        lastNs = 0L
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
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastNs == 0L) 1f / 60 else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        state.step(dt)
        acc += dt
        var n = 0
        while (acc >= STEP && n < 3) { step(STEP); acc -= STEP; n++ }
        draw(dt)
    }

    private fun step(dt: Float) {
        val s = pSim
        val t = simTime
        val pr = preset
        val st = state
        glUseProgram(s.id)
        // Per-axis pull breathes on its own slow, unrelated rhythms: never the same shape twice.
        val ax0 = drift(t, 0.61f); val ax1 = drift(t + 40f, 0.43f); val ax2 = drift(t + 90f, 0.77f)
        val energy = st.energy.value
        glUniform1f(s.u("uTime"), t)
        glUniform1f(s.u("uDt"), dt)
        glUniform1f(s.u("uFrame"), (frame % 100_000).toFloat())
        glUniform3f(s.u("uAttract"), pr.attract[0] * (1 + 0.55f * ax0), pr.attract[1] * (1 + 0.55f * ax1), pr.attract[2] * (1 + 0.55f * ax2))
        glUniform1f(s.u("uCurl"), pr.curl * (0.8f + 0.4f * energy))
        glUniform1f(s.u("uCurlScale"), pr.curlScale)
        glUniform1f(s.u("uCurlSpeed"), 0.12f * energy)
        val a = t * 0.047f; val b = t * 0.031f + 1f
        glUniform3f(s.u("uSwirlAxis"), sin(a) * 0.45f, cos(b) * 0.3f + 0.85f, cos(a) * 0.45f)
        glUniform1f(s.u("uSwirl"), pr.swirl * (0.7f + 0.5f * energy) * (1 + 0.35f * drift(t + 13f, 0.5f)))
        glUniform1f(s.u("uShellR"), 1f + st.dilate.value + st.amp * 0.12f)
        glUniform1f(s.u("uLife"), LIFE)
        glUniform1f(s.u("uStreams"), pr.streams)
        val since = t - st.shockStart
        glUniform2f(s.u("uShock"), since * 1.1f, st.shockStrength * (1f - since / 1.3f).coerceIn(0f, 1f))
        glUniform1f(s.u("uAmp"), st.amp)
        glUniform1f(s.u("uCoreR"), 0.045f)
        val src = cur; val dst = 1 - cur
        glBindVertexArray(simVao[src])
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindTransformFeedback(GL_TRANSFORM_FEEDBACK, tf)
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, bufs[dst])
        glEnable(GL_RASTERIZER_DISCARD)
        glBeginTransformFeedback(GL_POINTS)
        glDrawArrays(GL_POINTS, 0, particles)
        glEndTransformFeedback()
        glDisable(GL_RASTERIZER_DISCARD)
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0)
        glBindTransformFeedback(GL_TRANSFORM_FEEDBACK, 0)
        glBindVertexArray(0)
        cur = dst
        simTime += dt
        frame++
    }

    /** Time of the simulation clock, used by the audio shock wave. */
    val clock get() = simTime

    private fun draw(dt: Float) {
        val scene = scene ?: return
        val W = scene.w; val H = scene.h
        val s = state
        val t = simTime

        val fovY = Math.toRadians(30.0).toFloat()
        val aspect = W.toFloat() / H
        val tanX = tan(fovY / 2) * aspect
        val dist = sqrt(1f + (1f / (FILL * tanX)).let { it * it })
        val proj = perspective(fovY, aspect, 0.1f, 50f)
        val px = s.parallaxX; val py = s.parallaxY
        val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -px, -py, -dist, 1f)
        val model = mul3(rotX(0.25f + drift(t, 0.53f) * 0.1f), rotY(t * 0.03f))

        // ---- scene: fade previous frame (trails), then add streaks ----
        glBindFramebuffer(GL_FRAMEBUFFER, scene.fbo)
        glViewport(0, 0, W, H)
        glBindVertexArray(emptyVao)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ZERO, GL_SRC_ALPHA)
        glUseProgram(pFade.id)
        glUniform1f(pFade.u("uDecay"), TRAIL.pow(maxOf(dt, 1f / 240) * 60f))
        glDrawArrays(GL_TRIANGLES, 0, 3)

        glBlendFunc(GL_ONE, GL_ONE)
        val f = pPart
        glUseProgram(f.id)
        glUniformMatrix4fv(f.u("uView"), 1, false, view, 0)
        glUniformMatrix4fv(f.u("uProj"), 1, false, proj, 0)
        glUniformMatrix3fv(f.u("uModel"), 1, false, model, 0)
        glUniform2f(f.u("uViewport"), W.toFloat(), H.toFloat())
        glUniform1f(f.u("uTime"), t)
        glUniform1f(f.u("uPxScale"), H / 2400f)
        glUniform1f(f.u("uStreak"), 0.05f)
        glUniform1f(f.u("uFocusDist"), dist - 0.75f)
        glUniform1f(f.u("uCocScale"), 6f)
        glUniform1f(f.u("uMaxCoc"), 14f)
        glUniform3f(f.u("uCenterView"), -px, -py, -dist)
        glUniform1f(f.u("uBodyRadius"), BODY)
        glUniform1f(f.u("uBodyDensity"), 1.3f)
        glUniform1f(f.u("uGain"), s.gain.value * (1 - TRAIL) * 3.3f)
        glUniform1f(f.u("uCoreGain"), s.coreGain.value)
        glUniform1f(f.u("uTwinkle"), s.twinkle.value)
        glUniform1f(f.u("uAlert"), s.alert.value)
        glUniform1f(f.u("uTemp"), s.temp.value)
        glUniform1f(f.u("uDensity"), s.density.value)
        glUniform1f(f.u("uLife"), LIFE)
        glUniform1f(f.u("uSweep"), s.sweep.value)
        glUniform1f(f.u("uSweepPos"), sin(t * 1.7f))
        glUniform3f(f.u("uSweepAxis"), 0.3f, 0.9f, 0.3f)
        val b = s.bands
        glUniform4f(f.u("uBands"), b[0], b[1], b[2], b[3])
        val since = t - s.shockStart
        glUniform2f(f.u("uShock"), since * 1.1f, s.shockStrength * (1f - since / 1.3f).coerceIn(0f, 1f))
        val zones = s.zones
        val zc = min(zones.size / 4, 12)
        glUniform1i(f.u("uZoneCount"), zc)
        if (zc > 0) {
            glUniform4fv(f.u("uZones"), zc, zones, 0)
            glUniform4fv(f.u("uZoneParams"), zc, s.zoneParams, 0)
        }
        glBindVertexArray(drawVao[cur])
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, particles)
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
        glUniform1f(c.u("uHalo"), s.halo.value)
        glUniform1f(c.u("uAlert"), s.alert.value)
        glUniform1f(c.u("uHdrScale"), if (hdr) 1f else 6f)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glActiveTexture(GL_TEXTURE0)
    }

    companion object {
        const val FILL = 0.86f
        const val BODY = 0.96f
        const val TRAIL = 0.9f
        const val LIFE = 9f
        const val STEP = 1f / 60f

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
    }
}
