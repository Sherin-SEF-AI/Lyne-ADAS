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
     * Vulnerable-road-user severity from range, the measured closing speed and corridor overlap.
     *
     * Time-to-reach uses the track's own closing speed (Kalman), not just ego speed: a pedestrian
     * whose range stays constant is walking with traffic or on the pavement and is not a collision
     * threat, while a real one in our path closes at roughly our speed. Without enough track history
     * the alert is capped at CAUTION. Below walking pace (stopped at a crossing) it stays informational.
     *
     * @param closingMps track closing speed (+ = approaching) or NaN while unknown
     */
    fun vruSeverity(distanceM: Float, egoSpeedMps: Float, closingMps: Float, inEgo: Boolean, inWide: Boolean): Severity {
        if (!inWide || distanceM.isNaN()) return Severity.NONE
        val v = if (egoSpeedMps.isNaN()) 6f else egoSpeedMps
        if (v < 1.5f) return if (distanceM < 12f) Severity.INFO else Severity.NONE
        if (closingMps.isNaN()) {
            // Young track: distance-only, never more than a caution.
            return when {
                inEgo && (distanceM < 10f || distanceM / v < 2.5f) -> Severity.CAUTION
                distanceM < 25f -> Severity.INFO
                else -> Severity.NONE
            }
        }
        // Not approaching, or "approaching" faster than physically possible (noise): informational.
        if (closingMps < maxOf(1.0f, 0.35f * v) || closingMps > v + 3f) return if (distanceM < 25f) Severity.INFO else Severity.NONE
        // Pedestrians/riders move at <= ~3 m/s, so once we are clearly closing on one, the true closing
        // speed is within ~2.5 m/s of our own; the measured value lags on small, far boxes at low fps.
        val effClosing = maxOf(closingMps, v - 2.5f)
        val ttr = distanceM / effClosing
        return when {
            inEgo && (ttr < 1.6f || distanceM < 4f) -> Severity.CRITICAL
            (inEgo && ttr < 3.0f) || (ttr < 1.8f) -> Severity.CAUTION
            distanceM < 25f -> Severity.INFO
            else -> Severity.NONE
        }
    }
}
