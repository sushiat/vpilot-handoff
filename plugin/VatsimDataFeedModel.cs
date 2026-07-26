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
        private volatile bool _running;
        private volatile bool _connected;

        public event EventHandler Changed;

        public VatsimDataFeedModel(Action<string> logDebug = null, Func<Task<VatsimDataFeedSnapshot>> fetch = null)
        {
            _logDebug = logDebug;
            _fetch = fetch ?? (() => VatsimDataFeedClient.FetchAsync(_logDebug));
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

        public void Start()
        {
            lock (_lifecycleGate)
            {
                if (_running) return;
                _running = true;
                new Thread(PollLoop) { Name = "VatsimDataFeedModel.PollLoop", IsBackground = true }.Start();
            }
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
                try
                {
                    var snapshot = _fetch().GetAwaiter().GetResult();
                    if (snapshot != null)
                    {
                        lock (_gate)
                        {
                            _controllersByCallsign = snapshot.Controllers.ToDictionary(c => c.Callsign, c => c, StringComparer.OrdinalIgnoreCase);
                            _pilotsByCallsign = snapshot.Pilots.ToDictionary(p => p.Callsign, p => p, StringComparer.OrdinalIgnoreCase);
                        }
                        _connected = true;
                    }
                    else
                    {
                        _connected = false;
                        Log("Poll returned no data -- feed unreachable.");
                    }
                    Changed?.Invoke(this, EventArgs.Empty);
                }
                catch (Exception ex)
                {
                    _connected = false;
                    Log("Poll failed: " + ex.Message);
                    Changed?.Invoke(this, EventArgs.Empty);
                }

                Thread.Sleep(PollInterval);
            }
        }

        private void Log(string message)
        {
            var line = "VatsimDataFeedModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
