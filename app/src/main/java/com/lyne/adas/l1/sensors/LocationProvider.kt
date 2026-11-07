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

    /** Longitudinal acceleration from consecutive GPS speeds (m/s^2), NaN if unknown. */
    @Volatile var longAccel: Float = Float.NaN
        private set
    val speedKph: Float get() = if (speedMps.isNaN()) Float.NaN else speedMps * 3.6f
    @Volatile var hasFix: Boolean = false
        private set
    @Volatile var latitude: Double = Double.NaN
        private set
    @Volatile var longitude: Double = Double.NaN
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            if (loc.hasSpeed()) {
                val prev = rawSpeedMps; val dt = (now - lastFixMs) / 1000f
                longAccel = if (!prev.isNaN() && dt in 0.2f..3f) (loc.speed - prev) / dt else Float.NaN
                rawSpeedMps = loc.speed
                lastFixMs = now
            }
            latitude = loc.latitude; longitude = loc.longitude
            hasFix = true
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) { Log.i(TAG, "location permission absent; speed disabled"); return }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            client.requestLocationUpdates(req, callback, context.mainLooper)
        } catch (t: Throwable) {
            Log.w(TAG, "requestLocationUpdates failed", t)
        }
    }

    fun stop() {
        try { client.removeLocationUpdates(callback) } catch (_: Throwable) {}
        hasFix = false
    }

    companion object { private const val TAG = "LocationProvider" }
}
