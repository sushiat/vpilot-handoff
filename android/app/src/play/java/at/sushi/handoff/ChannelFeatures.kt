package at.sushi.handoff

/**
 * Per-distribution-channel feature switches (issue #153) -- the Google Play flavor. Same names as
 * the sideload copy under src/sideload; see docs/play-release.md for why each is set this way and
 * how to turn one back on (flip it here and drop its lines from src/play/AndroidManifest.xml).
 */
object ChannelFeatures {
    /** Off for the first submission: needs its own foreground-service declaration + demo video, and is only a debug nicety. */
    const val FullDeviceCapture = false

    /** Core to the split-screen chat, so kept unless Google refuses it. */
    const val FloatingOverlay = true

    /** Off: a Play-installed app shouldn't point users at another update channel. */
    const val GithubUpdateChecks = false
}
