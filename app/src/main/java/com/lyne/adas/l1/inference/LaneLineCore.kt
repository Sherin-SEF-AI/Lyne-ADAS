package com.lyne.adas.l1.inference

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Classical painted-lane-line detector. Pure Kotlin (no Android types) so it is JVM-testable; the
 * Android side only hands it the frame's ARGB pixels (see [LaneLineDetector]).
 *
 * Per frame:
 *  1. Sample a coarse grid (default 160x40) over a ROI between just below the horizon and the bottom of
 *     the frame, computing a "paint" channel: luma for near-neutral pixels (white paint), boosted for
 *     yellow paint, penalised for saturated colours.
 *  2. Per grid row, a symmetric ridge filter finds bright thin stripes (darker on both sides by a
 *     noise-relative margin, flanks of similar brightness and low saturation = road). The stripe scale
 *     follows the expected paint width at that row from the camera geometry. Non-max suppressed.
 *  3. Hough vote over straight near-field lines x = xb + s * (yBottom - y), refit on inliers, and keep
 *     lines that cover enough rows (dashed lines included).
 *  4. Pick the ego-lane pair: both lines must point at a common vanishing point near the horizon and be
 *     2.5-4.5 m apart at the nearest row. With no plausible pair, a single well-supported line within
 *     2.4 m of the car is kept as a one-sided boundary.
 *  5. Track each side with gating + smoothing; confidence only builds after several consistent frames,
 *     so passing clutter never reaches the confidence the fusion stage requires.
 *
 * Zero allocation per frame except the small result object. Single-threaded: call from one thread.
 */
class LaneLineCore(
    private val gw: Int = 160,
    private val gh: Int = 40,
) {
    /** Normalized horizon row at the image centre (from the camera geometry). */
    @Volatile var horizonY: Float = DEFAULT_HORIZON
        private set
    /** Normalized x-width of one metre at row y is metreSlope * (y - horizonY) (flat road, small angles). */
    @Volatile var metreSlope: Float = DEFAULT_METRE_SLOPE
        private set
    /** Lowest analysed row (keeps the bonnet/dashboard out of the ROI). */
    var yBottom: Float = 0.92f

    /** Number of ridge points found on the last frame (diagnostics). */
    var lastPointCount = 0
        private set

    // --- scratch (allocated once) ---
    private val paint = FloatArray(gw * gh)
    private val chroma = FloatArray(gw * gh)
    private val ys = FloatArray(gh)
    private val resp = FloatArray(gw)
    private val sortBuf = FloatArray(gw)
    private val ptX = FloatArray(MAX_PTS)
    private val ptY = FloatArray(MAX_PTS)
    private val ptRow = IntArray(MAX_PTS)
    private var nPts = 0
    private val acc = FloatArray(NS * NXB)
    private val acc2 = FloatArray(NS * NXB)
    private val pkXb = FloatArray(MAX_PEAKS)
    private val pkS = FloatArray(MAX_PEAKS)
    private val pkRows = FloatArray(MAX_PEAKS)
    private var nPk = 0
    private val rowSeen = BooleanArray(gh)
    private var refXb = 0f
    private var refS = 0f
    private var refRows = 0f

    // --- per-side track state: index 0 = left, 1 = right ---
    private val trValid = BooleanArray(2)
    private val trXb = FloatArray(2)
    private val trS = FloatArray(2)
    private val miss = IntArray(2)
    private val hits = IntArray(2)
    private val sideConf = FloatArray(2)
    private val measXb = FloatArray(2)
    private val measS = FloatArray(2)
    private val measQ = FloatArray(2)
    private val measOk = BooleanArray(2)
    private var widthMem = Float.NaN

    /** Feed the current camera geometry. Values outside sane ranges are ignored. */
    fun setGeometry(horizonY: Float, tanHalfH: Float, tanHalfV: Float, cameraHeightM: Float) {
        if (horizonY.isNaN() || tanHalfH <= 0f || tanHalfV <= 0f || cameraHeightM <= 0f) return
        this.horizonY = horizonY.coerceIn(0.15f, 0.75f)
        this.metreSlope = (tanHalfV / (tanHalfH * cameraHeightM)).coerceIn(0.08f, 3f)
    }

    fun reset() {
        for (s in 0..1) { trValid[s] = false; miss[s] = 99; hits[s] = 0; sideConf[s] = 0f }
        widthMem = Float.NaN
    }

    init { reset() }

    /** @param argb packed 0xAARRGGBB pixels, row-major, [w]x[h] (Bitmap.getPixels layout). */
    fun detect(argb: IntArray, w: Int, h: Int): LaneLines {
        val hz = horizonY
        val y0 = hz + TOP_MARGIN
        if (w < 16 || h < 16 || y0 > yBottom - 0.12f) { decayAll(); return output(hz, y0) }
        sampleGrid(argb, w, h, y0)
        findRidges(hz)
        hough()
        findPeaks()
        select(hz)
        return output(hz, y0)
    }

    // ---------------------------------------------------------------- 1. grid
    private fun sampleGrid(argb: IntArray, w: Int, h: Int, y0: Float) {
        val cellH = (yBottom - y0) / gh
        for (j in 0 until gh) {
            val yc = y0 + cellH * (j + 0.5f)
            ys[j] = yc
            val py0 = ((yc - cellH * 0.5f) * h).toInt().coerceIn(0, h - 1)
            val py1 = max(py0 + 1, ((yc + cellH * 0.5f) * h).toInt()).coerceAtMost(h)
            for (i in 0 until gw) {
                val px0 = (i * w / gw).coerceIn(0, w - 1)
                val px1 = max(px0 + 1, (i + 1) * w / gw).coerceAtMost(w)
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                var py = py0
                while (py < py1) {
                    var idx = py * w + px0
                    val end = py * w + px1
                    while (idx < end) {
                        val c = argb[idx]
                        sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
                        idx++
                    }
                    py++
                }
                val r = sr.toFloat() / n; val g = sg.toFloat() / n; val b = sb.toFloat() / n
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val chr = max(r, max(g, b)) - min(r, min(g, b))
                val yellow = r > 90f && g > 70f && b < 0.75f * g && r >= 0.9f * g && r - b > 40f
                val k = j * gw + i
                paint[k] = if (yellow) max(luma, (r + g) * 0.5f) else luma - max(0f, chr - CHROMA_FREE) * 1.5f
                chroma[k] = chr
            }
        }
    }

    // ---------------------------------------------------------------- 2. ridges
    private fun findRidges(hz: Float) {
        nPts = 0
        for (j in 0 until gh) {
            val base = j * gw
            val stripe = PAINT_WIDTH_M * metreSlope * (ys[j] - hz)
            val k = (stripe * gw * 1.2f).roundToInt().coerceIn(1, 6)
            // Row median brightness and a local noise scale (median absolute neighbour difference).
            System.arraycopy(paint, base, sortBuf, 0, gw)
            java.util.Arrays.sort(sortBuf, 0, gw)
            val med = sortBuf[gw / 2]
            for (i in 0 until gw - 1) sortBuf[i] = abs(paint[base + i + 1] - paint[base + i])
            java.util.Arrays.sort(sortBuf, 0, gw - 1)
            val noise = sortBuf[(gw - 1) / 2] + 1f
            val thr = max(RIDGE_ABS, RIDGE_NOISE_MULT * noise)

            for (i in 0 until gw) resp[i] = Float.NEGATIVE_INFINITY
            for (i in k until gw - k) {
                val c = paint[base + i]; val l = paint[base + i - k]; val r = paint[base + i + k]
                val rv = min(c - l, c - r)
                if (rv <= thr || c <= med + ABOVE_MEDIAN) continue
                if (abs(l - r) >= max(SYM_ABS, SYM_REL * rv)) continue
                if (chroma[base + i - k] >= FLANK_CHROMA || chroma[base + i + k] >= FLANK_CHROMA) continue
                resp[i] = rv
            }
            for (i in k until gw - k) {
                val rv = resp[i]
                if (rv == Float.NEGATIVE_INFINITY) continue
                var isMax = true
                for (q in max(0, i - k)..min(gw - 1, i + k)) if (resp[q] > rv) { isMax = false; break }
                if (!isMax || nPts >= MAX_PTS) continue
                ptX[nPts] = (i + 0.5f) / gw; ptY[nPts] = ys[j]; ptRow[nPts] = j; nPts++
            }
        }
        lastPointCount = nPts
    }

    // ---------------------------------------------------------------- 3. hough + refit
    private fun hough() {
        java.util.Arrays.fill(acc, 0f)
        val yb = yBottom
        for (p in 0 until nPts) {
            val x = ptX[p]; val dy = yb - ptY[p]
            for (si in 0 until NS) {
                val xb = x - slopeOf(si) * dy
                if (xb < XB_MIN || xb >= XB_MAX) continue
                val bi = ((xb - XB_MIN) / (XB_MAX - XB_MIN) * NXB).toInt()
                if (bi in 0 until NXB) acc[si * NXB + bi] += 1f
            }
        }
        for (si in 0 until NS) {
            val o = si * NXB
            for (bi in 0 until NXB) {
                var v = acc[o + bi]
                if (bi > 0) v += acc[o + bi - 1]
                if (bi < NXB - 1) v += acc[o + bi + 1]
                acc2[o + bi] = v
            }
        }
    }

    private fun findPeaks() {
        nPk = 0
        for (iter in 0 until MAX_PEAKS) {
            var best = -1f; var bIdx = -1
            for (q in acc2.indices) if (acc2[q] > best) { best = acc2[q]; bIdx = q }
            if (best < MIN_VOTES) break
            val si = bIdx / NXB; val bi = bIdx % NXB
            for (a in max(0, si - 2)..min(NS - 1, si + 2)) for (b in max(0, bi - 3)..min(NXB - 1, bi + 3)) acc2[a * NXB + b] = 0f
            val xb = XB_MIN + (bi + 0.5f) / NXB * (XB_MAX - XB_MIN)
            if (refine(xb, slopeOf(si))) {
                pkXb[nPk] = refXb; pkS[nPk] = refS; pkRows[nPk] = refRows; nPk++
            }
        }
    }

    /** Least-squares refit of x = xb + s*dy on inliers (two passes). Result in refXb/refS/refRows. */
    private fun refine(xb0: Float, s0: Float): Boolean {
        var xb = xb0; var s = s0
        val yb = yBottom
        var n = 0
        repeat(2) {
            var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            n = 0
            for (p in 0 until nPts) {
                val dy = yb - ptY[p]
                if (abs(ptX[p] - (xb + s * dy)) >= INLIER_TOL) continue
                sw += 1.0; sx += dy; sy += ptX[p]; sxx += dy * dy; sxy += dy * ptX[p]; n++
            }
            if (n < 3) return false
            val det = sw * sxx - sx * sx
            if (det < 1e-9) return false
            s = ((sw * sxy - sx * sy) / det).toFloat()
            xb = ((sy - s * sx) / sw).toFloat()
        }
        java.util.Arrays.fill(rowSeen, false)
        var rows = 0
        for (p in 0 until nPts) {
            val dy = yb - ptY[p]
            if (abs(ptX[p] - (xb + s * dy)) >= INLIER_TOL) continue
            if (!rowSeen[ptRow[p]]) { rowSeen[ptRow[p]] = true; rows++ }
        }
        val frac = rows.toFloat() / gh
        if (frac < MIN_ROWS || abs(s) > S_MAX * 1.2f) return false
        refXb = xb; refS = s; refRows = frac
        return true
    }

    // ---------------------------------------------------------------- 4. selection + 5. tracking
    private fun xAt(xb: Float, s: Float, y: Float) = xb + s * (yBottom - y)

    /** Lateral metres of normalized column x at the nearest ROI row. */
    private fun lat(x: Float, hz: Float): Float = (x - 0.5f) / (metreSlope * (yBottom - hz))

    private fun select(hz: Float) {
        measOk[0] = false; measOk[1] = false; measQ[0] = 0f; measQ[1] = 0f
        // Two-line ego lane: common vanishing point near the horizon + plausible metric width.
        var bestScore = -1f; var bl = -1; var br = -1
        for (a in 0 until nPk) {
            val la = lat(pkXb[a], hz)
            val va = xAt(pkXb[a], pkS[a], hz)
            if (la < -MAX_SIDE_M || la > CROSS_M || va < 0.15f || va > 0.85f) continue
            for (b in 0 until nPk) {
                if (a == b) continue
                val lb = lat(pkXb[b], hz)
                val vb = xAt(pkXb[b], pkS[b], hz)
                if (lb < -CROSS_M || lb > MAX_SIDE_M || vb < 0.15f || vb > 0.85f) continue
                val wM = lb - la
                if (wM < MIN_LANE_M || wM > MAX_LANE_M) continue
                if (abs(va - vb) > VP_TOL) continue
                val sc = pkRows[a] + pkRows[b]
                if (sc > bestScore) { bestScore = sc; bl = a; br = b }
            }
        }
        if (bl >= 0) {
            setMeas(0, bl, 1f); setMeas(1, br, 1f)
            val wM = lat(pkXb[br], hz) - lat(pkXb[bl], hz)
            widthMem = if (widthMem.isNaN()) wM else widthMem + 0.2f * (wM - widthMem)
        } else {
            // One-sided boundary: a long line close to the car that points at the vanishing point.
            var best = -1; var bestRows = 0f
            for (a in 0 until nPk) {
                val la = lat(pkXb[a], hz)
                val va = xAt(pkXb[a], pkS[a], hz)
                if (abs(la) > MAX_SINGLE_M || va < 0.25f || va > 0.75f || pkRows[a] < SINGLE_MIN_ROWS) continue
                if (pkRows[a] > bestRows) { bestRows = pkRows[a]; best = a }
            }
            if (best >= 0) setMeas(if (lat(pkXb[best], hz) < 0f) 0 else 1, best, SINGLE_WEIGHT)
        }

        for (side in 0..1) {
            var q = 0f
            if (measOk[side]) {
                val stale = !trValid[side] || miss[side] > STALE_FRAMES
                if (stale) {
                    trXb[side] = measXb[side]; trS[side] = measS[side]; trValid[side] = true
                    miss[side] = 0; hits[side] = 1; q = measQ[side]
                } else if (abs(measXb[side] - trXb[side]) < GATE_XB && abs(measS[side] - trS[side]) < GATE_S) {
                    trXb[side] += TRACK_ALPHA * (measXb[side] - trXb[side])
                    trS[side] += TRACK_ALPHA * (measS[side] - trS[side])
                    miss[side] = 0; hits[side]++; q = measQ[side]
                } else {
                    miss[side]++
                }
            } else {
                miss[side]++
            }
            if (miss[side] > DROP_FRAMES) { trValid[side] = false; hits[side] = 0 }
            if (hits[side] < MIN_HITS) q = 0f
            sideConf[side] += CONF_ALPHA * (q - sideConf[side])
        }
    }

    private fun setMeas(side: Int, pk: Int, weight: Float) {
        measOk[side] = true; measXb[side] = pkXb[pk]; measS[side] = pkS[pk]
        measQ[side] = weight * min(1f, pkRows[pk] / FULL_ROWS)
    }

    private fun decayAll() {
        for (side in 0..1) {
            miss[side]++
            if (miss[side] > DROP_FRAMES) { trValid[side] = false; hits[side] = 0 }
            sideConf[side] += CONF_ALPHA * (0f - sideConf[side])
        }
    }

    // ---------------------------------------------------------------- output
    private fun output(hz: Float, y0: Float): LaneLines {
        val rows = FloatArray(OUT_ROWS)
        val lx = FloatArray(OUT_ROWS)
        val rx = FloatArray(OUT_ROWS)
        for (i in 0 until OUT_ROWS) {
            val y = y0 + (yBottom - y0) * i / (OUT_ROWS - 1f)
            rows[i] = y
            lx[i] = if (trValid[0]) xAt(trXb[0], trS[0], y) else Float.NaN
            rx[i] = if (trValid[1]) xAt(trXb[1], trS[1], y) else Float.NaN
        }
        val lm = if (trValid[0]) lat(trXb[0], hz) else Float.NaN
        val rm = if (trValid[1]) lat(trXb[1], hz) else Float.NaN
        val both = trValid[0] && trValid[1]
        val conf = if (both) (sideConf[0] + sideConf[1]) * 0.5f
        else SINGLE_WEIGHT * max(if (trValid[0]) sideConf[0] else 0f, if (trValid[1]) sideConf[1] else 0f)
        val half = if (widthMem.isNaN()) NOMINAL_HALF_M else widthMem * 0.5f
        val offset = when {
            both -> -((lm + rm) * 0.5f) / max(0.5f, (rm - lm) * 0.5f)
            trValid[0] -> -max(0f, (half - (-lm)) / half)
            trValid[1] -> max(0f, (half - rm) / half)
            else -> Float.NaN
        }
        val curvature = if (both) {
            ((lx[0] + rx[0]) - (lx[OUT_ROWS - 1] + rx[OUT_ROWS - 1])) * 0.5f
        } else 0f
        return LaneLines(rows, lx, rx, if (trValid[0]) sideConf[0] else 0f, if (trValid[1]) sideConf[1] else 0f,
            conf.coerceIn(0f, 1f), offset, lm, rm, widthMem, curvature)
    }

    private fun slopeOf(si: Int): Float = -S_MAX + si * (2f * S_MAX / (NS - 1))

    companion object {
        const val DEFAULT_HORIZON = 0.5f
        /** tanHalfV/(tanHalfH*h) for a 4:3 stream at 1.25 m: 0.75/1.25. */
        const val DEFAULT_METRE_SLOPE = 0.6f

        private const val TOP_MARGIN = 0.06f
        private const val PAINT_WIDTH_M = 0.15f
        private const val CHROMA_FREE = 35f
        private const val RIDGE_ABS = 8f
        private const val RIDGE_NOISE_MULT = 4f
        private const val ABOVE_MEDIAN = 8f
        private const val SYM_ABS = 12f
        private const val SYM_REL = 0.6f
        private const val FLANK_CHROMA = 45f
        private const val MAX_PTS = 2000

        private const val NS = 33
        private const val S_MAX = 2.4f
        private const val NXB = 128
        private const val XB_MIN = -0.5f
        private const val XB_MAX = 1.5f
        private const val MAX_PEAKS = 6
        private const val MIN_VOTES = 5f
        private const val INLIER_TOL = 0.024f
        private const val MIN_ROWS = 0.18f

        private const val MIN_LANE_M = 2.5f
        private const val MAX_LANE_M = 4.5f
        private const val MAX_SIDE_M = 2.8f
        private const val CROSS_M = 0.3f
        private const val VP_TOL = 0.10f
        private const val MAX_SINGLE_M = 2.4f
        private const val SINGLE_MIN_ROWS = 0.35f
        private const val SINGLE_WEIGHT = 0.8f
        private const val NOMINAL_HALF_M = 1.75f

        private const val FULL_ROWS = 0.45f
        private const val GATE_XB = 0.12f
        private const val GATE_S = 0.6f
        private const val TRACK_ALPHA = 0.5f
        private const val STALE_FRAMES = 4
        private const val DROP_FRAMES = 6
        private const val MIN_HITS = 4
        private const val CONF_ALPHA = 0.25f
        private const val OUT_ROWS = 12
    }
}
