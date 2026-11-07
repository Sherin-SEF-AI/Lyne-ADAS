package com.lyne.adas.l1.alerts

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.lyne.adas.l1.fusion.AlertType
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.logging.Log

/**
 * Audio + haptic + (optional) voice alerting.
 *
 * Sounds fire on the ONSET of an incident (FusionEngine emits one event per incident), so a caution
 * is a single chirp, not a metronome. Only an active CRITICAL keeps repeating, because it demands an
 * immediate response. Tones come from [ToneGenerator]: asset-free and reliable on every device.
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

    private val voice = VoiceAnnouncer(context)
    private var lastCriticalMs = 0L
    @Volatile var muted = false
    @Volatile var voiceEnabled = true

    fun onResult(result: FusionResult, nowMs: Long) {
        if (muted) return
        var onsetCritical = false
        for (e in result.events) {
            when {
                e.type == AlertType.LEAD_GO -> { chime(); if (voiceEnabled) voice.say(e.type, e.severity, e.speedLimitKph) }
                e.severity == Severity.CRITICAL -> onsetCritical = true
                e.severity == Severity.CAUTION -> { beep(critical = false); vibrate(longArrayOf(0, 80)) }
            }
            if (voiceEnabled && e.type != AlertType.LEAD_GO && e.severity.ordinal >= Severity.CAUTION.ordinal) {
                voice.say(e.type, e.severity, e.speedLimitKph)
            }
        }
        val critActive = result.topSeverity == Severity.CRITICAL
        if (onsetCritical || (critActive && nowMs - lastCriticalMs > CRITICAL_REPEAT_MS)) {
            lastCriticalMs = nowMs
            beep(critical = true)
            vibrate(longArrayOf(0, 140, 70, 140))
        }
    }

    private fun beep(critical: Boolean) {
        val type = if (critical) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP
        val ms = if (critical) 260 else 140
        try { tone?.startTone(type, ms) } catch (t: Throwable) { Log.w(TAG, "tone failed", t) }
    }

    private fun chime() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 200) } catch (_: Throwable) {}
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (t: Throwable) { Log.w(TAG, "vibrate failed", t) }
    }

    fun close() {
        try { tone?.release() } catch (_: Throwable) {}
        voice.close()
    }

    companion object {
        private const val TAG = "AlertManager"
        private const val CRITICAL_REPEAT_MS = 900L
    }
}
