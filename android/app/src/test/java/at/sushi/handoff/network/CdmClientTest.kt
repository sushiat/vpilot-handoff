package at.sushi.handoff.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CdmClientTest {
    @Test
    fun singleObjectIsFound() {
        val result = classifyCallsignResponse(200, """{"callsign":"AUA123","departure":"LOWW","ctot":"1725","extra":1}""")
        assertIs<CdmLookup.Found>(result)
        assertEquals("AUA123", result.flight.callsign)
        assertEquals("1725", result.flight.ctot)
    }

    @Test
    fun emptyBodyOn200IsNoEntry() {
        assertEquals(CdmLookup.NoEntry, classifyCallsignResponse(200, ""))
        assertEquals(CdmLookup.NoEntry, classifyCallsignResponse(200, "  \n"))
        assertEquals(CdmLookup.NoEntry, classifyCallsignResponse(200, null))
    }

    @Test
    fun notFoundIsNoEntry() {
        assertEquals(CdmLookup.NoEntry, classifyCallsignResponse(404, ""))
    }

    @Test
    fun serverErrorAndGarbageAreFailed() {
        assertEquals(CdmLookup.Failed, classifyCallsignResponse(500, ""))
        assertEquals(CdmLookup.Failed, classifyCallsignResponse(200, "[]"))
        assertEquals(CdmLookup.Failed, classifyCallsignResponse(200, "not json"))
    }
}
