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
    /** Painted lane lines from the classical detector (runs with every object frame when LDW is on). */
    val laneLines: LaneLines = LaneLines.NONE,
    /** Square input edge (px) of the object model that produced [detections]; 0 = unknown. */
    val objectInputPx: Int = 0,
)
