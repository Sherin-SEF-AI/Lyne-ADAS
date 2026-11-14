package com.lyne.adas.l1

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.config.SettingsStore
import com.lyne.adas.l1.startup.CarConnectPolicy
import com.lyne.adas.l1.startup.CarConnectReceiver
import com.lyne.adas.l1.ui.BtDeviceChoice
import com.lyne.adas.l1.ui.CarReminderCard
import com.lyne.adas.l1.ui.DisclaimerScreen
import com.lyne.adas.l1.pipeline.AdasController
import com.lyne.adas.l1.ui.CalibrationScreen
import com.lyne.adas.l1.ui.HudScreen
import com.lyne.adas.l1.ui.OnboardingScreen
import com.lyne.adas.l1.ui.ClipsScreen
import com.lyne.adas.l1.ui.SessionScreen
import com.lyne.adas.l1.ui.SettingsScreen
import com.lyne.adas.l1.ui.TripsScreen
import com.lyne.adas.l1.ui.theme.LyneTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: AdasController
    private lateinit var previewView: PreviewView
    private val cameraGranted = mutableStateOf(false)
    private val onboarded = mutableStateOf(false)
    private val disclaimerOk = mutableStateOf(false)

    // "Remind me when my car connects" (Bluetooth) setting.
    private lateinit var settings: SettingsStore
    private val carReminder = mutableStateOf(false)
    private val carAddress = mutableStateOf<String?>(null)
    private val carStatus = mutableStateOf<String?>(null)

    private val carPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onCarPermissionsResult() }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            cameraGranted.value = grants[Manifest.permission.CAMERA] ?: hasCamera()
            maybeStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()
        controller = AdasController(this, this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        cameraGranted.value = hasCamera()
        onboarded.value = controller.isOnboarded()
        disclaimerOk.value = !controller.needsDisclaimer()
        settings = SettingsStore(this)
        syncCarReminder()

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
                    disclaimerAccepted = disclaimerOk.value,
                    onAcceptDisclaimer = { controller.acceptDisclaimer(); disclaimerOk.value = true },
                    onRequestPermissions = { requestPermissions() },
                    onFinish = {
                        controller.setOnboarded(); onboarded.value = true; maybeStart()
                    },
                )
                return@LyneTheme
            }

            // A changed safety disclaimer must be re-accepted before the HUD starts.
            if (!disclaimerOk.value) {
                DisclaimerScreen(onAccept = { controller.acceptDisclaimer(); disclaimerOk.value = true; maybeStart() })
                return@LyneTheme
            }

            when (screen) {
                Screen.HUD -> HudScreen(
                    state = state,
                    previewView = previewView,
                    onToggleMute = { controller.toggleMute() },
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
                    onShareAccuracyLog = { controller.shareAccuracyLog() },
                    onClose = { screen = Screen.HUD },
                    extraCards = {
                        CarReminderCard(
                            enabled = carReminder.value,
                            chosenAddress = carAddress.value,
                            status = carStatus.value,
                            pairedDevices = ::pairedDevices,
                            onToggle = ::setCarReminder,
                            onChoose = { d ->
                                settings.carDeviceAddress = d?.address; settings.carDeviceName = d?.name
                                carAddress.value = d?.address
                            },
                        )
                    },
                )
                Screen.CALIB -> CalibrationScreen(
                    state = state,
                    onSetFov = { controller.setFovOverride(it) },
                    onSetScale = { controller.setDistanceScale(it) },
                    onSetHeight = { h, preset -> controller.setCameraHeight(h, preset) },
                    onSetAutoCal = { controller.setAutoCal(it) },
                    onResetAutoCal = { controller.resetAutoCal() },
                    onStartRangeCheck = { controller.startRangeCheck(it) },
                    onShareAccuracy = { controller.shareAccuracyLog() },
                    onClose = { screen = Screen.HUD },
                )
                Screen.SESSION -> SessionScreen(
                    state = state,
                    onExport = { controller.shareRovix() },
                    onOpenTrips = { screen = Screen.TRIPS },
                    onOpenClips = { screen = Screen.CLIPS },
                    onClose = { screen = Screen.HUD },
                )
                Screen.TRIPS -> TripsScreen(
                    trips = remember(screen) { controller.recentTrips() },
                    onClose = { screen = Screen.SESSION },
                )
                Screen.CLIPS -> ClipsScreen(
                    clips = remember(screen) { controller.listClips() },
                    onPlay = { controller.playClip(it) },
                    onShare = { controller.shareClip(it) },
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

    /** Full-screen HUD: hide status/navigation bars; a swipe from the edge shows them briefly. */
    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onStart() { super.onStart(); syncCarReminder(); maybeStart() }
    override fun onStop() { super.onStop(); controller.stop() }
    override fun onDestroy() { super.onDestroy(); controller.release() }

    private fun maybeStart() {
        if (onboarded.value && disclaimerOk.value && cameraGranted.value) controller.start(previewView)
    }

    // --- Car Bluetooth reminder ---

    private fun missingCarPermissions(): List<String> = CarConnectReceiver.requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    /** Re-check on launch: permissions can be revoked from system settings at any time. */
    private fun syncCarReminder() {
        val wanted = settings.carReminder
        val btOk = CarConnectReceiver.hasBluetoothPermission(this)
        CarConnectReceiver.setEnabled(this, wanted && btOk)
        carReminder.value = wanted
        carAddress.value = settings.carDeviceAddress
        carStatus.value = when {
            !wanted -> null
            !btOk -> "Bluetooth permission is off, so Lyne cannot see your car connect. Allow \"Nearby devices\" for Lyne in system settings."
            !CarConnectReceiver.canPostNotifications(this) -> "Notifications are blocked for Lyne, so the reminder cannot appear. Allow them in system settings."
            else -> null
        }
    }

    private fun setCarReminder(on: Boolean) {
        if (!on) {
            settings.carReminder = false
            syncCarReminder()
            return
        }
        val missing = missingCarPermissions()
        if (missing.isEmpty()) enableCarReminder() else carPermLauncher.launch(missing.toTypedArray())
    }

    private fun onCarPermissionsResult() {
        when {
            !CarConnectReceiver.hasBluetoothPermission(this) -> {
                settings.carReminder = false; syncCarReminder()
                carStatus.value = "Without the Bluetooth (\"Nearby devices\") permission Lyne cannot tell when your car connects. Reminder left off."
            }
            !CarConnectReceiver.canPostNotifications(this) -> {
                settings.carReminder = false; syncCarReminder()
                carStatus.value = "Without notification permission the reminder cannot appear. Reminder left off."
            }
            else -> enableCarReminder()
        }
    }

    private fun enableCarReminder() {
        CarConnectReceiver.ensureChannel(this)
        settings.carReminder = true
        syncCarReminder()
    }

    /** Paired devices, car stereos / hands-free kits first. Empty without Bluetooth permission. */
    @SuppressLint("MissingPermission")
    private fun pairedDevices(): List<BtDeviceChoice> {
        if (!CarConnectReceiver.hasBluetoothPermission(this)) return emptyList()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .sortedWith(compareBy({ !CarConnectPolicy.isCarClass(it.bluetoothClass?.deviceClass) }, { it.name ?: it.address }))
                .map { BtDeviceChoice(it.name ?: it.address, it.address) }
        } catch (_: SecurityException) { emptyList() }
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private enum class Screen { HUD, SETTINGS, CALIB, SESSION, TRIPS, CLIPS }
}
