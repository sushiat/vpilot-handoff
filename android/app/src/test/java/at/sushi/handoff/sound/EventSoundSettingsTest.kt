package at.sushi.handoff.sound

import org.junit.Assert.assertFalse
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
        assertTrue(settings.isEnabled(EventSound.ContactMe))
        assertTrue(settings.isEnabled(EventSound.Selcal))

        val noSelcal = EventSoundSettings(selcal = false)
        assertFalse(noSelcal.isEnabled(EventSound.Selcal))
        assertTrue(noSelcal.isEnabled(EventSound.Chat))
    }
}
