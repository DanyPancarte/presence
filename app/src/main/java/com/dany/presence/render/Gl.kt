package com.dany.presence.render

import android.content.res.AssetManager
import android.opengl.GLES30.*
import android.util.Log

class GlProgram(assets: AssetManager, vs: String, fs: String, feedback: Array<String>? = null) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GL_VERTEX_SHADER, assets.read("shaders/$vs"))
        val f = compile(GL_FRAGMENT_SHADER, assets.read("shaders/$fs"))
        id = glCreateProgram()
        glAttachShader(id, v)
        glAttachShader(id, f)
        if (feedback != null) glTransformFeedbackVaryings(id, feedback, GL_INTERLEAVED_ATTRIBS)
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link $vs/$fs: ${glGetProgramInfoLog(id)}" }
        glDeleteShader(v); glDeleteShader(f)
    }

    fun u(name: String): Int = locs.getOrPut(name) { glGetUniformLocation(id, name) }

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e("Presence", log)
            error("shader: $log")
        }
        return s
    }

    private fun AssetManager.read(p: String) = open(p).bufferedReader().use { it.readText() }
}

/** Color render target. Half-float when the GPU can render to it, RGBA8 otherwise. */
class Target(val w: Int, val h: Int, hdr: Boolean) {
    val tex: Int
    val fbo: Int

    init {
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        tex = t[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        if (hdr) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, null)
        else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        glGenFramebuffers(1, f, 0)
        fbo = f[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    val complete: Boolean
        get() {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
        }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(tex), 0)
    }
}
