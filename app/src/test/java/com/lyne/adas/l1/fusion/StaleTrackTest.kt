package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraGeometry
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.ObjectClass
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for a road drive where a box that could not be ranged (cut off by the frame edges)
 * kept a track alive for minutes. The track coasted on a 1 m distance from its first frame and was
 * shown as the lead ("1 m ±2248") with a bogus 0.2 s headway.
 */
class StaleTrackTest {
    private val geom = CameraGeometry(66f, 52f, 1.3f, 0f, 0f, 1f, 0.005f)
    private val dtNs = 125_000_000L // 8 fps

    @Test
    fun trackWithoutRangeLosesItsDistance() {
        val tracker = ObjectTracker()
        var now = 1_000_000_000L
        // Close car, measurable (inside the frame).
        val good = BBox(0.30f, 0.45f, 0.70f, 0.90f)
        repeat(5) {
            tracker.update(listOf(Detection(ObjectClass.CAR, 0.9f, good)), 0.4f, now).forEach { it.step(now, it.misses == 0, geom, 256) }
            now += dtNs
        }
        val t = tracker.update(emptyList(), 0.4f, now).single()
        assertTrue(!t.distanceM.isNaN())
        // Same object now fills the frame: both range cues are truncated, nothing to measure.
        val cut = BBox(0f, 0.40f, 1f, 1f)
        var last: Track? = null
        repeat(16) { // 2 s
            now += dtNs
            last = tracker.update(listOf(Detection(ObjectClass.CAR, 0.9f, cut)), 0.4f, now).firstOrNull()
            last?.step(now, last!!.misses == 0, geom, 256)
        }
        assertTrue("stale range must be dropped, got ${last?.distanceM}", last == null || last!!.distanceM.isNaN())
    }

    @Test
    fun dashboardBoxNeverBecomesATrack() {
        val tracker = ObjectTracker()
        var now = 0L
        val dash = BBox(0f, 0.82f, 0.18f, 1f)
        repeat(20) {
            val tracks = tracker.update(listOf(Detection(ObjectClass.CAR, 0.9f, dash)), 0.4f, now)
            assertTrue(tracks.isEmpty())
            now += dtNs
        }
    }
}
