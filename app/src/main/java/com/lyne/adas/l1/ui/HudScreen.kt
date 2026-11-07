package com.lyne.adas.l1.ui

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.pipeline.AdasUiState

@Composable
fun HudScreen(
    state: AdasUiState,
    previewView: View,
    onToggleMute: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: () -> Unit,
    onCycleDayNight: () -> Unit,
) {
    val p = palette()
    val top = state.fusion?.topSeverity ?: Severity.NONE
    val transition = rememberInfiniteTransition(label = "hud")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "phase",
    )
    val pulse by transition.animateFloat(
        0.15f, 0.85f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "pulse",
    )
    val bannerColor by animateColorAsState(p.severityColor(top), label = "banner")

    Box(Modifier.fillMaxSize().background(p.background)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        HudOverlay(result = state.fusion, frameAspect = state.frameAspect, palette = p, phase = phase, modifier = Modifier.fillMaxSize())

        // Soft vignette so the HUD stays readable on bright scenes without hiding the road.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.22f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))

        if (top == Severity.CRITICAL) {
            Box(Modifier.fillMaxSize().border(7.dp, p.critical.copy(alpha = pulse)))
        }

        // Top bar.
        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { StatusStrip(state) }
        if (state.showPerf) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { state.perf?.let { PerfPanel(it) } }

        // Gauges: TTC + headway on the left, speed on the right.
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TtcRing(state.fusion?.ttcSec, state.fusion?.fcw ?: Severity.NONE)
            HeadwayRing(state.fusion?.headwaySec, state.fusion?.headway ?: Severity.NONE)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
            SpeedGauge(state.fusion?.speedKph, state.fusion?.speedLimitKph, state.fusion?.overspeed ?: Severity.NONE)
        }

        // Master alert banner, else the lead-vehicle readout.
        val msg = state.fusion?.topMessage
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
            if (top != Severity.NONE && msg != null) {
                Row(
                    Modifier.background(bannerColor, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = p.background, modifier = Modifier.size(26.dp))
                    Text("  $msg", color = p.background, fontWeight = FontWeight.Black, fontSize = if (top == Severity.CRITICAL) 26.sp else 20.sp)
                }
            } else LeadCard(state.fusion)
        }

        // Bottom action bar.
        ControlBar(
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter),
            onToggleMute = onToggleMute,
            onOpenCalibration = onOpenCalibration,
            onOpenSettings = onOpenSettings,
            onOpenSession = onOpenSession,
            onCycleDayNight = onCycleDayNight,
        )

        if (!state.cameraReady) {
            Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LYNE", color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("driver assistance", color = p.accent, fontSize = 12.sp)
                    Text(state.error ?: "Starting camera and models…", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
