using System;
using System.Threading;
using RossCarlson.Vatsim.Vpilot.Plugins;

namespace Handoff.Plugin
{
    public class HandoffPlugin : IPlugin
    {
        public string Name => "Handoff";

        private IBroker _broker;
        private HandoffControllerStateModel _controllerState;
        private ChatModel _chatModel;
        private RadioStateModel _radioState;
        private UpdateIntervalModel _updateInterval;
        private FlightPlanModel _flightPlanState;
        private VatsimDataFeedModel _vatsimDataFeed;
        private PilotSessionModel _pilotSession;
        private ControllerRankingModel _controllerRanking;
        private NearbyAircraftModel _nearbyAircraft;
        private OperationProgressModel _operationProgress;
        private VatGlassesDataModel _vatGlassesData;
        private VatSpyDataModel _vatSpyData;
        private HandoffWebSocketServer _webSocketServer;
        private HandoffDiscoveryListener _discoveryListener;
        private HandoffCertificateStore _certificateStore;
        private HandoffPairedClientStore _pairedClients;
        private HandoffPairingWindow _pairingWindow;
        private HandoffPairingSession _pairingSession;
        private PluginUpdateModel _pluginUpdate;

        public void Initialize(IBroker broker)
        {
            _broker = broker;
            _chatModel = new ChatModel(_broker);
            // Needs _chatModel to already exist -- it absorbs the old ContactMeModel/
            // SelcalActiveModel's chat-triggered detection directly (issue #18's unified
            // HandoffController model), not just IBroker's controller events.
            _controllerState = new HandoffControllerStateModel(_broker, _chatModel);

            // Pilot-selected update-interval tier (issue #88), persisted plugin-side, edited from
            // the Android client. Just synchronous disk-read of a tiny JSON at construction (same
            // shape as FlightPlanModel's credential load), safe here. Injected into RadioStateModel
            // (pushes the tier's SimConnect poll cadences down the command pipe) and the WebSocket
            // server (drives the broadcast cadence + the setUpdateInterval command).
            _updateInterval = new UpdateIntervalModel(_broker.PostDebugMessage);

            // RadioStateModel's helper process is tied to the VATSIM connection, not the
            // plugin's own load lifetime -- radio state isn't needed before connecting, and
            // IPlugin has no unload hook, so this is the only clean way to actually stop it.
            _radioState = new RadioStateModel(_broker.PostDebugMessage, _updateInterval);
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

            // Same disk-cache-only-at-construction shape as VatGlassesDataModel above -- see
            // VatSpyDataModel's own doc comment (issue #11).
            _vatSpyData = new VatSpyDataModel(_operationProgress, _broker.PostDebugMessage);

            _controllerRanking = new ControllerRankingModel(_controllerState, _radioState, _flightPlanState, _vatsimDataFeed, _pilotSession, _vatGlassesData, _vatSpyData, _broker.PostDebugMessage);

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

            new Thread(() => _vatSpyData.SyncAsync().GetAwaiter().GetResult())
            { Name = "VatSpyDataModel.Startup", IsBackground = true }.Start();

            // Unlike RadioStateModel, not tied to the VATSIM connection -- just an in-process
            // listener, and the Android app should be able to connect and see plugin status
            // even before the pilot connects.
            // Loaded/generated once up front -- both the wss:// listener and the discovery
            // reply's fingerprint field need the same certificate identity (see issue #15).
            _certificateStore = new HandoffCertificateStore(_broker.PostDebugMessage);

            // Device authorization on top of the certificate's own (silent) TOFU pinning --
            // TLS alone only proves "this is the right PC," not "this is the right pilot's
            // device," so an unauthenticated socket gets no data and no command execution until
            // it pairs (docs/protocol.md). HandoffPairingWindow needs vPilot's own UI-thread
            // SynchronizationContext, captured here since Initialize runs on it -- ShowCode/
            // CloseWindow get called later from HandoffWebSocketServer's Fleck callbacks, which
            // run on Fleck's own socket threads, not this one.
            var uiContext = SynchronizationContext.Current ?? new System.Windows.Forms.WindowsFormsSynchronizationContext();
            _pairedClients = new HandoffPairedClientStore(_broker.PostDebugMessage);
            _pairingWindow = new HandoffPairingWindow(uiContext);
            _pairingSession = new HandoffPairingSession(_pairingWindow, _broker.PostDebugMessage);

            _webSocketServer = new HandoffWebSocketServer(_controllerRanking, _chatModel, _radioState, _flightPlanState, _vatsimDataFeed, _nearbyAircraft, _controllerState, _pilotSession, _operationProgress, _certificateStore.Certificate, _pairedClients, _pairingSession, _vatGlassesData, _vatSpyData, _updateInterval, _broker.PostDebugMessage);
            _webSocketServer.Start();

            _discoveryListener = new HandoffDiscoveryListener(_certificateStore.FingerprintHex, _broker.PostDebugMessage);
            _discoveryListener.Start();

            // Checks GitHub releases once at plugin startup, not on VATSIM connect (issue #34) --
            // a pilot setting up the sim/tablet is exactly the moment they'd want to notice and
            // quit to update, not after they've already committed to a VATSIM session. Own
            // background thread, same reasoning as VatGlassesDataModel/VatSpyDataModel's startup
            // sync above -- network I/O (and now a blocking confirmation prompt) must never touch
            // vPilot's own Initialize-calling thread. CheckMarker (a prior update having just been
            // applied) is cheap local-disk-only, safe to run inline first.
            _pluginUpdate = new PluginUpdateModel(_operationProgress, new HandoffUpdatePromptWindow(uiContext), new HandoffUpdateAppliedWindow(uiContext), _broker.PostDebugMessage);
            _pluginUpdate.CheckMarker();
            new Thread(() => _pluginUpdate.CheckAsync().GetAwaiter().GetResult())
            { Name = "PluginUpdateModel.Startup", IsBackground = true }.Start();

            _broker.PostDebugMessage("Handoff plugin loaded.");
        }
    }
}
