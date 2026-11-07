package com.lyne.adas.l1.logging

import android.content.Context
import com.lyne.adas.l1.fusion.FusionResult
import java.io.File
import java.io.FileWriter
import java.util.Locale
import kotlin.concurrent.thread

/** Result of one parked known-gap measurement. */
data class RangeCheckResult(
    val trueM: Float,
    val medianM: Float,
    val p10M: Float,
    val p90M: Float,
    val sigmaM: Float,
    val samples: Int,
) {
    val errorM: Float get() = medianM - trueM
    val errorPct: Float get() = errorM / trueM * 100f
}

/** UI-facing state of the range check. */
data class RangeCheckState(
    val trueM: Float? = null,
    val running: Boolean = false,
    val progress: Float = 0f,
    val last: RangeCheckResult? = null,
    val history: List<RangeCheckResult> = emptyList(),
    val message: String? = null,
)

/**
 * Parked known-gap accuracy check. The driver parks behind a vehicle at a measured gap, enters it,
 * and the app collects [DURATION_MS] of filtered lead distances. The median, spread and mean sigma
 * are shown immediately and appended to `accuracy/rangecheck.csv` (analysed by
 * tools/validate_accuracy.py). Collection happens on the fusion thread with a fixed buffer; the
 * file write is a one-off background thread.
 */
class RangeCheck(context: Context) {
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "accuracy").apply { mkdirs() }
    val file = File(dir, "rangecheck.csv")

    private val buf = FloatArray(MAX)
    private var sigmaSum = 0.0
    private var n = 0
    private var startMs = 0L
    @Volatile var state = RangeCheckState(); private set

    fun start(trueM: Float, nowMs: Long) {
        n = 0; sigmaSum = 0.0; startMs = nowMs
        state = state.copy(trueM = trueM, running = true, progress = 0f, message = "Hold still… measuring")
    }

    fun cancel() { state = state.copy(running = false, progress = 0f, message = null) }

    /** @return true when the state changed in a way the UI should show. */
    fun onResult(r: FusionResult, nowMs: Long): Boolean {
        val s = state
        if (!s.running) return false
        val d = r.leadDistanceM
        if (d != null && n < MAX) { buf[n++] = d; sigmaSum += (r.leadSigmaM ?: 0f) }
        val elapsed = nowMs - startMs
        if (elapsed < DURATION_MS) { state = s.copy(progress = elapsed / DURATION_MS.toFloat()); return true }
        finish(s.trueM ?: return false)
        return true
    }

    private fun finish(trueM: Float) {
        if (n < MIN_SAMPLES) {
            state = state.copy(running = false, progress = 0f,
                message = "No steady lead vehicle detected ($n readings). Make sure the car ahead is centred and fully in view.")
            return
        }
        val a = buf.copyOf(n).also { it.sort() }
        val res = RangeCheckResult(trueM, pct(a, 0.5f), pct(a, 0.1f), pct(a, 0.9f), (sigmaSum / n).toFloat(), n)
        state = state.copy(running = false, progress = 1f, last = res, history = state.history + res, message = null)
        val line = String.format(Locale.US, "%d,%.2f,%.3f,%.3f,%.3f,%.3f,%d\n",
            System.currentTimeMillis(), res.trueM, res.medianM, res.p10M, res.p90M, res.sigmaM, res.samples)
        thread(name = "lyne-rangecheck") {
            runCatching {
                val header = !file.exists()
                FileWriter(file, true).use { w ->
                    if (header) w.write("t_ms,true_m,median_m,p10_m,p90_m,sigma_m,samples\n")
                    w.write(line)
                }
            }.onFailure { Log.w("RangeCheck", "write failed", it) }
        }
    }

    private fun pct(sorted: FloatArray, q: Float): Float = sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]

    companion object {
        const val DURATION_MS = 5000L
        private const val MAX = 400
        private const val MIN_SAMPLES = 10
    }
}
