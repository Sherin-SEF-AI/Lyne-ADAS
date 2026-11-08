package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.config.DeviceTier
import com.lyne.adas.l1.config.SensitivityPreset
import com.lyne.adas.l1.pipeline.AdasUiState

@Composable
fun SettingsScreen(
    state: AdasUiState,
    config: AdasConfig,
    onApplyConfig: (AdasConfig) -> Unit,
    onSetSensitivity: (SensitivityPreset) -> Unit,
    onSetTier: (DeviceTier?) -> Unit,
    onSetDayNight: (DayNightMode) -> Unit,
    onTogglePerf: () -> Unit,
    onShareAccuracyLog: () -> Unit,
    onClose: () -> Unit,
) {
    val p = palette()
    var showAdvanced by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(p.background).padding(horizontal = 20.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("Settings", onClose)

        Card("Warnings") {
            Text("Sensitivity", color = p.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensitivityPreset.entries.forEach { s -> Chip(s.label(), state.sensitivity == s) { onSetSensitivity(s) } }
            }
            Text(state.sensitivity.blurb(), color = p.textDim, fontSize = 12.sp)
            SwitchRow("Voice alerts", "Short spoken cues: \"Brake\", \"Pedestrian\", \"Speed limit 60\"", config.voiceAlerts) {
                onApplyConfig(config.copy(voiceAlerts = it))
            }
        }

        Card("Display") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayNightMode.entries.forEach { m -> Chip(m.name.lowercase().replaceFirstChar { it.uppercase() }, state.dayNightMode == m) { onSetDayNight(m) } }
            }
            SwitchRow("Performance overlay", "FPS, latency and memory in the corner", state.showPerf) { onTogglePerf() }
        }

        Card("Recording & data") {
            SwitchRow("Event dashcam", "Keeps a clip around every critical warning", config.dashcamEnabled) {
                onApplyConfig(config.copy(dashcamEnabled = it))
            }
            SwitchRow("Accuracy log (CSV)", "Per-frame lead distance, uncertainty, TTC and speed, for comparing against radar or a tape measure", config.accuracyLog) {
                onApplyConfig(config.copy(accuracyLog = it))
            }
            if (config.accuracyLog) PillButton("SHARE LATEST CSV", p.accent, onShareAccuracyLog)
            SwitchRow("Event sidecar (JSONL)", "One line per warning, exportable from the Trip screen", config.emitRovixSidecar) {
                onApplyConfig(config.copy(emitRovixSidecar = it))
            }
        }

        Card("Video test (bench)") {
            SwitchRow(
                "Video test mode",
                "Point the phone at driving footage on a screen. Uses an assumed speed instead of GPS, fixed geometry, " +
                    "and freezes auto-calibration. Warnings and TTC are meaningful; absolute distances are not (unknown video camera).",
                config.videoTestMode,
            ) { onApplyConfig(config.copy(videoTestMode = it)) }
            if (config.videoTestMode) {
                Text("Assumed speed", color = p.text, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 50, 80, 110).forEach { k ->
                        Chip("$k km/h", config.videoTestSpeedKph == k) { onApplyConfig(config.copy(videoTestSpeedKph = k)) }
                    }
                }
            }
        }

        Text(
            if (showAdvanced) "▾ Advanced" else "▸ Advanced",
            color = p.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clickable { showAdvanced = !showAdvanced }.padding(vertical = 6.dp),
        )
        if (showAdvanced) {
            Card("Thresholds") {
                SliderRow("Collision caution (TTC s)", config.ttcCautionSec, 1.5f, 4f) { onApplyConfig(config.copy(ttcCautionSec = it)) }
                SliderRow("Collision critical (TTC s)", config.ttcCriticalSec, 0.8f, 2.5f) { onApplyConfig(config.copy(ttcCriticalSec = it)) }
                SliderRow("Tailgating warn (headway s)", config.headwayWarnSec, 0.5f, 2.5f) { onApplyConfig(config.copy(headwayWarnSec = it)) }
                SliderRow("Detection confidence", config.objectScoreThreshold, 0.25f, 0.7f) { onApplyConfig(config.copy(objectScoreThreshold = it)) }
            }
            Card("Device tier (rebuilds the pipeline)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("AUTO", false) { onSetTier(null) }
                    DeviceTier.entries.forEach { t -> Chip(t.name, state.tier == t) { onSetTier(t) } }
                }
                Text(state.probeSummary, color = p.textDim, fontSize = 11.sp)
            }
            Card("Developer") {
                SwitchRow("Synthetic demo scenario", "Animated test vehicle/lane/sign for trying the HUD indoors", config.debugInjectSynthetic) {
                    onApplyConfig(config.copy(debugInjectSynthetic = it))
                }
            }
        }
        Text(
            "Lyne is a warning-only aid. It never controls the vehicle; distances and TTC are estimates.",
            color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

private fun SensitivityPreset.label() = when (this) {
    SensitivityPreset.CALM -> "Calm"; SensitivityPreset.NORMAL -> "Normal"; SensitivityPreset.AGGRESSIVE -> "Alert"
}

private fun SensitivityPreset.blurb() = when (this) {
    SensitivityPreset.CALM -> "Earlier warnings, more evidence required before sounding. Fewest nuisance alerts."
    SensitivityPreset.NORMAL -> "Balanced timing for everyday driving."
    SensitivityPreset.AGGRESSIVE -> "Reacts faster to weak evidence. Expect more caution chirps."
}

@Composable
fun Card(title: String, content: @Composable () -> Unit) {
    val p = palette()
    Column(
        Modifier.fillMaxWidth().widthIn(max = 720.dp).background(p.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun SwitchRow(label: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    val p = palette()
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.text, fontSize = 14.sp)
            if (hint != null) Text(hint, color = p.textDim, fontSize = 11.sp)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.background, uncheckedTrackColor = p.surfaceAlt),
        )
    }
}

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, format: String = "%.2f", onCommit: (Float) -> Unit) {
    val p = palette()
    var live by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = p.text, fontSize = 13.sp)
            Text(format.format(live), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = live, onValueChange = { live = it }, valueRange = min..max,
            onValueChangeFinished = { onCommit(live) },
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceAlt),
        )
    }
}
