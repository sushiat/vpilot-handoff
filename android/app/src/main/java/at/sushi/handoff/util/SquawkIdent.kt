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
}
