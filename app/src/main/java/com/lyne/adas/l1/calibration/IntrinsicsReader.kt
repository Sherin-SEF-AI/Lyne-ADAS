package com.lyne.adas.l1.calibration

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.lyne.adas.l1.logging.Log
import kotlin.math.atan2

/**
 * Reads rear-camera intrinsics via Camera2 and derives the field of view of the ACTIVE pixel array
 * (SENSOR_INFO_PHYSICAL_SIZE covers the full pixel array, which can be wider than what the camera
 * outputs). Falls back to a conservative default when the HAL does not report the values.
 */
object IntrinsicsReader {
    private const val TAG = "IntrinsicsReader"

    fun read(context: Context): CameraIntrinsics {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return CameraIntrinsics.DEFAULT

            val ch = cm.getCameraCharacteristics(id)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal == null || sensor == null || focal <= 0f || sensor.width <= 0f) return CameraIntrinsics.DEFAULT

            var w = sensor.width.toDouble()
            var h = sensor.height.toDouble()
            val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (pixelArray != null && active != null && pixelArray.width > 0 && pixelArray.height > 0) {
                w *= active.width().toDouble() / pixelArray.width
                h *= active.height().toDouble() / pixelArray.height
            }
            val aspect = if (active != null && active.height() > 0) active.width().toFloat() / active.height() else (w / h).toFloat()

            val hFov = Math.toDegrees(2.0 * atan2(w / 2.0, focal.toDouble())).toFloat()
            val vFov = Math.toDegrees(2.0 * atan2(h / 2.0, focal.toDouble())).toFloat()
            // Sanity: phone main cameras live roughly in 45..100 deg horizontal. Outside that the HAL lied.
            if (hFov !in 40f..110f) return CameraIntrinsics.DEFAULT
            CameraIntrinsics(hFov, vFov, focal, w.toFloat(), fromDevice = true, sensorAspect = aspect)
                .also { Log.i(TAG, "intrinsics: hFov=${it.horizontalFovDeg} vFov=${it.verticalFovDeg} aspect=$aspect f=${focal}mm") }
        } catch (t: Throwable) {
            Log.w(TAG, "intrinsics read failed; using default", t)
            CameraIntrinsics.DEFAULT
        }
    }
}
