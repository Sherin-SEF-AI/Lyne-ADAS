package com.lyne.adas.l1.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
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
    onFixLocation: () -> Unit = {},
) {
    val p = palette()
    val top = state.fusion?.topSeverity ?: Severity.NONE
    val transition = rememberInfiniteTransition(label = "hud")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val pulse by transition.animateFloat(
        0.15f, 0.85f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "pulse",
    )
    val bannerColor by animateColorAsState(p.severityColor(top), label = "banner")
    var lastTouch by remember { mutableLongStateOf(System.nanoTime()) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(lastTouch) { controlsVisible = true; delay(CONTROLS_HIDE_MS); controlsVisible = false }

    Box(Modifier.fillMaxSize().background(p.background)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        HudOverlay(result = state.fusion, frameAspect = state.frameAspect, palette = p, phase = phase, modifier = Modifier.fillMaxSize(), showHorizon = state.showPerf)

        // Tapping the road brings the controls back (they fade out so the view stays clear).
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { lastTouch = System.nanoTime() } })

        // Soft vignette so the HUD stays readable on bright scenes without hiding the road.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.22f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))

        if (top == Severity.CRITICAL) {
            Box(Modifier.fillMaxSize().border(7.dp, p.critical.copy(alpha = pulse)))
        }

        // Top bar.
        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { StatusStrip(state) }
        if (state.showPerf) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { state.perf?.let { PerfPanel(it) } }

        // Gauges: TTC + headway on the left, speed on the right.
        // Only while there is a vehicle ahead: empty "--" rings are just clutter.
        val hasLead = state.fusion?.leadDistanceM != null
        AnimatedVisibility(
            visible = hasLead || (state.fusion?.fcw ?: Severity.NONE) != Severity.NONE,
            enter = fadeIn(tween(250)), exit = fadeOut(tween(600)),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TtcRing(state.fusion?.ttcSec, state.fusion?.fcw ?: Severity.NONE)
                HeadwayRing(state.fusion?.headwaySec, state.fusion?.headway ?: Severity.NONE)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
            SpeedGauge(
                state.fusion?.speedKph, state.fusion?.speedLimitKph, state.fusion?.overspeed ?: Severity.NONE,
                estimated = state.speedEstimated,
            )
        }

        // Master alert banner, else the lead-vehicle readout.
        val msg = state.fusion?.topMessage
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (top != Severity.NONE && msg != null) {
                Row(
                    Modifier.background(bannerColor, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = p.background, modifier = Modifier.size(26.dp))
                    Text("  $msg", color = p.background, fontWeight = FontWeight.Black, fontSize = if (top == Severity.CRITICAL) 26.sp else 20.sp)
                }
            } else LeadCard(state.fusion)
            // Camera self-check (blocked lens / bad mount) sits under the master alert.
            CameraHealthBanner(state.cameraHealth)
            if (state.muted) MutedNote()
        }

        // Phone battery / heat guidance, out of the driver's main sight line.
        DeviceHintChips(state.deviceHints, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp), onFixLocation)

        // Bottom action bar: fades out after a few seconds so it doesn't sit over the road.
        AnimatedVisibility(
            visible = controlsVisible || !state.cameraReady,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 },
            exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ControlBar(
                state = state,
                onToggleMute = { lastTouch = System.nanoTime(); onToggleMute() },
                onOpenCalibration = onOpenCalibration,
                onOpenSettings = onOpenSettings,
                onOpenSession = onOpenSession,
                onCycleDayNight = { lastTouch = System.nanoTime(); onCycleDayNight() },
            )
        }

        if (!state.cameraReady) {
            Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(com.lyne.adas.l1.R.drawable.ic_lyne_lockup),
                        contentDescription = "Lyne ADAS", tint = p.text,
                        modifier = Modifier.size(width = 200.dp, height = 60.dp),
                    )
                    if (state.error == null) androidx.compose.material3.CircularProgressIndicator(
                        color = p.accent, trackColor = p.surfaceAlt, strokeWidth = 3.dp,
                        modifier = Modifier.padding(top = 24.dp).size(28.dp),
                    )
                    Text(state.error ?: "Starting camera…", color = p.textDim, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }
}

private const val CONTROLS_HIDE_MS = 6000L
