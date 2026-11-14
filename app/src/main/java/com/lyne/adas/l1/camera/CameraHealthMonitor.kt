package com.lyne.adas.l1.camera

import com.lyne.adas.l1.fusion.AlertType
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.fusion.Severity
import kotlin.math.abs
import kotlin.math.tan

/** Camera self-check verdict shown on the HUD. */
data class CameraHealth(
    val blocked: Boolean = false,
    val misaligned: Boolean = false,
    /** Which mount problem was seen (for the banner hint). */
    val tilted: Boolean = false,
) {
    val ok: Boolean get() = !blocked && !misaligned

    val title: String? get() = when {
        blocked -> "CAMERA BLOCKED"
        misaligned -> "CHECK PHONE MOUNT"
        else -> null
    }

    val detail: String? get() = when {
        blocked -> "Collision warnings paused. Clear the lens or anything in front of it."
        misaligned && tilted -> "Phone is tilted. Level it so warnings stay accurate."
        misaligned -> "Camera is not aimed at the road ahead. Warnings may be wrong."
        else -> null
    }
}

/**
 * Pure camera self-check. Fed with per-frame image statistics ([onFrame], from [FrameStats]) and the
 * IMU mount estimate ([onMount]); both are cheap and may run on different threads.
 *
 * Blocked = the frame stays suspicious for [blockMs]:
 *  - very dark AND featureless while it is daytime (ambient sensor says day, or the camera saw a
 *    bright, detailed scene in the last [dayMemoryMs]) -> lens covered;
 *  - near-uniform but not dark, any time of day -> cloth, paper, fogged lens;
 *  - almost no fine detail in daytime -> smeared / fogged lens.
 * Night dark frames alone never count: an unlit road looks like that.
 *
 * Misaligned = the horizon implied by the mount pitch is outside the frame's expected band, or the
 * roll exceeds [maxRollDeg], for [mountMs]. Both verdicts clear with hysteresis.
 */
class CameraHealthMonitor(
    private val blockMs: Long = 3_000,
    private val unblockMs: Long = 1_000,
    private val mountMs: Long = 5_000,
    private val mountClearMs: Long = 2_000,
    private val dayMemoryMs: Long = 60_000,
    private val maxRollDeg: Float = 8f,
    private val horizonBand: ClosedFloatingPointRange<Float> = 0.12f..0.88f,
) {
    private val lock = Any()
    private var lastBrightMs = Long.MIN_VALUE / 2
    private var badSince = -1L
    private var goodSince = -1L
    private var blocked = false

    private var mountBadSince = -1L
    private var mountGoodSince = -1L
    private var misaligned = false
    private var tilted = false

    @Volatile var health: CameraHealth = CameraHealth(); private set

    fun onFrame(nowMs: Long, meanLuma: Float, lumaStd: Float, detail: Float, ambientNight: Boolean): CameraHealth = synchronized(lock) {
        if (meanLuma > BRIGHT_LUMA && detail > HEALTHY_DETAIL) lastBrightMs = nowMs
        val daytime = !ambientNight || nowMs - lastBrightMs < dayMemoryMs
        val suspicious = isSuspicious(meanLuma, lumaStd, detail, daytime)
        if (suspicious) {
            goodSince = -1L
            if (badSince < 0) badSince = nowMs
            if (!blocked && nowMs - badSince >= blockMs) blocked = true
        } else {
            badSince = -1L
            if (goodSince < 0) goodSince = nowMs
            if (blocked && nowMs - goodSince >= unblockMs) blocked = false
        }
        publish()
    }

    /**
     * @param pitchRad mount pitch (down positive) or NaN if unknown. @param rollDeg mount roll.
     * @param enabled false (e.g. video test mode, phone aimed at a monitor) clears the verdict.
     */
    fun onMount(nowMs: Long, pitchRad: Float, rollDeg: Float, vFovDeg: Float, enabled: Boolean = true): CameraHealth = synchronized(lock) {
        val badRoll = abs(rollDeg) > maxRollDeg
        val bad = enabled && !pitchRad.isNaN() && (badRoll || horizonY(pitchRad, vFovDeg) !in horizonBand)
        if (bad) {
            mountGoodSince = -1L
            if (mountBadSince < 0) mountBadSince = nowMs
            if (!misaligned && nowMs - mountBadSince >= mountMs) misaligned = true
            if (misaligned) tilted = badRoll
        } else {
            mountBadSince = -1L
            if (mountGoodSince < 0) mountGoodSince = nowMs
            if (misaligned && (!enabled || nowMs - mountGoodSince >= mountClearMs)) { misaligned = false; tilted = false }
        }
        publish()
    }

    private fun publish(): CameraHealth {
        val h = CameraHealth(blocked, misaligned, tilted)
        if (h != health) health = h
        return health
    }

    companion object {
        const val DARK_LUMA = 18f
        const val BRIGHT_LUMA = 70f
        const val UNIFORM_STD = 5f
        const val LOW_DETAIL = 1.5f
        const val HEALTHY_DETAIL = 4f

        fun isSuspicious(meanLuma: Float, lumaStd: Float, detail: Float, daytime: Boolean): Boolean {
            val darkCovered = daytime && meanLuma < DARK_LUMA && detail < 2 * LOW_DETAIL
            val uniform = lumaStd < UNIFORM_STD && meanLuma >= 40f
            val featureless = daytime && detail < LOW_DETAIL
            return darkCovered || uniform || featureless
        }

        /** Image y (0 top, 1 bottom) of the horizon for a level camera pitched [pitchRad] down. */
        fun horizonY(pitchRad: Float, vFovDeg: Float): Float {
            val tanHalfV = tan(Math.toRadians(vFovDeg / 2.0)).toFloat().coerceAtLeast(0.05f)
            return 0.5f - tan(pitchRad) / (2f * tanHalfV)
        }

        /**
         * While the camera is blocked, forward-collision warnings are suspended: FCW is cleared, its
         * events dropped (no sound), and the master alert re-derived from the remaining features.
         */
        fun suppressFcw(r: FusionResult): FusionResult {
            if (r.fcw == Severity.NONE && r.events.none { it.type == AlertType.FCW }) return r
            var top = r.topSeverity; var topType = r.topType; var topMsg = r.topMessage
            if (topType == AlertType.FCW) {
                top = Severity.NONE; topType = null; topMsg = null
                fun consider(s: Severity, t: AlertType, m: String) {
                    if (s.ordinal > top.ordinal && s.ordinal >= Severity.CAUTION.ordinal) { top = s; topType = t; topMsg = m }
                }
                consider(r.vru, AlertType.VRU, if (r.vru == Severity.CRITICAL) "PEDESTRIAN / RIDER" else "ROAD USER AHEAD")
                consider(r.animal, AlertType.ANIMAL, "ANIMAL AHEAD")
                consider(r.headway, AlertType.HEADWAY, "KEEP DISTANCE")
                consider(r.ldw, AlertType.LDW, "LANE DEPARTURE")
                consider(r.overspeed, AlertType.OVERSPEED, "SPEED LIMIT ${r.speedLimitKph ?: ""}")
            }
            return r.copy(
                events = r.events.filter { it.type != AlertType.FCW },
                fcw = Severity.NONE, ttcSec = null,
                topSeverity = top, topType = topType, topMessage = topMsg,
            )
        }
    }
}
