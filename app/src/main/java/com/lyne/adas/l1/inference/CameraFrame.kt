package com.lyne.adas.l1.inference

import android.graphics.Bitmap

/**
 * A downscaled RGB frame ready for inference. The [bitmap] is owned by a pool and reused;
 * detectors must NOT retain it past [Detector.detect]. Coordinates produced by detectors are
 * normalized to [0,1] so they are independent of this frame's pixel size.
 */
class CameraFrame(
    @JvmField var bitmap: Bitmap,
    @JvmField var timestampNs: Long = 0L,
    @JvmField var rotationDegrees: Int = 0,
    @JvmField var frameId: Long = 0L,
)
