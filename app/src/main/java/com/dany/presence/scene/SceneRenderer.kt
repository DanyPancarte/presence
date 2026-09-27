package com.dany.presence.scene

import android.content.Context
import android.opengl.GLES30.GL_ARRAY_BUFFER
import android.opengl.GLES30.GL_BLEND
import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_DEPTH_TEST
import android.opengl.GLES30.GL_ELEMENT_ARRAY_BUFFER
import android.opengl.GLES30.GL_EXTENSIONS
import android.opengl.GLES30.GL_FLOAT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_ONE
import android.opengl.GLES30.GL_POINTS
import android.opengl.GLES30.GL_STATIC_DRAW
import android.opengl.GLES30.GL_TEXTURE0
import android.opengl.GLES30.GL_TEXTURE1
import android.opengl.GLES30.GL_TEXTURE2
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_TRIANGLES
import android.opengl.GLES30.GL_UNSIGNED_INT
import android.opengl.GLES30.glActiveTexture
import android.opengl.GLES30.glBindBuffer
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glBindVertexArray
import android.opengl.GLES30.glBlendFunc
import android.opengl.GLES30.glBufferData
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glDrawArrays
import android.opengl.GLES30.glDrawElements
import android.opengl.GLES30.glEnable
import android.opengl.GLES30.glEnableVertexAttribArray
import android.opengl.GLES30.glGenBuffers
import android.opengl.GLES30.glGenVertexArrays
import android.opengl.GLES30.glGetString
import android.opengl.GLES30.glUniform1f
import android.opengl.GLES30.glUniform1fv
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUniform2f
import android.opengl.GLES30.glUniform3f
import android.opengl.GLES30.glUniform4f
import android.opengl.GLES30.glUniform4fv
import android.opengl.GLES30.glUniformMatrix4fv
import android.opengl.GLES30.glUseProgram
import android.opengl.GLES30.glVertexAttribPointer
import android.opengl.GLES30.glViewport
import android.opengl.GLSurfaceView
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * The cinematic scene. Terrain (contours in the fragment shader) and the disc of points are drawn additively
 * into an HDR target; a quarter-res blur gives the bloom and a half-res blur the out-of-focus copy; the
 * composite adds tilt-shift, light leaks, vignette, tonemap and grain. tools/preview/mockup.html is the twin.
 */
internal class SceneRenderer(private val context: Context, val state: SceneState) : GLSurfaceView.Renderer {
    /** Fraction of the surface resolution the scene is rendered at. */
    var renderScale = 0.72f
    /** Screen row (0 = bottom, 1 = top) that stays sharp. */
    var focus = 0.5f

    private lateinit var pTerr: SceneProgram
    private lateinit var pPts: SceneProgram
    private lateinit var pBlur: SceneProgram
    private lateinit var pComp: SceneProgram
    private var terrVao = 0
    private var terrIndices = 0
    private var ptVao = 0
    private var pointCount = 0
    private var emptyVao = 0
    private var hdr = true
    private var outW = 1
    private var outH = 1
    private var aspect = 1f
    private var scene: SceneTarget? = null
    private var b1: SceneTarget? = null
    private var b2: SceneTarget? = null
    private var d1: SceneTarget? = null
    private var d2: SceneTarget? = null
    private var t0 = 0L
    private var lastNs = 0L
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val eye = FloatArray(3)
    private val right = FloatArray(3)
    private val up = FloatArray(3)
    private val back = FloatArray(3)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        val a = context.assets
        pTerr = SceneProgram(a, "cine_terrain.vert", "cine_terrain.frag")
        pPts = SceneProgram(a, "cine_points.vert", "cine_points.frag")
        pBlur = SceneProgram(a, "cine_fullscreen.vert", "cine_blur.frag")
        pComp = SceneProgram(a, "cine_fullscreen.vert", "cine_comp.frag")
        val ext = glGetString(GL_EXTENSIONS) ?: ""
        hdr = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
        buildTerrain()
        buildPoints()
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0)
        emptyVao = ids[0]
        if (t0 == 0L) t0 = SystemClock.elapsedRealtimeNanos()
        lastNs = 0L
        scene = null
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        outW = width
        outH = height
        listOfNotNull(scene, b1, b2, d1, d2).forEach { it.release() }
        val w = max(1, (width * renderScale).roundToInt())
        val h = max(1, (height * renderScale).roundToInt())
        var s = SceneTarget(w, h, hdr)
        if (hdr && !s.complete) { s.release(); hdr = false; s = SceneTarget(w, h, false) }
        scene = s
        b1 = SceneTarget(max(1, w / 4), max(1, h / 4), hdr)
        b2 = SceneTarget(max(1, w / 4), max(1, h / 4), hdr)
        d1 = SceneTarget(max(1, w / 2), max(1, h / 2), hdr)
        d2 = SceneTarget(max(1, w / 2), max(1, h / 2), hdr)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        aspect = w.toFloat() / h
        perspective(proj, FOV, aspect, 0.1f, 30f)
    }

    override fun onDrawFrame(unused: GL10?) {
        val sc = scene ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastNs == 0L) 1f / 60 else ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = now
        val tt = (now - t0) / 1e9
        val t = tt.toFloat()
        val s = state
        s.step(dt)
        camera(t, dt)
        val W = sc.w
        val H = sc.h
        val px = H / 900f
        val gain = if (hdr) 1f else LDR_GAIN
        val voice = s.voicePulse(t)

        glBindFramebuffer(GL_FRAMEBUFFER, sc.fbo)
        glViewport(0, 0, W, H)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        glDisable(GL_DEPTH_TEST)

        // ---- terrain ----
        val tp = pTerr
        glUseProgram(tp.id)
        glUniformMatrix4fv(tp.u("uVP"), 1, false, vp, 0)
        glUniform1f(tp.u("uT"), t)
        glUniform1f(tp.u("uAmp"), s.amp)
        glUniform1f(tp.u("uShock"), s.shock)
        glUniform1f(tp.u("uLow"), s.bands[0])
        glUniform1f(tp.u("uHeat"), s.heat)
        glUniform1f(tp.u("uGain"), gain)
        glUniform3f(tp.u("uCam"), eye[0], eye[1], eye[2])
        glUniform4f(tp.u("uTouch"), s.touchWx, s.touchWz, s.touch, 0f)
        glBindVertexArray(terrVao)
        glDrawElements(GL_TRIANGLES, terrIndices, GL_UNSIGNED_INT, 0)

        // ---- rings, core, ribbons ----
        val pp = pPts
        glUseProgram(pp.id)
        glUniformMatrix4fv(pp.u("uVP"), 1, false, vp, 0)
        glUniform1f(pp.u("uT"), t)
        glUniform1f(pp.u("uAmp"), s.amp)
        glUniform1f(pp.u("uPitch"), s.pitch)
        glUniform1f(pp.u("uHeat"), s.heat)
        glUniform1f(pp.u("uShock"), s.shock)
        glUniform1f(pp.u("uListen"), s.listen)
        glUniform1f(pp.u("uPx"), px)
        glUniform1f(pp.u("uSpin"), s.spin)
        glUniform1f(pp.u("uFlow"), s.flow)
        glUniform1fv(pp.u("uBands"), 8, s.bands, 0)
        glUniform3f(pp.u("uCam"), eye[0], eye[1], eye[2])
        glUniform4fv(pp.u("uSeg"), 8, s.seg, 0)
        glUniform1i(pp.u("uSegN"), 8)
        glUniform1f(pp.u("uMed"), s.med)
        glUniform1f(pp.u("uMargin"), s.margin)
        glUniform1f(pp.u("uVoice"), voice)
        glUniform1f(pp.u("uEvents"), s.events)
        glUniform4f(pp.u("uTouch"), s.touchWx, s.touchWz, s.touch, 0f)
        glUniform1f(pp.u("uGain"), gain)
        glBindVertexArray(ptVao)
        glDrawArrays(GL_POINTS, 0, pointCount)
        glBindVertexArray(emptyVao)
        glDisable(GL_BLEND)

        // ---- bloom (quarter res) and out-of-focus copy (half res) ----
        blur(sc, b1!!, b2!!, 1.8f)
        blur(sc, d1!!, d2!!, 1.4f)

        // ---- composite ----
        val c = pComp
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, outW, outH)
        glUseProgram(c.id)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sc.tex)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, b2!!.tex)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, d2!!.tex)
        glUniform1i(c.u("uS"), 0)
        glUniform1i(c.u("uB"), 1)
        glUniform1i(c.u("uD"), 2)
        glUniform1f(c.u("uGrain"), ((tt * 9.0) % 1.0 * 100.0).toFloat())
        glUniform1f(c.u("uAmp"), s.amp)
        glUniform1f(c.u("uAlert"), s.alert)
        glUniform1f(c.u("uListen"), s.listen)
        glUniform1f(c.u("uFocus"), focus)
        glUniform1f(c.u("uHdr"), 1f / gain)
        glUniform2f(c.u("uRes"), outW.toFloat(), outH.toFloat())
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glActiveTexture(GL_TEXTURE0)
    }

    /** Slow orbit, pushes in with the voice and while listening, parallax with the hand; the finger's ray hits the disc plane. */
    private fun camera(t: Float, dt: Float) {
        val s = state
        val orb = t * 0.05f + s.px * 0.25f
        val dist = 2.9f - s.amp * 0.25f - s.listen * 0.15f
        val el = 1.35f + s.py * 0.2f + s.pitch * 0.1f
        eye[0] = sin(orb) * dist; eye[1] = el; eye[2] = cos(orb) * dist
        lookAt(view, eye, 0f, -0.12f, 0f)
        mul(vp, proj, view)
        if (s.touchDown) {
            val th = tan(FOV / 2)
            val dx = s.touchX * th * aspect
            val dy = s.touchY * th
            val rx = right[0] * dx + up[0] * dy - back[0]
            val ry = right[1] * dx + up[1] * dy - back[1]
            val rz = right[2] * dx + up[2] * dy - back[2]
            if (ry < -1e-3f) {
                val k = -eye[1] / ry
                var x = eye[0] + rx * k
                var z = eye[2] + rz * k
                val l = hypot(x, z)
                if (l > 1.6f) { x *= 1.6f / l; z *= 1.6f / l }
                if (s.touch < 0.05f) { s.touchWx = x; s.touchWz = z }   // a new touch lands where the finger is
                else { s.touchWx = SceneState.ease(s.touchWx, x, 0.4f, dt); s.touchWz = SceneState.ease(s.touchWz, z, 0.4f, dt) }
            }
        }
    }

    private fun blur(src: SceneTarget, a: SceneTarget, b: SceneTarget, rad: Float) {
        val p = pBlur
        glUseProgram(p.id)
        glUniform1i(p.u("uSrc"), 0)
        glActiveTexture(GL_TEXTURE0)
        glBindFramebuffer(GL_FRAMEBUFFER, a.fbo)
        glViewport(0, 0, a.w, a.h)
        glBindTexture(GL_TEXTURE_2D, src.tex)
        glUniform2f(p.u("uDir"), rad / a.w, 0f)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindFramebuffer(GL_FRAMEBUFFER, b.fbo)
        glBindTexture(GL_TEXTURE_2D, a.tex)
        glUniform2f(p.u("uDir"), 0f, rad / a.h)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    // ---- geometry ---------------------------------------------------------------------------------

    /** A G×G grid over ±EXT; the height comes from the vertex shader. */
    private fun buildTerrain() {
        val verts = FloatArray(G * G * 2)
        var k = 0
        for (j in 0 until G) for (i in 0 until G) {
            verts[k++] = (i / (G - 1f) - 0.5f) * 2 * EXT
            verts[k++] = (j / (G - 1f) - 0.5f) * 2 * EXT
        }
        val idx = IntArray((G - 1) * (G - 1) * 6)
        k = 0
        for (j in 0 until G - 1) for (i in 0 until G - 1) {
            val a = j * G + i
            idx[k++] = a; idx[k++] = a + 1; idx[k++] = a + G
            idx[k++] = a + 1; idx[k++] = a + G + 1; idx[k++] = a + G
        }
        terrIndices = idx.size
        val ids = IntArray(2)
        glGenVertexArrays(1, ids, 0)
        terrVao = ids[0]
        glBindVertexArray(terrVao)
        glGenBuffers(2, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, verts.size * 4, verts.toBuffer(), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ids[1])
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, idx.toBuffer(), GL_STATIC_DRAW)
        glBindVertexArray(0)
    }

    /** Six rings of ticks, the core disc, eight ribbons. Seeded, so the scene is the same at every launch. */
    private fun buildPoints() {
        val rnd = Random(SEED)
        val n = PER.sum() + CORE + RIB * RIBN
        val a = FloatArray(n * 4)   // angle, radius, y0, seed
        val b = FloatArray(n * 4)   // ring, u, kind, ribbon
        var o = 0
        RINGS.forEachIndexed { ri, r ->
            repeat(PER[ri]) {
                a[o] = rnd.nextFloat() * TAU
                a[o + 1] = r + (rnd.nextFloat() - 0.5f) * 0.02f * (1 + ri * 0.4f)
                a[o + 2] = (rnd.nextFloat() - 0.5f) * 0.015f
                a[o + 3] = rnd.nextFloat()
                b[o] = ri.toFloat()
                b[o + 2] = if (rnd.nextFloat() < 0.06f) 1f else 0f
                o += 4
            }
        }
        repeat(CORE) {
            a[o] = rnd.nextFloat() * TAU
            a[o + 1] = sqrt(rnd.nextFloat())   // uniform over the disc; scaled by the budget margin in the shader
            a[o + 2] = (rnd.nextFloat() - 0.5f) * 0.02f
            a[o + 3] = rnd.nextFloat()
            b[o] = 6f
            b[o + 2] = if (rnd.nextFloat() < 0.06f) 1f else 0f
            o += 4
        }
        for (r in 0 until RIB) for (i in 0 until RIBN) {
            a[o + 3] = rnd.nextFloat()
            b[o] = 9f
            b[o + 1] = i / RIBN.toFloat() + (rnd.nextFloat() - 0.5f) * 0.002f
            b[o + 3] = r.toFloat()
            o += 4
        }
        pointCount = n
        val ids = IntArray(2)
        glGenVertexArrays(1, ids, 0)
        ptVao = ids[0]
        glBindVertexArray(ptVao)
        glGenBuffers(2, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, a.size * 4, a.toBuffer(), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 4, GL_FLOAT, false, 0, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[1])
        glBufferData(GL_ARRAY_BUFFER, b.size * 4, b.toBuffer(), GL_STATIC_DRAW)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, 0, 0)
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    // ---- matrices (column-major, like the mockup) ------------------------------------------------

    private fun perspective(out: FloatArray, fov: Float, a: Float, n: Float, f: Float) {
        val t = 1f / tan(fov / 2)
        val nf = 1f / (n - f)
        out.fill(0f)
        out[0] = t / a; out[5] = t; out[10] = (f + n) * nf; out[11] = -1f; out[14] = 2 * f * n * nf
    }

    /** View matrix looking from e at (cx, cy, cz); also keeps the camera basis for the touch ray. */
    private fun lookAt(out: FloatArray, e: FloatArray, cx: Float, cy: Float, cz: Float) {
        var zx = e[0] - cx; var zy = e[1] - cy; var zz = e[2] - cz
        val zl = sqrt(zx * zx + zy * zy + zz * zz)
        zx /= zl; zy /= zl; zz /= zl
        var xx = zz; var xz = -zx
        val xl = sqrt(xx * xx + xz * xz)
        xx /= xl; xz /= xl
        val yx = zy * xz; val yy = zz * xx - zx * xz; val yz = -zy * xx
        right[0] = xx; right[1] = 0f; right[2] = xz
        up[0] = yx; up[1] = yy; up[2] = yz
        back[0] = zx; back[1] = zy; back[2] = zz
        out[0] = xx; out[1] = yx; out[2] = zx; out[3] = 0f
        out[4] = 0f; out[5] = yy; out[6] = zy; out[7] = 0f
        out[8] = xz; out[9] = yz; out[10] = zz; out[11] = 0f
        out[12] = -(xx * e[0] + xz * e[2])
        out[13] = -(yx * e[0] + yy * e[1] + yz * e[2])
        out[14] = -(zx * e[0] + zy * e[1] + zz * e[2])
        out[15] = 1f
    }

    private fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (i in 0 until 4) s += a[i * 4 + r] * b[c * 4 + i]
            out[c * 4 + r] = s
        }
    }

    companion object {
        const val FOV = 0.72f
        const val G = 140
        const val EXT = 2.4f
        val RINGS = floatArrayOf(0.30f, 0.46f, 0.64f, 0.82f, 1.0f, 1.18f)
        val PER = intArrayOf(1400, 1800, 2600, 3000, 3600, 4000)
        const val CORE = 1500
        const val RIB = 8
        const val RIBN = 2600
        const val SEED = 1971
        const val TAU = 6.2831853f
        /** On RGBA8 targets the scene is written at this gain and the composite multiplies it back. */
        const val LDR_GAIN = 1f / 3f

        private fun FloatArray.toBuffer() =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(this); it.position(0) }

        private fun IntArray.toBuffer() =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().also { it.put(this); it.position(0) }
    }
}
