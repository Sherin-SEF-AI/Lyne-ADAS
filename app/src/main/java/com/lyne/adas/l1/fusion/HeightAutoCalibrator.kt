package com.lyne.adas.l1.fusion

import kotlin.math.exp
import kotlin.math.ln

/**
 * Online self-calibration of camera height from everyday traffic. Individual car widths vary, but
 * the AVERAGE car is a well-known width, while the mount height is one fixed unknown. So the median
 * ratio of (width-prior distance / ground-plane distance) over many cars measures how far the
 * configured height is off. We nudge a height multiplier toward that median, slowly and bounded.
 *
 * Histogram median over ln(ratio): O(1) memory, no allocation, robust to outliers.
 */
class HeightAutoCalibrator(initialScale: Float = 1f) {
    var scale: Float = initialScale.coerceIn(MIN, MAX); private set
    private val bins = FloatArray(NBINS)
    private var total = 0f
    /** Samples folded in since start (for the UI "learning" indicator). */
    var samples = 0; private set

    /** @param ratio width-prior distance / ground distance, measured under the CURRENT scale. */
    fun add(ratio: Float) {
        if (ratio.isNaN() || ratio <= 0f) return
        val l = ln(ratio)
        if (l < LO || l > HI) return
        val b = ((l - LO) / (HI - LO) * (NBINS - 1)).toInt().coerceIn(0, NBINS - 1)
        bins[b] += 1f; total += 1f; samples++
        if (total >= BATCH) commit()
    }

    private fun commit() {
        var acc = 0f; var med = 0f
        val half = total / 2f
        for (i in 0 until NBINS) {
            acc += bins[i]
            if (acc >= half) { med = LO + (HI - LO) * i / (NBINS - 1); break }
        }
        // Partial step: ground distance scales with height, so new = old * median ratio.
        scale = (scale * exp(GAIN * med)).coerceIn(MIN, MAX)
        for (i in 0 until NBINS) bins[i] *= 0.3f // ratios were measured under the old scale; fade them
        total *= 0.3f
    }

    val confidence: Float get() = (samples / 600f).coerceAtMost(1f)

    companion object {
        private const val NBINS = 61
        private const val LO = -0.5f
        private const val HI = 0.5f
        private const val BATCH = 120f
        private const val GAIN = 0.5f
        const val MIN = 0.7f
        const val MAX = 1.4f
    }
}
