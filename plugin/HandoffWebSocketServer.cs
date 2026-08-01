using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using Fleck;

namespace Handoff.Plugin
{
    /// <summary>
    /// Serves docs/protocol.md over a Fleck-hosted WebSocket. Raw TCP sockets, not
    /// HttpListener/http.sys -- binding to a LAN-reachable address (needed so the Android
    /// tablet, a different device, can connect) would otherwise require running vPilot
    /// elevated or a one-time admin netsh URL-ACL reservation. Fleck needs neither.
    ///
    /// Lifecycle: started once in HandoffPlugin.Initialize and lives for the plugin's
    /// lifetime, unlike RadioStateModel -- this is just an in-process listener with no
    /// spawned-process question to solve, and the Android app should be able to connect and
    /// see plugin status even before the pilot connects to VATSIM.
    /// </summary>
    public sealed class HandoffWebSocketServer
    {
        public const int Port = 48765;
        private const string Address = "wss://0.0.0.0:48765";

        // Static until the plugin has a real versioning scheme -- see docs/protocol.md.
        private const string PluginVersion = "0.1.0";

        private readonly object _gate = new object();
        // Only ever holds sockets that have completed device authorization (issue #15) --
        // nothing is sent to (or accepted as a command from) a socket before it's in here. Not
        // a superset tracked separately from "all open sockets": an unauthenticated socket gets
        // no snapshot/broadcast traffic at all, so there's nothing to track for it beyond what
        // Fleck itself already manages.
        private readonly HashSet<IWebSocketConnection> _authenticatedSockets = new HashSet<IWebSocketConnection>();
        private readonly ControllerRankingModel _controllerRanking;
        private readonly ChatModel _chatModel;
        private readonly RadioStateModel _radioState;
        private readonly FlightPlanModel _flightPlanState;
        private readonly VatsimDataFeedModel _vatsimDataFeed;
        private readonly NearbyAircraftModel _nearbyAircraft;
        private readonly HandoffControllerStateModel _controllerState;
        private readonly PilotSessionModel _pilotSession;
        private readonly OperationProgressModel _operationProgress;
        private readonly HandoffPairedClientStore _pairedClients;
        private readonly HandoffPairingSession _pairingSession;
        private readonly VatGlassesDataModel _vatGlassesData;
        private readonly VatSpyDataModel _vatSpyData;
        private readonly DebugSnapshotService _debugSnapshotService;
        private readonly Action<string> _logDebug;
        private readonly X509Certificate2 _certificate;
        private WebSocketServer _server;
        private Timer _broadcastTimer;

        // Decoupled from Recompute() -- internal ranking stays fully event-driven/reactive, but
        // diffing "did anything meaningful change" is intractable for SimConnect-driven fields
        // (distance/heading/altitude) without running the whole bucket 6-9 geometry anyway, so
        // the wire broadcast just goes out on a fixed cadence instead.
        private static readonly TimeSpan BroadcastInterval = TimeSpan.FromSeconds(1);

        public HandoffWebSocketServer(ControllerRankingModel controllerRanking, ChatModel chatModel, RadioStateModel radioState, FlightPlanModel flightPlanState, VatsimDataFeedModel vatsimDataFeed, NearbyAircraftModel nearbyAircraft, HandoffControllerStateModel controllerState, PilotSessionModel pilotSession, OperationProgressModel operationProgress, X509Certificate2 certificate, HandoffPairedClientStore pairedClients, HandoffPairingSession pairingSession, VatGlassesDataModel vatGlassesData, VatSpyDataModel vatSpyData, Action<string> logDebug = null)
        {
            _controllerRanking = controllerRanking ?? throw new ArgumentNullException(nameof(controllerRanking));
            _chatModel = chatModel ?? throw new ArgumentNullException(nameof(chatModel));
            _radioState = radioState ?? throw new ArgumentNullException(nameof(radioState));
            _flightPlanState = flightPlanState ?? throw new ArgumentNullException(nameof(flightPlanState));
            _vatsimDataFeed = vatsimDataFeed ?? throw new ArgumentNullException(nameof(vatsimDataFeed));
            _nearbyAircraft = nearbyAircraft ?? throw new ArgumentNullException(nameof(nearbyAircraft));
            _controllerState = controllerState ?? throw new ArgumentNullException(nameof(controllerState));
            _pilotSession = pilotSession ?? throw new ArgumentNullException(nameof(pilotSession));
            _operationProgress = operationProgress ?? throw new ArgumentNullException(nameof(operationProgress));
            _certificate = certificate ?? throw new ArgumentNullException(nameof(certificate));
            _pairedClients = pairedClients ?? throw new ArgumentNullException(nameof(pairedClients));
            _pairingSession = pairingSession ?? throw new ArgumentNullException(nameof(pairingSession));
            _vatGlassesData = vatGlassesData ?? throw new ArgumentNullException(nameof(vatGlassesData));
            _vatSpyData = vatSpyData ?? throw new ArgumentNullException(nameof(vatSpyData));
            _logDebug = logDebug;

            _debugSnapshotService = new DebugSnapshotService(
                _controllerRanking, _radioState, _flightPlanState, _vatsimDataFeed, _controllerState,
                _vatGlassesData, _vatSpyData, _pilotSession, _operationProgress, _pairedClients, _pairingSession,
                () => { lock (_gate) { return _authenticatedSockets.Count; } },
                PluginVersion, _logDebug);
        }

        public void Start()
        {
            try
            {
                _server = new WebSocketServer(Address)
                {
                    Certificate = _certificate,
                    EnabledSslProtocols = SslProtocols.Tls12
                };
                _server.Start(socket =>
                {
                    socket.OnOpen = () => OnOpen(socket);
                    socket.OnClose = () => OnClose(socket);
                    socket.OnMessage = message => OnMessage(message, socket);
                });

                _broadcastTimer = new Timer(_ =>
                {
                    Broadcast(ProtocolMessages.BuildControllersMessage(_controllerRanking.Current, _controllerRanking.EtaMinutes, _controllerRanking.PlanWideDebugExplain));
                    Broadcast(ProtocolMessages.BuildDiversionPendingMessage(_controllerRanking.PendingDiversionDestination));
                    // originMismatch (issue #68) is telemetry-driven and can flip every Recompute
                    // tick, not just on the three Changed events flightPlan is otherwise wired to
                    // below -- resent here on the same cadence as its IsOriginMismatched sibling
                    // PendingDiversionDestination above.
                    Broadcast(BuildFlightPlanMessage());
                }, null, BroadcastInterval, BroadcastInterval);
                _chatModel.Changed += (s, e) => Broadcast(ProtocolMessages.BuildChatMessage(_chatModel.Messages, _chatModel.SelcalAlerts));
                _radioState.Changed += (s, e) => Broadcast(ProtocolMessages.BuildRadioStateMessage(_radioState.Current));
                _nearbyAircraft.Changed += (s, e) => Broadcast(ProtocolMessages.BuildNearbyAircraftMessage(_nearbyAircraft.Current));

                // flightPlan now blends SimBrief (FlightPlanModel) with the actually-filed VATSIM
                // plan (PilotSessionModel's own callsign, cross-referenced against
                // VatsimDataFeedModel's pilots[]), so any of the three changing needs to
                // re-broadcast it, not just a SimBrief refetch.
                _flightPlanState.Changed += (s, e) => Broadcast(BuildFlightPlanMessage());
                _pilotSession.Changed += (s, e) => Broadcast(BuildFlightPlanMessage());
                _vatsimDataFeed.Changed += (s, e) => Broadcast(BuildFlightPlanMessage());

                // Each of these three also feeds the subsystemStatus message, so any of them
                // changing needs to re-broadcast it too, not just their own message type.
                _radioState.Changed += (s, e) => Broadcast(BuildSubsystemStatusMessage());
                _vatsimDataFeed.Changed += (s, e) => Broadcast(BuildSubsystemStatusMessage());
                _flightPlanState.Changed += (s, e) => Broadcast(BuildSubsystemStatusMessage());

                // A stream, not a snapshot -- broadcast just the one operation that changed, not
                // the whole set of currently-active operations (see OperationProgressModel).
                _operationProgress.Changed += (s, e) => Broadcast(ProtocolMessages.BuildOperationProgressMessage(e.OperationId, e.Status, e.Finished, e.Success));

                Log("Listening on " + Address);
            }
            catch (Exception ex)
            {
                Log("Failed to start WebSocket server: " + ex);
            }
        }

        private void OnOpen(IWebSocketConnection socket)
        {
            // Deliberately not added to _authenticatedSockets and sent nothing at all yet
            // (issue #15) -- an unauthenticated socket is entirely mute from this side until it
            // completes the authenticate handshake in OnMessage below. No point preparing or
            // sending anything to a client this plugin doesn't yet recognize.
            Log("Client connected (unauthenticated): " + socket.ConnectionInfo.ClientIpAddress);
        }

        /// <summary>Full current-state catch-up burst, sent once a socket authenticates --
        /// previously sent unconditionally from OnOpen, before device authorization existed.</summary>
        private void SendSnapshotTo(IWebSocketConnection socket)
        {
            socket.Send(ProtocolMessages.BuildControllersMessage(_controllerRanking.Current, _controllerRanking.EtaMinutes, _controllerRanking.PlanWideDebugExplain));
            socket.Send(ProtocolMessages.BuildDiversionPendingMessage(_controllerRanking.PendingDiversionDestination));
            socket.Send(ProtocolMessages.BuildChatMessage(_chatModel.Messages, _chatModel.SelcalAlerts));
            socket.Send(ProtocolMessages.BuildRadioStateMessage(_radioState.Current));
            socket.Send(BuildFlightPlanMessage());
            socket.Send(ProtocolMessages.BuildNearbyAircraftMessage(_nearbyAircraft.Current));
            socket.Send(BuildSubsystemStatusMessage());

            // Catch up a client connecting mid-operation -- otherwise it'd see nothing until the
            // next step happens to fire.
            foreach (var operation in _operationProgress.ActiveOperations)
            {
                socket.Send(ProtocolMessages.BuildOperationProgressMessage(operation.Key, operation.Value, finished: false, success: true));
            }
        }

        private string BuildFlightPlanMessage()
        {
            var vatsimCallsign = _pilotSession.Callsign;
            VatsimPilotInfo vatsimPilot = null;
            if (vatsimCallsign != null) _vatsimDataFeed.Pilots.TryGetValue(vatsimCallsign, out vatsimPilot);
            return ProtocolMessages.BuildFlightPlanMessage(_flightPlanState.Current, vatsimCallsign, vatsimPilot, _controllerRanking.IsOriginMismatched, _controllerRanking.IsVatsimCidMismatched);
        }

        private string BuildSubsystemStatusMessage() =>
            ProtocolMessages.BuildSubsystemStatusMessage(
                _radioState.IsRadioHostConnected,
                _radioState.IsSimulatorConnected,
                _vatsimDataFeed.IsConnected,
                _flightPlanState.HasFetchedSuccessfully,
                PluginVersion,
                _controllerRanking.DebugModeEnabled ? BuildSystemsDebugInfo() : null);

        /// <summary>Issue #65 -- the lean "Systems" section of the debug overlay, only ever built while debug mode is on (see SystemsDebugInfo's own doc comment for why this stays separate from the exhaustive per-subsystem snapshot detail).</summary>
        private SystemsDebugInfo BuildSystemsDebugInfo()
        {
            var radio = _radioState.BuildDebugSnapshot();
            var feed = _vatsimDataFeed.BuildDebugSnapshot();
            var flightPlan = _flightPlanState.BuildDebugSnapshot();
            var vatGlasses = _vatGlassesData.BuildDebugSnapshot();
            var vatSpy = _vatSpyData.BuildDebugSnapshot();
            var pairing = _pairedClients.BuildDebugSnapshot(_pairingSession.IsCodeCurrentlyActive);
            int authenticatedSocketCount;
            lock (_gate) { authenticatedSocketCount = _authenticatedSockets.Count; }

            return new SystemsDebugInfo(
                radio.RadioHostConnected, radio.SimulatorConnected, radio.Telemetry?.Timestamp,
                feed.Connected, feed.LastPollAt,
                flightPlan.HasFetchedSuccessfully, flightPlan.LastError,
                vatGlasses.LoadedRegionFiles.Count, vatSpy.BoundaryCount,
                pairing.PairedClientCount, authenticatedSocketCount, _operationProgress.ActiveOperations.Count);
        }

        private void OnClose(IWebSocketConnection socket)
        {
            lock (_gate) { _authenticatedSockets.Remove(socket); }
            Log("Client disconnected: " + socket.ConnectionInfo.ClientIpAddress);
        }

        private void OnMessage(string json, IWebSocketConnection socket)
        {
            ClientCommand command;
            try
            {
                command = ProtocolMessages.ParseClientCommand(json);
            }
            catch (Exception ex)
            {
                Log("Failed to parse client message: " + ex.Message);
                return;
            }

            Log("Received client command: " + json);

            if (command?.Type == ClientCommand.TypeAuthenticate)
            {
                HandleAuthenticate(command, socket);
                return;
            }

            bool authenticated;
            lock (_gate) { authenticated = _authenticatedSockets.Contains(socket); }
            if (!authenticated)
            {
                // Not just "don't act on it" -- don't even acknowledge it. A client that hasn't
                // authenticated yet gets silence for anything but authenticate, same as it gets
                // silence instead of a snapshot in OnOpen (issue #15).
                Log("Ignoring command from unauthenticated client: " + command?.Type);
                return;
            }

            switch (command?.Type)
            {
                case ClientCommand.TypePing:
                    socket?.Send(ProtocolMessages.BuildPongMessage(command.ClientTimestamp));
                    break;
                case ClientCommand.TypeSendPrivateMessage:
                    _chatModel.SendPrivateMessage(command.To, command.Message);
                    break;
                case ClientCommand.TypeSendRadioMessage:
                    _chatModel.SendRadioMessage(command.Message);
                    break;
                case ClientCommand.TypeSetCom1Frequency:
                    if (command.Megahertz.HasValue) _radioState.SetCom1Frequency(command.Megahertz.Value);
                    break;
                case ClientCommand.TypeSetCom2Frequency:
                    if (command.Megahertz.HasValue) _radioState.SetCom2Frequency(command.Megahertz.Value);
                    break;
                case ClientCommand.TypeSetCom1StandbyFrequency:
                    if (command.Megahertz.HasValue) _radioState.SetCom1StandbyFrequency(command.Megahertz.Value);
                    break;
                case ClientCommand.TypeSetCom2StandbyFrequency:
                    if (command.Megahertz.HasValue) _radioState.SetCom2StandbyFrequency(command.Megahertz.Value);
                    break;
                case ClientCommand.TypeSetCom1ActiveAndStandbyFrequency:
                    if (command.Megahertz.HasValue && command.StandbyMegahertz.HasValue)
                        _radioState.SetCom1ActiveAndStandbyFrequency(command.Megahertz.Value, command.StandbyMegahertz.Value);
                    break;
                case ClientCommand.TypeSetCom2ActiveAndStandbyFrequency:
                    if (command.Megahertz.HasValue && command.StandbyMegahertz.HasValue)
                        _radioState.SetCom2ActiveAndStandbyFrequency(command.Megahertz.Value, command.StandbyMegahertz.Value);
                    break;
                case ClientCommand.TypeSetTransponderCode:
                    if (command.TransponderCode.HasValue) _radioState.SetTransponderCode(command.TransponderCode.Value);
                    break;
                case ClientCommand.TypeSelectCom1Transmitter:
                    _radioState.SelectCom1Transmitter();
                    break;
                case ClientCommand.TypeSelectCom2Transmitter:
                    _radioState.SelectCom2Transmitter();
                    break;
                case ClientCommand.TypeSetCom1ReceiveEnabled:
                    if (command.Enabled.HasValue) _radioState.SetCom1ReceiveEnabled(command.Enabled.Value);
                    break;
                case ClientCommand.TypeSetCom2ReceiveEnabled:
                    if (command.Enabled.HasValue) _radioState.SetCom2ReceiveEnabled(command.Enabled.Value);
                    break;
                case ClientCommand.TypeSetSimbriefCredentials:
                    _flightPlanState.SetSimbriefCredentials(command.SimbriefUserId, command.SimbriefUsername);
                    break;
                case ClientCommand.TypeRefreshFlightPlan:
                    _ = _flightPlanState.RefreshAsync();
                    break;
                case ClientCommand.TypePinController:
                    _controllerState.SetPinnedController(command.Callsign);
                    break;
                case ClientCommand.TypeClearPinnedController:
                    _controllerState.ClearPinnedController(command.Callsign);
                    break;
                case ClientCommand.TypeDismissSelcal:
                    _controllerState.ClearSelcal(command.Callsign);
                    break;
                case ClientCommand.TypeConfirmDiversion:
                    _controllerRanking.ConfirmDiversion();
                    break;
                case ClientCommand.TypeDismissDiversion:
                    _controllerRanking.DismissDiversion();
                    break;
                case ClientCommand.TypeSetDebugMode:
                    _controllerRanking.SetDebugMode(command.Enabled == true);
                    break;
                case ClientCommand.TypeSaveDebugSnapshot:
                    HandleSaveDebugSnapshot(command, socket);
                    break;
                case ClientCommand.TypeAttachDebugSnapshotScreenshot:
                    if (!string.IsNullOrEmpty(command.SnapshotId) && !string.IsNullOrEmpty(command.ScreenshotPngBase64))
                        _debugSnapshotService.TrySaveScreenshot(command.SnapshotId, command.ScreenshotPngBase64);
                    break;
                case ClientCommand.TypeNameDebugSnapshot:
                    HandleNameDebugSnapshot(command, socket);
                    break;
                default:
                    Log("Unknown client message type: " + command?.Type);
                    break;
            }
        }

        /// <summary>
        /// Handles a client's authenticate command (docs/protocol.md, issue #15) -- the one
        /// message type an unauthenticated socket is allowed to send and get a reply to.
        /// Token path: validates against HandoffPairedClientStore, no new token issued on
        /// success (the client already has a working one). PairingCode path: validates against
        /// HandoffPairingSession's currently displayed code; success issues and persists a fresh
        /// token. Neither field set means "I have nothing," which just (re)shows the pairing
        /// window without needing a code guess at all.
        /// </summary>
        private void HandleAuthenticate(ClientCommand command, IWebSocketConnection socket)
        {
            if (!string.IsNullOrEmpty(command.Token))
            {
                if (_pairedClients.IsTokenValid(command.Token))
                {
                    lock (_gate) { _authenticatedSockets.Add(socket); }
                    Log("Client authenticated via token: " + socket.ConnectionInfo.ClientIpAddress);
                    socket.Send(ProtocolMessages.BuildAuthResultMessage(success: true));
                    SendSnapshotTo(socket);
                    return;
                }

                Log("Rejected unknown/revoked token from " + socket.ConnectionInfo.ClientIpAddress);
                _pairingSession.EnsureActiveCode();
                socket.Send(ProtocolMessages.BuildAuthResultMessage(success: false, reason: "pairingRequired"));
                return;
            }

            if (!string.IsNullOrEmpty(command.PairingCode))
            {
                if (_pairingSession.TryConsumeCode(command.PairingCode))
                {
                    var token = _pairedClients.IssueToken(command.DeviceId);
                    lock (_gate) { _authenticatedSockets.Add(socket); }
                    Log("Client paired via code: " + socket.ConnectionInfo.ClientIpAddress);
                    socket.Send(ProtocolMessages.BuildAuthResultMessage(success: true, token: token));
                    SendSnapshotTo(socket);
                    return;
                }

                Log("Rejected invalid/expired pairing code from " + socket.ConnectionInfo.ClientIpAddress);
                socket.Send(ProtocolMessages.BuildAuthResultMessage(success: false, reason: "invalidCode"));
                return;
            }

            // Bare authenticate -- "I have nothing yet." Shows (or refreshes) the pairing window
            // so the pilot can read a code off it, but there's no guess to validate here.
            _pairingSession.EnsureActiveCode();
            socket.Send(ProtocolMessages.BuildAuthResultMessage(success: false, reason: "pairingRequired"));
        }

        /// <summary>Issue #65 section 4 -- writes the snapshot synchronously on this Fleck message
        /// thread (nothing else queued ahead of it, per the issue's own accuracy requirement)
        /// before replying, so debugSnapshotSaved genuinely means "the file is on disk."</summary>
        private void HandleSaveDebugSnapshot(ClientCommand command, IWebSocketConnection socket)
        {
            if (string.IsNullOrEmpty(command.SnapshotId))
            {
                Log("Ignoring saveDebugSnapshot with no snapshotId.");
                return;
            }

            try
            {
                var path = _debugSnapshotService.SaveSnapshot(command.SnapshotId, command.AppVersion);
                socket.Send(ProtocolMessages.BuildDebugSnapshotSavedMessage(command.SnapshotId, path));
            }
            catch (Exception ex)
            {
                Log("Failed to save debug snapshot: " + ex.Message);
            }
        }

        /// <summary>Issue #73b -- attaches a pilot-chosen name to an already-saved snapshot,
        /// strictly after the fact. Always replies with debugSnapshotNamed, success or not, so
        /// the client can show the pilot a clear result either way.</summary>
        private void HandleNameDebugSnapshot(ClientCommand command, IWebSocketConnection socket)
        {
            if (string.IsNullOrEmpty(command.SnapshotId) || string.IsNullOrEmpty(command.Name))
            {
                Log("Ignoring nameDebugSnapshot with no snapshotId/name.");
                return;
            }

            var (success, error) = _debugSnapshotService.RenameSnapshot(command.SnapshotId, command.Name);
            socket.Send(ProtocolMessages.BuildDebugSnapshotNamedMessage(command.SnapshotId, success, error));
        }

        private void Broadcast(string message)
        {
            List<IWebSocketConnection> sockets;
            lock (_gate) { sockets = _authenticatedSockets.ToList(); }

            foreach (var socket in sockets)
            {
                try
                {
                    socket.Send(message);
                }
                catch (Exception ex)
                {
                    Log("Failed to send to client: " + ex.Message);
                }
            }
        }

        private void Log(string message)
        {
            var line = "HandoffWebSocketServer: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
