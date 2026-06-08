package com.lyne.adas.l1.config

/** Which warning pipelines are active. Tier gating and the debug screen both toggle these. */
data class FeatureFlags(
    val fcw: Boolean,
    val vru: Boolean,
    val ldw: Boolean,
    val headway: Boolean,
    val tsr: Boolean,
) {
    companion object {
        val ALL = FeatureFlags(fcw = true, vru = true, ldw = true, headway = true, tsr = true)
    }
}
