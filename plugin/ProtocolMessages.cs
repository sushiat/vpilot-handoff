using System;
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

        public static string BuildControllersMessage(IReadOnlyList<RankedController> controllers, double? etaMinutes = null)
        {
            var payload = new
            {
                type = "controllers",
                etaMinutes,
                controllers = controllers.Select(c => new
                {
                    callsign = c.Callsign,
                    frequency = c.Frequency,
                    latitude = c.Latitude,
                    longitude = c.Longitude,
                    cid = c.Cid,
                    name = c.Name,
                    facility = c.Facility,
                    rating = c.Rating,
                    stationName = c.StationName,
                    textAtis = c.TextAtis,
                    requestsContactMe = c.RequestsContactMe,
                    isCurrent = c.IsCurrent,
                    isContactMe = c.IsContactMe,
                    isHighlighted = c.IsHighlighted,
                    isNext = c.IsNext,
                    isLikelyNext = c.IsLikelyNext,
                    isPinned = c.IsPinned,
                    isStandbyTuned = c.IsStandbyTuned,
                    isSelcalActive = c.IsSelcalActive
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
                    from = m.From,
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

        /// <summary>
        /// Combines the SimBrief-derived plan with the actually-filed VATSIM one (the pilot's own
        /// callsign from IBroker, cross-referenced against the public data feed's pilots[]) so
        /// the client can flag a mismatch instead of silently trusting whichever loaded first --
        /// see docs/protocol.md. `vatsimCallsign` is the authoritative live value even when
        /// `vatsimPilot` is null (feed not yet caught up, or nothing filed on the network).
        /// </summary>
        public static string BuildFlightPlanMessage(FlightPlan simbriefPlan, string vatsimCallsign, VatsimPilotInfo vatsimPilot)
        {
            var payload = new
            {
                type = "flightPlan",
                simbriefCallsign = simbriefPlan.Callsign,
                simbriefOrigin = simbriefPlan.Origin,
                simbriefDestination = simbriefPlan.Destination,
                simbriefAlternate = simbriefPlan.Alternate,
                vatsimCallsign,
                vatsimOrigin = vatsimPilot?.Departure,
                vatsimDestination = vatsimPilot?.Arrival
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
                transponderCode = state.TransponderCode,
                com1TransmitEnabled = state.Com1TransmitEnabled,
                com2TransmitEnabled = state.Com2TransmitEnabled,
                com1ReceiveEnabled = state.Com1ReceiveEnabled,
                com2ReceiveEnabled = state.Com2ReceiveEnabled
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static string BuildNearbyAircraftMessage(IReadOnlyList<NearbyAircraft> aircraft)
        {
            var payload = new
            {
                type = "nearbyAircraft",
                aircraft = aircraft.Select(a => new
                {
                    callsign = a.Callsign,
                    aircraftType = a.AircraftType,
                    distanceNm = a.DistanceNm
                })
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        /// <summary>
        /// A destination change just observed on the VATSIM data feed, awaiting pilot
        /// confirmation (confirmDiversion/dismissDiversion) before the plugin drops the filed
        /// route from approach prediction -- see ControllerRankingModel.PendingDiversionDestination.
        /// destination is null whenever nothing is pending, same resendable-full-state shape as
        /// the other Build*Message methods here (not one-shot like operationProgress).
        /// </summary>
        public static string BuildDiversionPendingMessage(string destination)
        {
            var payload = new
            {
                type = "diversionPending",
                destination
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static string BuildSubsystemStatusMessage(bool radioHostConnected, bool simulatorConnected, bool vatsimDataFeedConnected, bool simbriefFetched, string pluginVersion)
        {
            var payload = new
            {
                type = "subsystemStatus",
                radioHostConnected,
                simulatorConnected,
                vatsimDataFeedConnected,
                simbriefFetched,
                pluginVersion
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        /// <summary>
        /// Unlike every other Build*Message here, this isn't a resendable full-state snapshot --
        /// it's one step of an in-progress background operation (see OperationProgressModel and
        /// docs/protocol.md). finished=true is the "end of update" signal clients swap their
        /// spinner for a success/failure icon on; success is only meaningful once finished=true.
        /// </summary>
        public static string BuildOperationProgressMessage(string operationId, string status, bool finished, bool success)
        {
            var payload = new
            {
                type = "operationProgress",
                operationId,
                status,
                finished,
                success
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        /// <summary>
        /// Reply to a client's `authenticate` command (docs/protocol.md, issue #15). `token` is
        /// only present when a *new* token was just issued (a successful pairing-code exchange) --
        /// a returning client validating an already-known token gets success with no token field,
        /// nothing new to persist. `reason` is only meaningful when success is false.
        /// </summary>
        public static string BuildAuthResultMessage(bool success, string token = null, string reason = null)
        {
            var payload = new
            {
                type = "authResult",
                success,
                token,
                reason
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static string BuildPongMessage(long? clientTimestamp)
        {
            var payload = new
            {
                type = "pong",
                clientTimestamp,
                serverTimestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        public static ClientCommand ParseClientCommand(string json) =>
            JsonConvert.DeserializeObject<ClientCommand>(json, SerializerSettings);
    }
}
