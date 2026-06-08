package com.lyne.adas.l1.perf

import com.lyne.adas.l1.inference.Backend
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Immutable snapshot rendered by the PerfOverlay. */
data class PerfSnapshot(
    val fps: Float,
    val totalMs: Float,
    val stageMs: Map<String, Float>,
    val droppedFrames: Long,
    val processedFrames: Long,
    val heapUsedMb: Int,
    val heapMaxMb: Int,
    val backend: Backend,
    val tierName: String,
    val inputResolution: Int,
    val cadenceMultiplier: Int,
    val thermal: String,
)

/**
 * Lock-light performance counters updated from the inference thread, read from the UI thread.
 * FPS and latencies are exponential moving averages so the overlay is stable, not jittery.
 */
class PerfMonitor {
    private val stageEma = ConcurrentHashMap<String, Float>()
    @Volatile private var fpsEma = 0f
    @Volatile private var totalMsEma = 0f
    @Volatile private var lastFrameNs = 0L
    private val processed = AtomicLong(0)
    private val dropped = AtomicLong(0)

    // Set by the controller; surfaced in the snapshot.
    @Volatile var backend: Backend = Backend.XNNPACK_CPU
    @Volatile var tierName: String = "?"
    @Volatile var inputResolution: Int = 0
    @Volatile var cadenceMultiplier: Int = 1
    @Volatile var thermal: String = "none"

    private val alpha = 0.15f

    fun frameSubmitted() {}

    fun frameDropped() { dropped.incrementAndGet() }

    fun stage(name: String, nanos: Long) {
        val ms = nanos / 1_000_000f
        stageEma.merge(name, ms) { old, new -> old + alpha * (new - old) }
    }

    fun frameDone(totalNanos: Long) {
        processed.incrementAndGet()
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val intervalMs = (now - lastFrameNs) / 1_000_000f
            if (intervalMs > 0f) {
                val instFps = 1000f / intervalMs
                fpsEma = if (fpsEma == 0f) instFps else fpsEma + alpha * (instFps - fpsEma)
            }
        }
        lastFrameNs = now
        val totalMs = totalNanos / 1_000_000f
        totalMsEma = if (totalMsEma == 0f) totalMs else totalMsEma + alpha * (totalMs - totalMsEma)
    }

    fun snapshot(): PerfSnapshot {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val maxMb = (rt.maxMemory() / (1024 * 1024)).toInt()
        return PerfSnapshot(
            fps = fpsEma,
            totalMs = totalMsEma,
            stageMs = HashMap(stageEma),
            droppedFrames = dropped.get(),
            processedFrames = processed.get(),
            heapUsedMb = usedMb,
            heapMaxMb = maxMb,
            backend = backend,
            tierName = tierName,
            inputResolution = inputResolution,
            cadenceMultiplier = cadenceMultiplier,
            thermal = thermal,
        )
    }
}
