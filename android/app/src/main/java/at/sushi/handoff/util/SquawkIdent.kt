package at.sushi.handoff.util

import at.sushi.handoff.network.AppUpdateClient

/** Issue #185 -- gate for the transponder dialog's IDENT button. */
object SquawkIdent {
    /** First plugin release that understands squawkIdent (docs/protocol.md). */
    const val MinPluginVersion = "0.10.0"

    /** Whether the connected plugin is new enough to offer the button. An unknown version (null)
     *  means an old plugin that never reported one, so it is hidden too. */
    fun isSupported(pluginVersion: String?): Boolean =
        pluginVersion != null && !AppUpdateClient.isNewer(MinPluginVersion, pluginVersion)

    /** Whether the dialog offers IDENT. Hidden only when a plugin is actually connected and too old
     *  to know the command (a connected plugin that reports no version is an old one too) -- never
     *  merely because nothing is connected, and demo mode answers locally so it always shows. */
    fun isOffered(demoMode: Boolean, pluginConnected: Boolean, pluginVersion: String?): Boolean =
        demoMode || !pluginConnected || isSupported(pluginVersion)
}
