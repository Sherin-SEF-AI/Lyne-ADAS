package com.lyne.adas.l1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.calibration.VehiclePreset
import com.lyne.adas.l1.pipeline.AdasUiState
import kotlin.math.abs

/**
 * Guided calibration. Monocular range needs three numbers: where the horizon is (mount pitch/roll,
 * measured live from the gravity sensor), how high the camera sits above the road (vehicle preset or
 * slider; refined automatically from traffic), and the camera field of view (read from the device).
 * An optional known-gap fine-tune and the auto-calibration controls finish the flow.
 */
@Composable
fun CalibrationScreen(
    state: AdasUiState,
    onSetFov: (Float?) -> Unit,
    onSetScale: (Float) -> Unit,
    onSetHeight: (Float, VehiclePreset?) -> Unit,
    onSetAutoCal: (Boolean) -> Unit,
    onResetAutoCal: () -> Unit,
    onClose: () -> Unit,
) {
    val p = palette()
    val intr = state.intrinsics
    var step by remember { mutableIntStateOf(0) }
    val last = 4

    Column(Modifier.fillMaxSize().background(p.background).padding(horizontal = 20.dp, vertical = 14.dp)) {
        ScreenHeader("Calibration  ${step + 1}/${last + 1}", onClose)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                0 -> {
                    StepTitle("Mount the phone")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        LevelBubble(state.mountPitchDeg, state.mountRollDeg)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val pitch = state.mountPitchDeg
                            val roll = state.mountRollDeg
                            Reading("Tilt (down)", pitch?.let { "%.1f°".format(it) } ?: "--", pitch != null && pitch in -2f..12f)
                            Reading("Roll", "%.1f°".format(roll), abs(roll) < 3f)
                            Text(
                                "Landscape, rear lens unobstructed, mounted firmly near the top-centre of the windshield. " +
                                    "Aim slightly down so the road fills the lower half. Lyne measures tilt and roll from the gravity " +
                                    "sensor and compensates automatically; keep roll small for best accuracy.",
                                color = p.textDim, fontSize = 13.sp,
                            )
                        }
                    }
                }
                1 -> {
                    StepTitle("Camera height")
                    Text("Ground-plane ranging uses the camera's height above the road. Pick your vehicle, then fine-tune if you know the exact mount height.", color = p.textDim, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        VehiclePreset.entries.forEach { v -> Chip(v.label, state.vehiclePreset == v) { onSetHeight(v.cameraHeightM, v) } }
                    }
                    SliderRow("Mount height (m)", state.cameraHeightM, 0.8f, 3.0f) { onSetHeight(it, null) }
                    Text("Auto-calibration then refines it from passing cars (currently ×${"%.2f".format(state.fusion?.heightScale ?: 1f)}).", color = p.textDim, fontSize = 12.sp)
                }
                2 -> {
                    StepTitle("Field of view")
                    Text("Source: ${if (intr.fromDevice) "camera hardware (active array)" else "default; your phone didn't report it"}", color = p.textDim, fontSize = 12.sp)
                    Text("${"%.1f".format(intr.horizontalFovDeg)}°", color = p.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("–1°", false) { onSetFov(intr.horizontalFovDeg - 1f) }
                        Chip("+1°", false) { onSetFov(intr.horizontalFovDeg + 1f) }
                        Chip("RESET", false) { onSetFov(null) }
                    }
                    Text("Only change this if your phone uses a wide or zoom lens for the main camera.", color = p.textDim, fontSize = 12.sp)
                }
                3 -> {
                    StepTitle("Known-gap check (optional)")
                    Text("Park behind a car at a measured gap (e.g. 10 m, rear bumper to your front bumper + bonnet). Compare with the live reading and nudge.", color = p.textDim, fontSize = 13.sp)
                    val d = state.fusion?.leadDistanceM
                    Text(d?.let { "Lead now: ${"%.1f".format(it)} m" } ?: "No lead vehicle in view", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("–3%", false) { onSetScale(0.97f) }
                        Chip("+3%", false) { onSetScale(1.03f) }
                        Chip("RESET", false) { onSetScale(1.0f) }
                    }
                    Text("Correction: ×${"%.2f".format(state.distanceScale)}", color = p.textDim, fontSize = 12.sp)
                }
                else -> {
                    StepTitle("Auto-calibration")
                    SwitchRow(
                        "Learn from traffic",
                        "Compares ground-plane and size-based distances across many ordinary cars and slowly corrects the camera height. Fully on-device.",
                        state.autoCalEnabled, onSetAutoCal,
                    )
                    val conf = state.fusion?.autoCalConfidence ?: 0f
                    Text("Progress: ${(conf * 100).toInt()}%   height ×${"%.2f".format(state.fusion?.heightScale ?: 1f)}", color = p.text, fontSize = 14.sp)
                    PillButton("RESET LEARNED VALUES", p.caution, onResetAutoCal)
                    Text("Distances are estimates with an uncertainty shown as ±. Low-confidence estimates never raise a hard alert.", color = p.textDim, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) PillButton("BACK", p.textDim) { step-- } else Spacer(Modifier.width(1.dp))
            if (step < last) PillButton("NEXT", p.accent) { step++ } else PillButton("DONE", p.accent, onClose)
        }
    }
}

@Composable
private fun StepTitle(t: String) {
    Text(t, color = palette().text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Reading(label: String, value: String, ok: Boolean) {
    val p = palette()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = p.textDim, fontSize = 13.sp)
        Text(value, color = if (ok) p.ok else p.caution, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Spirit-level style indicator: bubble offset by roll (x) and pitch (y). Green when level enough. */
@Composable
private fun LevelBubble(pitchDeg: Float?, rollDeg: Float) {
    val p = palette()
    val ok = pitchDeg != null && pitchDeg in -2f..12f && abs(rollDeg) < 3f
    Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(130.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(p.surfaceAlt, r)
            drawCircle(p.line, r * 0.35f, c, style = Stroke(2f * density))
            drawLine(p.line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f * density)
            drawLine(p.line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f * density)
            // target: ~5 deg down sits at centre
            val bx = (rollDeg / 10f).coerceIn(-1f, 1f) * r * 0.8f
            val by = (((pitchDeg ?: 5f) - 5f) / 10f).coerceIn(-1f, 1f) * r * 0.8f
            drawCircle(if (ok) p.ok else p.caution, r * 0.16f, Offset(c.x + bx, c.y + by))
        }
    }
}
