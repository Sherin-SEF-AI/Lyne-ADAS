package com.lyne.adas.l1.calibration

import kotlin.math.atan
import kotlin.math.tan

/**
 * Camera intrinsics used by the monocular range model. We work in field-of-view terms so estimates
 * are independent of the detection-frame pixel size (boxes are normalized [0,1]).
 *
 * [sensorAspect] is the active-array width/height. The analysis stream can crop that array (a 16:9
 * stream on a 4:3 sensor loses the top and bottom), so the FoV of the frame the detector actually sees
 * is derived with [forStream]. Getting this wrong biases every distance by the same factor.
 */
data class CameraIntrinsics(
    val horizontalFovDeg: Float,
    val verticalFovDeg: Float,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val fromDevice: Boolean,
    val sensorAspect: Float = 4f / 3f,
) {
    /** FoV of a [width]x[height] stream cut from the sensor active array (CameraX center-crops). */
    fun forStream(width: Int, height: Int): CameraIntrinsics {
        if (width <= 0 || height <= 0) return this
        val streamAspect = width.toFloat() / height
        val tanH = tan(Math.toRadians(horizontalFovDeg / 2.0))
        val tanV = tanH / sensorAspect
        val (h, v) = if (streamAspect >= sensorAspect) {
            tanH to tanH / streamAspect          // full width, vertical crop
        } else {
            tanV * streamAspect to tanV          // full height, horizontal crop
        }
        return copy(
            horizontalFovDeg = Math.toDegrees(2.0 * atan(h)).toFloat(),
            verticalFovDeg = Math.toDegrees(2.0 * atan(v)).toFloat(),
        )
    }

    companion object {
        /** Typical 26 mm-equivalent main camera on a 4:3 sensor; safe fallback. */
        val DEFAULT = CameraIntrinsics(
            horizontalFovDeg = 67f,
            verticalFovDeg = 52.6f,
            focalLengthMm = 4.2f,
            sensorWidthMm = 5.6f,
            fromDevice = false,
        )
    }
}
