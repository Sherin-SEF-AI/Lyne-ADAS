package com.lyne.adas.l1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.pipeline.Trip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Trip history: a list of past drives, each opening a route map, stats, and a warning timeline. */
@Composable
fun TripsScreen(trips: List<Trip>, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<Trip?>(null) }
    val sel = selected
    if (sel == null) {
        LyneScaffold("Trip history", onClose, subtitle = if (trips.isEmpty()) null else "${trips.size} drives") {
            if (trips.isEmpty()) {
                EmptyState(Icons.Filled.DirectionsCar, "No drives yet", "Each drive is saved here when you close Lyne: route, distance and the warnings you got.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                    SurfaceCard {
                        trips.forEachIndexed { i, t ->
                            if (i > 0) RowDivider()
                            ListRow(
                                stamp(t.startedAtMs),
                                "%.1f km  ·  %s  ·  %s".format(t.distanceKm, fmtDur(t.durationMs), warnings(t.totalAlerts)),
                                Icons.Filled.Route,
                                iconTint = if (t.totalAlerts > 0) palette().caution else palette().ok,
                                onClick = { selected = t },
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = palette().textDim) }
                        }
                    }
                }
            }
        }
    } else {
        LyneScaffold(stamp(sel.startedAtMs), { selected = null }, subtitle = "Trip details") { TripDetail(sel) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripDetail(t: Trip) {
    val p = palette()
    Row(Modifier.fillMaxSize().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RouteMap(t)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Distance", "%.1f km".format(t.distanceKm), Modifier.weight(1f), Icons.Filled.Route)
                StatTile("Duration", fmtDur(t.durationMs), Modifier.weight(1f), Icons.Filled.Schedule)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Top speed", if (t.maxSpeedKph > 0f) "${t.maxSpeedKph.toInt()} km/h" else "—", Modifier.weight(1f), Icons.Filled.Speed)
                StatTile("Warnings", t.totalAlerts.toString(), Modifier.weight(1f), Icons.Filled.Warning)
            }
            if (t.alertCounts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.alertCounts.entries.sortedByDescending { it.value }.forEach { StatusPill("${alertLabel(it.key)} · ${it.value}", p.textDim) }
            }
        }
        SurfaceCard(Modifier.weight(1f), padding = false) {
            Text("Timeline", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
            if (t.events.isEmpty()) EmptyState(Icons.Filled.NotificationsNone, "No warnings", "A calm drive.")
            else Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                t.events.takeLast(80).reversed().forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(sevColor(e.severity), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(alertLabel(e.type), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(e.message.lowercase().replaceFirstChar { it.uppercase() }, color = p.textDim, fontSize = 12.sp)
                        }
                        Text(clock(e.tsMs), color = p.textDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteMap(t: Trip) {
    val p = palette()
    val minLa = t.lats.minOrNull() ?: 0.0; val maxLa = t.lats.maxOrNull() ?: 0.0
    val minLo = t.lons.minOrNull() ?: 0.0; val maxLo = t.lons.maxOrNull() ?: 0.0
    // ~50 m: below that the "route" is GPS jitter around a parked car, not a drive worth drawing.
    val tooSmall = maxOf(maxLa - minLa, maxLo - minLo) < 0.00045
    if (t.lats.size < 2 || tooSmall) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(18.dp)).background(p.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (t.lats.size < 2) Icons.Filled.LocationOff else Icons.Filled.Route, null, tint = p.textDim)
                Text(
                    if (t.lats.size < 2) "No route: location was off" else "Route too short to show",
                    color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }
    val rangeLa = maxLa - minLa
    val rangeLo = maxLo - minLo
    Canvas(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp)).background(p.surface).padding(14.dp)) {
        // Keep the route's aspect ratio (a north-south drive shouldn't be stretched sideways).
        val scale = minOf(size.width / rangeLo.toFloat(), size.height / rangeLa.toFloat())
        val ox = (size.width - rangeLo.toFloat() * scale) / 2f
        val oy = (size.height - rangeLa.toFloat() * scale) / 2f
        fun px(i: Int) = Offset(ox + ((t.lons[i] - minLo).toFloat() * scale), oy + ((maxLa - t.lats[i]).toFloat() * scale))
        var prev = px(0)
        for (i in 1 until t.lats.size) {
            val cur = px(i)
            drawLine(p.accent, prev, cur, strokeWidth = 4f * density, cap = StrokeCap.Round)
            prev = cur
        }
        drawCircle(p.background, radius = 8f * density, center = px(0)); drawCircle(p.ok, radius = 6f * density, center = px(0))
        val end = px(t.lats.size - 1)
        drawCircle(p.background, radius = 8f * density, center = end); drawCircle(p.critical, radius = 6f * density, center = end)
    }
}

@Composable
private fun sevColor(sev: String) = when (sev) {
    "CRITICAL" -> palette().critical
    "CAUTION" -> palette().caution
    "INFO" -> palette().info
    else -> palette().textDim
}

private fun warnings(n: Int) = when (n) { 0 -> "no warnings"; 1 -> "1 warning"; else -> "$n warnings" }

private fun fmtDur(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, sec)
}

private fun stamp(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
