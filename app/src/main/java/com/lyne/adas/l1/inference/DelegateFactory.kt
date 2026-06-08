package com.lyne.adas.l1.inference

import com.lyne.adas.l1.config.BackendPreference
import com.lyne.adas.l1.logging.Log
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

/** Inference backend actually selected after probing. */
enum class Backend { NNAPI, GPU, XNNPACK_CPU }

/**
 * Builds Interpreter.Options for a backend and owns the delegate lifecycle. Selection is
 * "measured, not assumed": [LiteRtEngine] tries this order and keeps the first that builds AND
 * passes a warm-up validation; anything that throws or yields garbage is discarded here.
 */
object DelegateFactory {

    private const val TAG = "DelegateFactory"

    /** A built option set plus the delegate to release when the interpreter is closed. */
    class Built(val options: Interpreter.Options, val delegate: Delegate?, val backend: Backend) {
        fun release() {
            try {
                when (val d = delegate) {
                    is GpuDelegate -> d.close()
                    is NnApiDelegate -> d.close()
                    else -> { /* XNNPACK has no separate handle */ }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "delegate release failed", t)
            }
        }
    }

    /** Resolve the concrete attempt order for a preference. */
    fun order(pref: BackendPreference): List<Backend> = when (pref) {
        BackendPreference.NNAPI_THEN_GPU -> listOf(Backend.NNAPI, Backend.GPU, Backend.XNNPACK_CPU)
        BackendPreference.GPU_THEN_NNAPI -> listOf(Backend.GPU, Backend.NNAPI, Backend.XNNPACK_CPU)
        BackendPreference.XNNPACK_ONLY -> listOf(Backend.XNNPACK_CPU)
    }

    /** Build options for one backend, or null if that backend is unavailable on this device. */
    fun build(backend: Backend, threads: Int): Built? = try {
        when (backend) {
            Backend.NNAPI -> {
                val d = NnApiDelegate()
                Built(Interpreter.Options().addDelegate(d), d, Backend.NNAPI)
            }
            Backend.GPU -> {
                val compat = CompatibilityList()
                if (!compat.isDelegateSupportedOnThisDevice) {
                    Log.i(TAG, "GPU delegate not supported on this device")
                    null
                } else {
                    // Default options; the per-device best-options type isn't on the compile classpath.
                    val d = GpuDelegate()
                    Built(Interpreter.Options().addDelegate(d), d, Backend.GPU)
                }
            }
            Backend.XNNPACK_CPU -> {
                // XNNPACK is the optimized CPU path; always available as last resort.
                val opts = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)
                Built(opts, null, Backend.XNNPACK_CPU)
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "backend $backend init failed", t)
        null
    }
}
