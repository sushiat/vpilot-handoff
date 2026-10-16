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
        assertNull(deriveCdmSlotDisplay(CdmFlight(callsign = "EIDGX", cdmData = CdmFlightData()), utcMillis(12, 0)))
    }

    @Test
    fun reference_followsSiteOrder_tsatThenCtotMinusExotThenTobt() {
        val all = CdmFlight(callsign = "EIDGX", cdmData = CdmFlightData(tobt = "1700", tsat = "1712", ctot = "1725"))
        assertEquals("TSAT", deriveCdmSlotDisplay(all, utcMillis(17, 0), taxiMinutes = 15)!!.referenceLabel)

        val ctotAndTobt = CdmFlight(callsign = "EIDGX", cdmData = CdmFlightData(tobt = "1700", ctot = "1725"))
        val ctotDisplay = deriveCdmSlotDisplay(ctotAndTobt, utcMillis(17, 0), taxiMinutes = 15)!!
        assertEquals("CTOT-EXOT", ctotDisplay.referenceLabel)
        assertEquals("1710", ctotDisplay.referenceHhmm)

        // No EXOT known: the CTOT can't be turned into an off-block time, so T/OBT is next.
        assertEquals("T/OBT", deriveCdmSlotDisplay(ctotAndTobt, utcMillis(17, 0))!!.referenceLabel)
    }

    @Test
    fun eobtOnlyOrRequestOnly_staysGreyWithNoWindow() {
        val requested = CdmFlight(callsign = "EIN76G", eobt = "1800", cdmData = CdmFlightData(reqTobt = "1830"))
        val reqDisplay = deriveCdmSlotDisplay(requested, utcMillis(17, 40))!!
        assertEquals(CdmWindowPhase.UNKNOWN, reqDisplay.phase)
        assertEquals("1830", reqDisplay.requestedTobt)

        val eobtOnly = CdmFlight(callsign = "EIN76G", eobt = "1800", cdmData = CdmFlightData())
        // Even long after the EOBT: no accepted time, so never "MISS".
        assertEquals(CdmWindowPhase.UNKNOWN, deriveCdmSlotDisplay(eobtOnly, utcMillis(18, 30))!!.phase)
        assertEquals("T-?", deriveCdmSlotDisplay(eobtOnly, utcMillis(18, 30))!!.chipText(compact = true))
    }

    @Test
    fun requestedTobt_hiddenOnceAcceptedAndEqual() {
        val accepted = CdmFlight(callsign = "EIN76G", tobt = "1830", cdmData = CdmFlightData(reqTobt = "1830"))
        assertNull(deriveCdmSlotDisplay(accepted, utcMillis(17, 40))!!.requestedTobt)
        val changing = CdmFlight(callsign = "EIN76G", tobt = "1830", cdmData = CdmFlightData(reqTobt = "1840"))
        assertEquals("1840", deriveCdmSlotDisplay(changing, utcMillis(17, 40))!!.requestedTobt)
    }

    @Test
    fun usesTopLevelTobtWhenCdmDataTobtBlank() {
        // Seen live: an accepted TOBT sits on the flight itself while cdmData.tobt stays blank.
        val flight = CdmFlight(callsign = "EIN76G", eobt = "1800", tobt = "1810", cdmData = CdmFlightData(reqTobt = "1810"))
        val display = deriveCdmSlotDisplay(flight, utcMillis(17, 50))!!
        assertEquals("T/OBT", display.referenceLabel)
        assertEquals("1810", display.tobt)
    }

    @Test
    fun window_boundariesAroundReferenceTime() {
        val flight = CdmFlight(callsign = "EIN76G", tobt = "1810", cdmData = CdmFlightData())
        fun at(h: Int, m: Int) = deriveCdmSlotDisplay(flight, utcMillis(h, m))!!
        assertEquals(CdmWindowPhase.BEFORE, at(17, 54).phase)
        assertEquals(11, at(17, 54).minutes) // 16 min out: window opens in 11
        assertEquals(CdmWindowPhase.BEFORE, at(18, 4).phase)
        assertEquals(1, at(18, 4).minutes)
        assertEquals(CdmWindowPhase.OPEN, at(18, 5).phase) // opens at reference -5
        assertEquals(11, at(18, 5).minutes)
        assertEquals(CdmWindowPhase.OPEN, at(18, 16).phase) // reference +6 is the last open minute
        assertEquals(0, at(18, 16).minutes)
        assertEquals(CdmWindowPhase.MISSED, at(18, 17).phase)
        assertEquals(1, at(18, 17).minutes)
        assertEquals(4, at(18, 20).minutes)
    }

    @Test
    fun window_neverMissedOnceAsrtIsSet() {
        val flight = CdmFlight(callsign = "EIN76G", tobt = "1810", cdmData = CdmFlightData(asrt = "1805"))
        val display = deriveCdmSlotDisplay(flight, utcMillis(18, 30))!!
        assertEquals(CdmWindowPhase.OPEN, display.phase)
        assertNull(display.minutes)
        assertEquals("ok", display.chipText(compact = true))
    }

    @Test
    fun at_rederivesPhaseAndMinutesForANewNow() {
        val flight = CdmFlight(callsign = "EIN76G", tobt = "1810", cdmData = CdmFlightData())
        val display = deriveCdmSlotDisplay(flight, utcMillis(18, 0))!!
        assertEquals(CdmWindowPhase.BEFORE, display.phase)
        assertEquals(CdmWindowPhase.OPEN, display.at(utcMillis(18, 8)).phase)
        assertEquals(CdmWindowPhase.MISSED, display.at(utcMillis(18, 20)).phase)
    }

    @Test
    fun chipText_wideAndCompact() {
        val flight = CdmFlight(callsign = "EIN76G", tobt = "1810", cdmData = CdmFlightData())
        fun at(h: Int, m: Int) = deriveCdmSlotDisplay(flight, utcMillis(h, m))!!
        assertEquals("STUP T-8m", at(17, 57).chipText(compact = false)) // window opens 18:05
        assertEquals("T-8m", at(17, 57).chipText(compact = true))
        assertEquals("STUP 4m left", at(18, 12).chipText(compact = false))
        assertEquals("4m", at(18, 12).chipText(compact = true))
        assertEquals("MISS +3m", at(18, 19).chipText(compact = false))
        assertEquals("MISS", at(18, 19).chipText(compact = true))
        assertEquals("STUP T-1h20m", at(16, 45).chipText(compact = false)) // 85 min to the window
    }

    @Test
    fun cdmWaitingDisplay_isGreyAndAnchorless() {
        val waiting = cdmWaitingDisplay().at(utcMillis(12, 0))
        assertEquals(CdmWindowPhase.UNKNOWN, waiting.phase)
        assertNull(waiting.minutes)
        assertEquals("STUP T-?", waiting.chipText(compact = false))
        assertEquals("T-?", waiting.chipText(compact = true))
        assertEquals("waiting for flight", waiting.windowSummary())
    }

    @Test
    fun readsTopLevelCtotRunwaySidReasonAndExot() {
        val flight = CdmFlight(
            callsign = "SWR755L", ctot = "1510", atfcmStatus = "ATC_ACTIV",
            cdmData = CdmFlightData(depInfo = "29/LANUX2C", reason = "REGULATION")
        )
        val display = deriveCdmSlotDisplay(flight, utcMillis(15, 0), taxiMinutes = 12)!!
        assertEquals("1510", display.ctot)
        assertEquals("29", display.depRunway)
        assertEquals("LANUX2C", display.sid)
        assertEquals("REGULATION", display.reason)
        assertEquals("ATC_ACTIV", display.atfcmStatus)
        assertEquals(12, display.exot)
    }

    @Test
    fun normalizesSixDigitSequencerTimes() {
        // Seen live: once processed, cdmData.tobt/tsat are "HHmmss" while the flight's own tobt is "HHmm".
        val flight = CdmFlight(
            callsign = "EIN76G", eobt = "1800", tobt = "1845",
            cdmData = CdmFlightData(tobt = "184500", tsat = "184500")
        )
        val display = deriveCdmSlotDisplay(flight, utcMillis(18, 40))!!
        assertEquals("TSAT", display.referenceLabel)
        assertEquals("1845", display.tobt)
        assertEquals("1845", display.tsat)
        assertEquals(CdmWindowPhase.OPEN, display.phase) // 5 min out = window just opened
    }

    @Test
    fun subtractMinutesHhmm_wrapsPastMidnight() {
        assertEquals("2355", subtractMinutesHhmm("0010", 15))
        assertNull(subtractMinutesHhmm("", 15))
    }
}
