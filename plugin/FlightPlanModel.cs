using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace Handoff.Plugin
{
    /// <summary>
    /// Live flight-plan state, fetched from SimBrief (see SimBriefClient). Same shape as the
    /// other models (RadioStateModel, ChatModel): a Current snapshot + a payload-free Changed
    /// event.
    ///
    /// SimBrief credentials (user ID and/or username) are persisted locally so the plugin can
    /// re-fetch on its own startup, before the Android app has necessarily connected -- see
    /// docs/protocol.md and CLAUDE.md for why IBroker can't supply flight-plan data itself.
    /// </summary>
    public sealed class FlightPlanModel
    {
        private static readonly string Default_configPath = PathJoin.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "simbrief.json");

        private readonly object _gate = new object();
        private readonly OperationProgressModel _operationProgress;
        private readonly Func<string, string, Task<FlightPlan>> _fetch;
        private readonly Action<string> _logDebug;
        private readonly string _configPath;
        private FlightPlan _current = FlightPlan.Empty;
        private string _userId;
        private string _username;
        private DateTimeOffset? _lastFetchAttemptAt;
        private string _lastError;

        public event EventHandler Changed;

        /// <summary>Creates the model; call <see cref="SetSimbriefCredentials"/> and
        /// <see cref="RefreshAsync"/> to start fetching a plan.</summary>
        /// <param name="configPath">
        /// Overridable only for tests, so they don't read/write the real
        /// %LOCALAPPDATA%\Handoff\simbrief.json on the dev machine.
        /// </param>
        public FlightPlanModel(OperationProgressModel operationProgress, Action<string> logDebug = null, Func<string, string, Task<FlightPlan>> fetch = null, string configPath = null)
        {
            _operationProgress = operationProgress ?? throw new ArgumentNullException(nameof(operationProgress));
            _logDebug = logDebug;
            _fetch = fetch ?? ((userId, username) => SimBriefClient.FetchAsync(userId, username, _logDebug));
            _configPath = configPath ?? Default_configPath;
            LoadCredentials();
        }

        public FlightPlan Current
        {
            get { lock (_gate) { return _current; } }
        }

        /// <summary>Whether a SimBrief fetch has ever succeeded this session.</summary>
        public bool HasFetchedSuccessfully
        {
            get { lock (_gate) { return _current != FlightPlan.Empty; } }
        }

        /// <summary>Issue #65 -- full internal fetch state for the debug snapshot file. CredentialsPresent is a bool, never the userId/username values themselves (see FlightPlanDebugSnapshot's own doc comment).</summary>
        public FlightPlanDebugSnapshot BuildDebugSnapshot()
        {
            lock (_gate)
            {
                var credentialsPresent = !string.IsNullOrWhiteSpace(_userId) || !string.IsNullOrWhiteSpace(_username);
                return new FlightPlanDebugSnapshot(_current != FlightPlan.Empty, credentialsPresent, _lastFetchAttemptAt, _lastError, _current);
            }
        }

        /// <summary>
        /// Fetches using whatever credentials were last persisted (from a prior
        /// SetSimbriefCredentialsAndRefreshAsync call, possibly in an earlier plugin session).
        /// No-ops (and reports no operationProgress at all -- there's nothing being attempted)
        /// if neither a user ID nor a username has ever been set; otherwise reports progress
        /// through OperationProgressModel regardless of whether this call came from the Android
        /// app's refresh button or the plugin's own startup fetch, both being equally worth
        /// surfacing a result for. Each call gets its own fresh operationId (a GUID suffix, not
        /// a shared constant) specifically so that clicking refresh repeatedly in quick
        /// succession -- a real, expected user action, unlike VatGlassesDataModel's
        /// once-per-plugin-load sync -- never has two overlapping fetches racing to finish the
        /// same tracked operation.
        /// </summary>
        public Task RefreshAsync()
        {
            string userId, username;
            lock (_gate) { userId = _userId; username = _username; }

            if (string.IsNullOrWhiteSpace(userId) && string.IsNullOrWhiteSpace(username))
            {
                Log("No SimBrief credentials persisted yet -- skipping fetch.");
                return Task.CompletedTask;
            }

            var operationId = "simbriefRefresh-" + Guid.NewGuid().ToString("N");
            _operationProgress.Report(operationId, "Fetching SimBrief flight plan...");
            return FetchAndApplyAsync(userId, username, operationId);
        }

        /// <summary>
        /// Persists the given credentials (full overwrite, whatever is given -- including
        /// null/blank -- replaces what was there) for RefreshAsync to use, both on this
        /// plugin's own next startup and for any bare refreshFlightPlan trigger. Does not
        /// itself fetch -- callers that want an immediate fetch send a separate
        /// refreshFlightPlan, same as the Android UI's "Save & refresh" does.
        /// </summary>
        public void SetSimbriefCredentials(string userId, string username)
        {
            lock (_gate)
            {
                _userId = userId;
                _username = username;
            }
            SaveCredentials(userId, username);
        }

        private async Task FetchAndApplyAsync(string userId, string username, string operationId)
        {
            lock (_gate) { _lastFetchAttemptAt = DateTimeOffset.Now; }

            FlightPlan plan;
            try
            {
                plan = await _fetch(userId, username).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Log("Flight plan fetch threw: " + ex.Message);
                lock (_gate) { _lastError = ex.Message; }
                _operationProgress.Finish(operationId, "SimBrief fetch failed: " + ex.Message, success: false);
                return;
            }

            if (plan == null)
            {
                Log("No flight plan available from SimBrief.");
                lock (_gate) { _lastError = "No SimBrief flight plan available"; }
                _operationProgress.Finish(operationId, "No SimBrief flight plan available", success: false);
                return;
            }

            lock (_gate) { _current = plan; _lastError = null; }
            Log($"Flight plan updated: callsign={plan.Callsign}, origin={plan.Origin}, destination={plan.Destination}, alternate={plan.Alternate}");
            Changed?.Invoke(this, EventArgs.Empty);
            // Reports success (and the fetched route) even when it's identical to what was
            // already loaded -- "the fetch succeeded" is the whole scope of this operation, not
            // "the plan changed". A pilot re-fetching after adjusting fuel in SimBrief, with the
            // route itself unchanged, still needs to know the refresh actually happened.
            _operationProgress.Finish(operationId, $"SimBrief flight plan updated ({plan.Origin} → {plan.Destination})", success: true);
        }

        private void LoadCredentials()
        {
            try
            {
                if (!File.Exists(_configPath)) return;

                var json = File.ReadAllText(_configPath);
                var config = JsonConvert.DeserializeObject<SimbriefCredentials>(json);
                if (config == null) return;

                lock (_gate)
                {
                    _userId = config.UserId;
                    _username = config.Username;
                }
            }
            catch (Exception ex)
            {
                Log("Failed to load persisted SimBrief credentials: " + ex.Message);
            }
        }

        private void SaveCredentials(string userId, string username)
        {
            try
            {
                var directory = Path.GetDirectoryName(_configPath);
                if (directory != null) Directory.CreateDirectory(directory);

                var json = JsonConvert.SerializeObject(new SimbriefCredentials { UserId = userId, Username = username });
                File.WriteAllText(_configPath, json);
            }
            catch (Exception ex)
            {
                Log("Failed to persist SimBrief credentials: " + ex.Message);
            }
        }

        private void Log(string message)
        {
            var line = "FlightPlanModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }

        private sealed class SimbriefCredentials
        {
            public string UserId { get; set; }
            public string Username { get; set; }
        }
    }
}
