package com.lyne.adas.l1.pipeline

import android.content.Context
import android.content.Intent
import android.util.Size
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import com.lyne.adas.l1.alerts.AlertManager
import com.lyne.adas.l1.calibration.CalibrationStore
import com.lyne.adas.l1.calibration.CameraIntrinsics
import com.lyne.adas.l1.calibration.IntrinsicsReader
import com.lyne.adas.l1.camera.CameraController
import com.lyne.adas.l1.camera.FrameAnalyzer
import com.lyne.adas.l1.camera.ImageBuffers
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.DayNightMode
import com.lyne.adas.l1.config.DeviceTier
import com.lyne.adas.l1.config.SensitivityPreset
import com.lyne.adas.l1.config.SettingsStore
import com.lyne.adas.l1.fusion.AdasEvent
import com.lyne.adas.l1.fusion.FusionEngine
import com.lyne.adas.l1.inference.DetectorProvider
import com.lyne.adas.l1.inference.DetectorSet
import com.lyne.adas.l1.inference.InferenceScheduler
import com.lyne.adas.l1.inference.Perception
import com.lyne.adas.l1.logging.Log
import com.lyne.adas.l1.perf.PerfMonitor
import com.lyne.adas.l1.perf.ThermalManager
import com.lyne.adas.l1.perf.TierSelector
import com.lyne.adas.l1.recording.RingBufferRecorder
import com.lyne.adas.l1.sensors.LightSensor
import com.lyne.adas.l1.sensors.LocationProvider
import com.lyne.adas.l1.sensors.MotionProvider
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Top-level orchestration: resolves the device tier, builds the camera→inference→fusion→alert
 * pipeline for that tier, fuses sensor data each frame, and publishes [AdasUiState] for the HUD.
 * Lifecycle-safe: [start]/[stop] are idempotent and tear inference + camera down cleanly.
 */
class AdasController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val _state = MutableStateFlow(AdasUiState())
    val state: StateFlow<AdasUiState> = _state.asStateFlow()

    private val settings = SettingsStore(context)
    private val tripStore = TripStore(context)
    private var config: AdasConfig = settings.loadConfig()

    private val tierSelector = TierSelector(context)
    private val calibration = CalibrationStore(context)
    private val perf = PerfMonitor()
    private val thermal = ThermalManager(context)
    private val location = LocationProvider(context)
    private val motion = MotionProvider(context)
    private val light = LightSensor(context)
    private val alerts = AlertManager(context)
    private val camera = CameraController(context, lifecycleOwner)

    // Day/night
    private var dayNightMode: DayNightMode = settings.dayNightMode
    @Volatile private var nightAuto: Boolean = light.isNight

    // Drive session + recent-event log ring.
    private var session: DriveSession? = null
    private val eventRing = ArrayDeque<AdasEvent>()
    private var recentSnapshot: List<AdasEvent> = emptyList()

    private var detectors: DetectorSet? = null
    private var scheduler: InferenceScheduler? = null
    private var pool: ImageBuffers? = null
    private var analyzer: FrameAnalyzer? = null
    private var fusion: FusionEngine? = null
    private var recorder: RingBufferRecorder? = null
    private var rovix: com.lyne.adas.l1.logging.RovixEventWriter? = null

    private var previewView: PreviewView? = null
    @Volatile private var started = false

    @Volatile private var baseIntrinsics: CameraIntrinsics = CameraIntrinsics.DEFAULT
    @Volatile private var effIntrinsics: CameraIntrinsics = CameraIntrinsics.DEFAULT
    @Volatile private var distanceScale: Float = 1f

    fun start(preview: PreviewView?) {
        if (started) return
        started = true
        previewView = preview

        val resolved = tierSelector.resolve()
        val profile = resolved.profile
        perf.tierName = resolved.tier.name
        perf.inputResolution = profile.inputResolution

        baseIntrinsics = IntrinsicsReader.read(context)
        effIntrinsics = calibration.effectiveIntrinsics(baseIntrinsics)
        distanceScale = calibration.distanceScale

        buildPipeline()

        session = DriveSession(System.currentTimeMillis())

        if (location.hasPermission()) location.start()
        if (motion.available) motion.start()
        light.start { night -> nightAuto = night; pushNight() }
        thermal.start { multiplier, label ->
            scheduler?.setCadenceMultiplier(multiplier)
            perf.cadenceMultiplier = multiplier
            perf.thermal = label
        }

        val target = analysisTargetFor(profile.inputResolution)
        camera.start(
            previewView = preview,
            analyzer = analyzer!!,
            analysisTarget = target,
            videoCapture = recorder?.buildUseCase(),
            onReady = { pushBase(resolved.tier, resolved.probeSummary()) },
            onError = { t -> _state.value = _state.value.copy(error = t.message ?: "camera error", cameraReady = false) },
            onDashcamBound = { ok -> if (ok) recorder?.onBound() else recorder?.onBindFailed() },
        )
        pushBase(resolved.tier, resolved.probeSummary())
        Log.i(TAG, "started tier=${resolved.tier} obj=${detectors?.obj?.name} lane=${detectors?.lane?.name} sign=${detectors?.sign?.name}")
    }

    private fun buildPipeline() {
        val resolved = tierSelector.resolve()
        val profile = resolved.profile
        val dets = DetectorProvider.build(context, config, profile)
        detectors = dets
        perf.backend = dets.obj.backend
        fusion = FusionEngine(config, profile.features)
        recorder = RingBufferRecorder(context, config, profile)
        rovix = if (config.emitRovixSidecar)
            com.lyne.adas.l1.logging.RovixEventWriter(context, resolved.tier, dets.obj.backend) else null

        val pl = ImageBuffers(profile.inputResolution, profile.pixelFormat, capacity = profile.inferenceQueueDepth + 2)
        pool = pl
        val sch = InferenceScheduler(
            detectors = dets,
            profile = profile,
            perf = perf,
            onResult = ::onPerception,
            recycle = { frame -> pl.release(frame.bitmap) },
        )
        scheduler = sch
        analyzer = FrameAnalyzer(pl, sch, perf)
    }

    private fun onPerception(p: Perception) {
        val f = fusion ?: return
        val nowMs = System.currentTimeMillis()
        val result = f.update(
            p = p,
            speedMps = location.speedMps,
            yawRate = motion.yawRate,
            intrinsics = effIntrinsics,
            distanceScale = distanceScale,
            nowMs = nowMs,
        )
        alerts.onResult(result, nowMs)
        session?.tick(nowMs, location.speedMps, location.latitude, location.longitude, result.events)
        if (result.topSeverity == com.lyne.adas.l1.fusion.Severity.CRITICAL) recorder?.onEvent()
        if (result.events.isNotEmpty()) {
            rovix?.let { w -> result.events.forEach(w::record) }
            synchronized(eventRing) {
                for (e in result.events) { eventRing.addLast(e); if (eventRing.size > 100) eventRing.pollFirst() }
                recentSnapshot = eventRing.toList()
            }
        }
        perf.backend = p.objectBackend
        val a = analyzer
        val aspect = if (a != null && a.displayHeight > 0) a.displayWidth.toFloat() / a.displayHeight else _state.value.frameAspect
        _state.value = _state.value.copy(
            fusion = result,
            perf = perf.snapshot(),
            frameAspect = aspect,
            session = session?.snapshot(nowMs),
            recentEvents = recentSnapshot,
            isNight = currentNight(),
        )
    }

    private fun currentNight(): Boolean = when (dayNightMode) {
        DayNightMode.AUTO -> nightAuto
        DayNightMode.DAY -> false
        DayNightMode.NIGHT -> true
    }

    private fun pushNight() {
        _state.value = _state.value.copy(isNight = currentNight(), dayNightMode = dayNightMode)
    }

    private fun pushBase(tier: DeviceTier, probe: String) {
        val profile = tierSelector.resolve().profile
        _state.value = _state.value.copy(
            cameraReady = true,
            tier = tier,
            profile = profile,
            probeSummary = probe,
            usingStub = detectors?.obj?.isStub ?: true,
            signStub = detectors?.sign?.isStub ?: true,
            debugInject = config.debugInjectSynthetic,
            muted = alerts.muted,
            intrinsics = effIntrinsics,
            calibrated = calibration.isCalibrated,
            perf = perf.snapshot(),
            isNight = currentNight(),
            dayNightMode = dayNightMode,
            sensitivity = settings.sensitivity,
            showPerf = config.showPerfOverlay,
        )
    }

    // --- Runtime controls (debug screen / HUD) ---

    fun toggleMute() { alerts.muted = !alerts.muted; _state.value = _state.value.copy(muted = alerts.muted) }

    fun setTierOverride(tier: DeviceTier?) {
        tierSelector.setOverride(tier)
        restart()
    }

    fun applyConfig(newConfig: AdasConfig) {
        config = newConfig
        settings.saveConfig(newConfig)
        restart()
    }

    fun setSensitivity(preset: SensitivityPreset) {
        config = settings.applyPreset(preset)
        _state.value = _state.value.copy(sensitivity = preset)
        restart()
    }

    fun setDayNightMode(mode: DayNightMode) {
        dayNightMode = mode
        settings.dayNightMode = mode
        pushNight()
    }

    fun togglePerf() {
        config = config.copy(showPerfOverlay = !config.showPerfOverlay)
        settings.saveConfig(config)
        _state.value = _state.value.copy(showPerf = config.showPerfOverlay)
    }

    fun isOnboarded(): Boolean = settings.onboarded
    fun setOnboarded() { settings.onboarded = true }
    fun currentConfig(): AdasConfig = config

    /** Share the ROVIX sidecar via a chooser (FileProvider content URI). */
    fun shareRovix() {
        val path = rovix?.path() ?: return
        val file = File(path)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export ROVIX events").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "share rovix failed", t)
        }
    }

    /** Dashcam clips saved on disk (newest first), independent of recorder lifecycle. */
    fun listClips(): List<File> {
        val dir = File(context.getExternalFilesDir(null), "dashcam")
        return (dir.listFiles { f -> f.name.startsWith("clip_") && f.extension == "mp4" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    fun playClip(file: File) = openClip(file, Intent.ACTION_VIEW, "Play clip")
    fun shareClip(file: File) = openClip(file, Intent.ACTION_SEND, "Share clip")

    private fun openClip(file: File, action: String, title: String) {
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(action).apply {
                if (action == Intent.ACTION_SEND) { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri) }
                else { setDataAndType(uri, "video/mp4") }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { Log.w(TAG, "open clip failed", t) }
    }

    fun setFovOverride(deg: Float?) {
        calibration.fovOverrideDeg = deg
        effIntrinsics = calibration.effectiveIntrinsics(baseIntrinsics)
        _state.value = _state.value.copy(intrinsics = effIntrinsics, calibrated = calibration.isCalibrated)
    }

    fun setDistanceScale(scale: Float) {
        calibration.distanceScale = scale
        distanceScale = calibration.distanceScale
        _state.value = _state.value.copy(calibrated = calibration.isCalibrated)
    }

    private fun restart() {
        if (!started) return
        stop()
        start(previewView)
    }

    fun stop() {
        if (!started) return
        started = false
        // Persist the finished drive for trip history.
        session?.let { s -> if (s.hasData) runCatching { tripStore.save(s.toTrip(System.currentTimeMillis())) } }
        session = null
        recorder?.stop(); recorder = null
        camera.stop()
        thermal.stop()
        location.stop()
        motion.stop()
        light.stop()
        scheduler?.close()
        analyzer?.recycle()
        pool?.recycleAll()
        scheduler = null; analyzer = null; pool = null; detectors = null; fusion = null
    }

    /** Past drives, newest first, for the trip-history screen. */
    fun recentTrips(): List<Trip> = tripStore.list()

    fun release() {
        stop()
        camera.shutdown()
        alerts.close()
    }

    private fun analysisTargetFor(inputRes: Int): Size {
        // Keep analysis modestly above the model input so downscale stays sharp, capped for low-end.
        return when {
            inputRes >= 384 -> Size(1280, 720)
            inputRes >= 320 -> Size(960, 540)
            else -> Size(640, 480)
        }
    }

    companion object { private const val TAG = "AdasController" }
}

private fun com.lyne.adas.l1.perf.ResolvedTier.probeSummary(): String =
    probe?.summary ?: (if (overridden) "tier override=${tier.name}" else "tier=${tier.name} (cached)")
