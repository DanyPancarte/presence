package com.dany.presence.render

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView

@SuppressLint("ViewConstructor")
class SphereView(context: Context, val state: SphereState) : GLSurfaceView(context), SensorEventListener {
    val renderer = SphereRenderer(context, state)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var baseX = Float.NaN
    private var baseY = Float.NaN

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        super.onPause()
    }

    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        val pitch = ori[1]; val roll = ori[2]
        if (baseX.isNaN()) { baseX = roll; baseY = pitch }
        // Slowly re-centre so the parallax follows the hand, not the absolute pose.
        baseX += (roll - baseX) * 0.01f
        baseY += (pitch - baseY) * 0.01f
        val k = 0.35f
        val tx = ((roll - baseX) * k).coerceIn(-0.12f, 0.12f)
        val ty = ((pitch - baseY) * k).coerceIn(-0.12f, 0.12f)
        state.parallaxX += (tx - state.parallaxX) * 0.15f
        state.parallaxY += (ty - state.parallaxY) * 0.15f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
