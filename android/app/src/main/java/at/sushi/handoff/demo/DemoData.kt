package at.sushi.handoff.demo

import at.sushi.handoff.protocol.ChatEntry
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ChatReadStateMessage
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.FlightPlanMessage
import at.sushi.handoff.protocol.NearbyAircraft
import at.sushi.handoff.protocol.NearbyAircraftMessage
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.protocol.SelcalAlert
import at.sushi.handoff.protocol.SubsystemStatusMessage
import at.sushi.handoff.util.CdmSlotDisplay
import at.sushi.handoff.util.CdmWindowPhase
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Made-up sample data for demo mode (issue #169) -- lets the app be looked at without the
 *  Windows plugin (Play review, trying the app before setting up the plugin, screenshots). Every
 *  callsign/name/cid here is fictional. Built fresh from "now" each time [DemoMode.enable] runs
 *  (not a fixed constant), so chat timestamps and the CDM start-up window are never stale.
 *
 *  Shape/coverage follows the independent iOS client's demo mode for guidance
 *  (https://github.com/MANFahrer-GF/vpilot-handoff-ios, MIT, `Handoff/Store/DemoData.swift`) --
 *  re-derived for this protocol's own message types, nothing copied verbatim. */
object DemoData {
    /** Our own demo flight: taxiing out at JFK for a transatlantic to Heathrow. */
    const val OwnCallsign = "DAL123"
    private const val Origin = "KJFK"
    private const val Destination = "EGLL"
    private const val Alternate = "EGKK"

    private val hhmmFormatter = DateTimeFormatter.ofPattern("HHmm").withZone(ZoneOffset.UTC)

    private fun freq(megahertz: Double) = RadioFrequency.fromMegahertz(megahertz)
    private fun isoTimestamp(nowEpochMillis: Long, offsetMillis: Long) =
        Instant.ofEpochMilli(nowEpochMillis + offsetMillis).toString()

    // Demo station frequencies, reused between the controller rows below and radioState so the
    // tuned/standby badges line up with what's actually loaded.
    private const val GroundMhz = 121.9
    private const val TowerMhz = 119.1
    private const val DeliveryMhz = 121.65
    private const val ApproachMhz = 132.4
    private const val OceanicMhz = 127.9
    private const val AtisMhz = 128.075

    fun controllers(nowEpochMillis: Long): ControllersMessage = ControllersMessage(
        controllers = listOf(
            Controller(
                callsign = "KJFK_GND",
                frequency = freq(GroundMhz),
                latitude = 40.6398,
                longitude = -73.7789,
                stationName = "JFK Ground",
                isCurrent = true,
                isHighlighted = true
            ),
            Controller(
                callsign = "KJFK_TWR",
                frequency = freq(TowerMhz),
                latitude = 40.6413,
                longitude = -73.7781,
                cid = 900111,
                name = "Sample Controller",
                rating = 5,
                stationName = "JFK Tower",
                isStandbyTuned = true,
                isNext = true,
                isHighlighted = true
            ),
            Controller(
                callsign = "KJFK_DEL",
                frequency = freq(DeliveryMhz),
                latitude = 40.6417,
                longitude = -73.7822,
                stationName = "JFK Clearance Delivery",
                isContactMe = true,
                requestsContactMe = true,
                contactMeText = "Confirm squawk 2143 and contact ground when ready.",
                isHighlighted = true
            ),
            Controller(
                callsign = "NY_APP",
                frequency = freq(ApproachMhz),
                latitude = 40.7,
                longitude = -73.9,
                stationName = "New York Approach",
                isPinned = true
            ),
            Controller(
                callsign = "GANDER_CTR",
                frequency = freq(OceanicMhz),
                latitude = 48.95,
                longitude = -54.57,
                stationName = "Gander Oceanic",
                isSelcalActive = true,
                isHighlighted = true
            ),
            Controller(
                callsign = "EGLL_D_ATIS",
                frequency = freq(AtisMhz),
                latitude = 51.4706,
                longitude = -0.4619,
                stationName = "Heathrow Arrival ATIS",
                textAtis = listOf(
                    "EGLL ARR ATIS INFO Q 1452Z",
                    "ILS APPROACH RWY 27L, TRANSITION LEVEL 70",
                    "SFC WIND 250/12KT, VIS 10KM, FEW040",
                    "TEMP 14 DEWPOINT 09 QNH 1013"
                )
            )
        )
    )

    fun chat(nowEpochMillis: Long): ChatMessage = ChatMessage(
        sessionId = "demo-session",
        messages = listOf(
            ChatEntry(
                id = 1,
                channel = "private",
                direction = "incoming",
                peer = "KJFK_DEL",
                from = "KJFK_DEL",
                text = "Handoff 123, confirm squawk 2143 and contact ground when ready.",
                timestamp = isoTimestamp(nowEpochMillis, -15 * 60_000L)
            ),
            ChatEntry(
                id = 2,
                channel = "private",
                direction = "outgoing",
                peer = "KJFK_DEL",
                from = OwnCallsign,
                text = "Wilco, squawk 2143 set, Handoff 123.",
                timestamp = isoTimestamp(nowEpochMillis, -14 * 60_000L - 30_000L)
            ),
            ChatEntry(
                id = 3,
                channel = "radio",
                direction = "incoming",
                from = "KJFK_GND",
                text = "Handoff 123, taxi to runway 31L via Alpha, Bravo.",
                frequencies = listOf(freq(GroundMhz)),
                timestamp = isoTimestamp(nowEpochMillis, -10 * 60_000L)
            ),
            ChatEntry(
                id = 4,
                channel = "radio",
                direction = "outgoing",
                from = OwnCallsign,
                text = "Taxi 31L via Alpha, Bravo, Handoff 123.",
                frequencies = listOf(freq(GroundMhz)),
                timestamp = isoTimestamp(nowEpochMillis, -9 * 60_000L - 40_000L)
            )
        ),
        selcalAlerts = listOf(
            SelcalAlert(
                from = "GANDER_CTR",
                frequencies = listOf(freq(OceanicMhz)),
                timestamp = isoTimestamp(nowEpochMillis, -2 * 60_000L)
            )
        )
    )

    fun chatReadState(): ChatReadStateMessage = ChatReadStateMessage(sessionId = "demo-session")

    fun radioState(): RadioStateMessage = RadioStateMessage(
        com1Frequency = freq(GroundMhz),
        com1StandbyFrequency = freq(TowerMhz),
        com2Frequency = freq(AtisMhz),
        com2StandbyFrequency = freq(ApproachMhz),
        modeCEnabled = true,
        transponderCode = 2143,
        com1TransmitEnabled = true,
        com2TransmitEnabled = false,
        com1ReceiveEnabled = true,
        com2ReceiveEnabled = true
    )

    fun flightPlan(): FlightPlanMessage = FlightPlanMessage(
        simbriefCallsign = OwnCallsign,
        simbriefOrigin = Origin,
        simbriefDestination = Destination,
        simbriefAlternate = Alternate,
        vatsimCallsign = OwnCallsign,
        vatsimOrigin = Origin,
        vatsimDestination = Destination,
        originMismatch = false,
        vatsimCidMismatch = false
    )

    fun subsystemStatus(): SubsystemStatusMessage = SubsystemStatusMessage(
        radioHostConnected = true,
        simulatorConnected = true,
        vatsimDataFeedConnected = true,
        simbriefFetched = true,
        pluginVersion = null,
        updateInterval = "normal"
    )

    fun nearbyAircraft(): NearbyAircraftMessage = NearbyAircraftMessage(
        aircraft = listOf(
            NearbyAircraft(callsign = "BAW117", aircraftType = "B789", distanceNm = 4.2),
            NearbyAircraft(callsign = "UAL905", aircraftType = "B77W", distanceNm = 9.8)
        )
    )

    /** Anchored a few minutes ahead of "now" (issue #165's start-up window) so the demo walks
     *  through all three chip colors on its own as real time passes while a reviewer or pilot is
     *  looking at it: yellow/BEFORE for the first couple of minutes, green/OPEN for the bulk of
     *  the window, then red/MISSED once it closes -- without needing a timer of its own, since
     *  the existing ticking display (FooterStatusBar's rememberTickingCdmSlot) already re-derives
     *  [CdmSlotDisplay.phase]/[CdmSlotDisplay.minutes] from [CdmSlotDisplay.referenceHhmm] on a
     *  live clock. */
    fun cdmSlot(nowEpochMillis: Long): CdmSlotDisplay {
        val reference = Instant.ofEpochMilli(nowEpochMillis + 7 * 60_000L)
        val referenceHhmm = hhmmFormatter.format(reference)
        return CdmSlotDisplay(
            waiting = false,
            eobt = referenceHhmm,
            tobt = referenceHhmm,
            tsat = referenceHhmm,
            ctot = null,
            exot = 12,
            requestedTobt = null,
            reason = null,
            atfcmStatus = null,
            depRunway = "31L",
            sid = "DEEZZ5",
            referenceLabel = "TSAT",
            referenceHhmm = referenceHhmm,
            asrtSet = false,
            phase = CdmWindowPhase.UNKNOWN,
            minutes = null
        ).at(nowEpochMillis)
    }
}
