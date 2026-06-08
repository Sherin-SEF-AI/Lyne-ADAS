package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.inference.BBox
import kotlin.math.abs

/** Ego-path gating and VRU proximity severity. */
object PathIntrusion {

    /** True if the box centre falls within the ego-path band around the frame centre. */
    fun inEgoPath(box: BBox, config: AdasConfig): Boolean =
        abs(box.cx - 0.5f) <= config.egoPathHalfWidthFrac

    /** Severity for a vulnerable road user, by estimated distance and how low (near) it sits. */
    fun vruSeverity(distanceM: Float, box: BBox): Severity {
        val near = box.bottom > 0.78f // close to the bonnet line
        return when {
            distanceM < 6f || (near && distanceM < 12f) -> Severity.CRITICAL
            distanceM < 15f -> Severity.CAUTION
            distanceM < 25f -> Severity.INFO
            else -> Severity.NONE
        }
    }
}
