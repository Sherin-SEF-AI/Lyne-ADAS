package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraGeometry
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.FeatureFlags
import com.lyne.adas.l1.inference.Backend
import com.lyne.adas.l1.inference.LaneLineCore
import com.lyne.adas.l1.inference.LaneLines
import com.lyne.adas.l1.inference.LaneResult
import com.lyne.adas.l1.inference.Perception
import com.lyne.adas.l1.inference.SignResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** LDW lane-source selection: painted lines when confident, else the drivable-area/road-edge path. */
class LaneSourceTest {
    private val ldwOnly = FeatureFlags(fcw = false, vru = false, ldw = true, headway = false, tsr = false)
    private val geom = CameraGeometry(66f, 52f, 1.3f, 0.05f, 0f)
    private val speed = 20f // m/s

    private fun lines(conf: Float, offset: Float) = LaneLines(
        FloatArray(2) { 0.6f + it * 0.3f }, floatArrayOf(0.4f, 0.1f), floatArrayOf(0.6f, 0.9f),
        conf, conf, conf, offset, -1.75f, 1.75f, 3.5f, 0f,
    )

    private fun p(frame: Long, t: Float, lane: LaneResult, lines: LaneLines) = Perception(
        frameId = frame, timestampNs = (t * 1e9).toLong(), detections = emptyList(), lane = lane,
        sign = SignResult.NONE, objectBackend = Backend.XNNPACK_CPU, ranLaneThisFrame = false,
        ranSignThisFrame = false, laneLines = lines,
    )

    private val sourceAtLdw = ArrayList<LaneSource>()

    private fun drive(seconds: Float, make: (Int, Float) -> Pair<LaneResult, LaneLines>): Pair<List<AdasEvent>, FusionResult> {
        val eng = FusionEngine(AdasConfig.DEFAULT, ldwOnly, autoCalEnabled = false)
        val events = ArrayList<AdasEvent>()
        var last: FusionResult? = null
        var i = 0; var t = 0f
        while (t < seconds) {
            val (lane, ll) = make(i, t)
            last = eng.update(p(i.toLong(), t, lane, ll), speed, 0f, geom, 256, (t * 1000).toLong())
            events += last.events
            if (last.events.any { it.type == AlertType.LDW }) sourceAtLdw += last.laneSource
            i++; t += 0.1f
        }
        return events to last!!
    }

    @Test
    fun confidentLinesDriveTheWarning() {
        val (ev, last) = drive(3f) { _, _ -> LaneResult.EMPTY to lines(0.7f, -1.1f) }
        assertEquals(LaneSource.LINES, last.laneSource)
        assertTrue("expected LDW, got $ev", ev.any { it.type == AlertType.LDW })
        assertEquals(-1.1f, last.laneOffset!!, 1e-4f)
    }

    @Test
    fun weakLinesFallBackToRoadPath() {
        // Lines say "on the line" but are not confident; the road path says centred -> no warning.
        val road = LaneResult(floatArrayOf(0.9f), floatArrayOf(0.2f), floatArrayOf(0.8f), 0f, 0.8f)
        val (ev, last) = drive(3f) { _, _ -> road to lines(0.2f, 1.2f) }
        assertEquals(LaneSource.ROAD, last.laneSource)
        assertTrue("unexpected $ev", ev.none { it.type == AlertType.LDW })
    }

    @Test
    fun noLanesNoWarning() {
        val (ev, last) = drive(3f) { _, _ -> LaneResult.EMPTY to lines(0.25f, 1.3f) }
        assertEquals(LaneSource.NONE, last.laneSource)
        assertEquals(null, last.laneOffset)
        assertTrue(ev.none { it.type == AlertType.LDW })
    }

    // ---- end to end through the classical detector on synthetic frames ----

    private val w = 256; private val h = 256; private val hz = 0.48f
    private val slope = LaneLineCore.DEFAULT_METRE_SLOPE

    private fun frame(latLines: List<Float>, rnd: Random, phase: Float): IntArray {
        val px = IntArray(w * h)
        for (yy in 0 until h) {
            val y = (yy + 0.5f) / h
            for (xx in 0 until w) {
                var v = if (y < hz) 170 else 88 + (rnd.nextGaussian() * 6).toInt()
                if (y >= hz) {
                    val rs = slope * (y - hz)
                    val lat = ((xx + 0.5f) / w - 0.5f) / rs
                    val depth = 1f / rs
                    for ((k, l) in latLines.withIndex()) {
                        // right line dashed, left solid
                        if (kotlin.math.abs(lat - l) < 0.075f && (k == 0 || ((depth * 1.2f + phase) % 3f) < 1.2f)) v = 215
                    }
                }
                val c = v.coerceIn(0, 255)
                px[yy * w + xx] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }
        }
        return px
    }

    @Test
    fun slowDriftOverPaintedLineWarnsEndToEnd() {
        val core = LaneLineCore().apply { setGeometry(hz, 1f, 0.75f, 1.25f) }
        val rnd = Random(4)
        // 2 s centred, then a 0.3 m/s unintended drift to the left.
        val (ev, last) = drive(8f) { i, t ->
            val shift = if (t < 2f) 0f else (t - 2f) * 0.3f
            LaneResult.EMPTY to core.detect(frame(listOf(-1.75f + shift, 1.75f + shift), rnd, i * 2f), w, h)
        }
        val ldw = ev.filter { it.type == AlertType.LDW }
        assertTrue("expected an LDW from the lines, got $ev (source ${last.laneSource})", ldw.isNotEmpty())
        assertTrue("warning raised from the painted lines", sourceAtLdw.all { it == LaneSource.LINES })
        assertTrue("warning must point left: ${ldw.first().laneOffset}", (ldw.first().laneOffset ?: 0f) < 0f)
    }

    @Test
    fun unmarkedRoadNeverWarnsEndToEnd() {
        val core = LaneLineCore().apply { setGeometry(hz, 1f, 0.75f, 1.25f) }
        val rnd = Random(8)
        val (ev, last) = drive(6f) { i, _ -> LaneResult.EMPTY to core.detect(frame(emptyList(), rnd, i * 2f), w, h) }
        assertEquals(LaneSource.NONE, last.laneSource)
        assertTrue(ev.none { it.type == AlertType.LDW })
    }
}
