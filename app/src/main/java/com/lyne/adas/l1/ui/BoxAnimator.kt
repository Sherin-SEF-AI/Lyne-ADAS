package com.lyne.adas.l1.ui

import com.lyne.adas.l1.fusion.TrackedObject
import kotlin.math.exp

/**
 * Display-side smoothing for tracked boxes. The detector updates at 5-15 fps but the HUD redraws at
 * the display rate; easing each track's box toward its latest position (and fading tracks in and
 * out) removes the jumpy, flickery look without changing what the warning logic sees.
 */
class BoxAnimator(private val moveTauMs: Float = 75f, private val fadeInMs: Float = 140f, private val fadeOutMs: Float = 180f) {

    class Anim(var obj: TrackedObject) {
        var l = obj.box.left; var t = obj.box.top; var r = obj.box.right; var b = obj.box.bottom
        var alpha = 0f
        var live = true
    }

    private val anims = LinkedHashMap<Int, Anim>()
    private var lastNs = 0L

    /** Advance to [nowNs] toward [objects]; returns the boxes to draw (including ones fading out). */
    fun update(objects: List<TrackedObject>, nowNs: Long): Collection<Anim> {
        val dtMs = if (lastNs == 0L) 16f else ((nowNs - lastNs) / 1e6f).coerceIn(0f, 200f)
        lastNs = nowNs
        val kMove = 1f - exp(-dtMs / moveTauMs)
        val kIn = 1f - exp(-dtMs / fadeInMs)
        val kOut = 1f - exp(-dtMs / fadeOutMs)
        anims.values.forEach { it.live = false }
        for (o in objects) {
            val a = anims.getOrPut(o.id) { Anim(o) }
            a.obj = o; a.live = true
            val bx = o.box
            // A big jump (re-identified track) snaps instead of sliding across the screen.
            val jump = kotlin.math.abs(bx.left - a.l) + kotlin.math.abs(bx.right - a.r) > 0.35f
            if (jump || a.alpha < 0.05f) { a.l = bx.left; a.t = bx.top; a.r = bx.right; a.b = bx.bottom }
            else {
                a.l += (bx.left - a.l) * kMove; a.t += (bx.top - a.t) * kMove
                a.r += (bx.right - a.r) * kMove; a.b += (bx.bottom - a.b) * kMove
            }
            a.alpha += (1f - a.alpha) * kIn
        }
        val it = anims.values.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (!a.live) { a.alpha -= a.alpha * kOut; if (a.alpha < 0.03f) it.remove() }
        }
        return anims.values
    }
}
