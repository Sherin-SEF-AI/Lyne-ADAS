package com.lyne.adas.l1.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.lyne.adas.l1.logging.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Binds CameraX Preview + ImageAnalysis (RGBA_8888, KEEP_ONLY_LATEST) to a lifecycle. Analysis runs
 * at a modest target resolution decoupled from the preview; the analyzer downscales further to the
 * model input. Backpressure is handled by KEEP_ONLY_LATEST + the analyzer's drop-on-busy policy.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val analyzerExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lyne-analyzer") }
    private var provider: ProcessCameraProvider? = null

    fun start(
        previewView: PreviewView?,
        analyzer: ImageAnalysis.Analyzer,
        analysisTarget: Size = Size(640, 480),
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cp = future.get()
                provider = cp

                val preview = Preview.Builder().build().also { p ->
                    previewView?.let { p.surfaceProvider = it.surfaceProvider }
                }

                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(analysisTarget, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analyzerExecutor, analyzer) }

                cp.unbindAll()
                cp.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Log.i(TAG, "camera bound; analysis target=$analysisTarget")
                onReady()
            } catch (t: Throwable) {
                Log.e(TAG, "camera start failed", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try { provider?.unbindAll() } catch (t: Throwable) { Log.w(TAG, "unbind failed", t) }
    }

    fun shutdown() {
        stop()
        analyzerExecutor.shutdown()
    }

    companion object { private const val TAG = "CameraController" }
}
