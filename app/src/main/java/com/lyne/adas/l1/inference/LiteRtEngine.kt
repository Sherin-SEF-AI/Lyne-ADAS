package com.lyne.adas.l1.inference

import com.lyne.adas.l1.config.BackendPreference
import com.lyne.adas.l1.logging.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin reusable wrapper around a LiteRT/TFLite [Interpreter]. Owns one direct input buffer and one
 * direct buffer per output tensor; all are allocated once and rewound + reused every frame so the
 * hot path performs zero allocations. Backend is chosen by measured delegate probing in [create].
 */
class LiteRtEngine private constructor(
    private val model: ByteBuffer,
    private val interpreter: Interpreter,
    private val built: DelegateFactory.Built,
) : Closeable {

    val backend: Backend get() = built.backend

    val inputShape: IntArray = interpreter.getInputTensor(0).shape().copyOf()
    val inputDataType: DataType = interpreter.getInputTensor(0).dataType()
    val inputScale: Float = interpreter.getInputTensor(0).quantizationParams().scale
    val inputZeroPoint: Int = interpreter.getInputTensor(0).quantizationParams().zeroPoint
    val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())

    private val outputCount = interpreter.outputTensorCount
    val outputShapes: Array<IntArray> = Array(outputCount) { interpreter.getOutputTensor(it).shape().copyOf() }
    val outputDataTypes: Array<DataType> = Array(outputCount) { interpreter.getOutputTensor(it).dataType() }
    val outputScales: FloatArray = FloatArray(outputCount) { interpreter.getOutputTensor(it).quantizationParams().scale }
    val outputZeroPoints: IntArray = IntArray(outputCount) { interpreter.getOutputTensor(it).quantizationParams().zeroPoint }
    val outputBuffers: Array<ByteBuffer> = Array(outputCount) {
        ByteBuffer.allocateDirect(interpreter.getOutputTensor(it).numBytes()).order(ByteOrder.nativeOrder())
    }

    private val inputArray = arrayOf<Any>(inputBuffer)
    private val outputMap: MutableMap<Int, Any> = HashMap<Int, Any>(outputCount).apply {
        for (i in 0 until outputCount) put(i, outputBuffers[i])
    }

    /** Caller fills [inputBuffer] (rewound) before calling. Output buffers are rewound for reading. */
    fun run() {
        inputBuffer.rewind()
        for (b in outputBuffers) b.rewind()
        interpreter.runForMultipleInputsOutputs(inputArray, outputMap)
        for (b in outputBuffers) b.rewind()
    }

    override fun close() {
        try {
            interpreter.close()
        } finally {
            built.release()
        }
    }

    companion object {
        private const val TAG = "LiteRtEngine"

        /**
         * Try each backend in preference order. Keep the first that builds AND passes a warm-up
         * invocation with finite outputs; discard any that throws or yields NaN/Inf garbage.
         */
        fun create(model: ByteBuffer, pref: BackendPreference, threads: Int): LiteRtEngine {
            var lastError: Throwable? = null
            for (backend in DelegateFactory.order(pref)) {
                val built = DelegateFactory.build(backend, threads) ?: continue
                var interpreter: Interpreter? = null
                try {
                    interpreter = Interpreter(model, built.options)
                    interpreter.allocateTensors()
                    val engine = LiteRtEngine(model, interpreter, built)
                    if (engine.warmupOk()) {
                        Log.i(TAG, "engine ready on backend=$backend in=${engine.inputShape.contentToString()}")
                        return engine
                    } else {
                        Log.w(TAG, "backend $backend produced invalid warm-up output; falling back")
                        engine.close()
                    }
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "backend $backend failed to initialize", t)
                    try {
                        interpreter?.close()
                    } catch (_: Throwable) {
                    }
                    built.release()
                }
            }
            throw IllegalStateException("No usable inference backend for this model", lastError)
        }
    }

    private fun warmupOk(): Boolean = try {
        // Zero the input and run once. Validate that any float output is finite.
        inputBuffer.rewind()
        while (inputBuffer.hasRemaining()) inputBuffer.put(0)
        run()
        var ok = true
        for (i in outputBuffers.indices) {
            if (outputDataTypes[i] == DataType.FLOAT32) {
                val b = outputBuffers[i]
                b.rewind()
                val fb = b.asFloatBuffer()
                var checked = 0
                while (fb.hasRemaining() && checked < 256) { // sample, don't scan megabytes
                    val v = fb.get()
                    if (v.isNaN() || v.isInfinite()) { ok = false; break }
                    checked++
                }
                b.rewind()
                if (!ok) break
            }
        }
        ok
    } catch (t: Throwable) {
        Log.w(TAG, "warm-up threw", t)
        false
    }
}
