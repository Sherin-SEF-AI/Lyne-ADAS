package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.inference.Backend
import com.lyne.adas.l1.inference.LaneResult
import com.lyne.adas.l1.inference.Luma
import com.lyne.adas.l1.inference.Perception
import com.lyne.adas.l1.inference.SignResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightTest {
    private val frameNs = 100_000_000L // 10 fps

    @Test fun lumaOfUniformImages() {
        val w = 64; val h = 48
        assertEquals(0f, Luma.sparseMean(IntArray(w * h) { 0xFF000000.toInt() }, w, h), 1e-6f)
        assertEquals(1f, Luma.sparseMean(IntArray(w * h) { 0xFFFFFFFF.toInt() }, w, h), 1e-6f)
        assertEquals(64f / 255f, Luma.sparseMean(IntArray(w * h) { 0xFF404040.toInt() }, w, h), 0.01f)
        assertTrue(Luma.sparseMean(IntArray(0), 0, 0).isNaN())
    }

    @Test fun daylightStaysDayAndNightTurnsOn() {
        val e = LowLightEstimator()
        var t = 0L
        repeat(100) { assertFalse(e.update(0.45f, t)); t += frameNs }
        // Dusk to night: switches only after the smoothed value crosses the threshold.
        var switchedAt = -1
        for (i in 0 until 100) { if (e.update(0.12f, t) && switchedAt < 0) switchedAt = i; t += frameNs }
        assertTrue("should switch to night", e.lowLight)
        // EMA tau 2 s: 0.45 -> below 0.22 takes ~2.1 s, plus a 2 s dwell (~41 frames at 10 fps).
        assertTrue("switched at frame $switchedAt", switchedAt in 30..60)
    }

    @Test fun hysteresisIgnoresHeadlightsAndStreetLights() {
        val e = LowLightEstimator()
        var t = 0L
        repeat(80) { e.update(0.12f, t); t += frameNs }
        assertTrue(e.lowLight)
        // Oncoming headlights / street light: 1 s of a bright frame.
        repeat(10) { e.update(0.6f, t); t += frameNs }
        assertTrue("1 s of glare must not leave night mode", e.lowLight)
        // Sitting in the dead band (between thresholds) keeps the current mode.
        repeat(200) { e.update(0.26f, t); t += frameNs }
        assertTrue(e.lowLight)
        repeat(100) { e.update(0.45f, t); t += frameNs }
        assertFalse("daylight again", e.lowLight)
        repeat(200) { e.update(0.26f, t); t += frameNs }
        assertFalse(e.lowLight)
    }

    @Test fun unknownLumaKeepsMode() {
        val e = LowLightEstimator()
        repeat(50) { e.update(Float.NaN, it * frameNs) }
        assertFalse(e.lowLight)
        assertTrue(e.smoothed.isNaN())
    }

    /** Same approach, day vs night luma: night reports lowLight, cautions no later, and widens sigma. */
    @Test fun nightModeIsMoreConservative() {
        fun run(luma: Float): Triple<Boolean, Float, Float> {
            val proj = Projector(seed = 4)
            val lead = SimObject(z = 50f, x = 0f, width = 1.8f, height = 1.45f)
            val engine = FusionEngine(AdasConfig.DEFAULT, FCW_ONLY, autoCalEnabled = false)
            val geom = proj.geometry()
            var cautAt = Float.NaN; var low = false; var sigmaAt30 = Float.NaN
            var t = 0f; var frame = 0L
            while (t < 10f) {
                proj.timeSec = t
                val p = Perception(frame, (t * 1e9).toLong(), proj.detect(listOf(lead)), LaneResult.EMPTY, SignResult.NONE,
                    Backend.XNNPACK_CPU, false, false, meanLuma = luma)
                val r = engine.update(p, 20f, 0f, geom, proj.inputPx, (t * 1000).toLong())
                low = r.lowLight
                if (cautAt.isNaN() && r.fcw.ordinal >= Severity.CAUTION.ordinal) cautAt = lead.z / 5f
                if (sigmaAt30.isNaN() && lead.z <= 30f) sigmaAt30 = r.leadSigmaM ?: Float.NaN
                lead.z -= 5f / 15f; t += 1f / 15f; frame++
            }
            return Triple(low, cautAt, sigmaAt30)
        }
        val (dayLow, dayCaut, daySigma) = run(0.45f)
        val (nightLow, nightCaut, nightSigma) = run(0.10f)
        println("caution at real TTC: day=$dayCaut night=$nightCaut; sigma@30m day=$daySigma night=$nightSigma")
        assertFalse(dayLow)
        assertTrue(nightLow)
        assertTrue("night caution must fire", !nightCaut.isNaN())
        assertTrue("night caution no later than day", nightCaut >= dayCaut - 0.05f)
        assertTrue("night range sigma larger", nightSigma > daySigma)
    }
}
