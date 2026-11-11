package at.sushi.handoff.sound

/** Issue #238 -- the vPilot events that can play a sound. */
enum class EventSound { Chat, ContactMe, Selcal }

/** Per-event on/off switches from the settings dialog; all on by default. Pure, so the "does
 *  this event play" decision is unit-testable without Android. */
data class EventSoundSettings(
    val chat: Boolean = true,
    val contactMe: Boolean = true,
    val selcal: Boolean = true
) {
    fun isEnabled(event: EventSound): Boolean = when (event) {
        EventSound.Chat -> chat
        EventSound.ContactMe -> contactMe
        EventSound.Selcal -> selcal
    }
}
