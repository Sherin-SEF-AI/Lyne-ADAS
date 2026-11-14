package com.lyne.adas.l1.inference

import android.graphics.Bitmap

/**
 * Thin Android adapter around [LaneLineCore]: copies the analysis bitmap's pixels into a reused buffer
 * and runs the pure detector. Cheap (a few ms on the 256 px frame), so it runs on the inference thread
 * with every object frame. Geometry hints may be set from any thread.
 */
class LaneLineDetector {
    private val core = LaneLineCore()
    private var pixels = IntArray(0)

    fun setGeometry(horizonY: Float, tanHalfH: Float, tanHalfV: Float, cameraHeightM: Float) =
        core.setGeometry(horizonY, tanHalfH, tanHalfV, cameraHeightM)

    fun detect(bmp: Bitmap): LaneLines {
        val w = bmp.width; val h = bmp.height
        if (pixels.size != w * h) { pixels = IntArray(w * h); core.reset() }
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        return core.detect(pixels, w, h)
    }

    val lastPointCount: Int get() = core.lastPointCount
}
