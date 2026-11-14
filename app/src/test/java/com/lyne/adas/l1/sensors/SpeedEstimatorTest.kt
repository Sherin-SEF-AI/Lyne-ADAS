package com.lyne.adas.l1.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class SpeedEstimatorTest {
    /** Drives the estimator with 50 Hz accel (noise + bias) and 1 Hz GPS until [gpsUntilMs]. */
    private class Drive(val bias: Float = 0.15f, val noise: Float = 0.8f, seed: Long = 7L) {
        val est = SpeedEstimator()
        private val rnd = Random(seed)
        var t = 0L
        var v = 15f
        fun run(untilMs: Long, gps: Boolean, accel: (Long) -> Float = { 0f }, onTick: (Long) -> Unit = {}) {
            while (t < untilMs) {
                t += 20L
                val a = accel(t)
                v = (v + a * 0.02f).coerceAtLeast(0f)
                est.onAccel(t, a + bias + (rnd.nextGaussian() * noise).toFloat())
                if (gps && t % 1000L == 0L) est.onGps(t, (v + rnd.nextGaussian() * 0.15).toFloat().coerceAtLeast(0f))
                onTick(t)
            }
        }
    }

    @Test
    fun tenSecondOutageAtConstantSpeedStaysClose() {
        val d = Drive()
        d.run(60_000L, gps = true)
        assertTrue("bias learned ${d.est.biasMps2}", abs(d.est.biasMps2 - 0.15f) < 0.08f)
        var worst = 0f
        d.run(70_000L, gps = false) { t ->
            val s = d.est.speedMps(t)
            assertFalse("speed lost at $t", s.isNaN())
            worst = maxOf(worst, abs(s - d.v))
        }
        assertTrue("estimated flag", d.est.isEstimated(d.t))
        assertTrue("max error ${worst * 3.6f} km/h", worst * 3.6f < 3f)
    }

    @Test
    fun outageWithoutBiasLearningStillBounded() {
        // Unlearned accelerometer bias (fresh start, no GPS history): the cruise deadband holds the speed.
        val e = SpeedEstimator()
        var t = 0L
        e.onGps(t, 15f)
        while (t < 10_000L) { t += 20L; e.onAccel(t, 0.2f) }
        val s = e.speedMps(t)
        assertTrue("held speed $s", abs(s - 15f) * 3.6f < 2f)
    }

    @Test
    fun followsBrakingDuringOutage() {
        val d = Drive()
        d.run(60_000L, gps = true)
        // GPS lost; car brakes at 2 m/s^2 for 4 s from 15 to 7 m/s, then cruises.
        d.run(70_000L, gps = false, accel = { t -> if (t in 61_000L..65_000L) -2f else 0f })
        val s = d.est.speedMps(d.t)
        assertTrue("estimate ${s * 3.6f} vs true ${d.v * 3.6f}", abs(s - d.v) * 3.6f < 5f)
        assertTrue("braking must show up: ${s * 3.6f}", s < 11f)
    }

    @Test
    fun longOutageGivesUp() {
        val d = Drive()
        d.run(30_000L, gps = true)
        d.run(30_000L + 21_000L, gps = false)
        assertTrue(d.est.speedMps(d.t).isNaN())
        assertFalse(d.est.isEstimated(d.t))
    }

    @Test
    fun withoutImuHoldIsShort() {
        val e = SpeedEstimator()
        e.onGps(0L, 12f)
        assertEquals(12f, e.speedMps(2_000L), 1e-4f)
        assertFalse(e.isEstimated(2_000L))
        assertEquals(12f, e.speedMps(5_000L), 1e-4f)
        assertTrue(e.isEstimated(5_000L))
        assertTrue(e.speedMps(9_000L).isNaN())
    }

    @Test
    fun resumesCleanlyWhenGpsReturns() {
        val d = Drive()
        d.run(30_000L, gps = true)
        d.run(42_000L, gps = false)
        assertTrue(d.est.isEstimated(d.t))
        // GPS back with a clearly different speed: it wins immediately.
        d.v = 20f
        d.est.onGps(d.t + 20L, 20f)
        assertEquals(20f, d.est.speedMps(d.t + 40L), 1e-4f)
        assertFalse(d.est.isEstimated(d.t + 40L))
        assertTrue(d.est.sigmaMps(d.t + 40L) < 0.5f)
    }

    @Test
    fun uncertaintyGrows() {
        val d = Drive()
        d.run(30_000L, gps = true)
        val s0 = d.est.sigmaMps(d.t)
        d.run(35_000L, gps = false)
        val s1 = d.est.sigmaMps(d.t)
        d.run(45_000L, gps = false)
        val s2 = d.est.sigmaMps(d.t)
        assertTrue("$s0 < $s1 < $s2", s0 < s1 && s1 < s2)
    }

    @Test
    fun forwardAxisFromLandscapeMount() {
        // Landscape on a windshield: gravity along device +X; forward is device -Z.
        val a = SpeedEstimator.forwardAccel(9.81f, 0f, -1.5f, 9.81f, 0f, 0f)
        assertEquals(1.5f, a, 1e-4f)
        // Tilted 10 deg back (camera looking slightly up): forward stays horizontal.
        val th = Math.toRadians(10.0)
        val gx = (9.81 * Math.cos(th)).toFloat(); val gz = (9.81 * Math.sin(th)).toFloat()
        // Pure forward accel of 2 m/s^2 in the world, expressed in device axes.
        val fwdX = (Math.sin(th)).toFloat(); val fwdZ = (-Math.cos(th)).toFloat()
        val r = SpeedEstimator.forwardAccel(gx + 2f * fwdX, 0f, gz + 2f * fwdZ, gx, 0f, gz)
        assertEquals(2f, r, 1e-3f)
        assertTrue(SpeedEstimator.forwardAccel(0f, 0f, 9.81f, 0f, 0f, 9.81f).isNaN())
    }
}
