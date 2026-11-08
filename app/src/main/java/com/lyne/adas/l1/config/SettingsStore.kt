package com.lyne.adas.l1.config

import android.content.Context

/**
 * Persists user-tunable settings so they survive restarts. Uses SharedPreferences for synchronous
 * load at startup, consistent with CalibrationStore/TierSelector. Holds the tunable [AdasConfig]
 * subset plus sensitivity preset, day/night mode and the onboarding flag.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lyne_settings", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean(K_ONBOARDED, false)
        set(v) = prefs.edit().putBoolean(K_ONBOARDED, v).apply()

    var sensitivity: SensitivityPreset
        get() = runCatching { SensitivityPreset.valueOf(prefs.getString(K_SENS, "NORMAL")!!) }.getOrDefault(SensitivityPreset.NORMAL)
        set(v) = prefs.edit().putString(K_SENS, v.name).apply()

    var dayNightMode: DayNightMode
        get() = runCatching { DayNightMode.valueOf(prefs.getString(K_DN, "AUTO")!!) }.getOrDefault(DayNightMode.AUTO)
        set(v) = prefs.edit().putString(K_DN, v.name).apply()

    /** Build an AdasConfig from stored values, defaulting to the current sensitivity preset. */
    fun loadConfig(): AdasConfig {
        val d = sensitivity.apply(AdasConfig.DEFAULT)
        return d.copy(
            ttcCautionSec = prefs.getFloat(K_TTC_C, d.ttcCautionSec),
            ttcCriticalSec = prefs.getFloat(K_TTC_X, d.ttcCriticalSec),
            headwayWarnSec = prefs.getFloat(K_HW_W, d.headwayWarnSec),
            headwayCriticalSec = prefs.getFloat(K_HW_X, d.headwayCriticalSec),
            objectScoreThreshold = prefs.getFloat(K_SCORE, d.objectScoreThreshold),
            overspeedToleranceKph = prefs.getInt(K_OVSP, d.overspeedToleranceKph),
            egoPathHalfWidthFrac = prefs.getFloat(K_EGO, d.egoPathHalfWidthFrac),
            debugInjectSynthetic = prefs.getBoolean(K_INJECT, d.debugInjectSynthetic),
            videoTestMode = prefs.getBoolean(K_VIDEO, d.videoTestMode),
            videoTestSpeedKph = prefs.getInt(K_VIDEO_SPD, d.videoTestSpeedKph),
            showPerfOverlay = prefs.getBoolean(K_PERF, d.showPerfOverlay),
            emitRovixSidecar = prefs.getBoolean(K_ROVIX, d.emitRovixSidecar),
            dashcamEnabled = prefs.getBoolean(K_DASH, d.dashcamEnabled),
            voiceAlerts = prefs.getBoolean(K_VOICE, d.voiceAlerts),
            accuracyLog = prefs.getBoolean(K_ACCLOG, d.accuracyLog),
        )
    }

    fun saveConfig(c: AdasConfig) {
        prefs.edit()
            .putFloat(K_TTC_C, c.ttcCautionSec).putFloat(K_TTC_X, c.ttcCriticalSec)
            .putFloat(K_HW_W, c.headwayWarnSec).putFloat(K_HW_X, c.headwayCriticalSec)
            .putFloat(K_SCORE, c.objectScoreThreshold).putInt(K_OVSP, c.overspeedToleranceKph)
            .putFloat(K_EGO, c.egoPathHalfWidthFrac)
            .putBoolean(K_INJECT, c.debugInjectSynthetic)
            .putBoolean(K_VIDEO, c.videoTestMode).putInt(K_VIDEO_SPD, c.videoTestSpeedKph).putBoolean(K_PERF, c.showPerfOverlay)
            .putBoolean(K_ROVIX, c.emitRovixSidecar).putBoolean(K_DASH, c.dashcamEnabled)
            .putBoolean(K_VOICE, c.voiceAlerts).putBoolean(K_ACCLOG, c.accuracyLog)
            .apply()
    }

    /** Apply a preset to the stored config and remember the choice. */
    fun applyPreset(preset: SensitivityPreset): AdasConfig {
        sensitivity = preset
        val c = preset.apply(loadConfig())
        saveConfig(c)
        return c
    }

    companion object {
        private const val K_ONBOARDED = "onboarded"
        private const val K_SENS = "sensitivity"
        private const val K_DN = "daynight"
        private const val K_TTC_C = "ttc_caution"
        private const val K_TTC_X = "ttc_critical"
        private const val K_HW_W = "hw_warn"
        private const val K_HW_X = "hw_crit"
        private const val K_SCORE = "obj_score"
        private const val K_OVSP = "overspeed_tol"
        private const val K_EGO = "ego_half"
        private const val K_INJECT = "inject"
        private const val K_PERF = "perf"
        private const val K_ROVIX = "rovix"
        private const val K_DASH = "dashcam"
        private const val K_VIDEO = "video_test"
        private const val K_VIDEO_SPD = "video_test_kph"
        private const val K_VOICE = "voice"
        private const val K_ACCLOG = "acc_log"
    }
}
