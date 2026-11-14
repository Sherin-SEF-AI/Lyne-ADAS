package com.lyne.adas.l1.alerts

import com.lyne.adas.l1.fusion.Severity

/**
 * Decides whether an alert may make sound/vibration given the user's mute choice.
 *
 * Mute is a comfort control for nuisance chirps, never a way to switch off the one warning that
 * matters: a CRITICAL alert (brake / critical pedestrian or animal) always sounds while the vehicle
 * is moving. Only below [MOVING_KPH] (parked, crawling in a jam) does mute silence everything.
 * Unknown speed (no GPS fix yet) is treated as moving: failing loud is the safe direction.
 */
object MutePolicy {
    const val MOVING_KPH = 10f

    /** Short text for the mute button so the user knows what mute does and does not do. */
    const val MUTE_NOTE = "Critical alerts always sound while driving"

    fun isMoving(speedKph: Float?): Boolean = speedKph == null || speedKph.isNaN() || speedKph > MOVING_KPH

    /** True if an alert of [severity] may make sound/haptics. */
    fun audible(severity: Severity, muted: Boolean, speedKph: Float?): Boolean {
        if (!muted) return true
        return severity == Severity.CRITICAL && isMoving(speedKph)
    }
}
