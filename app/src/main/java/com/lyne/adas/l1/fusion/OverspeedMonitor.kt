package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.inference.SignResult
import com.lyne.adas.l1.inference.SignType

/**
 * Holds the current speed limit (last confidently recognized SPEED_LIMIT sign, until it goes stale)
 * and compares it to GPS speed for overspeed warnings.
 */
class OverspeedMonitor(private val config: AdasConfig) {
    private var currentLimitKph: Int? = null
    private var limitSetAtMs: Long = 0L

    private var pendingKph: Int? = null
    private var pendingAtMs = 0L

    /**
     * A single classification is not trusted: the same limit must be read twice within 3 s (or once
     * at very high confidence). One misread "30" on a highway no longer triggers an overspeed alarm.
     */
    fun onSign(sign: SignResult, nowMs: Long) {
        val kph = sign.speedLimitKph ?: return
        if (sign.type != SignType.SPEED_LIMIT || sign.confidence < config.signMinConfidence) return
        val confirmed = sign.confidence >= 0.9f || kph == currentLimitKph ||
            (kph == pendingKph && nowMs - pendingAtMs < 3000L)
        if (confirmed) {
            currentLimitKph = kph; limitSetAtMs = nowMs; pendingKph = null
        } else {
            pendingKph = kph; pendingAtMs = nowMs
        }
    }

    fun currentLimit(nowMs: Long): Int? {
        val limit = currentLimitKph ?: return null
        return if (nowMs - limitSetAtMs > config.signStaleMs) { currentLimitKph = null; null } else limit
    }

    /** @return CRITICAL if well over, CAUTION if over tolerance, else NONE. */
    fun evaluate(speedKph: Float, nowMs: Long): Pair<Severity, Int?> {
        val limit = currentLimit(nowMs) ?: return Severity.NONE to null
        if (speedKph.isNaN()) return Severity.NONE to limit
        val over = speedKph - limit
        val sev = when {
            over > config.overspeedToleranceKph + 15 -> Severity.CRITICAL
            over > config.overspeedToleranceKph -> Severity.CAUTION
            else -> Severity.NONE
        }
        return sev to limit
    }
}
