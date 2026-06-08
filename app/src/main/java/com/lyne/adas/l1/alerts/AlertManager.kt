package com.lyne.adas.l1.alerts

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.logging.Log

/**
 * Audio + haptic alerting. Tones come from [ToneGenerator] (asset-free, reliable across devices,
 * see ARCHITECTURE.md for why this over SoundPool). Output is rate-limited per severity so warnings are
 * assertive without becoming a continuous nuisance, and only escalate on CAUTION/CRITICAL.
 */
class AlertManager(context: Context) {
    private val tone = try {
        ToneGenerator(AudioManager.STREAM_ALARM, 90)
    } catch (t: Throwable) {
        Log.w(TAG, "ToneGenerator unavailable", t); null
    }

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastCriticalMs = 0L
    private var lastCautionMs = 0L
    @Volatile var muted = false

    fun onResult(result: FusionResult, nowMs: Long) {
        if (muted) return
        when (result.topSeverity) {
            Severity.CRITICAL -> if (nowMs - lastCriticalMs > CRITICAL_INTERVAL_MS) {
                lastCriticalMs = nowMs
                beep(critical = true)
                vibrate(longArrayOf(0, 120, 60, 120))
            }
            Severity.CAUTION -> if (nowMs - lastCautionMs > CAUTION_INTERVAL_MS) {
                lastCautionMs = nowMs
                beep(critical = false)
                vibrate(longArrayOf(0, 80))
            }
            else -> { /* INFO/NONE: visual only */ }
        }
    }

    private fun beep(critical: Boolean) {
        val type = if (critical) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP
        val ms = if (critical) 250 else 140
        try { tone?.startTone(type, ms) } catch (t: Throwable) { Log.w(TAG, "tone failed", t) }
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        } catch (t: Throwable) { Log.w(TAG, "vibrate failed", t) }
    }

    fun close() {
        try { tone?.release() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "AlertManager"
        private const val CRITICAL_INTERVAL_MS = 600L
        private const val CAUTION_INTERVAL_MS = 1500L
    }
}
