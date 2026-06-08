package com.lyne.adas.l1.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.Calendar

/**
 * Ambient-light source for auto day/night. Reports [isNight] with hysteresis so the HUD doesn't
 * flicker at the threshold. Falls back to time-of-day when no light sensor exists.
 */
class LightSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

    @Volatile var isNight: Boolean = isNightByClock()
        private set
    val available: Boolean get() = sensor != null

    private var onChange: ((Boolean) -> Unit)? = null

    fun start(onChange: (Boolean) -> Unit) {
        this.onChange = onChange
        if (sensor != null) {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            onChange(isNightByClock())
        }
    }

    fun stop() { sm.unregisterListener(this); onChange = null }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0]
        val newNight = when {
            isNight && lux > DAY_LUX -> false   // bright enough to switch to day
            !isNight && lux < NIGHT_LUX -> true // dark enough to switch to night
            else -> isNight
        }
        if (newNight != isNight) { isNight = newNight; onChange?.invoke(newNight) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun isNightByClock(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h < 6 || h >= 19
    }

    companion object {
        private const val NIGHT_LUX = 12f   // below -> night
        private const val DAY_LUX = 60f     // above -> day (hysteresis gap)
    }
}
