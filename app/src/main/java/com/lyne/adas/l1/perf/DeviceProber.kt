package com.lyne.adas.l1.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.lyne.adas.l1.config.DeviceTier
import com.lyne.adas.l1.inference.Backend
import com.lyne.adas.l1.logging.Log
import org.tensorflow.lite.gpu.CompatibilityList

data class ProbeResult(
    val tier: DeviceTier,
    val backendHint: Backend,
    val ramMb: Long,
    val cores: Int,
    val gpuSupported: Boolean,
    val nnApiAvailable: Boolean,
    val cpuBenchMs: Long,
    val measured: Boolean,
) {
    val summary: String
        get() = "tier=$tier ram=${ramMb}MB cores=$cores gpu=$gpuSupported nnapi=$nnApiAvailable bench=${cpuBenchMs}ms hint=$backendHint"
}

/**
 * Fast (<=3s) on-first-run capability probe. It measures rather than guesses: RAM, core count,
 * GPU/NNAPI availability, and a fixed-work CPU micro-benchmark. Those measurements pick the tier.
 * (Per-model inference micro-benchmarking happens for real in [com.lyne.adas.l1.inference.LiteRtEngine]
 * when real weights are present; the stub has no tflite to time, so we fall back to this proxy.)
 */
object DeviceProber {
    private const val TAG = "DeviceProber"

    fun probe(context: Context): ProbeResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramMb = mi.totalMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val lowRam = am.isLowRamDevice

        val gpuSupported = try { CompatibilityList().isDelegateSupportedOnThisDevice } catch (t: Throwable) {
            Log.w(TAG, "GPU compat check failed", t); false
        }
        // NNAPI exists from API 27; treat <31 as unreliable on many low-end SoCs.
        val nnApiAvailable = Build.VERSION.SDK_INT >= 27

        val benchMs = cpuBenchmark()

        val backendHint = when {
            gpuSupported -> Backend.GPU
            nnApiAvailable && Build.VERSION.SDK_INT >= 29 -> Backend.NNAPI
            else -> Backend.XNNPACK_CPU
        }

        val tier = classify(ramMb, cores, gpuSupported, nnApiAvailable, lowRam, benchMs)
        return ProbeResult(tier, backendHint, ramMb, cores, gpuSupported, nnApiAvailable, benchMs, measured = true)
            .also { Log.i(TAG, it.summary) }
    }

    private fun classify(
        ramMb: Long, cores: Int, gpu: Boolean, nnapi: Boolean, lowRam: Boolean, benchMs: Long,
    ): DeviceTier {
        // Tier C: entry/Go class or clearly slow CPU with no accelerator.
        if (lowRam || ramMb <= 3072 || cores <= 4 || (benchMs > 220 && !gpu)) return DeviceTier.C
        // Tier A: flagship-ish — lots of RAM, many cores, an accelerator, fast CPU.
        if (ramMb >= 6000 && cores >= 6 && (gpu || nnapi) && benchMs < 110) return DeviceTier.A
        return DeviceTier.B
    }

    /** Fixed-work FMA loop; wall-clock ms is a coarse single-thread speed proxy. */
    private fun cpuBenchmark(): Long {
        val iters = 24_000_000
        val start = System.nanoTime()
        var acc = 1.0001f
        var i = 0
        while (i < iters) {
            acc = acc * 1.0000001f + 0.000001f
            if (acc > 2f) acc = 1.0001f
            i++
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        // Touch acc so the JIT can't elide the loop.
        if (acc == Float.MAX_VALUE) Log.v(TAG, "noop $acc")
        return ms
    }
}
