package com.lyne.adas.l1.inference

/** Object classes Lyne reasons about. Maps from the model label set in ObjectDetector. */
enum class ObjectClass {
    CAR, TRUCK, BUS, MOTORCYCLE, BICYCLE, PEDESTRIAN, AUTORICKSHAW, STOP_SIGN, UNKNOWN;

    val isVehicle: Boolean get() = this == CAR || this == TRUCK || this == BUS || this == AUTORICKSHAW
    val isVru: Boolean get() = this == PEDESTRIAN || this == BICYCLE || this == MOTORCYCLE
}

/** Axis-aligned box in normalized [0,1] frame coordinates (origin top-left). */
data class BBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val cx: Float get() = (left + right) * 0.5f
    val cy: Float get() = (top + bottom) * 0.5f
    val area: Float get() = width * height

    fun iou(o: BBox): Float {
        val ix = (minOf(right, o.right) - maxOf(left, o.left)).coerceAtLeast(0f)
        val iy = (minOf(bottom, o.bottom) - maxOf(top, o.top)).coerceAtLeast(0f)
        val inter = ix * iy
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

data class Detection(
    val cls: ObjectClass,
    val score: Float,
    val box: BBox,
)

/** Lane geometry in normalized coords. x-arrays sampled at fixed y rows (ascending). */
data class LaneResult(
    val rows: FloatArray,        // y positions [0,1] for each sampled row
    val leftLaneX: FloatArray?,  // x [0,1] of left ego-lane line per row, NaN where absent
    val rightLaneX: FloatArray?, // x [0,1] of right ego-lane line per row, NaN where absent
    /** Signed lateral offset of vehicle centre from lane centre, in half-lane-width units. */
    val centerOffset: Float,
    val confidence: Float,
    /** Optional drivable-area segmentation: row-major grid, 1 = drivable. Drawn as the green carpet. */
    val mask: ByteArray? = null,
    val maskW: Int = 0,
    val maskH: Int = 0,
) {
    companion object {
        val EMPTY = LaneResult(FloatArray(0), null, null, 0f, 0f)
    }

    // data class with arrays: equals/hashCode are identity-based here, which is fine (we never key on it).
}

enum class SignType { NONE, SPEED_LIMIT, STOP }

data class SignResult(
    val type: SignType,
    val speedLimitKph: Int?,
    val confidence: Float,
) {
    companion object {
        val NONE = SignResult(SignType.NONE, null, 0f)
    }
}
