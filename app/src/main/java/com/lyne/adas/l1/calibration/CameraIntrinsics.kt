package com.lyne.adas.l1.calibration

/**
 * Camera intrinsics used by the monocular distance model. We work in field-of-view terms so the
 * estimate is independent of the detection-frame pixel size (boxes are normalized [0,1]).
 * [fromDevice] is true when read from CameraCharacteristics; false means the conservative default
 * is in use and distance accuracy is reduced (surfaced in the UI).
 */
data class CameraIntrinsics(
    val horizontalFovDeg: Float,
    val verticalFovDeg: Float,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val fromDevice: Boolean,
) {
    companion object {
        // Typical rear-camera main lens horizontal FoV; safe fallback.
        val DEFAULT = CameraIntrinsics(
            horizontalFovDeg = 62f,
            verticalFovDeg = 48f,
            focalLengthMm = 4.0f,
            sensorWidthMm = 5.0f,
            fromDevice = false,
        )
    }
}
