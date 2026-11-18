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
            val half = 0.03f + 0.37f * t
            for (x in 0 until w) {
                val nx = (x + 0.5f) / w
                if (kotlin.math.abs(nx - 0.5f) <= half) m[y * w + x] = 1
                if (sideArea && nx > 0.92f && ny > 0.6f) m[y * w + x] = 1 // pavement island, not connected
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
        for (i in 0 until c.length) assertTrue("leaked at $i: ${c.right[i]}", c.right[i] < 0.92f)
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
}
