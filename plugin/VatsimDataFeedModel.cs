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
    /// the latest controllers[] snapshot, keyed by callsign, for ControllerRankingModel to
    /// "solidify" enrichment (cid/name/facility/rating) onto controllers already known from
    /// IBroker's real-time events. The feed itself lags IBroker by ~15s, so this is
    /// enrichment-only and never blocks ranking (see ControllerRankingModel).
    ///
    /// Lifecycle: Start/Stop tied to the VATSIM connection in HandoffPlugin, same reasoning as
    /// RadioStateModel -- no point polling a public feed when the pilot isn't flying.
    /// </summary>
    public sealed class VatsimDataFeedModel
    {
        private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(15);

        private readonly object _gate = new object();
        private readonly object _lifecycleGate = new object();
        private readonly Func<Task<IReadOnlyList<VatsimControllerInfo>>> _fetch;
        private readonly Action<string> _logDebug;
        private Dictionary<string, VatsimControllerInfo> _byCallsign =
            new Dictionary<string, VatsimControllerInfo>(StringComparer.OrdinalIgnoreCase);
        private volatile bool _running;

        public event EventHandler Changed;

        public VatsimDataFeedModel(Action<string> logDebug = null, Func<Task<IReadOnlyList<VatsimControllerInfo>>> fetch = null)
        {
            _logDebug = logDebug;
            _fetch = fetch ?? (() => VatsimDataFeedClient.FetchAsync(_logDebug));
        }

        /// <summary>Point-in-time snapshot of the latest feed poll, keyed by callsign (case-insensitive).</summary>
        public IReadOnlyDictionary<string, VatsimControllerInfo> Controllers
        {
            get { lock (_gate) { return _byCallsign; } }
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

        public void Stop()
        {
            lock (_lifecycleGate)
            {
                if (!_running) return;
                _running = false;
            }

            lock (_gate) { _byCallsign = new Dictionary<string, VatsimControllerInfo>(StringComparer.OrdinalIgnoreCase); }
            Changed?.Invoke(this, EventArgs.Empty);
        }

        private void PollLoop()
        {
            while (_running)
            {
                try
                {
                    var controllers = _fetch().GetAwaiter().GetResult();
                    lock (_gate)
                    {
                        _byCallsign = controllers.ToDictionary(c => c.Callsign, c => c, StringComparer.OrdinalIgnoreCase);
                    }
                    Changed?.Invoke(this, EventArgs.Empty);
                }
                catch (Exception ex)
                {
                    Log("Poll failed: " + ex.Message);
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
