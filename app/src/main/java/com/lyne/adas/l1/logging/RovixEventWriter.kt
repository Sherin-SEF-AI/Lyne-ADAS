package com.lyne.adas.l1.logging

import android.content.Context
import com.lyne.adas.l1.config.DeviceTier
import com.lyne.adas.l1.fusion.AdasEvent
import com.lyne.adas.l1.inference.Backend
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Appends a ROVIX-compatible sidecar JSON-lines stream (one event object per line) under the app's
 * external files dir. Writes are batched off the hot path; the fusion thread only enqueues.
 */
class RovixEventWriter(
    context: Context,
    private val tier: DeviceTier,
    private val backend: Backend,
) {
    private val queue = ConcurrentLinkedQueue<String>()
    private val draining = AtomicBoolean(false)
    private val file: File =
        File(context.getExternalFilesDir("rovix") ?: context.filesDir, "events-${context.sessionStamp()}.jsonl")

    fun record(e: AdasEvent) {
        val o = JSONObject()
        o.put("ts", e.timestampMs)
        o.put("type", e.type.name)
        o.put("severity", e.severity.name)
        o.put("confidence", e.confidence.toDouble())
        o.put("message", e.message)
        e.ttcSec?.let { o.put("ttc_s", it.toDouble()) }
        e.headwaySec?.let { o.put("headway_s", it.toDouble()) }
        e.distanceM?.let { o.put("distance_m", it.toDouble()) }
        e.distanceConfidence?.let { o.put("distance_conf", it.toDouble()) }
        e.bbox?.let {
            o.put("bbox", JSONObject().apply {
                put("l", it.left.toDouble()); put("t", it.top.toDouble())
                put("r", it.right.toDouble()); put("b", it.bottom.toDouble())
            })
        }
        e.laneOffset?.let { o.put("lane_offset", it.toDouble()) }
        e.speedKph?.let { o.put("speed_kph", it.toDouble()) }
        e.speedLimitKph?.let { o.put("speed_limit_kph", it) }
        o.put("tier", tier.name)
        o.put("backend", backend.name)
        queue.add(o.toString())
        drain()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            BufferedWriter(FileWriter(file, /* append = */ true)).use { w ->
                while (true) {
                    val line = queue.poll() ?: break
                    w.write(line); w.newLine()
                }
            }
        } catch (t: Throwable) {
            Log.w("Rovix", "sidecar write failed", t)
        } finally {
            draining.set(false)
            if (queue.isNotEmpty()) drain()
        }
    }

    fun path(): String = file.absolutePath
}

/** Monotonic-ish session stamp without relying on wall clock formatting in the hot path. */
private fun Context.sessionStamp(): Long = (applicationInfo.hashCode().toLong() xor System.nanoTime()) and 0xFFFFFFFFL
