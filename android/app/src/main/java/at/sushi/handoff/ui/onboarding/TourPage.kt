package at.sushi.handoff.ui.onboarding

/** One page of the first-launch tour (issue #205). Dots and the "N OF TOTAL" label derive from
 *  [entries], so adding a page is one enum entry plus a branch in [TourVisual].
 *
 *  [body] may mark named features with `**bold**`, see [parseBold]. [legend] is the numbered
 *  callout legend under the title, for pages whose visual carries numbered badges. */
enum class TourPage(val title: String, val body: String, val legend: List<String> = emptyList()) {
    WELCOME(
        "Your VATSIM cockpit sidekick",
        "Handoff mirrors your vPilot session on your tablet or phone: the **live VATSIM controller list**, your radios, transponder and **ATC chat**, all within reach of a finger. The two talk directly over your **local network**, not over the internet, so both must be on the same one."
    ),
    GETTING_STARTED(
        "Two ways to start",
        "Connect to a real flight, or look around in **demo mode** first. Connection status is always one tap away in the footer."
    ),
    CONTROLLERS(
        "Controllers, in flight order",
        "Online stations around you, sorted by your phase of flight. The tuned station is highlighted, the one you'll need **next** is marked. Tap a row for quick tune, pin or message."
    ),
    RADIOS(
        "Two radios, full control",
        "",
        legend = listOf(
            "Tap active frequency to swap with standby",
            "Tap standby to open the tuner",
            "MIC: which radio you transmit on",
            "MON: tap to also listen on the other radio"
        )
    ),
    TRANSPONDER_MESSAGES(
        "Transponder and messages",
        "Set your squawk without leaving the app. The message button changes color so you know at a glance whether something needs you."
    ),
    NEVER_MISS(
        "Never miss a call",
        "A station that wants you flashes with **Contact Me**. A **SELCAL** call flashes on the station you are tuned to."
    ),
    CHAT(
        "Chat with ATC and traffic",
        "**RADIO** shows what is said on your frequency, and you can type to transmit. Private chats get their own tab. The **aircraft icon**, easy to miss, starts a chat with pilots nearby.",
        legend = listOf("Chat with nearby aircraft")
    ),
    STATUS_BAR(
        "Status at a glance",
        "**Tap the bar at the bottom** to expand it. The dot is green when everything works, amber when something needs attention and red when the plugin is not connected.",
        legend = listOf("Tap to expand the status details", "Keep the screen awake, blue while on")
    ),
    VATSIM_CONNECTION(
        "VATSIM, one tap away",
        "The **plug** opens this form to connect vPilot to VATSIM, with callsign and aircraft prefilled from SimBrief, or as an **observer**. Tapping it again disconnects (it asks first). Needs a recent plugin.",
        legend = listOf("Connect to or disconnect from VATSIM")
    ),
    STARTUP_WINDOW(
        "Start-up, always on time",
        "At airports on **VDGS** A-CDM (other CDM systems are not supported), the status bar shows your start-up window: yellow before it opens, green while it is open and red once missed. Tap it to set your **TOBT** on the VDGS site.",
        legend = listOf("Start-up window")
    ),
    ALL_SET(
        "Welcome to Handoff",
        "That's the tour. Tap the **?** button in the top bar, next to the version number, to run it again whenever you like. Happy flying!"
    );

    companion object {
        val entries: List<TourPage> = values().toList()
    }
}

/** Splits [text] on `**` into (segment, isBold) runs; an unmatched trailing `**` is kept literally
 *  as plain text rather than swallowing the rest of the string. */
fun parseBold(text: String): List<Pair<String, Boolean>> {
    val parts = text.split("**")
    val unmatched = parts.size % 2 == 0
    return parts.mapIndexedNotNull { i, part ->
        val last = i == parts.lastIndex
        when {
            unmatched && last -> ("**$part").takeIf { part.isNotEmpty() }?.let { it to false }
            part.isEmpty() -> null
            else -> part to (i % 2 == 1)
        }
    }
}
