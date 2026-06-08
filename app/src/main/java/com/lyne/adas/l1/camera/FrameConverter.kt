package com.lyne.adas.l1.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts a CameraX RGBA_8888 [ImageProxy] into a square detection bitmap, applying sensor
 * rotation so the result is in display orientation. Independent x/y squash to the square preserves
 * normalized coordinates: a detector box at (nx,ny) in the square is the same (nx,ny) in the
 * display image, which is what the HUD overlay assumes.
 *
 * We use RGBA_8888 analysis output instead of manual YUV_420_888 conversion: fewer per-frame ops,
 * no native YUV code, and robust across OEM camera HALs (see CLAUDE.md for the rationale).
 *
 * All scratch (source bitmap, row buffer, canvas, matrix) is allocated once and reused.
 */
class FrameConverter {
    private var source: Bitmap? = null
    private var packed: ByteBuffer? = null
    private val canvas = Canvas()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Display-oriented image dimensions (post-rotation); used by the HUD to map normalized coords. */
    @Volatile var displayWidth = 0
        private set
    @Volatile var displayHeight = 0
        private set

    fun convert(image: ImageProxy, dst: Bitmap, rotationDegrees: Int) {
        val w = image.width
        val h = image.height
        val src = ensureSource(w, h)
        copyRgba(image, src, w, h)

        displayWidth = if (rotationDegrees == 90 || rotationDegrees == 270) h else w
        displayHeight = if (rotationDegrees == 90 || rotationDegrees == 270) w else h
        val s = dst.width.toFloat()

        matrix.reset()
        matrix.postTranslate(-w / 2f, -h / 2f)
        matrix.postRotate(rotationDegrees.toFloat())
        matrix.postScale(s / displayWidth, s / displayHeight)
        matrix.postTranslate(s / 2f, s / 2f)

        canvas.setBitmap(dst)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, matrix, paint)
        canvas.setBitmap(null)
    }

    private fun ensureSource(w: Int, h: Int): Bitmap {
        val cur = source
        if (cur != null && cur.width == w && cur.height == h) return cur
        cur?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { source = it }
    }

    private fun copyRgba(image: ImageProxy, dst: Bitmap, w: Int, h: Int) {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        if (rowStride == w * 4) {
            buffer.rewind()
            dst.copyPixelsFromBuffer(buffer)
            return
        }
        // Strided rows: repack tightly once into a reusable buffer.
        val needed = w * h * 4
        val pk = packed?.takeIf { it.capacity() == needed } ?: ByteBuffer.allocateDirect(needed).also { packed = it }
        pk.rewind()
        val rowTmp = ByteArray(w * 4)
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            buffer.get(rowTmp, 0, w * 4)
            pk.put(rowTmp)
        }
        pk.rewind()
        dst.copyPixelsFromBuffer(pk)
    }

    fun recycle() { source?.recycle(); source = null; packed = null }
}
