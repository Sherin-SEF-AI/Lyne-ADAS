package com.lyne.adas.l1.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.lyne.adas.l1.logging.Log

/**
 * Watches the OS thermal status (API 29+) and reports it (PowerManager.THERMAL_STATUS_* + label). What to
 * shed at each status is decided by [ThermalPolicy] in the inference scheduler. Older devices report NONE.
 */
class ThermalManager(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start(onChange: (status: Int, label: String) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) { onChange(ThermalPolicy.STATUS_NONE, "n/a"); return }
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            val label = ThermalPolicy.statusLabel(status)
            Log.i(TAG, "thermal=$label")
            onChange(status, label)
        }
        listener = l
        power.addThermalStatusListener(l)
        val status = power.currentThermalStatus
        onChange(status, ThermalPolicy.statusLabel(status))
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 29) listener?.let { power.removeThermalStatusListener(it) }
        listener = null
    }

    companion object { private const val TAG = "ThermalManager" }
}
