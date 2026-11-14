package com.lyne.adas.l1.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarConnectPolicyTest {
    private val now = 100_000_000L
    private val headset = 0x0418 // AUDIO_VIDEO_HEADPHONES

    @Test fun disabledNeverNotifies() =
        assertFalse(CarConnectPolicy.shouldNotify(false, "AA", CarConnectPolicy.CLASS_CAR_AUDIO, null, now, 0L))

    @Test fun carAudioAndHandsfreeMatchWhenNoDeviceChosen() {
        assertTrue(CarConnectPolicy.shouldNotify(true, "AA", CarConnectPolicy.CLASS_CAR_AUDIO, null, now, 0L))
        assertTrue(CarConnectPolicy.shouldNotify(true, "AA", CarConnectPolicy.CLASS_HANDSFREE, null, now, 0L))
    }

    @Test fun headphonesAndUnknownIgnored() {
        assertFalse(CarConnectPolicy.shouldNotify(true, "AA", headset, null, now, 0L))
        assertFalse(CarConnectPolicy.shouldNotify(true, null, null, null, now, 0L))
    }

    @Test fun chosenDeviceMatchesByAddressOnly() {
        assertTrue(CarConnectPolicy.shouldNotify(true, "aa:bb", headset, "AA:BB", now, 0L))
        assertFalse(CarConnectPolicy.shouldNotify(true, "CC:DD", CarConnectPolicy.CLASS_CAR_AUDIO, "AA:BB", now, 0L))
        assertFalse(CarConnectPolicy.shouldNotify(true, null, null, "AA:BB", now, 0L))
    }

    @Test fun cooldownSuppressesReconnectStorms() {
        val c = CarConnectPolicy.CLASS_CAR_AUDIO
        assertFalse(CarConnectPolicy.shouldNotify(true, "AA", c, null, now, now - 60_000L))
        assertTrue(CarConnectPolicy.shouldNotify(true, "AA", c, null, now, now - CarConnectPolicy.COOLDOWN_MS))
    }
}
