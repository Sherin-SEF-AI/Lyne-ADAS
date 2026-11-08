package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraGeometry
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.FeatureFlags
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.LaneResult
import com.lyne.adas.l1.inference.ObjectClass
import com.lyne.adas.l1.inference.Perception
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns per-frame [Perception] + sensor state into warnings.
 *
 * Pipeline per frame: track -> per-track range Kalman + looming-rate fit -> metric lead selection on a
 * yaw-curved corridor -> fused, significance-tested TTC -> physical plausibility gates -> time-based
 * alert gates (onset/hysteresis/one-event-per-incident). Pure Kotlin: covered by JVM unit tests that
 * replay synthetic drives with realistic detector jitter.
 */
class FusionEngine(
    private val config: AdasConfig,
    private val features: FeatureFlags,
    initialHeightScale: Float = 1f,
    private val autoCalEnabled: Boolean = true,
) {
    private val tracker = ObjectTracker()
    private val ldw = LaneDepartureLogic(config)
    private val overspeed = OverspeedMonitor(config)
    val autoCal = HeightAutoCalibrator(initialHeightScale)

    private val hs = config.alertHoldScale
    private val fcwGate = AlertGate((300 * hs).toLong(), (200 * hs).toLong(), offMs = 600L)
    private val headwayGate = AlertGate((1800 * hs).toLong(), (900 * hs).toLong(), offMs = 1200L, rearmMs = 15_000L)
    private val vruGate = AlertGate((300 * hs).toLong(), (200 * hs).toLong())
    private val ldwGate = AlertGate((450 * hs).toLong(), (300 * hs).toLong(), rearmMs = 6000L)
    private val overspeedGate = AlertGate(2500L, 1500L, offMs = 2000L, rearmMs = 30_000L)
    private val stopGate = AlertGate(400L, 300L, rearmMs = 10_000L)

    private var leadId = -1

    // Stop-and-go ("traffic moving") state.
    private var stoppedSinceMs = 0L
    private var stopLeadId = -1
    private var stopLeadDist = Float.NaN
    private var goFired = false
    private var goSinceMs = 0L

    fun update(
        p: Perception,
        speedMps: Float,
        yawRate: Float,
        geom: CameraGeometry,
        inputPx: Int,
        nowMs: Long,
    ): FusionResult {
        val speedKph = if (speedMps.isNaN()) Float.NaN else speedMps * 3.6f
        val lowThr = config.objectScoreThreshold
        val tracks = tracker.update(p.detections, lowThr, p.timestampNs)
        val nowNs = p.timestampNs

        // Advance every track's filters; place it in metres relative to the predicted path.
        for (t in tracks) {
            t.step(nowNs, t.misses == 0, geom, inputPx)
            t.isLead = false
            val d = t.distanceM
            if (d.isNaN()) { t.inPath = false; t.lateralM = Float.NaN; continue }
            t.lateralM = geom.lateralM(t.box.cx, d)
            val half = (if (t.learnedWidthM.isNaN()) RangeEstimator.widthPrior(t.cls) else t.learnedWidthM) / 2f
            val center = PathIntrusion.pathCenterM(d, speedMps, yawRate)
            val ov = PathIntrusion.overlapM(t.lateralM, half, center, PathIntrusion.EGO_HALF_M)
            // Hysteresis: the current lead keeps its status until it clearly leaves the corridor.
            t.inPath = ov >= minOf(0.35f, half) || (t.id == leadId && ov > 0.05f)
            if (t.misses == 0 && autoCalEnabled) feedAutoCal(t, geom, speedMps, yawRate)
        }

        // Adaptive thresholds: warn earlier at speed (longer stopping distance).
        var ttcCaution = config.ttcCautionSec
        var ttcCritical = config.ttcCriticalSec
        var hwWarn = config.headwayWarnSec
        val hwCrit = config.headwayCriticalSec
        if (!speedKph.isNaN()) {
            val add = when { speedKph > 80f -> 0.6f; speedKph > 50f -> 0.3f; else -> 0f }
            ttcCaution += add; ttcCritical += add * 0.5f
            if (speedKph > 80f) hwWarn += 0.3f
        }

        // --- Lead vehicle: nearest confirmed vehicle in the corridor ---
        var lead: Track? = null
        for (t in tracks) {
            if (!t.cls.isVehicle || !t.confirmed || !t.inPath || t.misses > 2 || t.distanceM.isNaN()) continue
            if (lead == null || t.distanceM < lead.distanceM) lead = t
        }
        // Stickiness: don't hop to a marginally closer candidate.
        val prev = tracks.firstOrNull { it.id == leadId && it.inPath && it.misses <= 2 && !it.distanceM.isNaN() }
        if (prev != null && lead != null && prev !== lead && lead.distanceM > prev.distanceM - 3f) lead = prev
        val newLeadId = lead?.id ?: -1
        if (newLeadId != leadId) { fcwGate.resetEvidence(); headwayGate.resetEvidence() }
        leadId = newLeadId
        lead?.isLead = true

        var leadDist: Float? = null; var leadSigma: Float? = null; var leadConf: Float? = null
        var leadClosing: Float? = null; var ttcSec: Float? = null; var headwaySec: Float? = null
        var fcwRaw = Severity.NONE; var headwayRaw = Severity.NONE

        if (lead != null && (features.fcw || features.headway)) {
            val d = lead.distanceM
            val sd = lead.sigmaM
            leadDist = d; leadSigma = sd
            val relSigma = sd / d.coerceAtLeast(0.5f)
            leadConf = ((1f - (relSigma - 0.05f) / 0.35f).coerceIn(0f, 1f) * (0.6f + 0.4f * lead.score)).coerceIn(0f, 1f)
            if (lead.kalman.updates >= 3) leadClosing = lead.closingMps

            val ttc = fusedTtc(lead)
            val egoKnown = !speedMps.isNaN()
            val egoMoving = !egoKnown || speedMps >= MIN_FCW_SPEED_MPS
            if (ttc.valid) {
                val closing = ttc.lambda * d
                // Physical plausibility: the lead can't approach faster than we drive (+ margin for
                // a reversing/braking car and GPS lag). Violations are measurement noise.
                val plausible = !egoKnown || closing <= speedMps + 3f
                if (plausible && egoMoving) {
                    ttcSec = ttc.ttc
                    val mature = lead.ageSec(nowNs) >= 0.5f && lead.hits >= 4 && lead.misses <= 1
                    if (features.fcw && mature && closing >= 1f) {
                        val hardOk = leadConf >= config.minDistanceConfidence && ttc.z >= 3f && closing >= 2f
                        fcwRaw = when {
                            ttc.ttc < ttcCritical -> if (hardOk) Severity.CRITICAL else Severity.CAUTION
                            ttc.ttc < ttcCaution -> Severity.CAUTION
                            else -> Severity.NONE
                        }
                    }
                }
            }

            if (egoKnown && speedMps >= 2f) {
                val hw = d / speedMps
                headwaySec = hw
                if (features.headway && !config.videoTestMode && speedMps >= MIN_HEADWAY_SPEED_MPS && relSigma < 0.3f && lead.confirmed) {
                    headwayRaw = when {
                        hw < hwCrit -> Severity.CRITICAL
                        hw < hwWarn -> Severity.CAUTION
                        else -> Severity.NONE
                    }
                }
            }
        }

        // --- VRU ---
        var vruRaw = Severity.NONE
        var vruBox: BBox? = null
        var vruDist: Float? = null
        if (features.vru) {
            for (t in tracks) {
                if (!t.cls.isVru || !t.confirmed || t.misses > 1) continue
                val d = t.distanceM; if (d.isNaN() || t.lateralM.isNaN()) continue
                val center = PathIntrusion.pathCenterM(d, speedMps, yawRate)
                val half = RangeEstimator.widthPrior(t.cls) / 2f
                val inEgo = PathIntrusion.overlapM(t.lateralM, half, center, PathIntrusion.EGO_HALF_M + 0.3f) > 0f
                val inWide = PathIntrusion.overlapM(t.lateralM, half, center, PathIntrusion.EGO_HALF_M + 1.5f) > 0f
                val s = PathIntrusion.vruSeverity(d, speedMps, inEgo, inWide)
                if (s.ordinal > vruRaw.ordinal) { vruRaw = s; vruBox = t.box; vruDist = d }
            }
        }

        // --- LDW ---
        var ldwRaw = Severity.NONE
        var laneOffset: Float? = null
        if (features.ldw) {
            val r = ldw.update(p.lane, laneOffsetFor(p.lane, geom), yawRate, laneCurvature(p.lane), p.timestampNs)
            // Lane departure only means something while actually driving.
            ldwRaw = if (!speedMps.isNaN() && speedMps < MIN_LDW_SPEED_MPS) Severity.NONE else r.severity
            laneOffset = if (p.lane.confidence >= config.ldwMinLaneConfidence) r.offset else null
        }

        // --- Overspeed ---
        var overspeedRaw = Severity.NONE
        var speedLimit: Int? = null
        if (features.tsr) {
            if (p.ranSignThisFrame) overspeed.onSign(p.sign, nowMs)
            val (s, lim) = overspeed.evaluate(speedKph, nowMs)
            overspeedRaw = s; speedLimit = lim
        }

        // --- Stop sign (object model; confirmed track only) ---
        var stopRaw = Severity.NONE
        var stopBox: BBox? = null
        if (features.tsr) {
            var best: Track? = null
            for (t in tracks) if (t.cls == ObjectClass.STOP_SIGN && t.confirmed && t.misses == 0 &&
                (best == null || t.box.area > best.box.area)) best = t
            if (best != null && best.box.area > 0.003f && (speedMps.isNaN() || speedMps > 1.5f)) {
                stopRaw = if (best.box.area > 0.015f) Severity.CRITICAL else Severity.CAUTION
                stopBox = best.box
            }
        }

        // --- Gates + events (one per incident) ---
        val events = ArrayList<AdasEvent>(2)
        if (fcwGate.update(fcwRaw, nowMs)) events += AdasEvent(
            AlertType.FCW, fcwGate.active, nowMs, leadConf ?: 0f,
            if (fcwGate.active == Severity.CRITICAL) "BRAKE  ${fmt(ttcSec)}s" else "VEHICLE AHEAD  ${fmt(ttcSec)}s",
            ttcSec = ttcSec, distanceM = leadDist, distanceConfidence = leadConf, bbox = lead?.box,
            speedKph = speedKph.orNull(), distanceSigmaM = leadSigma, closingKph = leadClosing?.times(3.6f),
        )
        if (headwayGate.update(headwayRaw, nowMs)) events += AdasEvent(
            AlertType.HEADWAY, headwayGate.active, nowMs, leadConf ?: 0.5f, "KEEP DISTANCE  ${fmt(headwaySec)}s",
            headwaySec = headwaySec, distanceM = leadDist, bbox = lead?.box, speedKph = speedKph.orNull(),
            distanceSigmaM = leadSigma,
        )
        if (vruGate.update(vruRaw, nowMs)) events += AdasEvent(
            AlertType.VRU, vruGate.active, nowMs, 0.85f,
            if (vruGate.active == Severity.CRITICAL) "PEDESTRIAN / RIDER" else "ROAD USER AHEAD",
            bbox = vruBox, distanceM = vruDist, speedKph = speedKph.orNull(),
        )
        if (ldwGate.update(ldwRaw, nowMs)) events += AdasEvent(
            AlertType.LDW, ldwGate.active, nowMs, p.lane.confidence, "LANE DEPARTURE", laneOffset = laneOffset,
            speedKph = speedKph.orNull(),
        )
        if (overspeedGate.update(overspeedRaw, nowMs)) events += AdasEvent(
            AlertType.OVERSPEED, overspeedGate.active, nowMs, 0.9f, "SPEED LIMIT $speedLimit",
            speedKph = speedKph.orNull(), speedLimitKph = speedLimit,
        )
        if (stopGate.update(stopRaw, nowMs)) events += AdasEvent(
            AlertType.STOP, stopGate.active, nowMs, 0.85f, "STOP SIGN", bbox = stopBox,
        )
        leadGo(lead, speedMps, nowMs)?.let { events += it }

        val fcw = fcwGate.active; val headway = headwayGate.active; val vru = maxSev(vruGate.active, vruRaw.infoOnly())
        val ldwSev = ldwGate.active; val ovs = overspeedGate.active; val stop = stopGate.active

        // Master alert = highest active severity (FCW wins ties: it is the most urgent).
        var top = Severity.NONE; var topType: AlertType? = null; var topMsg: String? = null
        fun consider(s: Severity, type: AlertType, msg: String) {
            if (s.ordinal > top.ordinal && s.ordinal >= Severity.CAUTION.ordinal) { top = s; topType = type; topMsg = msg }
        }
        consider(fcw, AlertType.FCW, if (fcw == Severity.CRITICAL) "BRAKE" else "VEHICLE AHEAD")
        consider(vru, AlertType.VRU, if (vru == Severity.CRITICAL) "PEDESTRIAN / RIDER" else "ROAD USER AHEAD")
        consider(headway, AlertType.HEADWAY, "KEEP DISTANCE")
        consider(stop, AlertType.STOP, "STOP SIGN")
        consider(ldwSev, AlertType.LDW, "LANE DEPARTURE")
        consider(ovs, AlertType.OVERSPEED, "SPEED LIMIT ${speedLimit ?: ""}")
        if (top == Severity.NONE) events.firstOrNull { it.type == AlertType.LEAD_GO }?.let {
            top = Severity.INFO; topType = AlertType.LEAD_GO; topMsg = it.message
        }

        val objects = ArrayList<TrackedObject>(tracks.size)
        for (t in tracks) {
            if (!t.confirmed || t.misses > 2 || t.cls == ObjectClass.UNKNOWN) continue
            val d = t.distanceM
            objects += TrackedObject(
                t.id, t.cls, t.box,
                if (d.isNaN()) null else d,
                if (d.isNaN()) null else t.sigmaM,
                if (t.kalman.updates >= 3) t.closingMps else null,
                t.inPath, t.isLead,
            )
        }

        return FusionResult(
            frameId = p.frameId,
            events = events,
            detections = p.detections,
            objects = objects,
            lane = p.lane,
            leadBox = lead?.box,
            leadId = lead?.id,
            leadDistanceM = leadDist,
            leadSigmaM = leadSigma,
            leadDistanceConf = leadConf,
            leadClosingMps = leadClosing,
            ttcSec = ttcSec,
            headwaySec = headwaySec,
            speedKph = speedKph.orNull(),
            speedLimitKph = speedLimit,
            laneOffset = laneOffset,
            fcw = fcw, vru = vru, ldw = ldwSev, headway = headway, overspeed = ovs,
            topSeverity = top, topType = topType, topMessage = topMsg,
            horizonLeftY = geom.horizonY(0f), horizonRightY = geom.horizonY(1f),
            heightScale = autoCal.scale, autoCalConfidence = autoCal.confidence,
        )
    }

    private class Ttc(val valid: Boolean, val ttc: Float, val lambda: Float, val z: Float)
    private val noTtc = Ttc(false, Float.NaN, 0f, 0f)

    /**
     * Fuse two independent closing-rate measurements in inverse-TTC space (lambda = 1/TTC), where
     * noise is roughly Gaussian: the looming-rate regression and the Kalman range-rate. Only a lambda
     * at least 2 sigma above zero counts as "closing".
     */
    private fun fusedTtc(t: Track): Ttc {
        val sr = t.scaleRate
        var wSum = 0f; var lSum = 0f
        // Best single-cue evidence: a strongly significant cue is not diluted by a lagging one
        // (e.g. the CV Kalman when the lead starts braking hard).
        var single = 0f; var singleZ = 0f
        if (!sr.rate.isNaN() && sr.rateSigma > 0f) {
            val w = 1f / (sr.rateSigma * sr.rateSigma); wSum += w; lSum += w * sr.rate
            val zz = sr.rate / sr.rateSigma
            if (zz >= 2.5f && sr.rate > single) { single = sr.rate; singleZ = zz }
        }
        val k = t.kalman
        if (k.updates >= 4) {
            val lr = k.loomRate(); val s = k.loomSigma()
            if (!lr.isNaN() && s > 0f) {
                val w = 1f / (s * s); wSum += w; lSum += w * lr
                val zz = lr / s
                if (zz >= 2.5f && lr > single) { single = lr; singleZ = zz }
            }
        }
        if (wSum <= 0f) return noTtc
        var lambda = lSum / wSum
        var z = lambda / sqrt(1f / wSum)
        if (single > lambda) { lambda = single; z = singleZ }
        if (lambda <= 0.02f || z < 2f) return noTtc
        return Ttc(true, (1f / lambda).coerceIn(0f, 30f), lambda, z)
    }

    private fun feedAutoCal(t: Track, geom: CameraGeometry, speedMps: Float, yawRate: Float) {
        if (t.cls != ObjectClass.CAR || !t.confirmed || abs(yawRate) > 0.05f) return
        if (!speedMps.isNaN() && speedMps < 4f) return
        val m = t.meas
        val g = m.groundM
        if (g.isNaN() || g < 6f || g > 30f || m.groundRel > 0.12f) return
        val b = t.rawBox
        if (b.left <= 0.004f || b.right >= 0.996f || abs(b.cx - 0.5f) > 0.3f) return
        val priorD = geom.widthDistance(b.width, RangeEstimator.widthPrior(ObjectClass.CAR))
        autoCal.add(priorD / g)
    }

    /** "Traffic moving": we've been stopped behind a lead that has now pulled away. */
    private fun leadGo(lead: Track?, speedMps: Float, nowMs: Long): AdasEvent? {
        if (speedMps.isNaN() || speedMps > 0.8f) {
            stoppedSinceMs = 0L; stopLeadId = -1; goFired = false; goSinceMs = 0L
            return null
        }
        if (stoppedSinceMs == 0L) stoppedSinceMs = nowMs
        if (lead == null || lead.distanceM.isNaN()) return null
        if (stopLeadId != lead.id) {
            if (lead.distanceM < 15f) { stopLeadId = lead.id; stopLeadDist = lead.distanceM }
            goSinceMs = 0L
            return null
        }
        if (goFired || nowMs - stoppedSinceMs < 2500L) return null
        val moving = lead.distanceM > stopLeadDist + 2.5f && lead.closingMps < -0.6f
        if (!moving) { goSinceMs = 0L; return null }
        if (goSinceMs == 0L) goSinceMs = nowMs
        if (nowMs - goSinceMs < 500L) return null
        goFired = true
        return AdasEvent(AlertType.LEAD_GO, Severity.INFO, nowMs, 0.8f, "TRAFFIC MOVING", distanceM = lead.distanceM, bbox = lead.box)
    }

    private fun fmt(v: Float?): String = if (v == null || v.isNaN()) "--" else String.format("%.1f", v)
    private fun Float.orNull(): Float? = if (isNaN()) null else this
    private fun Severity.infoOnly(): Severity = if (this == Severity.INFO) Severity.INFO else Severity.NONE
    private fun maxSev(a: Severity, b: Severity) = if (a.ordinal >= b.ordinal) a else b

    /**
     * Lateral position for LDW. The drivable-area model outlines the whole ROAD, not the ego lane. On a
     * single-lane-wide surface (<= ~4.6 m) the road centre is the lane centre, so the classic offset works.
     * On a wider road, driving in the left or right lane is normal, so only the clearance to the road
     * EDGE matters: we map "0.9 m of clearance .. touching the edge" onto offset 0 .. 1 (road-edge
     * departure warning). Uses the nearest row at 5-15 m, converted to metres with the camera geometry.
     */
    private fun laneOffsetFor(lane: LaneResult, geom: CameraGeometry): Float {
        val l = lane.leftLaneX ?: return lane.centerOffset
        val r = lane.rightLaneX ?: return lane.centerOffset
        for (i in lane.rows.indices.reversed()) {
            val lx = l.getOrNull(i) ?: continue; val rx = r.getOrNull(i) ?: continue
            if (lx.isNaN() || rx.isNaN()) continue
            val d = geom.groundDistance(0.5f, lane.rows[i])
            if (d.isNaN() || d < 5f) continue
            if (d > 15f) break
            val leftM = geom.lateralM(lx, d); val rightM = geom.lateralM(rx, d)
            val width = rightM - leftM
            if (width < 2f) return 0f              // implausible: let confidence gating handle it
            if (width <= 4.6f) return lane.centerOffset
            val leftClear = -PathIntrusion.EGO_HALF_M - leftM
            val rightClear = rightM - PathIntrusion.EGO_HALF_M
            return if (rightClear < leftClear) (1f - rightClear / 0.9f).coerceAtMost(1.5f).coerceAtLeast(0f)
            else -(1f - leftClear / 0.9f).coerceAtMost(1.5f).coerceAtLeast(0f)
        }
        return lane.centerOffset
    }

    /** Lane centre shift from far to near rows, a proxy for road curvature (normalized units). */
    private fun laneCurvature(lane: LaneResult): Float {
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

    fun reset() {
        tracker.reset(); leadId = -1
        fcwGate.reset(); headwayGate.reset(); vruGate.reset(); ldwGate.reset(); overspeedGate.reset(); stopGate.reset()
    }

    companion object {
        /** Below ~10 km/h FCW is off (creeping traffic), like production systems. */
        const val MIN_FCW_SPEED_MPS = 2.8f
        /** Tailgating warnings only above ~30 km/h. */
        const val MIN_HEADWAY_SPEED_MPS = 8.3f
        const val MIN_LDW_SPEED_MPS = 11f
    }
}
