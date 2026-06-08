package com.lyne.adas.l1.inference

import android.content.Context
import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.BackendPreference
import com.lyne.adas.l1.config.TierProfile
import com.lyne.adas.l1.logging.Log

/** Bundle of the three model detectors actually wired into the pipeline. */
class DetectorSet(
    val obj: Detector<List<Detection>>,
    val lane: Detector<LaneResult>,
    val sign: Detector<SignResult>,
) {
    val anyStub: Boolean get() = obj.isStub || lane.isStub || sign.isStub
    fun close() { runCatching { obj.close() }; runCatching { lane.close() }; runCatching { sign.close() } }
}

/**
 * Chooses real model decoders when their weights are present and load on a usable backend, else
 * falls back to the bundled stub — so the app always runs. Honors the tier's model variant,
 * backend preference and thread cap.
 */
object DetectorProvider {
    private const val TAG = "DetectorProvider"

    fun build(context: Context, config: AdasConfig, profile: TierProfile): DetectorSet {
        val objPath = if (profile.useNanoModels) config.objectModelNano else config.objectModel
        val lanePath = if (profile.useNanoModels) config.laneModelNano else config.laneModel
        val pref = profile.backendPreference
        val threads = profile.inferenceThreads

        val obj: Detector<List<Detection>> = tryBuild("object") {
            if (ModelAssets.exists(context, objPath))
                ObjectDetector(context, objPath, config.objectLabels, config, pref, threads)
            else null
        } ?: StubObjectDetector(config)

        val lane: Detector<LaneResult> = tryBuild("lane") {
            when {
                !profile.features.ldw -> null
                // Real drivable-area segmentation on capable tiers (NNAPI; GPU delegate classes are
                // absent in this LiteRT build, so we don't attempt it).
                profile.useSegModel && ModelAssets.exists(context, config.drivableSegModel) ->
                    DrivableSegDetector(context, config.drivableSegModel, pref, threads)
                // A bundled UFLD lane model, if someone drops one in.
                ModelAssets.exists(context, lanePath) ->
                    LaneDetector(context, lanePath, config, pref, threads)
                else -> null
            }
        } ?: if (profile.features.ldw) DrivableAreaDetector() else StubLaneDetector(config)

        val sign: Detector<SignResult> = tryBuild("sign") {
            if (profile.features.tsr && ModelAssets.exists(context, config.signModel))
                SignClassifier(context, config.signModel, config.signLabels, config, pref, threads)
            else null
        } ?: StubSignDetector(config)

        Log.i(TAG, "detectors: obj=${obj.name}(stub=${obj.isStub}) lane=${lane.name}(stub=${lane.isStub}) sign=${sign.name}(stub=${sign.isStub})")
        return DetectorSet(obj, lane, sign)
    }

    private inline fun <T> tryBuild(which: String, block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "$which real model failed to load; using stub", t)
        null
    }
}
