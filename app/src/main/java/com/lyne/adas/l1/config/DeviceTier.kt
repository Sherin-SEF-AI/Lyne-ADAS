package com.lyne.adas.l1.config

/**
 * Coarse hardware classification chosen at runtime by the capability probe.
 * The same APK runs on every tier; only the [TierProfile] differs.
 */
enum class DeviceTier {
    /** Flagship / NPU or strong GPU, >=6GB RAM, >=6 perf cores. Full feature set. */
    A,

    /** Mid-range (Snapdragon 6-series class, ~4GB RAM). Reference real-time target. */
    B,

    /** Entry / Android Go class (2-3GB RAM, weak/absent accelerator). Survival mode. */
    C;

    companion object {
        fun fromName(name: String?): DeviceTier? = entries.firstOrNull { it.name == name }
    }
}
