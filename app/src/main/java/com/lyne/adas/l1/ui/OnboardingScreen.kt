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
    Step("Lyne", "Driver assistance that runs entirely on your phone. Forward-collision, pedestrian, tailgating, lane and speed-sign warnings. No cloud, no account, works offline."),
    Step("Warning-only", "Lyne never controls your vehicle. It is an extra pair of eyes, not a substitute for attention. Distances and time-to-collision are estimates and are shown with their uncertainty."),
    Step("Mount it well", "Landscape on the windshield, rear camera facing the road, lens clean and unobstructed. Lyne reads the mount's tilt from the gravity sensor and compensates automatically."),
    Step("Permissions", "Camera is required to see the road. Location is optional but strongly recommended: speed lets Lyne suppress alerts in slow traffic and time warnings correctly."),
    Step("Pick your vehicle", "In Calibrate, choose your vehicle type so Lyne knows the camera height. It then keeps refining that from everyday traffic, so accuracy improves as you drive."),
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
