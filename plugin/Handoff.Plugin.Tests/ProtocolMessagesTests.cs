using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class ProtocolMessagesTests
    {
        [Fact]
        public void BuildControllersMessage_EmptyList()
        {
            var json = JObject.Parse(ProtocolMessages.BuildControllersMessage(new List<RankedController>()));

            Assert.Equal("controllers", (string)json["type"]);
            Assert.Empty((JArray)json["controllers"]);
        }

        [Fact]
        public void BuildControllersMessage_OneController()
        {
            var controllers = new List<RankedController>
            {
                new RankedController(
                    "EGLL_TWR", 23725, 51.4775, -0.4614, 1234567, "John Smith", 4, 5,
                    requestsContactMe: true, isCurrent: true, isContactMe: false,
                    isHighlighted: true, isNext: true, isLikelyNext: false,
                    isPinned: true, isStandbyTuned: false, isSelcalActive: false)
            };

            var json = JObject.Parse(ProtocolMessages.BuildControllersMessage(controllers));
            var controller = json["controllers"][0];

            Assert.Equal("EGLL_TWR", (string)controller["callsign"]);
            Assert.Equal(23725, (int)controller["frequency"]);
            Assert.Equal(51.4775, (double)controller["latitude"]);
            Assert.Equal(-0.4614, (double)controller["longitude"]);
            Assert.Equal(1234567, (int)controller["cid"]);
            Assert.Equal("John Smith", (string)controller["name"]);
            Assert.Equal(4, (int)controller["facility"]);
            Assert.Equal(5, (int)controller["rating"]);
            Assert.True((bool)controller["requestsContactMe"]);
            Assert.True((bool)controller["isCurrent"]);
            Assert.False((bool)controller["isContactMe"]);
            Assert.True((bool)controller["isHighlighted"]);
            Assert.True((bool)controller["isNext"]);
            Assert.False((bool)controller["isLikelyNext"]);
            Assert.True((bool)controller["isPinned"]);
            Assert.False((bool)controller["isStandbyTuned"]);
            Assert.False((bool)controller["isSelcalActive"]);
            Assert.Equal(JTokenType.Null, controller["stationName"].Type);
            Assert.Equal(JTokenType.Null, controller["textAtis"].Type);
        }

        [Fact]
        public void BuildControllersMessage_TextAtis_SerializedAsArray()
        {
            var controllers = new List<RankedController>
            {
                new RankedController(
                    "EGLL_TWR", 23725, 51.4775, -0.4614, 1234567, "John Smith", 4, 5,
                    requestsContactMe: false, isCurrent: false, isContactMe: false,
                    isHighlighted: false, isNext: false, isLikelyNext: false,
                    isPinned: false, isStandbyTuned: false, isSelcalActive: false,
                    stationName: "Heathrow Tower",
                    textAtis: new[] { "Heathrow Tower", "Submit feedback at vats.im/atcfb" })
            };

            var json = JObject.Parse(ProtocolMessages.BuildControllersMessage(controllers));
            var textAtis = (JArray)json["controllers"][0]["textAtis"];

            Assert.Equal(new[] { "Heathrow Tower", "Submit feedback at vats.im/atcfb" }, textAtis.Select(t => (string)t));
        }

        [Fact]
        public void RankedController_EmptyTextAtisArray_NormalizesToNull()
        {
            var controller = new RankedController(
                "EGLL_TWR", 23725, 51.4775, -0.4614, null, null, null, null,
                requestsContactMe: false, isCurrent: false, isContactMe: false,
                isHighlighted: false, isNext: false, isLikelyNext: false,
                isPinned: false, isStandbyTuned: false, isSelcalActive: false,
                textAtis: new List<string>());

            Assert.Null(controller.TextAtis);
        }

        [Fact]
        public void BuildControllersMessage_UnenrichedController_EnrichmentFieldsAreNull()
        {
            var controllers = new List<RankedController>
            {
                new RankedController(
                    "EGLL_TWR", 23725, 51.4775, -0.4614, null, null, null, null,
                    requestsContactMe: false, isCurrent: false, isContactMe: false,
                    isHighlighted: false, isNext: false, isLikelyNext: false,
                    isPinned: false, isStandbyTuned: false, isSelcalActive: false)
            };

            var json = JObject.Parse(ProtocolMessages.BuildControllersMessage(controllers));
            var controller = json["controllers"][0];

            Assert.Equal(JTokenType.Null, controller["cid"].Type);
            Assert.Equal(JTokenType.Null, controller["name"].Type);
            Assert.Equal(JTokenType.Null, controller["facility"].Type);
            Assert.Equal(JTokenType.Null, controller["rating"].Type);
        }

        [Fact]
        public void BuildControllersMessage_EtaMinutes_NullByDefault()
        {
            var json = JObject.Parse(ProtocolMessages.BuildControllersMessage(new List<RankedController>()));

            Assert.Equal(JTokenType.Null, json["etaMinutes"].Type);
        }

        [Fact]
        public void BuildControllersMessage_EtaMinutes_IncludedWhenProvided()
        {
            var json = JObject.Parse(ProtocolMessages.BuildControllersMessage(new List<RankedController>(), etaMinutes: 12.5));

            Assert.Equal(12.5, (double)json["etaMinutes"]);
        }

        [Fact]
        public void BuildChatMessage_PrivateMessage_ChannelAndDirectionAreLowercase()
        {
            var messages = new List<ChatMessage>
            {
                new ChatMessage(ChatChannel.Private, ChatDirection.Incoming, "EGLL_TWR", "cleared for takeoff", null, DateTimeOffset.UtcNow)
            };

            var json = JObject.Parse(ProtocolMessages.BuildChatMessage(messages, new List<SelcalAlert>()));
            var message = json["messages"][0];

            Assert.Equal("chat", (string)json["type"]);
            Assert.Equal("private", (string)message["channel"]);
            Assert.Equal("incoming", (string)message["direction"]);
            Assert.Equal("EGLL_TWR", (string)message["peer"]);
            Assert.Equal("cleared for takeoff", (string)message["text"]);
            Assert.Equal(JTokenType.Null, message["frequencies"].Type);
        }

        [Fact]
        public void BuildChatMessage_RadioMessage_IncludesFrequencies()
        {
            var messages = new List<ChatMessage>
            {
                new ChatMessage(ChatChannel.Radio, ChatDirection.Incoming, null, "report final", new[] { 23725 }, DateTimeOffset.UtcNow)
            };

            var json = JObject.Parse(ProtocolMessages.BuildChatMessage(messages, new List<SelcalAlert>()));
            var message = json["messages"][0];

            Assert.Equal("radio", (string)message["channel"]);
            Assert.Equal(JTokenType.Null, message["peer"].Type);
            Assert.Equal(23725, (int)message["frequencies"][0]);
        }

        [Fact]
        public void BuildChatMessage_IncludesSelcalAlerts()
        {
            var alerts = new List<SelcalAlert> { new SelcalAlert("EGLL_TWR", new[] { 23725 }, DateTimeOffset.UtcNow) };

            var json = JObject.Parse(ProtocolMessages.BuildChatMessage(new List<ChatMessage>(), alerts));
            var alert = json["selcalAlerts"][0];

            Assert.Equal("EGLL_TWR", (string)alert["from"]);
            Assert.Equal(23725, (int)alert["frequencies"][0]);
        }

        [Fact]
        public void BuildRadioStateMessage_BeforeFirstRead_FrequenciesAreNull()
        {
            var state = new RadioState(null, null, null, null, false, null, false, false, false, false, DateTimeOffset.UtcNow);

            var json = JObject.Parse(ProtocolMessages.BuildRadioStateMessage(state));

            Assert.Equal("radioState", (string)json["type"]);
            Assert.Equal(JTokenType.Null, json["com1Frequency"].Type);
            Assert.Equal(JTokenType.Null, json["com2Frequency"].Type);
            Assert.Equal(JTokenType.Null, json["com1StandbyFrequency"].Type);
            Assert.Equal(JTokenType.Null, json["com2StandbyFrequency"].Type);
            Assert.False((bool)json["modeCEnabled"]);
            Assert.Equal(JTokenType.Null, json["transponderCode"].Type);
            Assert.False((bool)json["com1TransmitEnabled"]);
            Assert.False((bool)json["com2TransmitEnabled"]);
            Assert.False((bool)json["com1ReceiveEnabled"]);
            Assert.False((bool)json["com2ReceiveEnabled"]);
        }

        [Fact]
        public void BuildRadioStateMessage_WithValues()
        {
            var state = new RadioState(23725, 18000, 21000, 19000, true, 1200, true, false, true, true, DateTimeOffset.UtcNow);

            var json = JObject.Parse(ProtocolMessages.BuildRadioStateMessage(state));

            Assert.Equal(23725, (int)json["com1Frequency"]);
            Assert.Equal(18000, (int)json["com2Frequency"]);
            Assert.Equal(21000, (int)json["com1StandbyFrequency"]);
            Assert.Equal(19000, (int)json["com2StandbyFrequency"]);
            Assert.True((bool)json["modeCEnabled"]);
            Assert.Equal(1200, (int)json["transponderCode"]);
            Assert.True((bool)json["com1TransmitEnabled"]);
            Assert.False((bool)json["com2TransmitEnabled"]);
            Assert.True((bool)json["com1ReceiveEnabled"]);
            Assert.True((bool)json["com2ReceiveEnabled"]);
        }

        [Fact]
        public void BuildFlightPlanMessage_BeforeFirstFetch_FieldsAreNull()
        {
            var json = JObject.Parse(ProtocolMessages.BuildFlightPlanMessage(FlightPlan.Empty, vatsimCallsign: null, vatsimPilot: null));

            Assert.Equal("flightPlan", (string)json["type"]);
            Assert.Equal(JTokenType.Null, json["simbriefCallsign"].Type);
            Assert.Equal(JTokenType.Null, json["simbriefOrigin"].Type);
            Assert.Equal(JTokenType.Null, json["simbriefDestination"].Type);
            Assert.Equal(JTokenType.Null, json["simbriefAlternate"].Type);
            Assert.Equal(JTokenType.Null, json["vatsimCallsign"].Type);
            Assert.Equal(JTokenType.Null, json["vatsimOrigin"].Type);
            Assert.Equal(JTokenType.Null, json["vatsimDestination"].Type);
        }

        [Fact]
        public void BuildFlightPlanMessage_WithSimbriefValues()
        {
            var plan = new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS");

            var json = JObject.Parse(ProtocolMessages.BuildFlightPlanMessage(plan, vatsimCallsign: null, vatsimPilot: null));

            Assert.Equal("BAW123", (string)json["simbriefCallsign"]);
            Assert.Equal("EGLL", (string)json["simbriefOrigin"]);
            Assert.Equal("KJFK", (string)json["simbriefDestination"]);
            Assert.Equal("KBOS", (string)json["simbriefAlternate"]);
        }

        [Fact]
        public void BuildFlightPlanMessage_WithVatsimValues()
        {
            var simbrief = new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS");
            var vatsimPilot = new VatsimPilotInfo(callsign: "BAW123", departure: "EGLL", arrival: "KJFK");

            var json = JObject.Parse(ProtocolMessages.BuildFlightPlanMessage(simbrief, vatsimCallsign: "BAW123", vatsimPilot: vatsimPilot));

            Assert.Equal("BAW123", (string)json["vatsimCallsign"]);
            Assert.Equal("EGLL", (string)json["vatsimOrigin"]);
            Assert.Equal("KJFK", (string)json["vatsimDestination"]);
        }

        [Fact]
        public void BuildFlightPlanMessage_VatsimCallsignKnown_ButNothingFiledYet_OriginDestinationStayNull()
        {
            var json = JObject.Parse(ProtocolMessages.BuildFlightPlanMessage(FlightPlan.Empty, vatsimCallsign: "BAW123", vatsimPilot: null));

            Assert.Equal("BAW123", (string)json["vatsimCallsign"]);
            Assert.Equal(JTokenType.Null, json["vatsimOrigin"].Type);
            Assert.Equal(JTokenType.Null, json["vatsimDestination"].Type);
        }

        [Fact]
        public void BuildNearbyAircraftMessage_EmptyList()
        {
            var json = JObject.Parse(ProtocolMessages.BuildNearbyAircraftMessage(new List<NearbyAircraft>()));

            Assert.Equal("nearbyAircraft", (string)json["type"]);
            Assert.Empty((JArray)json["aircraft"]);
        }

        [Fact]
        public void BuildNearbyAircraftMessage_OneAircraft()
        {
            var aircraft = new List<NearbyAircraft> { new NearbyAircraft("BAW123", "B738", 6.2) };

            var json = JObject.Parse(ProtocolMessages.BuildNearbyAircraftMessage(aircraft));
            var entry = json["aircraft"][0];

            Assert.Equal("BAW123", (string)entry["callsign"]);
            Assert.Equal("B738", (string)entry["aircraftType"]);
            Assert.Equal(6.2, (double)entry["distanceNm"]);
        }

        [Fact]
        public void BuildSubsystemStatusMessage_IncludesAllFields()
        {
            var json = JObject.Parse(ProtocolMessages.BuildSubsystemStatusMessage(true, false, true, false, "0.1.0"));

            Assert.Equal("subsystemStatus", (string)json["type"]);
            Assert.True((bool)json["radioHostConnected"]);
            Assert.False((bool)json["simulatorConnected"]);
            Assert.True((bool)json["vatsimDataFeedConnected"]);
            Assert.False((bool)json["simbriefFetched"]);
            Assert.Equal("0.1.0", (string)json["pluginVersion"]);
        }

        [Fact]
        public void BuildOperationProgressMessage_InProgress()
        {
            var json = JObject.Parse(ProtocolMessages.BuildOperationProgressMessage("vatGlassesSync", "Updating VatGlasses file 12/24", finished: false, success: true));

            Assert.Equal("operationProgress", (string)json["type"]);
            Assert.Equal("vatGlassesSync", (string)json["operationId"]);
            Assert.Equal("Updating VatGlasses file 12/24", (string)json["status"]);
            Assert.False((bool)json["finished"]);
        }

        [Fact]
        public void BuildOperationProgressMessage_FinishedSuccess()
        {
            var json = JObject.Parse(ProtocolMessages.BuildOperationProgressMessage("vatGlassesSync", "VatGlasses data up to date", finished: true, success: true));

            Assert.True((bool)json["finished"]);
            Assert.True((bool)json["success"]);
        }

        [Fact]
        public void BuildOperationProgressMessage_FinishedFailure()
        {
            var json = JObject.Parse(ProtocolMessages.BuildOperationProgressMessage("vatGlassesSync", "VatGlasses sync incomplete", finished: true, success: false));

            Assert.True((bool)json["finished"]);
            Assert.False((bool)json["success"]);
        }

        [Fact]
        public void BuildPongMessage_EchoesClientTimestamp()
        {
            var json = JObject.Parse(ProtocolMessages.BuildPongMessage(1234567890));

            Assert.Equal("pong", (string)json["type"]);
            Assert.Equal(1234567890, (long)json["clientTimestamp"]);
            Assert.True((long)json["serverTimestamp"] > 0);
        }

        [Fact]
        public void ParseClientCommand_Ping()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"ping\",\"clientTimestamp\":1234567890}");

            Assert.Equal(ClientCommand.TypePing, command.Type);
            Assert.Equal(1234567890, command.ClientTimestamp);
        }

        [Fact]
        public void ParseClientCommand_SetSimbriefCredentials()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setSimbriefCredentials\",\"simbriefUserId\":\"12345\",\"simbriefUsername\":\"someuser\"}");

            Assert.Equal(ClientCommand.TypeSetSimbriefCredentials, command.Type);
            Assert.Equal("12345", command.SimbriefUserId);
            Assert.Equal("someuser", command.SimbriefUsername);
        }

        [Fact]
        public void ParseClientCommand_RefreshFlightPlan()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"refreshFlightPlan\"}");

            Assert.Equal(ClientCommand.TypeRefreshFlightPlan, command.Type);
        }

        [Fact]
        public void ParseClientCommand_SendPrivateMessage()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"sendPrivateMessage\",\"to\":\"EGLL_TWR\",\"message\":\"wilco\"}");

            Assert.Equal(ClientCommand.TypeSendPrivateMessage, command.Type);
            Assert.Equal("EGLL_TWR", command.To);
            Assert.Equal("wilco", command.Message);
        }

        [Fact]
        public void ParseClientCommand_SendRadioMessage()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"sendRadioMessage\",\"message\":\"request pushback\"}");

            Assert.Equal(ClientCommand.TypeSendRadioMessage, command.Type);
            Assert.Equal("request pushback", command.Message);
        }

        [Fact]
        public void ParseClientCommand_SetCom1Frequency()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setCom1Frequency\",\"megahertz\":123.725}");

            Assert.Equal(ClientCommand.TypeSetCom1Frequency, command.Type);
            Assert.Equal(123.725, command.Megahertz);
        }

        [Fact]
        public void ParseClientCommand_SetCom2Frequency()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setCom2Frequency\",\"megahertz\":118.3}");

            Assert.Equal(ClientCommand.TypeSetCom2Frequency, command.Type);
            Assert.Equal(118.3, command.Megahertz);
        }

        [Fact]
        public void ParseClientCommand_SetCom1StandbyFrequency()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setCom1StandbyFrequency\",\"megahertz\":121.9}");

            Assert.Equal(ClientCommand.TypeSetCom1StandbyFrequency, command.Type);
            Assert.Equal(121.9, command.Megahertz);
        }

        [Fact]
        public void ParseClientCommand_SetCom2StandbyFrequency()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setCom2StandbyFrequency\",\"megahertz\":121.9}");

            Assert.Equal(ClientCommand.TypeSetCom2StandbyFrequency, command.Type);
            Assert.Equal(121.9, command.Megahertz);
        }

        [Fact]
        public void ParseClientCommand_SetCom1ActiveAndStandbyFrequency()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setCom1ActiveAndStandbyFrequency\",\"megahertz\":123.725,\"standbyMegahertz\":121.9}");

            Assert.Equal(ClientCommand.TypeSetCom1ActiveAndStandbyFrequency, command.Type);
            Assert.Equal(123.725, command.Megahertz);
            Assert.Equal(121.9, command.StandbyMegahertz);
        }

        [Fact]
        public void ParseClientCommand_SetCom2ActiveAndStandbyFrequency()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setCom2ActiveAndStandbyFrequency\",\"megahertz\":118.3,\"standbyMegahertz\":121.9}");

            Assert.Equal(ClientCommand.TypeSetCom2ActiveAndStandbyFrequency, command.Type);
            Assert.Equal(118.3, command.Megahertz);
            Assert.Equal(121.9, command.StandbyMegahertz);
        }

        [Fact]
        public void ParseClientCommand_SetTransponderCode()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"setTransponderCode\",\"transponderCode\":1200}");

            Assert.Equal(ClientCommand.TypeSetTransponderCode, command.Type);
            Assert.Equal(1200, command.TransponderCode);
        }

        [Fact]
        public void ParseClientCommand_PinController()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"pinController\",\"callsign\":\"EGLL_TWR\"}");

            Assert.Equal(ClientCommand.TypePinController, command.Type);
            Assert.Equal("EGLL_TWR", command.Callsign);
        }

        [Fact]
        public void ParseClientCommand_ClearPinnedController()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"clearPinnedController\",\"callsign\":\"EGLL_TWR\"}");

            Assert.Equal(ClientCommand.TypeClearPinnedController, command.Type);
            Assert.Equal("EGLL_TWR", command.Callsign);
        }

        [Fact]
        public void ParseClientCommand_DismissSelcal()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"dismissSelcal\",\"callsign\":\"EGLL_CTR\"}");

            Assert.Equal(ClientCommand.TypeDismissSelcal, command.Type);
            Assert.Equal("EGLL_CTR", command.Callsign);
        }

        [Fact]
        public void ParseClientCommand_UnknownType_ParsesWithoutThrowing()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"somethingElse\"}");

            Assert.Equal("somethingElse", command.Type);
        }
    }
}
