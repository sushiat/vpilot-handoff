package at.sushi.handoff.util

/** Issue #209 -- when HandoffConnectionService should stop itself, and when it should warn that
 *  Android is about to stop it. The service's foreground type is dataSync, which Android 15+ caps
 *  at 6 hours while the app is in the background (the timer resets when the app comes to the
 *  foreground); a service left running past that crashed the app. Only ever applies while the app
 *  is fully backgrounded.
 *
 *  [stopAfterMillis] -- quit like the notification's Quit action after this long: a service with
 *  no plugin connection has nothing to keep alive (usually a forgotten app, and demo mode lands
 *  here too since it keeps the plugin connection down), and one connected to the plugin but not
 *  to VATSIM is only slightly more likely to be wanted. null = never auto-stop.
 *
 *  [warnAfterMillis] -- post the "about to be stopped by Android" notification after this long.
 *  Only in the connected-to-both tier (the one that never auto-stops, so the only one that can
 *  reach the limit), a little before Android's own 6 hours. */
data class ServiceIdlePolicy(val stopAfterMillis: Long?, val warnAfterMillis: Long?)

object ServiceIdleStop {
    const val NoPluginStopMillis = 5 * 60_000L
    const val NoVatsimStopMillis = 15 * 60_000L
    const val LimitWarnMillis = 5 * 3_600_000L + 30 * 60_000L

    fun policy(appVisible: Boolean, pluginConnected: Boolean, onVatsim: Boolean): ServiceIdlePolicy = when {
        appVisible -> ServiceIdlePolicy(stopAfterMillis = null, warnAfterMillis = null)
        !pluginConnected -> ServiceIdlePolicy(stopAfterMillis = NoPluginStopMillis, warnAfterMillis = null)
        !onVatsim -> ServiceIdlePolicy(stopAfterMillis = NoVatsimStopMillis, warnAfterMillis = null)
        else -> ServiceIdlePolicy(stopAfterMillis = null, warnAfterMillis = LimitWarnMillis)
    }
}
