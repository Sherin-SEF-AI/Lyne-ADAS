package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.pipeline.AdasUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** This drive: headline stats, warnings by type, the live event log, and links to history and clips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(state: AdasUiState, onExport: () -> Unit, onOpenTrips: () -> Unit, onOpenClips: () -> Unit, onClose: () -> Unit) {
    val p = palette()
    val s = state.session
    LyneScaffold(
        "This drive", onClose,
        actions = { IconButton(Icons.Filled.IosShare, "Export events", p.text, onExport) },
    ) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left: stats and navigation.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Duration", s?.let { fmtDuration(it.durationMs) } ?: "0:00", Modifier.weight(1f), Icons.Filled.Schedule)
                    StatTile("Distance", s?.let { "%.1f km".format(it.distanceKm) } ?: "0.0 km", Modifier.weight(1f), Icons.Filled.Route)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Top speed", s?.let { if (it.maxSpeedKph > 0f) "${it.maxSpeedKph.toInt()} km/h" else "—" } ?: "—", Modifier.weight(1f), Icons.Filled.Speed)
                    StatTile("Warnings", s?.totalAlerts?.toString() ?: "0", Modifier.weight(1f), Icons.Filled.Warning)
                }
                s?.alertCounts?.takeIf { it.isNotEmpty() }?.let { counts ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        counts.entries.sortedByDescending { it.value }.forEach { (type, n) -> StatusPill("${alertLabel(type.name)} · $n", p.textDim) }
                    }
                }
                SurfaceCard {
                    ListRow("Trip history", "Past drives with route and warnings", Icons.Filled.History, onClick = onOpenTrips) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.textDim)
                    }
                    RowDivider()
                    ListRow("Dashcam clips", "Saved around critical warnings", Icons.Filled.Movie, onClick = onOpenClips) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.textDim)
                    }
                }
            }
            // Right: event log.
            SurfaceCard(Modifier.weight(1f), padding = false) {
                Text("Warnings", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
                if (state.recentEvents.isEmpty()) {
                    EmptyState(Icons.Filled.NotificationsNone, "All quiet", "Warnings from this drive will appear here.")
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                        state.recentEvents.asReversed().forEach { e ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(p.severityColor(e.severity), CircleShape))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(alertLabel(e.type.name), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(e.message.lowercase().replaceFirstChar { it.uppercase() }, color = p.textDim, fontSize = 12.sp)
                                }
                                Text(clock(e.timestampMs), color = p.textDim, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

private fun fmtDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
