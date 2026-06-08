package com.lyne.adas.l1.inference

import android.content.Context
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.BackendPreference
import com.lyne.adas.l1.logging.Log
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer

/**
 * Ultralight lane detector for a per-pixel lane-probability output (segmentation style):
 * [1,H,W,C] or [1,C,H,W], channel 0 = background, channels 1..C-1 = lane instances.
 * Decodes ego-lane left/right polylines and the signed lane-centre offset (in half-lane units).
 * If you bundle a UFLD griding model instead, adapt the decode here (see models/README.md).
 */
class LaneDetector(
    context: Context,
    modelPath: String,
    private val config: AdasConfig,
    pref: BackendPreference,
    threads: Int,
) : Detector<LaneResult> {

    override val name = "ufld-lane"
    override val isStub = false

    private val engine = LiteRtEngine.create(ModelAssets.loadModel(context, modelPath), pref, threads)
    override val backend: Backend get() = engine.backend

    private val pre = TensorPreprocessor(engine.inputShape, engine.inputDataType, engine.inputScale, engine.inputZeroPoint)

    private val shape = engine.outputShapes[0] // 4D
    private val nhwc = shape[3] <= shape[1]
    private val outH = if (nhwc) shape[1] else shape[2]
    private val outW = if (nhwc) shape[2] else shape[3]
    private val outC = if (nhwc) shape[3] else shape[1]
    private val elementCount = shape.fold(1) { a, b -> a * b }
    private val scratch = FloatArray(elementCount)

    private val sampleRows = 12
    private val rows = FloatArray(sampleRows) { 0.45f + it * (0.55f / sampleRows) } // lower 55% of frame
    private val leftX = FloatArray(sampleRows)
    private val rightX = FloatArray(sampleRows)

    init {
        Log.i(name, "out=${shape.joinToString("x")} nhwc=$nhwc HxW=${outH}x$outW C=$outC backend=$backend")
    }

    override fun detect(frame: CameraFrame): LaneResult {
        pre.fill(frame.bitmap, engine.inputBuffer)
        engine.run()
        dequantize(engine.outputBuffers[0], engine.outputDataTypes[0], engine.outputScales[0], engine.outputZeroPoints[0])

        var confAccum = 0f
        var confN = 0
        var bottomLeft = Float.NaN
        var bottomRight = Float.NaN

        for (r in 0 until sampleRows) {
            val y = (rows[r] * (outH - 1)).toInt().coerceIn(0, outH - 1)
            // For each lane channel find its argmax column at this row.
            var nearestLeft = Float.NaN; var nearestLeftDist = Float.MAX_VALUE
            var nearestRight = Float.NaN; var nearestRightDist = Float.MAX_VALUE
            for (c in 1 until outC) {
                var bestCol = -1; var bestProb = 0f
                for (x in 0 until outW) {
                    val p = prob(c, y, x)
                    if (p > bestProb) { bestProb = p; bestCol = x }
                }
                if (bestCol >= 0 && bestProb > 0.4f) {
                    val xn = bestCol.toFloat() / (outW - 1)
                    confAccum += bestProb; confN++
                    if (xn < 0.5f) { val d = 0.5f - xn; if (d < nearestLeftDist) { nearestLeftDist = d; nearestLeft = xn } }
                    else { val d = xn - 0.5f; if (d < nearestRightDist) { nearestRightDist = d; nearestRight = xn } }
                }
            }
            leftX[r] = nearestLeft
            rightX[r] = nearestRight
            if (r == sampleRows - 1) { bottomLeft = nearestLeft; bottomRight = nearestRight }
        }

        val confidence = if (confN > 0) (confAccum / confN).coerceIn(0f, 1f) else 0f
        val offset = if (!bottomLeft.isNaN() && !bottomRight.isNaN()) {
            val center = (bottomLeft + bottomRight) * 0.5f
            val halfWidth = ((bottomRight - bottomLeft) * 0.5f).coerceAtLeast(1e-3f)
            (0.5f - center) / halfWidth // +ve = vehicle right of centre
        } else 0f

        return LaneResult(rows.copyOf(), leftX.copyOf(), rightX.copyOf(), offset, confidence)
    }

    private fun prob(c: Int, y: Int, x: Int): Float {
        val idx = if (nhwc) (y * outW + x) * outC + c else (c * outH + y) * outW + x
        return scratch[idx]
    }

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
}
