package com.lyne.adas.l1.calibration

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Estimates the phone mount's pitch and roll from the gravity vector, expressed in SCREEN axes
 * (right, up, toward-driver). The camera looks out of the back of the phone, so:
 *   pitch (down) = atan2(g_toward, g_up)      roll slope = g_right / g_up
 *
 * Vehicle acceleration contaminates gravity (1 m/s^2 of braking tilts it ~6 deg), so updates are
 * gated: only while the vehicle is not accelerating (GPS dv/dt), the accelerometer magnitude is close
 * to g, and the gyro is quiet. A slow exponential average then rejects bumps. The first reading is
 * taken directly (the car is usually stationary when the drive starts).
 */
class MountEstimator {
    @Volatile var pitchRad: Float = Float.NaN; private set
    @Volatile var rollTan: Float = 0f; private set
    /** 0..1 how settled the estimate is; drives the pitch uncertainty used by the range model. */
    @Volatile var convergence: Float = 0f; private set

    private var accepted = 0

    /**
     * @param right,up,toward gravity components (m/s^2) in screen axes.
     * @param gyroMag total angular rate (rad/s). @param longAccel GPS longitudinal accel (m/s^2) or NaN.
     */
    fun update(right: Float, up: Float, toward: Float, gyroMag: Float, longAccel: Float, dtSec: Float) {
        val mag = sqrt(right * right + up * up + toward * toward)
        if (mag < 1f || up < 2f) return // phone not upright in landscape; ignore
        val p = atan2(toward, up)
        val r = right / up
        if (pitchRad.isNaN()) { pitchRad = p; rollTan = r; accepted = 1; return }

        val quiet = abs(mag - G) < 0.6f && gyroMag < 0.15f && (longAccel.isNaN() || abs(longAccel) < 0.4f)
        if (!quiet) return
        val tau = if (accepted < 50) 1.0f else 6.0f // converge quickly, then hold steady
        val a = (dtSec / tau).coerceIn(0f, 1f)
        pitchRad += a * (p - pitchRad)
        rollTan += a * (r - rollTan)
        accepted++
        convergence = (accepted / 300f).coerceAtMost(1f)
    }

    /** Pitch 1-sigma (rad): ~2 deg before convergence, ~0.6 deg after. */
    fun pitchSigma(): Float = 0.035f - 0.025f * convergence

    fun seed(pitch: Float, roll: Float) {
        if (!pitch.isNaN()) { pitchRad = pitch; rollTan = roll; accepted = 1 }
    }

    companion object { private const val G = 9.81f }
}
