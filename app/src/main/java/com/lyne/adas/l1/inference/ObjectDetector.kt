package com.lyne.adas.l1.inference

import android.content.Context
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.BackendPreference
import com.lyne.adas.l1.logging.Log
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer

/**
 * YOLOv8n INT8 object detector. Handles both common export layouts ([1,C,N] channels-first and
 * [1,N,C]) and both coordinate conventions (normalized 0..1 or input-pixel). All scratch buffers
 * are allocated once and reused; only the small result list is created per frame.
 */
class ObjectDetector(
    context: Context,
    modelPath: String,
    labelsPath: String,
    private val config: AdasConfig,
    pref: BackendPreference,
    threads: Int,
) : Detector<List<Detection>> {

    override val name = "yolov8n"
    override val isStub = false

    private val engine = LiteRtEngine.create(ModelAssets.loadModel(context, modelPath), pref, threads)
    override val backend: Backend get() = engine.backend

    private val pre = TensorPreprocessor(engine.inputShape, engine.inputDataType, engine.inputScale, engine.inputZeroPoint)

    private val labels: List<String> = ModelAssets.loadLabels(context, labelsPath)
    private val shape = engine.outputShapes[0]            // [1, C, N] or [1, N, C]
    private val channelsFirst = shape[1] <= shape[2]
    private val numChannels = if (channelsFirst) shape[1] else shape[2]
    private val numBoxes = if (channelsFirst) shape[2] else shape[1]
    private val numClasses = (numChannels - 4).coerceAtLeast(1)
    private val elementCount = shape.fold(1) { a, b -> a * b }
    private val scratch = FloatArray(elementCount)

    init {
        Log.i(name, "out=${shape.joinToString("x")} channelsFirst=$channelsFirst classes=$numClasses backend=$backend")
    }

    override fun detect(frame: CameraFrame): List<Detection> {
        pre.fill(frame.bitmap, engine.inputBuffer)
        engine.run()
        dequantizeOutput(engine.outputBuffers[0], engine.outputDataTypes[0], engine.outputScales[0], engine.outputZeroPoints[0])

        val dets = ArrayList<Detection>(16)
        val thr = config.objectScoreThreshold
        for (n in 0 until numBoxes) {
            var best = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val s = at(4 + c, n)
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (bestScore < thr || best < 0) continue
            var xc = at(0, n); var yc = at(1, n); var w = at(2, n); var h = at(3, n)
            // Normalize if the export emits input-pixel coordinates.
            if (xc > 1.5f || w > 1.5f) {
                val iw = engine.inputShape[2].toFloat(); val ih = engine.inputShape[1].toFloat()
                xc /= iw; w /= iw; yc /= ih; h /= ih
            }
            val box = BBox(
                (xc - w / 2f).coerceIn(0f, 1f),
                (yc - h / 2f).coerceIn(0f, 1f),
                (xc + w / 2f).coerceIn(0f, 1f),
                (yc + h / 2f).coerceIn(0f, 1f),
            )
            if (box.area <= 0f) continue
            dets.add(Detection(mapClass(best), bestScore, box))
        }
        return nms(dets)
    }

    /** Read element [channel, box] from the dequantized scratch honoring layout. */
    private fun at(channel: Int, box: Int): Float =
        if (channelsFirst) scratch[channel * numBoxes + box] else scratch[box * numChannels + channel]

    private fun dequantizeOutput(buf: ByteBuffer, type: DataType, scale: Float, zp: Int) {
        buf.rewind()
        when (type) {
            DataType.FLOAT32 -> { val fb = buf.asFloatBuffer(); var i = 0; while (i < elementCount) { scratch[i] = fb.get(); i++ } }
            DataType.UINT8 -> { var i = 0; while (i < elementCount) { scratch[i] = ((buf.get().toInt() and 0xFF) - zp) * scale; i++ } }
            else -> { var i = 0; while (i < elementCount) { scratch[i] = (buf.get().toInt() - zp) * scale; i++ } } // INT8
        }
        buf.rewind()
    }

    private fun mapClass(idx: Int): ObjectClass {
        val label = labels.getOrNull(idx)?.lowercase() ?: return ObjectClass.UNKNOWN
        return when {
            "stop sign" in label || "stop_sign" in label -> ObjectClass.STOP_SIGN
            "person" in label || "pedestrian" in label -> ObjectClass.PEDESTRIAN
            "rickshaw" in label || "tuk" in label -> ObjectClass.AUTORICKSHAW
            "motor" in label || "scooter" in label -> ObjectClass.MOTORCYCLE
            "bicycle" in label || "cycle" in label -> ObjectClass.BICYCLE
            "truck" in label -> ObjectClass.TRUCK
            "bus" in label -> ObjectClass.BUS
            "car" in label || "vehicle" in label -> ObjectClass.CAR
            else -> ObjectClass.UNKNOWN
        }
    }

    private fun nms(dets: ArrayList<Detection>): List<Detection> {
        if (dets.size <= 1) return dets
        dets.sortByDescending { it.score }
        val keep = ArrayList<Detection>(dets.size.coerceAtMost(config.maxDetections))
        val removed = BooleanArray(dets.size)
        for (i in dets.indices) {
            if (removed[i]) continue
            val a = dets[i]
            keep.add(a)
            if (keep.size >= config.maxDetections) break
            for (j in i + 1 until dets.size) {
                if (removed[j]) continue
                val b = dets[j]
                if (a.cls == b.cls && a.box.iou(b.box) > config.nmsIouThreshold) removed[j] = true
            }
        }
        return keep
    }

    override fun close() = engine.close()
}
