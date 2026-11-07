package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import kotlin.math.abs
import kotlin.math.tan

/**
 * Faithful replica of the v1 FCW path (width-only distance with a 62 deg default FoV, frame-to-frame
 * EMA box growth, distance-rate TTC, 2-frame debounce, one event per frame) used as a baseline.
 */
class LegacyFcw(private val hFovDeg: Float = 62f) {
    private var box: BBox? = null
    private var lastW = 0f; private var lastT = 0f; private var growth = 0f
    private var lastD = Float.NaN; private var lastDT = 0f; private var closing = 0f
    private var deb = 0

    fun distance(d: Detection): Float {
        val w = d.box.width.coerceIn(1e-4f, 1f)
        val ang = Math.toRadians((hFovDeg * w).toDouble()).toFloat()
        return (0.9f / tan(ang / 2f)).coerceIn(0.5f, 250f)
    }

    /** @return true when v1 would have emitted an FCW event this frame. */
    fun update(dets: List<Detection>, t: Float): Boolean {
        val lead = dets.filter { abs(it.box.cx - 0.5f) <= 0.28f }.maxByOrNull { it.box.area } ?: return false.also { deb = 0 }
        val b = box
        if (b == null || b.iou(lead.box) < 0.3f) {
            box = lead.box; lastW = lead.box.width; lastT = t; growth = 0f; lastD = Float.NaN; closing = 0f
            return false
        }
        val nb = BBox((b.left + lead.box.left) / 2, (b.top + lead.box.top) / 2, (b.right + lead.box.right) / 2, (b.bottom + lead.box.bottom) / 2)
        box = nb
        val dt = t - lastT
        if (dt > 1e-3f) { growth += 0.35f * ((nb.width - lastW) / dt - growth); lastW = nb.width; lastT = t }
        val d = distance(Detection(lead.cls, lead.score, nb))
        if (!lastD.isNaN()) { val ddt = t - lastDT; if (ddt > 1e-3f) closing += 0.3f * ((lastD - d) / ddt - closing) }
        lastD = d; lastDT = t
        val s = if (growth > 1e-3f) nb.width / growth else Float.NaN
        val dd = if (closing > 0.3f) d / closing else Float.NaN
        val ttc = when {
            s.isNaN() && dd.isNaN() -> Float.NaN
            s.isNaN() -> dd
            dd.isNaN() -> s
            maxOf(s, dd) / maxOf(minOf(s, dd), 0.01f) < 1.6f -> minOf(s, dd)
            else -> s
        }
        val alert = !ttc.isNaN() && ttc < 2.7f
        deb = if (alert) deb + 1 else 0
        return deb >= 2
    }
}
