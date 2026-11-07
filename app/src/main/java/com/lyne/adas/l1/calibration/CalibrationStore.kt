package com.lyne.adas.l1.calibration

import android.content.Context

/** Vehicle-type presets for the camera mount height above the road (metres). */
enum class VehiclePreset(val label: String, val cameraHeightM: Float) {
    HATCHBACK("Hatchback", 1.18f),
    SEDAN("Sedan", 1.25f),
    SUV("SUV / Crossover", 1.45f),
    PICKUP("Pickup / Van", 1.65f),
    TRUCK("Truck / Bus", 2.3f),
}

/**
 * Persists per-device calibration: effective horizontal FoV override, camera mount height, a manual
 * known-gap distance scale, and the online auto-calibration state (learned height scale, last mount
 * pitch/roll). Stored in SharedPreferences; keyed to this install so a device swap re-calibrates.
 */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("lyne_calib", Context.MODE_PRIVATE)

    var fovOverrideDeg: Float?
        get() = prefs.getFloat(KEY_FOV, Float.NaN).takeIf { !it.isNaN() }
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_FOV) else putFloat(KEY_FOV, v.coerceIn(40f, 110f)) }.apply()

    /** Multiplicative correction applied to final distance estimates (1.0 = none). */
    var distanceScale: Float
        get() = prefs.getFloat(KEY_SCALE, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_SCALE, v.coerceIn(0.5f, 2f)).apply()

    var cameraHeightM: Float
        get() = prefs.getFloat(KEY_HEIGHT, CameraGeometry.DEFAULT_CAMERA_HEIGHT_M)
        set(v) = prefs.edit().putFloat(KEY_HEIGHT, v.coerceIn(0.6f, 3.5f)).apply()

    var vehiclePreset: VehiclePreset?
        get() = prefs.getString(KEY_PRESET, null)?.let { runCatching { VehiclePreset.valueOf(it) }.getOrNull() }
        set(v) = prefs.edit().putString(KEY_PRESET, v?.name).apply()

    /** Learned multiplier on camera height from traffic statistics (1 = none). */
    var heightScale: Float
        get() = prefs.getFloat(KEY_HSCALE, 1f)
        set(v) = prefs.edit().putFloat(KEY_HSCALE, v.coerceIn(0.7f, 1.4f)).apply()

    var autoCalEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOCAL, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOCAL, v).apply()

    var lastPitchRad: Float
        get() = prefs.getFloat(KEY_PITCH, Float.NaN)
        set(v) = prefs.edit().putFloat(KEY_PITCH, v).apply()

    var lastRollTan: Float
        get() = prefs.getFloat(KEY_ROLL, 0f)
        set(v) = prefs.edit().putFloat(KEY_ROLL, v).apply()

    val isCalibrated: Boolean
        get() = fovOverrideDeg != null || prefs.contains(KEY_SCALE) || prefs.contains(KEY_HEIGHT)

    fun effectiveIntrinsics(base: CameraIntrinsics): CameraIntrinsics {
        val fov = fovOverrideDeg ?: return base
        return base.copy(horizontalFovDeg = fov)
    }

    fun resetAutoCal() { prefs.edit().remove(KEY_HSCALE).apply() }

    companion object {
        private const val KEY_FOV = "fov_deg"
        private const val KEY_SCALE = "dist_scale"
        private const val KEY_HEIGHT = "cam_height"
        private const val KEY_PRESET = "vehicle_preset"
        private const val KEY_HSCALE = "height_scale"
        private const val KEY_AUTOCAL = "autocal"
        private const val KEY_PITCH = "mount_pitch"
        private const val KEY_ROLL = "mount_roll"
    }
}
