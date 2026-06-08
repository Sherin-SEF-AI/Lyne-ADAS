package com.lyne.adas.l1.fusion

/**
 * Scale-based monocular time-to-collision for the tracked lead vehicle. As the lead approaches its
 * bounding box grows; TTC = w / (dw/dt). This needs no absolute speed and is the standard monocular
 * FCW cue. dw/dt is EMA-smoothed; TTC is only valid while the box is expanding (closing).
 */
class TtcEstimator {
    private var lastWidth = Float.NaN
    private var lastTimeNs = 0L
    private var growthEma = 0f
    private val alpha = 0.3f

    /** @return TTC in seconds, or NaN if not closing / not enough history. */
    fun update(boxWidth: Float, timestampNs: Long): Float {
        if (lastWidth.isNaN() || lastTimeNs == 0L) {
            lastWidth = boxWidth; lastTimeNs = timestampNs
            return Float.NaN
        }
        val dt = (timestampNs - lastTimeNs) / 1e9f
        if (dt <= 1e-3f) return ttcFrom(boxWidth)
        val growth = (boxWidth - lastWidth) / dt // width units per second
        growthEma += alpha * (growth - growthEma)
        lastWidth = boxWidth; lastTimeNs = timestampNs
        return ttcFrom(boxWidth)
    }

    private fun ttcFrom(boxWidth: Float): Float {
        // Positive growth => approaching. Require a minimum expansion to avoid divide-by-noise.
        if (growthEma <= 1e-3f) return Float.NaN
        return (boxWidth / growthEma).coerceIn(0f, 60f)
    }

    fun reset() { lastWidth = Float.NaN; lastTimeNs = 0L; growthEma = 0f }
}
