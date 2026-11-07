package com.lyne.adas.l1.logging

import android.content.Context
import com.lyne.adas.l1.fusion.FusionResult
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Per-frame lead-vehicle CSV for validating range/TTC against ground truth (radar, lidar, a tape
 * measure). Off by default. Lines are built on the fusion thread and written on a background thread.
 *
 * Columns: t_ms, speed_kph, lead_id, distance_m, sigma_m, closing_mps, ttc_s, headway_s, fcw, conf,
 *          horizon_y, height_scale
 */
class AccuracyLogger(context: Context) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lyne-acclog") }
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "accuracy").apply { mkdirs() }
    val file = File(dir, "lead_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv")
    private var writer: BufferedWriter? = null
    private var lines = 0

    init {
        io.execute {
            runCatching {
                writer = BufferedWriter(FileWriter(file, true)).apply {
                    write("t_ms,speed_kph,lead_id,distance_m,sigma_m,closing_mps,ttc_s,headway_s,fcw,conf,horizon_y,height_scale\n")
                }
            }
        }
    }

    fun record(nowMs: Long, r: FusionResult) {
        val line = buildString(96) {
            append(nowMs).append(',')
            append(f(r.speedKph)).append(',')
            append(r.leadId ?: "").append(',')
            append(f(r.leadDistanceM)).append(',')
            append(f(r.leadSigmaM)).append(',')
            append(f(r.leadClosingMps)).append(',')
            append(f(r.ttcSec)).append(',')
            append(f(r.headwaySec)).append(',')
            append(r.fcw.name).append(',')
            append(f(r.leadDistanceConf)).append(',')
            append(f((r.horizonLeftY + r.horizonRightY) / 2f)).append(',')
            append(f(r.heightScale)).append('\n')
        }
        io.execute {
            runCatching {
                writer?.write(line)
                if (++lines % 60 == 0) writer?.flush()
            }
        }
    }

    fun close() {
        io.execute { runCatching { writer?.flush(); writer?.close() }; writer = null }
        io.shutdown()
    }

    private fun f(v: Float?): String = if (v == null || v.isNaN()) "" else String.format(Locale.US, "%.3f", v)
}
