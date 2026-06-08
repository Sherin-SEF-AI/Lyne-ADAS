package com.lyne.adas.l1.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer

/**
 * Converts the camera [CameraFrame] bitmap into a model's input tensor with ZERO per-frame
 * allocation: a single scaled bitmap, pixel int[] and Canvas are created once and reused.
 * Honors the input tensor dtype and INT8/UINT8 quantization params.
 *
 * Assumes NHWC layout [1,H,W,3], RGB channel order, and (for float/int8) a [0,1] normalized
 * pixel model. See models/README.md — match your export to this contract.
 */
class TensorPreprocessor(
    inputShape: IntArray,
    private val dataType: DataType,
    private val scale: Float,
    private val zeroPoint: Int,
) {
    // inputShape = [1, H, W, C]
    private val h = inputShape[1]
    private val w = inputShape[2]
    private val pixels = IntArray(w * h)
    private val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(scaled)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect(0, 0, w, h)
    private val srcRect = Rect()

    val inputWidth get() = w
    val inputHeight get() = h

    fun fill(src: Bitmap, dst: ByteBuffer) = fillRegion(src, 0, 0, src.width, src.height, dst)

    /** Like [fill] but only the given pixel sub-rectangle of [src] is scaled into the input tensor. */
    fun fillRegion(src: Bitmap, left: Int, top: Int, right: Int, bottom: Int, dst: ByteBuffer) {
        srcRect.set(left, top, right, bottom)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        dst.rewind()
        when (dataType) {
            DataType.FLOAT32 -> {
                var i = 0
                while (i < pixels.size) {
                    val p = pixels[i]
                    dst.putFloat(((p shr 16) and 0xFF) / 255f)
                    dst.putFloat(((p shr 8) and 0xFF) / 255f)
                    dst.putFloat((p and 0xFF) / 255f)
                    i++
                }
            }
            DataType.UINT8 -> {
                var i = 0
                while (i < pixels.size) {
                    val p = pixels[i]
                    dst.put(((p shr 16) and 0xFF).toByte())
                    dst.put(((p shr 8) and 0xFF).toByte())
                    dst.put((p and 0xFF).toByte())
                    i++
                }
            }
            else -> { // INT8 (or other quantized): q = round(norm/scale + zeroPoint)
                val s = if (scale == 0f) 1f / 255f else scale
                var i = 0
                while (i < pixels.size) {
                    val p = pixels[i]
                    dst.put(quantize(((p shr 16) and 0xFF) / 255f, s))
                    dst.put(quantize(((p shr 8) and 0xFF) / 255f, s))
                    dst.put(quantize((p and 0xFF) / 255f, s))
                    i++
                }
            }
        }
        dst.rewind()
    }

    private fun quantize(norm: Float, s: Float): Byte {
        val q = Math.round(norm / s) + zeroPoint
        return q.coerceIn(-128, 127).toByte()
    }
}
