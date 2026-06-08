package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.ObjectClass

/**
 * A single tracked object with stable [id]. Holds the temporal state that makes FCW stable:
 * bounding-box-scale growth (for monocular TTC) and, for the lead, distance rate (for a second,
 * independent TTC estimate that can be fused with the scale one).
 */
class Track(val id: Int, var cls: ObjectClass, box: BBox, score: Float, nowNs: Long) {
    var box: BBox = box; private set
    var score: Float = score; private set
    var hits: Int = 1
    var misses: Int = 0

    private var lastWidth = box.width
    private var lastTimeNs = nowNs
    private var growthEma = 0f          // box-width units / second

    private var lastDist = Float.NaN
    private var lastDistNs = 0L
    private var closingEma = 0f         // metres / second, positive = approaching

    fun matched(newBox: BBox, newScore: Float, nowNs: Long) {
        // Light box smoothing keeps geometry steady frame to frame.
        box = BBox(
            lerp(box.left, newBox.left), lerp(box.top, newBox.top),
            lerp(box.right, newBox.right), lerp(box.bottom, newBox.bottom),
        )
        score = newScore
        hits++; misses = 0
        val dt = (nowNs - lastTimeNs) / 1e9f
        if (dt > 1e-3f) {
            val g = (box.width - lastWidth) / dt
            growthEma += GROWTH_ALPHA * (g - growthEma)
            lastWidth = box.width; lastTimeNs = nowNs
        }
    }

    /** Scale-based TTC: width / (d width / dt). NaN unless clearly expanding (approaching). */
    fun scaleTtc(): Float {
        if (growthEma <= 1e-3f) return Float.NaN
        return (box.width / growthEma).coerceIn(0f, 60f)
    }

    /** Feed an external distance estimate (metres) to maintain a closing-speed based TTC. */
    fun updateDistance(distM: Float, nowNs: Long): Float {
        if (!lastDist.isNaN() && lastDistNs != 0L) {
            val dt = (nowNs - lastDistNs) / 1e9f
            if (dt > 1e-3f) {
                val closing = (lastDist - distM) / dt
                closingEma += CLOSE_ALPHA * (closing - closingEma)
            }
        }
        lastDist = distM; lastDistNs = nowNs
        return distTtc(distM)
    }

    fun distTtc(distM: Float): Float {
        if (closingEma <= 0.3f) return Float.NaN // not meaningfully approaching
        return (distM / closingEma).coerceIn(0f, 60f)
    }

    private fun lerp(a: Float, b: Float) = a + SMOOTH * (b - a)

    companion object {
        private const val GROWTH_ALPHA = 0.35f
        private const val CLOSE_ALPHA = 0.3f
        private const val SMOOTH = 0.5f
    }
}

/**
 * Greedy IoU multi-object tracker. Cheap, allocation-light, and good enough to give detections
 * stable identities so per-object temporal estimates (TTC, closing speed) stop jittering.
 */
class ObjectTracker(
    private val iouThreshold: Float = 0.3f,
    private val maxMisses: Int = 8,
) {
    private val tracks = ArrayList<Track>()
    private var nextId = 1

    fun update(detections: List<Detection>, nowNs: Long): List<Track> {
        val matchedTrack = BooleanArray(tracks.size)
        val matchedDet = BooleanArray(detections.size)

        // Greedy: repeatedly take the highest-IoU (track, det) pair of the same class.
        while (true) {
            var bestIou = iouThreshold
            var bt = -1; var bd = -1
            for (ti in tracks.indices) {
                if (matchedTrack[ti]) continue
                val t = tracks[ti]
                for (di in detections.indices) {
                    if (matchedDet[di]) continue
                    val d = detections[di]
                    if (d.cls != t.cls) continue
                    val iou = t.box.iou(d.box)
                    if (iou > bestIou) { bestIou = iou; bt = ti; bd = di }
                }
            }
            if (bt < 0) break
            tracks[bt].matched(detections[bd].box, detections[bd].score, nowNs)
            matchedTrack[bt] = true; matchedDet[bd] = true
        }

        // Age unmatched tracks, drop the stale ones.
        var i = tracks.size - 1
        while (i >= 0) {
            if (!matchedTrack[i]) { tracks[i].misses++; if (tracks[i].misses > maxMisses) tracks.removeAt(i) }
            i--
        }
        // Spawn new tracks for unmatched detections.
        for (di in detections.indices) {
            if (!matchedDet[di]) {
                val d = detections[di]
                tracks.add(Track(nextId++, d.cls, d.box, d.score, nowNs))
            }
        }
        return tracks
    }

    fun reset() { tracks.clear() }
}
