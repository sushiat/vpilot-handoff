package at.sushi.handoff.util

/** Issue #177 -- whether split-screen draws the chat as the floating overlay window (needs the
 *  "display over other apps" permission) or falls back to the in-window chat that narrow
 *  fullscreen already uses. */
fun useFloatingChat(featureAvailable: Boolean, hasPermission: Boolean, declined: Boolean): Boolean =
    featureAvailable && hasPermission && !declined

/** Whether to show the permission explainer dialog: only when floating chat is possible in this
 *  build, not yet permitted, and the pilot hasn't said "I don't want that" (persisted) or
 *  "Not now" (this process only). */
fun shouldPromptOverlayExplainer(
    featureAvailable: Boolean,
    hasPermission: Boolean,
    declined: Boolean,
    snoozedThisSession: Boolean
): Boolean = featureAvailable && !hasPermission && !declined && !snoozedThisSession
