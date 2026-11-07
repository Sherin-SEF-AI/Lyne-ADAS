package com.lyne.adas.l1.alerts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.lyne.adas.l1.fusion.AlertType
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.logging.Log
import java.util.Locale

/**
 * Short spoken alerts through the platform TextToSpeech engine (offline on virtually every phone,
 * no bundled assets). Phrases are 1-2 words so they never lag the situation; each type is throttled.
 */
class VoiceAnnouncer(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = try { TextToSpeech(context.applicationContext, this) } catch (t: Throwable) { null }
    @Volatile private var ready = false
    private val lastSaid = LongArray(AlertType.entries.size)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "TTS init failed: $status"); return }
        val t = tts ?: return
        runCatching { t.language = Locale.getDefault() }
        runCatching { t.setSpeechRate(1.15f) }
        ready = true
    }

    fun say(type: AlertType, severity: Severity, speedLimit: Int?) {
        if (!ready) return
        val now = System.currentTimeMillis()
        val minGap = if (severity == Severity.CRITICAL) 2500L else 6000L
        if (now - lastSaid[type.ordinal] < minGap) return
        val phrase = when (type) {
            AlertType.FCW -> if (severity == Severity.CRITICAL) "Brake!" else "Vehicle ahead"
            AlertType.VRU -> if (severity == Severity.CRITICAL) "Pedestrian!" else "Watch for pedestrians"
            AlertType.HEADWAY -> "Keep distance"
            AlertType.LDW -> "Lane"
            AlertType.OVERSPEED -> speedLimit?.let { "Speed limit $it" } ?: "Slow down"
            AlertType.STOP -> "Stop sign"
            AlertType.LEAD_GO -> "Traffic moving"
        }
        lastSaid[type.ordinal] = now
        val mode = if (severity == Severity.CRITICAL) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        runCatching { tts?.speak(phrase, mode, null, type.name) }
    }

    fun close() { runCatching { tts?.shutdown() }; tts = null; ready = false }

    companion object { private const val TAG = "Voice" }
}
