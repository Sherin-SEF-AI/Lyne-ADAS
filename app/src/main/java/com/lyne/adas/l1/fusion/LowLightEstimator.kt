package com.lyne.adas.l1.fusion

import kotlin.math.exp

/**
 * Decides "the scene is dark" (night driving) from the per-frame mean luminance of the camera image.
 *
 * The luma is smoothed with a time-based EMA ([TAU_SEC], so the result doesn't depend on frame rate)
 * and switched with hysteresis plus a [DWELL_SEC] hold: a passing street light, an oncoming car's headlights or a short
 * tunnel must not toggle the mode. Phone auto-exposure lifts night scenes, so the thresholds sit well
 * above "black"; typical daylight frames read ~0.35-0.6.
 */
class LowLightEstimator(
    private val enterBelow: Float = ENTER_BELOW,
    private val exitAbove: Float = EXIT_ABOVE,
) {
    var lowLight = false; private set
    /** Smoothed mean luma (0..1) or NaN before the first sample. */
    var smoothed = Float.NaN; private set
    private var lastNs = Long.MIN_VALUE

    /** Fold in one frame's mean luma (NaN = unknown, ignored). Returns the current [lowLight]. */
    fun update(meanLuma: Float, nowNs: Long): Boolean {
        if (meanLuma.isNaN()) return lowLight
        if (smoothed.isNaN() || lastNs == Long.MIN_VALUE) {
            smoothed = meanLuma
        } else {
            val dt = ((nowNs - lastNs) / 1e9f).coerceIn(0f, 2f)
            val a = 1f - exp(-dt / TAU_SEC)
            smoothed += a * (meanLuma - smoothed)
        }
        lastNs = nowNs
        // Switch only after the smoothed value has stayed past the threshold for DWELL_SEC.
        val wantsSwitch = if (lowLight) smoothed > exitAbove else smoothed < enterBelow
        if (!wantsSwitch) {
            pendingSinceNs = Long.MIN_VALUE
        } else {
            if (pendingSinceNs == Long.MIN_VALUE) pendingSinceNs = nowNs
            if (nowNs - pendingSinceNs >= (DWELL_SEC * 1e9f).toLong()) { lowLight = !lowLight; pendingSinceNs = Long.MIN_VALUE }
        }
        return lowLight
    }

    private var pendingSinceNs = Long.MIN_VALUE

    fun reset() { lowLight = false; smoothed = Float.NaN; lastNs = Long.MIN_VALUE; pendingSinceNs = Long.MIN_VALUE }

    companion object {
        const val ENTER_BELOW = 0.22f
        const val EXIT_ABOVE = 0.30f
        const val TAU_SEC = 2.0f
        const val DWELL_SEC = 2.0f
        /** Earlier FCW caution in the dark: detection lags and the driver sees less. */
        const val TTC_CAUTION_ADD_SEC = 0.3f
    }
}
