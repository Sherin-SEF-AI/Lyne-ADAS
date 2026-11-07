package com.lyne.adas.l1.calibration

import kotlin.math.atan
import kotlin.math.tan

/**
 * Everything needed to turn a normalized image point into metres on a flat road: field of view of the
 * analysis frame, camera height above the road, and the mount's pitch/roll (from the gravity sensor).
 *
 * Image coordinates are normalized [0,1], origin top-left, y down. Internally we work in tangent-plane
 * units: u = (x-0.5)*2*tan(hFov/2), v = (y-0.5)*2*tan(vFov/2), so a ray through (u,v) has direction
 * (u, v, 1) in the camera frame. Pure Kotlin, no Android types, unit-testable.
 */
class CameraGeometry(
    hFovDeg: Float,
    vFovDeg: Float,
    /** Camera height above the road surface (metres), already multiplied by any auto-cal scale. */
    val cameraHeightM: Float,
    /** Positive = optical axis tilted DOWN toward the road (radians). */
    val pitchRad: Float,
    /** Image-plane horizon slope dv/du (tan of roll). Positive = horizon lower on the right. */
    val rollTan: Float,
    /** Final multiplicative correction from the manual known-gap calibration (1 = none). */
    val distanceScale: Float = 1f,
    /** 1-sigma uncertainty of [pitchRad]; ~0.5 deg once the IMU has converged, more otherwise. */
    val pitchSigmaRad: Float = 0.015f,
) {
    val tanHalfH: Float = tan(Math.toRadians(hFovDeg / 2.0)).toFloat()
    val tanHalfV: Float = tan(Math.toRadians(vFovDeg / 2.0)).toFloat()
    val hFovDeg: Float = hFovDeg
    val vFovDeg: Float = vFovDeg

    fun u(nx: Float): Float = (nx - 0.5f) * 2f * tanHalfH
    fun v(ny: Float): Float = (ny - 0.5f) * 2f * tanHalfV

    /** Normalized image row of the horizon at column [nx]. */
    fun horizonY(nx: Float = 0.5f): Float {
        val vh = -tan(pitchRad) + rollTan * u(nx)
        return 0.5f + vh / (2f * tanHalfV)
    }

    /**
     * Angle (radians) below the horizontal of the ray through (nx, ny). Positive = below horizon.
     * Roll is removed by measuring v relative to the tilted horizon line.
     */
    fun depressionRad(nx: Float, ny: Float): Float {
        val vRel = v(ny) - rollTan * u(nx)
        return pitchRad + atan(vRel)
    }

    /** Flat-road distance to a ground contact point (box bottom). NaN at/above the horizon. */
    fun groundDistance(nx: Float, nyBottom: Float): Float {
        val a = depressionRad(nx, nyBottom)
        if (a < MIN_DEPRESSION_RAD) return Float.NaN
        return cameraHeightM / tan(a) * distanceScale
    }

    /** Distance from an object's apparent width given its real width (pinhole depth). */
    fun widthDistance(boxWidthNorm: Float, realWidthM: Float): Float {
        val w = boxWidthNorm.coerceAtLeast(1e-4f)
        return realWidthM / (w * 2f * tanHalfH) * distanceScale
    }

    /** Real width (m) implied by a box width at a known depth: inverse of [widthDistance]. */
    fun impliedWidth(boxWidthNorm: Float, depthM: Float): Float =
        boxWidthNorm * 2f * tanHalfH * depthM / distanceScale

    /** Lateral offset (m, + = right) of image column [nx] at forward depth [depthM]. */
    fun lateralM(nx: Float, depthM: Float): Float = u(nx) * depthM

    /** Radians subtended by one detector-input pixel vertically. */
    fun pixelAngleV(inputPx: Int): Float = 2f * tanHalfV / inputPx.coerceAtLeast(1)

    companion object {
        /** Rays shallower than ~0.4 deg below the horizon are numerically useless for range. */
        const val MIN_DEPRESSION_RAD = 0.007f
        const val DEFAULT_CAMERA_HEIGHT_M = 1.25f
    }
}
