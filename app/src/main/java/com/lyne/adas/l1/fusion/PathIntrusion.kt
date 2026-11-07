package com.lyne.adas.l1.fusion

import kotlin.math.abs

/**
 * Metric ego-path corridor. Instead of a fixed band of the screen (which, at 40 m, includes cars
 * parked two lanes over), objects are placed in metres: lateral = u(x) * depth. The corridor follows
 * the predicted path: with yaw rate w and speed v the path curvature is k = w/v and its lateral
 * offset at depth d is -k d^2 / 2 (yaw > 0 = turning left = path bends left).
 */
object PathIntrusion {
    /** Ego vehicle half-width plus a small margin (m). */
    const val EGO_HALF_M = 0.95f

    fun pathCenterM(depthM: Float, speedMps: Float, yawRate: Float): Float {
        if (speedMps.isNaN() || speedMps < 3f || abs(yawRate) < 0.02f) return 0f
        val k = (yawRate / speedMps).coerceIn(-0.1f, 0.1f)
        return (-k * depthM * depthM / 2f).coerceIn(-6f, 6f)
    }

    /** Lateral overlap (m) between an object span and the corridor of half-width [halfM]. */
    fun overlapM(objLateralM: Float, objHalfWidthM: Float, pathCenterM: Float, halfM: Float): Float =
        minOf(objLateralM + objHalfWidthM, pathCenterM + halfM) - maxOf(objLateralM - objHalfWidthM, pathCenterM - halfM)

    /**
     * Vulnerable-road-user severity from range, time-to-reach at ego speed and corridor overlap.
     * Below walking pace (stopped at a crossing) it stays informational: no beeping at red lights.
     */
    fun vruSeverity(distanceM: Float, egoSpeedMps: Float, inEgo: Boolean, inWide: Boolean): Severity {
        if (!inWide || distanceM.isNaN()) return Severity.NONE
        val v = if (egoSpeedMps.isNaN()) 6f else egoSpeedMps
        if (v < 1.5f) return if (distanceM < 12f) Severity.INFO else Severity.NONE
        val ttr = distanceM / v
        return when {
            inEgo && (distanceM < 5f || ttr < 1.6f) -> Severity.CRITICAL
            (inEgo && (distanceM < 12f || ttr < 3.0f)) || (distanceM < 7f) -> Severity.CAUTION
            distanceM < 25f -> Severity.INFO
            else -> Severity.NONE
        }
    }
}
