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
    onToggleInject: () -> Unit,
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

        if (top == Severity.CRITICAL) {
            Box(Modifier.fillMaxSize().border(7.dp, p.critical.copy(alpha = pulse)))
        }

        // Top bar.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusStrip(state)
            if (state.showPerf) state.perf?.let { PerfPanel(it) }
        }

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

        // Master alert banner.
        val msg = state.fusion?.topMessage
        if (top != Severity.NONE && msg != null) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 54.dp)
                    .background(bannerColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 18.dp, vertical = 7.dp),
            ) {
                Text(msg, color = p.background, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }

        // Bottom action bar.
        ControlBar(
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter),
            onToggleMute = onToggleMute,
            onToggleInject = onToggleInject,
            onOpenCalibration = onOpenCalibration,
            onOpenSettings = onOpenSettings,
            onOpenSession = onOpenSession,
            onCycleDayNight = onCycleDayNight,
        )

        if (!state.cameraReady) {
            Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LYNE ADAS", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(state.error ?: "INITIALIZING…", color = p.textDim, fontSize = 13.sp)
                }
            }
        }
    }
}
