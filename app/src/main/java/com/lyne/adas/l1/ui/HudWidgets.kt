package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.perf.PerfSnapshot
import com.lyne.adas.l1.pipeline.AdasUiState

/** Compact system status: live/tier dot, backend, and calibration health in plain words. */
@Composable
fun StatusStrip(state: AdasUiState) {
    val p = palette()
    val live = !state.usingStub
    val auto = state.fusion?.autoCalConfidence ?: 0f
    val mountOk = state.mountPitchDeg != null && state.mountConvergence > 0.3f
    Column(Modifier.background(p.chip, RoundedCornerShape(10.dp)).padding(10.dp, 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(if (live) p.ok else p.caution, CircleShape))
            Text(if (live) "LIVE" else "DEMO", color = p.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("· TIER ${state.tier.name} · ${state.perf?.backend?.name ?: "--"}", color = p.textDim, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (mountOk) "MOUNT ✓" else "MOUNT…",
                color = if (mountOk) p.ok else p.caution, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                if (auto >= 1f) "AUTO-CAL ✓" else "AUTO-CAL ${(auto * 100).toInt()}%",
                color = if (auto >= 1f) p.ok else p.textDim, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
            Text("H ${"%.2f".format(state.cameraHeightM * (state.fusion?.heightScale ?: 1f))}m", color = p.textDim, fontSize = 10.sp)
        }
    }
}

/** Lead vehicle readout: distance with honest uncertainty and relative (closing) speed. */
@Composable
fun LeadCard(f: FusionResult?) {
    val p = palette()
    val d = f?.leadDistanceM ?: return
    val sigma = f.leadSigmaM
    val closing = f.leadClosingMps
    val color = when (f.fcw) { Severity.CRITICAL -> p.critical; Severity.CAUTION -> p.caution; else -> p.text }
    Row(
        Modifier.background(p.chip, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("LEAD", color = p.textDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("${d.toInt()}", color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(" m", color = p.textDim, fontSize = 12.sp)
        if (sigma != null && sigma >= 1f) Text("  ±${sigma.toInt()}", color = p.textDim, fontSize = 12.sp)
        if (closing != null && kotlin.math.abs(closing) >= 0.6f) {
            Spacer(Modifier.width(12.dp))
            val kph = (closing * 3.6f).toInt()
            Text(
                if (closing > 0) "▼ $kph km/h" else "▲ ${-kph} km/h",
                color = if (closing > 0) p.caution else p.ok, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun PerfPanel(perf: PerfSnapshot) {
    val p = palette()
    Column(
        Modifier.background(p.chip, RoundedCornerShape(10.dp)).padding(10.dp, 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text("${"%.1f".format(perf.fps)} FPS", color = p.ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("tot ${"%.0f".format(perf.totalMs)}ms  obj ${stage(perf, "object")} lane ${stage(perf, "lane")}", color = p.textDim, fontSize = 10.sp)
        Text("drop ${perf.droppedFrames}  heap ${perf.heapUsedMb}/${perf.heapMaxMb}MB  th ${perf.thermal}", color = p.textDim, fontSize = 10.sp)
    }
}

private fun stage(p: PerfSnapshot, name: String): String = p.stageMs[name]?.let { "%.0f".format(it) } ?: "-"

@Composable
fun ControlBar(
    state: AdasUiState,
    modifier: Modifier = Modifier,
    onToggleMute: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: () -> Unit,
    onCycleDayNight: () -> Unit,
) {
    val p = palette()
    val dnIcon = when (state.dayNightMode) {
        DayNightMode.AUTO -> Icons.Filled.BrightnessAuto
        DayNightMode.DAY -> Icons.Filled.LightMode
        DayNightMode.NIGHT -> Icons.Filled.Brightness4
    }
    Row(
        modifier.padding(10.dp).background(p.chip, RoundedCornerShape(28.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconAction(if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (state.muted) "Unmute" else "Mute",
            if (state.muted) p.caution else p.text, onToggleMute)
        IconAction(dnIcon, "Day / night", p.text, onCycleDayNight)
        IconAction(Icons.Filled.Timeline, "Trip", p.text, onOpenSession)
        IconAction(Icons.Filled.Straighten, "Calibrate", if (state.calibrated) p.text else p.caution, onOpenCalibration)
        IconAction(Icons.Filled.Settings, "Settings", p.text, onOpenSettings)
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp)) }
}
