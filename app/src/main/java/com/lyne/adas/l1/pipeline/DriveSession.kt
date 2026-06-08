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

    /** @param speedMps current GPS speed (NaN if unknown). */
    fun tick(nowMs: Long, speedMps: Float, events: List<AdasEvent>) {
        val dt = ((nowMs - lastTickMs).coerceAtLeast(0)) / 1000.0
        lastTickMs = nowMs
        if (!speedMps.isNaN()) {
            distanceM += speedMps * dt
            val kph = speedMps * 3.6f
            if (kph > maxSpeedKph) maxSpeedKph = kph
        }
        for (e in events) counts[e.type] = (counts[e.type] ?: 0) + 1
    }

    fun snapshot(nowMs: Long): SessionStats = SessionStats(
        startedAtMs = startedAtMs,
        durationMs = nowMs - startedAtMs,
        maxSpeedKph = maxSpeedKph,
        distanceKm = (distanceM / 1000.0).toFloat(),
        alertCounts = HashMap(counts),
        totalAlerts = counts.values.sum(),
    )
}
