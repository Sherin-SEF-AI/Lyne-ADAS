package com.lyne.adas.l1.recording

import android.content.Context
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.TierProfile
import com.lyne.adas.l1.logging.Log

/**
 * Optional event-triggered dashcam (H.265, last N seconds). FEATURE-FLAGGED OFF by default and
 * auto-disabled on Tier C / low storage — a third concurrent camera use case is exactly the kind
 * of load entry devices can't carry alongside live inference.
 *
 * Enabling it requires binding a CameraX VideoCapture use case in CameraController and a storage
 * permission flow; that wiring is intentionally not active in this build. The control surface is
 * real so the rest of the app can call it unconditionally; when disabled every call is a safe no-op
 * and [isActive] is false (the UI shows "dashcam off", never a false "recording" indicator).
 */
class RingBufferRecorder(
    private val context: Context,
    private val config: AdasConfig,
    private val profile: TierProfile,
) {
    @Volatile var isActive = false
        private set

    val enabled: Boolean get() = config.dashcamEnabled && profile.allowDashcam

    fun start() {
        if (!enabled) {
            Log.i(TAG, "dashcam disabled (flag=${config.dashcamEnabled}, tierAllows=${profile.allowDashcam})")
            return
        }
        // Activation path (not wired in this build): bind VideoCapture + maintain a rolling buffer.
        Log.i(TAG, "dashcam enable requested but VideoCapture wiring is inactive in this build")
        isActive = false
    }

    /** Persist the rolling buffer around a triggering event. No-op while inactive. */
    fun onEvent() {
        if (!isActive) return
    }

    fun stop() { isActive = false }

    companion object { private const val TAG = "RingBufferRecorder" }
}
