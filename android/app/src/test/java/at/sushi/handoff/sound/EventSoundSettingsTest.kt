package at.sushi.handoff.sound

import at.sushi.handoff.protocol.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSoundSettingsTest {
    @Test
    fun allEventsEnabledByDefault() {
        val settings = EventSoundSettings()
        EventSound.values().forEach { assertTrue(settings.isEnabled(it)) }
    }

    @Test
    fun eachSwitchOnlyAffectsItsOwnEvent() {
        val settings = EventSoundSettings(chat = false)
        assertFalse(settings.isEnabled(EventSound.Chat))
        assertTrue(settings.isEnabled(EventSound.RadioToYou))
        assertTrue(settings.isEnabled(EventSound.ContactMe))
        assertTrue(settings.isEnabled(EventSound.Selcal))

        val noRadioToYou = EventSoundSettings(radioToYou = false)
        assertFalse(noRadioToYou.isEnabled(EventSound.RadioToYou))
        assertTrue(noRadioToYou.isEnabled(EventSound.Chat))
    }

    @Test
    fun callsignMatchIsWholeWordAndCaseInsensitive() {
        assertTrue(isAddressedTo("BAW123, cleared for takeoff", "BAW123"))
        assertTrue(isAddressedTo("cleared for takeoff baw123", "BAW123"))
        assertTrue(isAddressedTo("Speedbird BAW123: contact tower", "baw123"))
        assertFalse(isAddressedTo("BAW1234 cleared for takeoff", "BAW123"))
        assertFalse(isAddressedTo("XBAW123 cleared", "BAW123"))
    }

    @Test
    fun callsignMatchNeverHitsWithoutCallsignOrText() {
        assertFalse(isAddressedTo("BAW123 hello", null))
        assertFalse(isAddressedTo("BAW123 hello", " "))
        assertFalse(isAddressedTo(null, "BAW123"))
        assertFalse(isAddressedTo("", "BAW123"))
    }

    @Test
    fun callsignWithRegexCharactersIsMatchedLiterally() {
        assertTrue(isAddressedTo("N1+2 go ahead", "N1+2"))
        assertFalse(isAddressedTo("N122 go ahead", "N1+2"))
    }

    private fun radio(text: String?, direction: String = "incoming") =
        ChatEntry(channel = "radio", direction = direction, from = "EGLL_TWR", text = text, timestamp = "t")

    private fun privateMsg(text: String) =
        ChatEntry(channel = "private", direction = "incoming", peer = "EGLL_TWR", text = text, timestamp = "t")

    @Test
    fun radioAddressedToUsPlaysRadioToYou() {
        assertEquals(EventSound.RadioToYou, soundForNewChat(listOf(radio("BAW123 cleared to land")), "BAW123"))
    }

    @Test
    fun generalRadioTrafficPlaysChat() {
        assertEquals(EventSound.Chat, soundForNewChat(listOf(radio("DLH456 cleared to land")), "BAW123"))
        assertEquals(EventSound.Chat, soundForNewChat(listOf(radio("BAW123 cleared to land")), null))
    }

    @Test
    fun privateMessageAlwaysPlaysChatEvenIfItMentionsUs() {
        assertEquals(EventSound.Chat, soundForNewChat(listOf(privateMsg("BAW123 contact me")), "BAW123"))
    }

    @Test
    fun mixedBatchPrefersRadioToYou() {
        val batch = listOf(radio("DLH456 hold short"), radio("BAW123 line up"), privateMsg("hi"))
        assertEquals(EventSound.RadioToYou, soundForNewChat(batch, "BAW123"))
    }

    @Test
    fun outgoingOrEmptyBatchPlaysNothing() {
        assertNull(soundForNewChat(emptyList(), "BAW123"))
        assertNull(soundForNewChat(listOf(radio("BAW123 wilco", direction = "outgoing")), "BAW123"))
    }
}
