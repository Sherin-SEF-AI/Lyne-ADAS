package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.config.AdasConfig

/** Following-distance in seconds = gap / ego-speed. Continuous HUD readout + tailgating warning. */
object HeadwayMonitor {

    fun headwaySec(distanceM: Float, speedMps: Float): Float {
        if (speedMps.isNaN() || speedMps < 2.0f) return Float.NaN // below ~7 km/h headway is meaningless
        return distanceM / speedMps
    }

    fun severity(headwaySec: Float, config: AdasConfig): Severity = when {
        headwaySec.isNaN() -> Severity.NONE
        headwaySec < config.headwayCriticalSec -> Severity.CRITICAL
        headwaySec < config.headwayWarnSec -> Severity.CAUTION
        else -> Severity.NONE
    }
}
