package com.lyne.adas.l1.inference

/**
 * Painted lane lines of the ego lane from [LaneLineCore]. Image coordinates are normalized [0,1]
 * (origin top-left); metric values assume a flat road and a laterally centred camera.
 *
 * [offset] uses the same convention as [LaneResult.centerOffset]: signed lateral offset of the vehicle
 * from the lane centre in half-lane-width units, positive = vehicle right of centre, |1| = vehicle centre
 * on a line. With only one line visible it is one-sided: it only grows as the car closes on that line
 * and stays 0 while the car is at least half a lane away from it. NaN when no line is tracked.
 */
class LaneLines(
    /** Sampled image rows (ascending y) for [leftX]/[rightX]. */
    val rows: FloatArray,
    /** x of the left/right line per row, NaN where that line is not tracked. */
    val leftX: FloatArray,
    val rightX: FloatArray,
    val leftConf: Float,
    val rightConf: Float,
    /** Overall confidence that [offset] is usable for lane departure. */
    val confidence: Float,
    val offset: Float,
    /** Lateral position of each line at the nearest ROI row (m, + = right), NaN if not tracked. */
    val leftM: Float,
    val rightM: Float,
    /** Lane width (m) from the last two-line fit, NaN if never seen. */
    val widthM: Float,
    /** Far-minus-near lane-centre shift (normalized x), same meaning as the drivable-area curvature proxy. */
    val curvature: Float,
) {
    val hasLeft: Boolean get() = !leftM.isNaN()
    val hasRight: Boolean get() = !rightM.isNaN()

    companion object {
        val NONE = LaneLines(FloatArray(0), FloatArray(0), FloatArray(0), 0f, 0f, 0f, Float.NaN,
            Float.NaN, Float.NaN, Float.NaN, 0f)
    }
}
