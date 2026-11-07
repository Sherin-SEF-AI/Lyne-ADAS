package com.lyne.adas.l1.fusion

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Robust looming rate from a sliding window of box scale samples. For an object at range d closing
 * at speed c, its image scale s ~ 1/d, so d(ln s)/dt = c/d = 1/TTC. Instead of differencing two
 * noisy frames (which is what turned 1px jitter into phantom collisions), we fit a least-squares line
 * to ln(scale) over the last ~1.2 s and keep its standard error, so callers can require the closing
 * to be statistically significant before warning. Fixed-size ring buffer: zero allocation.
 */
class ScaleRateEstimator(private val capacity: Int = 24, private val windowSec: Float = 1.2f) {
    private val ts = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private var head = 0
    private var count = 0

    /** Last fit: rate (1/s, + = looming) and its 1-sigma standard error. NaN if not enough data. */
    var rate = Float.NaN; private set
    var rateSigma = Float.NaN; private set

    fun reset() { head = 0; count = 0; rate = Float.NaN; rateSigma = Float.NaN }

    fun add(timeSec: Double, scale: Float) {
        if (scale <= 1e-4f) return
        ts[head] = timeSec; ys[head] = ln(scale.toDouble())
        head = (head + 1) % capacity
        if (count < capacity) count++
        fit(timeSec)
    }

    /**
     * Recency-weighted least squares (half-life ~[HALF_LIFE] s): a braking lead makes the looming rate
     * climb quickly, and an unweighted window would report its average from half a window ago.
     */
    private fun fit(now: Double) {
        var sw = 0.0; var st = 0.0; var sy = 0.0; var n = 0; var tMin = Double.MAX_VALUE
        for (i in 0 until count) {
            val age = now - ts[i]; if (age > windowSec) continue
            val w = Math.exp(-age * DECAY)
            sw += w; st += w * ts[i]; sy += w * ys[i]; n++
            if (ts[i] < tMin) tMin = ts[i]
        }
        if (n < 5 || now - tMin < 0.3) { rate = Float.NaN; rateSigma = Float.NaN; return }
        val mt = st / sw; val my = sy / sw
        var stt = 0.0; var sty = 0.0; var sw2 = 0.0
        for (i in 0 until count) {
            val age = now - ts[i]; if (age > windowSec) continue
            val w = Math.exp(-age * DECAY)
            val dt = ts[i] - mt
            stt += w * dt * dt; sty += w * dt * (ys[i] - my); sw2 += w * w
        }
        if (stt < 1e-9) { rate = Float.NaN; rateSigma = Float.NaN; return }
        val slope = sty / stt
        var ssr = 0.0
        for (i in 0 until count) {
            val age = now - ts[i]; if (age > windowSec) continue
            val w = Math.exp(-age * DECAY)
            val e = ys[i] - (my + slope * (ts[i] - mt))
            ssr += w * e * e
        }
        val nEff = sw * sw / sw2 // effective sample count under the weights
        // Floor the residual so a few identical (quantized) boxes don't claim infinite certainty.
        val resVar = maxOf(ssr / sw * nEff / (nEff - 2.0).coerceAtLeast(1.0), 0.0004)
        rate = slope.toFloat()
        rateSigma = Math.sqrt(resVar / (stt / sw) / (nEff - 2.0).coerceAtLeast(1.0)).toFloat()
    }

    companion object {
        private const val HALF_LIFE = 0.35
        private const val DECAY = 0.6931 / HALF_LIFE
    }
}
