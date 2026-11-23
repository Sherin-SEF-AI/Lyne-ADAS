package com.lyne.adas.l1.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.R
import com.lyne.adas.l1.calibration.VehiclePreset
import com.lyne.adas.l1.config.SafetyDisclaimer

private enum class Step { WELCOME, SAFETY, MOUNT, PERMISSIONS, VEHICLE }

/**
 * First-run setup, laid out for a landscape phone: an illustration panel on the left, the step on
 * the right, and a single primary action. Safety must be accepted and camera granted to continue;
 * location is recommended but can be skipped.
 */
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
    vehicle: VehiclePreset? = null,
    onPickVehicle: (VehiclePreset) -> Unit = {},
) {
    val p = palette()
    var index by rememberSaveable { mutableIntStateOf(0) }
    val steps = Step.entries
    val step = steps[index]
    BackHandler(enabled = index > 0) { index-- }

    Row(Modifier.fillMaxSize().background(p.background)) {
        // Illustration panel.
        Box(
            Modifier.weight(0.42f).fillMaxHeight()
                .background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.22f), p.background))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_lyne_lockup), "Lyne", tint = p.text,
                modifier = Modifier.align(Alignment.TopStart).padding(28.dp).size(width = 120.dp, height = 36.dp))
            AnimatedContent(step, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "art") { s ->
                Box(Modifier.size(150.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) {
                    Icon(s.icon(), null, tint = p.accent, modifier = Modifier.size(72.dp))
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.indices.forEach { i ->
                    val w by animateDpAsState(if (i == index) 24.dp else 8.dp, label = "dot")
                    Box(Modifier.height(8.dp).width(w).clip(RoundedCornerShape(4.dp)).background(if (i <= index) p.accent else p.surfaceAlt))
                }
            }
        }

        // Step content.
        Column(Modifier.weight(0.58f).fillMaxHeight().padding(horizontal = 32.dp, vertical = 20.dp)) {
            AnimatedContent(
                step,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(260))) togetherWith fadeOut(tween(120))
                },
                modifier = Modifier.weight(1f), label = "step",
            ) { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text("Step ${s.ordinal + 1} of ${steps.size}", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(s.title(), color = p.text, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(s.body(), color = p.textDim, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(14.dp))
                    when (s) {
                        Step.WELCOME -> Features()
                        Step.SAFETY -> Safety(disclaimerAccepted, onAcceptDisclaimer)
                        Step.MOUNT -> MountTips()
                        Step.PERMISSIONS -> Permissions(cameraGranted, locationGranted, locationOn, onRequestPermissions, onTurnOnLocation)
                        Step.VEHICLE -> Vehicles(vehicle, onPickVehicle)
                    }
                }
            }
            val canAdvance = when (step) {
                Step.SAFETY -> disclaimerAccepted
                Step.PERMISSIONS -> cameraGranted
                else -> true
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) LyneButton("Back", { index-- }, style = ButtonStyle.TEXT)
                Spacer(Modifier.weight(1f))
                val last = index == steps.lastIndex
                LyneButton(
                    if (last) "Start driving" else "Continue",
                    { if (last) onFinish() else index++ },
                    icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = canAdvance,
                )
            }
        }
    }
}

private fun Step.icon(): ImageVector = when (this) {
    Step.WELCOME -> Icons.Filled.VerifiedUser
    Step.SAFETY -> Icons.Filled.Shield
    Step.MOUNT -> Icons.Filled.PhoneAndroid
    Step.PERMISSIONS -> Icons.Filled.LocationOn
    Step.VEHICLE -> Icons.Filled.DirectionsCar
}

private fun Step.title() = when (this) {
    Step.WELCOME -> "Your second pair of eyes"
    Step.SAFETY -> SafetyDisclaimer.TITLE
    Step.MOUNT -> "Mount your phone"
    Step.PERMISSIONS -> "Camera and location"
    Step.VEHICLE -> "What do you drive?"
}

private fun Step.body() = when (this) {
    Step.WELCOME -> "Lyne watches the road through your phone's camera and warns you about collisions, pedestrians, riders and lane drift. It runs entirely on your phone: no account, no cloud, works offline."
    Step.SAFETY -> "Lyne is a driver aid, not an autopilot. Please read and accept these points."
    Step.MOUNT -> "A good mount makes warnings accurate. Lyne reads the phone's tilt from its sensors and corrects for it automatically."
    Step.PERMISSIONS -> "Lyne needs the camera to see the road and your location for speed. Without speed it can't tell oncoming or passing traffic from a real hazard. Nothing leaves your phone."
    Step.VEHICLE -> "Lyne uses the camera's height above the road to judge distance. You can change this later in Calibration, and Lyne keeps refining it as you drive."
}

@Composable
private fun Features() {
    val items = listOf(
        Icons.Filled.DirectionsCar to "Forward collision and tailgating warnings",
        Icons.Filled.VerifiedUser to "Pedestrians, riders and animals in your path",
        Icons.Filled.MyLocation to "Lane departure and speed-limit reminders",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { items.forEach { (ic, t) -> CompactRow(ic, t) } }
}

@Composable
private fun Safety(accepted: Boolean, onAccept: () -> Unit) {
    val p = palette()
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SafetyDisclaimer.POINTS.forEach { point ->
                Row {
                    Icon(Icons.Filled.CheckCircle, null, tint = p.accent, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(point, color = p.text, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    if (accepted) StatusPill("Accepted", p.ok) else LyneButton("I understand", onAccept, style = ButtonStyle.TONAL, icon = Icons.Filled.CheckCircle)
}

@Composable
private fun MountTips() {
    val tips = listOf(
        "Landscape, rear camera facing the road",
        "High and centred on the windshield, clear of the wipers' blind spot",
        "Lens clean and not blocked by the dashboard",
        "Plugged in, and out of direct sun if you can: phones slow down when hot",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { tips.forEach { CompactRow(Icons.Filled.CheckCircle, it) } }
}

@Composable
private fun Permissions(
    cameraGranted: Boolean,
    locationGranted: Boolean,
    locationOn: Boolean,
    onRequest: () -> Unit,
    onTurnOnLocation: () -> Unit,
) {
    val p = palette()
    SurfaceCard {
        ListRow("Camera", "Required: sees the road", Icons.Filled.CameraAlt) { OnOff(cameraGranted, required = true) }
        RowDivider()
        ListRow("Location", "Recommended: your speed, from GPS", Icons.Filled.LocationOn) { OnOff(locationGranted) }
        if (locationGranted) {
            RowDivider()
            ListRow("Location switched on", "The Location setting in quick settings", Icons.Filled.MyLocation) { OnOff(locationOn) }
        }
    }
    Spacer(Modifier.height(14.dp))
    when {
        !cameraGranted || !locationGranted -> LyneButton("Allow access", onRequest, style = ButtonStyle.TONAL)
        !locationOn -> LyneButton("Turn on location", onTurnOnLocation, style = ButtonStyle.TONAL, icon = Icons.Filled.MyLocation)
        else -> StatusPill("All set", p.ok)
    }
    if (cameraGranted && !locationGranted) {
        Text("You can continue without location, but warnings will be limited.", color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun OnOff(ok: Boolean, required: Boolean = false) {
    val p = palette()
    StatusPill(if (ok) "Allowed" else if (required) "Needed" else "Off", if (ok) p.ok else p.caution)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Vehicles(selected: VehiclePreset?, onPick: (VehiclePreset) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VehiclePreset.entries.forEach { v -> Chip(v.label, selected == v) { onPick(v) } }
    }
}

@Composable
private fun CompactRow(icon: ImageVector, text: String) {
    val p = palette()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = p.accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = p.text, fontSize = 14.sp)
    }
}
