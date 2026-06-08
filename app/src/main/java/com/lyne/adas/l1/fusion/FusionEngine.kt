package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraIntrinsics
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.FeatureFlags
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.ObjectClass
import com.lyne.adas.l1.inference.Perception

/**
 * Turns per-frame [Perception] + sensor state into warnings. Stateful across frames for TTC, lane
 * offset rate, sign staleness and alert debouncing. Hard (CRITICAL) alerts are gated by distance
 * confidence and a frame debounce so a single noisy frame cannot trigger one.
 */
class FusionEngine(
    private val config: AdasConfig,
    private val features: FeatureFlags,
) {
    private val tracker = ObjectTracker()
    private val ldw = LaneDepartureLogic(config)
    private val overspeed = OverspeedMonitor(config)
    private val debounce = HashMap<AlertType, Int>()

    fun update(
        p: Perception,
        speedMps: Float,
        yawRate: Float,
        intrinsics: CameraIntrinsics,
        distanceScale: Float,
        nowMs: Long,
    ): FusionResult {
        val speedKph = if (speedMps.isNaN()) Float.NaN else speedMps * 3.6f

        // Multi-object tracking: stable IDs so per-object TTC/closing-speed stop jittering.
        val tracks = tracker.update(p.detections, p.timestampNs)

        // Adaptive sensitivity: warn earlier at higher speed (longer stopping distance).
        var ttcCaution = config.ttcCautionSec
        var ttcCritical = config.ttcCriticalSec
        var hwWarn = config.headwayWarnSec
        var hwCrit = config.headwayCriticalSec
        if (!speedKph.isNaN()) {
            val add = when { speedKph > 70f -> 0.6f; speedKph > 40f -> 0.3f; else -> 0f }
            ttcCaution += add; ttcCritical += add * 0.5f
            if (speedKph > 70f) hwWarn += 0.3f
        }

        // --- Lead vehicle / FCW / headway (per tracked vehicle) ---
        var leadBox: BBox? = null
        var leadDist: Float? = null
        var leadConf: Float? = null
        var ttcSec: Float? = null
        var headwaySec: Float? = null
        var fcwSev = Severity.NONE
        var headwaySev = Severity.NONE

        val lead = tracks
            .filter { it.cls.isVehicle && PathIntrusion.inEgoPath(it.box, config) }
            .maxByOrNull { it.box.area }

        if (lead != null && (features.fcw || features.headway)) {
            val est = DistanceEstimator.estimate(Detection(lead.cls, lead.score, lead.box), intrinsics, distanceScale)
            leadBox = lead.box; leadDist = est.distanceM

            // Two independent TTC estimates, fused: box-scale growth and distance closing-rate.
            val scaleT = lead.scaleTtc()
            val distT = lead.updateDistance(est.distanceM, p.timestampNs)
            val (fused, ttcConf) = fuseTtc(scaleT, distT)
            ttcSec = if (fused.isNaN()) null else fused
            leadConf = (est.confidence * (0.5f + 0.5f * ttcConf)).coerceIn(0f, 1f)

            val hw = HeadwayMonitor.headwaySec(est.distanceM, speedMps)
            headwaySec = if (hw.isNaN()) null else hw

            if (features.fcw && ttcSec != null) {
                val hardOk = leadConf!! >= config.minDistanceConfidence
                fcwSev = when {
                    ttcSec < ttcCritical -> if (hardOk) Severity.CRITICAL else Severity.CAUTION
                    ttcSec < ttcCaution -> Severity.CAUTION
                    else -> Severity.NONE
                }
            }
            if (features.headway) headwaySev = when {
                hw.isNaN() -> Severity.NONE
                hw < hwCrit -> Severity.CRITICAL
                hw < hwWarn -> Severity.CAUTION
                else -> Severity.NONE
            }
        }

        // --- VRU ---
        var vruSev = Severity.NONE
        var vruBox: BBox? = null
        if (features.vru) {
            for (d in p.detections) {
                if (!d.cls.isVru || !PathIntrusion.inEgoPath(d.box, config)) continue
                val est = DistanceEstimator.estimate(d, intrinsics, distanceScale)
                val s = PathIntrusion.vruSeverity(est.distanceM, d.box)
                if (s.ordinal > vruSev.ordinal) { vruSev = s; vruBox = d.box }
            }
        }

        // --- LDW ---
        var ldwSev = Severity.NONE
        var laneOffset: Float? = null
        if (features.ldw) {
            val r = ldw.update(p.lane, yawRate, laneCurvature(p.lane), p.timestampNs)
            ldwSev = r.severity
            laneOffset = if (p.lane.confidence >= config.ldwMinLaneConfidence) r.offset else null
        }

        // --- Overspeed ---
        var overspeedSev = Severity.NONE
        var speedLimit: Int? = null
        if (features.tsr) {
            if (p.ranSignThisFrame) overspeed.onSign(p.sign, nowMs)
            val (s, lim) = overspeed.evaluate(speedKph, nowMs)
            overspeedSev = s; speedLimit = lim
        }

        // --- Stop sign (detected by the object model; COCO 'stop sign') ---
        var stopSev = Severity.NONE
        var stopBox: BBox? = null
        if (features.tsr) {
            val stop = p.detections.filter { it.cls == ObjectClass.STOP_SIGN }.maxByOrNull { it.box.area }
            if (stop != null && stop.box.area > 0.004f) {
                stopSev = if (stop.box.area > 0.02f) Severity.CRITICAL else Severity.CAUTION
                stopBox = stop.box
            }
        }

        // --- Events (debounced) ---
        val events = ArrayList<AdasEvent>(5)
        maybeEvent(events, AlertType.FCW, fcwSev, nowMs) {
            AdasEvent(AlertType.FCW, fcwSev, nowMs, leadConf ?: 0f, "COLLISION ${fmt(ttcSec)}s",
                ttcSec = ttcSec, distanceM = leadDist, distanceConfidence = leadConf, bbox = leadBox)
        }
        maybeEvent(events, AlertType.HEADWAY, headwaySev, nowMs) {
            AdasEvent(AlertType.HEADWAY, headwaySev, nowMs, 0.8f, "TAILGATING ${fmt(headwaySec)}s",
                headwaySec = headwaySec, distanceM = leadDist, bbox = leadBox, speedKph = speedKph)
        }
        maybeEvent(events, AlertType.VRU, vruSev, nowMs) {
            AdasEvent(AlertType.VRU, vruSev, nowMs, 0.85f, "ROAD USER AHEAD", bbox = vruBox)
        }
        maybeEvent(events, AlertType.LDW, ldwSev, nowMs) {
            AdasEvent(AlertType.LDW, ldwSev, nowMs, p.lane.confidence, "LANE DEPARTURE", laneOffset = laneOffset)
        }
        maybeEvent(events, AlertType.OVERSPEED, overspeedSev, nowMs) {
            AdasEvent(AlertType.OVERSPEED, overspeedSev, nowMs, 0.9f, "OVERSPEED ${fmt(speedKph)}/$speedLimit",
                speedKph = speedKph, speedLimitKph = speedLimit)
        }
        maybeEvent(events, AlertType.STOP, stopSev, nowMs) {
            AdasEvent(AlertType.STOP, stopSev, nowMs, 0.85f, "STOP SIGN", bbox = stopBox)
        }

        // --- Top severity for the master alert ---
        var top = Severity.NONE; var topType: AlertType? = null; var topMsg: String? = null
        for (e in events) if (e.severity.ordinal > top.ordinal) { top = e.severity; topType = e.type; topMsg = e.message }

        return FusionResult(
            frameId = p.frameId,
            events = events,
            detections = p.detections,
            lane = p.lane,
            leadBox = leadBox,
            leadDistanceM = leadDist,
            leadDistanceConf = leadConf,
            ttcSec = ttcSec,
            headwaySec = headwaySec,
            speedKph = if (speedKph.isNaN()) null else speedKph,
            speedLimitKph = speedLimit,
            laneOffset = laneOffset,
            fcw = fcwSev, vru = vruSev, ldw = ldwSev, headway = headwaySev, overspeed = overspeedSev,
            topSeverity = top, topType = topType, topMessage = topMsg,
        )
    }

    private inline fun maybeEvent(out: MutableList<AdasEvent>, type: AlertType, sev: Severity, nowMs: Long, build: () -> AdasEvent) {
        if (sev.ordinal >= Severity.CAUTION.ordinal) {
            val c = (debounce[type] ?: 0) + 1
            debounce[type] = c
            if (c >= config.alertDebounceFrames) out.add(build())
        } else {
            debounce[type] = 0
        }
    }

    private fun fmt(v: Float?): String = if (v == null || v.isNaN()) "--" else String.format("%.1f", v)

    /** Fuse scale-based and distance-based TTC. Agreement => take the sooner one at high confidence. */
    private fun fuseTtc(scale: Float, dist: Float): Pair<Float, Float> {
        val s = scale; val d = dist
        return when {
            s.isNaN() && d.isNaN() -> Float.NaN to 0f
            s.isNaN() -> d to 0.55f
            d.isNaN() -> s to 0.7f
            else -> {
                val hi = maxOf(s, d); val lo = maxOf(minOf(s, d), 0.01f)
                if (hi / lo < 1.6f) minOf(s, d) to 1.0f else s to 0.6f
            }
        }
    }

    /** Lane centre shift from far to near rows, a proxy for road curvature (normalized units). */
    private fun laneCurvature(lane: com.lyne.adas.l1.inference.LaneResult): Float {
        val l = lane.leftLaneX ?: return 0f
        val r = lane.rightLaneX ?: return 0f
        var farC = Float.NaN; var nearC = Float.NaN
        for (i in lane.rows.indices) {
            val lx = l.getOrNull(i); val rx = r.getOrNull(i)
            if (lx == null || rx == null || lx.isNaN() || rx.isNaN()) continue
            val c = (lx + rx) * 0.5f
            if (farC.isNaN()) farC = c
            nearC = c
        }
        return if (!farC.isNaN() && !nearC.isNaN()) farC - nearC else 0f
    }

    fun reset() { tracker.reset(); debounce.clear() }
}
