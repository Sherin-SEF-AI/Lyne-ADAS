package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Step(val title: String, val body: String)

private val STEPS = listOf(
    Step("LYNE ADAS", "On-device driver assistance for your phone. Forward-collision, lane-departure, pedestrian, headway and speed-sign warnings — all running locally, no cloud."),
    Step("Warning-only", "Lyne never controls your vehicle. It is an aid, not a substitute for attention. Distances and time-to-collision are estimates. Keep your eyes on the road."),
    Step("Mount the phone", "Place the phone landscape on the windshield/dash, rear camera facing the road, lens unobstructed and roughly at eye level. A stable mount matters for accuracy."),
    Step("Permissions", "Camera is required to see the road. Location is optional and powers speed-based warnings (headway, overspeed)."),
    Step("Calibrate", "Monocular distance depends on your camera's field of view. You can fine-tune it anytime from the CAL screen against a known gap."),
)

@Composable
fun OnboardingScreen(cameraGranted: Boolean, onRequestPermissions: () -> Unit, onFinish: () -> Unit) {
    val p = palette()
    var step by remember { mutableIntStateOf(0) }
    val s = STEPS[step]
    val isPermStep = step == 3

    Column(Modifier.fillMaxSize().background(p.background).padding(24.dp)) {
        // progress dots
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEPS.indices.forEach { i ->
                Box(Modifier.size(if (i == step) 10.dp else 7.dp).background(if (i <= step) p.accent else p.surfaceAlt, CircleShape))
            }
        }
        Spacer(Modifier.weight(0.3f))
        Text(s.title, color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(12.dp))
        Text(s.body, color = p.textDim, fontSize = 15.sp)
        if (isPermStep) {
            Spacer(Modifier.size(16.dp))
            if (cameraGranted) Text("Camera granted ✓", color = p.ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            else PillButton("GRANT PERMISSIONS", p.accent, onRequestPermissions)
        }
        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) PillButton("BACK", p.textDim) { step-- } else Spacer(Modifier.size(1.dp))
            val canAdvance = !isPermStep || cameraGranted
            val label = if (step == STEPS.lastIndex) "START" else "NEXT"
            PillButton(label, if (canAdvance) p.accent else p.textDim) {
                if (!canAdvance) return@PillButton
                if (step == STEPS.lastIndex) onFinish() else step++
            }
        }
    }
}
