package com.lyne.adas.l1.perf

/** A small, non-intrusive HUD hint about the phone itself (not the road). */
enum class DeviceHint(val text: String) {
    PLUG_IN("Plug in charger"),
    PHONE_HOT("Phone hot — use a vent mount, keep out of sun"),
    LOCATION_OFF("Location is off — turn it on for speed-aware warnings"),
    NO_GPS("No GPS speed — warnings are limited"),
}

/** Speed source health for the driver hint. */
enum class GpsState { OK, OFF, NO_PERMISSION, NO_FIX }

/**
 * Pure policy for battery and heat guidance chips. Battery below [LOW_BATTERY_PCT] while not
 * charging asks for a charger (a long drive with camera + inference drains a phone fast); thermal
 * status at or above SEVERE asks the driver to improve cooling (the pipeline is already shedding
 * frames at that point, which degrades warnings).
 */
object DeviceGuidance {
    const val LOW_BATTERY_PCT = 30

    /** Android PowerManager.THERMAL_STATUS_SEVERE. */
    const val THERMAL_SEVERE = 3

    fun hints(batteryPct: Int?, charging: Boolean?, thermalStatus: Int, gps: GpsState = GpsState.OK): List<DeviceHint> {
        val out = ArrayList<DeviceHint>(3)
        // Without speed, FCW/VRU can't tell oncoming or passing traffic from a hazard and LDW is off.
        when (gps) {
            GpsState.OFF, GpsState.NO_PERMISSION -> out += DeviceHint.LOCATION_OFF
            GpsState.NO_FIX -> out += DeviceHint.NO_GPS
            GpsState.OK -> {}
        }
        if (thermalStatus >= THERMAL_SEVERE) out += DeviceHint.PHONE_HOT
        if (batteryPct != null && batteryPct in 0 until LOW_BATTERY_PCT && charging == false) out += DeviceHint.PLUG_IN
        return out
    }

    /**
     * Maps the ThermalManager label (as stored in PerfMonitor.thermal) back to the PowerManager
     * status ordinal, so this policy does not depend on Android classes. Unknown labels -> 0.
     */
    fun thermalStatusFromLabel(label: String): Int = when (label.lowercase()) {
        "light" -> 1
        "moderate" -> 2
        "severe" -> 3
        "critical" -> 4
        "emergency" -> 5
        "shutdown" -> 6
        else -> 0
    }
}
