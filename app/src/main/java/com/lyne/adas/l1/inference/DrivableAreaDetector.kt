package com.lyne.adas.l1.inference

import android.graphics.Bitmap
import com.lyne.adas.l1.logging.Log

/**
 * Ultra-light drivable-area segmentation with NO model. Instead of fitting lane lines (jittery on
 * faint markings) it region-grows the contiguous road surface from a seed just in front of the car
 * over a coarse grid, then reports the left/right extent of that region per row. The HUD fills that
 * region as the green "drivable area" carpet, and the region centre drives LDW.
 *
 * Cost is trivial: a fixed ~96×72 grid, one sample per cell, one flood fill. All scratch reused.
 */
class DrivableAreaDetector : Detector<LaneResult> {
    override val name = "drivable-seg"
    override val backend = Backend.XNNPACK_CPU
    override val isStub = false

    private var pixels = IntArray(0)
    private var w = 0; private var h = 0

    private val gw = 96
    private val gh = 72
    private val visited = BooleanArray(gw * gh)
    private val queue = IntArray(gw * gh)
    private val mask = ByteArray(gw * gh)

    private val outRows = 20
    private val rows = FloatArray(outRows) { ROI_TOP + it * ((0.99f - ROI_TOP) / (outRows - 1)) }
    private val leftX = FloatArray(outRows)
    private val rightX = FloatArray(outRows)
    private var offsetEma = Float.NaN

    override fun detect(frame: CameraFrame): LaneResult {
        val bmp = frame.bitmap
        ensureBuffers(bmp)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val cellW = w.toFloat() / gw
        val cellH = h.toFloat() / gh
        val roiTopGy = (ROI_TOP * gh).toInt()

        // Seed colour = mean of a patch just in front of the car (bottom-centre).
        var sr = 0L; var sg = 0L; var sb = 0L; var sn = 0
        val sx0 = (0.40f * gw).toInt(); val sx1 = (0.60f * gw).toInt()
        val sy0 = (0.88f * gh).toInt(); val sy1 = (gh - 1)
        for (gy in sy0..sy1) for (gx in sx0..sx1) {
            val c = sample(gx, gy, cellW, cellH)
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; sn++
        }
        if (sn == 0) return LaneResult.EMPTY
        val mr = (sr / sn).toInt(); val mg = (sg / sn).toInt(); val mb = (sb / sn).toInt()

        // Flood fill the contiguous road-coloured region from the seed cells, within the ROI.
        java.util.Arrays.fill(visited, false)
        var head = 0; var tail = 0
        for (gy in sy0..sy1) for (gx in sx0..sx1) {
            val idx = gy * gw + gx
            if (!visited[idx] && near(sample(gx, gy, cellW, cellH), mr, mg, mb)) { visited[idx] = true; queue[tail++] = idx }
        }
        var count = 0
        while (head < tail) {
            val c = queue[head++]; count++
            val cx = c % gw; val cy = c / gw
            if (cx > 0) { val n = c - 1; if (!visited[n] && near(sample(cx - 1, cy, cellW, cellH), mr, mg, mb)) { visited[n] = true; queue[tail++] = n } }
            if (cx < gw - 1) { val n = c + 1; if (!visited[n] && near(sample(cx + 1, cy, cellW, cellH), mr, mg, mb)) { visited[n] = true; queue[tail++] = n } }
            if (cy > roiTopGy) { val n = c - gw; if (!visited[n] && near(sample(cx, cy - 1, cellW, cellH), mr, mg, mb)) { visited[n] = true; queue[tail++] = n } }
            if (cy < gh - 1) { val n = c + gw; if (!visited[n] && near(sample(cx, cy + 1, cellW, cellH), mr, mg, mb)) { visited[n] = true; queue[tail++] = n } }
        }

        // Per output row: left/right extent of the drivable region.
        var bottomL = Float.NaN; var bottomR = Float.NaN
        for (i in 0 until outRows) {
            val gy = (rows[i] * (gh - 1)).toInt().coerceIn(roiTopGy, gh - 1)
            var minX = -1; var maxX = -1
            val base = gy * gw
            for (gx in 0 until gw) if (visited[base + gx]) { if (minX < 0) minX = gx; maxX = gx }
            if (minX >= 0 && maxX > minX) {
                leftX[i] = minX.toFloat() / (gw - 1); rightX[i] = maxX.toFloat() / (gw - 1)
                if (i == outRows - 1) { bottomL = leftX[i]; bottomR = rightX[i] }
            } else { leftX[i] = Float.NaN; rightX[i] = Float.NaN }
        }

        val roiCells = (gh - roiTopGy) * gw
        val coverage = count.toFloat() / roiCells
        // Confidence: enough road, but not a near-uniform scene (which floods everything).
        val confidence = when {
            coverage < 0.04f -> 0f
            coverage > 0.92f -> 0.25f
            else -> (0.45f + coverage).coerceIn(0f, 0.95f)
        }

        var offset = 0f
        if (!bottomL.isNaN() && !bottomR.isNaN()) {
            val center = (bottomL + bottomR) * 0.5f
            val half = ((bottomR - bottomL) * 0.5f).coerceAtLeast(0.05f)
            val raw = (0.5f - center) / half
            offsetEma = if (offsetEma.isNaN()) raw else offsetEma + 0.35f * (raw - offsetEma)
            offset = offsetEma
        }

        for (i in visited.indices) mask[i] = if (visited[i]) 1 else 0

        return LaneResult(rows.copyOf(), leftX.copyOf(), rightX.copyOf(), offset, confidence, mask.copyOf(), gw, gh)
    }

    private fun sample(gx: Int, gy: Int, cw: Float, ch: Float): Int {
        val px = ((gx + 0.5f) * cw).toInt().coerceIn(0, w - 1)
        val py = ((gy + 0.5f) * ch).toInt().coerceIn(0, h - 1)
        return pixels[py * w + px]
    }

    private fun near(p: Int, mr: Int, mg: Int, mb: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val d = kotlin.math.abs(r - mr) + kotlin.math.abs(g - mg) + kotlin.math.abs(b - mb)
        if (d < THRESH) return true
        // Shadow-tolerant: same hue (chroma) but darker than the seed (road in shade).
        val chroma = kotlin.math.abs((r - g) - (mr - mg)) + kotlin.math.abs((g - b) - (mg - mb))
        return chroma < CHROMA_THRESH && r <= mr + 18 && g <= mg + 18 && b <= mb + 18
    }

    private fun ensureBuffers(bmp: Bitmap) {
        if (bmp.width != w || bmp.height != h) {
            w = bmp.width; h = bmp.height; pixels = IntArray(w * h); offsetEma = Float.NaN
            Log.i(name, "buffers for ${w}x$h grid ${gw}x$gh")
        }
    }

    override fun close() {}

    companion object {
        private const val ROI_TOP = 0.45f
        private const val THRESH = 100 // sum |ΔR|+|ΔG|+|ΔB| tolerance for "same road surface"
        private const val CHROMA_THRESH = 26 // hue tolerance for shadow-darkened road
    }
}
