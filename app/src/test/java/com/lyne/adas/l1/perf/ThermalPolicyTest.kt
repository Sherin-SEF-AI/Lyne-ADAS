package com.lyne.adas.l1.perf

import com.lyne.adas.l1.perf.ThermalPolicy.Companion.STATUS_CRITICAL
import com.lyne.adas.l1.perf.ThermalPolicy.Companion.STATUS_EMERGENCY
import com.lyne.adas.l1.perf.ThermalPolicy.Companion.STATUS_LIGHT
import com.lyne.adas.l1.perf.ThermalPolicy.Companion.STATUS_MODERATE
import com.lyne.adas.l1.perf.ThermalPolicy.Companion.STATUS_NONE
import com.lyne.adas.l1.perf.ThermalPolicy.Companion.STATUS_SEVERE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    private var now = 0L

    /** Feeds [seconds] of updates at 4 Hz and returns the last plan. */
    private fun ThermalPolicy.run(status: Int, fps: Float, seconds: Float): DegradationPlan {
        var p = plan
        val end = now + (seconds * 1000).toLong()
        while (now < end) { p = update(status, fps, now); now += 250L }
        return p
    }

    @Test
    fun coolDeviceRunsEverything() {
        val p = ThermalPolicy(15f, canUseSmallModel = true).run(STATUS_LIGHT, 8f, 30f)
        assertEquals(0, p.level)
        assertTrue(p.signEnabled)
        assertEquals(1, p.segEvery)
        assertFalse(p.smallObjectModel)
    }

    @Test
    fun degradationOrderSignsThenSegThenObjectInput() {
        val pol = ThermalPolicy(15f, canUseSmallModel = true)
        pol.run(STATUS_NONE, 8f, 5f)
        // Steady fps so the fps term stays out of this test.
        val moderate = pol.run(STATUS_MODERATE, 8f, 1f)
        assertEquals(1, moderate.level)
        assertFalse("signs go first", moderate.signEnabled)
        assertEquals(1, moderate.segEvery)
        assertFalse(moderate.smallObjectModel)

        val severe = pol.run(STATUS_SEVERE, 8f, 1f)
        assertEquals(2, severe.segEvery)
        assertFalse(severe.smallObjectModel)

        val critical = pol.run(STATUS_CRITICAL, 8f, 1f)
        assertEquals(3, critical.segEvery)
        assertFalse("object model untouched until the last step", critical.smallObjectModel)

        val emergency = pol.run(STATUS_EMERGENCY, 8f, 1f)
        assertEquals(4, emergency.level)
        assertTrue(emergency.smallObjectModel)
    }

    @Test
    fun withoutASmallerModelTheLastStepSlowsSegInstead() {
        val p = ThermalPolicy(15f, canUseSmallModel = false).run(STATUS_EMERGENCY, 5f, 1f)
        assertEquals(4, p.level)
        assertFalse(p.smallObjectModel)
        assertEquals(4, p.segEvery)
    }

    @Test
    fun escalationIsImmediate() {
        val pol = ThermalPolicy(15f, canUseSmallModel = true)
        pol.run(STATUS_NONE, 8f, 5f)
        assertEquals(3, pol.update(STATUS_CRITICAL, 8f, now).level)
    }

    @Test
    fun recoveryStepsDownOneLevelPerHoldPeriod() {
        val pol = ThermalPolicy(15f, canUseSmallModel = true, coolHoldMs = 20_000L)
        pol.run(STATUS_NONE, 8f, 5f)
        pol.run(STATUS_CRITICAL, 8f, 2f)
        assertEquals(3, pol.level)
        assertEquals("still hot-mode right after cooling", 3, pol.run(STATUS_NONE, 8f, 19f).level)
        assertEquals(2, pol.run(STATUS_NONE, 8f, 2f).level)
        assertEquals(1, pol.run(STATUS_NONE, 8f, 20f).level)
        assertEquals(0, pol.run(STATUS_NONE, 8f, 20f).level)
        assertTrue(pol.plan.signEnabled)
    }

    @Test
    fun flickeringStatusDoesNotOscillate() {
        val pol = ThermalPolicy(15f, canUseSmallModel = true)
        pol.run(STATUS_NONE, 8f, 5f)
        val levels = HashSet<Int>()
        repeat(12) {
            levels += pol.run(STATUS_SEVERE, 8f, 4f).level
            levels += pol.run(STATUS_LIGHT, 8f, 4f).level
        }
        assertEquals(setOf(2), levels)
    }

    @Test
    fun lowFpsWhileHotAddsLevelsAndRecovers() {
        val pol = ThermalPolicy(15f, canUseSmallModel = true, coolHoldMs = 20_000L, lowFpsHoldMs = 5_000L)
        pol.run(STATUS_NONE, 8f, 10f)          // learns ~8 fps as the cool baseline
        assertEquals(8f, pol.coolFps, 0.5f)
        pol.run(STATUS_SEVERE, 4f, 1f)
        assertEquals(2, pol.level)
        pol.run(STATUS_SEVERE, 4f, 5f)         // throttled: 4 fps < 0.6 x 8
        assertEquals(3, pol.level)
        pol.run(STATUS_SEVERE, 4f, 5f)
        assertEquals(4, pol.level)
        assertTrue(pol.plan.smallObjectModel)
        // Frame rate recovers but the phone is still SEVERE: penalties are removed slowly.
        pol.run(STATUS_SEVERE, 7.5f, 45f)
        assertEquals(3, pol.level)
        pol.run(STATUS_SEVERE, 7.5f, 45f)
        assertEquals(2, pol.level)
        assertEquals(2, pol.run(STATUS_SEVERE, 7.5f, 60f).level)
    }

    @Test
    fun lowFpsOnACoolPhoneIsNotThermal() {
        // A slow phone that is not hot keeps every feature.
        val p = ThermalPolicy(15f, canUseSmallModel = true).run(STATUS_LIGHT, 3f, 60f)
        assertEquals(0, p.level)
    }
}
