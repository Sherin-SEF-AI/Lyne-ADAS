package com.lyne.adas.l1.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.lyne.adas.l1.fusion.Severity

/** Generic 270° arc gauge with a value sweep over a track, and centered content. */
@Composable
fun ArcGauge(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val animFrac by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "gaugeFrac")
    val animColor by animateColorAsState(color, label = "gaugeColor")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(118.dp)) {
            val sw = size.minDimension * 0.11f
            val inset = sw / 2f
            val arcSize = Size(size.width - sw, size.height - sw)
            val topLeft = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(animColor, 135f, 270f * animFrac, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun SpeedGauge(
    speedKph: Float?, limitKph: Int?, overspeed: Severity, modifier: Modifier = Modifier,
    /** Held/dead-reckoned through a GPS outage: shown dimmed with a "~". */
    estimated: Boolean = false,
) {
    val p = palette()
    val maxK = ((limitKph?.times(1.6f)) ?: 120f).coerceAtLeast(80f)
    val frac = (speedKph ?: 0f) / maxK
    val color = if (overspeed != Severity.NONE) p.severityColor(overspeed) else p.accent
    ArcGauge(frac, color, p.surfaceAlt, modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = speedKph?.let { if (estimated) "~${it.toInt()}" else it.toInt().toString() } ?: "--"
            Text(shown, color = if (estimated) p.textDim else p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                when { speedKph == null -> "no GPS"; estimated -> "km/h est"; else -> "km/h" },
                color = if (speedKph == null) p.caution else p.textDim, fontSize = 10.sp,
            )
            // Only when a speed-limit sign has been read: a permanent "LIM --" is just noise.
            if (limitKph != null) Text("LIMIT $limitKph", color = if (overspeed != Severity.NONE) color else p.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TtcRing(ttcSec: Float?, severity: Severity, modifier: Modifier = Modifier) {
    val p = palette()
    val frac = if (ttcSec == null) 0f else (1f - ttcSec / 5f).coerceIn(0f, 1f)
    val color = if (severity != Severity.NONE) p.severityColor(severity) else p.ok
    ArcGauge(frac, color, p.surfaceAlt, modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("IMPACT", color = p.textDim, fontSize = 9.sp, letterSpacing = 0.8.sp)
            Text(ttcSec?.let { fmt1(it) } ?: "--", color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("seconds", color = p.textDim, fontSize = 9.sp)
        }
    }
}

@Composable
fun HeadwayRing(headwaySec: Float?, severity: Severity, modifier: Modifier = Modifier) {
    val p = palette()
    val frac = if (headwaySec == null) 0f else (1f - headwaySec / 3f).coerceIn(0f, 1f)
    val color = if (severity != Severity.NONE) p.severityColor(severity) else p.ok
    ArcGauge(frac, color, p.surfaceAlt, modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GAP", color = p.textDim, fontSize = 9.sp, letterSpacing = 0.8.sp)
            Text(headwaySec?.let { fmt1(it) } ?: "--", color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("seconds", color = p.textDim, fontSize = 9.sp)
        }
    }
}

internal fun fmt1(v: Float): String = if (v.isNaN()) "--" else String.format("%.1f", v)
