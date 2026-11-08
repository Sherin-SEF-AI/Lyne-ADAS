package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraGeometry
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.ObjectClass
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One tracked object with a stable [id]. Owns all temporal state that makes warnings stable:
 *  - a range Kalman filter (distance + closing speed) fed by [RangeEstimator]
 *  - a sliding-window looming-rate fit for scale-based TTC
 *  - a per-object real-width estimate, learned from the ground-plane cue while the object is close
 *    enough for that cue to be sharp. A 2.0 m pickup stops being treated as a 1.8 m "average car".
 *  - class voting, so a car that flickers to "truck" for one frame keeps its identity
 */
class Track(val id: Int, cls: ObjectClass, box: BBox, score: Float, nowNs: Long) {
    /** Smoothed box for display and path tests. */
    var box: BBox = box; private set
    /** Latest raw detector box (unsmoothed) used for measurements. */
    var rawBox: BBox = box; private set
    var score: Float = score; private set
    var hits = 1; private set
    var misses = 0; internal set
    val firstSeenNs = nowNs
    var lastMatchNs = nowNs; private set
    private var lastStepNs = 0L

    private val votes = IntArray(ObjectClass.entries.size).also { it[cls.ordinal] = 3 }
    var cls: ObjectClass = cls; private set

    val kalman = RangeKalman()
    val scaleRate = ScaleRateEstimator()
    val meas = RangeMeasurement()

    private var widthSum = 0.0
    private var widthWeight = 0.0
    /** Learned real width (m) or NaN; and its relative 1-sigma. */
    var learnedWidthM = Float.NaN; private set
    var learnedRelSigma = 1f; private set

    // Per-frame outputs written by FusionEngine.
    var lateralM = Float.NaN
    var inPath = false
    var isLead = false

    val confirmed: Boolean get() = hits >= 3
    fun ageSec(nowNs: Long): Float = (nowNs - firstSeenNs) / 1e9f

    fun matched(det: Detection, nowNs: Long) {
        val b = det.box
        rawBox = b
        val a = SMOOTH
        box = BBox(
            box.left + a * (b.left - box.left), box.top + a * (b.top - box.top),
            box.right + a * (b.right - box.right), box.bottom + a * (b.bottom - box.bottom),
        )
        score = 0.6f * score + 0.4f * det.score
        hits++; misses = 0; lastMatchNs = nowNs
        votes[det.cls.ordinal]++
        var best = cls.ordinal
        for (i in votes.indices) if (votes[i] > votes[best]) best = i
        cls = ObjectClass.entries[best]
    }

    /**
     * Advance the filters to [nowNs]. When [fresh] (matched this frame) a new range measurement and
     * scale sample are folded in; otherwise the Kalman coasts on its velocity.
     */
    fun step(nowNs: Long, fresh: Boolean, geom: CameraGeometry, inputPx: Int) {
        val dt = if (lastStepNs == 0L) 0f else (nowNs - lastStepNs) / 1e9f
        lastStepNs = nowNs
        kalman.predict(dt)
        if (!fresh) return

        val b = rawBox
        val truncated = b.left <= 0.004f || b.right >= 0.996f || b.bottom >= 0.992f
        if (truncated) scaleRate.reset()
        else scaleRate.add(nowNs / 1e9, sqrt(b.width * b.height))

        if (RangeEstimator.measure(cls, b, geom, inputPx, learnedWidthM, learnedRelSigma, meas)) {
            // Kalman sees only the random part; bias (pitch, prior) is constant and would just add lag.
            kalman.update(meas.inv, meas.invJitter.coerceAtLeast(0.01f * meas.inv))
            biasInvSigma = sqrt((meas.invSigma * meas.invSigma - meas.invJitter * meas.invJitter).coerceAtLeast(0f))
            learnWidth(geom, inputPx)
        }
    }

    private fun learnWidth(geom: CameraGeometry, inputPx: Int) {
        val g = meas.groundM
        if (g.isNaN() || meas.widthM.isNaN() || g > 35f) return
        val gRel = meas.groundRel
        if (gRel > 0.10f) return
        val implied = geom.impliedWidth(rawBox.width, g)
        val prior = RangeEstimator.widthPrior(cls)
        if (implied < prior * 0.65f || implied > prior * 1.5f) return
        val quantRel = 1.84f / (rawBox.width * inputPx).coerceAtLeast(1f)
        val rel2 = gRel * gRel + quantRel * quantRel
        val w = 1.0 / rel2
        widthSum += implied * w; widthWeight += w
        if (widthWeight > 4000.0) { widthSum *= 0.5; widthWeight *= 0.5 } // keep adapting slowly
        val rel = maxOf(0.045f, (1.0 / sqrt(widthWeight)).toFloat()) // pitch bias is common-mode: floor it
        if (rel < RangeEstimator.widthPriorRelSigma(cls)) {
            learnedWidthM = (widthSum / widthWeight).toFloat()
            learnedRelSigma = rel
        }
    }

    private var biasInvSigma = 0f
    /** Total 1-sigma distance uncertainty: filter variance plus the cues' systematic part. */
    val sigmaM: Float get() {
        val q = kalman.q
        if (kalman.q.isNaN() || q <= 0f) return Float.NaN
        val sq = sqrt(kalman.sigmaQ * kalman.sigmaQ + biasInvSigma * biasInvSigma)
        return sq / (q * q)
    }

    /** Filtered distance (m) or NaN. */
    val distanceM: Float get() = kalman.d
    /** Closing speed (m/s, + = approaching). */
    val closingMps: Float get() = -kalman.v

    companion object { private const val SMOOTH = 0.55f }
}

/**
 * Greedy multi-object tracker with score hysteresis: a new track needs a confident detection
 * ([spawnScore]) but an existing one is kept alive by weaker ones ([config objectScoreThreshold] x0.65
 * from the detector), which removes most flicker. Matching uses IoU, falling back to centre distance
 * for small, far boxes whose IoU is unstable. Vehicle sub-classes match each other.
 */
class ObjectTracker(
    private val minIou: Float = 0.2f,
    private val maxMisses: Int = 6,
) {
    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private val matchedTrack = BooleanArray(MAX_TRACKS)
    private val matchedDet = BooleanArray(256)

    fun update(detections: List<Detection>, spawnScore: Float, nowNs: Long): List<Track> {
        val nd = detections.size.coerceAtMost(matchedDet.size)
        java.util.Arrays.fill(matchedTrack, false)
        java.util.Arrays.fill(matchedDet, false)

        while (true) {
            var best = 0.05f
            var bt = -1; var bd = -1
            for (ti in tracks.indices) {
                if (matchedTrack[ti]) continue
                val t = tracks[ti]
                for (di in 0 until nd) {
                    if (matchedDet[di]) continue
                    val d = detections[di]
                    if (!compatible(t.cls, d.cls)) continue
                    val s = matchScore(t.box, d.box)
                    if (s > best) { best = s; bt = ti; bd = di }
                }
            }
            if (bt < 0) break
            tracks[bt].matched(detections[bd], nowNs)
            matchedTrack[bt] = true; matchedDet[bd] = true
        }

        var i = tracks.size - 1
        while (i >= 0) {
            if (!matchedTrack[i]) {
                val t = tracks[i]
                t.misses++
                // Unconfirmed tracks die fast; confirmed ones may coast briefly through occlusion.
                if (t.misses > (if (t.confirmed) maxMisses else 2)) tracks.removeAt(i)
            }
            i--
        }
        for (di in 0 until nd) {
            if (matchedDet[di]) continue
            val d = detections[di]
            if (d.score < spawnScore || tracks.size >= MAX_TRACKS) continue
            tracks.add(Track(nextId++, d.cls, d.box, d.score, nowNs))
        }
        return tracks
    }

    private fun compatible(a: ObjectClass, b: ObjectClass): Boolean =
        a == b || (a.isVehicle && b.isVehicle) || (a == ObjectClass.MOTORCYCLE && b == ObjectClass.BICYCLE) ||
            (a == ObjectClass.BICYCLE && b == ObjectClass.MOTORCYCLE)

    private fun matchScore(t: BBox, d: BBox): Float {
        val iou = t.iou(d)
        if (iou >= minIou) return 0.2f + iou
        val maxW = maxOf(t.width, d.width, 1e-3f)
        val dist = sqrt((t.cx - d.cx) * (t.cx - d.cx) + (t.cy - d.cy) * (t.cy - d.cy))
        val ratio = d.width / t.width.coerceAtLeast(1e-4f)
        if (dist < 0.6f * maxW && ratio in 0.67f..1.5f && abs(t.cy - d.cy) < 0.6f * maxOf(t.height, d.height)) {
            return 0.2f * (1f - dist / (0.6f * maxW))
        }
        return 0f
    }

    fun reset() { tracks.clear() }

    companion object { const val MAX_TRACKS = 48 }
}
