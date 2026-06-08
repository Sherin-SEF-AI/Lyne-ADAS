package com.lyne.adas.l1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.pipeline.Trip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Trip history: a list of past drives, each opening a route map, stats, and an event timeline. */
@Composable
fun TripsScreen(trips: List<Trip>, onClose: () -> Unit) {
    val p = palette()
    var selected by remember { mutableStateOf<Trip?>(null) }

    Column(Modifier.fillMaxSize().background(p.background).padding(16.dp)) {
        ScreenHeader(if (selected == null) "TRIP HISTORY" else "TRIP", onClose)
        Spacer(Modifier.height(12.dp))

        val sel = selected
        if (sel == null) {
            if (trips.isEmpty()) {
                Text("No drives recorded yet. Finish a drive and it lands here.", color = p.textDim, fontSize = 13.sp)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trips.forEach { t -> TripRow(t) { selected = t } }
                }
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("BACK", p.accent) { selected = null }
                RouteMap(sel)
                StatRow("Distance", "%.1f km".format(sel.distanceKm))
                StatRow("Duration", fmtDur(sel.durationMs))
                StatRow("Top speed", "${sel.maxSpeedKph.toInt()} km/h")
                StatRow("Alerts", sel.totalAlerts.toString())
                if (sel.alertCounts.isNotEmpty()) {
                    SectionLabel("BY TYPE")
                    sel.alertCounts.entries.sortedByDescending { it.value }.forEach { StatRow(it.key, it.value.toString()) }
                }
                SectionLabel("TIMELINE")
                if (sel.events.isEmpty()) Text("No events.", color = p.textDim, fontSize = 12.sp)
                else sel.events.takeLast(80).reversed().forEach { e ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(e.message, color = sevColor(e.severity), fontSize = 12.sp)
                        Text(clock(e.tsMs), color = p.textDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(t: Trip, onClick: () -> Unit) {
    val p = palette()
    Column(
        Modifier.fillMaxWidth().background(p.surfaceAlt, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(12.dp),
    ) {
        Text(stamp(t.startedAtMs), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "%.1f km  ·  %s  ·  %d km/h  ·  %d alerts".format(t.distanceKm, fmtDur(t.durationMs), t.maxSpeedKph.toInt(), t.totalAlerts),
            color = p.textDim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun RouteMap(t: Trip) {
    val p = palette()
    if (t.lats.size < 2) {
        Text("No GPS route for this trip.", color = p.textDim, fontSize = 12.sp)
        return
    }
    val minLa = t.lats.min(); val maxLa = t.lats.max()
    val minLo = t.lons.min(); val maxLo = t.lons.max()
    val rangeLa = (maxLa - minLa).coerceAtLeast(1e-6)
    val rangeLo = (maxLo - minLo).coerceAtLeast(1e-6)
    Canvas(Modifier.fillMaxWidth().height(200.dp).background(p.surface, RoundedCornerShape(8.dp)).padding(10.dp)) {
        val pad = 8f
        fun px(i: Int): Offset {
            val x = pad + ((t.lons[i] - minLo) / rangeLo).toFloat() * (size.width - 2 * pad)
            val y = pad + (1f - ((t.lats[i] - minLa) / rangeLa).toFloat()) * (size.height - 2 * pad)
            return Offset(x, y)
        }
        var prev = px(0)
        for (i in 1 until t.lats.size) {
            val cur = px(i)
            drawLine(p.accent, prev, cur, strokeWidth = 4f * density, cap = StrokeCap.Round)
            prev = cur
        }
        drawCircle(p.ok, radius = 6f * density, center = px(0))
        drawCircle(p.critical, radius = 6f * density, center = px(t.lats.size - 1))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val p = palette()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = p.textDim, fontSize = 13.sp)
        Text(value, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun sevColor(sev: String) = when (sev) {
    "CRITICAL" -> palette().critical
    "CAUTION" -> palette().caution
    "INFO" -> palette().info
    else -> palette().textDim
}

private fun fmtDur(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, sec)
}

private fun stamp(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
