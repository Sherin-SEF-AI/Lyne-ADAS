package com.lyne.adas.l1.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import com.lyne.adas.l1.config.TierProfile
import com.lyne.adas.l1.logging.Log
import com.lyne.adas.l1.perf.DegradationPlan
import com.lyne.adas.l1.perf.PerfMonitor
import com.lyne.adas.l1.perf.ThermalPolicy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the single inference thread and a bounded in-flight budget. The camera analyzer calls
 * [submit]; if the worker is busy beyond the queue depth the frame is dropped immediately (and
 * counted) rather than blocking the analyzer.
 *
 * Every processed frame runs the object detector (collision/pedestrian warnings) plus the cheap
 * classical lane-line detector. Signs and drivable-area segmentation run on slower cadences counted in
 * object frames, and a [ThermalPolicy] sheds them first when the device heats up (see [DegradationPlan]);
 * only at the last step does the object model drop to its smaller input.
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
    @Volatile private var closed = false

    // Thermal degradation (policy evaluated on the inference thread).
    @Volatile private var thermalStatus = ThermalPolicy.STATUS_NONE
    private val policy = ThermalPolicy(profile.targetFps.toFloat(), canUseSmallModel = detectors.smallObjFactory != null)
    private var lastPolicyMs = 0L
    private var smallObj: Detector<List<Detection>>? = null
    private var smallObjFailed = false
    private var objFrames = 0L
    private var objInputPx = detectors.objInputPx

    // Carried-over slow-cadence results. lastLane is written by laneWorker, read by the main worker.
    @Volatile private var lastLane: LaneResult = LaneResult.EMPTY
    private var lastSign: SignResult = SignResult.NONE
    private var lastLines: LaneLines = LaneLines.NONE
    private val lastBackend = AtomicReference(detectors.obj.backend)

    /** OS thermal status (PowerManager.THERMAL_STATUS_*). */
    fun setThermalStatus(status: Int) { thermalStatus = status }

    /** Camera geometry hint for the lane-line detector (horizon + metric scale of the ground plane). */
    fun setLaneGeometry(horizonY: Float, tanHalfH: Float, tanHalfV: Float, cameraHeightM: Float) {
        detectors.lines?.setGeometry(horizonY, tanHalfH, tanHalfV, cameraHeightM)
    }

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

    private fun updatePlan(): DegradationPlan {
        val now = System.currentTimeMillis()
        if (now - lastPolicyMs < POLICY_PERIOD_MS) return policy.plan
        lastPolicyMs = now
        val before = policy.level
        val plan = policy.update(thermalStatus, perf.snapshotFps(), now)
        if (plan.level != before) Log.i(TAG, "thermal=${ThermalPolicy.statusLabel(thermalStatus)} degrade ${before} -> ${plan.level} (${plan.label})")
        perf.degradeLevel = plan.level
        perf.degradeLabel = plan.label
        return plan
    }

    /** Object detector for this plan; sets [objInputPx] to its input edge. */
    private fun objectDetector(plan: DegradationPlan): Detector<List<Detection>> {
        if (plan.smallObjectModel && !smallObjFailed) {
            val small = smallObj ?: detectors.smallObjFactory?.invoke().also {
                smallObj = it
                if (it == null) smallObjFailed = true else Log.i(TAG, "thermal: switched to small object model ${it.name}")
            }
            if (small != null) { objInputPx = DetectorProvider.NANO_INPUT_PX; return small }
        }
        objInputPx = detectors.objInputPx
        return detectors.obj
    }

    private fun process(frame: CameraFrame) {
        val t0 = System.nanoTime()
        try {
            val plan = updatePlan()
            val objCad = profile.objectCadence.coerceAtLeast(1)
            val runObj = frame.frameId % objCad == 0L
            if (!runObj) return // not a fusion frame (see note below)
            val n = objFrames++

            val objDet = objectDetector(plan)
            val s0 = System.nanoTime()
            val detections = objDet.detect(frame)
            perf.stage("object", System.nanoTime() - s0)
            lastBackend.set(objDet.backend)

            // Painted lane lines: cheap, every object frame (LDW needs the rate more than segmentation does).
            val lines = detectors.lines
            if (lines != null) {
                val s = System.nanoTime()
                try { lastLines = lines.detect(frame.bitmap) } catch (t: Throwable) { Log.e(TAG, "lines failed", t) }
                perf.stage("lines", System.nanoTime() - s)
            }

            // Dispatch the drivable-area segmenter asynchronously (own thread) if it isn't already
            // running. Copy into a private bitmap so the pooled frame can be recycled immediately.
            val laneCad = profile.laneCadence * plan.segEvery
            if (laneCad > 0 && n % laneCad == 0L && laneBusy.compareAndSet(false, true)) {
                val priv = copyForLane(frame.bitmap)
                val laneFrame = CameraFrame(priv, frame.timestampNs, 0, frame.frameId)
                laneWorker.execute {
                    val s = System.nanoTime()
                    try { lastLane = detectors.lane.detect(laneFrame) }
                    catch (t: Throwable) { Log.e(TAG, "lane failed", t) }
                    finally { perf.stage("lane", System.nanoTime() - s); laneBusy.set(false) }
                }
            }

            var ranSign = false
            val signCad = profile.signCadence
            if (plan.signEnabled && signCad > 0 && n % signCad == 0L) {
                val s = System.nanoTime()
                lastSign = detectors.sign.detect(frame)
                perf.stage("sign", System.nanoTime() - s)
                ranSign = true
            }

            // Frames where the object model was skipped are NOT fusion frames: feeding them as
            // "no detections" made every track register a miss and inflated FPS.
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
                    laneLines = lastLines,
                    objectInputPx = objInputPx,
                )
            )
            perf.frameDone(System.nanoTime() - t0)
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed", t)
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
        runCatching { smallObj?.close() }; smallObj = null
        laneBitmap?.recycle(); laneBitmap = null; laneCanvas = null
    }

    companion object {
        private const val TAG = "InferenceScheduler"
        private const val POLICY_PERIOD_MS = 250L
    }
}
