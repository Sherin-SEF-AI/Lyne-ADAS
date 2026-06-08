package com.lyne.adas.l1.inference

import com.lyne.adas.l1.config.AdasConfig

/**
 * Bundled stubs used until real .tflite weights are dropped in. By design they emit NOTHING in
 * normal operation (no fake alerts, no fake green light). When [AdasConfig.debugInjectSynthetic]
 * is on they animate deterministic scenarios so the full fusion→alert→HUD path can be exercised
 * end-to-end on any device without weights or a road.
 */
private val STUB_BACKEND = Backend.XNNPACK_CPU

class StubObjectDetector(private val config: AdasConfig) : Detector<List<Detection>> {
    override val name = "stub-object"
    override val backend = STUB_BACKEND
    override val isStub = true

    override fun detect(frame: CameraFrame): List<Detection> {
        if (!config.debugInjectSynthetic) return emptyList()
        // A vehicle that approaches: box grows over a 200-frame loop -> TTC decreases -> FCW escalates.
        val phase = (frame.frameId % 200L) / 200f
        val w = 0.08f + phase * 0.46f
        val h = w * 0.85f
        val cx = 0.5f
        val cy = 0.58f
        val box = BBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        return listOf(Detection(ObjectClass.CAR, 0.92f, box))
    }

    override fun close() {}
}

class StubLaneDetector(private val config: AdasConfig) : Detector<LaneResult> {
    override val name = "stub-lane"
    override val backend = STUB_BACKEND
    override val isStub = true

    private val rows = FloatArray(10) { 0.5f + it * 0.05f }

    override fun detect(frame: CameraFrame): LaneResult {
        if (!config.debugInjectSynthetic) return LaneResult.EMPTY
        // Ego drifting slowly left and right across the lane to exercise LDW.
        val drift = Math.sin(frame.frameId / 60.0).toFloat() // -1..1 half-lane units
        val left = FloatArray(rows.size) { 0.30f + drift * 0.1f }
        val right = FloatArray(rows.size) { 0.70f + drift * 0.1f }
        return LaneResult(rows, left, right, centerOffset = drift, confidence = 0.8f)
    }

    override fun close() {}
}

class StubSignDetector(private val config: AdasConfig) : Detector<SignResult> {
    override val name = "stub-sign"
    override val backend = STUB_BACKEND
    override val isStub = true

    override fun detect(frame: CameraFrame): SignResult {
        if (!config.debugInjectSynthetic) return SignResult.NONE
        // Surface a 50 km/h limit periodically.
        return if ((frame.frameId % 300L) < 150L) SignResult(SignType.SPEED_LIMIT, 50, 0.85f)
        else SignResult.NONE
    }

    override fun close() {}
}
