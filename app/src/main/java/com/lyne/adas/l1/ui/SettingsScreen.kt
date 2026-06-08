package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onClose: () -> Unit,
) {
    val p = palette()
    Column(
        Modifier.fillMaxSize().background(p.background).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("SETUP", onClose)

        SectionLabel("SENSITIVITY")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensitivityPreset.entries.forEach { s ->
                Chip(s.name, state.sensitivity == s) { onSetSensitivity(s) }
            }
        }

        SectionLabel("DAY / NIGHT")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayNightMode.entries.forEach { m -> Chip(m.name, state.dayNightMode == m) { onSetDayNight(m) } }
        }

        SectionLabel("DEVICE TIER (rebuilds pipeline)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("AUTO", false) { onSetTier(null) }
            DeviceTier.entries.forEach { t -> Chip(t.name, state.tier == t) { onSetTier(t) } }
        }
        Text(state.probeSummary, color = p.textDim, fontSize = 10.sp)

        SectionLabel("THRESHOLDS")
        SliderRow("TTC caution", config.ttcCautionSec, 1f, 4f) { onApplyConfig(config.copy(ttcCautionSec = it)) }
        SliderRow("TTC critical", config.ttcCriticalSec, 0.8f, 3f) { onApplyConfig(config.copy(ttcCriticalSec = it)) }
        SliderRow("Headway warn", config.headwayWarnSec, 0.5f, 2.5f) { onApplyConfig(config.copy(headwayWarnSec = it)) }
        SliderRow("Object score", config.objectScoreThreshold, 0.2f, 0.7f) { onApplyConfig(config.copy(objectScoreThreshold = it)) }
        SliderRow("Ego-path width", config.egoPathHalfWidthFrac, 0.15f, 0.45f) { onApplyConfig(config.copy(egoPathHalfWidthFrac = it)) }

        SectionLabel("TOGGLES")
        Toggle("Inject synthetic scenario", config.debugInjectSynthetic) { onApplyConfig(config.copy(debugInjectSynthetic = it)) }
        Toggle("Perf overlay", state.showPerf) { onTogglePerf() }
        Toggle("ROVIX sidecar logging", config.emitRovixSidecar) { onApplyConfig(config.copy(emitRovixSidecar = it)) }
        Toggle("Dashcam (off by default)", config.dashcamEnabled) { onApplyConfig(config.copy(dashcamEnabled = it)) }

        Text(
            "Settings persist across restarts. Presets scale all FCW/headway thresholds at once.",
            color = p.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onCommit: (Float) -> Unit) {
    val p = palette()
    var live by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = p.text, fontSize = 13.sp)
            Text("%.2f".format(live), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = live, onValueChange = { live = it }, valueRange = min..max,
            onValueChangeFinished = { onCommit(live) },
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceAlt),
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val p = palette()
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = p.text, fontSize = 13.sp)
        Text(
            if (value) "ON" else "OFF",
            color = if (value) p.ok else p.textDim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
}
