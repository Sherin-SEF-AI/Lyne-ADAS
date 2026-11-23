package com.lyne.adas.l1.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.BuildConfig
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.config.DeviceTier
import com.lyne.adas.l1.config.SafetyDisclaimer
import com.lyne.adas.l1.config.SensitivityPreset
import com.lyne.adas.l1.logging.CrashReporter
import com.lyne.adas.l1.pipeline.AdasUiState

@OptIn(ExperimentalLayoutApi::class)
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
    onOpenCalibration: () -> Unit = {},
    onOpenClips: () -> Unit = {},
    /** Extra cards supplied by the host (e.g. the car Bluetooth reminder). */
    extraCards: @Composable () -> Unit = {},
) {
    val p = palette()
    var showDev by rememberSaveable { mutableStateOf(false) }
    var showSafety by rememberSaveable { mutableStateOf(false) }

    LyneScaffold("Settings", onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            SectionLabel("Warnings")
            SurfaceCard {
                ListRow("Sensitivity", state.sensitivity.blurb(), Icons.Filled.Tune)
                FlowRow(
                    Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SensitivityPreset.entries.forEach { s -> Chip(s.label(), state.sensitivity == s) { onSetSensitivity(s) } }
                }
                RowDivider()
                SwitchRow("Voice alerts", "Short spoken cues such as \"Brake\" and \"Pedestrian\"", config.voiceAlerts, Icons.AutoMirrored.Filled.VolumeUp) {
                    onApplyConfig(config.copy(voiceAlerts = it))
                }
            }

            SectionLabel("Display")
            SurfaceCard {
                ListRow("Theme", "Auto follows the light outside", Icons.Filled.DarkMode)
                FlowRow(
                    Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DayNightMode.entries.forEach { m -> Chip(m.label(), state.dayNightMode == m) { onSetDayNight(m) } }
                }
            }

            SectionLabel("Camera")
            SurfaceCard {
                ListRow(
                    "Calibration", if (state.calibrated) "Mount, camera height and accuracy check" else "Not set up yet: pick your vehicle for accurate distances",
                    Icons.Filled.Straighten, iconTint = if (state.calibrated) p.accent else p.caution, onClick = onOpenCalibration,
                ) { Chevron() }
                RowDivider()
                SwitchRow("Event dashcam", "Saves a short clip around every critical warning", config.dashcamEnabled, Icons.Filled.Videocam) {
                    onApplyConfig(config.copy(dashcamEnabled = it))
                }
                RowDivider()
                ListRow("Saved clips", "Play or share dashcam clips", Icons.Filled.Movie, onClick = onOpenClips) { Chevron() }
            }

            extraCards()

            SectionLabel("About")
            SurfaceCard {
                ListRow("Safety information", "Lyne warns; it never controls the car", Icons.Filled.Shield, onClick = { showSafety = !showSafety }) {
                    Icon(if (showSafety) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = p.textDim)
                }
                AnimatedVisibility(showSafety) {
                    Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SafetyDisclaimer.POINTS.forEach { Text("•  $it", color = p.textDim, fontSize = 13.sp, lineHeight = 18.sp) }
                    }
                }
                RowDivider()
                val uri = androidx.compose.ui.platform.LocalUriHandler.current
                ListRow("Privacy policy", "Everything runs on this phone. No account, no uploads.", Icons.Filled.Lock,
                    onClick = { runCatching { uri.openUri(PRIVACY_URL) } }) { Chevron() }
                RowDivider()
                ListRow("Version", "Lyne ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Icons.Filled.Info)
            }

            SectionLabel("Advanced")
            SurfaceCard {
                ListRow("Developer options", "Diagnostics, logs, bench testing and thresholds", Icons.Filled.Code, onClick = { showDev = !showDev }) {
                    Icon(if (showDev) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = p.textDim)
                }
                AnimatedVisibility(showDev) { DeveloperOptions(state, config, onApplyConfig, onSetTier, onTogglePerf, onShareAccuracyLog) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeveloperOptions(
    state: AdasUiState,
    config: AdasConfig,
    onApplyConfig: (AdasConfig) -> Unit,
    onSetTier: (DeviceTier?) -> Unit,
    onTogglePerf: () -> Unit,
    onShareAccuracyLog: () -> Unit,
) {
    val p = palette()
    val ctx = LocalContext.current
    Column {
        RowDivider()
        SwitchRow("Performance overlay", "FPS, latency, memory and thermal state on the driving screen", state.showPerf, Icons.Filled.Speed) { onTogglePerf() }
        RowDivider()
        SwitchRow(
            "Video test mode",
            "For pointing the phone at driving footage on a screen: assumes a fixed speed and freezes auto-calibration",
            config.videoTestMode, Icons.Filled.Movie,
        ) { onApplyConfig(config.copy(videoTestMode = it)) }
        if (config.videoTestMode) {
            FlowRow(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 50, 80, 110).forEach { k -> Chip("$k km/h", config.videoTestSpeedKph == k) { onApplyConfig(config.copy(videoTestSpeedKph = k)) } }
            }
        }
        RowDivider()
        SwitchRow("Accuracy log (CSV)", "Per-frame lead distance, uncertainty, TTC and speed", config.accuracyLog, Icons.Filled.BugReport) {
            onApplyConfig(config.copy(accuracyLog = it))
        }
        if (config.accuracyLog) Column(Modifier.padding(start = 66.dp, bottom = 12.dp)) {
            LyneButton("Share latest CSV", onShareAccuracyLog, style = ButtonStyle.TONAL)
        }
        RowDivider()
        SwitchRow("Event sidecar (JSONL)", "One line per warning, exportable from the Trip screen", config.emitRovixSidecar, Icons.Filled.Code) {
            onApplyConfig(config.copy(emitRovixSidecar = it))
        }
        RowDivider()
        SwitchRow("Synthetic demo", "Animated test vehicle, lane and sign for trying the display indoors", config.debugInjectSynthetic, Icons.Filled.Movie) {
            onApplyConfig(config.copy(debugInjectSynthetic = it))
        }
        val crashes = remember { CrashReporter.list(ctx).size }
        if (crashes > 0) {
            RowDivider()
            ListRow("Crash reports", "$crashes saved on this phone", Icons.Filled.BugReport, iconTint = p.caution, onClick = { CrashReporter.share(ctx) }) {
                Text("Share", color = p.accent, fontWeight = FontWeight.SemiBold)
            }
        }
        RowDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Thresholds", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            SliderRow("Collision caution (TTC s)", config.ttcCautionSec, 1.5f, 4f) { onApplyConfig(config.copy(ttcCautionSec = it)) }
            SliderRow("Collision critical (TTC s)", config.ttcCriticalSec, 0.8f, 2.5f) { onApplyConfig(config.copy(ttcCriticalSec = it)) }
            SliderRow("Tailgating warning (headway s)", config.headwayWarnSec, 0.5f, 2.5f) { onApplyConfig(config.copy(headwayWarnSec = it)) }
            SliderRow("Detection confidence", config.objectScoreThreshold, 0.25f, 0.7f) { onApplyConfig(config.copy(objectScoreThreshold = it)) }
            Spacer(Modifier.height(8.dp))
            Text("Device tier (rebuilds the pipeline)", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Auto", false) { onSetTier(null) }
                DeviceTier.entries.forEach { t -> Chip(t.name, state.tier == t) { onSetTier(t) } }
            }
            Text(state.probeSummary, color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = palette().textDim)

private fun SensitivityPreset.label() = when (this) {
    SensitivityPreset.CALM -> "Calm"; SensitivityPreset.NORMAL -> "Normal"; SensitivityPreset.AGGRESSIVE -> "Alert"
}

private fun SensitivityPreset.blurb() = when (this) {
    SensitivityPreset.CALM -> "Fewest nuisance alerts: needs more evidence before sounding"
    SensitivityPreset.NORMAL -> "Balanced timing for everyday driving"
    SensitivityPreset.AGGRESSIVE -> "Reacts to weaker evidence: expect more caution chimes"
}

private fun DayNightMode.label() = when (this) {
    DayNightMode.AUTO -> "Auto"; DayNightMode.DAY -> "Day"; DayNightMode.NIGHT -> "Night"
}

/** Section card with a title, for host-supplied cards (e.g. the car reminder). */
@Composable
fun Card(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    SurfaceCard { Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
fun SwitchRow(label: String, hint: String?, value: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    val p = palette()
    ListRow(label, hint, icon, onClick = { onChange(!value) }) {
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = p.accent, checkedThumbColor = p.background,
                uncheckedTrackColor = p.surfaceAlt, uncheckedThumbColor = p.textDim, uncheckedBorderColor = p.line,
            ),
        )
    }
}

@Composable
fun SwitchRow(label: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit) = SwitchRow(label, hint, value, null, onChange)

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, format: String = "%.2f", onCommit: (Float) -> Unit) {
    val p = palette()
    var live by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = p.textDim, fontSize = 13.sp)
            Text(format.format(live), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = live, onValueChange = { live = it }, valueRange = min..max,
            onValueChangeFinished = { onCommit(live) },
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceAlt),
        )
    }
}

private const val PRIVACY_URL = "https://sherin-sef-ai.github.io/Lyne-ADAS/privacy.html"
