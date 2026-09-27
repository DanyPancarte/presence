package com.dany.presence.scene

import android.os.SystemClock
import com.dany.presence.core.Signal
import com.dany.presence.core.World
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * What the scene reacts to.
 *
 * Targets are written by the bus and world collectors (any thread) into @Volatile fields. The smoothed values
 * are owned by the GL thread and eased every frame, like the mockup's `ease()`: nothing on screen ever cuts.
 */
class SceneState {
    // ---- targets (any thread) -----------------------------------------------------------------
    @Volatile var ampT = 0f
    @Volatile var pitchT = 0f
    @Volatile var bandsT = FloatArray(8)
    /** Last Level, ms. Without one for 400 ms the voice is considered gone (mic closed while speaking). */
    @Volatile var levelAt = 0L
    @Volatile var listenT = 0f
    @Volatile var thinkingUntil = 0L
    @Volatile var heatPulse = 0f
    @Volatile var heatUntil = 0L
    /** Set to 1 by a transient; consumed by the GL thread. */
    @Volatile var shockT = 0f
    @Volatile var alertUntil = 0L
    @Volatile var speaking = false
    @Volatile var voiceUntil = 0L
    @Volatile var touchX = 0f
    @Volatile var touchY = 0f
    @Volatile var touchDown = false
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    /** 8 × (start angle, length, progress, is97) for ring 2. Unused slots have length 0. */
    @Volatile var segT = FloatArray(32)
    @Volatile var medT = 0f
    @Volatile var marginT = 1f
    @Volatile var eventsT = 0f

    // ---- smoothed (GL thread) -----------------------------------------------------------------
    var amp = 0f
    var pitch = 0f
    val bands = FloatArray(8)
    var heat = 0f
    var shock = 0f
    var alert = 0f
    var listen = 0f
    var voice = 0f
    var px = 0f
    var py = 0f
    var med = 0f
    var margin = 1f
    var events = 0f
    val seg = FloatArray(32)
    /** Finger pressure 0..1 and its point on the disc plane (world x, z). */
    var touch = 0f
    var touchWx = 0f
    var touchWz = 0f
    /** Integrated phases: rings spin faster while listening, ribbons flow faster with the voice, with no jump. */
    var spin = 0f
    var flow = 0f

    /** Feed one signal from the bus. */
    fun on(s: Signal) {
        val now = SystemClock.elapsedRealtime()
        when (s) {
            is Signal.Level -> {
                ampT = s.amp.coerceIn(0f, 1f)
                pitchT = s.pitch.coerceIn(-1f, 1f)
                val b = FloatArray(8)
                for (i in 0 until minOf(8, s.bands.size)) b[i] = s.bands[i].coerceIn(0f, 1f)
                bandsT = b
                levelAt = now
            }
            Signal.SpeechStart -> listenT = 1f
            Signal.SpeechIdle, is Signal.SpeechFinal -> listenT = 0f
            is Signal.Captured -> { heatPulse = 1f; heatUntil = now + 400 }
            is Signal.Thinking -> thinkingUntil = now + 8_000
            is Signal.Executed -> { heatPulse = 1f; heatUntil = now + 2_600; shockT = 1f; thinkingUntil = 0L }
            is Signal.Alert -> { alertUntil = now + 3_000; thinkingUntil = 0L }
            is Signal.Failed -> thinkingUntil = 0L
            is Signal.Said -> { voiceUntil = now + 2_500; thinkingUntil = 0L }
            is Signal.Proactive -> voiceUntil = now + 2_500
            is Signal.Speaking -> { speaking = s.on; if (!s.on) voiceUntil = 0L }
            is Signal.Touch -> { touchX = s.x.coerceIn(-1f, 1f); touchY = s.y.coerceIn(-1f, 1f); touchDown = s.down }
            is Signal.Tilt -> { tiltX = s.x.coerceIn(-1f, 1f); tiltY = s.y.coerceIn(-1f, 1f) }
            else -> Unit
        }
    }

    /** Feed a snapshot of the world: tasks → ring 2 segments, med → ring 1 gap, budget → core radius, events → ring 4. */
    fun on(w: World) {
        val tasks = w.tasks.filter { !it.fini }.take(8)
        val prev = segT
        val arr = FloatArray(32)
        val step = (2 * PI / maxOf(tasks.size, 1)).toFloat()
        for (i in 0 until 8) {
            val o = i * 4
            if (i < tasks.size) {
                val t = tasks[i]
                arr[o] = i * step + 0.06f
                arr[o + 1] = step - 0.12f
                arr[o + 2] = (t.avancement / 100f).coerceIn(0f, 1f)
                arr[o + 3] = if (t.avancement in 90..99) 1f else 0f
            } else {
                // a task that left: its arc shrinks to nothing where it was
                arr[o] = prev[o]; arr[o + 1] = 0f; arr[o + 2] = prev[o + 2]; arr[o + 3] = prev[o + 3]
            }
        }
        segT = arr
        medT = if (w.medTakenAt != null) 1f else 0f
        marginT = if (w.budgetTotal > 0) ((w.budgetTotal - w.spent) / w.budgetTotal).toFloat().coerceIn(0f, 1f) else 0f
        eventsT = w.events.size.coerceAtMost(6) / 6f
    }

    /** One frame of smoothing. The k factors are the mockup's per-frame values at 60 fps, made frame-rate independent. */
    fun step(dt: Float) {
        val now = SystemClock.elapsedRealtime()
        val live = now - levelAt < 400
        val aT = if (live) ampT else 0f
        amp = ease(amp, aT, if (aT > amp) 0.35f else 0.05f, dt)
        pitch = ease(pitch, if (live) pitchT else 0f, 0.06f, dt)
        val bT = bandsT
        for (i in 0 until 8) {
            val tg = if (live) bT[i] else 0f
            bands[i] = ease(bands[i], tg, if (tg > bands[i]) 0.5f else 0.08f, dt)
        }
        val heatT = maxOf(if (now < heatUntil) heatPulse else 0f, if (now < thinkingUntil) 0.6f else 0f)
        heat = ease(heat, heatT, 0.08f, dt)
        if (shockT > 0f) { shock = maxOf(shock, shockT); shockT = 0f }
        shock *= exp(-dt * 2.2f)
        alert = ease(alert, if (now < alertUntil) 1f else 0f, 0.08f, dt)
        listen = ease(listen, listenT, 0.05f, dt)
        voice = ease(voice, if (speaking || now < voiceUntil) 1f else 0f, 0.08f, dt)
        px = ease(px, tiltX, 0.03f, dt)
        py = ease(py, tiltY, 0.03f, dt)
        med = ease(med, medT, 0.03f, dt)
        margin = ease(margin, marginT, 0.03f, dt)
        events = ease(events, eventsT, 0.03f, dt)
        val sT = segT
        for (i in 0 until 32) seg[i] = ease(seg[i], sT[i], 0.05f, dt)
        val tT = if (touchDown) 1f else 0f
        touch = ease(touch, tT, if (tT > touch) 0.35f else 0.06f, dt)
        spin += dt * (1f + listen * 0.8f)
        flow += dt * (1f + amp * 2f)
    }

    /** The voice, as an amp-like pulse for the core and the disc. */
    fun voicePulse(t: Float): Float = voice * (0.55f + 0.45f * sin(t * 7f))

    companion object {
        /** Exponential ease with per-frame factor k at 60 fps, independent of the actual frame time. */
        fun ease(a: Float, b: Float, k: Float, dt: Float): Float = a + (b - a) * (1f - (1f - k).pow(dt * 60f))
    }
}
