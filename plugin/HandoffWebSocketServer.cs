using System;
using System.Collections.Generic;
using System.Linq;
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
        private const string Address = "ws://0.0.0.0:48765";

        // Static until the plugin has a real versioning scheme -- see docs/protocol.md.
        private const string PluginVersion = "0.1.0";

        private readonly object _gate = new object();
        private readonly List<IWebSocketConnection> _sockets = new List<IWebSocketConnection>();
        private readonly ControllerRankingModel _controllerRanking;
        private readonly ChatModel _chatModel;
        private readonly RadioStateModel _radioState;
        private readonly FlightPlanModel _flightPlanState;
        private readonly VatsimDataFeedModel _vatsimDataFeed;
        private readonly NearbyAircraftModel _nearbyAircraft;
        private readonly SelcalActiveModel _selcalActive;
        private readonly Action<string> _logDebug;
        private WebSocketServer _server;

        public HandoffWebSocketServer(ControllerRankingModel controllerRanking, ChatModel chatModel, RadioStateModel radioState, FlightPlanModel flightPlanState, VatsimDataFeedModel vatsimDataFeed, NearbyAircraftModel nearbyAircraft, SelcalActiveModel selcalActive, Action<string> logDebug = null)
        {
            _controllerRanking = controllerRanking ?? throw new ArgumentNullException(nameof(controllerRanking));
            _chatModel = chatModel ?? throw new ArgumentNullException(nameof(chatModel));
            _radioState = radioState ?? throw new ArgumentNullException(nameof(radioState));
            _flightPlanState = flightPlanState ?? throw new ArgumentNullException(nameof(flightPlanState));
            _vatsimDataFeed = vatsimDataFeed ?? throw new ArgumentNullException(nameof(vatsimDataFeed));
            _nearbyAircraft = nearbyAircraft ?? throw new ArgumentNullException(nameof(nearbyAircraft));
            _selcalActive = selcalActive ?? throw new ArgumentNullException(nameof(selcalActive));
            _logDebug = logDebug;
        }

        public void Start()
        {
            try
            {
                _server = new WebSocketServer(Address);
                _server.Start(socket =>
                {
                    socket.OnOpen = () => OnOpen(socket);
                    socket.OnClose = () => OnClose(socket);
                    socket.OnMessage = message => OnMessage(message, socket);
                });

                _controllerRanking.Changed += (s, e) => Broadcast(ProtocolMessages.BuildControllersMessage(_controllerRanking.Current));
                _chatModel.Changed += (s, e) => Broadcast(ProtocolMessages.BuildChatMessage(_chatModel.Messages, _chatModel.SelcalAlerts));
                _radioState.Changed += (s, e) => Broadcast(ProtocolMessages.BuildRadioStateMessage(_radioState.Current));
                _flightPlanState.Changed += (s, e) => Broadcast(ProtocolMessages.BuildFlightPlanMessage(_flightPlanState.Current));
                _nearbyAircraft.Changed += (s, e) => Broadcast(ProtocolMessages.BuildNearbyAircraftMessage(_nearbyAircraft.Current));

                // Each of these three also feeds the subsystemStatus message, so any of them
                // changing needs to re-broadcast it too, not just their own message type.
                _radioState.Changed += (s, e) => Broadcast(BuildSubsystemStatusMessage());
                _vatsimDataFeed.Changed += (s, e) => Broadcast(BuildSubsystemStatusMessage());
                _flightPlanState.Changed += (s, e) => Broadcast(BuildSubsystemStatusMessage());

                Log("Listening on " + Address);
            }
            catch (Exception ex)
            {
                Log("Failed to start WebSocket server: " + ex);
            }
        }

        private void OnOpen(IWebSocketConnection socket)
        {
            lock (_gate) { _sockets.Add(socket); }
            Log("Client connected: " + socket.ConnectionInfo.ClientIpAddress);

            socket.Send(ProtocolMessages.BuildControllersMessage(_controllerRanking.Current));
            socket.Send(ProtocolMessages.BuildChatMessage(_chatModel.Messages, _chatModel.SelcalAlerts));
            socket.Send(ProtocolMessages.BuildRadioStateMessage(_radioState.Current));
            socket.Send(ProtocolMessages.BuildFlightPlanMessage(_flightPlanState.Current));
            socket.Send(ProtocolMessages.BuildNearbyAircraftMessage(_nearbyAircraft.Current));
            socket.Send(BuildSubsystemStatusMessage());
        }

        private string BuildSubsystemStatusMessage() =>
            ProtocolMessages.BuildSubsystemStatusMessage(
                _radioState.IsRadioHostConnected,
                _radioState.IsSimulatorConnected,
                _vatsimDataFeed.IsConnected,
                _flightPlanState.HasFetchedSuccessfully,
                PluginVersion);

        private void OnClose(IWebSocketConnection socket)
        {
            lock (_gate) { _sockets.Remove(socket); }
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
                case ClientCommand.TypeSetTransponderCode:
                    if (command.TransponderCode.HasValue) _radioState.SetTransponderCode(command.TransponderCode.Value);
                    break;
                case ClientCommand.TypeSetSimbriefCredentials:
                    _flightPlanState.SetSimbriefCredentials(command.SimbriefUserId, command.SimbriefUsername);
                    break;
                case ClientCommand.TypeRefreshFlightPlan:
                    _ = _flightPlanState.RefreshAsync();
                    break;
                case ClientCommand.TypePinController:
                    _controllerRanking.SetPinnedController(command.Callsign);
                    break;
                case ClientCommand.TypeClearPinnedController:
                    _controllerRanking.ClearPinnedController();
                    break;
                case ClientCommand.TypeDismissSelcal:
                    _selcalActive.Clear(command.Callsign);
                    break;
                default:
                    Log("Unknown client message type: " + command?.Type);
                    break;
            }
        }

        private void Broadcast(string message)
        {
            List<IWebSocketConnection> sockets;
            lock (_gate) { sockets = _sockets.ToList(); }

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
