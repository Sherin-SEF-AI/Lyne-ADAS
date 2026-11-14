package com.lyne.adas.l1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A paired Bluetooth device the user can pick as "my car". */
data class BtDeviceChoice(val name: String, val address: String)

/**
 * Settings card for "Remind me to start Lyne when my car connects". Enabling it asks for the
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
    Card("Start with car") {
        SwitchRow(
            "Remind me to start Lyne when my car connects",
            "Shows a notification when your car's Bluetooth connects; tap it to open Lyne. Android does not let apps open themselves.",
            enabled, onToggle,
        )
        if (status != null) Text(status, color = p.caution, fontSize = 12.sp)
        if (enabled) {
            val devices = remember(enabled) { pairedDevices() }
            Text("Which device is your car?", color = p.text, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Any car stereo / hands-free", chosenAddress == null) { onChoose(null) }
                devices.forEach { d -> Chip(d.name, d.address.equals(chosenAddress, ignoreCase = true)) { onChoose(d) } }
            }
        }
    }
}
