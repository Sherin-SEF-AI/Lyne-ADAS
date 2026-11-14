package com.lyne.adas.l1.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Synthetic road frames rendered with the same flat-road model the detector uses (horizon row,
 * metres-per-row slope), with sensor noise and asphalt texture. Lines are placed at known lateral
 * positions in metres so the expected lane offset is exact.
 */
class LaneLineCoreTest {
    private val w = 256
    private val h = 256
    private val hz = 0.48f
    private val slope = LaneLineCore.DEFAULT_METRE_SLOPE

    private class Paint(val latM: Float, val dashed: Boolean = false, val yellow: Boolean = false)

    /** Renders a frame. [phase] scrolls the dashes (metres travelled). */
    private fun frame(lines: List<Paint>, rnd: Random, phase: Float = 0f, kerbAtM: Float = Float.NaN): IntArray {
        val px = IntArray(w * h)
        for (yy in 0 until h) {
            val y = (yy + 0.5f) / h
            for (xx in 0 until w) {
                var r: Int; var g: Int; var b: Int
                if (y < hz) { r = 150; g = 170; b = 200 } else {
                    val v = 88 + (rnd.nextGaussian() * 6).toInt()
                    r = v; g = v; b = v + 2
                    val x = (xx + 0.5f) / w
                    val rowScale = slope * (y - hz)            // normalized x per metre at this row
                    val latM = (x - 0.5f) / rowScale
                    if (!kerbAtM.isNaN() && latM > kerbAtM) { r = 150; g = 150; b = 150 } // raised footpath: step edge
                    val depth = 1f / rowScale                  // proportional to forward distance
                    for (p in lines) {
                        if (abs(latM - p.latM) > 0.075f) continue
                        if (p.dashed && ((depth * 1.2f + phase) % 3f) > 1.2f) continue
                        if (p.yellow) { r = 215; g = 180; b = 40 } else { r = 215; g = 215; b = 210 }
                    }
                }
                px[yy * w + xx] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return px
    }

    private fun abs(v: Float) = kotlin.math.abs(v)

    private fun core() = LaneLineCore().apply {
        // tanHalfV/(tanHalfH*height) == slope with a 4:3 stream at 1.25 m.
        setGeometry(hz, 1f, 0.75f, 1.25f)
    }

    private fun run(core: LaneLineCore, lines: List<Paint>, frames: Int, seed: Long = 1L): LaneLines {
        val rnd = Random(seed)
        var out = LaneLines.NONE
        for (i in 0 until frames) out = core.detect(frame(lines, rnd, phase = i * 0.7f), w, h)
        return out
    }

    @Test
    fun centredBetweenTwoSolidLines() {
        val r = run(core(), listOf(Paint(-1.75f), Paint(1.75f)), 12)
        assertTrue("confidence ${r.confidence}", r.confidence >= 0.5f)
        assertEquals(0f, r.offset, 0.06f)
        assertEquals(3.5f, r.widthM, 0.3f)
        assertEquals(-1.75f, r.leftM, 0.2f)
        assertEquals(1.75f, r.rightM, 0.2f)
    }

    @Test
    fun offCentreGivesSignedOffset() {
        // Lane centre 0.55 m to the right of the car -> car is left of centre -> negative.
        val r = run(core(), listOf(Paint(-1.2f), Paint(2.3f)), 12)
        assertTrue(r.confidence >= 0.5f)
        assertEquals(-0.314f, r.offset, 0.07f)
        val m = run(core(), listOf(Paint(-2.3f), Paint(1.2f)), 12)
        assertEquals(0.314f, m.offset, 0.07f)
    }

    @Test
    fun dashedLinesAreFound() {
        val r = run(core(), listOf(Paint(-1.6f, dashed = true), Paint(1.9f, dashed = true)), 14)
        assertTrue("confidence ${r.confidence}", r.confidence >= 0.4f)
        assertEquals(-(0.15f) / 1.75f, r.offset, 0.08f)
    }

    @Test
    fun yellowEdgeLineCounts() {
        val r = run(core(), listOf(Paint(-1.75f, yellow = true), Paint(1.75f, dashed = true)), 14)
        assertTrue("confidence ${r.confidence}", r.confidence >= 0.4f)
        assertTrue(r.hasLeft && r.hasRight)
        assertEquals(0f, r.offset, 0.08f)
    }

    @Test
    fun unmarkedRoadHasNoConfidence() {
        val r = run(core(), emptyList(), 20)
        assertTrue("confidence ${r.confidence}", r.confidence < 0.1f)
    }

    @Test
    fun footpathStepEdgeIsNotALaneLine() {
        val rnd = Random(3)
        val c = core()
        var r = LaneLines.NONE
        repeat(20) { r = c.detect(frame(emptyList(), rnd, kerbAtM = 1.6f), w, h) }
        assertTrue("confidence ${r.confidence}", r.confidence < 0.1f)
    }

    @Test
    fun carriagewayEdgesTooFarApartAreNotTheEgoLane() {
        // Two lines 8 m apart (road edges of a wide unmarked road): no plausible ego lane, no single line close.
        val r = run(core(), listOf(Paint(-4f), Paint(4f)), 20)
        assertTrue("confidence ${r.confidence}", r.confidence < 0.1f)
    }

    @Test
    fun singleLineIsOneSided() {
        val c = core()
        val near = run(c, listOf(Paint(-0.9f)), 16)
        assertTrue("confidence ${near.confidence}", near.confidence >= 0.4f)
        assertTrue(near.hasLeft && !near.hasRight)
        assertEquals(-(1.75f - 0.9f) / 1.75f, near.offset, 0.08f)
        // Half a lane or more away from the only visible line: no departure evidence either way.
        val far = run(core(), listOf(Paint(2.2f)), 16)
        assertEquals(0f, far.offset, 0.01f)
    }

    @Test
    fun driftTowardLeftLineShowsInOffsetTrend() {
        val c = core()
        val rnd = Random(9)
        val offsets = ArrayList<Float>()
        // Car drifts left at 0.04 m/frame: the lines move right relative to the car.
        for (i in 0 until 40) {
            val shift = if (i < 10) 0f else (i - 10) * 0.04f
            val r = c.detect(frame(listOf(Paint(-1.75f + shift), Paint(1.75f + shift)), rnd, phase = i * 0.7f), w, h)
            if (i >= 8) { assertTrue("lost lines at frame $i (${r.confidence})", r.confidence >= 0.4f); offsets += r.offset }
        }
        assertEquals(0f, offsets.first(), 0.08f)
        // Expected at the end: shift 1.16 m -> offset -0.66 (tracking lags slightly behind).
        assertTrue("end offset ${offsets.last()}", offsets.last() < -0.5f)
        // Monotone trend (allowing tiny jitter).
        for (k in 1 until offsets.size) assertTrue("non-monotone at $k: ${offsets[k - 1]} -> ${offsets[k]}", offsets[k] <= offsets[k - 1] + 0.03f)
    }

    @Test
    fun confidenceDecaysWhenLinesDisappear() {
        val c = core()
        run(c, listOf(Paint(-1.75f), Paint(1.75f)), 12)
        val r = run(c, emptyList(), 12, seed = 5)
        assertTrue("confidence ${r.confidence}", r.confidence < 0.1f)
        assertTrue(r.offset.isNaN())
    }
}
