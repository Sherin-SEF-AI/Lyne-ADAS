package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.inference.BBox

enum class AlertType { FCW, VRU, LDW, HEADWAY, OVERSPEED, STOP }

/** Severity ladder. Color is "earned" only above NONE; CRITICAL drives the strongest alert. */
enum class Severity { NONE, INFO, CAUTION, CRITICAL }

/**
 * One fused warning record. Also the shape serialized to the ROVIX sidecar JSON stream.
 * Nullable fields are populated per [type] (e.g. ttc for FCW, headway for HEADWAY).
 */
data class AdasEvent(
    val type: AlertType,
    val severity: Severity,
    val timestampMs: Long,
    val confidence: Float,
    val message: String,
    val ttcSec: Float? = null,
    val headwaySec: Float? = null,
    val distanceM: Float? = null,
    val distanceConfidence: Float? = null,
    val bbox: BBox? = null,
    val laneOffset: Float? = null,
    val speedKph: Float? = null,
    val speedLimitKph: Int? = null,
)
