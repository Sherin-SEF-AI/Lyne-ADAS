package com.lyne.adas.l1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.ui.theme.LocalLynePalette
import com.lyne.adas.l1.ui.theme.LynePalette

@Composable
fun palette(): LynePalette = LocalLynePalette.current

fun LynePalette.severityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> critical
    Severity.CAUTION -> caution
    Severity.INFO -> info
    Severity.NONE -> textDim
}

@Composable
fun ScreenHeader(title: String, onClose: () -> Unit) {
    val p = palette()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "CLOSE", color = p.accent, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, color = palette().textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = palette()
    Text(
        text, color = if (selected) p.background else p.text,
        fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(if (selected) p.accent else p.surfaceAlt, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun PillButton(text: String, color: Color, onClick: () -> Unit) {
    val p = palette()
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(p.chip, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
