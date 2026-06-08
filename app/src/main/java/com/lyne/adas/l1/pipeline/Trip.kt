package com.lyne.adas.l1.pipeline

/** A single recorded event marker within a trip (for the timeline). */
data class TripEvent(val tsMs: Long, val type: String, val severity: String, val message: String)

/** A completed drive: stats, GPS route polyline, and the event timeline. Persisted by TripStore. */
data class Trip(
    val id: Long,
    val startedAtMs: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val maxSpeedKph: Float,
    val totalAlerts: Int,
    val alertCounts: Map<String, Int>,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val events: List<TripEvent>,
)
