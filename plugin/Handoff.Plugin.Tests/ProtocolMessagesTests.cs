using System;
using System.Collections.Generic;
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
                new RankedController("EGLL_TWR", 23725, 51.4775, -0.4614, 1234567, "John Smith", 4, 5, true, true, false, true, false)
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
            Assert.True((bool)controller["isLikelyNextCandidate"]);
            Assert.False((bool)controller["isApproaching"]);
        }

        [Fact]
        public void BuildControllersMessage_UnenrichedController_EnrichmentFieldsAreNull()
        {
            var controllers = new List<RankedController>
            {
                new RankedController("EGLL_TWR", 23725, 51.4775, -0.4614, null, null, null, null, false, false, false, false, false)
            };

            var json = JObject.Parse(ProtocolMessages.BuildControllersMessage(controllers));
            var controller = json["controllers"][0];

            Assert.Equal(JTokenType.Null, controller["cid"].Type);
            Assert.Equal(JTokenType.Null, controller["name"].Type);
            Assert.Equal(JTokenType.Null, controller["facility"].Type);
            Assert.Equal(JTokenType.Null, controller["rating"].Type);
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
            var state = new RadioState(null, null, null, null, false, null, DateTimeOffset.UtcNow);

            var json = JObject.Parse(ProtocolMessages.BuildRadioStateMessage(state));

            Assert.Equal("radioState", (string)json["type"]);
            Assert.Equal(JTokenType.Null, json["com1Frequency"].Type);
            Assert.Equal(JTokenType.Null, json["com2Frequency"].Type);
            Assert.Equal(JTokenType.Null, json["com1StandbyFrequency"].Type);
            Assert.Equal(JTokenType.Null, json["com2StandbyFrequency"].Type);
            Assert.False((bool)json["modeCEnabled"]);
            Assert.Equal(JTokenType.Null, json["transponderCode"].Type);
        }

        [Fact]
        public void BuildRadioStateMessage_WithValues()
        {
            var state = new RadioState(23725, 18000, 21000, 19000, true, 1200, DateTimeOffset.UtcNow);

            var json = JObject.Parse(ProtocolMessages.BuildRadioStateMessage(state));

            Assert.Equal(23725, (int)json["com1Frequency"]);
            Assert.Equal(18000, (int)json["com2Frequency"]);
            Assert.Equal(21000, (int)json["com1StandbyFrequency"]);
            Assert.Equal(19000, (int)json["com2StandbyFrequency"]);
            Assert.True((bool)json["modeCEnabled"]);
            Assert.Equal(1200, (int)json["transponderCode"]);
        }

        [Fact]
        public void BuildFlightPlanMessage_BeforeFirstFetch_FieldsAreNull()
        {
            var json = JObject.Parse(ProtocolMessages.BuildFlightPlanMessage(FlightPlan.Empty));

            Assert.Equal("flightPlan", (string)json["type"]);
            Assert.Equal(JTokenType.Null, json["callsign"].Type);
            Assert.Equal(JTokenType.Null, json["origin"].Type);
            Assert.Equal(JTokenType.Null, json["destination"].Type);
            Assert.Equal(JTokenType.Null, json["alternate"].Type);
        }

        [Fact]
        public void BuildFlightPlanMessage_WithValues()
        {
            var plan = new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS");

            var json = JObject.Parse(ProtocolMessages.BuildFlightPlanMessage(plan));

            Assert.Equal("BAW123", (string)json["callsign"]);
            Assert.Equal("EGLL", (string)json["origin"]);
            Assert.Equal("KJFK", (string)json["destination"]);
            Assert.Equal("KBOS", (string)json["alternate"]);
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
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"clearPinnedController\"}");

            Assert.Equal(ClientCommand.TypeClearPinnedController, command.Type);
        }

        [Fact]
        public void ParseClientCommand_UnknownType_ParsesWithoutThrowing()
        {
            var command = ProtocolMessages.ParseClientCommand("{\"type\":\"somethingElse\"}");

            Assert.Equal("somethingElse", command.Type);
        }
    }
}
