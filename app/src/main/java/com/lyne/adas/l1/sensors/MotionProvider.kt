package com.lyne.adas.l1.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.lyne.adas.l1.logging.Log

/**
 * IMU source for the LDW lateral-velocity heuristic (turn-indicator state is unavailable on a
 * phone). Exposes a lightly low-pass-filtered lateral acceleration and yaw rate. Assumes the phone
 * is mounted in landscape on the windshield; see README for the mounting/axis convention.
 * Degrades gracefully when a sensor is missing.
 */
class MotionProvider(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Across-vehicle acceleration (device X in landscape), m/s^2, low-pass filtered. */
    @Volatile var lateralAccel: Float = 0f
        private set
    /** Yaw rate around the vertical axis (device Z), rad/s, low-pass filtered. */
    @Volatile var yawRate: Float = 0f
        private set
    val available: Boolean get() = accel != null || gyro != null

    private val a = 0.2f // low-pass factor

    fun start() {
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (!available) Log.i(TAG, "no IMU sensors; LDW heuristic uses lane-offset rate only")
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> lateralAccel += a * (e.values[0] - lateralAccel)
            Sensor.TYPE_GYROSCOPE -> yawRate += a * (e.values[2] - yawRate)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object { private const val TAG = "MotionProvider" }
}
