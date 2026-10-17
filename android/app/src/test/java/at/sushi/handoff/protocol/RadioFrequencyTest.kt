package at.sushi.handoff.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioFrequencyTest {

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
