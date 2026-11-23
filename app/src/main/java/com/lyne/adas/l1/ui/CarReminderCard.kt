package com.lyne.adas.l1.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A paired Bluetooth device the user can pick as "my car". */
data class BtDeviceChoice(val name: String, val address: String)

/**
 * Settings section for "Remind me to start Lyne when my car connects". Enabling it asks for the
 * Bluetooth/notification permissions it needs; [status] explains anything that blocks it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarReminderCard(
    enabled: Boolean,
    chosenAddress: String?,
    status: String?,
    pairedDevices: () -> List<BtDeviceChoice>,
    onToggle: (Boolean) -> Unit,
    onChoose: (BtDeviceChoice?) -> Unit,
) {
    val p = palette()
    SectionLabel("Start with car")
    SurfaceCard {
        SwitchRow(
            "Remind me when my car connects",
            "A notification when your car's Bluetooth connects; tap it to open Lyne",
            enabled, Icons.Filled.DirectionsCar, onToggle,
        )
        if (status != null) Text(status, color = p.caution, fontSize = 12.sp, modifier = Modifier.padding(start = 66.dp, end = 16.dp, bottom = 10.dp))
        AnimatedVisibility(enabled) {
            val devices = remember(enabled) { pairedDevices() }
            Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 14.dp)) {
                Text("Which device is your car?", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Any car stereo", chosenAddress == null) { onChoose(null) }
                    devices.forEach { d -> Chip(d.name, d.address.equals(chosenAddress, ignoreCase = true)) { onChoose(d) } }
                }
            }
        }
    }
}
