package com.lyne.adas.l1.inference

import android.content.Context
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.BackendPreference
import com.lyne.adas.l1.logging.Log
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer

/**
 * Traffic-sign recognition = lightweight red-sign localization (so the classifier sees a sign crop,
 * not the whole scene) + a GTSRB-trained classifier on that crop. If no plausible red sign blob is
 * found the classifier is NOT run, so it never fabricates a reading from an out-of-distribution
 * frame. Speed-limit value is parsed from the predicted label.
 *
 * Localization: coarse grid red-mask + connected-components, pick the largest plausible blob in the
 * upper region. All scratch reused (zero per-frame alloc).
 */
class SignClassifier(
    context: Context,
    modelPath: String,
    labelsPath: String,
    private val config: AdasConfig,
    pref: BackendPreference,
    threads: Int,
) : Detector<SignResult> {

    override val name = "gtsrb-sign"
    override val isStub = false

    private val engine = LiteRtEngine.create(ModelAssets.loadModel(context, modelPath), pref, threads)
    override val backend: Backend get() = engine.backend

    private val pre = TensorPreprocessor(engine.inputShape, engine.inputDataType, engine.inputScale, engine.inputZeroPoint)
    private val labels = ModelAssets.loadLabels(context, labelsPath)
    private val numClasses = engine.outputShapes[0].fold(1) { a, b -> a * b }
    private val scratch = FloatArray(numClasses)

    // Localization scratch (sized to frame on first use).
    private var pixels = IntArray(0)
    private var w = 0; private var h = 0
    private val cell = 8
    private var gw = 0; private var gh = 0
    private var red = BooleanArray(0)
    private var seen = BooleanArray(0)
    private var queue = IntArray(0)

    init { Log.i(name, "classes=$numClasses labels=${labels.size} backend=$backend") }

    override fun detect(frame: CameraFrame): SignResult {
        val bmp = frame.bitmap
        ensureBuffers(bmp.width, bmp.height)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val box = largestRedBlob() ?: return SignResult.NONE
        pre.fillRegion(bmp, box[0], box[1], box[2], box[3], engine.inputBuffer)
        engine.run()
        dequantize(engine.outputBuffers[0], engine.outputDataTypes[0], engine.outputScales[0], engine.outputZeroPoints[0])

        var best = -1; var bestP = 0f
        for (i in 0 until numClasses) if (scratch[i] > bestP) { bestP = scratch[i]; best = i }
        if (best < 0 || bestP < config.signMinConfidence) return SignResult.NONE

        val label = labels.getOrNull(best)?.lowercase() ?: return SignResult.NONE
        return when {
            "stop" in label -> SignResult(SignType.STOP, null, bestP)
            "speed" in label || "limit" in label -> {
                val kph = Regex("\\d+").find(label)?.value?.toIntOrNull()
                if (kph != null) SignResult(SignType.SPEED_LIMIT, kph, bestP) else SignResult.NONE
            }
            else -> SignResult.NONE
        }
    }

    /** @return [left,top,right,bottom] pixel box of the largest plausible red sign, or null. */
    private fun largestRedBlob(): IntArray? {
        // Mark red cells (>=40% red pixels) in the upper region.
        java.util.Arrays.fill(red, false)
        java.util.Arrays.fill(seen, false)
        val maxCy = (0.80f * gh).toInt()
        for (cy in 0 until gh) {
            for (cx in 0 until gw) {
                if (cy >= maxCy) continue
                var rc = 0
                val y0 = cy * cell; val x0 = cx * cell
                var yy = y0
                val y1 = minOf(y0 + cell, h); val x1 = minOf(x0 + cell, w)
                while (yy < y1) {
                    val base = yy * w
                    var xx = x0
                    while (xx < x1) { if (isRed(pixels[base + xx])) rc++; xx++ }
                    yy++
                }
                if (rc >= (cell * cell) * 4 / 10) red[cy * gw + cx] = true
            }
        }
        // Connected components (BFS) over red cells; keep the largest.
        var bestCount = 0
        var bMinX = 0; var bMinY = 0; var bMaxX = 0; var bMaxY = 0
        for (start in 0 until gw * gh) {
            if (!red[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var count = 0; var minX = gw; var minY = gh; var maxX = 0; var maxY = 0
            while (head < tail) {
                val c = queue[head++]
                val cx = c % gw; val cy = c / gw
                count++
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                if (cx > 0 && red[c - 1] && !seen[c - 1]) { seen[c - 1] = true; queue[tail++] = c - 1 }
                if (cx < gw - 1 && red[c + 1] && !seen[c + 1]) { seen[c + 1] = true; queue[tail++] = c + 1 }
                if (cy > 0 && red[c - gw] && !seen[c - gw]) { seen[c - gw] = true; queue[tail++] = c - gw }
                if (cy < gh - 1 && red[c + gw] && !seen[c + gw]) { seen[c + gw] = true; queue[tail++] = c + gw }
            }
            if (count > bestCount) { bestCount = count; bMinX = minX; bMinY = minY; bMaxX = maxX; bMaxY = maxY }
        }
        if (bestCount < 4) return null

        var l = bMinX * cell; var t = bMinY * cell
        var r = (bMaxX + 1) * cell; var b = (bMaxY + 1) * cell
        val bw = r - l; val bh = b - t
        // Plausibility: size + aspect (signs are roughly square, not tiny, not huge).
        if (bw < 0.04f * w || bw > 0.6f * w) return null
        val aspect = bw.toFloat() / bh.coerceAtLeast(1)
        if (aspect < 0.45f || aspect > 2.4f) return null
        // Pad 20% and clamp.
        val padX = (bw * 0.2f).toInt(); val padY = (bh * 0.2f).toInt()
        l = (l - padX).coerceAtLeast(0); t = (t - padY).coerceAtLeast(0)
        r = (r + padX).coerceAtMost(w); b = (b + padY).coerceAtMost(h)
        return intArrayOf(l, t, r, b)
    }

    private fun isRed(p: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return r > 100 && r - g > 35 && r - b > 35
    }

    private fun dequantize(buf: ByteBuffer, type: DataType, scale: Float, zp: Int) {
        buf.rewind()
        when (type) {
            DataType.FLOAT32 -> { val fb = buf.asFloatBuffer(); var i = 0; while (i < numClasses) { scratch[i] = fb.get(); i++ } }
            DataType.UINT8 -> { var i = 0; while (i < numClasses) { scratch[i] = ((buf.get().toInt() and 0xFF) - zp) * scale; i++ } }
            else -> { var i = 0; while (i < numClasses) { scratch[i] = (buf.get().toInt() - zp) * scale; i++ } }
        }
        buf.rewind()
    }

    private fun ensureBuffers(width: Int, height: Int) {
        if (width != w || height != h) {
            w = width; h = height
            pixels = IntArray(w * h)
            gw = (w + cell - 1) / cell; gh = (h + cell - 1) / cell
            red = BooleanArray(gw * gh); seen = BooleanArray(gw * gh); queue = IntArray(gw * gh)
        }
    }

    override fun close() = engine.close()
}
