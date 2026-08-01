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

        /// <summary>`debug` (issue #65) is null whenever debug mode is off -- both the top-level
        /// plugin-wide context and every per-controller entry's own debug object, matching the
        /// rest of this protocol's additive/nullable compatibility rule.</summary>
        public static string BuildControllersMessage(IReadOnlyList<RankedController> controllers, double? etaMinutes = null, RankingDebugExplain debug = null)
        {
            var payload = new
            {
                type = "controllers",
                etaMinutes,
                debug = debug == null ? null : new
                {
                    phaseOfFlight = debug.PhaseOfFlight,
                    hasTakenOffThisSession = debug.HasTakenOffThisSession,
                    ownshipLatitude = debug.OwnshipLatitude,
                    ownshipLongitude = debug.OwnshipLongitude,
                    ownshipAltitudeTrue = debug.OwnshipAltitudeTrue,
                    ownshipAltitudeAgl = debug.OwnshipAltitudeAgl,
                    ownshipGroundspeedKt = debug.OwnshipGroundspeedKt,
                    ownshipHeadingTrue = debug.OwnshipHeadingTrue,
                    ownshipTrackTrue = debug.OwnshipTrackTrue,
                    com1TunedCallsign = debug.Com1TunedCallsign,
                    com2TunedCallsign = debug.Com2TunedCallsign,
                    activeRouteWaypoint = debug.ActiveRouteWaypoint,
                    lastPassedWaypoint = debug.LastPassedWaypoint,
                    etaCalculationDetail = debug.EtaCalculationDetail
                },
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
                    isSelcalActive = c.IsSelcalActive,
                    debug = c.DebugExplain == null ? null : new
                    {
                        bucket = c.DebugExplain.Bucket,
                        bucketName = c.DebugExplain.BucketName,
                        reason = c.DebugExplain.Reason,
                        distanceNm = c.DebugExplain.DistanceNm,
                        vatGlassesSectorMatch = c.DebugExplain.VatGlassesSectorMatch,
                        vatSpyPolygonMatch = c.DebugExplain.VatSpyPolygonMatch,
                        routeMatch = c.DebugExplain.RouteMatch,
                        hysteresisState = c.DebugExplain.HysteresisState,
                        hysteresisPendingBucket = c.DebugExplain.HysteresisPendingBucket,
                        hysteresisPendingSince = c.DebugExplain.HysteresisPendingSince,
                        candidateRank = c.DebugExplain.CandidateRank
                    }
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

        /// <summary>`systemsDebug` (issue #65) is null whenever debug mode is off -- see SystemsDebugInfo's own doc comment.</summary>
        public static string BuildSubsystemStatusMessage(bool radioHostConnected, bool simulatorConnected, bool vatsimDataFeedConnected, bool simbriefFetched, string pluginVersion, SystemsDebugInfo systemsDebug = null)
        {
            var payload = new
            {
                type = "subsystemStatus",
                radioHostConnected,
                simulatorConnected,
                vatsimDataFeedConnected,
                simbriefFetched,
                pluginVersion,
                systemsDebug = systemsDebug == null ? null : new
                {
                    radioHostConnected = systemsDebug.RadioHostConnected,
                    simulatorConnected = systemsDebug.SimulatorConnected,
                    lastTelemetryAt = systemsDebug.LastTelemetryAt,
                    vatsimFeedConnected = systemsDebug.VatsimFeedConnected,
                    vatsimFeedLastPollAt = systemsDebug.VatsimFeedLastPollAt,
                    simbriefFetchedSuccessfully = systemsDebug.SimbriefFetchedSuccessfully,
                    simbriefLastError = systemsDebug.SimbriefLastError,
                    vatGlassesLoadedRegionCount = systemsDebug.VatGlassesLoadedRegionCount,
                    vatSpyBoundaryCount = systemsDebug.VatSpyBoundaryCount,
                    pairedClientCount = systemsDebug.PairedClientCount,
                    authenticatedSocketCount = systemsDebug.AuthenticatedSocketCount,
                    activeOperationCount = systemsDebug.ActiveOperationCount
                }
            };
            return JsonConvert.SerializeObject(payload, SerializerSettings);
        }

        /// <summary>Reply to saveDebugSnapshot once the file write completes (issue #65) -- also the client's cue to now send the optional follow-up screenshot.</summary>
        public static string BuildDebugSnapshotSavedMessage(string snapshotId, string path)
        {
            var payload = new
            {
                type = "debugSnapshotSaved",
                snapshotId,
                path
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
