package com.lyne.adas.l1.config

/**
 * First-run safety disclaimer. The user must explicitly accept it before the HUD starts. Acceptance
 * is stored with [VERSION]; bump the version whenever [POINTS] change meaningfully so every user is
 * asked again.
 */
object SafetyDisclaimer {
    const val VERSION = 1

    const val TITLE = "Before you drive"

    val POINTS: List<String> = listOf(
        "Warning-only: Lyne never brakes, steers or controls your vehicle in any way.",
        "Not certified: Lyne is not a certified safety system and is not a substitute for a vehicle's own driver-assistance features.",
        "Keep your eyes on the road. Do not look at or operate the phone while driving; you are always responsible for driving safely.",
        "Mount the phone securely where it does not block your view, airbags or controls, and follow local laws on phone mounts.",
        "Lyne can miss hazards or warn late, especially at night, in rain, glare or unusual traffic. Never rely on it to stay safe.",
    )

    const val ACCEPT_LABEL = "I UNDERSTAND"

    /** True if the user has not yet accepted the current disclaimer text. */
    fun needsAcceptance(acceptedVersion: Int): Boolean = acceptedVersion < VERSION
}
