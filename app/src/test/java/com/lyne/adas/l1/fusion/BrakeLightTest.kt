package com.lyne.adas.l1.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BrakeLightTest {
    private val dtMs = 125L // 8 fps
    private val bbox = com.lyne.adas.l1.inference.BBox(0.4f, 0.4f, 0.6f, 0.6f)

    /** Runs [seconds] of frames; [lit] says whether the lamps are on at time t (ms). */
    private fun run(
        det: BrakeLightDetector, src: SimTailLights, fromMs: Long, seconds: Float,
        lit: (Long) -> Boolean, onFrame: (Long, Boolean) -> Unit = { _, _ -> },
    ): Long {
        var t = fromMs
        val end = fromMs + (seconds * 1000).toLong()
        while (t < end) {
            src.braking = lit(t)
            val b = det.update(bbox, src, t)
            onFrame(t, b)
            t += dtMs
        }
        return t
    }

    @Test fun steadyTailLampsNeverBrake() {
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 1)
        run(det, src, 0L, 30f, { false }) { t, b -> assertFalse("false brake at $t", b) }
    }

    @Test fun onsetIsDetectedWithinAFewFrames() {
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 2)
        var firstOn = -1L
        run(det, src, 0L, 6f, { it >= 3000L }) { t, b -> if (b && firstOn < 0) firstOn = t }
        assertTrue("onset at $firstOn", firstOn in 3000L + BrakeLightDetector.ONSET_MS..3000L + 500L)
    }

    @Test fun stillBrakingAfterLongStop() {
        // Stopped behind a car holding the brake for 30 s: the baseline must not absorb the lamps.
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 3)
        var t = run(det, src, 0L, 3f, { false })
        t = run(det, src, t, 30f, { true })
        assertTrue(det.braking)
    }

    @Test fun singleFrameFlickerIgnored() {
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 4)
        // One lit frame every 2 s (sun glint, a passing reflection).
        run(det, src, 0L, 20f, { it > 2000L && it % 2000L < dtMs }) { t, b -> assertFalse("flicker at $t", b) }
    }

    @Test fun releaseAfterLampsGoOff() {
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 5)
        var off = -1L
        run(det, src, 0L, 10f, { it in 2000L until 6000L }) { t, b -> if (t > 6000L && !b && off < 0) off = t }
        // Score smoothing takes ~3 frames to fall below the release level, then RELEASE_MS.
        assertTrue("released at $off", off in 6000L..6000L + BrakeLightDetector.RELEASE_MS + 4 * dtMs)
        assertFalse(det.braking)
    }

    @Test fun releasesWhenLeadIsLost() {
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 6)
        val t = run(det, src, 0L, 5f, { it >= 2000L })
        assertTrue(det.braking)
        assertTrue(det.coast(t + 500L))
        assertFalse(det.coast(t + BrakeLightDetector.COAST_MS + 200L))
    }

    @Test fun alreadyBrakingWhenAcquiredIsNotFlaggedButReleaseThenOnsetIs() {
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 7)
        // Lamps on from the first frame: no "rise" seen yet, so no claim.
        var t = run(det, src, 0L, 3f, { true }) { _, b -> assertFalse(b) }
        // Lamps off, then on again: now it is a clear rise.
        t = run(det, src, t, 3f, { false })
        var seen = false
        run(det, src, t, 2f, { true }) { _, b -> seen = seen || b }
        assertTrue(seen)
    }

    @Test fun noiseOnlyRedCarDoesNotTrigger() {
        // A red car: the whole rear is saturated red, lamps on or off make no relative difference.
        val det = BrakeLightDetector()
        val rnd = Random(9)
        var t = 0L
        repeat(200) {
            for (i in det.grid.indices) {
                val n = (rnd.nextGaussian() * 25).toInt()
                val r = (200 + n).coerceIn(0, 255); val g = (40 + n / 3).coerceIn(0, 255)
                det.grid[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 40
            }
            assertFalse(det.updateFromGrid(t)); t += dtMs
        }
    }

    @Test fun resetForgetsState() {
        val det = BrakeLightDetector(); val src = SimTailLights(seed = 8)
        run(det, src, 0L, 5f, { it >= 2000L })
        assertTrue(det.braking)
        det.reset()
        assertFalse(det.braking)
        assertEquals(0f, det.baseline[0], 0f)
    }

    @Test fun rednessMeasure() {
        assertEquals(158, BrakeLightDetector.redness(0xFFE84A3C.toInt()))  // lit brake lamp
        assertEquals(0, BrakeLightDetector.redness(0xFFFFFFFF.toInt()))    // white / glare
        assertEquals(0, BrakeLightDetector.redness(0xFF503020.toInt()))    // dark: below MIN_RED
        assertEquals(64, BrakeLightDetector.redness(0xFFE0A030.toInt()))   // amber: weak
    }

    /**
     * Lead 28 m ahead brakes moderately (3 m/s^2) at 80 km/h. With its brake lights visible the
     * CAUTION comes earlier; CRITICAL is still decided by the looming evidence alone.
     */
    @Test fun brakeLightsGiveEarlierCaution() {
        val gain = ArrayList<Float>()
        val critDiff = ArrayList<Float>()
        for (seed in 1L..8L) {
            val res = listOf(false, true).map { lights ->
                val proj = Projector(seed = seed)
                // Close enough (28 m) that the lamps are resolvable before the lead brakes.
                val lead = SimObject(z = 28f, x = 0f, width = 1.8f, height = 1.45f)
                val ego = 22f; var leadV = 22f
                val src = SimTailLights(seed = seed)
                var cautAt = Float.NaN; var critAt = Float.NaN; var brakingSeen = false
                runSim(proj, listOf(lead), 12f, 8f, step = { dt, t ->
                    if (t > 2f) leadV = (leadV - 3f * dt).coerceAtLeast(0f)
                    src.braking = t > 2f
                    lead.z -= (ego - leadV) * dt
                    ego
                }, pixels = if (lights) src else null) { _, r ->
                    val realTtc = if (ego > leadV) lead.z / (ego - leadV) else Float.NaN
                    brakingSeen = brakingSeen || r.leadBraking
                    if (cautAt.isNaN() && r.fcw.ordinal >= Severity.CAUTION.ordinal) cautAt = realTtc
                    if (critAt.isNaN() && r.fcw == Severity.CRITICAL) critAt = realTtc
                }
                if (lights) assertTrue("brake lights never detected (seed $seed)", brakingSeen)
                Triple(cautAt, critAt, brakingSeen)
            }
            gain += res[1].first - res[0].first
            critDiff += res[1].second - res[0].second
        }
        println("brake lights: caution earlier by (s of real TTC) $gain")
        println("brake lights: critical TTC diff $critDiff")
        assertTrue("caution must never be later with brake lights: $gain", gain.all { it >= -0.05f })
        assertTrue("median caution gain should be >= 0.3 s: $gain", gain.sorted()[gain.size / 2] >= 0.3f)
        // The raw CRITICAL test (z >= 3, TTC < critical) ignores brake lights. The only coupling is the
        // alert gate's rule that escalation from an already-active CAUTION needs half the evidence,
        // which can move CRITICAL by at most a frame or so.
        assertTrue("critical must not move materially earlier: $critDiff", critDiff.all { it.isNaN() || it <= 0.25f })
    }

    /** Brake lights while the gap is steady (both slowing together): no FCW at all. */
    @Test fun brakeLightsAloneDoNotWarn() {
        val proj = Projector(seed = 12)
        val lead = SimObject(z = 20f, x = 0f, width = 1.8f, height = 1.45f)
        val src = SimTailLights(seed = 12)
        var braking = 0
        val events = runSim(proj, listOf(lead), 20f, 8f, step = { _, t -> src.braking = (t.toInt() / 4) % 2 == 1; 12f },
            pixels = src) { _, r -> if (r.leadBraking) braking++ }
        assertTrue("brake lights should be seen", braking > 20)
        assertEquals(0, events.count { it.type == AlertType.FCW })
    }
}
