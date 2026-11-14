package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.inference.BBox

/**
 * Supplies pixels of the current frame for cheap appearance cues on the lead vehicle (brake
 * lights). Implementations fill [out] row-major with [cols] x [rows] ARGB samples taken on a
 * regular grid inside the normalized [box] (cell centres) and return false when no frame is
 * available. Must not allocate per call.
 */
interface LeadPixelSource {
    fun sample(box: BBox, out: IntArray, cols: Int, rows: Int): Boolean
}
