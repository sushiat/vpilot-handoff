using System;
using RossCarlson.Vatsim.Vpilot.Plugins;

namespace Handoff.Plugin
{
    public class HandoffPlugin : IPlugin
    {
        public string Name => "Handoff";

        private IBroker _broker;
        private ControllerStateModel _controllerState;
        private ChatModel _chatModel;
        private RadioStateModel _radioState;
        private HandoffWebSocketServer _webSocketServer;
        private HandoffDiscoveryListener _discoveryListener;

        public void Initialize(IBroker broker)
        {
            _broker = broker;
            _controllerState = new ControllerStateModel(_broker);
            _chatModel = new ChatModel(_broker);

            // RadioStateModel's helper process is tied to the VATSIM connection, not the
            // plugin's own load lifetime -- radio state isn't needed before connecting, and
            // IPlugin has no unload hook, so this is the only clean way to actually stop it.
            _radioState = new RadioStateModel(_broker.PostDebugMessage);
            _broker.NetworkConnected += (sender, e) => _radioState.Start();
            _broker.NetworkDisconnected += (sender, e) => _radioState.Stop();
            _broker.SessionEnded += (sender, e) => _radioState.Stop();

            // Best-effort backstop for closing vPilot without disconnecting first -- not
            // guaranteed on a hard kill/crash, but catches the common case ProcessExit does
            // fire for.
            AppDomain.CurrentDomain.ProcessExit += (sender, e) => _radioState.Stop();

            // Unlike RadioStateModel, not tied to the VATSIM connection -- just an in-process
            // listener, and the Android app should be able to connect and see plugin status
            // even before the pilot connects.
            _webSocketServer = new HandoffWebSocketServer(_controllerState, _chatModel, _radioState, _broker.PostDebugMessage);
            _webSocketServer.Start();

            _discoveryListener = new HandoffDiscoveryListener(_broker.PostDebugMessage);
            _discoveryListener.Start();

            _broker.PostDebugMessage("Handoff plugin loaded.");
        }
    }
}
