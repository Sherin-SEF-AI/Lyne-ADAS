package com.lyne.adas.l1.inference

/**
 * COCO models box a person riding a two-wheeler as a separate "person". Treated as a pedestrian,
 * that rider produced spurious pedestrian warnings for every motorbike ahead. After NMS, a person
 * box that sits ON a motorcycle/bicycle box is folded into it: the person is dropped and the
 * two-wheeler box's top is raised to include the rider (left/right/bottom are kept, so the width
 * prior and the ground-contact range cue are unaffected).
 *
 * "Sits on" means:
 *  - the person's centre x lies within the two-wheeler box, and
 *  - the person's bottom lies within the upper [SEAT_FRAC] of the two-wheeler box height (a rider's
 *    legs end on the pegs / seat; a pedestrian's feet are on the road at or below the bike's wheels),
 *  - and the sizes are plausible for one unit (a tiny far-away person seen "through" a near bike is
 *    not its rider).
 *
 * Decisions use the ORIGINAL boxes (a pillion passenger is merged too, independently). Allocates
 * nothing unless a merge actually happens.
 */
object RiderMerge {
    const val SEAT_FRAC = 0.70f

    /** True if [person] is riding [twoWheeler] by the geometry above. */
    fun isRider(person: BBox, twoWheeler: BBox): Boolean {
        val tw = twoWheeler
        if (tw.height <= 0f || tw.width <= 0f) return false
        if (person.cx < tw.left || person.cx > tw.right) return false
        if (person.bottom < tw.top || person.bottom > tw.top + SEAT_FRAC * tw.height) return false
        // Size sanity: rider is about as tall as the bike box or taller, and not much wider.
        if (person.height < 0.4f * tw.height || person.width > 1.6f * tw.width) return false
        return true
    }

    /**
     * Merges riders into their two-wheelers in place. [dets] is the post-NMS list. Returns the same
     * list for chaining.
     */
    fun merge(dets: MutableList<Detection>): MutableList<Detection> {
        val n = dets.size
        if (n < 2) return dets
        var hasPerson = false; var hasTw = false
        for (i in 0 until n) {
            val c = dets[i].cls
            if (c == ObjectClass.PEDESTRIAN) hasPerson = true
            else if (c == ObjectClass.MOTORCYCLE || c == ObjectClass.BICYCLE) hasTw = true
        }
        if (!hasPerson || !hasTw) return dets

        // For each person, the two-wheeler it rides (best = most overlap), decided on original boxes.
        var target: IntArray? = null
        for (i in 0 until n) {
            val p = dets[i]
            if (p.cls != ObjectClass.PEDESTRIAN) continue
            var best = -1; var bestOv = 0f
            for (j in 0 until n) {
                val tw = dets[j]
                if (tw.cls != ObjectClass.MOTORCYCLE && tw.cls != ObjectClass.BICYCLE) continue
                if (!isRider(p.box, tw.box)) continue
                val ov = (minOf(p.box.right, tw.box.right) - maxOf(p.box.left, tw.box.left)).coerceAtLeast(0f)
                if (best < 0 || ov > bestOv) { best = j; bestOv = ov }
            }
            if (best >= 0) {
                val t = target ?: IntArray(n) { -1 }.also { target = it }
                t[i] = best
            }
        }
        val t = target ?: return dets

        // Raise each two-wheeler's top to its highest rider.
        for (i in 0 until n) {
            val j = t[i]; if (j < 0) continue
            val tw = dets[j]; val p = dets[i]
            if (p.box.top < tw.box.top) dets[j] = tw.copy(box = tw.box.copy(top = p.box.top))
        }
        // Drop merged persons (back to front keeps indices valid).
        for (i in n - 1 downTo 0) if (t[i] >= 0) dets.removeAt(i)
        return dets
    }
}
