package com.lyne.adas.l1.pipeline

import com.lyne.adas.l1.fusion.AdasEvent
import com.lyne.adas.l1.fusion.AlertType

/** Immutable snapshot of the current drive session, shown on the session screen. */
data class SessionStats(
    val startedAtMs: Long,
    val durationMs: Long,
    val maxSpeedKph: Float,
    val distanceKm: Float,
    val alertCounts: Map<AlertType, Int>,
    val totalAlerts: Int,
)

/**
 * Accumulates live drive statistics: duration, max speed, a distance proxy (integral of GPS speed),
 * and per-type alert counts. Updated each processed frame from [AdasController].
 */
class DriveSession(private val startedAtMs: Long) {
    private var maxSpeedKph = 0f
    private var distanceM = 0.0
    private var lastTickMs = startedAtMs
    private val counts = HashMap<AlertType, Int>()

    // Route polyline (downsampled) + event timeline for trip history.
    private val lats = ArrayList<Double>()
    private val lons = ArrayList<Double>()
    private var lastPointMs = 0L
    private val eventLog = ArrayList<TripEvent>()

    /** @param speedMps GPS speed (NaN if unknown). @param lat/lon NaN if no fix. */
    fun tick(nowMs: Long, speedMps: Float, lat: Double, lon: Double, events: List<AdasEvent>) {
        val dt = ((nowMs - lastTickMs).coerceAtLeast(0)) / 1000.0
        lastTickMs = nowMs
        if (!speedMps.isNaN()) {
            distanceM += speedMps * dt
            val kph = speedMps * 3.6f
            if (kph > maxSpeedKph) maxSpeedKph = kph
        }
        // Downsample the route to ~1 point/2s to keep trips small.
        if (!lat.isNaN() && !lon.isNaN() && nowMs - lastPointMs >= 2000L) {
            lats.add(lat); lons.add(lon); lastPointMs = nowMs
        }
        for (e in events) {
            counts[e.type] = (counts[e.type] ?: 0) + 1
            if (eventLog.size < 500) eventLog.add(TripEvent(e.timestampMs, e.type.name, e.severity.name, e.message))
        }
    }

    fun snapshot(nowMs: Long): SessionStats = SessionStats(
        startedAtMs = startedAtMs,
        durationMs = nowMs - startedAtMs,
        maxSpeedKph = maxSpeedKph,
        distanceKm = (distanceM / 1000.0).toFloat(),
        alertCounts = HashMap(counts),
        totalAlerts = counts.values.sum(),
    )

    val hasData: Boolean get() = eventLog.isNotEmpty() || lats.size > 1 || distanceM > 50.0

    fun toTrip(nowMs: Long): Trip = Trip(
        id = startedAtMs,
        startedAtMs = startedAtMs,
        durationMs = nowMs - startedAtMs,
        distanceKm = (distanceM / 1000.0).toFloat(),
        maxSpeedKph = maxSpeedKph,
        totalAlerts = counts.values.sum(),
        alertCounts = counts.mapKeys { it.key.name },
        lats = lats.toDoubleArray(),
        lons = lons.toDoubleArray(),
        events = ArrayList(eventLog),
    )
}
