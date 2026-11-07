package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.calibration.CameraGeometry
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.ObjectClass
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One fused range measurement, expressed in INVERSE depth q = 1/d (1/m). Reused per track.
 *
 * Why inverse depth: both monocular cues are linear in pixels in q (box width ~ q, ground-ray tangent
 * ~ q), so detector pixel noise is Gaussian and value-independent there. Fusing or filtering raw
 * metres instead weights each sample by an error that is itself correlated with the sample (a box
 * that looks too big also looks "more certain"), which biases every distance short.
 */
class RangeMeasurement {
    var inv = Float.NaN          // fused q (1/m)
    var invSigma = Float.NaN     // total 1-sigma of q (random + systematic)
    var invJitter = Float.NaN    // random, frame-to-frame part of invSigma
    var widthInv = Float.NaN; var widthInvSigma = Float.NaN
    var groundInv = Float.NaN; var groundInvSigma = Float.NaN

    val valid: Boolean get() = !inv.isNaN()
    val distanceM: Float get() = if (inv > 0f) 1f / inv else Float.NaN
    val groundM: Float get() = if (groundInv > 0f) 1f / groundInv else Float.NaN
    val widthM: Float get() = if (widthInv > 0f) 1f / widthInv else Float.NaN
    /** Relative 1-sigma of the ground cue (= relative sigma of its distance). */
    val groundRel: Float get() = if (groundInv > 0f) groundInvSigma / groundInv else Float.NaN

    fun clear() {
        inv = Float.NaN; invSigma = Float.NaN; invJitter = Float.NaN
        widthInv = Float.NaN; widthInvSigma = Float.NaN
        groundInv = Float.NaN; groundInvSigma = Float.NaN
    }
}

/**
 * Monocular range from two independent cues, fused by inverse variance in inverse depth:
 *
 *  1. Ground plane: the box bottom is where the object touches the road. With camera height H and
 *     the ray's depression angle a below the horizon, q = tan(a) / H. Independent of object size,
 *     so an SUV and a hatchback at the same gap read the same.
 *  2. Width prior: q = box_width * 2 tan(hFov/2) / W, with a per-class width W or the width this
 *     particular object was measured to have while it was close (see [Track]).
 *
 * Each cue carries its random (pixel quantization, road bounce) and systematic (pitch, height, width
 * prior) error. Truncated boxes drop the affected cue. Cues that disagree beyond their combined
 * uncertainty inflate the result's sigma, so downstream logic knows not to trust it.
 */
object RangeEstimator {

    fun widthPrior(cls: ObjectClass): Float = when (cls) {
        ObjectClass.CAR -> 1.80f
        ObjectClass.TRUCK -> 2.40f
        ObjectClass.BUS -> 2.55f
        ObjectClass.AUTORICKSHAW -> 1.40f
        ObjectClass.MOTORCYCLE -> 0.75f
        ObjectClass.BICYCLE -> 0.60f
        ObjectClass.PEDESTRIAN -> 0.50f
        ObjectClass.STOP_SIGN -> 0.75f
        ObjectClass.UNKNOWN -> 1.80f
    }

    /** Relative 1-sigma spread of real widths within the class. */
    fun widthPriorRelSigma(cls: ObjectClass): Float = when (cls) {
        ObjectClass.CAR -> 0.09f          // 1.6 m city car .. 2.05 m full-size pickup
        ObjectClass.TRUCK -> 0.14f
        ObjectClass.BUS -> 0.06f
        ObjectClass.AUTORICKSHAW -> 0.10f
        ObjectClass.MOTORCYCLE -> 0.25f
        ObjectClass.BICYCLE -> 0.25f
        ObjectClass.PEDESTRIAN -> 0.30f   // side-on vs front-on
        ObjectClass.STOP_SIGN -> 0.15f
        ObjectClass.UNKNOWN -> 0.30f
    }

    /** Box edge localization noise in detector-input pixels (YOLOv8n INT8, ~1-1.5 px). */
    const val EDGE_NOISE_PX = 1.3f
    /** Short-term pitch oscillation from road bumps / mount vibration (rad), ~0.25 deg. */
    private const val BOUNCE_RAD = 0.0045f
    /** Relative uncertainty of the camera height (user preset, refined by auto-cal). */
    private const val HEIGHT_REL_SIGMA = 0.05f

    fun measure(
        cls: ObjectClass,
        box: BBox,
        geom: CameraGeometry,
        inputPx: Int,
        learnedWidthM: Float,
        learnedRelSigma: Float,
        out: RangeMeasurement,
    ): Boolean {
        out.clear()
        val px = inputPx.coerceAtLeast(96).toFloat()
        var wJit = 0f; var gJit = 0f

        // --- width cue: q = w * 2tanH / W / scale ---
        val truncatedSide = box.left <= 0.004f || box.right >= 0.996f
        if (!truncatedSide && box.width > 0.003f) {
            val useLearned = !learnedWidthM.isNaN()
            val realW = if (useLearned) learnedWidthM else widthPrior(cls)
            val priorRel = if (useLearned) learnedRelSigma else widthPriorRelSigma(cls)
            val k = 2f * geom.tanHalfH / (realW * geom.distanceScale)
            val q = box.width * k
            val jit = (EDGE_NOISE_PX * 1.414f / px) * k            // absolute, value-independent
            val bias = q * priorRel
            out.widthInv = q; out.widthInvSigma = sqrt(jit * jit + bias * bias); wJit = jit
        }

        // --- ground cue: q = tan(a) / (H * scale) ---
        val truncatedBottom = box.bottom >= 0.992f
        if (!truncatedBottom && cls != ObjectClass.STOP_SIGN) {
            val a = geom.depressionRad(box.cx, box.bottom)
            if (a >= CameraGeometry.MIN_DEPRESSION_RAD * 0.5f) {
                val h = geom.cameraHeightM * geom.distanceScale
                val q = tan(a) / h
                val c = cos(a)
                val dqda = 1f / (h * c * c)
                val pxAng = geom.pixelAngleV(inputPx) * EDGE_NOISE_PX
                val jitAng = sqrt(BOUNCE_RAD * BOUNCE_RAD + pxAng * pxAng)
                val jit = dqda * jitAng
                val biasAng = dqda * geom.pitchSigmaRad
                val biasH = q * HEIGHT_REL_SIGMA
                out.groundInv = q
                out.groundInvSigma = sqrt(jit * jit + biasAng * biasAng + biasH * biasH)
                gJit = jit
            }
        }

        val hasW = !out.widthInv.isNaN()
        val hasG = !out.groundInv.isNaN()
        when {
            hasW && hasG -> {
                val ww = 1f / (out.widthInvSigma * out.widthInvSigma)
                val wg = 1f / (out.groundInvSigma * out.groundInvSigma)
                val fw = ww / (ww + wg); val fg = wg / (ww + wg)
                var q = fw * out.widthInv + fg * out.groundInv
                var s = sqrt(1f / (ww + wg))
                val gap = abs(out.widthInv - out.groundInv)
                val comb = sqrt(1f / ww + 1f / wg)
                if (gap > 3f * comb) { s = maxOf(s, gap * 0.5f) } // disagreement: be honest about it
                out.inv = q; out.invSigma = s
                out.invJitter = sqrt(fw * fw * wJit * wJit + fg * fg * gJit * gJit)
            }
            hasW -> { out.inv = out.widthInv; out.invSigma = out.widthInvSigma; out.invJitter = wJit }
            hasG -> { out.inv = out.groundInv; out.invSigma = out.groundInvSigma; out.invJitter = gJit }
            else -> return false
        }
        out.inv = out.inv.coerceIn(1f / 200f, 1f / 0.8f)
        return true
    }
}
