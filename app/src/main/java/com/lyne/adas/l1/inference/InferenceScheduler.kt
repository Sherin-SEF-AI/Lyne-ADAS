package com.lyne.adas.l1.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import com.lyne.adas.l1.config.TierProfile
import com.lyne.adas.l1.logging.Log
import com.lyne.adas.l1.perf.PerfMonitor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the single inference thread and a bounded in-flight budget. The camera analyzer calls
 * [submit]; if the worker is busy beyond the queue depth the frame is dropped immediately (and
 * counted) rather than blocking the analyzer. Models run on a staggered cadence that can be
 * stretched at runtime via [cadenceMultiplier] under thermal/load pressure.
 */
class InferenceScheduler(
    private val detectors: DetectorSet,
    private val profile: TierProfile,
    private val perf: PerfMonitor,
    private val onResult: (Perception) -> Unit,
    private val recycle: (CameraFrame) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lyne-inference").apply { priority = Thread.MAX_PRIORITY - 1 } }
    // Drivable-area segmentation is heavier; run it on its OWN thread so it never stalls object/sign.
    private val laneWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "lyne-lane") }
    private val laneBusy = AtomicBoolean(false)
    private var laneBitmap: Bitmap? = null
    private var laneCanvas: Canvas? = null

    private val inFlight = AtomicInteger(0)
    private val queueDepth = profile.inferenceQueueDepth.coerceAtLeast(1)
    @Volatile private var cadenceMultiplier = 1
    @Volatile private var closed = false

    // Carried-over slow-cadence results. lastLane is written by laneWorker, read by the main worker.
    @Volatile private var lastLane: LaneResult = LaneResult.EMPTY
    private var lastSign: SignResult = SignResult.NONE
    private val lastBackend = AtomicReference(detectors.obj.backend)

    fun setCadenceMultiplier(m: Int) { cadenceMultiplier = m.coerceIn(1, 8) }

    /** Returns true if accepted for processing; false if dropped (analyzer should recycle). */
    fun submit(frame: CameraFrame): Boolean {
        if (closed) return false
        if (inFlight.get() >= queueDepth) {
            perf.frameDropped()
            return false
        }
        inFlight.incrementAndGet()
        perf.frameSubmitted()
        try {
            worker.execute { process(frame) }
        } catch (t: Throwable) {
            inFlight.decrementAndGet()
            return false
        }
        return true
    }

    private fun process(frame: CameraFrame) {
        val t0 = System.nanoTime()
        try {
            val objCad = (profile.objectCadence * cadenceMultiplier).coerceAtLeast(1)
            val laneCad = profile.laneCadence * cadenceMultiplier
            val signCad = profile.signCadence * cadenceMultiplier

            val detections = if (frame.frameId % objCad == 0L) {
                val s = System.nanoTime()
                val d = detectors.obj.detect(frame)
                perf.stage("object", System.nanoTime() - s)
                lastBackend.set(detectors.obj.backend)
                d
            } else emptyList()

            // Dispatch the drivable-area segmenter asynchronously (own thread) if it isn't already
            // running. Copy into a private bitmap so the pooled frame can be recycled immediately.
            if (laneCad > 0 && frame.frameId % laneCad == 0L && laneBusy.compareAndSet(false, true)) {
                val priv = copyForLane(frame.bitmap)
                val laneFrame = CameraFrame(priv, frame.timestampNs, 0, frame.frameId)
                laneWorker.execute {
                    val s = System.nanoTime()
                    try { lastLane = detectors.lane.detect(laneFrame) }
                    catch (t: Throwable) { Log.e("InferenceScheduler", "lane failed", t) }
                    finally { perf.stage("lane", System.nanoTime() - s); laneBusy.set(false) }
                }
            }

            var ranSign = false
            if (signCad > 0 && frame.frameId % signCad == 0L) {
                val s = System.nanoTime()
                lastSign = detectors.sign.detect(frame)
                perf.stage("sign", System.nanoTime() - s)
                ranSign = true
            }

            onResult(
                Perception(
                    frameId = frame.frameId,
                    timestampNs = frame.timestampNs,
                    detections = detections,
                    lane = lastLane,
                    sign = lastSign,
                    objectBackend = lastBackend.get(),
                    ranLaneThisFrame = false, // lane runs async on its own thread
                    ranSignThisFrame = ranSign,
                )
            )
            perf.frameDone(System.nanoTime() - t0)
        } catch (t: Throwable) {
            Log.e("InferenceScheduler", "inference failed", t)
        } finally {
            recycle(frame)
            inFlight.decrementAndGet()
        }
    }

    /** Copy the pooled frame bitmap into a private reused bitmap for the async lane worker. */
    private fun copyForLane(src: Bitmap): Bitmap {
        var bmp = laneBitmap
        if (bmp == null || bmp.width != src.width || bmp.height != src.height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
            laneBitmap = bmp
            laneCanvas = Canvas(bmp)
        }
        laneCanvas!!.drawBitmap(src, 0f, 0f, null)
        return bmp
    }

    fun close() {
        closed = true
        worker.shutdown()
        laneWorker.shutdown()
        // Wait for in-flight tasks so we don't recycle interpreters while a detect() is running.
        runCatching { worker.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { laneWorker.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
        detectors.close()
        laneBitmap?.recycle(); laneBitmap = null; laneCanvas = null
    }
}
