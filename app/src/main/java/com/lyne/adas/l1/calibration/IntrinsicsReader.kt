package com.lyne.adas.l1.calibration

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.lyne.adas.l1.logging.Log
import kotlin.math.atan2

/** Reads rear-camera intrinsics via Camera2 and derives field of view, with a safe fallback. */
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
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal == null || sensor == null || focal <= 0f) return CameraIntrinsics.DEFAULT

            val hFov = Math.toDegrees(2.0 * atan2((sensor.width / 2.0), focal.toDouble())).toFloat()
            val vFov = Math.toDegrees(2.0 * atan2((sensor.height / 2.0), focal.toDouble())).toFloat()
            CameraIntrinsics(hFov, vFov, focal, sensor.width, fromDevice = true)
                .also { Log.i(TAG, "intrinsics from device: hFov=${it.horizontalFovDeg} f=${it.focalLengthMm}mm") }
        } catch (t: Throwable) {
            Log.w(TAG, "intrinsics read failed; using default", t)
            CameraIntrinsics.DEFAULT
        }
    }
}
