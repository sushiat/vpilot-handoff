package at.sushi.handoff

/**
 * Per-distribution-channel feature switches (issue #153). This is the sideload channel -- GitHub
 * Releases, Obtainium, flightsim.to -- which ships everything. The Google Play flavor has its own
 * copy of this object under src/play with the same names, so call sites never branch on the flavor
 * itself, only on what the channel allows.
 */
object ChannelFeatures {
    /** The opt-in full-device screenshot for debug snapshots (MediaProjection). */
    const val FullDeviceCapture = true

    /** The SYSTEM_ALERT_WINDOW overlay permission prompt (floating chat/debug windows). */
    const val FloatingOverlay = true

    /** Checking this repo's GitHub releases for a newer app version. False where the store owns updates. */
    const val GithubUpdateChecks = true
}
