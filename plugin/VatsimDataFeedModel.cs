using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Handoff.Plugin
{
    /// <summary>
    /// Polls the public VATSIM data feed (VatsimDataFeedClient) on a background thread and holds
    /// the latest controllers[]/pilots[] snapshot, each keyed by callsign. Controllers feed
    /// ControllerRankingModel's "solidify" enrichment (cid/name/facility/rating) onto controllers
    /// already known from IBroker's real-time events; pilots feed the plugin's own-flight-plan
    /// cross-check (looking up PilotSessionModel's callsign here gets the actually-filed VATSIM
    /// flight plan, for comparison against the SimBrief-derived FlightPlanModel). The feed itself
    /// lags IBroker by ~15s, so this is enrichment-only and never blocks ranking (see
    /// ControllerRankingModel).
    ///
    /// Lifecycle: Start/Stop tied to the VATSIM connection in HandoffPlugin, same reasoning as
    /// RadioStateModel -- no point polling a public feed when the pilot isn't flying.
    /// </summary>
    public sealed class VatsimDataFeedModel
    {
        private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(15);

        private readonly object _gate = new object();
        private readonly object _lifecycleGate = new object();
        private readonly Func<Task<VatsimDataFeedSnapshot>> _fetch;
        private readonly Action<string> _logDebug;
        private Dictionary<string, VatsimControllerInfo> _controllersByCallsign =
            new Dictionary<string, VatsimControllerInfo>(StringComparer.OrdinalIgnoreCase);
        private Dictionary<string, VatsimPilotInfo> _pilotsByCallsign =
            new Dictionary<string, VatsimPilotInfo>(StringComparer.OrdinalIgnoreCase);
        // Wakes PollLoop out of its inter-poll wait early (RefreshNow); an AutoResetEvent so several
        // quick requests collapse into one extra poll.
        private readonly AutoResetEvent _pollNow = new AutoResetEvent(false);
        private volatile bool _running;
        private volatile bool _connected;
        private DateTimeOffset? _lastPollAt;
        private string _lastError;

        public event EventHandler Changed;

        // Optional: only needed to give a manual refresh (RefreshNow) its operationProgress feedback.
        private readonly OperationProgressModel _operationProgress;
        // Operations a manual refresh started and the next poll will finish; a queue so quick repeated
        // taps all get their result rather than the earlier ones being left hanging.
        private readonly System.Collections.Concurrent.ConcurrentQueue<string> _pendingRefreshOperations =
            new System.Collections.Concurrent.ConcurrentQueue<string>();

        public VatsimDataFeedModel(Action<string> logDebug = null, Func<Task<VatsimDataFeedSnapshot>> fetch = null, OperationProgressModel operationProgress = null)
        {
            _logDebug = logDebug;
            _fetch = fetch ?? (() => VatsimDataFeedClient.FetchAsync(_logDebug));
            _operationProgress = operationProgress;
        }

        /// <summary>Point-in-time snapshot of the latest feed poll's controllers, keyed by callsign (case-insensitive).</summary>
        public IReadOnlyDictionary<string, VatsimControllerInfo> Controllers
        {
            get { lock (_gate) { return _controllersByCallsign; } }
        }

        /// <summary>Point-in-time snapshot of the latest feed poll's filed flight plans, keyed by callsign (case-insensitive).</summary>
        public IReadOnlyDictionary<string, VatsimPilotInfo> Pilots
        {
            get { lock (_gate) { return _pilotsByCallsign; } }
        }

        /// <summary>Whether the most recent poll of the public VATSIM data feed succeeded.</summary>
        public bool IsConnected => _connected;

        /// <summary>Issue #65 -- full internal feed state for the debug snapshot file.</summary>
        public VatsimFeedDebugSnapshot BuildDebugSnapshot()
        {
            lock (_gate)
            {
                return new VatsimFeedDebugSnapshot(_connected, _controllersByCallsign.Count, _pilotsByCallsign.Count, _lastPollAt, _lastError);
            }
        }

        public void Start()
        {
            lock (_lifecycleGate)
            {
                if (_running) return;
                _running = true;
                new Thread(PollLoop) { Name = "VatsimDataFeedModel.PollLoop", IsBackground = true }.Start();
            }
        }

        /// <summary>Polls the feed now instead of waiting out the rest of the interval (a client's
        /// refresh button). A no-op while stopped: the feed only runs while the pilot is on the
        /// network, so there is nothing to refresh before that.</summary>
        public void RefreshNow()
        {
            // The regular 15s poll deliberately reports nothing; a manual refresh does, as the
            // pilot's feedback that the tap did something (same operationProgress as SimBrief's).
            var operationId = "vatsimFeedRefresh-" + Guid.NewGuid().ToString("N");

            if (!_running)
            {
                _operationProgress?.Finish(operationId, "The VATSIM feed only updates while you're connected to the network.", success: false);
                return;
            }

            _operationProgress?.Report(operationId, "Refreshing VATSIM data...");
            _pendingRefreshOperations.Enqueue(operationId);
            _pollNow.Set();
        }

        public void Stop()
        {
            lock (_lifecycleGate)
            {
                if (!_running) return;
                _running = false;
            }

            lock (_gate)
            {
                _controllersByCallsign = new Dictionary<string, VatsimControllerInfo>(StringComparer.OrdinalIgnoreCase);
                _pilotsByCallsign = new Dictionary<string, VatsimPilotInfo>(StringComparer.OrdinalIgnoreCase);
            }
            _connected = false;
            Changed?.Invoke(this, EventArgs.Empty);
        }

        private void PollLoop()
        {
            while (_running)
            {
                // Taken before the fetch, so a refresh asked for mid-poll waits for the *next* poll
                // (the event is already set, so it starts at once) and gets data fetched after the tap.
                var refreshOperations = DrainPendingRefreshOperations();
                var succeeded = false;
                try
                {
                    var snapshot = _fetch().GetAwaiter().GetResult();
                    lock (_gate) { _lastPollAt = DateTimeOffset.Now; }
                    if (snapshot != null)
                    {
                        lock (_gate)
                        {
                            _controllersByCallsign = ByCallsign(snapshot.Controllers, c => c.Callsign);
                            _pilotsByCallsign = ByCallsign(snapshot.Pilots, p => p.Callsign);
                            _lastError = null;
                        }
                        _connected = true;
                        succeeded = true;
                    }
                    else
                    {
                        _connected = false;
                        lock (_gate) { _lastError = "Poll returned no data -- feed unreachable."; }
                        Log("Poll returned no data -- feed unreachable.");
                    }
                    Changed?.Invoke(this, EventArgs.Empty);
                }
                catch (Exception ex)
                {
                    _connected = false;
                    lock (_gate) { _lastPollAt = DateTimeOffset.Now; _lastError = ex.Message; }
                    Log("Poll failed: " + ex.Message);
                    Changed?.Invoke(this, EventArgs.Empty);
                }

                foreach (var operationId in refreshOperations)
                {
                    _operationProgress?.Finish(
                        operationId,
                        succeeded ? "VATSIM data updated." : "Couldn't reach the VATSIM data feed.",
                        success: succeeded);
                }

                _pollNow.WaitOne(PollInterval);
            }
        }

        private List<string> DrainPendingRefreshOperations()
        {
            var operations = new List<string>();
            while (_pendingRefreshOperations.TryDequeue(out var operationId)) operations.Add(operationId);
            return operations;
        }

        /// <summary>
        /// The live feed has been seen listing the same callsign twice (identical duplicate
        /// controller rows) -- ToDictionary would throw on that and fail the whole poll, so every
        /// pilot lookup silently stayed empty for as long as the duplicate lasted. Last entry wins.
        /// </summary>
        private static Dictionary<string, T> ByCallsign<T>(IEnumerable<T> items, Func<T, string> callsignOf)
        {
            var result = new Dictionary<string, T>(StringComparer.OrdinalIgnoreCase);
            foreach (var item in items) result[callsignOf(item)] = item;
            return result;
        }

        private void Log(string message)
        {
            var line = "VatsimDataFeedModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
