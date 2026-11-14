package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraGeometry
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.FeatureFlags
import com.lyne.adas.l1.inference.Backend
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.LaneResult
import com.lyne.adas.l1.inference.ObjectClass
import com.lyne.adas.l1.inference.Perception
import com.lyne.adas.l1.inference.SignResult
import kotlin.math.atan
import kotlin.math.tan
import java.util.Random

/** A simulated vehicle in road coordinates (metres): depth Z ahead, lateral X (+right). */
data class SimObject(var z: Float, var x: Float, val width: Float, val height: Float, val cls: ObjectClass = ObjectClass.CAR)

/**
 * Projects simulated objects through a TRUE camera (pinhole, height, pitch) and adds per-edge
 * Gaussian localization noise in detector-input pixels, plus occasional missed detections. This is
 * the noise that made v1 hallucinate collisions.
 */
class Projector(
    val hFovDeg: Float = 66f,
    val vFovDeg: Float = 51f,
    val camHeight: Float = 1.30f,
    val pitchRad: Float = 0.045f,
    val inputPx: Int = 256,
    val edgeNoisePx: Float = 1.3f,
    val missRate: Float = 0.05f,
    seed: Long = 42L,
    /** Road-bounce pitch oscillation amplitude (rad) - rough roads shake the mount. */
    val bounceRad: Float = 0f,
) {
    var timeSec = 0f
    private val rnd = Random(seed)
    private val tH = tan(Math.toRadians(hFovDeg / 2.0)).toFloat()
    private val tV = tan(Math.toRadians(vFovDeg / 2.0)).toFloat()

    private fun ny(heightAboveGround: Float, z: Float): Float {
        val bounce = bounceRad * kotlin.math.sin(timeSec * 9f) + bounceRad * 0.5f * kotlin.math.sin(timeSec * 23f)
        val dep = atan((camHeight - heightAboveGround) / z) - (pitchRad + bounce)
        return 0.5f + tan(dep) / (2f * tV)
    }
    private fun nx(x: Float, z: Float): Float = 0.5f + (x / z) / (2f * tH)
    private fun n(): Float = (rnd.nextGaussian() * edgeNoisePx / inputPx).toFloat()

    fun detect(objs: List<SimObject>): List<Detection> = objs.mapNotNull { o ->
        if (o.z < 1f || rnd.nextFloat() < missRate) return@mapNotNull null
        val l = nx(o.x - o.width / 2f, o.z) + n(); val r = nx(o.x + o.width / 2f, o.z) + n()
        val t = ny(o.height, o.z) + n(); val b = ny(0f, o.z) + n()
        val box = BBox(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        if (box.width <= 0f || box.height <= 0f) null else Detection(o.cls, 0.55f + 0.4f * rnd.nextFloat(), box)
    }

    /** Geometry the app would build: given (possibly wrong) height and pitch estimates. */
    fun geometry(heightGuess: Float = camHeight, pitchGuess: Float = pitchRad, pitchSigma: Float = 0.012f) =
        CameraGeometry(hFovDeg, vFovDeg, heightGuess, pitchGuess, 0f, 1f, pitchSigma)
}

fun perception(frame: Long, tNs: Long, dets: List<Detection>) = Perception(
    frameId = frame, timestampNs = tNs, detections = dets, lane = LaneResult.EMPTY, sign = SignResult.NONE,
    objectBackend = Backend.XNNPACK_CPU, ranLaneThisFrame = false, ranSignThisFrame = false,
)

val FCW_ONLY = FeatureFlags(fcw = true, vru = false, ldw = false, headway = false, tsr = false)

/** Runs [seconds] of a scenario at [fps]; [step] moves the world by dt and returns ego speed. */
fun runSim(
    proj: Projector,
    objs: List<SimObject>,
    seconds: Float,
    fps: Float,
    engine: FusionEngine = FusionEngine(AdasConfig.DEFAULT, FCW_ONLY, autoCalEnabled = false),
    geom: CameraGeometry = proj.geometry(),
    step: (dt: Float, t: Float) -> Float,
    pixels: LeadPixelSource? = null,
    onFrame: (t: Float, r: FusionResult) -> Unit = { _, _ -> },
): List<AdasEvent> {
    val events = ArrayList<AdasEvent>()
    val dt = 1f / fps
    var t = 0f; var frame = 0L
    var speed = step(0f, 0f)
    while (t < seconds) {
        proj.timeSec = t
        val dets = proj.detect(objs)
        val r = engine.update(perception(frame, (t * 1e9).toLong(), dets), speed, 0f, geom, proj.inputPx, (t * 1000).toLong(), pixels)
        events += r.events
        onFrame(t, r)
        t += dt; frame++
        speed = step(dt, t)
    }
    return events
}

/**
 * Synthetic rear view of the lead for the brake-light cue: grey body, dark-red tail-lamp housings
 * at both sides and a small centre lamp; [braking] lights them up. Per-pixel noise included.
 */
class SimTailLights(seed: Long = 3L, var braking: Boolean = false) : LeadPixelSource {
    private val rnd = Random(seed)
    override fun sample(box: BBox, out: IntArray, cols: Int, rows: Int): Boolean {
        for (r in 0 until rows) for (c in 0 until cols) {
            val fx = (c + 0.5f) / cols; val fy = (r + 0.5f) / rows
            val side = (fx < 0.16f || fx > 0.84f) && fy in 0.32f..0.58f
            val centre = fx in 0.42f..0.58f && fy < 0.12f
            var rr: Int; var gg: Int; var bb: Int
            if (side || centre) {
                if (braking) { rr = 235; gg = 70; bb = 60 } else { rr = 125; gg = 55; bb = 50 }
            } else { rr = 105; gg = 105; bb = 110 }
            val n = (rnd.nextGaussian() * 12).toInt() // brightness noise, then per-channel noise
            rr = (rr + n + (rnd.nextGaussian() * 8).toInt()).coerceIn(0, 255)
            gg = (gg + n + (rnd.nextGaussian() * 8).toInt()).coerceIn(0, 255)
            bb = (bb + n + (rnd.nextGaussian() * 8).toInt()).coerceIn(0, 255)
            out[r * cols + c] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return true
    }
}
