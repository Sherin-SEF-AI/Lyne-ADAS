package com.lyne.adas.l1.alerts

import com.lyne.adas.l1.fusion.Severity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutePolicyTest {
    @Test fun unmutedEverythingSounds() {
        for (s in Severity.entries) assertTrue(MutePolicy.audible(s, muted = false, speedKph = 0f))
    }

    @Test fun mutedCriticalStillSoundsWhileMoving() {
        assertTrue(MutePolicy.audible(Severity.CRITICAL, muted = true, speedKph = 50f))
        assertTrue(MutePolicy.audible(Severity.CRITICAL, muted = true, speedKph = 10.5f))
    }

    @Test fun mutedCriticalSoundsWhenSpeedUnknown() {
        assertTrue(MutePolicy.audible(Severity.CRITICAL, muted = true, speedKph = null))
        assertTrue(MutePolicy.audible(Severity.CRITICAL, muted = true, speedKph = Float.NaN))
    }

    @Test fun mutedCriticalSilentWhenParked() {
        assertFalse(MutePolicy.audible(Severity.CRITICAL, muted = true, speedKph = 0f))
        assertFalse(MutePolicy.audible(Severity.CRITICAL, muted = true, speedKph = 10f))
    }

    @Test fun muteSilencesInfoAndCautionAtAnySpeed() {
        for (v in listOf(0f, 30f, 120f)) {
            assertFalse(MutePolicy.audible(Severity.CAUTION, muted = true, speedKph = v))
            assertFalse(MutePolicy.audible(Severity.INFO, muted = true, speedKph = v))
        }
    }
}
