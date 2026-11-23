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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.lyne.adas.l1.config.SafetyDisclaimer

private data class Step(val title: String, val body: String)

private const val DISCLAIMER_STEP = 1

private val STEPS = listOf(
    Step("Lyne", "Driver assistance that runs entirely on your phone. Forward-collision, pedestrian, tailgating, lane and speed-sign warnings. No cloud, no account, works offline."),
    // Safety disclaimer: must be explicitly accepted (see SafetyDisclaimer) before onboarding can continue.
    Step(SafetyDisclaimer.TITLE, "Lyne is an extra pair of eyes, not a substitute for attention. Distances and time-to-collision are estimates."),
    Step("Mount it well", "Landscape on the windshield, rear camera facing the road, lens clean and unobstructed. Lyne reads the mount's tilt from the gravity sensor and compensates automatically."),
    Step("Permissions", "Lyne needs the camera to see the road, and your location for speed. Without speed it can't tell oncoming or passing traffic from a real hazard, can't time warnings, and lane warnings stay off. Nothing leaves your phone."),
    Step("Pick your vehicle", "In Calibrate, choose your vehicle type so Lyne knows the camera height. It then keeps refining that from everyday traffic, so accuracy improves as you drive."),
)

@Composable
fun OnboardingScreen(
    cameraGranted: Boolean,
    locationGranted: Boolean,
    locationOn: Boolean,
    onTurnOnLocation: () -> Unit,
    disclaimerAccepted: Boolean,
    onAcceptDisclaimer: () -> Unit,
    onRequestPermissions: () -> Unit,
    onFinish: () -> Unit,
) {
    val p = palette()
    var step by remember { mutableIntStateOf(0) }
    val s = STEPS[step]
    val isPermStep = step == 3
    val isDisclaimerStep = step == DISCLAIMER_STEP

    Column(Modifier.fillMaxSize().background(p.background).padding(24.dp)) {
        androidx.compose.material3.Icon(
            androidx.compose.ui.res.painterResource(com.lyne.adas.l1.R.drawable.ic_lyne_lockup),
            contentDescription = "Lyne ADAS", tint = p.text,
            modifier = Modifier.padding(bottom = 16.dp).size(width = 133.dp, height = 40.dp),
        )
        // progress dots
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEPS.indices.forEach { i ->
                Box(Modifier.size(if (i == step) 10.dp else 7.dp).background(if (i <= step) p.accent else p.surfaceAlt, CircleShape))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(s.title, color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            Text(s.body, color = p.textDim, fontSize = 15.sp)
            if (isDisclaimerStep) {
                Spacer(Modifier.size(12.dp))
                SafetyDisclaimerBody(accepted = disclaimerAccepted, onAccept = onAcceptDisclaimer)
            }
            if (isPermStep) {
                Spacer(Modifier.size(16.dp))
                PermissionRow("Camera", "Required: sees the road", cameraGranted)
                PermissionRow("Location", "Recommended: your speed, from GPS", locationGranted)
                if (locationGranted) PermissionRow("Location switched on", "Phone's Location setting (quick settings)", locationOn)
                Spacer(Modifier.size(14.dp))
                when {
                    !cameraGranted || !locationGranted -> PillButton("GRANT PERMISSIONS", p.accent, onRequestPermissions)
                    !locationOn -> PillButton("TURN ON LOCATION", p.accent, onTurnOnLocation)
                    else -> Text("All set ✓", color = p.ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                if (cameraGranted && !locationGranted) {
                    Spacer(Modifier.size(8.dp))
                    Text("You can continue without location, but warnings will be limited.", color = p.textDim, fontSize = 12.sp)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) PillButton("BACK", p.textDim) { step-- } else Spacer(Modifier.size(1.dp))
            val canAdvance = (!isPermStep || cameraGranted) && (!isDisclaimerStep || disclaimerAccepted)
            val label = if (step == STEPS.lastIndex) "START" else "NEXT"
            PillButton(label, if (canAdvance) p.accent else p.textDim) {
                if (!canAdvance) return@PillButton
                if (step == STEPS.lastIndex) onFinish() else step++
            }
        }
    }
}

@Composable
private fun PermissionRow(name: String, why: String, ok: Boolean) {
    val p = palette()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (ok) p.ok else p.caution, CircleShape))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(why, color = p.textDim, fontSize = 12.sp)
        }
        Text(if (ok) "ON" else "OFF", color = if (ok) p.ok else p.caution, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
