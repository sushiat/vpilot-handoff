using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json;
using Newtonsoft.Json.Converters;
using Newtonsoft.Json.Serialization;

namespace Handoff.Plugin
{
    /// <summary>
    /// Builds and parses the WebSocket wire messages documented in docs/protocol.md. Pure
    /// functions -- no socket I/O -- so this is the one part of the WebSocket feature worth
    /// unit testing; HandoffWebSocketServer just calls into this and pushes bytes.
    /// </summary>
    public static class ProtocolMessages
    {
        private static readonly JsonSerializerSettings SerializerSettings = new JsonSerializerSettings
        {
            ContractResolver = new CamelCasePropertyNamesContractResolver(),
            Converters = { new StringEnumConverter(new CamelCaseNamingStrategy()) }
        };

        public static string BuildControllersMessage(IReadOnlyCollection<Controller> controllers)
        {
            var payload = new
            {
                type = "controllers",
                controllers = controllers.Select(c => new
                {
                    callsign = c.Callsign,
                    frequency = c.Frequency,
                    latitude = c.Latitude,
                    longitude = c.Longitude
                })
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static string BuildChatMessage(IReadOnlyList<ChatMessage> messages, IReadOnlyList<SelcalAlert> selcalAlerts)
        {
            var payload = new
            {
                type = "chat",
                messages = messages.Select(m => new
                {
                    channel = m.Channel,
                    direction = m.Direction,
                    peer = m.Peer,
                    text = m.Text,
                    frequencies = m.Frequencies,
                    timestamp = m.Timestamp.UtcDateTime
                }),
                selcalAlerts = selcalAlerts.Select(a => new
                {
                    from = a.From,
                    frequencies = a.Frequencies,
                    timestamp = a.Timestamp.UtcDateTime
                })
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static string BuildFlightPlanMessage(FlightPlan plan)
        {
            var payload = new
            {
                type = "flightPlan",
                callsign = plan.Callsign,
                origin = plan.Origin,
                destination = plan.Destination,
                alternate = plan.Alternate
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static string BuildRadioStateMessage(RadioState state)
        {
            var payload = new
            {
                type = "radioState",
                com1Frequency = state.Com1Frequency,
                com2Frequency = state.Com2Frequency,
                com1StandbyFrequency = state.Com1StandbyFrequency,
                com2StandbyFrequency = state.Com2StandbyFrequency,
                modeCEnabled = state.ModeCEnabled,
                transponderCode = state.TransponderCode
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static ClientCommand ParseClientCommand(string json) =>
            JsonConvert.DeserializeObject<ClientCommand>(json, SerializerSettings);
    }
}
