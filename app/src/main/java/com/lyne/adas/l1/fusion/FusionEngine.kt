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
    private val ttc = TtcEstimator()
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

        // --- Lead vehicle / FCW / headway ---
        var leadBox: BBox? = null
        var leadDist: Float? = null
        var leadConf: Float? = null
        var ttcSec: Float? = null
        var headwaySec: Float? = null
        var fcwSev = Severity.NONE
        var headwaySev = Severity.NONE

        val lead = p.detections
            .filter { it.cls.isVehicle && PathIntrusion.inEgoPath(it.box, config) }
            .maxByOrNull { it.box.area }

        if (lead != null && (features.fcw || features.headway)) {
            val est = DistanceEstimator.estimate(lead, intrinsics, distanceScale)
            leadBox = lead.box; leadDist = est.distanceM; leadConf = est.confidence
            val t = ttc.update(lead.box.width, p.timestampNs)
            ttcSec = if (t.isNaN()) null else t
            val hw = HeadwayMonitor.headwaySec(est.distanceM, speedMps)
            headwaySec = if (hw.isNaN()) null else hw

            if (features.fcw && ttcSec != null) {
                fcwSev = when {
                    ttcSec < config.ttcCriticalSec -> if (est.confidence >= config.minDistanceConfidence) Severity.CRITICAL else Severity.CAUTION
                    ttcSec < config.ttcCautionSec -> Severity.CAUTION
                    else -> Severity.NONE
                }
            }
            if (features.headway) headwaySev = HeadwayMonitor.severity(hw, config)
        } else {
            ttc.reset()
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
            val r = ldw.update(p.lane, yawRate, p.timestampNs)
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

    fun reset() { ttc.reset(); debounce.clear() }
}
