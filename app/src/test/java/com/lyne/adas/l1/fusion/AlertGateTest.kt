package com.lyne.adas.l1.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertGateTest {
    /** Regression (found in video bench test): an alert must clear ~offMs after the threat ends,
     *  not linger while accumulated evidence drains. */
    @Test fun releasesPromptlyAfterThreatEnds() {
        val g = AlertGate(cautionOnMs = 300, criticalOnMs = 200, offMs = 600)
        var t = 0L
        while (t < 3000) { g.update(Severity.CRITICAL, t); t += 66 }
        assertEquals(Severity.CRITICAL, g.active)
        val end = t
        while (g.active != Severity.NONE && t < end + 5000) { g.update(Severity.NONE, t); t += 66 }
        assertTrue("released after ${t - end} ms", t - end <= 800)
    }

    @Test fun singleFrameSpikeNeverFires() {
        val g = AlertGate(cautionOnMs = 300, criticalOnMs = 200)
        var fired = false; var t = 0L
        repeat(300) { i -> fired = fired or g.update(if (i % 10 == 0) Severity.CRITICAL else Severity.NONE, t); t += 66 }
        assertTrue(!fired)
    }

    @Test fun oneEventPerIncident() {
        val g = AlertGate(cautionOnMs = 300, criticalOnMs = 200)
        var events = 0; var t = 0L
        repeat(100) { if (g.update(Severity.CAUTION, t)) events++; t += 66 }
        assertEquals(1, events)
    }
}
