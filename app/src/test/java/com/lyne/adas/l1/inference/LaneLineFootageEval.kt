package com.lyne.adas.l1.inference

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * Opt-in evaluation of [LaneLineCore] on recorded drive footage (skipped unless LYNE_FOOTAGE is set).
 * LYNE_FOOTAGE = comma-separated .bin files: big-endian int n, w, h, then n x (float seconds, w*h*3 RGB).
 * LYNE_FOOTAGE_GEOM = "horizonY,metreSlope" for that footage (default 0.47,0.376).
 * Prints how often the lane-line source would be active and how often it would breach the LDW threshold;
 * per-frame rows go to <file>.csv next to the input.
 */
class LaneLineFootageEval {
    @Test
    fun evaluate() {
        val spec = System.getenv("LYNE_FOOTAGE")
        assumeTrue(!spec.isNullOrBlank())
        val geom = (System.getenv("LYNE_FOOTAGE_GEOM") ?: "0.47,0.376").split(',').map { it.trim().toFloat() }
        for (path in spec!!.split(',')) {
            val core = LaneLineCore().apply { setGeometry(geom[0], 1f, geom[1] * 1.25f, 1.25f) }
            val csv = StringBuilder("sec,conf,offset,leftM,rightM,points\n")
            var n = 0; var active = 0; var breach = 0
            val breachSecs = sortedSetOf<Int>(); val activeSecs = sortedSetOf<Int>()
            var tNs = 0L
            DataInputStream(File(path).inputStream().buffered(1 shl 20)).use { inp ->
                val count = inp.readInt(); val w = inp.readInt(); val h = inp.readInt()
                val rgb = ByteArray(w * h * 3); val argb = IntArray(w * h)
                repeat(count) {
                    val sec = inp.readFloat(); inp.readFully(rgb)
                    for (i in 0 until w * h) {
                        argb[i] = (0xFF shl 24) or ((rgb[3 * i].toInt() and 0xFF) shl 16) or
                            ((rgb[3 * i + 1].toInt() and 0xFF) shl 8) or (rgb[3 * i + 2].toInt() and 0xFF)
                    }
                    val t0 = System.nanoTime()
                    val r = core.detect(argb, w, h)
                    tNs += System.nanoTime() - t0
                    n++
                    if (r.confidence >= 0.4f && !r.offset.isNaN()) {
                        active++; activeSecs += sec.toInt()
                        if (kotlin.math.abs(r.offset) >= 0.85f) { breach++; breachSecs += sec.toInt() }
                    }
                    csv.append("%.2f,%.3f,%.3f,%.2f,%.2f,%d\n".format(sec, r.confidence, r.offset, r.leftM, r.rightM, core.lastPointCount))
                }
            }
            File("$path.csv").writeText(csv.toString())
            println("FOOTAGE $path frames=$n active=${"%.1f".format(100f * active / n)}% breach=$breach " +
                "avgMs=${"%.2f".format(tNs / 1e6 / n)} activeSecs=$activeSecs breachSecs=$breachSecs")
        }
    }
}
