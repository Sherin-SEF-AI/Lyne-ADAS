package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.LaneResult
import com.lyne.adas.l1.inference.ObjectClass

/** A confirmed track as the HUD sees it. */
data class TrackedObject(
    val id: Int,
    val cls: ObjectClass,
    val box: BBox,
    val distanceM: Float?,
    val sigmaM: Float?,
    val closingMps: Float?,
    val inPath: Boolean,
    val isLead: Boolean,
)

/** Everything the HUD needs for one frame, plus the discrete events emitted this frame. */
data class FusionResult(
    val frameId: Long,
    val events: List<AdasEvent>,
    val detections: List<Detection>,
    val objects: List<TrackedObject>,
    val lane: LaneResult,
    val leadBox: BBox?,
    val leadId: Int?,
    val leadDistanceM: Float?,
    val leadSigmaM: Float?,
    val leadDistanceConf: Float?,
    val leadClosingMps: Float?,
    val ttcSec: Float?,
    val headwaySec: Float?,
    val speedKph: Float?,
    val speedLimitKph: Int?,
    val laneOffset: Float?,
    val fcw: Severity,
    val vru: Severity,
    val ldw: Severity,
    val headway: Severity,
    val overspeed: Severity,
    val topSeverity: Severity,
    val topType: AlertType?,
    val topMessage: String?,
    /** Normalized horizon row at the left and right image edges (for the HUD horizon line). */
    val horizonLeftY: Float = Float.NaN,
    val horizonRightY: Float = Float.NaN,
    val heightScale: Float = 1f,
    val autoCalConfidence: Float = 0f,
) {
    companion object {
        fun empty(frameId: Long, lane: LaneResult) = FusionResult(
            frameId, emptyList(), emptyList(), emptyList(), lane, null, null, null, null, null, null, null, null,
            null, null, null, Severity.NONE, Severity.NONE, Severity.NONE, Severity.NONE, Severity.NONE,
            Severity.NONE, null, null,
        )
    }
}
