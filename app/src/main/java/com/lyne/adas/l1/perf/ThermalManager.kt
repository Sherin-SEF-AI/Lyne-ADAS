package com.lyne.adas.l1.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.lyne.adas.l1.logging.Log

/**
 * Watches the OS thermal status (API 29+) and maps it to a cadence multiplier so the pipeline sheds
 * work as the device heats up, then recovers when it cools. On older devices this is a no-op (x1).
 */
class ThermalManager(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start(onChange: (multiplier: Int, label: String) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) { onChange(1, "n/a"); return }
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            val (mult, label) = map(status)
            Log.i(TAG, "thermal=$label -> cadence x$mult")
            onChange(mult, label)
        }
        listener = l
        power.addThermalStatusListener(l)
        val (mult, label) = map(power.currentThermalStatus)
        onChange(mult, label)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 29) listener?.let { power.removeThermalStatusListener(it) }
        listener = null
    }

    private fun map(status: Int): Pair<Int, String> = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> 1 to "none"
        PowerManager.THERMAL_STATUS_LIGHT -> 1 to "light"
        PowerManager.THERMAL_STATUS_MODERATE -> 2 to "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> 3 to "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> 4 to "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> 4 to "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> 4 to "shutdown"
        else -> 1 to "none"
    }

    companion object { private const val TAG = "ThermalManager" }
}
