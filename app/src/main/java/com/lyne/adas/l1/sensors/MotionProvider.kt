package com.lyne.adas.l1.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.lyne.adas.l1.calibration.MountEstimator
import com.lyne.adas.l1.logging.Log
import kotlin.math.sqrt

/**
 * IMU source, independent of how the phone is held:
 *  - gravity direction (TYPE_GRAVITY when available, otherwise a low-passed accelerometer)
 *  - vehicle yaw rate = gyro projected onto the gravity "up" axis. (Reading a fixed device axis is
 *    wrong for a windshield mount: device Z points at the driver, so it measures roll, not yaw.)
 *  - mount pitch/roll via [MountEstimator], in screen axes resolved from the display rotation.
 * Degrades gracefully when a sensor is missing.
 */
class MotionProvider(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val mount = MountEstimator()

    /** Yaw rate about world vertical, rad/s, + = turning left. Low-pass filtered. */
    @Volatile var yawRate: Float = 0f
        private set
    val available: Boolean get() = accel != null || gyro != null
    val hasGyro: Boolean get() = gyro != null

    /** Latest GPS longitudinal acceleration (m/s^2), fed by the controller; NaN if unknown. */
    @Volatile var longAccel: Float = Float.NaN

    private val g = FloatArray(3)            // gravity, device axes
    private val lin = FloatArray(3)          // raw accel, device axes
    private var gInit = false
    private var gyroMag = 0f
    private var lastMountNs = 0L
    private var rotation = Surface.ROTATION_90

    fun start() {
        rotation = displayRotation()
        gInit = false
        gravitySensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (!available) Log.i(TAG, "no IMU sensors; mount pitch defaults, yaw-path disabled")
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { setG(e.values, 1f); updateMount(e.timestamp) }
            Sensor.TYPE_ACCELEROMETER -> {
                lin[0] = e.values[0]; lin[1] = e.values[1]; lin[2] = e.values[2]
                if (gravitySensor == null) { setG(e.values, if (gInit) 0.05f else 1f); updateMount(e.timestamp) }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val wx = e.values[0]; val wy = e.values[1]; val wz = e.values[2]
                gyroMag = sqrt(wx * wx + wy * wy + wz * wz)
                val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (n > 1f) {
                    val yaw = (wx * g[0] + wy * g[1] + wz * g[2]) / n
                    yawRate += 0.2f * (yaw - yawRate)
                }
            }
        }
    }

    private fun setG(v: FloatArray, a: Float) {
        if (!gInit) { g[0] = v[0]; g[1] = v[1]; g[2] = v[2]; gInit = true; return }
        g[0] += a * (v[0] - g[0]); g[1] += a * (v[1] - g[1]); g[2] += a * (v[2] - g[2])
    }

    private fun updateMount(tsNs: Long) {
        val dt = if (lastMountNs == 0L) 0f else (tsNs - lastMountNs) / 1e9f
        lastMountNs = tsNs
        // Device axes -> screen axes (right, up). Toward-driver is device +Z for every rotation.
        val (right, up) = when (rotation) {
            Surface.ROTATION_90 -> -g[1] to g[0]
            Surface.ROTATION_270 -> g[1] to -g[0]
            Surface.ROTATION_180 -> -g[0] to -g[1]
            else -> g[0] to g[1]
        }
        // Magnitude gate uses the raw accelerometer (TYPE_GRAVITY is always ~g by construction).
        val mag = if (gravitySensor != null && accel != null) sqrt(lin[0] * lin[0] + lin[1] * lin[1] + lin[2] * lin[2]) else Float.NaN
        val gz = g[2]
        val scale = if (!mag.isNaN() && mag > 1f) 9.81f / sqrt(right * right + up * up + gz * gz).coerceAtLeast(1f) else 1f
        val quietMagOk = mag.isNaN() || kotlin.math.abs(mag - 9.81f) < 0.6f
        mount.update(right * scale, up * scale, gz * scale, if (quietMagOk) gyroMag else 9f, longAccel, dt)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        if (Build.VERSION.SDK_INT >= 30) context.display?.rotation ?: Surface.ROTATION_90
        else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    } catch (_: Throwable) { Surface.ROTATION_90 }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object { private const val TAG = "MotionProvider" }
}
