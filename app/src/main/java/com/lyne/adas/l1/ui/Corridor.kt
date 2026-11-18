package com.lyne.adas.l1.ui

import kotlin.math.abs
import kotlin.math.exp

/**
 * The ego drivable corridor for display: left/right edge x (normalized 0..1) at [Corridor.N] fixed
 * rows from the bottom of the image ([rows] descending y, near -> far). NaN where the corridor has
 * ended. Built from the drivable-area mask (only the region connected to the car, not side roads or
 * pavement islands) or from lane edges, smoothed across rows so it draws as a clean shape.
 */
class Corridor(val rows: FloatArray, val left: FloatArray, val right: FloatArray) {

    /** Number of leading (near) rows that are valid. */
    val length: Int get() {
        var n = 0
        while (n < rows.size && !left[n].isNaN() && !right[n].isNaN()) n++
        return n
    }

    companion object {
        const val N = 32
        private const val BOTTOM = 0.99f

        private fun sampleRows(topY: Float): FloatArray {
            val top = topY.coerceIn(0f, BOTTOM - 0.05f)
            // Denser near the horizon, where the road narrows fastest.
            return FloatArray(N) { i -> val t = i / (N - 1f); BOTTOM - (BOTTOM - top) * (1f - (1f - t) * (1f - t)) }
        }

        /**
         * @param topY don't extend above this image y (horizon, or the bottom of the lead vehicle).
         * @param egoX image x of the car's own path at the bottom (0.5 for a centred mount).
         */
        fun fromMask(mask: ByteArray, w: Int, h: Int, topY: Float, egoX: Float = 0.5f): Corridor? {
            if (w <= 1 || h <= 1 || mask.size < w * h) return null
            val rows = sampleRows(topY)
            val left = FloatArray(N) { Float.NaN }; val right = FloatArray(N) { Float.NaN }
            var ref = (egoX * w).toInt().coerceIn(0, w - 1)
            var prevWidth = -1
            for (i in 0 until N) {
                val my = (rows[i] * h).toInt().coerceIn(0, h - 1)
                val base = my * w
                fun on(x: Int) = mask[base + x].toInt() == 1
                // Find the drivable run containing ref, or the nearest one within reach.
                var seed = -1
                val reach = maxOf(2, (w * if (i == 0) 0.25f else 0.08f).toInt())
                for (d in 0..reach) {
                    if (ref - d >= 0 && on(ref - d)) { seed = ref - d; break }
                    if (ref + d < w && on(ref + d)) { seed = ref + d; break }
                }
                if (seed < 0) break
                var a = seed; while (a > 0 && on(a - 1)) a--
                var b = seed; while (b < w - 1 && on(b + 1)) b++
                val width = b - a + 1
                if (width < maxOf(2, w / 100)) break
                // The road can't get wider going away from the camera; a jump means it leaked into a
                // side road or an open area. Clamp to the previous width around the same centre.
                if (prevWidth > 0 && width > prevWidth * 1.15f + 1) {
                    val c = (a + b) / 2f; val half = prevWidth / 2f
                    a = (c - half).toInt().coerceAtLeast(0); b = (c + half).toInt().coerceAtMost(w - 1)
                }
                left[i] = a.toFloat() / w; right[i] = (b + 1).toFloat() / w
                prevWidth = b - a + 1
                ref = (a + b) / 2
            }
            return Corridor(rows, smooth(smooth(left)), smooth(smooth(right))).takeIf { it.length >= 3 }
        }

        /** From per-row lane edges (classical road-edge path), resampled onto the display rows. */
        fun fromEdges(srcRows: FloatArray, srcLeft: FloatArray, srcRight: FloatArray, topY: Float): Corridor? {
            if (srcRows.size < 2) return null
            val rows = sampleRows(topY)
            val left = FloatArray(N) { Float.NaN }; val right = FloatArray(N) { Float.NaN }
            for (i in 0 until N) {
                val l = interp(srcRows, srcLeft, rows[i]); val r = interp(srcRows, srcRight, rows[i])
                if (l.isNaN() || r.isNaN() || r - l < 0.005f) break
                left[i] = l; right[i] = r
            }
            return Corridor(rows, smooth(smooth(left)), smooth(smooth(right))).takeIf { it.length >= 3 }
        }

        private fun interp(ys: FloatArray, xs: FloatArray, y: Float): Float {
            var best = Float.NaN
            for (k in 0 until ys.size - 1) {
                val y0 = ys[k]; val y1 = ys[k + 1]
                if ((y in y0..y1) || (y in y1..y0)) {
                    val x0 = xs.getOrNull(k) ?: return Float.NaN; val x1 = xs.getOrNull(k + 1) ?: return Float.NaN
                    if (x0.isNaN() || x1.isNaN()) return Float.NaN
                    val t = if (abs(y1 - y0) < 1e-6f) 0f else (y - y0) / (y1 - y0)
                    best = x0 + (x1 - x0) * t; break
                }
            }
            return best
        }

        /** 5-tap moving average over the valid prefix (keeps the ends anchored). */
        internal fun smooth(v: FloatArray): FloatArray {
            val out = v.copyOf()
            var n = 0; while (n < v.size && !v[n].isNaN()) n++
            for (i in 1 until n - 1) {
                var s = 0f; var c = 0
                for (k in -2..2) { val j = i + k; if (j in 0 until n) { s += v[j]; c++ } }
                out[i] = s / c
            }
            return out
        }
    }
}

/**
 * Frame-to-frame smoothing for the corridor so the shape glides instead of flickering between model
 * updates. Call [update] each draw with the newest corridor (or null) and the wall-clock time.
 */
class CorridorSmoother(private val tauMs: Float = 90f, private val holdMs: Long = 400) {
    private var left = FloatArray(Corridor.N) { Float.NaN }
    private var right = FloatArray(Corridor.N) { Float.NaN }
    private var rows = FloatArray(Corridor.N)
    private var lastNs = 0L
    private var lastSeenNs = 0L
    /** 0..1 overall visibility, eased in and out. */
    var alpha = 0f; private set

    fun update(c: Corridor?, nowNs: Long): Corridor? {
        val dtMs = if (lastNs == 0L) 16f else ((nowNs - lastNs) / 1e6f).coerceIn(0f, 200f)
        lastNs = nowNs
        val k = 1f - exp(-dtMs / tauMs)
        if (c != null) {
            lastSeenNs = nowNs
            val fresh = left[0].isNaN() || alpha < 0.05f
            for (i in 0 until Corridor.N) rows[i] = if (fresh) c.rows[i] else rows[i] + (c.rows[i] - rows[i]) * k
            for (i in 0 until Corridor.N) {
                val l = c.left[i]; val r = c.right[i]
                if (l.isNaN() || r.isNaN()) { left[i] = Float.NaN; right[i] = Float.NaN; continue }
                if (fresh || left[i].isNaN()) { left[i] = l; right[i] = r }
                else { left[i] += (l - left[i]) * k; right[i] += (r - right[i]) * k }
            }
            alpha += (1f - alpha) * (1f - exp(-dtMs / 180f))
        } else if (nowNs - lastSeenNs > holdMs * 1_000_000L) {
            alpha += (0f - alpha) * (1f - exp(-dtMs / 220f))
        }
        return if (alpha < 0.02f || left[0].isNaN()) null else Corridor(rows, left, right)
    }
}
