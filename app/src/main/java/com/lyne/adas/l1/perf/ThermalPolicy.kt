package com.lyne.adas.l1.perf

/**
 * What the scheduler should run at the current degradation level. The object detector (collision and
 * pedestrian warnings) is never skipped: every level only takes work away from the other models.
 */
data class DegradationPlan(
    /** 0 = full pipeline ... [ThermalPolicy.MAX_LEVEL] = most degraded. */
    val level: Int,
    /** Speed-limit sign classification. First thing dropped. */
    val signEnabled: Boolean,
    /** Drivable-area segmentation runs on every Nth object frame (multiplies the tier's lane cadence). */
    val segEvery: Int,
    /** Use the smaller (nano) object model input. Last resort. */
    val smallObjectModel: Boolean,
) {
    val label: String
        get() = if (level == 0) "full" else buildString {
            append("L").append(level).append(" tsr off")
            if (segEvery > 1) append(" seg/").append(segEvery)
            if (smallObjectModel) append(" obj-small")
        }

    companion object {
        val FULL = DegradationPlan(0, signEnabled = true, segEvery = 1, smallObjectModel = false)
    }
}

/**
 * Prioritised thermal degradation with hysteresis. Pure Kotlin, driven by the OS thermal status
 * (PowerManager.THERMAL_STATUS_* values, 0..6) and the measured processed-frame rate:
 *
 *  level 0  everything (tier defaults)
 *  level 1  sign classification off                         (thermal MODERATE)
 *  level 2  + segmentation every 2nd object frame          (SEVERE)
 *  level 3  + segmentation every 3rd object frame          (CRITICAL)
 *  level 4  + smaller object model input if the tier has one, else segmentation every 4th (EMERGENCY+)
 *
 * While the device is already hot (MODERATE or worse) and the frame rate stays well below what it ran at
 * when cool, one or two extra levels are added (each after [lowFpsHoldMs] of low fps).
 * Escalation is immediate; recovery steps down one level at a time, and only after the lower level has
 * been justified continuously for [coolHoldMs], so a status flickering around a boundary cannot make the
 * pipeline oscillate.
 */
class ThermalPolicy(
    private val targetFps: Float,
    private val canUseSmallModel: Boolean,
    private val coolHoldMs: Long = 20_000L,
    private val lowFpsHoldMs: Long = 5_000L,
) {
    var level = 0
        private set
    var plan: DegradationPlan = DegradationPlan.FULL
        private set

    private var fpsPenalty = 0
    private var lowSinceMs = -1L
    private var goodSinceMs = -1L
    private var lowerSinceMs = -1L
    /** Frame rate the device sustained while cool at level 0 (EMA); the yardstick for "too slow". */
    var coolFps = Float.NaN
        private set

    fun update(thermalStatus: Int, fps: Float, nowMs: Long): DegradationPlan {
        val base = baseLevel(thermalStatus)
        val hasFps = fps > 0f && !fps.isNaN()

        if (hasFps && thermalStatus <= STATUS_LIGHT && level == 0) {
            coolFps = if (coolFps.isNaN()) fps else coolFps + 0.05f * (fps - coolFps)
        }
        val ref = if (coolFps.isNaN()) targetFps else coolFps

        if (thermalStatus < STATUS_MODERATE) {
            fpsPenalty = 0; lowSinceMs = -1L; goodSinceMs = -1L
        } else if (hasFps) {
            if (fps < LOW_FPS_FRAC * ref) {
                goodSinceMs = -1L
                if (lowSinceMs < 0L) lowSinceMs = nowMs
                if (nowMs - lowSinceMs >= lowFpsHoldMs && fpsPenalty < MAX_FPS_PENALTY) { fpsPenalty++; lowSinceMs = nowMs }
            } else {
                lowSinceMs = -1L
                if (fps >= GOOD_FPS_FRAC * ref) {
                    if (goodSinceMs < 0L) goodSinceMs = nowMs
                    if (nowMs - goodSinceMs >= coolHoldMs && fpsPenalty > 0) { fpsPenalty--; goodSinceMs = nowMs }
                } else goodSinceMs = -1L
            }
        }

        val desired = (base + fpsPenalty).coerceIn(0, MAX_LEVEL)
        when {
            desired > level -> { level = desired; lowerSinceMs = -1L }
            desired < level -> {
                if (lowerSinceMs < 0L) lowerSinceMs = nowMs
                if (nowMs - lowerSinceMs >= coolHoldMs) { level--; lowerSinceMs = nowMs }
            }
            else -> lowerSinceMs = -1L
        }
        plan = planFor(level)
        return plan
    }

    private val plans = arrayOf(
        DegradationPlan.FULL,
        DegradationPlan(1, signEnabled = false, segEvery = 1, smallObjectModel = false),
        DegradationPlan(2, signEnabled = false, segEvery = 2, smallObjectModel = false),
        DegradationPlan(3, signEnabled = false, segEvery = 3, smallObjectModel = false),
        if (canUseSmallModel) DegradationPlan(4, signEnabled = false, segEvery = 3, smallObjectModel = true)
        else DegradationPlan(4, signEnabled = false, segEvery = 4, smallObjectModel = false),
    )

    private fun planFor(l: Int): DegradationPlan = plans[l.coerceIn(0, MAX_LEVEL)]

    companion object {
        const val MAX_LEVEL = 4
        // android.os.PowerManager.THERMAL_STATUS_* (kept here so the policy stays JVM-testable).
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val STATUS_CRITICAL = 4
        const val STATUS_EMERGENCY = 5
        const val STATUS_SHUTDOWN = 6

        private const val LOW_FPS_FRAC = 0.6f
        private const val GOOD_FPS_FRAC = 0.85f
        private const val MAX_FPS_PENALTY = 2

        fun baseLevel(status: Int): Int = when {
            status <= STATUS_LIGHT -> 0
            status == STATUS_MODERATE -> 1
            status == STATUS_SEVERE -> 2
            status == STATUS_CRITICAL -> 3
            else -> 4
        }

        fun statusLabel(status: Int): String = when (status) {
            STATUS_NONE -> "none"; STATUS_LIGHT -> "light"; STATUS_MODERATE -> "moderate"
            STATUS_SEVERE -> "severe"; STATUS_CRITICAL -> "critical"; STATUS_EMERGENCY -> "emergency"
            STATUS_SHUTDOWN -> "shutdown"; else -> "n/a"
        }
    }
}
