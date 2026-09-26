package com.dany.presence.render

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Full-screen particle hologram: transform-feedback simulation → additive points → quarter-res
 * bloom → composite (glitch, aberration, scanlines, vignette, grain).
 * tools/preview/mockup.html is the WebGL2 twin of this file.
 */
class HoloRenderer(private val context: Context, val state: HoloState) : GLSurfaceView.Renderer {

    var renderScale = 0.75f
    val particles = 90_000

    private lateinit var pSim: GlProgram
    private lateinit var pDraw: GlProgram
    private lateinit var pBlur: GlProgram
    private lateinit var pComp: GlProgram
    private val bufs = IntArray(2)
    private val vaos = IntArray(2)
    private var emptyVao = 0
    private var tf = 0
    private var cur = 0
    private var hdr = true
    private var outW = 1
    private var outH = 1
    private var scene: Target? = null
    private var b1: Target? = null
    private var b2: Target? = null
    private val t0 = SystemClock.elapsedRealtimeNanos()
    private var lastNs = 0L
    private val proj = FloatArray(16)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        val a = context.assets
        pSim = GlProgram(a, "holo_sim.vert", "holo_sim.frag", arrayOf("oPos", "oVel"))
        pDraw = GlProgram(a, "holo_draw.vert", "holo_draw.frag")
        pBlur = GlProgram(a, "fullscreen.vert", "blur.frag")
        pComp = GlProgram(a, "fullscreen.vert", "holo_comp.frag")
        val ext = glGetString(GL_EXTENSIONS) ?: ""
        hdr = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")

        val rnd = Random(7)
        val data = FloatArray(particles * 8)
        for (i in 0 until particles) {
            val o = i * 8
            data[o] = (rnd.nextFloat() * 2 - 1) * 2; data[o + 1] = rnd.nextFloat() * 2 - 1; data[o + 2] = rnd.nextFloat() * 2 - 1
            data[o + 7] = i * 0.618f + rnd.nextFloat()
        }
        glGenBuffers(2, bufs, 0)
        val db = data.toBuffer()
        for (b in bufs) { glBindBuffer(GL_ARRAY_BUFFER, b); glBufferData(GL_ARRAY_BUFFER, data.size * 4, db, GL_DYNAMIC_COPY) }
        glGenVertexArrays(2, vaos, 0)
        for (i in 0..1) {
            glBindVertexArray(vaos[i])
            glBindBuffer(GL_ARRAY_BUFFER, bufs[i])
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 4, GL_FLOAT, false, 32, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, false, 32, 16)
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
        listOfNotNull(scene, b1, b2).forEach { it.release() }
        val w = (width * renderScale).toInt(); val h = (height * renderScale).toInt()
        var s = Target(w, h, hdr)
        if (hdr && !s.complete) { s.release(); hdr = false; s = Target(w, h, false) }
        scene = s
        b1 = Target(w / 4, h / 4, hdr); b2 = Target(w / 4, h / 4, hdr)
        val fov = 0.62f; val aspect = w.toFloat() / h
        val f = 1f / tan(fov / 2); val near = 0.1f; val far = 20f; val nf = 1f / (near - far)
        proj.fill(0f)
        proj[0] = f / aspect; proj[5] = f; proj[10] = (far + near) * nf; proj[11] = -1f; proj[14] = 2 * far * near * nf
    }

    override fun onDrawFrame(unused: GL10?) {
        val scene = scene ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastNs == 0L) 1f / 60 else ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = now
        val t = (now - t0) / 1e9f
        val s = state
        s.step(dt)
        val W = scene.w; val H = scene.h
        val aspect = W.toFloat() / H
        val jitter = s.alert * (0.5f + 0.5f * sin(t * 9f))
        val glitch = s.glitch + s.pulse * 0.25f

        // ---- simulation ----
        val p = pSim
        glUseProgram(p.id)
        glUniform1f(p.u("uT"), t); glUniform1f(p.u("uDt"), dt); glUniform1f(p.u("uAspect"), aspect)
        glUniform1f(p.u("uBlend"), s.blend); glUniform1f(p.u("uEnergy"), s.energy); glUniform1f(p.u("uAmp"), s.amp)
        glUniform1f(p.u("uGlitch"), glitch); glUniform1f(p.u("uJitter"), jitter)
        glUniform1i(p.u("uA"), s.shapeA); glUniform1i(p.u("uB"), s.shapeB)
        glUniform1fv(p.u("uBands"), 24, s.bands, 0)
        glUniform2f(p.u("uTouch"), s.touchX, s.touchY)
        val src = cur; val dst = 1 - cur
        glBindVertexArray(vaos[src])
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
        cur = dst

        // ---- points ----
        glBindFramebuffer(GL_FRAMEBUFFER, scene.fbo)
        glViewport(0, 0, W, H)
        glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE)
        val d = pDraw
        glUseProgram(d.id)
        glUniformMatrix4fv(d.u("uProj"), 1, false, proj, 0)
        glUniform1f(d.u("uPx"), H / 900f); glUniform1f(d.u("uT"), t); glUniform1f(d.u("uAlert"), s.alert); glUniform1f(d.u("uAmp"), s.amp)
        glUniform2f(d.u("uParallax"), s.parallaxX, s.parallaxY)
        glUniform3f(d.u("uAccent"), s.accent[0], s.accent[1], s.accent[2])
        glBindVertexArray(vaos[cur])
        glDrawArrays(GL_POINTS, 0, particles)
        glBindVertexArray(emptyVao)
        glDisable(GL_BLEND)

        // ---- bloom (quarter res, separable) ----
        val b1 = b1!!; val b2 = b2!!
        val bl = pBlur
        glUseProgram(bl.id)
        glUniform1i(bl.u("uSrc"), 0)
        glActiveTexture(GL_TEXTURE0)
        glBindFramebuffer(GL_FRAMEBUFFER, b1.fbo); glViewport(0, 0, b1.w, b1.h)
        glBindTexture(GL_TEXTURE_2D, scene.tex); glUniform2f(bl.u("uDir"), 1.5f / b1.w, 0f); glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindFramebuffer(GL_FRAMEBUFFER, b2.fbo)
        glBindTexture(GL_TEXTURE_2D, b1.tex); glUniform2f(bl.u("uDir"), 0f, 1.5f / b1.h); glDrawArrays(GL_TRIANGLES, 0, 3)

        // ---- composite ----
        val c = pComp
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, outW, outH)
        glUseProgram(c.id)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, scene.tex)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, b2.tex)
        glUniform1i(c.u("uScene"), 0); glUniform1i(c.u("uBloom"), 1)
        glUniform1f(c.u("uT"), t); glUniform1f(c.u("uAlert"), s.alert); glUniform1f(c.u("uGlitch"), glitch)
        glUniform1f(c.u("uHdrScale"), if (hdr) 1f else 3f)
        glUniform2f(c.u("uRes"), outW.toFloat(), outH.toFloat())
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glActiveTexture(GL_TEXTURE0)
    }

    /** Screen px → box coords for touch repulsion. */
    fun touchToBox(xPx: Float, yPx: Float): Pair<Float, Float> {
        val a = outW.toFloat() / outH
        return (xPx / outW * 2 - 1) * a to (1 - yPx / outH * 2)
    }

    companion object {
        private fun FloatArray.toBuffer() =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(this); it.position(0) }
    }
}
