package at.sushi.handoff.util

import at.sushi.handoff.network.CdmFlight
import at.sushi.handoff.network.CdmFlightData
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun utcMillis(hour: Int, minute: Int): Long =
    Instant.now().atZone(ZoneOffset.UTC).toLocalDate().atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

class CdmSlotStatusTest {

    @Test
    fun minutesUntilUtcHhmm_futureSameDay() {
        assertEquals(30, minutesUntilUtcHhmm("1730", utcMillis(17, 0)))
    }

    @Test
    fun minutesUntilUtcHhmm_pastSameDay_isNegative() {
        assertEquals(-30, minutesUntilUtcHhmm("1700", utcMillis(17, 30)))
    }

    @Test
    fun minutesUntilUtcHhmm_rollsOverMidnightForward() {
        // 00:10 is only 20 minutes after 23:50 the same "now" day, not ~23h30m away.
        assertEquals(20, minutesUntilUtcHhmm("0010", utcMillis(23, 50)))
    }

    @Test
    fun minutesUntilUtcHhmm_rollsOverMidnightBackward() {
        // 23:50 the previous day is only 20 minutes before 00:10.
        assertEquals(-20, minutesUntilUtcHhmm("2350", utcMillis(0, 10)))
    }

    @Test
    fun minutesUntilUtcHhmm_blankOrMalformed_isNull() {
        assertNull(minutesUntilUtcHhmm("", utcMillis(12, 0)))
        assertNull(minutesUntilUtcHhmm("25:00", utcMillis(12, 0)))
        assertNull(minutesUntilUtcHhmm("173", utcMillis(12, 0)))
    }

    @Test
    fun deriveCdmSlotDisplay_nullWhenNoCdmData() {
        assertNull(deriveCdmSlotDisplay(null, utcMillis(12, 0)))
        assertNull(deriveCdmSlotDisplay(CdmFlight(callsign = "EIDGX"), utcMillis(12, 0)))
    }

    @Test
    fun deriveCdmSlotDisplay_nullWhenCdmDataAllBlank() {
        val flight = CdmFlight(callsign = "EIDGX", cdmData = CdmFlightData())
        assertNull(deriveCdmSlotDisplay(flight, utcMillis(12, 0)))
    }

    @Test
    fun deriveCdmSlotDisplay_prefersCtotOverTsatOverTobt() {
        val flight = CdmFlight(
            callsign = "EIDGX",
            cdmSts = "COMPLY",
            cdmData = CdmFlightData(tobt = "1700", tsat = "1712", ctot = "1725")
        )
        val display = deriveCdmSlotDisplay(flight, utcMillis(17, 0))!!
        assertEquals("CTOT", display.targetLabel)
        assertEquals(25, display.minutesUntil)
        assertEquals("COMPLY", display.statusText)
    }

    @Test
    fun deriveCdmSlotDisplay_fallsBackToTsatThenTobt() {
        val tsatOnly = CdmFlight(callsign = "EIDGX", cdmData = CdmFlightData(tobt = "1700", tsat = "1712"))
        assertEquals("TSAT", deriveCdmSlotDisplay(tsatOnly, utcMillis(17, 0))!!.targetLabel)

        val tobtOnly = CdmFlight(callsign = "EIDGX", cdmData = CdmFlightData(tobt = "1700"))
        assertEquals("TOBT", deriveCdmSlotDisplay(tobtOnly, utcMillis(16, 0))!!.targetLabel)
    }

    @Test
    fun deriveCdmSlotDisplay_urgencyThresholds() {
        val flight = CdmFlight(callsign = "EIDGX", cdmData = CdmFlightData(ctot = "1730"))
        assertEquals(CdmUrgency.GOOD, deriveCdmSlotDisplay(flight, utcMillis(17, 0))!!.urgency) // 30m out
        assertEquals(CdmUrgency.ATTENTION, deriveCdmSlotDisplay(flight, utcMillis(17, 20))!!.urgency) // 10m out
        assertEquals(CdmUrgency.URGENT, deriveCdmSlotDisplay(flight, utcMillis(17, 27))!!.urgency) // 3m out
        assertEquals(CdmUrgency.URGENT, deriveCdmSlotDisplay(flight, utcMillis(17, 35))!!.urgency) // 5m overdue
    }
}
