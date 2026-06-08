package com.lyne.adas.l1.config

/**
 * Single typed source of truth for thresholds, model paths and feature flags.
 * Defaults are sane and field-tested-conservative; [DebugSettings] mutates a copy at runtime.
 * Per-tier knobs live in [TierProfile]; this holds tier-independent tuning.
 */
data class AdasConfig(
    // --- Model asset paths (under assets/models/). Nano variants used on Tier C. ---
    val objectModel: String = "models/object_yolov8n_int8.tflite",
    val objectModelNano: String = "models/object_yolov8n_nano_int8.tflite",
    val laneModel: String = "models/lane_ufld_int8.tflite",
    val laneModelNano: String = "models/lane_ufld_nano_int8.tflite",
    /** Drivable-area segmentation model (TwinLiteNet). Used for the green carpet + LDW on Tier A/B. */
    val drivableSegModel: String = "models/drivable_twinlite.tflite",
    val signModel: String = "models/sign_mobilenetv3_int8.tflite",
    val objectLabels: String = "models/object_labels.txt",
    val signLabels: String = "models/sign_labels.txt",

    // --- Forward Collision Warning ---
    val ttcCautionSec: Float = 2.7f,
    val ttcCriticalSec: Float = 1.5f,
    /** Below this distance-estimate confidence [0,1], FCW caution is downgraded (no hard alert). */
    val minDistanceConfidence: Float = 0.45f,

    // --- Headway / tailgating ---
    val headwayWarnSec: Float = 1.0f,
    val headwayCriticalSec: Float = 0.6f,

    // --- Lane Departure ---
    /** Fraction of half-lane-width at which an unsignaled crossing warns. */
    val ldwOffsetWarnFrac: Float = 0.85f,
    /** Lateral speed (m/s, from IMU + lane-offset rate) above which a deliberate maneuver is inferred. */
    val ldwManeuverLateralMps: Float = 0.55f,
    val ldwMinLaneConfidence: Float = 0.40f,

    // --- Traffic Sign / overspeed ---
    val overspeedToleranceKph: Int = 5,
    val signMinConfidence: Float = 0.60f,
    /** How long a recognized speed limit stays "current" without re-confirmation. */
    val signStaleMs: Long = 30_000L,

    // --- Object detection decode ---
    val objectScoreThreshold: Float = 0.40f,
    val nmsIouThreshold: Float = 0.50f,
    val maxDetections: Int = 50,

    // --- Pipeline / safety ---
    /** Detection is gated to the ego-path band (fraction of frame width around centre). */
    val egoPathHalfWidthFrac: Float = 0.28f,
    /** Min frames a track must persist before it can raise a hard alert (debounce). */
    val alertDebounceFrames: Int = 2,

    // --- Debug / dev ---
    val debugInjectSynthetic: Boolean = false,
    val showPerfOverlay: Boolean = true,
    val emitRovixSidecar: Boolean = true,
    val dashcamEnabled: Boolean = false, // feature-flagged OFF by default
    val dashcamSeconds: Int = 12,
) {
    companion object {
        val DEFAULT = AdasConfig()
    }
}
