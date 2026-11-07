package com.lyne.adas.l1.pipeline

import com.lyne.adas.l1.calibration.CameraIntrinsics
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.config.DeviceTier
import com.lyne.adas.l1.config.SensitivityPreset
import com.lyne.adas.l1.config.TierProfile
import com.lyne.adas.l1.fusion.AdasEvent
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.perf.PerfSnapshot

/** Snapshot of the whole pipeline state rendered by the HUD. Updated ~per processed frame. */
data class AdasUiState(
    val cameraReady: Boolean = false,
    val error: String? = null,
    val tier: DeviceTier = DeviceTier.B,
    val profile: TierProfile = TierProfile.TIER_B,
    val probeSummary: String = "probing…",
    val usingStub: Boolean = true, // object detector is a stub (no real weights)
    val signStub: Boolean = true,  // sign classifier is a stub (no speed-limit model)
    val debugInject: Boolean = false,
    val muted: Boolean = false,
    val intrinsics: CameraIntrinsics = CameraIntrinsics.DEFAULT,
    val calibrated: Boolean = false,
    val frameAspect: Float = 4f / 3f, // displayWidth/displayHeight of the analysis frame
    val fusion: FusionResult? = null,
    val perf: PerfSnapshot? = null,
    // Phase-2 UX state
    val isNight: Boolean = true,
    val dayNightMode: DayNightMode = DayNightMode.AUTO,
    val sensitivity: SensitivityPreset = SensitivityPreset.NORMAL,
    val showPerf: Boolean = false,
    val session: SessionStats? = null,
    val recentEvents: List<AdasEvent> = emptyList(),
    // Geometry / calibration
    val mountPitchDeg: Float? = null,
    val mountRollDeg: Float = 0f,
    val mountConvergence: Float = 0f,
    val cameraHeightM: Float = 1.25f,
    val vehiclePreset: com.lyne.adas.l1.calibration.VehiclePreset? = null,
    val autoCalEnabled: Boolean = true,
    val distanceScale: Float = 1f,
    val rangeCheck: com.lyne.adas.l1.logging.RangeCheckState = com.lyne.adas.l1.logging.RangeCheckState(),
)
