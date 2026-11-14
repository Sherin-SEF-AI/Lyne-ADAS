package com.lyne.adas.l1.inference

import android.graphics.Bitmap
import com.lyne.adas.l1.fusion.LeadPixelSource

/**
 * Samples a regular grid of pixels inside a normalized box of the current detection bitmap, one
 * getPixels() row read per grid row into a reused buffer. Allocation-free after the first frame.
 * Set [bitmap] just before fusion and clear it right after: pooled frames are recycled.
 */
class BitmapLeadSampler : LeadPixelSource {
    @Volatile var bitmap: Bitmap? = null
    private var row = IntArray(0)

    override fun sample(box: BBox, out: IntArray, cols: Int, rows: Int): Boolean {
        val bmp = bitmap ?: return false
        if (bmp.isRecycled) return false
        val w = bmp.width; val h = bmp.height
        if (row.size < w) row = IntArray(w)
        val l = box.left * w; val t = box.top * h
        val bw = box.width * w; val bh = box.height * h
        if (bw < 1f || bh < 1f) return false
        for (r in 0 until rows) {
            val y = (t + (r + 0.5f) / rows * bh).toInt().coerceIn(0, h - 1)
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val base = r * cols
            for (c in 0 until cols) {
                val x = (l + (c + 0.5f) / cols * bw).toInt().coerceIn(0, w - 1)
                out[base + c] = row[x]
            }
        }
        return true
    }
}
