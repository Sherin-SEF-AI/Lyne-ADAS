package com.lyne.adas.l1.calibration

import android.content.Context

/**
 * Persists per-device calibration: an effective horizontal FoV (the single most important number
 * for monocular distance) plus an optional empirical scale correction from the known-width flow.
 * Stored in SharedPreferences; keyed only to this install so a device swap re-calibrates.
 */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("lyne_calib", Context.MODE_PRIVATE)

    var fovOverrideDeg: Float?
        get() = prefs.getFloat(KEY_FOV, Float.NaN).takeIf { !it.isNaN() }
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_FOV) else putFloat(KEY_FOV, v) }.apply()

    /** Multiplicative correction applied to distance estimates (1.0 = none). */
    var distanceScale: Float
        get() = prefs.getFloat(KEY_SCALE, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_SCALE, v.coerceIn(0.3f, 3f)).apply()

    /** Normalized horizon row [0,1], set by the horizon-tap flow; default mid-frame. */
    var horizonY: Float
        get() = prefs.getFloat(KEY_HORIZON, 0.5f)
        set(v) = prefs.edit().putFloat(KEY_HORIZON, v.coerceIn(0.2f, 0.8f)).apply()

    val isCalibrated: Boolean get() = fovOverrideDeg != null || prefs.contains(KEY_SCALE)

    fun effectiveIntrinsics(base: CameraIntrinsics): CameraIntrinsics {
        val fov = fovOverrideDeg ?: return base
        return base.copy(horizontalFovDeg = fov, fromDevice = base.fromDevice)
    }

    companion object {
        private const val KEY_FOV = "fov_deg"
        private const val KEY_SCALE = "dist_scale"
        private const val KEY_HORIZON = "horizon_y"
    }
}
