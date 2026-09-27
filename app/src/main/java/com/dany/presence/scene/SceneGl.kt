package com.dany.presence.scene

import android.content.res.AssetManager
import android.opengl.GLES30.GL_CLAMP_TO_EDGE
import android.opengl.GLES30.GL_COLOR_ATTACHMENT0
import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_COMPILE_STATUS
import android.opengl.GLES30.GL_FRAGMENT_SHADER
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_FRAMEBUFFER_COMPLETE
import android.opengl.GLES30.GL_HALF_FLOAT
import android.opengl.GLES30.GL_LINEAR
import android.opengl.GLES30.GL_LINK_STATUS
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_RGBA16F
import android.opengl.GLES30.GL_RGBA8
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES30.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES30.GL_TEXTURE_WRAP_S
import android.opengl.GLES30.GL_TEXTURE_WRAP_T
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.GL_VERTEX_SHADER
import android.opengl.GLES30.glAttachShader
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glCheckFramebufferStatus
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glCompileShader
import android.opengl.GLES30.glCreateProgram
import android.opengl.GLES30.glCreateShader
import android.opengl.GLES30.glDeleteFramebuffers
import android.opengl.GLES30.glDeleteShader
import android.opengl.GLES30.glDeleteTextures
import android.opengl.GLES30.glFramebufferTexture2D
import android.opengl.GLES30.glGenFramebuffers
import android.opengl.GLES30.glGenTextures
import android.opengl.GLES30.glGetProgramInfoLog
import android.opengl.GLES30.glGetProgramiv
import android.opengl.GLES30.glGetShaderInfoLog
import android.opengl.GLES30.glGetShaderiv
import android.opengl.GLES30.glGetUniformLocation
import android.opengl.GLES30.glLinkProgram
import android.opengl.GLES30.glShaderSource
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glTexParameteri
import android.util.Log

/** A linked vertex + fragment program from assets/shaders/. `#include "file"` lines are expanded from the same folder. */
internal class SceneProgram(assets: AssetManager, vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GL_VERTEX_SHADER, vs, load(assets, vs))
        val f = compile(GL_FRAGMENT_SHADER, fs, load(assets, fs))
        id = glCreateProgram()
        glAttachShader(id, v)
        glAttachShader(id, f)
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link $vs/$fs: ${glGetProgramInfoLog(id)}" }
        glDeleteShader(v)
        glDeleteShader(f)
    }

    /** Uniform location, cached. Arrays are addressed by their bare name. */
    fun u(name: String): Int = locs.getOrPut(name) { glGetUniformLocation(id, name) }

    private fun compile(type: Int, name: String, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e("Scene", "$name: $log")
            error("shader $name: $log")
        }
        return s
    }

    companion object {
        private val include = Regex("""^[ \t]*#include[ \t]+"([^"]+)"[ \t]*$""", RegexOption.MULTILINE)

        fun load(assets: AssetManager, name: String): String {
            val src = assets.open("shaders/$name").bufferedReader().use { it.readText() }
            return include.replace(src) { m -> load(assets, m.groupValues[1]) }
        }
    }
}

/** Colour render target. Half-float when the GPU can render to it, RGBA8 otherwise. */
internal class SceneTarget(val w: Int, val h: Int, hdr: Boolean) {
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
