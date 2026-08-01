using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json;
using Newtonsoft.Json.Converters;
using Newtonsoft.Json.Serialization;

namespace Handoff.Plugin
{
    /// <summary>
    /// Orchestrates the debug snapshot feature (issue #65 section 4) -- on `saveDebugSnapshot`,
    /// gathers a full point-in-time dump from every subsystem model (each via its own
    /// BuildDebugSnapshot()) and writes it to
    /// %LOCALAPPDATA%\Handoff\debug-snapshots\&lt;timestamp&gt;-&lt;snapshotId&gt;.json -- the plugin's own
    /// data directory, distinct from vPilot's own install (see CLAUDE.md's Resolved section).
    /// The optional screenshot (`attachDebugSnapshotScreenshot`) is a separate, later round trip
    /// -- see RememberSnapshotPath's own doc comment for why a small in-memory correlation table
    /// is needed to find the right file to save it alongside.
    /// </summary>
    public sealed class DebugSnapshotService
    {
        private static readonly string Default_snapshotDirectory = PathJoin.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "debug-snapshots");

        // Bounds how long a snapshotId stays resolvable for the follow-up screenshot -- a pilot
        // who takes a snapshot, then does something else for an hour before finally attaching a
        // screenshot, doesn't need that to still work; capping this avoids an unbounded dictionary
        // over a long flight session with many snapshots taken.
        private static readonly TimeSpan ScreenshotCorrelationWindow = TimeSpan.FromMinutes(10);

        private static readonly JsonSerializerSettings SerializerSettings = new JsonSerializerSettings
        {
            ContractResolver = new CamelCasePropertyNamesContractResolver(),
            Converters = { new StringEnumConverter(new CamelCaseNamingStrategy()) },
            Formatting = Formatting.Indented
        };

        private readonly ControllerRankingModel _controllerRanking;
        private readonly RadioStateModel _radioState;
        private readonly FlightPlanModel _flightPlanState;
        private readonly VatsimDataFeedModel _vatsimDataFeed;
        private readonly HandoffControllerStateModel _controllerState;
        private readonly VatGlassesDataModel _vatGlassesData;
        private readonly VatSpyDataModel _vatSpyData;
        private readonly PilotSessionModel _pilotSession;
        private readonly OperationProgressModel _operationProgress;
        private readonly HandoffPairedClientStore _pairedClients;
        private readonly HandoffPairingSession _pairingSession;
        private readonly Func<int> _authenticatedSocketCountProvider;
        private readonly string _pluginVersion;
        private readonly Action<string> _logDebug;
        private readonly string _snapshotDirectory;

        private readonly object _gate = new object();
        // snapshotId -> (file path without extension, savedAt) -- see ScreenshotCorrelationWindow.
        private readonly Dictionary<string, (string BasePath, DateTimeOffset SavedAt)> _recentSnapshots =
            new Dictionary<string, (string, DateTimeOffset)>(StringComparer.Ordinal);

        public DebugSnapshotService(
            ControllerRankingModel controllerRanking, RadioStateModel radioState, FlightPlanModel flightPlanState,
            VatsimDataFeedModel vatsimDataFeed, HandoffControllerStateModel controllerState,
            VatGlassesDataModel vatGlassesData, VatSpyDataModel vatSpyData, PilotSessionModel pilotSession,
            OperationProgressModel operationProgress, HandoffPairedClientStore pairedClients, HandoffPairingSession pairingSession,
            Func<int> authenticatedSocketCountProvider, string pluginVersion, Action<string> logDebug = null, string snapshotDirectory = null)
        {
            _controllerRanking = controllerRanking ?? throw new ArgumentNullException(nameof(controllerRanking));
            _radioState = radioState ?? throw new ArgumentNullException(nameof(radioState));
            _flightPlanState = flightPlanState ?? throw new ArgumentNullException(nameof(flightPlanState));
            _vatsimDataFeed = vatsimDataFeed ?? throw new ArgumentNullException(nameof(vatsimDataFeed));
            _controllerState = controllerState ?? throw new ArgumentNullException(nameof(controllerState));
            _vatGlassesData = vatGlassesData ?? throw new ArgumentNullException(nameof(vatGlassesData));
            _vatSpyData = vatSpyData ?? throw new ArgumentNullException(nameof(vatSpyData));
            _pilotSession = pilotSession ?? throw new ArgumentNullException(nameof(pilotSession));
            _operationProgress = operationProgress ?? throw new ArgumentNullException(nameof(operationProgress));
            _pairedClients = pairedClients ?? throw new ArgumentNullException(nameof(pairedClients));
            _pairingSession = pairingSession ?? throw new ArgumentNullException(nameof(pairingSession));
            _authenticatedSocketCountProvider = authenticatedSocketCountProvider ?? throw new ArgumentNullException(nameof(authenticatedSocketCountProvider));
            _pluginVersion = pluginVersion;
            _logDebug = logDebug;
            _snapshotDirectory = snapshotDirectory ?? Default_snapshotDirectory;
        }

        /// <summary>
        /// Gathers and writes the full snapshot synchronously (issue #65: "nothing else queued
        /// ahead of it," so the file reflects the exact instant the button was pressed). Returns
        /// the full file path written, for the debugSnapshotSaved reply.
        /// </summary>
        public string SaveSnapshot(string snapshotId, string appVersion)
        {
            var now = DateTimeOffset.UtcNow;
            var snapshot = new FullDebugSnapshot(
                snapshotId, now, _pluginVersion, appVersion,
                _pilotSession.Callsign, _pilotSession.Cid,
                _controllerRanking.Current, _controllerRanking.PlanWideDebugExplain, _controllerRanking.BuildDebugSnapshot(),
                _controllerState.BuildDebugSnapshot(), _radioState.BuildDebugSnapshot(), _vatsimDataFeed.BuildDebugSnapshot(),
                _flightPlanState.BuildDebugSnapshot(), _vatGlassesData.BuildDebugSnapshot(), _vatSpyData.BuildDebugSnapshot(),
                _pairedClients.BuildDebugSnapshot(_pairingSession.IsCodeCurrentlyActive), _authenticatedSocketCountProvider(),
                _operationProgress.ActiveOperations);

            Directory.CreateDirectory(_snapshotDirectory);
            var fileNameStem = now.ToString("yyyyMMdd-HHmmss") + "-" + SanitizeForFileName(snapshotId);
            var basePath = PathJoin.Combine(_snapshotDirectory, fileNameStem);
            var jsonPath = basePath + ".json";

            File.WriteAllText(jsonPath, JsonConvert.SerializeObject(snapshot, SerializerSettings));
            Log("Wrote debug snapshot: " + jsonPath);

            lock (_gate)
            {
                PruneStaleCorrelations(now);
                _recentSnapshots[snapshotId] = (basePath, now);
            }

            return jsonPath;
        }

        /// <summary>
        /// Saves the async follow-up screenshot (issue #65 section 5) alongside the JSON already
        /// written for this snapshotId. Returns false (with no exception) if the snapshotId is
        /// unknown or its correlation window expired -- the JSON file itself is unaffected either
        /// way, per the issue's "sending it is optional" framing.
        /// </summary>
        public bool TrySaveScreenshot(string snapshotId, string pngBase64)
        {
            string basePath;
            lock (_gate)
            {
                if (!_recentSnapshots.TryGetValue(snapshotId, out var entry)) return false;
                basePath = entry.BasePath;
            }

            try
            {
                var bytes = Convert.FromBase64String(pngBase64);
                File.WriteAllBytes(basePath + ".png", bytes);
                Log("Wrote debug snapshot screenshot: " + basePath + ".png");
                return true;
            }
            catch (Exception ex)
            {
                Log("Failed to save debug snapshot screenshot for " + snapshotId + ": " + ex.Message);
                return false;
            }
        }

        private void PruneStaleCorrelations(DateTimeOffset now)
        {
            var stale = _recentSnapshots
                .Where(kv => now - kv.Value.SavedAt > ScreenshotCorrelationWindow)
                .Select(kv => kv.Key)
                .ToList();
            foreach (var key in stale) _recentSnapshots.Remove(key);
        }

        private static string SanitizeForFileName(string value)
        {
            if (string.IsNullOrEmpty(value)) return "snapshot";
            var chars = value.ToCharArray();
            for (var i = 0; i < chars.Length; i++)
            {
                if (Array.IndexOf(Path.GetInvalidFileNameChars(), chars[i]) >= 0) chars[i] = '_';
            }
            return new string(chars);
        }

        private void Log(string message)
        {
            var line = "DebugSnapshotService: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
