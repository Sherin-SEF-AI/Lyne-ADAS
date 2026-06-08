package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.pipeline.AdasUiState

/**
 * Guided calibration wizard. The effective horizontal FoV dominates monocular distance, so we let
 * the driver nudge it (and an empirical distance scale) against a known gap. Values persist per
 * device via CalibrationStore (wired in the controller).
 */
@Composable
fun CalibrationScreen(
    state: AdasUiState,
    onSetFov: (Float?) -> Unit,
    onSetScale: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val p = palette()
    val intr = state.intrinsics
    var step by remember { mutableIntStateOf(0) }
    val last = 3

    Column(Modifier.fillMaxSize().background(p.background).padding(16.dp)) {
        ScreenHeader("CALIBRATION  ${step + 1}/${last + 1}", onClose)
        Spacer(Modifier.height(12.dp))

        when (step) {
            0 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1 · Mount", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Mount the phone landscape on the windshield, rear lens unobstructed and level. A stable mount is essential — distance accuracy depends on it.", color = p.textDim, fontSize = 14.sp)
            }
            1 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2 · Field of view", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Source: ${if (intr.fromDevice) "CameraCharacteristics" else "DEFAULT (reduced accuracy)"}", color = p.textDim, fontSize = 12.sp)
                Text("${"%.1f".format(intr.horizontalFovDeg)}°", color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("–1°", false) { onSetFov(intr.horizontalFovDeg - 1f) }
                    Chip("+1°", false) { onSetFov(intr.horizontalFovDeg + 1f) }
                    Chip("RESET", false) { onSetFov(null) }
                }
            }
            2 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3 · Distance scale", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Park behind a vehicle at a known gap (e.g. 10 m). On the HUD, adjust until the LEAD distance matches reality.", color = p.textDim, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("–5%", false) { onSetScale(0.95f) }
                    Chip("+5%", false) { onSetScale(1.05f) }
                    Chip("RESET", false) { onSetScale(1.0f) }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Done", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (state.calibrated) "Calibration saved for this device." else "Using defaults — distance remains an estimate.",
                    color = if (state.calibrated) p.ok else p.caution, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                Text("Distance and TTC are always estimates; low-confidence values never raise a hard alert.", color = p.textDim, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) PillButton("BACK", p.textDim) { step-- } else Spacer(Modifier.width(1.dp))
            if (step < last) PillButton("NEXT", p.accent) { step++ } else PillButton("FINISH", p.accent, onClose)
        }
    }
}
