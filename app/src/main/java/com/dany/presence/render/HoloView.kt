package com.dany.presence.render

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView

@SuppressLint("ViewConstructor")
class HoloView(context: Context, val state: HoloState) : GLSurfaceView(context), SensorEventListener {
    val renderer = HoloRenderer(context, state)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var baseX = Float.NaN
    private var baseY = Float.NaN
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        val pitch = ori[1]; val roll = ori[2]
        if (baseX.isNaN()) { baseX = roll; baseY = pitch }
        // Slowly re-centre so the parallax follows the hand, not the absolute pose.
        baseX += (roll - baseX) * 0.01f
        baseY += (pitch - baseY) * 0.01f
        state.parallaxTX = ((roll - baseX) * 1.4f).coerceIn(-0.5f, 0.5f)
        state.parallaxTY = ((pitch - baseY) * 1.4f).coerceIn(-0.5f, 0.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
