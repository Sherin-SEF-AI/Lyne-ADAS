package com.lyne.adas.l1.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.fusion.AlertType
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.ui.theme.LocalLynePalette
import com.lyne.adas.l1.ui.theme.LynePalette

// ------------------------------------------------------------------ design system
//
// One set of building blocks for every non-HUD screen: a top bar with a back arrow (and the system
// back gesture wired to it), filled / tonal / text buttons with a 48 dp touch target, selectable
// chips, list rows with leading icons, cards, stat tiles and empty states. Screens are landscape, so
// content is width-limited and centred instead of stretched edge to edge.

@Composable
fun palette(): LynePalette = LocalLynePalette.current

fun LynePalette.severityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> critical
    Severity.CAUTION -> caution
    Severity.INFO -> info
    Severity.NONE -> textDim
}

/** Driver-facing names for alert types (never the internal FCW / VRU / LDW codes). */
fun alertLabel(type: String): String = when (type) {
    AlertType.FCW.name -> "Collision"
    AlertType.VRU.name -> "Pedestrian / rider"
    AlertType.LDW.name -> "Lane departure"
    AlertType.HEADWAY.name -> "Too close"
    AlertType.OVERSPEED.name -> "Speeding"
    AlertType.STOP.name -> "Stop sign"
    AlertType.LEAD_GO.name -> "Traffic moving"
    AlertType.ANIMAL.name -> "Animal"
    else -> type.lowercase().replaceFirstChar { it.uppercase() }
}

private val MaxContentWidth = 760.dp

/**
 * Standard screen: top bar (back arrow + title + optional actions) over a centred, width-limited
 * body. The system back gesture does the same as the arrow.
 */
@Composable
fun LyneScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = palette()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", p.text, onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, color = p.textDim, fontSize = 12.sp)
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line.copy(alpha = 0.6f)))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = 20.dp), content = content)
        }
    }
}

/** Kept for older call sites: a back-arrow header. */
@Composable
fun ScreenHeader(title: String, onClose: () -> Unit) {
    val p = palette()
    BackHandler(onBack = onClose)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", p.text, onClose)
        Text(title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun IconButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp)) }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = palette().textDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp),
    )
}

/** Selectable chip (single-choice groups such as sensitivity or vehicle type). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = palette()
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) p.accent.copy(alpha = 0.18f) else p.surfaceAlt)
            .border(1.dp, if (selected) p.accent else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = p.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (selected) p.accent else p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

enum class ButtonStyle { PRIMARY, TONAL, TEXT, DANGER }

@Composable
fun LyneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val p = palette()
    val (bg, fg) = when (style) {
        ButtonStyle.PRIMARY -> p.accent to p.background
        ButtonStyle.TONAL -> p.surfaceAlt to p.text
        ButtonStyle.TEXT -> Color.Transparent to p.accent
        ButtonStyle.DANGER -> p.critical.copy(alpha = 0.16f) to p.critical
    }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) bg else p.surfaceAlt.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) fg else p.textDim
        if (icon != null) {
            Icon(icon, null, tint = c, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Older call sites: accent-coloured pills become primary buttons, anything else a tonal one.
 * Labels are shown in sentence case.
 */
@Composable
fun PillButton(text: String, color: Color, onClick: () -> Unit) {
    val p = palette()
    LyneButton(
        sentenceCase(text), onClick,
        style = when (color) { p.accent -> ButtonStyle.PRIMARY; p.critical, p.caution -> ButtonStyle.DANGER; else -> ButtonStyle.TONAL },
    )
}

private fun sentenceCase(s: String): String =
    if (s.any { it.isLowerCase() }) s else s.lowercase().replaceFirstChar { it.uppercase() }
        .replace("csv", "CSV").replace("jsonl", "JSONL")

/** Rounded surface grouping related rows. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val p = palette()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)
            .then(if (padding) Modifier.padding(vertical = 6.dp) else Modifier),
        content = content,
    )
}

/** Icon + title + supporting text + trailing slot; the whole row is the touch target. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = palette()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background((iconTint ?: p.accent).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = iconTint ?: p.accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = p.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(palette().line.copy(alpha = 0.5f)))
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null, valueColor: Color? = null) {
    val p = palette()
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(p.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = p.textDim, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(label, color = p.textDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, color = valueColor ?: p.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val p = palette()
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(p.surfaceAlt), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = p.textDim, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = p.textDim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 380.dp))
    }
}

/** Small coloured status pill ("On", "Off", "Recommended"). */
@Composable
fun StatusPill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
