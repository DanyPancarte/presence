package com.dany.presence.scene

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import com.dany.presence.core.Bus
import com.dany.presence.core.Signal
import com.dany.presence.core.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The scene, full screen. Listens to the bus (voice levels, the mind's signals, touch, tilt) and to the world
 * (tasks, med, budget, events); owns the rotation sensor and emits [Signal.Tilt] for everyone.
 *
 * Call [onResume] / [onPause] from the activity. Collection starts when the view is attached to a window and
 * stops when it is detached; the GL thread reads the smoothed state in [SceneState].
 */
@SuppressLint("ViewConstructor")
class SceneView(
    context: Context,
    private val bus: Bus,
    private val world: StateFlow<World>,
    private val scope: CoroutineScope,
) : GLSurfaceView(context), SensorEventListener {
    val state = SceneState()
    internal val renderer = SceneRenderer(context, state)
    private val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val jobs = ArrayList<Job>(2)
    private var baseX = Float.NaN
    private var baseY = Float.NaN
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    /** Fraction of the surface resolution the scene is rendered at (0.72 by default). Takes effect on the next resize. */
    var renderScale: Float
        get() = renderer.renderScale
        set(v) { renderer.renderScale = v.coerceIn(0.25f, 1f) }

    /** Screen row (0 = bottom, 1 = top) that the tilt-shift keeps sharp. */
    var focus: Float
        get() = renderer.focus
        set(v) { renderer.focus = v.coerceIn(0f, 1f) }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        jobs += scope.launch { bus.signals.collect { state.on(it) } }
        jobs += scope.launch { world.collect { state.on(it) } }
    }

    override fun onDetachedFromWindow() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        super.onResume()
        val sm = sensors ?: return
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        val pitch = ori[1]
        val roll = ori[2]
        if (baseX.isNaN()) { baseX = roll; baseY = pitch }
        // Slowly re-centre so the parallax follows the hand, not the absolute pose.
        baseX += (roll - baseX) * 0.01f
        baseY += (pitch - baseY) * 0.01f
        val x = ((roll - baseX) * 2.8f).coerceIn(-1f, 1f)
        val y = ((pitch - baseY) * 2.8f).coerceIn(-1f, 1f)
        bus.emit(Signal.Tilt(x, y))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
