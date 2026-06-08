package com.lyne.adas.l1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.pipeline.AdasController
import com.lyne.adas.l1.ui.CalibrationScreen
import com.lyne.adas.l1.ui.HudScreen
import com.lyne.adas.l1.ui.OnboardingScreen
import com.lyne.adas.l1.ui.SessionScreen
import com.lyne.adas.l1.ui.SettingsScreen
import com.lyne.adas.l1.ui.TripsScreen
import com.lyne.adas.l1.ui.theme.LyneTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: AdasController
    private lateinit var previewView: PreviewView
    private val cameraGranted = mutableStateOf(false)
    private val onboarded = mutableStateOf(false)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            cameraGranted.value = grants[Manifest.permission.CAMERA] ?: hasCamera()
            maybeStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AdasController(this, this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        cameraGranted.value = hasCamera()
        onboarded.value = controller.isOnboarded()

        setContent { Root() }

        if (!cameraGranted.value) requestPermissions()
    }

    @Composable
    private fun Root() {
        val state by controller.state.collectAsStateWithLifecycle()
        LyneTheme(night = state.isNight) {
            var screen by remember { mutableStateOf(Screen.HUD) }
            var config by remember { mutableStateOf(controller.currentConfig()) }

            // First-run onboarding gates everything until completed.
            if (!onboarded.value) {
                OnboardingScreen(
                    cameraGranted = cameraGranted.value,
                    onRequestPermissions = { requestPermissions() },
                    onFinish = {
                        controller.setOnboarded(); onboarded.value = true; maybeStart()
                    },
                )
                return@LyneTheme
            }

            when (screen) {
                Screen.HUD -> HudScreen(
                    state = state,
                    previewView = previewView,
                    onToggleMute = { controller.toggleMute() },
                    onToggleInject = {
                        config = config.copy(debugInjectSynthetic = !config.debugInjectSynthetic)
                        controller.applyConfig(config)
                    },
                    onOpenCalibration = { screen = Screen.CALIB },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenSession = { screen = Screen.SESSION },
                    onCycleDayNight = { controller.setDayNightMode(nextMode(state.dayNightMode)) },
                )
                Screen.SETTINGS -> SettingsScreen(
                    state = state,
                    config = config,
                    onApplyConfig = { config = it; controller.applyConfig(it) },
                    onSetSensitivity = { controller.setSensitivity(it); config = controller.currentConfig() },
                    onSetTier = { controller.setTierOverride(it) },
                    onSetDayNight = { controller.setDayNightMode(it) },
                    onTogglePerf = { controller.togglePerf() },
                    onClose = { screen = Screen.HUD },
                )
                Screen.CALIB -> CalibrationScreen(
                    state = state,
                    onSetFov = { controller.setFovOverride(it) },
                    onSetScale = { controller.setDistanceScale(it) },
                    onClose = { screen = Screen.HUD },
                )
                Screen.SESSION -> SessionScreen(
                    state = state,
                    onExport = { controller.shareRovix() },
                    onOpenTrips = { screen = Screen.TRIPS },
                    onClose = { screen = Screen.HUD },
                )
                Screen.TRIPS -> TripsScreen(
                    trips = remember(screen) { controller.recentTrips() },
                    onClose = { screen = Screen.SESSION },
                )
            }
        }
    }

    private fun nextMode(m: DayNightMode): DayNightMode = when (m) {
        DayNightMode.AUTO -> DayNightMode.DAY
        DayNightMode.DAY -> DayNightMode.NIGHT
        DayNightMode.NIGHT -> DayNightMode.AUTO
    }

    override fun onStart() { super.onStart(); maybeStart() }
    override fun onStop() { super.onStop(); controller.stop() }
    override fun onDestroy() { super.onDestroy(); controller.release() }

    private fun maybeStart() {
        if (onboarded.value && cameraGranted.value) controller.start(previewView)
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private enum class Screen { HUD, SETTINGS, CALIB, SESSION, TRIPS }
}
