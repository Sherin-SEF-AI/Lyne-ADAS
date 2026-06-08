package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.LaneResult

/** Everything the HUD needs for one frame, plus the discrete events emitted this frame. */
data class FusionResult(
    val frameId: Long,
    val events: List<AdasEvent>,
    val detections: List<Detection>,
    val lane: LaneResult,
    val leadBox: BBox?,
    val leadDistanceM: Float?,
    val leadDistanceConf: Float?,
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
) {
    companion object {
        fun empty(frameId: Long, lane: LaneResult) = FusionResult(
            frameId, emptyList(), emptyList(), lane, null, null, null, null, null, null, null, null,
            Severity.NONE, Severity.NONE, Severity.NONE, Severity.NONE, Severity.NONE,
            Severity.NONE, null, null,
        )
    }
}
