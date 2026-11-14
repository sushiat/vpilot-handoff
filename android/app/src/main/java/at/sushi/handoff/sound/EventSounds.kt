package at.sushi.handoff.sound

import at.sushi.handoff.protocol.ChatEntry

/** Issue #238 -- the vPilot events that can play a sound. [RadioToYou] is a radio message
 *  addressed to this aircraft (our callsign in the text), kept apart from [Chat] -- all other
 *  incoming chat -- so it can get its own, more attention-grabbing tone. */
enum class EventSound { Chat, RadioToYou, ContactMe, Selcal }

/** Per-event on/off switches from the settings dialog; all on by default. Pure, so the "does
 *  this event play" decision is unit-testable without Android. */
data class EventSoundSettings(
    val chat: Boolean = true,
    val radioToYou: Boolean = true,
    val contactMe: Boolean = true,
    val selcal: Boolean = true
) {
    fun isEnabled(event: EventSound): Boolean = when (event) {
        EventSound.Chat -> chat
        EventSound.RadioToYou -> radioToYou
        EventSound.ContactMe -> contactMe
        EventSound.Selcal -> selcal
    }
}

/** True when [text] contains [callsign] as a whole word, ignoring case -- "BAW123, cleared..."
 *  and "cleared, baw123" match, "BAW1234" and "XBAW123" do not. */
fun isAddressedTo(text: String?, callsign: String?): Boolean {
    if (text.isNullOrBlank() || callsign.isNullOrBlank()) return false
    return Regex("(?<![A-Za-z0-9])${Regex.escape(callsign.trim())}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)
}

/** The one sound a batch of newly arrived chat entries should play, or null for none. A radio
 *  message addressed to [ownCallsign] wins over everything else; any other incoming entry
 *  (general radio traffic, private messages) plays the plain chat sound. */
fun soundForNewChat(newEntries: List<ChatEntry>, ownCallsign: String?): EventSound? {
    val incoming = newEntries.filter { it.direction == "incoming" }
    if (incoming.isEmpty()) return null
    return if (incoming.any { it.channel == "radio" && isAddressedTo(it.text, ownCallsign) }) {
        EventSound.RadioToYou
    } else {
        EventSound.Chat
    }
}
