package com.lyne.adas.l1.camera

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cheap image-health statistics from a sparse grid of an RGBA_8888 frame (no allocation, absolute
 * buffer reads so the buffer position is untouched): mean luma, luma standard deviation across the
 * grid, and "detail" = mean absolute luma difference between each grid sample and its near
 * neighbours (a covered, fogged or smeared lens has almost none).
 *
 * Reused per analyzer; read the fields right after [compute] on the same thread.
 */
class FrameStats(private val cols: Int = 16, private val rows: Int = 12) {
    var meanLuma = 0f; private set
    var lumaStd = 0f; private set
    var detail = 0f; private set

    fun compute(buf: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int = 4) {
        if (width < 8 || height < 8) { meanLuma = 0f; lumaStd = 0f; detail = 0f; return }
        val step = (width / 160).coerceAtLeast(2) // neighbour offset, ~0.6% of the width
        var sum = 0L; var sumSq = 0L; var diff = 0L; var n = 0
        for (r in 0 until rows) {
            val y = ((r + 0.5f) * height / rows).toInt().coerceIn(0, height - 1 - step)
            for (c in 0 until cols) {
                val x = ((c + 0.5f) * width / cols).toInt().coerceIn(0, width - 1 - step)
                val l = luma(buf, x, y, rowStride, pixelStride)
                val lr = luma(buf, x + step, y, rowStride, pixelStride)
                val ld = luma(buf, x, y + step, rowStride, pixelStride)
                sum += l; sumSq += l * l; n++
                diff += abs(l - lr) + abs(l - ld)
            }
        }
        val mean = sum.toFloat() / n
        meanLuma = mean
        lumaStd = sqrt((sumSq.toFloat() / n - mean * mean).coerceAtLeast(0f))
        detail = diff.toFloat() / (2 * n)
    }

    private fun luma(buf: ByteBuffer, x: Int, y: Int, rowStride: Int, pixelStride: Int): Int {
        val i = y * rowStride + x * pixelStride
        if (i + 2 >= buf.limit()) return 0
        val r = buf.get(i).toInt() and 0xFF
        val g = buf.get(i + 1).toInt() and 0xFF
        val b = buf.get(i + 2).toInt() and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}
