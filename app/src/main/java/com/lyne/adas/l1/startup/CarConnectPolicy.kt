package com.lyne.adas.l1.startup

/**
 * Decides whether a Bluetooth connection should trigger the "start Lyne" reminder. Pure (device
 * class values are android.bluetooth.BluetoothClass.Device constants, copied so this is JVM-testable).
 *
 * If the user picked a specific car device, only that address matches. Otherwise any device that
 * reports itself as car audio or a hands-free kit matches.
 */
object CarConnectPolicy {
    /** BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO */
    const val CLASS_CAR_AUDIO = 0x0420
    /** BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE */
    const val CLASS_HANDSFREE = 0x0408

    /** Do not nag again within this window (cars often drop/reconnect ACL links). */
    const val COOLDOWN_MS = 10 * 60 * 1000L

    fun isCarClass(deviceClass: Int?): Boolean = deviceClass == CLASS_CAR_AUDIO || deviceClass == CLASS_HANDSFREE

    fun shouldNotify(
        enabled: Boolean,
        deviceAddress: String?,
        deviceClass: Int?,
        chosenAddress: String?,
        nowMs: Long,
        lastNotifiedMs: Long,
    ): Boolean {
        if (!enabled) return false
        if (nowMs - lastNotifiedMs in 0 until COOLDOWN_MS) return false
        return if (!chosenAddress.isNullOrBlank()) {
            deviceAddress != null && deviceAddress.equals(chosenAddress, ignoreCase = true)
        } else {
            isCarClass(deviceClass)
        }
    }
}
