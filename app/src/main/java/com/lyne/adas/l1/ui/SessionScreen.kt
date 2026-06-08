package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.pipeline.AdasUiState

@Composable
fun SessionScreen(state: AdasUiState, onExport: () -> Unit, onClose: () -> Unit) {
    val p = palette()
    val s = state.session
    Column(Modifier.fillMaxSize().background(p.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("DRIVE SESSION", onClose)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("DURATION", s?.let { fmtDuration(it.durationMs) } ?: "00:00", Modifier.weight(1f))
            StatCard("DISTANCE", s?.let { "%.1f km".format(it.distanceKm) } ?: "0.0 km", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("MAX SPEED", s?.let { "${it.maxSpeedKph.toInt()} km/h" } ?: "-- km/h", Modifier.weight(1f))
            StatCard("ALERTS", s?.totalAlerts?.toString() ?: "0", Modifier.weight(1f))
        }

        s?.alertCounts?.takeIf { it.isNotEmpty() }?.let { counts ->
            SectionLabel("BY TYPE")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                counts.entries.sortedByDescending { it.value }.forEach { (type, n) ->
                    Text("${type.name} $n", color = p.text, fontSize = 12.sp,
                        modifier = Modifier.background(p.surfaceAlt, RoundedCornerShape(6.dp)).padding(8.dp, 5.dp))
                }
            }
        }

        SectionLabel("EVENT LOG")
        Box(Modifier.weight(1f).fillMaxWidth().background(p.surface, RoundedCornerShape(8.dp)).padding(8.dp)) {
            if (state.recentEvents.isEmpty()) {
                Text("No events yet. Warnings appear here as they fire.", color = p.textDim, fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.recentEvents.asReversed()) { e ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("●", color = p.severityColor(e.severity), fontSize = 12.sp)
                            Text(e.type.name, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(e.message, color = p.textDim, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        PillButton("EXPORT ROVIX JSON", p.accent, onExport)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    val p = palette()
    Column(modifier.background(p.surface, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text(label, color = p.textDim, fontSize = 10.sp)
        Text(value, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

private fun fmtDuration(ms: Long): String {
    val total = ms / 1000
    val m = total / 60; val s = total % 60
    return "%02d:%02d".format(m, s)
}
