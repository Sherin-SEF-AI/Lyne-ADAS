package com.lyne.adas.l1.config

/**
 * One-tap warning sensitivity. Scales the FCW/headway/detection thresholds of an [AdasConfig].
 * CALM warns earlier and is more conservative about false alarms; AGGRESSIVE warns later/tighter and
 * is more eager to detect.
 */
enum class SensitivityPreset {
    CALM, NORMAL, AGGRESSIVE;

    fun apply(base: AdasConfig): AdasConfig = when (this) {
        CALM -> base.copy(
            ttcCautionSec = 3.3f, ttcCriticalSec = 1.9f,
            headwayWarnSec = 1.4f, headwayCriticalSec = 0.8f,
            objectScoreThreshold = 0.48f, alertHoldScale = 1.35f,
        )
        NORMAL -> base.copy(
            ttcCautionSec = 2.7f, ttcCriticalSec = 1.5f,
            headwayWarnSec = 1.0f, headwayCriticalSec = 0.6f,
            objectScoreThreshold = 0.40f, alertHoldScale = 1.0f,
        )
        AGGRESSIVE -> base.copy(
            ttcCautionSec = 2.3f, ttcCriticalSec = 1.2f,
            headwayWarnSec = 0.9f, headwayCriticalSec = 0.5f,
            objectScoreThreshold = 0.35f, alertHoldScale = 0.7f,
        )
    }
}

/** How the HUD chooses its day vs night palette. */
enum class DayNightMode { AUTO, DAY, NIGHT }
