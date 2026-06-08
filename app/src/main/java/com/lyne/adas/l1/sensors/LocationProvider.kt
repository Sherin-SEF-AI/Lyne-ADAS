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

    @Volatile var speedMps: Float = Float.NaN
        private set
    val speedKph: Float get() = if (speedMps.isNaN()) Float.NaN else speedMps * 3.6f
    @Volatile var hasFix: Boolean = false
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            speedMps = if (loc.hasSpeed()) loc.speed else speedMps
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
