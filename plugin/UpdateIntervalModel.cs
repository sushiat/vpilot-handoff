using System;
using System.Diagnostics;
using System.IO;
using Newtonsoft.Json;

namespace Handoff.Plugin
{
    /// <summary>The three update-interval presets (issue #88). Normal reproduces the plugin's
    /// original hardcoded cadences exactly, so an unset/Normal setting is a no-op for existing
    /// users.</summary>
    public enum UpdateIntervalTier
    {
        Fast,
        Normal,
        Slow
    }

    /// <summary>
    /// The pilot-selected update-interval preset (issue #88), persisted plugin-side but edited
    /// from the Android client -- same persistence-plugin-side/edit-from-client model as SimBrief
    /// credentials (see FlightPlanModel). Holds one tri-state tier and maps it to the three
    /// concrete cadences it drives: the two SimConnect polls in Handoff.RadioHost (radio +
    /// telemetry, pushed down the command pipe by RadioStateModel) and the WebSocket broadcast
    /// timer (HandoffWebSocketServer). Same shape as the other models: a Current* snapshot + a
    /// payload-free Changed event.
    ///
    /// The tier->intervals mapping lives here and only here -- RadioHost is handed concrete
    /// millisecond values and never learns about tiers, and the wire contract carries the tier as
    /// a plain lowercase string ("fast"/"normal"/"slow") rather than coupling to this enum's C#
    /// names (see docs/protocol.md).
    /// </summary>
    public sealed class UpdateIntervalModel
    {
        private static readonly string Default_configPath = PathJoin.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "updateInterval.json");

        private readonly object _gate = new object();
        private readonly Action<string> _logDebug;
        private readonly string _configPath;
        private UpdateIntervalTier _tier = UpdateIntervalTier.Normal;

        public event EventHandler Changed;

        /// <summary>Loads the persisted update-interval tier from disk, defaulting to Normal.</summary>
        /// <param name="configPath">
        /// Overridable only for tests, so they don't read/write the real
        /// %LOCALAPPDATA%\Handoff\updateInterval.json on the dev machine.
        /// </param>
        public UpdateIntervalModel(Action<string> logDebug = null, string configPath = null)
        {
            _logDebug = logDebug;
            _configPath = configPath ?? Default_configPath;
            Load();
        }

        public UpdateIntervalTier CurrentTier
        {
            get { lock (_gate) { return _tier; } }
        }

        /// <summary>The current tier as its lowercase wire string, for the protocol messages
        /// (subsystemStatus.updateInterval) -- see docs/protocol.md.</summary>
        public string CurrentTierWire => ToWire(CurrentTier);

        /// <summary>SimConnect radio-poll cadence (COM freq, transponder) for the current tier.</summary>
        public int RadioPollMs => IntervalsFor(CurrentTier).RadioMs;

        /// <summary>SimConnect ownship-telemetry cadence (position/speed/AGL/VS) for the current tier.</summary>
        public int TelemetryPollMs => IntervalsFor(CurrentTier).TelemetryMs;

        /// <summary>WebSocket broadcast cadence (controller list, flight plan, diversion) for the current tier.</summary>
        public int WsBroadcastMs => IntervalsFor(CurrentTier).WsMs;

        /// <summary>Persists the given tier (a full overwrite of whatever was persisted before)
        /// and raises Changed so the SimConnect polls and the WS broadcast timer re-apply it live.
        /// No-ops (no save, no event) if the tier is unchanged, so a redundant client set doesn't
        /// churn the pipe or re-broadcast.</summary>
        public void SetTier(UpdateIntervalTier tier)
        {
            lock (_gate)
            {
                if (_tier == tier) return;
                _tier = tier;
            }
            Save(tier);
            Log("Update interval set to " + ToWire(tier) + " (radio=" + RadioPollMs + "ms, telemetry=" + TelemetryPollMs + "ms, ws=" + WsBroadcastMs + "ms).");
            Changed?.Invoke(this, EventArgs.Empty);
        }

        /// <summary>Applies a tier from its wire string (client's setUpdateInterval command).
        /// Returns false and leaves the current tier untouched for an unrecognized/blank value,
        /// rather than falling back to a default that would silently override the pilot's choice.</summary>
        public bool TrySetTierFromWire(string wire)
        {
            if (!TryParseWire(wire, out var tier))
            {
                Log("Ignoring unrecognized update-interval tier: " + (wire ?? "<null>"));
                return false;
            }
            SetTier(tier);
            return true;
        }

        private void Load()
        {
            try
            {
                if (!File.Exists(_configPath)) return;

                var json = File.ReadAllText(_configPath);
                var config = JsonConvert.DeserializeObject<PersistedSettings>(json);
                if (config == null) return;

                if (TryParseWire(config.Tier, out var tier))
                {
                    lock (_gate) { _tier = tier; }
                }
                else
                {
                    Log("Persisted update-interval tier unrecognized ('" + config.Tier + "'), keeping Normal.");
                }
            }
            catch (Exception ex)
            {
                Log("Failed to load persisted update interval: " + ex.Message);
            }
        }

        private void Save(UpdateIntervalTier tier)
        {
            try
            {
                var directory = Path.GetDirectoryName(_configPath);
                if (directory != null) Directory.CreateDirectory(directory);

                var json = JsonConvert.SerializeObject(new PersistedSettings { Tier = ToWire(tier) });
                File.WriteAllText(_configPath, json);
            }
            catch (Exception ex)
            {
                Log("Failed to persist update interval: " + ex.Message);
            }
        }

        private static (int RadioMs, int TelemetryMs, int WsMs) IntervalsFor(UpdateIntervalTier tier)
        {
            switch (tier)
            {
                case UpdateIntervalTier.Fast: return (500, 1000, 500);
                case UpdateIntervalTier.Slow: return (2000, 5000, 2000);
                default: return (1000, 3000, 1000); // Normal -- matches the original hardcoded constants.
            }
        }

        private static string ToWire(UpdateIntervalTier tier)
        {
            switch (tier)
            {
                case UpdateIntervalTier.Fast: return "fast";
                case UpdateIntervalTier.Slow: return "slow";
                default: return "normal";
            }
        }

        private static bool TryParseWire(string wire, out UpdateIntervalTier tier)
        {
            switch (wire?.Trim().ToLowerInvariant())
            {
                case "fast": tier = UpdateIntervalTier.Fast; return true;
                case "normal": tier = UpdateIntervalTier.Normal; return true;
                case "slow": tier = UpdateIntervalTier.Slow; return true;
                default: tier = UpdateIntervalTier.Normal; return false;
            }
        }

        private void Log(string message)
        {
            var line = "UpdateIntervalModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }

        private sealed class PersistedSettings
        {
            public string Tier { get; set; }
        }
    }
}
