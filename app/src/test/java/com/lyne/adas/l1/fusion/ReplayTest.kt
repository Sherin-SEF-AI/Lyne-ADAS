package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraGeometry
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.FeatureFlags
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.ObjectClass
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan
import kotlin.math.tan

/**
 * Real-drive regression: replays detector output recorded from road footage (tools/replay_drive.py)
 * through ObjectTracker + FusionEngine and checks invariants that must hold on any real drive.
 * Each CSV under src/test/resources/replays/ is one drive; with none present the test is a no-op.
 *
 * Speed is not in the CSV (the footage has no GPS log), so a fixed urban profile is assumed.
 */
class ReplayTest {

    private class Replay(val name: String, val frameMs: Long, val frames: List<Pair<Long, List<Detection>>>, val aspect: Float)

    private fun replays(): List<Replay> {
        val dir = listOf(File("src/test/resources/replays"), File("app/src/test/resources/replays")).firstOrNull { it.isDirectory }
            ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }.map(::parse)
    }

    private fun parse(f: File): Replay {
        val lines = f.readLines()
        val header = lines.first { it.startsWith("#") }
        val kv = Regex("(\\w+)=(\\S+)").findAll(header).associate { it.groupValues[1] to it.groupValues[2] }
        val frameMs = kv.getValue("frame_ms").toLong()
        val spanMs = ((kv.getValue("end_s").toDouble() - kv.getValue("start_s").toDouble()) * 1000).toLong()
        val byT = HashMap<Long, MutableList<Detection>>()
        for (ln in lines) {
            if (ln.startsWith("#") || ln.startsWith("t_ms")) continue
            val c = ln.split(',')
            val cls = classOf(c[1]) ?: continue
            byT.getOrPut(c[0].toLong()) { ArrayList() } +=
                Detection(cls, c[2].toFloat(), BBox(c[3].toFloat(), c[4].toFloat(), c[5].toFloat(), c[6].toFloat()))
        }
        val frames = (0 until spanMs / frameMs).map { k -> (k * frameMs).let { t -> t to (byT[t] ?: emptyList()) } }
        val aspect = kv["aspect"]?.toFloat() ?: (16f / 9f)
        return Replay(f.nameWithoutExtension, frameMs, frames, aspect)
    }

    /** Same mapping idea as ObjectDetector; tolerant of classes this build does not have yet. */
    private fun classOf(label: String): ObjectClass? {
        fun named(n: String) = ObjectClass.entries.firstOrNull { it.name == n }
        return when (label) {
            "person" -> ObjectClass.PEDESTRIAN
            "bicycle" -> ObjectClass.BICYCLE
            "motorcycle" -> ObjectClass.MOTORCYCLE
            "car" -> ObjectClass.CAR
            "bus" -> ObjectClass.BUS
            "truck" -> ObjectClass.TRUCK
            "autorickshaw" -> ObjectClass.AUTORICKSHAW
            "stop sign" -> ObjectClass.STOP_SIGN
            "rider" -> named("RIDER") ?: ObjectClass.MOTORCYCLE
            "animal" -> named("ANIMAL")
            else -> null
        }
    }

    /** Plausible city-traffic speed (km/h): cruise, slow down, crawl, pick up again. Period 60 s. */
    private fun speedKph(tSec: Float): Float {
        val p = tSec % 60f
        return when {
            p < 20f -> 35f
            p < 30f -> 35f - (p - 20f) * 2.5f // down to 10
            p < 40f -> 10f
            else -> 10f + (p - 40f) * 1.25f   // back to 35
        }
    }

    @Test
    fun realDriveInvariants() {
        val all = replays()
        if (all.isEmpty()) { println("ReplayTest: no replay CSVs, skipped"); return }
        val failures = ArrayList<String>()
        for (r in all) failures += check(r)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(r: Replay): List<String> {
        // Phone camera ~66 deg horizontal; the footage's vertical FoV follows from its aspect ratio.
        val hFov = 66f
        val vFov = Math.toDegrees(2.0 * atan(tan(Math.toRadians(hFov / 2.0)) / r.aspect)).toFloat()
        val geom = CameraGeometry(hFov, vFov, 1.25f, 0.02f, 0f, 1f, 0.015f)
        val engine = FusionEngine(AdasConfig.DEFAULT, FeatureFlags.ALL, autoCalEnabled = false)

        val fails = ArrayList<String>()
        var coastLeadId = -1; var coastSinceMs = -1L; var maxCoastMs = 0L
        var noRangeSinceMs = -1L; var maxNoRangeMs = 0L
        var dashLeads = 0; var tooClose = 0; var fcwCritical = 0; var leadFrames = 0
        var minLeadAtSpeed = Float.MAX_VALUE

        for ((i, fr) in r.frames.withIndex()) {
            val (tMs, dets) = fr
            val tSec = tMs / 1000f
            val v = speedKph(tSec)
            val res = engine.update(perception(i.toLong(), tMs * 1_000_000L, dets), v / 3.6f, 0f, geom, 320, tMs)
            fcwCritical += res.events.count { it.type == AlertType.FCW && it.severity == Severity.CRITICAL }

            val lead = res.leadBox
            if (lead == null) { coastLeadId = -1; coastSinceMs = -1; noRangeSinceMs = -1; continue }
            leadFrames++
            // 1) The lead must be backed by a fresh detection at least every second.
            val fresh = dets.any { it.box.iou(lead) > 0.3f }
            if (fresh || res.leadId != coastLeadId) { coastLeadId = res.leadId ?: -1; coastSinceMs = tMs }
            maxCoastMs = maxOf(maxCoastMs, tMs - coastSinceMs)
            //    ... and must not be shown without a range for more than a second.
            if (res.leadDistanceM == null) { if (noRangeSinceMs < 0) noRangeSinceMs = tMs; maxNoRangeMs = maxOf(maxNoRangeMs, tMs - noRangeSinceMs) }
            else noRangeSinceMs = -1
            // 2) Nothing lying entirely in the bottom 20 % of the frame (dashboard, bonnet) is a lead.
            if (lead.top >= 0.8f) dashLeads++
            // 3) No sub-1.5 m lead while moving faster than 15 km/h.
            val d = res.leadDistanceM
            if (d != null && v > 15f) {
                minLeadAtSpeed = minOf(minLeadAtSpeed, d)
                if (d < 1.5f) tooClose++
            }
        }
        val minutes = r.frames.size * r.frameMs / 60_000f
        val critPerMin = fcwCritical / minutes
        println(
            "replay ${r.name}: ${r.frames.size} frames (${"%.1f".format(minutes)} min), lead in $leadFrames, " +
                "max lead coast ${maxCoastMs} ms, max lead without range ${maxNoRangeMs} ms, dashboard leads $dashLeads, " +
                "min lead >15 km/h ${"%.1f".format(minLeadAtSpeed)} m (<1.5 m: $tooClose), FCW critical $fcwCritical (${"%.2f".format(critPerMin)}/min)"
        )
        if (maxCoastMs > 1000) fails += "${r.name}: lead coasted ${maxCoastMs} ms without a matching detection"
        if (maxNoRangeMs > 1000) fails += "${r.name}: lead shown without range for ${maxNoRangeMs} ms"
        if (dashLeads > 0) fails += "${r.name}: $dashLeads frames with a lead box entirely in the bottom 20%"
        if (tooClose > 0) fails += "${r.name}: $tooClose frames with lead < 1.5 m above 15 km/h (min ${minLeadAtSpeed} m)"
        if (critPerMin > MAX_CRITICAL_PER_MIN) fails += "${r.name}: ${"%.2f".format(critPerMin)} FCW critical/min > $MAX_CRITICAL_PER_MIN"
        return fails
    }

    companion object {
        /** Ordinary city driving: well under one critical collision warning per minute. */
        const val MAX_CRITICAL_PER_MIN = 1.0f
    }
}
