package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraIntrinsics
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.ObjectClass
import kotlin.math.tan

data class DistanceEstimate(val distanceM: Float, val confidence: Float)

/**
 * Monocular distance from bounding-box geometry + camera field of view (pinhole model):
 *
 *   angular_width = hFov * box.width            (box.width is the fraction of frame width)
 *   distance      = (real_width / 2) / tan(angular_width / 2) * calibrationScale
 *
 * Assumes the object's real width matches a per-class prior and that it sits roughly upright on a
 * flat road. This is an ESTIMATE; confidence drops for tiny/huge/edge boxes and feeds alert gating.
 */
object DistanceEstimator {

    /** Real-world widths (metres) per class. Tuned for mixed Indian/urban traffic. */
    private fun realWidthM(cls: ObjectClass): Float = when (cls) {
        ObjectClass.CAR -> 1.8f
        ObjectClass.TRUCK -> 2.5f
        ObjectClass.BUS -> 2.6f
        ObjectClass.AUTORICKSHAW -> 1.4f
        ObjectClass.MOTORCYCLE -> 0.8f
        ObjectClass.BICYCLE -> 0.6f
        ObjectClass.PEDESTRIAN -> 0.5f
        ObjectClass.STOP_SIGN -> 0.75f
        ObjectClass.UNKNOWN -> 1.8f
    }

    fun estimate(det: Detection, intr: CameraIntrinsics, calibrationScale: Float): DistanceEstimate {
        val w = det.box.width.coerceIn(1e-4f, 1f)
        val angular = Math.toRadians((intr.horizontalFovDeg * w).toDouble()).toFloat()
        val realW = realWidthM(det.cls)
        val distance = ((realW / 2f) / tan(angular / 2f)) * calibrationScale

        // Confidence: detector score gated by box plausibility.
        val sizeConf = when {
            w < 0.02f -> 0.25f          // too small: width noise dominates
            w > 0.85f -> 0.45f          // fills frame: width clipped, unreliable
            else -> 1.0f
        }
        // Edge boxes are often truncated -> width underestimated.
        val edgeConf = if (det.box.left <= 0.01f || det.box.right >= 0.99f) 0.6f else 1.0f
        val confidence = (det.score * sizeConf * edgeConf).coerceIn(0f, 1f)

        return DistanceEstimate(distance.coerceIn(0.5f, 250f), confidence)
    }
}
