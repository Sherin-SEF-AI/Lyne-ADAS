package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.config.SafetyDisclaimer

/** The safety points plus an explicit "I understand" button (used inside onboarding and standalone). */
@Composable
fun SafetyDisclaimerBody(accepted: Boolean, onAccept: () -> Unit) {
    val p = palette()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SafetyDisclaimer.POINTS.forEach { point ->
            Row {
                Text("•  ", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(point, color = p.textDim, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.size(6.dp))
        if (accepted) Text("Accepted ✓", color = p.ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        else PillButton(SafetyDisclaimer.ACCEPT_LABEL, p.accent, onAccept)
    }
}

/** Standalone gate shown when the disclaimer text changed since the user last accepted it. */
@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    val p = palette()
    Column(
        Modifier.fillMaxSize().background(p.background).padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(SafetyDisclaimer.TITLE, color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Please read and accept before Lyne starts.", color = p.textDim, fontSize = 13.sp)
        SafetyDisclaimerBody(accepted = false, onAccept = onAccept)
    }
}
