package com.lyne.adas.l1.inference

/** Cheap scene-brightness estimate used for the low-light (night) mode. Pure Kotlin. */
object Luma {
    /**
     * Mean Rec.601 luma (0..1) over a sparse grid of ARGB [px] (row-major, [w] x [h]), about 32
     * samples per axis (~1000 reads). Allocation-free.
     */
    fun sparseMean(px: IntArray, w: Int, h: Int): Float {
        if (w <= 0 || h <= 0 || px.size < w * h) return Float.NaN
        val sx = (w / 32).coerceAtLeast(1); val sy = (h / 32).coerceAtLeast(1)
        var sum = 0L; var n = 0
        var y = sy / 2
        while (y < h) {
            var x = sx / 2
            val row = y * w
            while (x < w) {
                val p = px[row + x]
                sum += (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                n++
                x += sx
            }
            y += sy
        }
        return if (n == 0) Float.NaN else sum.toFloat() / (n * 255f)
    }
}
