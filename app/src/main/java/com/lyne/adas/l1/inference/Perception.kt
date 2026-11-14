package com.lyne.adas.l1.inference

/**
 * Latest fused detector outputs for one processed frame. Lane/sign may be carried over from an
 * earlier frame because they run on a slower cadence than object detection.
 */
data class Perception(
    val frameId: Long,
    val timestampNs: Long,
    val detections: List<Detection>,
    val lane: LaneResult,
    val sign: SignResult,
    val objectBackend: Backend,
    val ranLaneThisFrame: Boolean,
    val ranSignThisFrame: Boolean,
    /**
     * The pooled frame these detections came from. Valid ONLY inside the scheduler's onResult
     * callback (the bitmap is recycled right after); used for cheap pixel cues such as brake lights.
     */
    val frame: CameraFrame? = null,
)
