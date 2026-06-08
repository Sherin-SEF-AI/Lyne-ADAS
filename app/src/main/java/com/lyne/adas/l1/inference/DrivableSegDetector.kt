package com.lyne.adas.l1.inference

import android.content.Context
import com.lyne.adas.l1.config.BackendPreference
import com.lyne.adas.l1.logging.Log
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer

/**
 * Real drivable-area segmentation (TwinLiteNet). Runs the seg model and produces a binary drivable
 * mask plus per-row extent + centre offset for LDW. The model bakes RGB[0,1]→BGR + /255 internally,
 * so [TensorPreprocessor]'s plain RGB[0,1] input is correct. Output is the drivable head with 2
 * classes (0=bg, 1=drivable), either NHWC [1,H,W,2] or NCHW [1,2,H,W] (auto-detected).
 */
class DrivableSegDetector(
    context: Context,
    modelPath: String,
    pref: BackendPreference,
    threads: Int,
) : Detector<LaneResult> {

    override val name = "twinlite-seg"
    override val isStub = false

    private val engine = LiteRtEngine.create(ModelAssets.loadModel(context, modelPath), pref, threads)
    override val backend: Backend get() = engine.backend
    private val pre = TensorPreprocessor(engine.inputShape, engine.inputDataType, engine.inputScale, engine.inputZeroPoint)

    private val shape = engine.outputShapes[0]
    private val nhwc = shape[3] == 2 || shape[3] < shape[1]
    private val oh = if (nhwc) shape[1] else shape[2]
    private val ow = if (nhwc) shape[2] else shape[3]
    private val elementCount = shape.fold(1) { a, b -> a * b }
    private val scratch = FloatArray(elementCount)

    private val maskW = 80
    private val maskH = 45
    private val mask = ByteArray(maskW * maskH)
    private val prevMask = ByteArray(maskW * maskH)

    private val outRows = 20
    private val rows = FloatArray(outRows) { it / (outRows - 1f) }
    private val leftX = FloatArray(outRows)
    private val rightX = FloatArray(outRows)
    private var offsetEma = Float.NaN
    private var first = true

    init { Log.i(name, "out=${shape.joinToString("x")} nhwc=$nhwc HxW=${oh}x$ow backend=$backend") }

    override fun detect(frame: CameraFrame): LaneResult {
        pre.fill(frame.bitmap, engine.inputBuffer)
        engine.run()
        dequantize(engine.outputBuffers[0], engine.outputDataTypes[0], engine.outputScales[0], engine.outputZeroPoints[0])

        // Build the downsampled drivable mask by sampling the seg output.
        var drivableCount = 0
        for (my in 0 until maskH) {
            val oy = (my * (oh - 1) / (maskH - 1f)).toInt()
            for (mx in 0 until maskW) {
                val ox = (mx * (ow - 1) / (maskW - 1f)).toInt()
                val bg = at(oy, ox, 0)
                val dr = at(oy, ox, 1)
                var v = if (dr > bg) 1 else 0
                // Light temporal hold: keep a cell that was drivable last frame and is near-threshold.
                if (v == 0 && !first && prevMask[my * maskW + mx].toInt() == 1 && dr > bg - SMOOTH) v = 1
                mask[my * maskW + mx] = v.toByte()
                if (v == 1) drivableCount++
            }
        }
        System.arraycopy(mask, 0, prevMask, 0, mask.size)
        first = false

        // Per-row extent for LDW + carpet geometry.
        var bL = Float.NaN; var bR = Float.NaN
        for (i in 0 until outRows) {
            val my = (rows[i] * (maskH - 1)).toInt().coerceIn(0, maskH - 1)
            var minX = -1; var maxX = -1
            val base = my * maskW
            for (mx in 0 until maskW) if (mask[base + mx].toInt() == 1) { if (minX < 0) minX = mx; maxX = mx }
            if (minX >= 0 && maxX > minX) {
                leftX[i] = minX.toFloat() / (maskW - 1); rightX[i] = maxX.toFloat() / (maskW - 1)
                bL = leftX[i]; bR = rightX[i]
            } else { leftX[i] = Float.NaN; rightX[i] = Float.NaN }
        }

        val coverage = drivableCount.toFloat() / mask.size
        val confidence = when {
            coverage < 0.02f -> 0f
            coverage > 0.97f -> 0.3f
            else -> (0.5f + coverage).coerceIn(0f, 0.97f)
        }

        var offset = 0f
        if (!bL.isNaN() && !bR.isNaN()) {
            val center = (bL + bR) * 0.5f
            val half = ((bR - bL) * 0.5f).coerceAtLeast(0.05f)
            val raw = (0.5f - center) / half
            offsetEma = if (offsetEma.isNaN()) raw else offsetEma + 0.3f * (raw - offsetEma)
            offset = offsetEma
        }

        return LaneResult(rows.copyOf(), leftX.copyOf(), rightX.copyOf(), offset, confidence, mask.copyOf(), maskW, maskH)
    }

    private fun at(y: Int, x: Int, c: Int): Float =
        if (nhwc) scratch[(y * ow + x) * 2 + c] else scratch[(c * oh + y) * ow + x]

    private fun dequantize(buf: ByteBuffer, type: DataType, scale: Float, zp: Int) {
        buf.rewind()
        when (type) {
            DataType.FLOAT32 -> { val fb = buf.asFloatBuffer(); var i = 0; while (i < elementCount) { scratch[i] = fb.get(); i++ } }
            DataType.UINT8 -> { var i = 0; while (i < elementCount) { scratch[i] = ((buf.get().toInt() and 0xFF) - zp) * scale; i++ } }
            else -> { var i = 0; while (i < elementCount) { scratch[i] = (buf.get().toInt() - zp) * scale; i++ } }
        }
        buf.rewind()
    }

    override fun close() = engine.close()

    companion object { private const val SMOOTH = 0.5f }
}
