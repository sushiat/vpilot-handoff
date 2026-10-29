package at.sushi.handoff.protocol

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class RadioFrequencyTest {

    // Issue #202 -- a device set to a comma-decimal locale must still show "121.900".
    @Test
    fun format_alwaysUsesADotDecimal_whateverTheDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("121.900", RadioFrequency.format(21900))
            assertEquals("118.025", RadioFrequency.format(18025))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun fromMegahertz_isTheInverseOfToMegahertz() {
        assertEquals(23725, RadioFrequency.fromMegahertz(123.725))
        assertEquals(0, RadioFrequency.fromMegahertz(100.0))
    }

    @Test
    fun fromMegahertz_roundTripsThroughToMegahertz() {
        val compressed = 21900 // 121.900 MHz
        assertEquals(compressed, RadioFrequency.fromMegahertz(RadioFrequency.toMegahertz(compressed)))
    }
}
