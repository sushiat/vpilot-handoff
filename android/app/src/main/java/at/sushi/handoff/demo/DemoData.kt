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
    /** Our own demo flight: taxiing out at Vienna for a flight to Heathrow. */
    const val OwnCallsign = "AUA81"
    private const val Origin = "LOWW"
    private const val Destination = "EGLL"
    private const val Alternate = "EGKK"

    private val hhmmFormatter = DateTimeFormatter.ofPattern("HHmm").withZone(ZoneOffset.UTC)

    private fun freq(megahertz: Double) = RadioFrequency.fromMegahertz(megahertz)
    private fun isoTimestamp(nowEpochMillis: Long, offsetMillis: Long) =
        Instant.ofEpochMilli(nowEpochMillis + offsetMillis).toString()

    // Demo station frequencies, reused between the controller rows below and radioState so the
    // tuned/standby badges line up with what's actually loaded.
    private const val GroundMhz = 121.6
    private const val TowerMhz = 119.4
    private const val DeliveryMhz = 122.125
    private const val ApproachMhz = 134.675
    private const val CenterMhz = 132.8
    private const val AtisMhz = 121.73

    /** The sample stations' base order, the final tiebreak when [DemoMode] re-sorts them. */
    val callsignOrder = listOf("LOWW_GND", "LOWW_TWR", "LOWW_DEL", "LOWW_APP", "LOVV_CTR", "LOWW_D_ATIS")

    fun controllers(nowEpochMillis: Long): ControllersMessage = ControllersMessage(
        controllers = listOf(
            Controller(
                callsign = "LOWW_GND",
                frequency = freq(GroundMhz),
                latitude = 48.1120,
                longitude = 16.5650,
                stationName = "Wien Ground",
                isCurrent = true,
                isHighlighted = true
            ),
            Controller(
                callsign = "LOWW_TWR",
                frequency = freq(TowerMhz),
                latitude = 48.1103,
                longitude = 16.5697,
                cid = 900111,
                name = "Sample Controller",
                rating = 5,
                stationName = "Wien Tower",
                isStandbyTuned = true,
                isNext = true,
                isHighlighted = true
            ),
            Controller(
                callsign = "LOWW_DEL",
                frequency = freq(DeliveryMhz),
                latitude = 48.1100,
                longitude = 16.5610,
                stationName = "Wien Delivery",
                isContactMe = true,
                requestsContactMe = true,
                contactMeText = "Confirm squawk 2143 and contact Wien ground on 121.6 when ready.",
                isHighlighted = true
            ),
            Controller(
                callsign = "LOWW_APP",
                frequency = freq(ApproachMhz),
                latitude = 48.11,
                longitude = 16.57,
                stationName = "Wien Approach",
                isPinned = true
            ),
            Controller(
                callsign = "LOVV_CTR",
                frequency = freq(CenterMhz),
                latitude = 48.2,
                longitude = 16.4,
                stationName = "Wien Radar",
                isSelcalActive = true,
                isHighlighted = true
            ),
            Controller(
                callsign = "LOWW_D_ATIS",
                frequency = freq(AtisMhz),
                latitude = 48.1103,
                longitude = 16.5697,
                stationName = "Wien Departure ATIS",
                textAtis = listOf(
                    "LOWW DEP ATIS INFO Q 1452Z",
                    "DEP RWY 29, TRANSITION LEVEL 70",
                    "SFC WIND 280/10KT, VIS 10KM, FEW040",
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
                peer = "LOWW_DEL",
                from = "LOWW_DEL",
                text = "Austrian 81, confirm squawk 2143 and contact Wien ground on 121.6 when ready.",
                timestamp = isoTimestamp(nowEpochMillis, -15 * 60_000L)
            ),
            ChatEntry(
                id = 2,
                channel = "private",
                direction = "outgoing",
                peer = "LOWW_DEL",
                from = OwnCallsign,
                text = "Wilco, squawk 2143 set, Austrian 81.",
                timestamp = isoTimestamp(nowEpochMillis, -14 * 60_000L - 30_000L)
            ),
            ChatEntry(
                id = 3,
                channel = "radio",
                direction = "incoming",
                from = "LOWW_GND",
                text = "Austrian 81, taxi to HP runway 29 via EX6 and M.",
                frequencies = listOf(freq(GroundMhz)),
                timestamp = isoTimestamp(nowEpochMillis, -10 * 60_000L)
            ),
            ChatEntry(
                id = 4,
                channel = "radio",
                direction = "outgoing",
                from = OwnCallsign,
                text = "Taxi to HP runway 29 via EX6 and M, Austrian 81.",
                frequencies = listOf(freq(GroundMhz)),
                timestamp = isoTimestamp(nowEpochMillis, -9 * 60_000L - 40_000L)
            )
        ),
        selcalAlerts = listOf(
            SelcalAlert(
                from = "LOVV_CTR",
                frequencies = listOf(freq(CenterMhz)),
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
            depRunway = "29",
            sid = "LNZ2C",
            referenceLabel = "TSAT",
            referenceHhmm = referenceHhmm,
            asrtSet = false,
            phase = CdmWindowPhase.UNKNOWN,
            minutes = null
        ).at(nowEpochMillis)
    }
}
