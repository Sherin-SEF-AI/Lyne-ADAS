package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.FeatureFlags
import com.lyne.adas.l1.inference.ObjectClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccuracyTest {

    /** Following a car at a steady 30 m on the highway for 2 minutes: zero warnings allowed. */
    @Test fun steadyFollowingProducesNoFalseWarnings() {
        for (fps in listOf(8f, 15f, 30f)) {
            val proj = Projector(seed = fps.toLong())
            val lead = SimObject(z = 30f, x = 0.2f, width = 1.85f, height = 1.5f)
            val legacy = LegacyFcw()
            var legacyEvents = 0
            var frame = 0
            val events = runSim(proj, listOf(lead), 120f, fps, step = { _, _ -> 25f }) { t, _ ->
                if (legacy.update(proj.detect(listOf(lead)), t)) legacyEvents++
                frame++
            }
            val fcw = events.count { it.type == AlertType.FCW }
            println("steady 30m @${fps}fps: v2 FCW events=$fcw  v1 FCW events=$legacyEvents")
            assertEquals("false FCW at $fps fps", 0, fcw)
        }
    }

    /** Stop-and-go creeping at 1-2 m: no FCW below 10 km/h. */
    @Test fun creepingTrafficIsSilent() {
        val proj = Projector(seed = 7)
        val lead = SimObject(z = 4f, x = 0f, width = 1.8f, height = 1.5f)
        val events = runSim(proj, listOf(lead), 30f, 15f, step = { dt, t ->
            lead.z = 4f + kotlin.math.sin(t * 0.7f) * 1.5f; 1.5f
        })
        assertEquals(0, events.count { it.type == AlertType.FCW })
    }

    /** Lead brakes hard (6 m/s^2) in front of us at 80 km/h, over several noise seeds. */
    @Test fun hardBrakingLeadEscalatesInTime() {
        val crit = ArrayList<Float>(); val caut = ArrayList<Float>()
        for (seed in 1L..8L) {
            val proj = Projector(seed = seed)
            val lead = SimObject(z = 35f, x = 0f, width = 1.8f, height = 1.45f)
            val ego = 22f; var leadV = 22f
            var critAt = Float.NaN; var cautAt = Float.NaN
            runSim(proj, listOf(lead), 8f, 15f, step = { dt, t ->
                if (t > 1.5f) leadV = (leadV - 6f * dt).coerceAtLeast(0f)
                lead.z -= (ego - leadV) * dt
                ego
            }) { _, r ->
                val realTtc = if (ego > leadV) lead.z / (ego - leadV) else Float.NaN
                if (cautAt.isNaN() && r.fcw.ordinal >= Severity.CAUTION.ordinal) cautAt = realTtc
                if (critAt.isNaN() && r.fcw == Severity.CRITICAL) critAt = realTtc
            }
            crit += critAt; caut += cautAt
        }
        println("hard brake (8 seeds): caution at real TTC=$caut")
        println("hard brake (8 seeds): critical at real TTC=$crit")
        assertTrue("critical must always fire before impact", crit.all { !it.isNaN() && it > 0.5f })
        assertTrue("median caution should give >1.2 s", caut.sorted()[caut.size / 2] > 1.2f)
    }

    /** Gentle approach to a slower car (closing 5 m/s from 50 m): caution well ahead of time. */
    @Test fun gentleApproachWarnsEarly() {
        val proj = Projector(seed = 4)
        val lead = SimObject(z = 50f, x = 0f, width = 1.8f, height = 1.45f)
        var cautAt = Float.NaN
        runSim(proj, listOf(lead), 10f, 15f, step = { dt, _ -> lead.z -= 5f * dt; 20f }) { _, r ->
            if (cautAt.isNaN() && r.fcw.ordinal >= Severity.CAUTION.ordinal) cautAt = lead.z / 5f
        }
        println("gentle approach: caution at real TTC=$cautAt s")
        assertTrue(cautAt > 1.8f)
    }

    /** Parked car 4 m to the right while we approach at 15 m/s: not in path, no FCW. */
    @Test fun roadsideParkedCarIgnored() {
        val proj = Projector(seed = 11)
        val parked = SimObject(z = 60f, x = 3.6f, width = 1.8f, height = 1.5f)
        val events = runSim(proj, listOf(parked), 3.6f, 15f, step = { dt, _ -> parked.z -= 15f * dt; 15f })
        assertEquals(0, events.count { it.type == AlertType.FCW })
    }

    /**
     * Range error across a hatchback, sedan and pickup from 8 to 60 m, with realistic calibration
     * error in the app (height +5 cm, pitch +0.3 deg). Baseline = v1 width-only with the CORRECT FoV.
     */
    @Test fun rangeErrorBeatsWidthOnly() {
        val proj = Projector(seed = 5, hFovDeg = 69f, vFovDeg = 54f)
        val legacy = LegacyFcw(hFovDeg = 69f)
        var errV2 = 0.0; var errV1 = 0.0; var n = 0; var n1 = 0
        val sb = StringBuilder()
        for (w in listOf(1.68f, 1.82f, 2.03f)) for (z in listOf(8f, 12f, 18f, 25f, 35f, 45f, 60f)) {
            val car = SimObject(z = z, x = 0f, width = w, height = if (w > 2f) 1.9f else 1.45f)
            val engine = FusionEngine(AdasConfig.DEFAULT, FCW_ONLY, autoCalEnabled = false)
            val geom = proj.geometry(heightGuess = proj.camHeight + 0.05f, pitchGuess = proj.pitchRad + 0.005f)
            var last: Float? = null
            runSim(proj, listOf(car), 3f, 15f, engine, geom, step = { _, _ -> 20f }) { t, r ->
                if (t > 1.5f) {
                    r.objects.firstOrNull()?.distanceM?.let { errV2 += abs(it - z); n++; last = it }
                    proj.detect(listOf(car)).firstOrNull()?.let { errV1 += abs(legacy.distance(it) - z); n1++ }
                }
            }
            sb.append("w=%.2f z=%.0f v2=%.1f | ".format(w, z, last ?: Float.NaN))
        }
        val maeV2 = errV2 / n; val maeV1 = errV1 / n1
        println(sb)
        println("range MAE: v2=%.2f m  v1=%.2f m".format(maeV2, maeV1))
        assertTrue("v2 should beat width-only (v2=$maeV2 v1=$maeV1)", maeV2 < maeV1 * 0.8)
    }

    /** Range jitter (frame-to-frame std-dev) at 30 m: what made v1's numbers dance. */
    @Test fun rangeJitterIsLow() {
        val proj = Projector(seed = 21)
        val car = SimObject(z = 30f, x = 0f, width = 1.8f, height = 1.5f)
        val legacy = LegacyFcw(hFovDeg = 66f)
        val v2 = ArrayList<Float>(); val v1 = ArrayList<Float>()
        runSim(proj, listOf(car), 20f, 15f, step = { _, _ -> 20f }) { t, r ->
            if (t > 2f) {
                r.objects.firstOrNull()?.distanceM?.let { v2 += it }
                proj.detect(listOf(car)).firstOrNull()?.let { v1 += legacy.distance(it) }
            }
        }
        fun sd(a: List<Float>): Double { val m = a.average(); return Math.sqrt(a.sumOf { (it - m) * (it - m).toDouble() } / a.size) }
        println("jitter @30m: v2 sd=%.2f m  v1 sd=%.2f m".format(sd(v2), sd(v1)))
        assertTrue(sd(v2) < sd(v1) / 3)
    }

    /** Auto-cal pulls a wrong configured camera height toward the truth from ordinary traffic. */
    @Test fun autoCalibrationCorrectsHeight() {
        val proj = Projector(seed = 9, camHeight = 1.45f)
        val engine = FusionEngine(AdasConfig.DEFAULT, FCW_ONLY, autoCalEnabled = true)
        val rnd = java.util.Random(1)
        var t0 = 0f
        repeat(40) {
            val car = SimObject(z = 8f + rnd.nextFloat() * 20f, x = 0f, width = 1.8f + (rnd.nextFloat() - 0.5f) * 0.3f, height = 1.5f)
            val geom = proj.geometry(heightGuess = 1.20f * engine.autoCal.scale)
            runSim(proj, listOf(car), 4f, 15f, engine, geom, step = { _, _ -> 15f })
            t0 += 4f
        }
        val learned = 1.20f * engine.autoCal.scale
        println("auto-cal: configured 1.20 m, true 1.45 m, learned %.2f m".format(learned))
        assertTrue(abs(learned - 1.45f) < 0.08f)
    }

    /** Passing a slower car in the adjacent lane (3.5 m over), closing 8 m/s: not our lane, no FCW. */
    @Test fun adjacentLaneOvertakeIsSilent() {
        val proj = Projector(seed = 13)
        val slow = SimObject(z = 45f, x = 3.5f, width = 1.8f, height = 1.5f)
        val events = runSim(proj, listOf(slow), 5f, 15f, step = { dt, _ -> slow.z -= 8f * dt; 25f })
        assertEquals(0, events.count { it.type == AlertType.FCW })
    }

    /** Rough road: +-0.4 deg mount bounce while following at 20 m. */
    @Test fun roughRoadBounceIsSilent() {
        val proj = Projector(seed = 17, bounceRad = 0.007f)
        val lead = SimObject(z = 20f, x = 0f, width = 1.8f, height = 1.5f)
        val events = runSim(proj, listOf(lead), 60f, 15f, step = { _, _ -> 15f })
        assertEquals(0, events.count { it.type == AlertType.FCW })
    }

    /**
     * 20-minute random traffic soak: leads that slowly vary their gap (never a real threat, true
     * TTC > 4 s) plus adjacent-lane traffic. Counts false FCW warnings for v2 and v1.
     */
    @Test fun randomTrafficSoak() {
        val proj = Projector(seed = 99, bounceRad = 0.003f)
        val rnd = java.util.Random(5)
        val lead = SimObject(z = 30f, x = 0f, width = 1.85f, height = 1.5f)
        val side = SimObject(z = 40f, x = -3.5f, width = 1.8f, height = 1.5f)
        var relV = 0f
        var minTrueTtc = Float.MAX_VALUE
        val legacy = LegacyFcw(hFovDeg = 66f)
        var v1 = 0; var v1Prev = false
        val events = runSim(proj, listOf(lead, side), 1200f, 15f, step = { dt, _ ->
            relV = (relV + (rnd.nextFloat() - 0.5f) * 0.6f).coerceIn(-1.2f, 1.2f)
            if (lead.z < 15f) relV = -kotlin.math.abs(relV); if (lead.z > 45f) relV = kotlin.math.abs(relV)
            lead.z -= relV * dt
            if (relV > 0f) minTrueTtc = minOf(minTrueTtc, lead.z / relV)
            side.z -= 3f * dt; if (side.z < 3f) side.z = 60f
            22f
        }) { t, _ ->
            val a = legacy.update(proj.detect(listOf(lead, side)), t)
            if (a && !v1Prev) v1++
            v1Prev = a
        }
        val fcw = events.count { it.type == AlertType.FCW }
        println("20-min soak: min true TTC=%.1f s, v2 FCW warnings=$fcw, v1 FCW warnings=$v1".format(minTrueTtc))
        assertTrue(minTrueTtc > 4f)
        assertEquals(0, fcw)
    }

    /** Budget-phone frame rate: a car overtaking us across the frame must keep ONE track id. */
    @Test fun trackSurvivesFastLateralMotionAt7fps() {
        val proj = Projector(seed = 31)
        val car = SimObject(z = 12f, x = -5f, width = 1.8f, height = 1.5f)
        val ids = HashSet<Int>()
        runSim(proj, listOf(car), 3f, 7f, step = { dt, _ -> car.x += 3.5f * dt; car.z += 2f * dt; 20f }) { _, r ->
            r.objects.forEach { ids += it.id }
        }
        println("overtaking car at 7 fps: track ids used = ${ids.size}")
        assertTrue("expected 1 track id, got ${ids.size}", ids.size <= 1)
    }

    /**
     * Scenario coverage (from the video bench test): the detector reports one truck as BOTH truck and
     * bus. FCW must still fire. Note: this simulation does not reproduce the on-device lead flipping
     * (that needed track churn); class-group NMS, duplicate-track merging and same-vehicle evidence
     * keeping address it on device.
     */
    @Test fun duplicateClassDetectionsStillWarn() {
        val proj = Projector(seed = 8)
        val truck = SimObject(z = 30f, x = 0f, width = 2.4f, height = 3f, cls = ObjectClass.TRUCK)
        val engine = FusionEngine(AdasConfig.DEFAULT, FCW_ONLY, autoCalEnabled = false)
        var leadV = 20f; var t = 0f; var frame = 0L; var cautionAt = Float.NaN
        val rnd = java.util.Random(3)
        while (t < 6f) {
            val dets = proj.detect(listOf(truck)).flatMap { d ->
                // Same box, second class, slightly jittered: what YOLO does on big vehicles.
                val j = 0.004f
                listOf(d, d.copy(cls = ObjectClass.BUS, score = d.score * (0.8f + 0.3f * rnd.nextFloat()),
                    box = com.lyne.adas.l1.inference.BBox(d.box.left + j, d.box.top, d.box.right - j, d.box.bottom)))
            }
            val r = engine.update(perception(frame++, (t * 1e9).toLong(), dets), 20f, 0f, proj.geometry(), proj.inputPx, (t * 1000).toLong())
            if (cautionAt.isNaN() && r.fcw.ordinal >= Severity.CAUTION.ordinal) cautionAt = truck.z / (20f - leadV)
            val dt = 1f / 10f
            if (t > 1f) leadV = (leadV - 5f * dt).coerceAtLeast(0f)
            truck.z -= (20f - leadV) * dt
            t += dt
        }
        println("duplicate truck/bus detections: caution at real TTC=$cautionAt s")
        assertTrue("FCW must fire despite duplicate-class detections", !cautionAt.isNaN() && cautionAt > 0.8f)
    }

    @Test fun pedestrianWhileStoppedIsNotAnAlarm() {
        val proj = Projector(seed = 2)
        val ped = SimObject(z = 6f, x = 0f, width = 0.5f, height = 1.7f, cls = ObjectClass.PEDESTRIAN)
        val engine = FusionEngine(AdasConfig.DEFAULT, FeatureFlags(fcw = false, vru = true, ldw = false, headway = false, tsr = false), autoCalEnabled = false)
        val events = runSim(proj, listOf(ped), 10f, 15f, engine, step = { dt, _ -> ped.x = (ped.x + 1.2f * dt); 0f })
        assertEquals(0, events.count { it.type == AlertType.VRU })
    }
}
