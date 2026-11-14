package com.lyne.adas.l1.sensors

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Vehicle speed that survives short GPS outages (metro underpasses, tunnels, flyover shadows).
 *
 * While GPS is fresh the GPS speed is used as is. Once the last fix is older than [STALE_MS] the
 * last good speed is held and dead-reckoned with the phone's longitudinal (forward) acceleration:
 *
 *   v = vFix + delta,   d(delta)/dt = gate(aFwd - bias) - delta / DECAY_TAU_S
 *
 * gate() passes the acceleration only while its 1 s low-pass clearly shows the car speeding up or
 * slowing down (|a| >= [DEADBAND_MPS2]); steady cruising holds the speed exactly, so a residual
 * accelerometer bias or mount vibration cannot integrate into a drift. A slow decay pulls the
 * estimate back toward the held value as a second guard. The bias itself is learned while GPS is
 * good (integrated IMU dv vs GPS dv between fixes). The
 * 1-sigma uncertainty grows with time since the last fix (bias drift + random walk), and the
 * estimate is abandoned (NaN) after [MAX_HOLD_MS] or once sigma exceeds [MAX_SIGMA_MPS].
 *
 * Pure Kotlin and clock-agnostic: callers pass a monotonic millisecond time (elapsedRealtime on
 * device). Methods are synchronized because GPS, sensor and inference threads all touch it; the
 * work per call is a few float operations and allocates nothing.
 */
class SpeedEstimator {
    private var fixMs = Long.MIN_VALUE
    private var fixSpeed = Float.NaN
    private var hasFix = false

    // Dead-reckoning integral since the last fix (decayed), and a raw one for bias learning.
    private var delta = 0f
    private var rawDv = 0f
    private var lastAccelMs = Long.MIN_VALUE
    private var accelSamplesSinceFix = 0
    private var lpAccel = 0f

    /** Learned accelerometer bias along the forward axis (m/s^2). */
    var biasMps2 = 0f; private set
    private var biasWeight = 0f

    @Synchronized
    fun onGps(tMs: Long, speedMps: Float) {
        if (speedMps.isNaN() || speedMps < 0f) return
        // Bias learning: the IMU should have integrated to the same dv as GPS between two fixes.
        if (hasFix) {
            val dt = (tMs - fixMs) / 1000f
            if (dt in 0.3f..3f && accelSamplesSinceFix >= 5) {
                val err = (rawDv - (speedMps - fixSpeed)) / dt
                if (abs(err) < 3f) { // reject wheel-spin-like outliers, GPS jumps
                    val a = (dt / BIAS_TAU_S).coerceIn(0f, 1f).let { if (biasWeight < 1f) maxOf(it, 0.2f) else it }
                    biasMps2 = (biasMps2 + a * (err - biasMps2)).coerceIn(-MAX_BIAS, MAX_BIAS)
                    biasWeight = (biasWeight + dt / BIAS_TAU_S).coerceAtMost(1f)
                }
            }
        }
        fixMs = tMs; fixSpeed = speedMps; hasFix = true
        delta = 0f; rawDv = 0f; accelSamplesSinceFix = 0
    }

    /** Forward (direction of travel) acceleration with gravity removed, m/s^2. */
    @Synchronized
    fun onAccel(tMs: Long, fwdAccel: Float) {
        if (fwdAccel.isNaN()) return
        val prev = lastAccelMs
        lastAccelMs = tMs
        if (prev == Long.MIN_VALUE || !hasFix) return
        val dt = ((tMs - prev) / 1000f)
        if (dt <= 0f || dt > 0.2f) return // gap in the sensor stream: skip rather than extrapolate
        val a = fwdAccel.coerceIn(-12f, 12f)
        rawDv += a * dt
        val ac = a - biasMps2
        lpAccel += (ac - lpAccel) * (dt / LP_TAU_S).coerceAtMost(1f)
        val drive = if (abs(lpAccel) >= DEADBAND_MPS2) ac else 0f
        delta += (drive - delta / DECAY_TAU_S) * dt
        accelSamplesSinceFix++
    }

    /** Best speed at [tMs]: fresh GPS, else a held/dead-reckoned estimate, else NaN. */
    @Synchronized
    fun speedMps(tMs: Long): Float {
        if (!hasFix) return Float.NaN
        val age = tMs - fixMs
        if (age <= STALE_MS) return fixSpeed
        if (!holdValid(age)) return Float.NaN
        return (fixSpeed + if (imuAlive(tMs)) delta else 0f).coerceAtLeast(0f)
    }

    /** True while the speed is a hold/dead-reckoned estimate rather than a fresh GPS reading. */
    @Synchronized
    fun isEstimated(tMs: Long): Boolean {
        if (!hasFix) return false
        val age = tMs - fixMs
        return age > STALE_MS && holdValid(age)
    }

    /** 1-sigma speed uncertainty (m/s); NaN when there is no speed at all. */
    @Synchronized
    fun sigmaMps(tMs: Long): Float {
        if (!hasFix) return Float.NaN
        val age = tMs - fixMs
        if (age <= STALE_MS) return GPS_SIGMA
        return if (holdValid(age)) sigmaAt(age) else Float.NaN
    }

    private fun imuAlive(tMs: Long) = lastAccelMs != Long.MIN_VALUE && tMs - lastAccelMs < 500L

    private fun holdValid(ageMs: Long): Boolean {
        val limit = if (imuAlive(fixMs + ageMs)) MAX_HOLD_MS else MAX_HOLD_NO_IMU_MS
        return ageMs <= limit && sigmaAt(ageMs) <= MAX_SIGMA_MPS
    }

    private fun sigmaAt(ageMs: Long): Float {
        val t = ageMs / 1000f
        val imu = imuAlive(fixMs + ageMs)
        val b = if (imu) BIAS_DRIFT_SIGMA * t else 0f
        val rw = if (imu) RW_SIGMA else HOLD_RW_SIGMA
        return sqrt(GPS_SIGMA * GPS_SIGMA + b * b + rw * rw * t)
    }

    @Synchronized
    fun reset() {
        hasFix = false; fixMs = Long.MIN_VALUE; fixSpeed = Float.NaN
        delta = 0f; rawDv = 0f; lastAccelMs = Long.MIN_VALUE; accelSamplesSinceFix = 0; lpAccel = 0f
    }

    companion object {
        /** GPS speed is trusted for this long after a fix (unchanged from before). */
        const val STALE_MS = 3000L
        /** Give up dead-reckoning after this long without a fix. */
        const val MAX_HOLD_MS = 20_000L
        /** Without accelerometer data a held speed is just a guess: keep it only briefly. */
        const val MAX_HOLD_NO_IMU_MS = 8_000L
        const val MAX_SIGMA_MPS = 2.5f
        const val DECAY_TAU_S = 120f
        const val LP_TAU_S = 1f
        /** Low-passed forward accel below this is treated as steady cruising (no integration). */
        const val DEADBAND_MPS2 = 0.25f
        const val BIAS_TAU_S = 20f
        const val MAX_BIAS = 1.0f
        const val GPS_SIGMA = 0.3f
        /** Residual bias uncertainty after learning (m/s^2) -> sigma grows linearly with time. */
        const val BIAS_DRIFT_SIGMA = 0.08f
        /** Random-walk term (m/s per sqrt(s)): vibration noise, mount flex, decay mismatch. */
        const val RW_SIGMA = 0.2f
        const val HOLD_RW_SIGMA = 0.75f

        /**
         * Gravity-removed acceleration projected on the vehicle's forward axis. The camera looks out
         * of the back of the phone, so forward is device -Z flattened to the horizontal plane
         * (perpendicular to gravity). [ax..az] raw accelerometer, [gx..gz] gravity estimate, both in
         * device axes (m/s^2). Returns NaN when gravity is unknown or the phone lies flat.
         */
        fun forwardAccel(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float): Float {
            val gn = sqrt(gx * gx + gy * gy + gz * gz)
            if (gn < 1f) return Float.NaN
            val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
            // f = (-Z) - ((-Z).u) u
            var fx = ux * uz; var fy = uy * uz; var fz = -1f + uz * uz
            val fn = sqrt(fx * fx + fy * fy + fz * fz)
            if (fn < 0.3f) return Float.NaN // camera pointing at the sky/ground: forward ill-defined
            fx /= fn; fy /= fn; fz /= fn
            return (ax - gx) * fx + (ay - gy) * fy + (az - gz) * fz
        }
    }
}
