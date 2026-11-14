package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.alerts.MutePolicy
import com.lyne.adas.l1.camera.CameraHealth
import com.lyne.adas.l1.perf.DeviceHint

/** Camera self-check banner: "CAMERA BLOCKED" (red, warnings paused) or "CHECK PHONE MOUNT" (amber). */
@Composable
fun CameraHealthBanner(health: CameraHealth, modifier: Modifier = Modifier) {
    val title = health.title ?: return
    val p = palette()
    val bg = if (health.blocked) p.critical else p.caution
    Row(
        modifier.widthIn(max = 520.dp).background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (health.blocked) Icons.Filled.VideocamOff else Icons.Filled.ScreenRotation,
            contentDescription = null, tint = p.background, modifier = Modifier.size(24.dp),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = p.background, fontSize = 18.sp, fontWeight = FontWeight.Black)
            health.detail?.let { Text(it, color = p.background, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Small, non-intrusive phone-condition chips (battery / heat). */
@Composable
fun DeviceHintChips(hints: List<DeviceHint>, modifier: Modifier = Modifier) {
    if (hints.isEmpty()) return
    val p = palette()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        hints.forEach { h ->
            Row(
                Modifier.widthIn(max = 200.dp).background(p.chip, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (h == DeviceHint.PHONE_HOT) Icons.Filled.DeviceThermostat else Icons.Filled.BatteryAlert,
                    contentDescription = null, tint = p.caution, modifier = Modifier.size(16.dp),
                )
                Text(h.text, color = p.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Shown while muted, so the driver knows mute does not switch off critical warnings. */
@Composable
fun MutedNote(modifier: Modifier = Modifier) {
    val p = palette()
    Text(
        "MUTED · ${MutePolicy.MUTE_NOTE.lowercase()}",
        color = p.caution, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.background(p.chip, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
