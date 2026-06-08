package com.lyne.adas.l1.camera

import android.graphics.Bitmap
import com.lyne.adas.l1.config.PixelFormat
import java.util.ArrayDeque

/**
 * Bounded pool of square detection bitmaps, reused across frames so the camera hot path performs
 * zero per-frame bitmap allocation. The analyzer [acquire]s, fills, and submits; the inference
 * thread [release]s when done. If the pool is empty the analyzer drops the frame.
 */
class ImageBuffers(
    private val size: Int,
    pixelFormat: PixelFormat,
    capacity: Int,
) {
    private val config = when (pixelFormat) {
        PixelFormat.ARGB_8888 -> Bitmap.Config.ARGB_8888
        PixelFormat.RGB_565 -> Bitmap.Config.RGB_565
    }
    private val pool = ArrayDeque<Bitmap>(capacity)
    private val lock = Any()

    init {
        synchronized(lock) { repeat(capacity) { pool.add(Bitmap.createBitmap(size, size, config)) } }
    }

    fun acquire(): Bitmap? = synchronized(lock) { pool.pollFirst() }

    fun release(bmp: Bitmap) = synchronized(lock) { if (!bmp.isRecycled) pool.addLast(bmp) }

    fun recycleAll() = synchronized(lock) {
        while (pool.isNotEmpty()) pool.pollFirst()?.recycle()
    }
}
