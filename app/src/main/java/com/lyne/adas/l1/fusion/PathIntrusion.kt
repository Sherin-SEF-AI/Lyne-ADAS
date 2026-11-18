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
    /** How far ahead (s) a VRU's sideways motion is extrapolated to test for path intrusion. */
    const val VRU_PREDICT_HORIZON_S = 2.0f

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
            // Young track: distance-only, never more than a caution (someone walking with traffic
            // keeps a constant gap; that needs the closing speed to tell apart).
            return when {
                inEgo && (distanceM < 12f || distanceM / v < 3.0f) -> Severity.CAUTION
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

    /** Animal warnings are off below this ego speed (~10 km/h, like FCW). */
    const val ANIMAL_MIN_SPEED_MPS = 2.8f
    /** Corridor half-width margins for animals: ego path, and "near the path" (can step in). */
    const val ANIMAL_EGO_EXTRA_M = 0.3f
    const val ANIMAL_NEAR_EXTRA_M = 1.2f

    /**
     * Animal-ahead severity. Animals stand still, wander or cross without regard for traffic, so there
     * is no useful closing-speed model for them: they are treated as stationary and time-to-reach is
     * distance / ego speed.
     *  - CRITICAL: in the ego corridor and reached in < 2 s
     *  - CAUTION: in or near the corridor and reached in < 4 s
     *  - nothing while (nearly) stopped. Unknown ego speed (no GPS fix) assumes 6 m/s and never goes
     *    above CAUTION.
     */
    fun animalSeverity(distanceM: Float, egoSpeedMps: Float, inEgo: Boolean, inNear: Boolean): Severity {
        if (distanceM.isNaN() || (!inEgo && !inNear)) return Severity.NONE
        val known = !egoSpeedMps.isNaN()
        val v = if (known) egoSpeedMps else 6f
        if (v < ANIMAL_MIN_SPEED_MPS) return Severity.NONE
        val ttr = distanceM / v
        return when {
            inEgo && ttr < 2.0f && known -> Severity.CRITICAL
            ttr < 4.0f -> Severity.CAUTION
            distanceM < 30f -> Severity.INFO
            else -> Severity.NONE
        }
    }
}
