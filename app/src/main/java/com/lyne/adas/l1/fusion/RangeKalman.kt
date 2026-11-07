package com.lyne.adas.l1.fusion

import kotlin.math.sqrt

/**
 * Constant-velocity Kalman filter in INVERSE depth: state x = [q, q'] with q = 1/d.
 * Nice properties for a monocular sensor:
 *  - measurement noise is Gaussian and value-independent (see [RangeMeasurement])
 *  - q'/q = -d'/d = 1/TTC exactly, so the filter yields TTC without dividing noisy differences
 * Only the random part of the measurement noise is fed in; systematic bias (pitch, width prior) is
 * constant over seconds and would merely add lag. A 4-sigma innovation gate rejects single bad boxes;
 * a run of rejections means the world changed (occlusion, ID swap) and the filter re-initializes.
 */
class RangeKalman(
    /** Relative-acceleration process noise (m/s^2, 1-sigma). Hard braking is ~8 m/s^2. */
    private val accelSigma: Float = 7f,
) {
    var q = Float.NaN; private set
    var qd = 0f; private set
    private var p00 = 0f; private var p01 = 0f; private var p11 = 0f
    private var outlierRun = 0
    var updates = 0; private set

    val initialized: Boolean get() = !q.isNaN()
    val sigmaQ: Float get() = sqrt(p00.coerceAtLeast(0f))
    val sigmaQd: Float get() = sqrt(p11.coerceAtLeast(0f))

    /** Distance (m). */
    val d: Float get() = if (initialized && q > 0f) 1f / q else Float.NaN
    /** Range rate (m/s); negative = closing. d' = -q'/q^2. */
    val v: Float get() = if (initialized && q > 0f) -qd / (q * q) else 0f
    /** 1-sigma of distance from the filter state. */
    val sigmaD: Float get() = if (initialized && q > 0f) sigmaQ / (q * q) else Float.NaN

    fun reset() { q = Float.NaN; qd = 0f; p00 = 0f; p01 = 0f; p11 = 0f; outlierRun = 0; updates = 0 }

    fun predict(dt: Float) {
        if (!initialized || dt <= 0f) return
        val t = dt.coerceAtMost(1f)
        q += qd * t
        if (q < MIN_Q) { q = MIN_Q; if (qd < 0f) qd = 0f }
        // q'' = -d''/d^2 + 2 d'^2/d^3  ->  map metric relative acceleration into q-space.
        val sa = accelSigma * q * q + 2f * qd * qd / q.coerceAtLeast(MIN_Q) * 0.5f
        val qn = sa * sa
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        val n00 = p00 + 2f * t * p01 + t2 * p11 + qn * t4 / 4f
        val n01 = p01 + t * p11 + qn * t3 / 2f
        val n11 = p11 + qn * t2
        p00 = n00; p01 = n01; p11 = n11
    }

    /** @return true if the measurement was accepted (not gated as an outlier). */
    fun update(z: Float, sigma: Float): Boolean {
        val r = (sigma * sigma).coerceAtLeast(1e-8f)
        if (!initialized) {
            q = z; qd = 0f
            p00 = r
            val s = 4f * z * z // closing speed unknown: +-4 m/s  ->  q' = c q^2
            p11 = s * s; p01 = 0f
            updates = 1
            return true
        }
        val y = z - q
        val s = p00 + r
        if (y * y / s > GATE_CHI2) {
            outlierRun++
            if (outlierRun >= 4) { reset(); update(z, sigma); return true }
            return false
        }
        outlierRun = 0
        val k0 = p00 / s; val k1 = p01 / s
        q += k0 * y
        qd += k1 * y
        val n00 = (1f - k0) * p00
        val n01 = (1f - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
        if (q < MIN_Q) q = MIN_Q
        updates++
        return true
    }

    /** Looming rate q'/q = 1/TTC (1/s, + = closing) and its 1-sigma. */
    fun loomRate(): Float = if (initialized && q > 0f) qd / q else Float.NaN
    fun loomSigma(): Float = if (initialized && q > 0f) sqrt(p11 + (qd / q) * (qd / q) * p00) / q else Float.NaN

    companion object {
        private const val GATE_CHI2 = 16f
        private const val MIN_Q = 1f / 250f
    }
}
