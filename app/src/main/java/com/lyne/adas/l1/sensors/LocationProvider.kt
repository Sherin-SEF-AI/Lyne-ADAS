package com.lyne.adas.l1.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lyne.adas.l1.logging.Log

/**
 * GPS speed via FusedLocationProvider. Degrades gracefully: if permission is absent or no fix is
 * available, [speedKph] stays NaN and speed-dependent warnings (overspeed, headway timing) fall
 * back or suppress rather than using bad data.
 */
class LocationProvider(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @Volatile private var rawSpeedMps: Float = Float.NaN
    @Volatile private var lastFixMs = 0L

    /** GPS speed, or NaN when unknown or stale (>3 s without a fix: tunnels, lost signal). */
    val speedMps: Float
        get() = if (android.os.SystemClock.elapsedRealtime() - lastFixMs > 3000L) Float.NaN else rawSpeedMps

    /**
     * Speed estimate that survives GPS outages: fresh GPS speed, else the last good speed
     * dead-reckoned with the IMU (fed by [MotionProvider.speedEstimator]) for up to ~20 s, else NaN.
     */
    val estimator = SpeedEstimator()

    /** Best available speed (m/s): GPS, or a held/dead-reckoned estimate; NaN when unknown. */
    val fusedSpeedMps: Float get() = estimator.speedMps(android.os.SystemClock.elapsedRealtime())

    /** True while [fusedSpeedMps] is an estimate held through a GPS outage. */
    val speedEstimated: Boolean get() = estimator.isEstimated(android.os.SystemClock.elapsedRealtime())

    /** Longitudinal acceleration from consecutive GPS speeds (m/s^2), NaN if unknown. */
    @Volatile var longAccel: Float = Float.NaN
        private set
    val speedKph: Float get() = if (speedMps.isNaN()) Float.NaN else speedMps * 3.6f
    @Volatile var hasFix: Boolean = false
        private set
    @Volatile private var started = false
    @Volatile var latitude: Double = Double.NaN
        private set
    @Volatile var longitude: Double = Double.NaN
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            val sAcc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else Float.NaN
            val hAcc = if (loc.hasAccuracy()) loc.accuracy else Float.NaN
            if (loc.hasSpeed() && usable(sAcc, hAcc)) {
                val prev = rawSpeedMps; val dt = (now - lastFixMs) / 1000f
                val v = plausible(prev, loc.speed, dt)
                longAccel = if (!prev.isNaN() && dt in 0.2f..3f) (v - prev) / dt else Float.NaN
                rawSpeedMps = v
                lastFixMs = now
                estimator.onGps(now, v)
            }
            latitude = loc.latitude; longitude = loc.longitude
            hasFix = true
        }
    }

    /** Whether speed is available, and if not, why (drives the on-screen hint). */
    fun gpsState(): com.lyne.adas.l1.perf.GpsState {
        if (!hasPermission()) return com.lyne.adas.l1.perf.GpsState.NO_PERMISSION
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val on = try { lm == null || androidx.core.location.LocationManagerCompat.isLocationEnabled(lm) } catch (_: Throwable) { true }
        if (!on) return com.lyne.adas.l1.perf.GpsState.OFF
        val fresh = !fusedSpeedMps.isNaN()
        return if (fresh) com.lyne.adas.l1.perf.GpsState.OK else com.lyne.adas.l1.perf.GpsState.NO_FIX
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) { Log.i(TAG, "location permission absent; speed disabled"); return }
        if (started) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            client.requestLocationUpdates(req, callback, context.mainLooper)
            started = true
        } catch (t: Throwable) {
            Log.w(TAG, "requestLocationUpdates failed", t)
        }
    }

    fun stop() {
        try { client.removeLocationUpdates(callback) } catch (_: Throwable) {}
        started = false
        hasFix = false
        estimator.reset()
    }

    companion object {
        private const val TAG = "LocationProvider"
        /** Speed is ignored when the receiver itself says it is this uncertain (weak/indoor GPS). */
        const val MAX_SPEED_ACC_MPS = 2.5f
        const val MAX_H_ACC_M = 35f
        /** A car can't gain speed faster than this; brakes can shed it much faster. */
        const val MAX_ACCEL_MPS2 = 4.5f
        const val MAX_DECEL_MPS2 = 10f

        internal fun usable(speedAccMps: Float, hAccM: Float): Boolean =
            !(speedAccMps > MAX_SPEED_ACC_MPS) && !(hAccM > MAX_H_ACC_M)

        /**
         * Rate-limits a new GPS speed against the previous one. Weak fixes (indoors, urban canyon)
         * produce position jumps that read as sudden 20-30 km/h; real speed changes pass within a
         * second or two.
         */
        internal fun plausible(prev: Float, v: Float, dtS: Float): Float {
            if (prev.isNaN() || dtS !in 0.05f..3f) return v
            return v.coerceIn(prev - MAX_DECEL_MPS2 * dtS, prev + MAX_ACCEL_MPS2 * dtS).coerceAtLeast(0f)
        }
    }
}
