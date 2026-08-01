using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json;
using Newtonsoft.Json.Converters;
using Newtonsoft.Json.Linq;
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

        // Issue #73b -- caps how much of a pilot-typed snapshot name gets appended to the
        // filename itself (the full name is still stored untruncated inside the JSON's `name`
        // field) -- keeps filenames from growing unboundedly long on a verbose name.
        private const int MaxNameSuffixLength = 40;

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
                snapshotId, now, _pluginVersion, appVersion, null,
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

        /// <summary>
        /// Issue #73b -- attaches a pilot-chosen name to an already-saved snapshot, strictly after
        /// the fact (never touches the original save/capture timing). Reuses the same
        /// _recentSnapshots correlation SaveSnapshot/TrySaveScreenshot already maintain. Renames
        /// the file(s) first (a pure filesystem move, either both succeed or neither file's name
        /// changes) and only then patches the `name` field into the renamed JSON -- so a failure
        /// partway through never leaves the pair split between old/new names, and any failure at
        /// all leaves the original files exactly as they were, per the issue's framing.
        /// </summary>
        public (bool Success, string Error) RenameSnapshot(string snapshotId, string name)
        {
            Log("RenameSnapshot: entered for " + snapshotId);
            string basePath;
            lock (_gate)
            {
                if (!_recentSnapshots.TryGetValue(snapshotId, out var entry))
                {
                    Log("RenameSnapshot: snapshotId not found in _recentSnapshots (unknown/expired): " + snapshotId);
                    return (false, "Unknown or expired snapshotId.");
                }
                basePath = entry.BasePath;
            }
            Log("RenameSnapshot: resolved basePath=" + basePath);

            var jsonPath = basePath + ".json";
            if (!File.Exists(jsonPath))
            {
                Log("RenameSnapshot: jsonPath does not exist: " + jsonPath);
                return (false, "Snapshot file no longer exists.");
            }

            var directory = Path.GetDirectoryName(basePath);
            var newStem = Path.GetFileName(basePath) + "-" + SanitizeForFileName(name, MaxNameSuffixLength);
            var newBasePath = PathJoin.Combine(directory, newStem);
            var newJsonPath = newBasePath + ".json";
            var pngPath = basePath + ".png";
            var newPngPath = newBasePath + ".png";
            var hasPng = File.Exists(pngPath);
            Log("RenameSnapshot: about to move " + jsonPath + " -> " + newJsonPath + " (hasPng=" + hasPng + ")");

            try
            {
                File.Move(jsonPath, newJsonPath);
                Log("RenameSnapshot: moved json file");
                if (hasPng)
                {
                    File.Move(pngPath, newPngPath);
                    Log("RenameSnapshot: moved png file");
                }

                var json = JObject.Parse(File.ReadAllText(newJsonPath));
                Log("RenameSnapshot: parsed json for patching");
                json["name"] = name;
                // JsonConvert.SerializeObject, not JToken.ToString(Formatting) -- the latter threw
                // MissingMethodException on a real device: vPilot's process has a different
                // Newtonsoft.Json assembly actually loaded than this plugin was compiled against,
                // and that instance-method overload didn't resolve. SerializeObject is the same
                // static entry point already used successfully everywhere else in this file
                // (SaveSnapshot) and across the plugin, so it's proven to work against whatever
                // version is actually loaded at runtime.
                File.WriteAllText(newJsonPath, JsonConvert.SerializeObject(json, SerializerSettings));
                Log("RenameSnapshot: wrote patched json");

                lock (_gate)
                {
                    if (_recentSnapshots.TryGetValue(snapshotId, out var entry))
                    {
                        _recentSnapshots[snapshotId] = (newBasePath, entry.SavedAt);
                    }
                }

                Log("Renamed debug snapshot " + snapshotId + " to " + newStem);
                return (true, null);
            }
            catch (Exception ex)
            {
                Log("Failed to name debug snapshot " + snapshotId + ": " + ex);
                return (false, "Failed to rename snapshot file(s): " + ex.Message);
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

        private static string SanitizeForFileName(string value, int? maxLength = null)
        {
            if (string.IsNullOrEmpty(value)) return "snapshot";
            var truncated = maxLength.HasValue && value.Length > maxLength.Value ? value.Substring(0, maxLength.Value) : value;
            var chars = truncated.ToCharArray();
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
