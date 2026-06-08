package com.lyne.adas.l1.perf

import android.content.Context
import android.os.Build
import com.lyne.adas.l1.config.DeviceTier
import com.lyne.adas.l1.config.TierProfile
import com.lyne.adas.l1.logging.Log

data class ResolvedTier(
    val tier: DeviceTier,
    val profile: TierProfile,
    val probe: ProbeResult?,
    val overridden: Boolean,
)

/**
 * Resolves and persists the device tier. The probe runs once and is cached against a hardware/OS
 * fingerprint; it re-runs only if that fingerprint changes (OS update, different device restore).
 * A debug override pins a tier and skips the probe.
 */
class TierSelector(private val context: Context) {
    private val prefs = context.getSharedPreferences("lyne_tier", Context.MODE_PRIVATE)

    private val fingerprint: String
        get() = "${Build.FINGERPRINT}|${Build.VERSION.SDK_INT}|${Runtime.getRuntime().availableProcessors()}"

    fun resolve(): ResolvedTier {
        DeviceTier.fromName(prefs.getString(KEY_OVERRIDE, null))?.let { forced ->
            Log.i(TAG, "tier overridden -> $forced")
            return ResolvedTier(forced, TierProfile.forTier(forced), probe = null, overridden = true)
        }

        val cachedTier = DeviceTier.fromName(prefs.getString(KEY_TIER, null))
        val cachedFp = prefs.getString(KEY_FP, null)
        if (cachedTier != null && cachedFp == fingerprint) {
            Log.i(TAG, "tier from cache -> $cachedTier")
            return ResolvedTier(cachedTier, TierProfile.forTier(cachedTier), probe = null, overridden = false)
        }

        val probe = DeviceProber.probe(context)
        prefs.edit().putString(KEY_TIER, probe.tier.name).putString(KEY_FP, fingerprint).apply()
        return ResolvedTier(probe.tier, TierProfile.forTier(probe.tier), probe, overridden = false)
    }

    fun setOverride(tier: DeviceTier?) {
        prefs.edit().apply { if (tier == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, tier.name) }.apply()
    }

    fun clearCache() = prefs.edit().remove(KEY_TIER).remove(KEY_FP).apply()

    companion object {
        private const val TAG = "TierSelector"
        private const val KEY_TIER = "tier"
        private const val KEY_FP = "fingerprint"
        private const val KEY_OVERRIDE = "override"
    }
}
