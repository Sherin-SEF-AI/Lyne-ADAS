package com.lyne.adas.l1.camera

import com.lyne.adas.l1.fusion.AdasEvent
import com.lyne.adas.l1.fusion.AlertType
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.inference.LaneResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CameraHealthMonitorTest {
    private val dt = 66L // ~15 fps

    private fun CameraHealthMonitor.feed(fromMs: Long, toMs: Long, mean: Float, sd: Float, detail: Float, night: Boolean): Long {
        var t = fromMs
        while (t < toMs) { onFrame(t, mean, sd, detail, night); t += dt }
        return t
    }

    @Test fun coveredLensInDaylightBlocksAfterThreeSeconds() {
        val m = CameraHealthMonitor()
        var t = m.feed(0, 5_000, mean = 120f, sd = 40f, detail = 12f, night = false)
        assertFalse(m.health.blocked)
        val coverAt = t
        t = m.feed(t, coverAt + 2_500, mean = 6f, sd = 2f, detail = 0.5f, night = false)
        assertFalse("not before 3 s", m.health.blocked)
        t = m.feed(t, coverAt + 3_300, mean = 6f, sd = 2f, detail = 0.5f, night = false)
        assertTrue(m.health.blocked)
        assertEquals("CAMERA BLOCKED", m.health.title)
        assertTrue(m.health.detail!!.contains("paused"))
        // Uncovered: clears after ~1 s of good frames.
        val clearAt = t
        t = m.feed(t, clearAt + 500, mean = 120f, sd = 40f, detail = 12f, night = false)
        assertTrue(m.health.blocked)
        m.feed(t, clearAt + 1_200, mean = 120f, sd = 40f, detail = 12f, night = false)
        assertFalse(m.health.blocked)
    }

    @Test fun darkRoadAtNightIsNotBlocked() {
        val m = CameraHealthMonitor()
        m.feed(0, 10_000, mean = 8f, sd = 6f, detail = 1.0f, night = true)
        assertFalse(m.health.blocked)
    }

    @Test fun recentDaylightFramesOverrideDarkAmbientSensor() {
        // Ambient sensor in shade says night, but the camera just saw a bright detailed road.
        val m = CameraHealthMonitor()
        var t = m.feed(0, 2_000, mean = 130f, sd = 45f, detail = 10f, night = true)
        t = m.feed(t, t + 3_500, mean = 5f, sd = 1f, detail = 0.3f, night = true)
        assertTrue(m.health.blocked)
    }

    @Test fun uniformBrightFrameBlocksEvenAtNight() {
        val m = CameraHealthMonitor()
        m.feed(0, 3_500, mean = 160f, sd = 2f, detail = 0.4f, night = true)
        assertTrue(m.health.blocked)
    }

    @Test fun shortDropoutDoesNotBlock() {
        // e.g. a hand passing in front of the lens, or a dark underpass for 2 s.
        val m = CameraHealthMonitor()
        var t = m.feed(0, 2_000, 120f, 40f, 12f, false)
        t = m.feed(t, t + 2_000, 5f, 1f, 0.3f, false)
        m.feed(t, t + 3_000, 120f, 40f, 12f, false)
        assertFalse(m.health.blocked)
    }

    @Test fun normalSceneIsNotSuspicious() {
        assertFalse(CameraHealthMonitor.isSuspicious(110f, 35f, 9f, daytime = true))
        assertFalse(CameraHealthMonitor.isSuspicious(25f, 12f, 3f, daytime = false))
    }

    // --- mount ---

    private fun CameraHealthMonitor.mount(fromMs: Long, toMs: Long, pitchDeg: Float, rollDeg: Float, enabled: Boolean = true): Long {
        var t = fromMs
        while (t < toMs) { onMount(t, Math.toRadians(pitchDeg.toDouble()).toFloat(), rollDeg, 45f, enabled); t += 100 }
        return t
    }

    @Test fun normalMountIsFine() {
        val m = CameraHealthMonitor()
        m.mount(0, 20_000, pitchDeg = 3f, rollDeg = 2f)
        assertFalse(m.health.misaligned)
    }

    @Test fun rolledMountFlagsAfterFiveSeconds() {
        val m = CameraHealthMonitor()
        var t = m.mount(0, 4_500, pitchDeg = 3f, rollDeg = 12f)
        assertFalse(m.health.misaligned)
        t = m.mount(t, 5_300, pitchDeg = 3f, rollDeg = 12f)
        assertTrue(m.health.misaligned)
        assertTrue(m.health.tilted)
        assertEquals("CHECK PHONE MOUNT", m.health.title)
        m.mount(t, t + 2_500, pitchDeg = 3f, rollDeg = 1f)
        assertFalse(m.health.misaligned)
    }

    @Test fun cameraPointingAtDashboardFlagsHorizon() {
        val m = CameraHealthMonitor()
        m.mount(0, 6_000, pitchDeg = 25f, rollDeg = 0f) // looking well down: horizon off the top
        assertTrue(m.health.misaligned)
        assertFalse(m.health.tilted)
    }

    @Test fun mountCheckDisabledInVideoMode() {
        val m = CameraHealthMonitor()
        m.mount(0, 10_000, pitchDeg = 40f, rollDeg = 30f, enabled = false)
        assertFalse(m.health.misaligned)
    }

    @Test fun unknownPitchIsNotMisaligned() {
        val m = CameraHealthMonitor()
        var t = 0L
        while (t < 10_000) { m.onMount(t, Float.NaN, 0f, 45f); t += 100 }
        assertFalse(m.health.misaligned)
    }

    @Test fun horizonFormulaMatchesGeometry() {
        assertEquals(0.5f, CameraHealthMonitor.horizonY(0f, 45f), 1e-4f)
        assertTrue(CameraHealthMonitor.horizonY(0.1f, 45f) < 0.5f) // pitched down -> horizon higher
    }

    // --- FCW suppression ---

    private fun result(fcw: Severity, vru: Severity, events: List<AdasEvent>): FusionResult {
        val base = FusionResult.empty(1, LaneResult.EMPTY)
        val top = if (fcw.ordinal >= vru.ordinal && fcw != Severity.NONE) AlertType.FCW else if (vru != Severity.NONE) AlertType.VRU else null
        return base.copy(
            events = events, fcw = fcw, vru = vru, ttcSec = 1.2f,
            topSeverity = maxOf(fcw, vru), topType = top, topMessage = if (top == AlertType.FCW) "BRAKE" else "ROAD USER AHEAD",
        )
    }

    @Test fun suppressFcwRemovesFcwAndFallsBackToNextFeature() {
        val ev = listOf(
            AdasEvent(AlertType.FCW, Severity.CRITICAL, 0L, 0.9f, "BRAKE"),
            AdasEvent(AlertType.VRU, Severity.CAUTION, 0L, 0.9f, "ROAD USER AHEAD"),
        )
        val r = CameraHealthMonitor.suppressFcw(result(Severity.CRITICAL, Severity.CAUTION, ev))
        assertEquals(Severity.NONE, r.fcw)
        assertNull(r.ttcSec)
        assertTrue(r.events.none { it.type == AlertType.FCW })
        assertEquals(1, r.events.size)
        assertEquals(Severity.CAUTION, r.topSeverity)
        assertEquals(AlertType.VRU, r.topType)
    }

    @Test fun suppressFcwAloneLeavesNoMasterAlert() {
        val r = CameraHealthMonitor.suppressFcw(result(Severity.CRITICAL, Severity.NONE,
            listOf(AdasEvent(AlertType.FCW, Severity.CRITICAL, 0L, 0.9f, "BRAKE"))))
        assertEquals(Severity.NONE, r.topSeverity)
        assertNull(r.topType)
        assertTrue(r.events.isEmpty())
    }

    // --- frame stats ---

    private fun rgba(w: Int, h: Int, px: (Int, Int) -> Int): ByteBuffer {
        val b = ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val v = px(x, y).toByte()
            b.put(v); b.put(v); b.put(v); b.put(255.toByte())
        }
        b.rewind(); return b
    }

    @Test fun frameStatsUniformVsTextured() {
        val s = FrameStats()
        s.compute(rgba(320, 240) { _, _ -> 128 }, 320, 240, 320 * 4)
        assertEquals(127f, s.meanLuma, 2f)
        assertTrue(s.lumaStd < 1f)
        assertTrue(s.detail < 0.5f)

        s.compute(rgba(320, 240) { x, y -> if (((x / 2) + (y / 2)) % 2 == 0) 30 else 220 }, 320, 240, 320 * 4)
        assertTrue("detail=${s.detail}", s.detail > CameraHealthMonitor.HEALTHY_DETAIL)
    }

    @Test fun frameStatsRespectsRowStride() {
        val w = 100; val h = 80; val stride = w * 4 + 64
        val b = ByteBuffer.allocate(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * stride + x * 4
            b.put(i, 50); b.put(i + 1, 50); b.put(i + 2, 50)
        }
        val s = FrameStats()
        s.compute(b, w, h, stride)
        assertEquals(49f, s.meanLuma, 2f)
        assertEquals(0, b.position())
    }
}
