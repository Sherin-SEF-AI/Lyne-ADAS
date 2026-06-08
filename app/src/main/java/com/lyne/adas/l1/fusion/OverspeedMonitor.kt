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

    fun onSign(sign: SignResult, nowMs: Long) {
        if (sign.type == SignType.SPEED_LIMIT && sign.speedLimitKph != null && sign.confidence >= config.signMinConfidence) {
            currentLimitKph = sign.speedLimitKph
            limitSetAtMs = nowMs
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
