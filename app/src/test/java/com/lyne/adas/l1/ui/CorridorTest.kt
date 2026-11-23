package com.lyne.adas.l1.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorTest {
    private val w = 80; private val h = 60

    /** Trapezoid road from the bottom to y=0.45, optionally with a disconnected side area. */
    private fun mask(sideArea: Boolean = false, sideRoadRow: Int = -1): ByteArray {
        val m = ByteArray(w * h)
        for (y in 0 until h) {
            val ny = (y + 0.5f) / h
            if (ny < 0.45f) continue
            val t = (ny - 0.45f) / 0.55f            // 0 at horizon -> 1 at bottom
            val half = 0.03f + 0.27f * t
            for (x in 0 until w) {
                val nx = (x + 0.5f) / w
                if (kotlin.math.abs(nx - 0.5f) <= half) m[y * w + x] = 1
                if (sideArea && nx > 0.93f && ny > 0.6f) m[y * w + x] = 1 // pavement island, well separated
                if (y == sideRoadRow && nx > 0.5f) m[y * w + x] = 1         // side road joining
            }
        }
        return m
    }

    @Test
    fun followsTheRoadAndNarrowsTowardHorizon() {
        val c = Corridor.fromMask(mask(), w, h, topY = 0.47f)!!
        val n = c.length
        assertTrue("too short: $n", n >= Corridor.N - 3)
        assertTrue(c.right[0] - c.left[0] > c.right[n - 1] - c.left[n - 1])
        for (i in 0 until n) assertEquals(0.5f, (c.left[i] + c.right[i]) / 2f, 0.03f)
    }

    @Test
    fun ignoresDisconnectedDrivablePatches() {
        val c = Corridor.fromMask(mask(sideArea = true), w, h, topY = 0.47f)!!
        for (i in 0 until c.length) assertTrue("leaked at $i: ${c.right[i]}", c.right[i] < 0.93f)
    }

    @Test
    fun sideRoadDoesNotWidenThePath() {
        val c = Corridor.fromMask(mask(sideRoadRow = 40), w, h, topY = 0.47f)!!
        for (i in 1 until c.length) {
            val wPrev = c.right[i - 1] - c.left[i - 1]; val wNow = c.right[i] - c.left[i]
            assertTrue("widened at $i: $wPrev -> $wNow", wNow <= wPrev * 1.2f + 0.03f)
        }
    }

    @Test
    fun doesNotClimbIntoRoadsideVegetation() {
        // Road trapezoid plus a "drivable" blob attached on the left that sweeps up and outward
        // (segmentation leaking into a hedge/trees).
        val m = mask()
        for (y in 0 until h) {
            val ny = (y + 0.5f) / h
            if (ny < 0.2f || ny > 0.75f) continue
            val reach = 0.25f - (0.75f - ny) * 0.4f      // further left the higher it goes
            for (x in 0 until w) { val nx = (x + 0.5f) / w; if (nx > reach && nx < 0.5f) m[y * w + x] = 1 }
        }
        val c = Corridor.fromMask(m, w, h, topY = 0.25f)!!
        for (i in 0 until c.length) {
            if (c.rows[i] < 0.6f) assertTrue("leaked left at y=${c.rows[i]}: ${c.left[i]}", c.left[i] > 0.15f)
        }
    }

    @Test
    fun stopsAtTheLeadVehicle() {
        val c = Corridor.fromMask(mask(), w, h, topY = 0.7f)!!
        for (i in 0 until c.length) assertTrue(c.rows[i] >= 0.7f - 1e-3f)
    }

    @Test
    fun noRoadNoCorridor() {
        assertNull(Corridor.fromMask(ByteArray(w * h), w, h, topY = 0.47f))
    }

    @Test
    fun smootherEasesTowardNewShape() {
        val s = CorridorSmoother(tauMs = 100f)
        val a = Corridor.fromMask(mask(), w, h, topY = 0.47f)!!
        var t = 1_000_000_000L
        repeat(30) { s.update(a, t); t += 16_000_000L }
        val shifted = Corridor(a.rows.copyOf(), FloatArray(Corridor.N) { a.left[it] + 0.1f }, FloatArray(Corridor.N) { a.right[it] + 0.1f })
        val after1 = s.update(shifted, t + 16_000_000L)!!
        // One frame later it has moved part of the way, not jumped.
        assertTrue(after1.left[0] > a.left[0] + 0.005f && after1.left[0] < a.left[0] + 0.09f)
    }
    @Test
    fun curvedDashboardDoesNotShrinkTheCorridorToOneLane() {
        // A dashboard edge that rises to the right hides the near road unevenly: the lowest visible
        // rows show only the left part of the road.
        val m = mask()
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x + 0.5f) / w; val ny = (y + 0.5f) / h
            if (ny > 0.92f - 0.25f * nx) m[y * w + x] = 0
        }
        val c = Corridor.fromMask(m, w, h, topY = 0.3f)!!
        val full = Corridor.fromMask(mask(), w, h, topY = 0.3f)!!
        fun widthAt(c: Corridor, y: Float): Float {
            var best = 0; for (i in 0 until c.length) if (kotlin.math.abs(c.rows[i] - y) < kotlin.math.abs(c.rows[best] - y)) best = i
            return c.right[best] - c.left[best]
        }
        assertTrue("narrowed: ${widthAt(c, 0.55f)} vs ${widthAt(full, 0.55f)}", widthAt(c, 0.55f) > 0.8f * widthAt(full, 0.55f))
    }

    @Test
    fun startsWhereTheRoadStartsNotOverTheBonnet() {
        // Bottom 25% hidden (bonnet / screen edge): the corridor must not be stretched down over it.
        val m = mask()
        for (y in (h * 0.75f).toInt() until h) for (x in 0 until w) m[y * w + x] = 0
        val c = Corridor.fromMask(m, w, h, topY = 0.3f)!!
        assertTrue("starts at ${c.rows[0]}", c.rows[0] < 0.8f)
    }

    @Test
    fun thinSliverAtTheBottomDoesNotNarrowTheCorridor() {
        // A thin run at the very bottom, then the full road above it.
        val m = mask()
        for (y in (h * 0.92f).toInt() until h) for (x in 0 until w) {
            val nx = (x + 0.5f) / w; m[y * w + x] = if (nx in 0.48f..0.53f) 1 else 0
        }
        val c = Corridor.fromMask(m, w, h, topY = 0.3f)!!
        val mid = c.length / 3
        assertTrue("too narrow: ${c.right[mid] - c.left[mid]}", c.right[mid] - c.left[mid] > 0.3f)
    }
}

class MaskCleanupTest {
    private val w = 80; private val h = 45

    /** Full road below row 22 split by a thin diagonal lane marking, bottom row empty (bonnet). */
    private fun markedRoad(): ByteArray {
        val m = ByteArray(w * h)
        for (y in 22 until h - 1) for (x in 0 until w) m[y * w + x] = 1
        for (y in 22 until h - 1) { val x = 40 - (y - 22); m[y * w + x] = 0; m[y * w + x - 1] = 0 }
        return m
    }

    @Test
    fun laneMarkingsDoNotSplitTheRoad() {
        val c = Corridor.fromMask(markedRoad(), w, h, topY = 0.5f)!!
        // The whole road width is covered near the car, not just one side of the marking.
        assertTrue("left ${c.left[0]} right ${c.right[0]}", c.left[0] < 0.05f && c.right[0] > 0.95f)
    }

    @Test
    fun emptyBottomRowIsBridged() {
        val c = Corridor.fromMask(markedRoad(), w, h, topY = 0.5f)!!
        assertTrue(c.length >= Corridor.N - 2)
    }

    @Test
    fun closeGapsFillsOnlySmallHoles() {
        val m = ByteArray(10 * 3) { 1 }
        m[1 * 10 + 4] = 0                                // 1-cell hole: filled
        for (x in 0 until 10) m[2 * 10 + x] = if (x < 5) 1 else 0 // open edge: kept
        val out = Corridor.closeGaps(m, 10, 3)
        assertEquals(1, out[1 * 10 + 4].toInt())
        assertEquals(0, out[2 * 10 + 9].toInt())
    }

}
