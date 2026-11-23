package com.lyne.adas.l1.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSpeedGateTest {
    @Test fun weakFixIsIgnored() {
        assertFalse(LocationProvider.usable(4f, 15f))
        assertFalse(LocationProvider.usable(1f, 60f))
        assertTrue(LocationProvider.usable(0.9f, 20.5f))
        assertTrue(LocationProvider.usable(Float.NaN, Float.NaN))
    }

    @Test fun indoorSpikeIsRateLimited() {
        // Parked (0.2 m/s), then a jittery fix claims 7 m/s (25 km/h) one second later.
        assertEquals(4.7f, LocationProvider.plausible(0.2f, 7f, 1f), 1e-3f)
    }

    @Test fun hardBrakingPassesThrough() {
        assertEquals(6f, LocationProvider.plausible(14f, 6f, 1f), 1e-3f)
    }
}
