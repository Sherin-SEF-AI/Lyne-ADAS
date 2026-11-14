package com.lyne.adas.l1.perf

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceGuidanceTest {
    @Test fun lowBatteryNotChargingAsksForCharger() =
        assertEquals(listOf(DeviceHint.PLUG_IN), DeviceGuidance.hints(29, charging = false, thermalStatus = 0))

    @Test fun lowBatteryWhileChargingIsFine() =
        assertEquals(emptyList<DeviceHint>(), DeviceGuidance.hints(10, charging = true, thermalStatus = 0))

    @Test fun thirtyPercentIsFine() =
        assertEquals(emptyList<DeviceHint>(), DeviceGuidance.hints(30, charging = false, thermalStatus = 0))

    @Test fun unknownBatteryShowsNothing() =
        assertEquals(emptyList<DeviceHint>(), DeviceGuidance.hints(null, charging = null, thermalStatus = 0))

    @Test fun moderateHeatIsQuietSevereWarns() {
        assertEquals(emptyList<DeviceHint>(), DeviceGuidance.hints(80, false, thermalStatus = 2))
        assertEquals(listOf(DeviceHint.PHONE_HOT), DeviceGuidance.hints(80, false, thermalStatus = 3))
        assertEquals(listOf(DeviceHint.PHONE_HOT), DeviceGuidance.hints(80, false, thermalStatus = 6))
    }

    @Test fun bothHintsHeatFirst() =
        assertEquals(listOf(DeviceHint.PHONE_HOT, DeviceHint.PLUG_IN), DeviceGuidance.hints(5, false, thermalStatus = 4))

    @Test fun thermalLabelsMap() {
        assertEquals(0, DeviceGuidance.thermalStatusFromLabel("none"))
        assertEquals(0, DeviceGuidance.thermalStatusFromLabel("n/a"))
        assertEquals(2, DeviceGuidance.thermalStatusFromLabel("moderate"))
        assertEquals(3, DeviceGuidance.thermalStatusFromLabel("severe"))
        assertEquals(4, DeviceGuidance.thermalStatusFromLabel("critical"))
    }
}
