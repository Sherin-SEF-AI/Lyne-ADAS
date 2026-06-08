package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.inference.LaneResult
import kotlin.math.abs

data class LaneDepartureResult(val severity: Severity, val offset: Float, val suppressed: Boolean)

/**
 * Lane Departure Warning without a turn-signal feed. Warns when the vehicle's lane-centre offset
 * breaches a threshold AND the lane fit is confident. A *deliberate* manoeuvre (a sharp, fast
 * crossing — high yaw rate or high lateral velocity, as in a signaled lane change or a junction
 * turn) is treated as intentional and suppressed; a slow unintended drift is what we warn on.
 */
class LaneDepartureLogic(private val config: AdasConfig) {
    private var lastOffset = Float.NaN
    private var lastTimeNs = 0L

    fun update(lane: LaneResult, yawRate: Float, timestampNs: Long): LaneDepartureResult {
        if (lane.confidence < config.ldwMinLaneConfidence) {
            lastOffset = Float.NaN
            return LaneDepartureResult(Severity.NONE, lane.centerOffset, suppressed = false)
        }

        val offset = lane.centerOffset
        // Lateral velocity in half-lane units/sec from the offset trajectory.
        val lateralVel = if (!lastOffset.isNaN() && lastTimeNs != 0L) {
            val dt = (timestampNs - lastTimeNs) / 1e9f
            if (dt > 1e-3f) (offset - lastOffset) / dt else 0f
        } else 0f
        lastOffset = offset
        lastTimeNs = timestampNs

        val deliberate = abs(yawRate) > YAW_DELIBERATE_RPS || abs(lateralVel) > config.ldwManeuverLateralMps
        val breach = abs(offset) >= config.ldwOffsetWarnFrac

        return when {
            breach && deliberate -> LaneDepartureResult(Severity.NONE, offset, suppressed = true)
            abs(offset) >= 1.0f -> LaneDepartureResult(Severity.CRITICAL, offset, suppressed = false)
            breach -> LaneDepartureResult(Severity.CAUTION, offset, suppressed = false)
            else -> LaneDepartureResult(Severity.NONE, offset, suppressed = false)
        }
    }

    companion object {
        // ~0.35 rad/s yaw is a clearly intentional turn, not a drift.
        private const val YAW_DELIBERATE_RPS = 0.35f
    }
}
