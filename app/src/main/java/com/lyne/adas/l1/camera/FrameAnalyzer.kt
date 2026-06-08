package com.lyne.adas.l1.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.lyne.adas.l1.inference.CameraFrame
import com.lyne.adas.l1.inference.InferenceScheduler
import com.lyne.adas.l1.logging.Log
import com.lyne.adas.l1.perf.PerfMonitor

/**
 * CameraX analyzer. With STRATEGY_KEEP_ONLY_LATEST the camera always hands us the newest frame;
 * we copy it into a pooled bitmap, close the ImageProxy immediately (freeing the camera), and hand
 * the bitmap to the inference scheduler. If the pool is empty or the scheduler is busy, we drop the
 * frame (counted) — the analyzer never blocks.
 */
class FrameAnalyzer(
    private val pool: ImageBuffers,
    private val scheduler: InferenceScheduler,
    private val perf: PerfMonitor,
) : ImageAnalysis.Analyzer {

    private val converter = FrameConverter()
    private var frameId = 0L

    val displayWidth: Int get() = converter.displayWidth
    val displayHeight: Int get() = converter.displayHeight

    override fun analyze(image: ImageProxy) {
        val id = frameId++
        val dst = pool.acquire()
        if (dst == null) {
            perf.frameDropped()
            image.close()
            return
        }
        try {
            converter.convert(image, dst, image.imageInfo.rotationDegrees)
        } catch (t: Throwable) {
            Log.e("FrameAnalyzer", "convert failed", t)
            pool.release(dst)
            image.close()
            return
        } finally {
            image.close()
        }

        val frame = CameraFrame(dst, timestampNs = System.nanoTime(), rotationDegrees = 0, frameId = id)
        if (!scheduler.submit(frame)) {
            pool.release(dst) // dropped by scheduler; return buffer immediately
        }
    }

    fun recycle() = converter.recycle()
}
