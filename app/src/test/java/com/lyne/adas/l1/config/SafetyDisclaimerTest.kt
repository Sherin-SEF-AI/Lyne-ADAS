package com.lyne.adas.l1.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyDisclaimerTest {
    @Test fun neverAcceptedNeedsAcceptance() = assertTrue(SafetyDisclaimer.needsAcceptance(0))

    @Test fun olderVersionNeedsAcceptance() = assertTrue(SafetyDisclaimer.needsAcceptance(SafetyDisclaimer.VERSION - 1))

    @Test fun currentVersionAccepted() = assertFalse(SafetyDisclaimer.needsAcceptance(SafetyDisclaimer.VERSION))

    @Test fun coversRequiredPoints() {
        val text = SafetyDisclaimer.POINTS.joinToString(" ").lowercase()
        for (k in listOf("warning-only", "not certified", "eyes on the road", "mount", "miss hazards")) {
            assertTrue("missing '$k'", k in text)
        }
        assertEquals(5, SafetyDisclaimer.POINTS.size)
    }
}
