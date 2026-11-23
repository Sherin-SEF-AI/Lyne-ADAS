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
        /** Max outward drift of a road edge per unit of image height going up (gentle curves only). */
        private const val MAX_OUTWARD_SLOPE = 0.2f
        /** The first (nearest) accepted row must span at least this share of the image width. */
        private const val MIN_FIRST_WIDTH = 0.12f
        /** Near rows allowed to widen freely before the no-widening clamp applies. */
        private const val FREE_ROWS = 3
        /** A start row must show at least this share of the widest near-road row. */
        private const val FULL_WIDTH_SHARE = 0.8f

        private fun sampleRows(topY: Float, bottom: Float = BOTTOM): FloatArray {
            val top = topY.coerceIn(0f, bottom - 0.05f)
            // Denser near the horizon, where the road narrows fastest.
            return FloatArray(N) { i -> val t = i / (N - 1f); bottom - (bottom - top) * (1f - (1f - t) * (1f - t)) }
        }

        /**
         * The drivable road connected to the car, from the segmentation mask, cleaned up for display:
         *  - thin gaps (painted lane markings, speckle) are closed so the road reads as one surface;
         *  - only the region connected to the car is kept (no pavement islands or far side roads);
         *  - a sudden widening (leak into a side road / open ground) is clamped;
         *  - near rows hidden by the bonnet and short holes are bridged.
         * @param topY don't extend above this image y (horizon, or the bottom of the vehicle ahead).
         * @param egoX image x of the car's own path at the bottom (0.5 for a centred mount).
         */
        fun fromMask(mask: ByteArray, w: Int, h: Int, topY: Float, egoX: Float = 0.5f): Corridor? {
            if (w <= 1 || h <= 1 || mask.size < w * h) return null
            return scan(closeGaps(mask, w, h), w, h, topY, egoX, BOTTOM, 0)
        }

        private fun scan(m: ByteArray, w: Int, h: Int, topY: Float, egoX: Float, bottom: Float, depth: Int): Corridor? {
            val rows = sampleRows(topY, bottom)
            if (depth == 0) {
                // A dashboard or bonnet with a curved edge hides the near road unevenly, so the lowest
                // visible rows show only a sliver (often one lane). Start at the first row that shows
                // (nearly) the full road width instead; the no-widening rules then hold from there.
                val start = fullWidthStart(m, w, h, rows, egoX)
                if (start > 0 && rows[start] > topY + 0.1f) return scan(m, w, h, topY, egoX, rows[start], 1)
            }
            val left = FloatArray(N) { Float.NaN }; val right = FloatArray(N) { Float.NaN }
            var ref = (egoX * w).toInt().coerceIn(0, w - 1)
            var maxWidth = -1; var goodRows = 0
            var lastGood = -1; var gap = 0
            for (i in 0 until N) {
                val my = (rows[i] * h).toInt().coerceIn(0, h - 1)
                val base = my * w
                fun on(x: Int) = m[base + x].toInt() == 1
                var seed = -1
                val reach = maxOf(2, (w * if (lastGood < 0) 0.25f else 0.08f).toInt())
                for (d in 0..reach) {
                    if (ref - d >= 0 && on(ref - d)) { seed = ref - d; break }
                    if (ref + d < w && on(ref + d)) { seed = ref + d; break }
                }
                if (seed < 0) {
                    // Bonnet / bottom-edge rows may be empty: keep looking a little way up.
                    if (lastGood < 0) { if (i > N / 4) return null else continue }
                    if (++gap > 3) break else continue
                }
                var a = seed; while (a > 0 && on(a - 1)) a--
                var b = seed; while (b < w - 1 && on(b + 1)) b++
                // The near road is wide. A thin first run is a sliver beside the bonnet, a close car or
                // the screen edge; anchoring on it would keep the whole corridor thin.
                val minRun = if (lastGood < 0) maxOf(2, (w * MIN_FIRST_WIDTH).toInt()) else maxOf(2, w / 100)
                if (b - a + 1 < minRun) {
                    if (lastGood < 0) { if (i > N / 4) return null else continue }
                    if (++gap > 3) break else continue
                }
                // Start only where the road continues: a lone wide row at the very bottom is
                // usually an edge artefact (dashboard reflection, screen bezel when bench testing).
                if (lastGood < 0 && i + 1 < N) {
                    val ny = (rows[i + 1] * h).toInt().coerceIn(0, h - 1)
                    val c = (a + b) / 2
                    if (m[ny * w + c].toInt() != 1) { if (i > N / 4) return null else continue }
                }
                goodRows++
                // The road can't get much wider going away from the camera; a jump means it leaked into
                // a side road or open ground. Clamp to the widest nearer row around the same centre
                // (not just the previous row, so a short notch from a segmentation hole can recover).
                // The nearest few rows may be partly hidden (bonnet, close vehicle), so they can widen.
                if (goodRows > FREE_ROWS && maxWidth > 0 && b - a + 1 > maxWidth * 1.15f + 1) {
                    val c = (a + b) / 2f; val half = maxWidth / 2f
                    a = (c - half).toInt().coerceAtLeast(0); b = (c + half).toInt().coerceAtMost(w - 1)
                }
                var l = a.toFloat() / w; var r = (b + 1).toFloat() / w
                if (lastGood >= 0) {
                    // Going away from the camera a road edge moves inward (or bends gently with a
                    // curve). An edge sweeping outward fast is a leak into verge, hedge or trees.
                    val maxOut = MAX_OUTWARD_SLOPE * (rows[lastGood] - rows[i])
                    l = maxOf(l, left[lastGood] - maxOut); r = minOf(r, right[lastGood] + maxOut)
                    if (r - l < 0.01f) { if (++gap > 3) break; continue }
                }
                left[i] = l; right[i] = r
                maxWidth = maxOf(maxWidth, ((r - l) * w).toInt(), 1)
                ref = (((l + r) / 2f) * w).toInt().coerceIn(0, w - 1)
                lastGood = i; gap = 0
            }
            if (lastGood < 0) return null
            // The road starts well above the frame bottom (bonnet, or a screen edge on the bench):
            // resample from where it starts rather than stretching the first row down over it.
            var first = 0; while (left[first].isNaN()) first++
            if (first > 2 && depth == 0 && rows[first] > topY + 0.1f)
                return scan(m, w, h, topY, egoX, rows[first], 1)
            bridge(left, right, lastGood)
            // A median over 5 rows removes short notches (holes at zebra crossings, shadows) and
            // spikes before the averaging pass, which would otherwise just blur them.
            return Corridor(rows, smooth(smooth(median5(left))), smooth(smooth(median5(right)))).takeIf { it.length >= 3 }
        }

        /**
         * Index of the nearest sample row (in the near half) whose road run around [egoX] is at least
         * [FULL_WIDTH_SHARE] of the widest such run; 0 when the bottom row already qualifies.
         */
        private fun fullWidthStart(m: ByteArray, w: Int, h: Int, rows: FloatArray, egoX: Float): Int {
            val widths = IntArray(N / 2)
            val ref = (egoX * w).toInt().coerceIn(0, w - 1)
            val reach = maxOf(2, (w * 0.25f).toInt())
            for (i in widths.indices) {
                val base = (rows[i] * h).toInt().coerceIn(0, h - 1) * w
                var seed = -1
                for (d in 0..reach) {
                    if (ref - d >= 0 && m[base + ref - d].toInt() == 1) { seed = ref - d; break }
                    if (ref + d < w && m[base + ref + d].toInt() == 1) { seed = ref + d; break }
                }
                if (seed < 0) continue
                var a = seed; while (a > 0 && m[base + a - 1].toInt() == 1) a--
                var b = seed; while (b < w - 1 && m[base + b + 1].toInt() == 1) b++
                widths[i] = b - a + 1
            }
            val max = widths.maxOrNull() ?: 0
            if (max <= 0) return 0
            for (i in widths.indices) if (widths[i] >= max * FULL_WIDTH_SHARE) return i
            return 0
        }

        /** 5-row running median over the valid prefix. */
        internal fun median5(v: FloatArray): FloatArray {
            val out = v.copyOf()
            var n = 0; while (n < v.size && !v[n].isNaN()) n++
            val win = FloatArray(5)
            for (i in 2 until n - 2) {
                for (k in 0 until 5) win[k] = v[i - 2 + k]
                win.sort(); out[i] = win[2]
            }
            return out
        }

        /** Morphological closing of small holes: horizontal gaps up to ~w/10, vertical up to 2 rows. */
        internal fun closeGaps(mask: ByteArray, w: Int, h: Int): ByteArray {
            val m = mask.copyOf(w * h)
            val maxH = maxOf(2, w / 10)
            for (y in 0 until h) {
                val base = y * w
                var x = 0
                while (x < w) {
                    if (m[base + x].toInt() == 0) {
                        var e = x; while (e < w && m[base + e].toInt() == 0) e++
                        if (x > 0 && e < w && e - x <= maxH) for (k in x until e) m[base + k] = 1
                        x = e
                    } else x++
                }
            }
            for (x in 0 until w) {
                var y = 0
                while (y < h) {
                    if (m[y * w + x].toInt() == 0) {
                        var e = y; while (e < h && m[e * w + x].toInt() == 0) e++
                        if (y > 0 && e < h && e - y <= 2) for (k in y until e) m[k * w + x] = 1
                        y = e
                    } else y++
                }
            }
            return m
        }

        /** Fill leading misses with the first valid row and interpolate interior holes. */
        private fun bridge(left: FloatArray, right: FloatArray, lastGood: Int) {
            var first = 0; while (first <= lastGood && left[first].isNaN()) first++
            for (i in 0 until first) { left[i] = left[first]; right[i] = right[first] }
            var i = first
            while (i <= lastGood) {
                if (left[i].isNaN()) {
                    var j = i; while (j <= lastGood && left[j].isNaN()) j++
                    val a = i - 1; val b = j
                    for (k in i until j) {
                        val t = (k - a).toFloat() / (b - a)
                        left[k] = left[a] + (left[b] - left[a]) * t; right[k] = right[a] + (right[b] - right[a]) * t
                    }
                    i = j
                } else i++
            }
            for (k in lastGood + 1 until left.size) { left[k] = Float.NaN; right[k] = Float.NaN }
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
class CorridorSmoother(private val tauMs: Float = 170f, private val holdMs: Long = 700) {
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
        val kIn = 1f - exp(-dtMs / (tauMs * 2f))
        if (c != null) {
            lastSeenNs = nowNs
            val fresh = left[0].isNaN() || alpha < 0.05f
            for (i in 0 until Corridor.N) rows[i] = if (fresh) c.rows[i] else rows[i] + (c.rows[i] - rows[i]) * k
            for (i in 0 until Corridor.N) {
                val l = c.left[i]; val r = c.right[i]
                if (l.isNaN() || r.isNaN()) { left[i] = Float.NaN; right[i] = Float.NaN; continue }
                if (fresh || left[i].isNaN()) { left[i] = l; right[i] = r }
                else {
                    // Segmentation dropouts shrink the corridor for a frame or two; let edges move
                    // inward more slowly than outward so the shape doesn't flicker with them.
                    left[i] += (l - left[i]) * (if (l > left[i]) kIn else k)
                    right[i] += (r - right[i]) * (if (r < right[i]) kIn else k)
                }
            }
            alpha += (1f - alpha) * (1f - exp(-dtMs / 180f))
        } else if (nowNs - lastSeenNs > holdMs * 1_000_000L) {
            alpha += (0f - alpha) * (1f - exp(-dtMs / 220f))
        }
        return if (alpha < 0.02f || left[0].isNaN()) null else Corridor(rows, left, right)
    }
}
