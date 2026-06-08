package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.perf.PerfSnapshot
import com.lyne.adas.l1.pipeline.AdasUiState

@Composable
fun StatusStrip(state: AdasUiState) {
    val p = palette()
    Column(Modifier.background(p.chip, RoundedCornerShape(6.dp)).padding(8.dp, 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TIER ${state.tier.name}", color = p.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(state.perf?.backend?.name ?: "--", color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(if (state.usingStub) "STUB" else "LIVE", color = if (state.usingStub) p.caution else p.ok, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(state.sensitivity.name, color = p.textDim, fontSize = 11.sp)
        }
        Text(
            "intr ${if (state.intrinsics.fromDevice) "dev" else "def"} ${state.intrinsics.horizontalFovDeg.toInt()}°  " +
                (if (state.calibrated) "cal" else "uncal") + "  " + (if (state.isNight) "night" else "day"),
            color = p.textDim, fontSize = 10.sp,
        )
        if (state.usingStub) Text("object: no weights — drop .tflite", color = p.textDim, fontSize = 9.sp)
        else if (state.signStub) Text("TSR: no sign model (STOP via detector)", color = p.textDim, fontSize = 9.sp)
    }
}

@Composable
fun PerfPanel(perf: PerfSnapshot) {
    val p = palette()
    Column(
        Modifier.background(p.chip, RoundedCornerShape(6.dp)).padding(8.dp, 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text("${"%.1f".format(perf.fps)} FPS", color = p.ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("tot ${"%.0f".format(perf.totalMs)}ms", color = p.text, fontSize = 10.sp)
        Text("obj ${stage(perf, "object")} lane ${stage(perf, "lane")} sgn ${stage(perf, "sign")}", color = p.textDim, fontSize = 10.sp)
        Text("drop ${perf.droppedFrames}  heap ${perf.heapUsedMb}/${perf.heapMaxMb}MB", color = p.textDim, fontSize = 10.sp)
        Text("res ${perf.inputResolution}  cad x${perf.cadenceMultiplier}  th ${perf.thermal}", color = p.textDim, fontSize = 10.sp)
    }
}

private fun stage(p: PerfSnapshot, name: String): String = p.stageMs[name]?.let { "%.0f".format(it) } ?: "-"

@Composable
fun ControlBar(
    state: AdasUiState,
    modifier: Modifier = Modifier,
    onToggleMute: () -> Unit,
    onToggleInject: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: () -> Unit,
    onCycleDayNight: () -> Unit,
) {
    val p = palette()
    val dn = when (state.dayNightMode) { DayNightMode.AUTO -> "AUTO"; DayNightMode.DAY -> "DAY"; DayNightMode.NIGHT -> "NIGHT" }
    Row(modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(if (state.muted) "MUTED" else "SOUND", if (state.muted) p.caution else p.ok, onToggleMute)
        PillButton(if (state.debugInject) "INJECT•" else "INJECT", if (state.debugInject) p.caution else p.textDim, onToggleInject)
        PillButton(dn, p.accent, onCycleDayNight)
        PillButton("TRIP", p.text, onOpenSession)
        PillButton("CAL", p.text, onOpenCalibration)
        PillButton("SETUP", p.text, onOpenSettings)
    }
}
