package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.inference.BBox

/**
 * Classical brake-light detector for the lead vehicle (no model).
 *
 * A [COLS] x [ROWS] grid of pixels is sampled inside the lead box. Three regions are scored: the
 * left and right tail lamps (outer columns, upper-middle band of the rear, which covers sedan and
 * MPV lamp heights) and the centre high-mount stop lamp (top-centre, at the roof line). A region's
 * score is the mean "redness" (R - max(G, B), only for pixels with a bright red channel) of its
 * [TOP_K] reddest samples: a lamp is a few cells at this resolution, so a peak statistic beats an
 * area fraction. Tail lamps and (in daylight) their red housings are always red, so the absolute
 * score says little; what matters is a clear RISE over a slow per-vehicle baseline.
 *
 * BRAKING is declared when both side lamps, or the centre lamp, rise above baseline for [ONSET_MS],
 * and released once the rise has dropped below [RELEASE_FRACTION] of the onset threshold for
 * [RELEASE_MS]. The baseline is frozen while a rise is present, so a long stop at a red light does
 * not teach it that "lamps on" is normal. A lead that is already braking when first seen is not
 * flagged until its lamps go off and on again (no rise is ever visible).
 *
 * Tuned on real daytime drive footage (Bengaluru, phone-camera resolution): there the side lamps
 * barely change in daylight and the centre lamp carries the detection.
 *
 * Pure Kotlin, allocation-free per frame: [grid] is a preallocated ARGB buffer filled either by a
 * [LeadPixelSource] ([update]) or directly by tests ([updateFromGrid]).
 */
class BrakeLightDetector {
    /** Row-major ARGB samples, [COLS] x [ROWS]. */
    val grid = IntArray(COLS * ROWS)

    var braking = false; private set

    // Region scores (L, R, C) in redness units (0..255): raw, lightly smoothed, slow baseline.
    val score = FloatArray(3)
    val smooth = FloatArray(3)
    val baseline = FloatArray(3)
    private var frames = 0
    private var candSinceMs = -1L
    private var clearSinceMs = -1L
    private var lastMeasMs = -1L

    /** New lead: forget everything learned about the previous vehicle. */
    fun reset() {
        braking = false; frames = 0; candSinceMs = -1L; clearSinceMs = -1L; lastMeasMs = -1L
        score.fill(0f); smooth.fill(0f); baseline.fill(0f)
    }

    /** No measurement this frame (lead missed/occluded/absent): hold state, then release. */
    fun coast(nowMs: Long): Boolean {
        if (braking && lastMeasMs >= 0 && nowMs - lastMeasMs > COAST_MS) {
            braking = false; candSinceMs = -1L; clearSinceMs = -1L
        }
        return braking
    }

    /** Samples [box] from [src] and advances the state machine. */
    fun update(box: BBox, src: LeadPixelSource, nowMs: Long): Boolean {
        // Too small (far away) or cut off by the frame edge: lamps not resolvable/visible.
        if (box.width < MIN_BOX_W || box.left <= 0.003f || box.right >= 0.997f || box.bottom >= 0.997f) return coast(nowMs)
        if (!src.sample(box, grid, COLS, ROWS)) return coast(nowMs)
        return updateFromGrid(nowMs)
    }

    /** Advances the state machine from the samples already in [grid]. */
    fun updateFromGrid(nowMs: Long): Boolean {
        score[0] = regionScore(0, SIDE_COLS, LAMP_ROW0, LAMP_ROW1)
        score[1] = regionScore(COLS - SIDE_COLS, COLS, LAMP_ROW0, LAMP_ROW1)
        score[2] = regionScore(CHMSL_COL0, CHMSL_COL1, 0, CHMSL_ROWS)
        lastMeasMs = nowMs

        if (frames == 0) for (i in 0..2) { baseline[i] = score[i]; smooth[i] = score[i] }
        else for (i in 0..2) smooth[i] += SMOOTH_ALPHA * (score[i] - smooth[i])
        frames++

        val candidate = frames > WARMUP_FRAMES && ((rises(0) && rises(1)) || rises(2))
        val holding = (above(0, RELEASE_FRACTION) && above(1, RELEASE_FRACTION)) || above(2, RELEASE_FRACTION)

        if (candidate) {
            if (candSinceMs < 0) candSinceMs = nowMs
            clearSinceMs = -1L
            if (!braking && nowMs - candSinceMs >= ONSET_MS) braking = true
        } else {
            candSinceMs = -1L
            if (braking) {
                if (holding) clearSinceMs = -1L
                else {
                    if (clearSinceMs < 0) clearSinceMs = nowMs
                    if (nowMs - clearSinceMs >= RELEASE_MS) { braking = false; clearSinceMs = -1L }
                }
            }
        }

        // Baseline: settles fast during warm-up, then falls slowly, creeps up very slowly, and is
        // frozen while a region looks lit.
        if (!braking && !candidate) {
            for (i in 0..2) {
                val s = smooth[i]; val b = baseline[i]
                val a = when {
                    frames <= WARMUP_FRAMES -> 0.5f
                    s < b -> BASE_DOWN_ALPHA
                    above(i, RELEASE_FRACTION) -> 0f
                    else -> BASE_UP_ALPHA
                }
                baseline[i] = b + a * (s - b)
            }
        }
        return braking
    }

    private fun threshold(i: Int): Float = if (i == 2) CHMSL_RISE else SIDE_RISE
    private fun above(i: Int, frac: Float): Boolean = smooth[i] - baseline[i] >= threshold(i) * frac
    private fun rises(i: Int): Boolean = above(i, 1f) && (i != 2 || smooth[2] >= CHMSL_MIN)

    /** Mean of the [TOP_K] largest redness values in the region (insertion into 3 slots, no alloc). */
    private fun regionScore(c0: Int, c1: Int, r0: Int, r1: Int): Float {
        var m0 = 0; var m1 = 0; var m2 = 0
        for (r in r0 until r1) {
            val base = r * COLS
            for (c in c0 until c1) {
                val v = redness(grid[base + c])
                if (v > m2) {
                    if (v > m1) {
                        m2 = m1
                        if (v > m0) { m1 = m0; m0 = v } else m1 = v
                    } else m2 = v
                }
            }
        }
        return (m0 + m1 + m2) / 3f
    }

    companion object {
        const val COLS = 20
        const val ROWS = 16
        // Regions in grid cells.
        const val SIDE_COLS = 6                 // outer 30 % of the box width on each side
        const val LAMP_ROW0 = 2                 // 12.5 % .. 62.5 % of the box height
        const val LAMP_ROW1 = 10
        const val CHMSL_COL0 = 7                // 35 % .. 65 % width
        const val CHMSL_COL1 = 13
        const val CHMSL_ROWS = 3                // top 19 %
        const val TOP_K = 3

        /** Pixels whose red channel is below this don't count as lamp light (shadow, dark paint). */
        const val MIN_RED = 90
        /** Rise over baseline (redness units) for each side lamp; both sides must rise. */
        const val SIDE_RISE = 25f
        /** Rise over baseline for the centre lamp alone, plus an absolute floor. */
        const val CHMSL_RISE = 20f
        const val CHMSL_MIN = 25f
        /** Release once the rise falls below this fraction of the onset threshold (hysteresis). */
        const val RELEASE_FRACTION = 0.7f
        /** Per-frame smoothing of region scores (pixel noise, compression blocks). */
        const val SMOOTH_ALPHA = 0.5f

        const val ONSET_MS = 200L
        const val RELEASE_MS = 400L
        const val COAST_MS = 1000L
        const val WARMUP_FRAMES = 3
        const val BASE_DOWN_ALPHA = 0.1f
        const val BASE_UP_ALPHA = 0.03f
        /** Lead narrower than ~4 % of the frame (about 10 px at 256): lamps are a pixel or two. */
        const val MIN_BOX_W = 0.04f

        /** R - max(G, B) for pixels with a bright red channel, else 0. */
        fun redness(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            if (r < MIN_RED) return 0
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val d = r - (if (g > b) g else b)
            return if (d > 0) d else 0
        }
    }
}
