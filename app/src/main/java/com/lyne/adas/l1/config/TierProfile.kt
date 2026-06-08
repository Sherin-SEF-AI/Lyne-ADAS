package com.lyne.adas.l1.config

/** Preferred inference backend order; the engine still validates and falls back. */
enum class BackendPreference { NNAPI_THEN_GPU, GPU_THEN_NNAPI, XNNPACK_ONLY }

/** Bitmap config used for the downscaled detection frame. RGB565 halves memory bandwidth. */
enum class PixelFormat { ARGB_8888, RGB_565 }

/**
 * All per-tier tuning lives here as data so behaviour is declarative and runtime-tunable,
 * never scattered through the pipeline. Cadence values are "run every Nth frame"; 0 = disabled.
 */
data class TierProfile(
    val tier: DeviceTier,
    /** Square detection-input edge in px, decoupled from preview resolution. */
    val inputResolution: Int,
    val targetFps: Int,
    val objectCadence: Int,
    val laneCadence: Int,
    val signCadence: Int,
    val backendPreference: BackendPreference,
    val inferenceThreads: Int,
    val pixelFormat: PixelFormat,
    /** Use the smaller "nano" model variants bundled for weak devices. */
    val useNanoModels: Boolean,
    /** Run the real drivable-area seg model (A/B). Tier C uses the lighter classical segmenter. */
    val useSegModel: Boolean,
    val allowDashcam: Boolean,
    val features: FeatureFlags,
    /** Max in-flight frames before the analyzer drops (counts as a dropped frame). */
    val inferenceQueueDepth: Int = 1,
) {
    companion object {
        val TIER_A = TierProfile(
            tier = DeviceTier.A,
            inputResolution = 384,
            targetFps = 30,
            objectCadence = 1,
            laneCadence = 2,
            signCadence = 5,
            backendPreference = BackendPreference.NNAPI_THEN_GPU,
            inferenceThreads = 4,
            pixelFormat = PixelFormat.ARGB_8888,
            useNanoModels = false,
            useSegModel = true,
            allowDashcam = true,
            features = FeatureFlags.ALL,
        )

        val TIER_B = TierProfile(
            tier = DeviceTier.B,
            inputResolution = 256,
            targetFps = 15,
            objectCadence = 1,
            laneCadence = 1,        // seg runs async on its own thread; busy-guard throttles it
            signCadence = 8,
            backendPreference = BackendPreference.NNAPI_THEN_GPU,
            inferenceThreads = 2,
            pixelFormat = PixelFormat.ARGB_8888,
            useNanoModels = true,   // 256-input YOLOv8n -> ~35% less compute than 320 for faster FPS
            useSegModel = true,
            allowDashcam = true,
            features = FeatureFlags.ALL,
        )

        val TIER_C = TierProfile(
            tier = DeviceTier.C,
            inputResolution = 256,
            targetFps = 8,
            objectCadence = 1,
            laneCadence = 6,
            signCadence = 0, // TSR off on entry devices
            backendPreference = BackendPreference.XNNPACK_ONLY,
            inferenceThreads = 2,
            pixelFormat = PixelFormat.RGB_565,
            useNanoModels = true,
            useSegModel = false,    // entry devices: lighter classical segmenter
            allowDashcam = false,
            features = FeatureFlags(
                fcw = true,
                vru = true,
                ldw = true,        // kept but on long cadence
                headway = true,
                tsr = false,
            ),
        )

        fun forTier(tier: DeviceTier): TierProfile = when (tier) {
            DeviceTier.A -> TIER_A
            DeviceTier.B -> TIER_B
            DeviceTier.C -> TIER_C
        }
    }
}
