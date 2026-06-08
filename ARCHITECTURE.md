# Lyne ADAS - Architecture & Invariants

On-device Android ADAS L1 (warning-only). One APK, self-tunes across all device tiers. Read this
before changing the pipeline so the invariants below stay intact.

## Module map (`app/src/main/java/com/lyne/adas/l1/`)
- `config/` - `AdasConfig` (tier-independent thresholds/flags), `DeviceTier`, `TierProfile`
  (per-tier knobs as data), `FeatureFlags`, `SensitivityPreset`/`DayNightMode`, `SettingsStore`
  (SharedPreferences persistence of the tunable config + preset + day/night + onboarded flag).
  **All tuning is data here, not scattered in code.**
- `perf/` - `DeviceProber` (capability probe + CPU micro-benchmark), `TierSelector` (resolve + cache
  against a hardware fingerprint + override), `PerfMonitor` (EMA FPS/latency, drops, heap),
  `ThermalManager` (thermal → cadence multiplier).
- `camera/` - `CameraController` (CameraX Preview + ImageAnalysis, RGBA_8888, KEEP_ONLY_LATEST),
  `FrameAnalyzer` (copy→close ImageProxy→submit; drop on busy), `FrameConverter` (rotate+squash to
  square, reusable scratch), `ImageBuffers` (bitmap pool).
- `inference/` - `Detector<T>` interface; `DetectionResult` types; `DelegateFactory` + `LiteRtEngine`
  (measured NNAPI→GPU→XNNPACK with warm-up validation, reusable tensor buffers); real decoders
  (`ObjectDetector`/`LaneDetector`/`SignClassifier`); `DrivableAreaDetector` (classical no-model road
  segmentation → green carpet + LDW); `StubDetectors`; `DetectorProvider` (real-or-stub selection);
  `InferenceScheduler` (single thread, bounded queue, staggered cadence).
- `fusion/` - `DistanceEstimator`, `TtcEstimator`, `HeadwayMonitor`, `LaneDepartureLogic`,
  `OverspeedMonitor`, `PathIntrusion`, `FusionEngine` → `AdasEvent`/`FusionResult`.
- `sensors/` - `LocationProvider` (GPS speed), `MotionProvider` (IMU for LDW), `LightSensor`
  (ambient lux → auto day/night with hysteresis).
- `calibration/` - `IntrinsicsReader`, `CameraIntrinsics`, `CalibrationStore`.
- `alerts/` - `AlertManager` (ToneGenerator + haptics, rate-limited by severity).
- `logging/` - `Log`, `RovixEventWriter` (sidecar JSON-lines).
- `recording/` - `RingBufferRecorder` (dashcam, flagged off).
- `pipeline/` - `AdasController` (orchestration), `AdasUiState`, `DriveSession` (live trip stats).
- `ui/` - automotive Compose HUD: `HudScreen`, `HudOverlay` (3D lane carpet + chevrons + corner
  boxes + departure arrow), `HudWidgets` (status/perf/control bar), `Gauges` (animated TTC/headway/
  speed rings), `SettingsScreen` (sliders/presets/day-night/tier), `SessionScreen` (trip + event log
  + export), `OnboardingScreen`, `CalibrationScreen` (wizard), `Common` (palette helpers), `theme/`
  (`LynePalette` day & night via `LocalLynePalette`).
- root - `LyneApp` (StrictMode/onTrimMemory), `MainActivity` (permissions + onboarding-gated nav:
  HUD/SETTINGS/CALIB/SESSION, day/night theming). `FileProvider` (`xml/file_paths`) backs ROVIX export.

## Invariants - do not break
1. **Zero per-frame allocation in the hot path.** Bitmaps come from `ImageBuffers`; tensor buffers,
   pixel arrays, the source bitmap, Canvas/Matrix and float scratch are allocated once and reused.
   New per-frame `ByteBuffer`/`Bitmap`/large array = regression.
2. **The camera analyzer never blocks.** `FrameAnalyzer` copies, closes the `ImageProxy`, and submits
   to a bounded queue; if the pool is empty or inference is busy, it **drops and counts** the frame.
3. **Single inference thread.** All `Detector.detect` calls run on `InferenceScheduler`'s one thread.
   Detectors are not thread-safe by contract.
4. **Backend is measured, not assumed.** `LiteRtEngine.create` tries the tier's delegate order and
   keeps the first that builds AND passes a finite-output warm-up; everything falls back to XNNPACK.
5. **The app always runs.** Missing/failed real weights → fall back (object→stub, lane→classical CV).
   Stubs emit nothing in normal mode (no fake alerts, no fake "all clear"); INJECT drives synthetic
   scenarios. Current state: object = **real YOLOv8n COCO INT8** (bundled, verified NNAPI/XNNPACK);
   lane/drivable = **real classical segmentation** (`DrivableAreaDetector`: seed + coarse-grid region
   grow, rendered as the green drivable-area carpet); stop sign = real (object model); speed-limit TSR = **real GTSRB
   INT8 classifier** + red-blob localization in `SignClassifier`. `DetectorProvider` prefers a real
   `.tflite` when present, else falls back (object→stub, lane→classical, sign→stub).
6. **Per-tier behaviour lives in `TierProfile`.** Add a knob there + thread it through; don't branch
   on `DeviceTier` in pipeline code.
7. **Hard alerts are gated.** CRITICAL requires distance confidence ≥ threshold AND debounce frames.
   Low-confidence estimates downgrade, never fabricate a CRITICAL.
8. **Lifecycle-safe.** `AdasController.stop()` tears down camera, sensors, thermal, scheduler and
   recycles pools; `start/stop` are idempotent and driven by Activity `onStart/onStop`.

## Deliberate deviations from a naive spec read (with rationale)
- **RGBA_8888 analysis output** instead of manual YUV_420_888 conversion: fewer per-frame ops, no
  native YUV code, robust across OEM HALs. Conversion lives in `FrameConverter`.
- **ToneGenerator** for audio instead of SoundPool: asset-free, reliable; no bundled audio needed.
- **FoV-based** monocular distance (not pixel focal length): independent of detection-frame size and
  works directly with normalized boxes.
- **Square squash** (independent x/y scale) preserves normalized coords, so detector boxes map
  straight onto the display image for the HUD.

## Coordinate convention
Detector outputs are normalized `[0,1]` in **display-image** space (post-rotation). `HudOverlay`
maps them through the PreviewView's FILL_CENTER crop. Distance uses box width fraction × FoV.

## Build / verify
`./gradlew :app:assembleDebug` → `adb install -r`. On device: probe runs ≤3 s → tier selected →
PerfOverlay shows FPS/latency/backend/tier/drops/heap. ROVIX sidecar lands under the app's external
files dir (`rovix/events-*.jsonl`). Logs: `adb logcat -s Lyne`.
