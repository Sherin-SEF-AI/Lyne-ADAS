package com.lyne.adas.l1.pipeline

import android.content.Context
import com.lyne.adas.l1.logging.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists completed [Trip]s as JSON files under filesDir/trips and lists them newest-first. */
class TripStore(context: Context) {
    private val dir = File(context.filesDir, "trips").apply { mkdirs() }

    fun save(trip: Trip) {
        try {
            val o = JSONObject()
            o.put("id", trip.id); o.put("startedAtMs", trip.startedAtMs)
            o.put("durationMs", trip.durationMs); o.put("distanceKm", trip.distanceKm.toDouble())
            o.put("maxSpeedKph", trip.maxSpeedKph.toDouble()); o.put("totalAlerts", trip.totalAlerts)
            o.put("alertCounts", JSONObject(trip.alertCounts as Map<*, *>))
            o.put("lats", JSONArray().apply { trip.lats.forEach { put(it) } })
            o.put("lons", JSONArray().apply { trip.lons.forEach { put(it) } })
            o.put("events", JSONArray().apply {
                trip.events.forEach { e ->
                    put(JSONObject().put("ts", e.tsMs).put("type", e.type).put("sev", e.severity).put("msg", e.message))
                }
            })
            File(dir, "trip-${trip.id}.json").writeText(o.toString())
            prune()
        } catch (t: Throwable) { Log.w(TAG, "trip save failed", t) }
    }

    fun list(maxTrips: Int = 50): List<Trip> =
        (dir.listFiles { f -> f.name.startsWith("trip-") && f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .take(maxTrips)
            .mapNotNull { parse(it) }

    private fun parse(f: File): Trip? = try {
        val o = JSONObject(f.readText())
        val lats = o.getJSONArray("lats").let { DoubleArray(it.length()) { i -> it.getDouble(i) } }
        val lons = o.getJSONArray("lons").let { DoubleArray(it.length()) { i -> it.getDouble(i) } }
        val counts = HashMap<String, Int>()
        o.optJSONObject("alertCounts")?.let { jc -> jc.keys().forEach { k -> counts[k] = jc.getInt(k) } }
        val events = ArrayList<TripEvent>()
        o.optJSONArray("events")?.let { ja ->
            for (i in 0 until ja.length()) {
                val e = ja.getJSONObject(i)
                events.add(TripEvent(e.getLong("ts"), e.getString("type"), e.getString("sev"), e.getString("msg")))
            }
        }
        Trip(
            o.getLong("id"), o.getLong("startedAtMs"), o.getLong("durationMs"),
            o.getDouble("distanceKm").toFloat(), o.getDouble("maxSpeedKph").toFloat(),
            o.getInt("totalAlerts"), counts, lats, lons, events,
        )
    } catch (t: Throwable) { Log.w(TAG, "trip parse failed ${f.name}", t); null }

    private fun prune(maxTrips: Int = 50) {
        val files = (dir.listFiles { f -> f.name.startsWith("trip-") } ?: return).sortedByDescending { it.lastModified() }
        files.drop(maxTrips).forEach { it.delete() }
    }

    companion object { private const val TAG = "TripStore" }
}
