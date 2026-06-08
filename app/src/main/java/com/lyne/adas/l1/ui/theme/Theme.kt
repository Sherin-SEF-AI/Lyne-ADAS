package com.lyne.adas.l1.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Rich-automotive palette with day & night variants. The HUD overlays a live camera feed, so both
 * variants keep dark chrome chips for legibility; they differ in text brightness, severity
 * saturation and accent. Colour is still "earned" by severity.
 */
data class LynePalette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val chip: Color,
    val text: Color,
    val textDim: Color,
    val line: Color,
    val accent: Color,
    val info: Color,
    val caution: Color,
    val critical: Color,
    val ok: Color,
    val laneNear: Color,
    val laneFar: Color,
)

val NightPalette = LynePalette(
    background = Color(0xFF07090C),
    surface = Color(0xFF12161B),
    surfaceAlt = Color(0xFF1B2128),
    chip = Color(0xCC0B0E12),
    text = Color(0xFFD7DEE6),
    textDim = Color(0xFF7E8893),
    line = Color(0xFF2A323B),
    accent = Color(0xFF3FC8E0),
    info = Color(0xFF54B9C9),
    caution = Color(0xFFFFB020),
    critical = Color(0xFFFF3B3B),
    ok = Color(0xFF38D27A),
    laneNear = Color(0xFF3CE07F),
    laneFar = Color(0xFF1F8F52),
)

val DayPalette = LynePalette(
    background = Color(0xFF0E1316),
    surface = Color(0xFF18202A),
    surfaceAlt = Color(0xFF222C38),
    chip = Color(0xE60A0F14),
    text = Color(0xFFFFFFFF),
    textDim = Color(0xFFB8C4D0),
    line = Color(0xFF3A4654),
    accent = Color(0xFF36D6F2),
    info = Color(0xFF5FE0F0),
    caution = Color(0xFFFFC233),
    critical = Color(0xFFFF4D4D),
    ok = Color(0xFF49F08C),
    laneNear = Color(0xFF55F58F),
    laneFar = Color(0xFF2BAE63),
)

val LocalLynePalette = staticCompositionLocalOf { NightPalette }

/** Backwards-compatible night constants for any non-palette-aware call site. */
object LyneColors {
    val Background = NightPalette.background
    val Surface = NightPalette.surface
    val SurfaceAlt = NightPalette.surfaceAlt
    val Text = NightPalette.text
    val TextDim = NightPalette.textDim
    val Line = NightPalette.line
    val Info = NightPalette.info
    val Caution = NightPalette.caution
    val Critical = NightPalette.critical
    val Ok = NightPalette.ok
    val LaneLine = NightPalette.info
    val EgoPath = Color(0x333FC8E0)
}

private val Mono = FontFamily.Monospace
private val LyneTypography = Typography(
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 34.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp),
)

@Composable
fun LyneTheme(night: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (night) NightPalette else DayPalette
    val scheme = darkColorScheme(
        primary = palette.text,
        background = palette.background,
        surface = palette.surface,
        onPrimary = palette.background,
        onBackground = palette.text,
        onSurface = palette.text,
        error = palette.critical,
    )
    CompositionLocalProvider(LocalLynePalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = LyneTypography, content = content)
    }
}
