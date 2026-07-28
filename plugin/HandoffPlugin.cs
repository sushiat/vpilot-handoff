using System;
using System.Threading;
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
        private FlightPlanModel _flightPlanState;
        private VatsimDataFeedModel _vatsimDataFeed;
        private ContactMeModel _contactMe;
        private SelcalActiveModel _selcalActive;
        private PilotSessionModel _pilotSession;
        private ControllerRankingModel _controllerRanking;
        private NearbyAircraftModel _nearbyAircraft;
        private OperationProgressModel _operationProgress;
        private VatGlassesDataModel _vatGlassesData;
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

            // Not tied to the VATSIM connection -- needed pre-connection (FlightPlanModel's
            // startup SimBrief fetch needs it immediately below) and generic enough (see issue
            // #9) that other future operations can report through it too. Just in-memory state
            // (no I/O), safe to construct directly here.
            _operationProgress = new OperationProgressModel();

            // Fetches using whatever SimBrief credentials were persisted from a prior session
            // (see FlightPlanModel) -- no-ops if the Android app has never sent any yet.
            _flightPlanState = new FlightPlanModel(_operationProgress, _broker.PostDebugMessage);
            _ = _flightPlanState.RefreshAsync();

            // Own callsign/CID for the current connection, straight from IBroker -- the
            // authoritative live value, distinct from FlightPlanModel's SimBrief-derived one
            // (see PilotSessionModel). Cleared on disconnect, not just stopped, since a stale
            // callsign from a prior session would be actively misleading, not just unused.
            _pilotSession = new PilotSessionModel();
            _broker.NetworkConnected += (sender, e) => _pilotSession.OnNetworkConnected(e.Callsign, e.Cid);
            _broker.NetworkDisconnected += (sender, e) => _pilotSession.OnDisconnected();
            _broker.SessionEnded += (sender, e) => _pilotSession.OnDisconnected();

            // Public VATSIM data feed for cid/name/facility/rating enrichment -- tied to the
            // VATSIM connection same as RadioStateModel, no point polling it when not flying.
            _vatsimDataFeed = new VatsimDataFeedModel(_broker.PostDebugMessage);
            _broker.NetworkConnected += (sender, e) => _vatsimDataFeed.Start();
            _broker.NetworkDisconnected += (sender, e) => _vatsimDataFeed.Stop();
            _broker.SessionEnded += (sender, e) => _vatsimDataFeed.Stop();

            // VatGlassesDataModel's constructor only does synchronous *disk-cache* I/O (reading
            // whatever's already cached locally) -- fast and bounded, unlike SyncAsync's network
            // fetch, so it's fine to construct here on vPilot's Initialize-calling thread.
            // ControllerRankingModel needs a live instance to resolve sector/airport ownership
            // against (issue #9 phase 2), so this has to exist before it, not just before the
            // WebSocket server.
            _vatGlassesData = new VatGlassesDataModel(_operationProgress, _broker.PostDebugMessage);

            _contactMe = new ContactMeModel(_chatModel, _controllerState);
            _selcalActive = new SelcalActiveModel(_chatModel, _controllerState);
            _controllerRanking = new ControllerRankingModel(_controllerState, _radioState, _flightPlanState, _vatsimDataFeed, _contactMe, _selcalActive, _pilotSession, _vatGlassesData, _broker.PostDebugMessage);

            // Nearby-aircraft events aren't tied to the VATSIM connection either (wiring is just
            // event subscriptions, same as ControllerStateModel/ChatModel) -- IBroker simply
            // won't raise them until connected.
            _nearbyAircraft = new NearbyAircraftModel(_broker, _radioState);

            // SyncAsync does network I/O -- runs on its own dedicated background thread (mirrors
            // VatsimDataFeedModel's own PollLoop thread), never vPilot's Initialize-calling
            // thread, so this can never add so much as a millisecond of delay to vPilot's own
            // startup or its VATSIM network handling.
            new Thread(() => _vatGlassesData.SyncAsync().GetAwaiter().GetResult())
            { Name = "VatGlassesDataModel.Startup", IsBackground = true }.Start();

            // Unlike RadioStateModel, not tied to the VATSIM connection -- just an in-process
            // listener, and the Android app should be able to connect and see plugin status
            // even before the pilot connects.
            _webSocketServer = new HandoffWebSocketServer(_controllerRanking, _chatModel, _radioState, _flightPlanState, _vatsimDataFeed, _nearbyAircraft, _selcalActive, _pilotSession, _operationProgress, _broker.PostDebugMessage);
            _webSocketServer.Start();

            _discoveryListener = new HandoffDiscoveryListener(_broker.PostDebugMessage);
            _discoveryListener.Start();

            _broker.PostDebugMessage("Handoff plugin loaded.");
        }
    }
}
