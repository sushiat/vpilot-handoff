package at.sushi.handoff.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagesTest {

    @Test
    fun decodesControllersMessage_MinimalFields_EnrichmentAndFlagsDefault() {
        val json = """
            {"type":"controllers","controllers":[
              {"callsign":"EGLL_TWR","frequency":23725,"latitude":51.4775,"longitude":-0.4614}
            ]}
        """.trimIndent()

        val message = decodeServerMessage(json) as ControllersMessage
        val controller = message.controllers.single()
        assertEquals("EGLL_TWR", controller.callsign)
        assertEquals(23725, controller.frequency)
        assertEquals(51.4775, controller.latitude)
        assertEquals(-0.4614, controller.longitude)
        assertNull(controller.cid)
        assertNull(controller.name)
        assertNull(controller.facility)
        assertNull(controller.rating)
        assertEquals(false, controller.requestsContactMe)
        assertEquals(false, controller.isCurrent)
        assertEquals(false, controller.isContactMe)
        assertEquals(false, controller.isHighlighted)
        assertEquals(false, controller.isNext)
        assertEquals(false, controller.isLikelyNext)
        assertEquals(false, controller.isPinned)
        assertEquals(false, controller.isStandbyTuned)
        assertEquals(false, controller.isSelcalActive)
        assertNull(controller.stationName)
        assertNull(controller.textAtis)
        assertNull(message.etaMinutes)
    }

    @Test
    fun decodesControllersMessage_StationNameAndTextAtis() {
        val json = """
            {"type":"controllers","controllers":[
              {"callsign":"EGLL_TWR","frequency":23725,"latitude":51.4775,"longitude":-0.4614,
               "stationName":"Heathrow Tower","textAtis":["Heathrow Tower","Submit feedback at vats.im/atcfb"]}
            ]}
        """.trimIndent()

        val controller = (decodeServerMessage(json) as ControllersMessage).controllers.single()
        assertEquals("Heathrow Tower", controller.stationName)
        assertEquals(listOf("Heathrow Tower", "Submit feedback at vats.im/atcfb"), controller.textAtis)
    }

    @Test
    fun decodesControllersMessage_FullyEnriched_PreservesSortOrderAndFlags() {
        val json = """
            {"type":"controllers","etaMinutes":4.5,"controllers":[
              {"callsign":"EGLL_TWR","frequency":23725,"latitude":51.4775,"longitude":-0.4614,
               "cid":1234567,"name":"John Smith","facility":4,"rating":5,
               "requestsContactMe":false,"isCurrent":true,"isContactMe":false,"isHighlighted":false,"isNext":false,"isLikelyNext":false,"isPinned":false,"isStandbyTuned":false,"isSelcalActive":false},
              {"callsign":"EGLL_APP","frequency":12900,"latitude":51.5,"longitude":-0.46,
               "cid":null,"name":null,"facility":null,"rating":null,
               "requestsContactMe":true,"isCurrent":false,"isContactMe":true,"isHighlighted":true,"isNext":false,"isLikelyNext":true,"isPinned":false,"isStandbyTuned":false,"isSelcalActive":false}
            ]}
        """.trimIndent()

        val message = decodeServerMessage(json) as ControllersMessage
        assertEquals(listOf("EGLL_TWR", "EGLL_APP"), message.controllers.map { it.callsign })
        assertEquals(4.5, message.etaMinutes)

        val current = message.controllers[0]
        assertEquals(1234567, current.cid)
        assertEquals("John Smith", current.name)
        assertEquals(4, current.facility)
        assertEquals(5, current.rating)
        assertEquals(true, current.isCurrent)
        assertEquals(false, current.isLikelyNext)

        val contactMe = message.controllers[1]
        assertNull(contactMe.cid)
        assertEquals(true, contactMe.requestsContactMe)
        assertEquals(true, contactMe.isContactMe)
        assertEquals(true, contactMe.isLikelyNext)
    }

    @Test
    fun decodesControllersMessage_NoDebugField_DebugStaysNull() {
        val json = """
            {"type":"controllers","controllers":[
              {"callsign":"EGLL_TWR","frequency":23725,"latitude":51.4775,"longitude":-0.4614}
            ]}
        """.trimIndent()

        val message = decodeServerMessage(json) as ControllersMessage
        assertNull(message.debug)
        assertNull(message.controllers.single().debug)
    }

    @Test
    fun decodesControllersMessage_WithDebug_ParsesTopLevelAndPerControllerFields() {
        val json = """
            {"type":"controllers","debug":{
              "phaseOfFlight":"cruise","hasTakenOffThisSession":true,
              "ownshipLatitude":51.02,"ownshipLongitude":1.87,
              "ownshipAltitudeTrue":45000,"ownshipAltitudeAgl":44700,
              "ownshipGroundspeedKt":461,"ownshipHeadingTrue":68,"ownshipTrackTrue":68,
              "com1TunedCallsign":null,"com2TunedCallsign":null,
              "activeRouteWaypoint":"KONAN","lastPassedWaypoint":"GASBA",
              "etaCalculationDetail":"No bucket-8 candidate currently qualifies."
            },"controllers":[
              {"callsign":"EGKK_APP","frequency":12345,"latitude":51.1,"longitude":-0.2,
               "debug":{
                 "bucket":8,"bucketName":"Airborne CTR relevance","subBucket":"8a",
                 "reason":"test reason","distanceNm":47.2,
                 "vatGlassesSectorMatch":false,"vatSpyPolygonMatch":false,"routeMatch":false,
                 "hysteresisState":"stable","hysteresisPendingBucket":null,"hysteresisPendingSince":null,
                 "candidateRank":null
               }}
            ]}
        """.trimIndent()

        val message = decodeServerMessage(json) as ControllersMessage
        val debug = message.debug!!
        assertEquals("cruise", debug.phaseOfFlight)
        assertEquals(true, debug.hasTakenOffThisSession)
        assertEquals("KONAN", debug.activeRouteWaypoint)
        assertEquals("GASBA", debug.lastPassedWaypoint)

        val controllerDebug = message.controllers.single().debug!!
        assertEquals(8, controllerDebug.bucket)
        assertEquals("Airborne CTR relevance", controllerDebug.bucketName)
        assertEquals("8a", controllerDebug.subBucket)
        assertEquals(47.2, controllerDebug.distanceNm)
        assertEquals("stable", controllerDebug.hysteresisState)
        assertNull(controllerDebug.candidateRank)
    }

    @Test
    fun decodesSubsystemStatusMessage_NoSystemsDebugField_StaysNull() {
        val json = """{"type":"subsystemStatus","radioHostConnected":true,"simulatorConnected":true,"vatsimDataFeedConnected":true,"simbriefFetched":false,"pluginVersion":"0.1.0"}"""

        val message = decodeServerMessage(json) as SubsystemStatusMessage
        assertNull(message.systemsDebug)
    }

    @Test
    fun decodesSubsystemStatusMessage_WithSystemsDebug() {
        val json = """{"type":"subsystemStatus","radioHostConnected":true,"simulatorConnected":true,"vatsimDataFeedConnected":true,"simbriefFetched":true,"pluginVersion":"0.1.0",
            "systemsDebug":{
              "radioHostConnected":true,"simulatorConnected":true,"lastTelemetryAt":null,
              "vatsimFeedConnected":true,"vatsimFeedLastPollAt":null,
              "simbriefFetchedSuccessfully":true,"simbriefLastError":null,
              "vatGlassesLoadedRegionCount":42,"vatSpyBoundaryCount":380,
              "pairedClientCount":1,"authenticatedSocketCount":1,"activeOperationCount":0
            }}"""

        val message = decodeServerMessage(json) as SubsystemStatusMessage
        val systemsDebug = message.systemsDebug!!
        assertEquals(42, systemsDebug.vatGlassesLoadedRegionCount)
        assertEquals(380, systemsDebug.vatSpyBoundaryCount)
        assertEquals(1, systemsDebug.pairedClientCount)
    }

    @Test
    fun decodesDebugSnapshotSavedMessage() {
        val json = """{"type":"debugSnapshotSaved","snapshotId":"abc123","path":"C:\\path\\to\\snapshot.json"}"""

        val message = decodeServerMessage(json) as DebugSnapshotSavedMessage
        assertEquals("abc123", message.snapshotId)
        assertEquals("C:\\path\\to\\snapshot.json", message.path)
    }

    @Test
    fun encodesSetDebugModeCommand() {
        val json = SetDebugModeCommand(enabled = true).encode()
        assertEquals("""{"type":"setDebugMode","enabled":true}""", json)
    }

    @Test
    fun encodesSaveDebugSnapshotCommand() {
        val json = SaveDebugSnapshotCommand(snapshotId = "abc123", appVersion = "1.4.0").encode()
        assertEquals("""{"type":"saveDebugSnapshot","snapshotId":"abc123","appVersion":"1.4.0"}""", json)
    }

    @Test
    fun encodesAttachDebugSnapshotScreenshotCommand() {
        val json = AttachDebugSnapshotScreenshotCommand(snapshotId = "abc123", screenshotPngBase64 = "iVBORw0KG").encode()
        assertEquals("""{"type":"attachDebugSnapshotScreenshot","snapshotId":"abc123","screenshotPngBase64":"iVBORw0KG"}""", json)
    }

    @Test
    fun decodesChatMessageWithPrivateAndRadioEntries() {
        val json = """
            {"type":"chat","messages":[
              {"channel":"private","direction":"incoming","peer":"EGLL_TWR","text":"cleared for takeoff","frequencies":null,"timestamp":"2026-07-25T10:15:30Z"},
              {"channel":"radio","direction":"incoming","peer":null,"text":"report final","frequencies":[23725],"timestamp":"2026-07-25T10:16:05Z"}
            ],"selcalAlerts":[
              {"from":"EGLL_TWR","frequencies":[23725],"timestamp":"2026-07-25T10:16:00Z"}
            ]}
        """.trimIndent()

        val message = decodeServerMessage(json) as ChatMessage
        assertEquals(2, message.messages.size)
        assertEquals("EGLL_TWR", message.messages[0].peer)
        assertNull(message.messages[1].peer)
        assertEquals(listOf(23725), message.messages[1].frequencies)
        assertEquals("EGLL_TWR", message.selcalAlerts.single().from)
    }

    @Test
    fun decodesRadioStateMessageWithNullFrequencies() {
        val json = """{"type":"radioState","com1Frequency":null,"com2Frequency":null,"com1StandbyFrequency":null,"com2StandbyFrequency":null,"modeCEnabled":false,"transponderCode":null}"""

        val message = decodeServerMessage(json) as RadioStateMessage
        assertNull(message.com1Frequency)
        assertNull(message.com2Frequency)
        assertNull(message.com1StandbyFrequency)
        assertNull(message.com2StandbyFrequency)
        assertEquals(false, message.modeCEnabled)
        assertNull(message.transponderCode)
    }

    @Test
    fun decodesRadioStateMessageWithValues() {
        val json = """{"type":"radioState","com1Frequency":23725,"com2Frequency":18000,"com1StandbyFrequency":21000,"com2StandbyFrequency":19000,"modeCEnabled":true,"transponderCode":1200}"""

        val message = decodeServerMessage(json) as RadioStateMessage
        assertEquals(23725, message.com1Frequency)
        assertEquals(18000, message.com2Frequency)
        assertEquals(21000, message.com1StandbyFrequency)
        assertEquals(19000, message.com2StandbyFrequency)
        assertEquals(true, message.modeCEnabled)
        assertEquals(1200, message.transponderCode)
    }

    @Test
    fun decodesFlightPlanMessageWithValues() {
        val json = """{"type":"flightPlan","simbriefCallsign":"BAW123","simbriefOrigin":"EGLL","simbriefDestination":"KJFK","simbriefAlternate":"KBOS","vatsimCallsign":"BAW123","vatsimOrigin":"EGLL","vatsimDestination":"KJFK"}"""

        val message = decodeServerMessage(json) as FlightPlanMessage
        assertEquals("BAW123", message.simbriefCallsign)
        assertEquals("EGLL", message.simbriefOrigin)
        assertEquals("KJFK", message.simbriefDestination)
        assertEquals("KBOS", message.simbriefAlternate)
        assertEquals("BAW123", message.vatsimCallsign)
        assertEquals("EGLL", message.vatsimOrigin)
        assertEquals("KJFK", message.vatsimDestination)
    }

    @Test
    fun decodesFlightPlanMessageBeforeFirstFetch() {
        val json = """{"type":"flightPlan","simbriefCallsign":null,"simbriefOrigin":null,"simbriefDestination":null,"simbriefAlternate":null,"vatsimCallsign":null,"vatsimOrigin":null,"vatsimDestination":null}"""

        val message = decodeServerMessage(json) as FlightPlanMessage
        assertNull(message.simbriefCallsign)
        assertNull(message.simbriefOrigin)
        assertNull(message.simbriefDestination)
        assertNull(message.simbriefAlternate)
        assertNull(message.vatsimCallsign)
        assertNull(message.vatsimOrigin)
        assertNull(message.vatsimDestination)
    }

    @Test
    fun decodesFlightPlanMessageConnectedButNotFiled() {
        val json = """{"type":"flightPlan","simbriefCallsign":"BAW123","simbriefOrigin":"EGLL","simbriefDestination":"KJFK","simbriefAlternate":"KBOS","vatsimCallsign":"BAW123","vatsimOrigin":null,"vatsimDestination":null}"""

        val message = decodeServerMessage(json) as FlightPlanMessage
        assertEquals("BAW123", message.vatsimCallsign)
        assertNull(message.vatsimOrigin)
        assertNull(message.vatsimDestination)
    }

    @Test
    fun decodesFlightPlanMessage_OriginMismatchTrue() {
        val json = """{"type":"flightPlan","simbriefCallsign":null,"simbriefOrigin":null,"simbriefDestination":null,"simbriefAlternate":null,"vatsimCallsign":null,"vatsimOrigin":null,"vatsimDestination":null,"originMismatch":true}"""

        val message = decodeServerMessage(json) as FlightPlanMessage
        assertEquals(true, message.originMismatch)
    }

    @Test
    fun decodesFlightPlanMessage_OriginMismatchOmitted_DefaultsFalse() {
        val json = """{"type":"flightPlan","simbriefCallsign":null,"simbriefOrigin":null,"simbriefDestination":null,"simbriefAlternate":null,"vatsimCallsign":null,"vatsimOrigin":null,"vatsimDestination":null}"""

        val message = decodeServerMessage(json) as FlightPlanMessage
        assertEquals(false, message.originMismatch)
    }

    @Test
    fun decodesFlightPlanMessage_VatsimCidMismatchTrue() {
        val json = """{"type":"flightPlan","simbriefCallsign":null,"simbriefOrigin":null,"simbriefDestination":null,"simbriefAlternate":null,"vatsimCallsign":null,"vatsimOrigin":null,"vatsimDestination":null,"vatsimCidMismatch":true}"""

        val message = decodeServerMessage(json) as FlightPlanMessage
        assertEquals(true, message.vatsimCidMismatch)
    }

    @Test
    fun decodesFlightPlanMessage_VatsimCidMismatchOmitted_DefaultsFalse() {
        val json = """{"type":"flightPlan","simbriefCallsign":null,"simbriefOrigin":null,"simbriefDestination":null,"simbriefAlternate":null,"vatsimCallsign":null,"vatsimOrigin":null,"vatsimDestination":null}"""

        val message = decodeServerMessage(json) as FlightPlanMessage
        assertEquals(false, message.vatsimCidMismatch)
    }

    @Test
    fun decodesDiversionPendingMessage_WithDestination() {
        val json = """{"type":"diversionPending","destination":"EDDF"}"""

        val message = decodeServerMessage(json) as DiversionPendingMessage
        assertEquals("EDDF", message.destination)
    }

    @Test
    fun decodesDiversionPendingMessage_NothingPending() {
        val json = """{"type":"diversionPending","destination":null}"""

        val message = decodeServerMessage(json) as DiversionPendingMessage
        assertNull(message.destination)
    }

    @Test
    fun decodesOperationProgressMessage_InProgress() {
        val json = """{"type":"operationProgress","operationId":"vatGlassesSync","status":"Updating VatGlasses file 12/24","finished":false}"""

        val message = decodeServerMessage(json) as OperationProgressMessage
        assertEquals("vatGlassesSync", message.operationId)
        assertEquals("Updating VatGlasses file 12/24", message.status)
        assertEquals(false, message.finished)
    }

    @Test
    fun decodesOperationProgressMessage_FinishedSuccess() {
        val json = """{"type":"operationProgress","operationId":"vatGlassesSync","status":"VatGlasses data up to date","finished":true,"success":true}"""

        val message = decodeServerMessage(json) as OperationProgressMessage
        assertEquals(true, message.finished)
        assertEquals(true, message.success)
    }

    @Test
    fun decodesOperationProgressMessage_FinishedFailure() {
        val json = """{"type":"operationProgress","operationId":"vatGlassesSync","status":"VatGlasses sync incomplete","finished":true,"success":false}"""

        val message = decodeServerMessage(json) as OperationProgressMessage
        assertEquals(true, message.finished)
        assertEquals(false, message.success)
    }

    @Test
    fun decodesOperationProgressMessage_MissingSuccessField_DefaultsToTrue() {
        val json = """{"type":"operationProgress","operationId":"vatGlassesSync","status":"Updating VatGlasses file 12/24","finished":false}"""

        val message = decodeServerMessage(json) as OperationProgressMessage
        assertEquals(true, message.success)
    }

    @Test
    fun encodesSetSimbriefCredentialsCommand() {
        val json = SetSimbriefCredentialsCommand(simbriefUserId = "123456", simbriefUsername = null).encode()
        assertEquals("""{"type":"setSimbriefCredentials","simbriefUserId":"123456","simbriefUsername":null}""", json)
    }

    @Test
    fun encodesRefreshFlightPlanCommand() {
        val json = RefreshFlightPlanCommand().encode()
        assertEquals("""{"type":"refreshFlightPlan"}""", json)
    }

    @Test
    fun returnsNullForUnrecognizedType() {
        assertNull(decodeServerMessage("""{"type":"somethingElse"}"""))
    }

    @Test
    fun encodesSendPrivateMessageCommand() {
        val json = SendPrivateMessageCommand(to = "EGLL_TWR", message = "wilco").encode()
        assertEquals("""{"type":"sendPrivateMessage","to":"EGLL_TWR","message":"wilco"}""", json)
    }

    @Test
    fun encodesSendRadioMessageCommand() {
        val json = SendRadioMessageCommand(message = "request pushback").encode()
        assertEquals("""{"type":"sendRadioMessage","message":"request pushback"}""", json)
    }

    @Test
    fun encodesSetCom1FrequencyCommand() {
        val json = SetCom1FrequencyCommand(megahertz = 123.725).encode()
        assertEquals("""{"type":"setCom1Frequency","megahertz":123.725}""", json)
    }

    @Test
    fun encodesSetCom2FrequencyCommand() {
        val json = SetCom2FrequencyCommand(megahertz = 118.3).encode()
        assertEquals("""{"type":"setCom2Frequency","megahertz":118.3}""", json)
    }

    @Test
    fun encodesSetCom1StandbyFrequencyCommand() {
        val json = SetCom1StandbyFrequencyCommand(megahertz = 121.9).encode()
        assertEquals("""{"type":"setCom1StandbyFrequency","megahertz":121.9}""", json)
    }

    @Test
    fun encodesSetCom2StandbyFrequencyCommand() {
        val json = SetCom2StandbyFrequencyCommand(megahertz = 121.9).encode()
        assertEquals("""{"type":"setCom2StandbyFrequency","megahertz":121.9}""", json)
    }

    @Test
    fun encodesSetCom1ActiveAndStandbyFrequencyCommand() {
        val json = SetCom1ActiveAndStandbyFrequencyCommand(megahertz = 123.725, standbyMegahertz = 121.9).encode()
        assertEquals("""{"type":"setCom1ActiveAndStandbyFrequency","megahertz":123.725,"standbyMegahertz":121.9}""", json)
    }

    @Test
    fun encodesSetCom2ActiveAndStandbyFrequencyCommand() {
        val json = SetCom2ActiveAndStandbyFrequencyCommand(megahertz = 118.3, standbyMegahertz = 121.9).encode()
        assertEquals("""{"type":"setCom2ActiveAndStandbyFrequency","megahertz":118.3,"standbyMegahertz":121.9}""", json)
    }

    @Test
    fun encodesSetTransponderCodeCommand() {
        val json = SetTransponderCodeCommand(transponderCode = 1200).encode()
        assertEquals("""{"type":"setTransponderCode","transponderCode":1200}""", json)
    }

    @Test
    fun encodesPinControllerCommand() {
        val json = PinControllerCommand(callsign = "EGLL_TWR").encode()
        assertEquals("""{"type":"pinController","callsign":"EGLL_TWR"}""", json)
    }

    @Test
    fun encodesClearPinnedControllerCommand() {
        // Regression: clearPinnedController used to carry no fields at all (a global "unpin
        // whatever's pinned" command) -- multiple controllers can be pinned at once now, so
        // clearing one specifically requires its callsign, same as pinController.
        val json = ClearPinnedControllerCommand(callsign = "EGLL_TWR").encode()
        assertEquals("""{"type":"clearPinnedController","callsign":"EGLL_TWR"}""", json)
    }

    @Test
    fun encodesDismissSelcalCommand() {
        val json = DismissSelcalCommand(callsign = "EGLL_CTR").encode()
        assertEquals("""{"type":"dismissSelcal","callsign":"EGLL_CTR"}""", json)
    }

    @Test
    fun encodesConfirmDiversionCommand() {
        val json = ConfirmDiversionCommand().encode()
        assertEquals("""{"type":"confirmDiversion"}""", json)
    }

    @Test
    fun encodesDismissDiversionCommand() {
        val json = DismissDiversionCommand().encode()
        assertEquals("""{"type":"dismissDiversion"}""", json)
    }

    @Test
    fun formatsCompressedFrequency() {
        assertEquals("123.725", RadioFrequency.format(23725))
        assertEquals("118.000", RadioFrequency.format(18000))
    }
}
