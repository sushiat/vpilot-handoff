using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace Handoff.Plugin
{
    /// <summary>
    /// Owns the VATGlasses dataset's fetch-then-cache-then-parse lifecycle (issue #9 phase 1).
    /// Loads whatever's on disk cache synchronously at construction so callers have data
    /// immediately, then SyncAsync() (kicked off fire-and-forget by HandoffPlugin, same shape as
    /// FlightPlanModel.RefreshAsync -- never blocks plugin startup on a network call) checks the
    /// data repo's latest commit SHA and only does a full per-file re-download when it's actually
    /// changed. Progress is reported through OperationProgressModel so the Android app can show
    /// something other than silence while a full sync (rare -- first run, or after an upstream
    /// data update) is in progress.
    ///
    /// Deliberately does not do anything with the parsed data beyond exposing it (no point-in-
    /// polygon lookup, no DMS conversion, no ranking integration) -- that's the ranking-
    /// integration follow-up plan, not this one.
    /// </summary>
    public sealed class VatGlassesDataModel
    {
        private const string OperationIdPrefix = "vatGlassesSync";
        private const string ShaFileName = "_commit.sha";

        private readonly object _gate = new object();
        private readonly OperationProgressModel _operationProgress;
        private readonly Action<string> _logDebug;
        private readonly string _cacheDirectory;
        private readonly Func<Task<string>> _fetchLatestSha;
        private readonly Func<Task<IReadOnlyList<VatGlassesDataFile>>> _listFiles;
        private readonly Func<string, Task<string>> _fetchFile;

        private IReadOnlyDictionary<string, VatGlassesRegionData> _regionsByFileName =
            new Dictionary<string, VatGlassesRegionData>(StringComparer.OrdinalIgnoreCase);

        public event EventHandler Changed;

        public VatGlassesDataModel(
            OperationProgressModel operationProgress,
            Action<string> logDebug = null,
            string cacheDirectory = null,
            Func<Task<string>> fetchLatestSha = null,
            Func<Task<IReadOnlyList<VatGlassesDataFile>>> listFiles = null,
            Func<string, Task<string>> fetchFile = null)
        {
            _operationProgress = operationProgress ?? throw new ArgumentNullException(nameof(operationProgress));
            _logDebug = logDebug;
            _cacheDirectory = cacheDirectory ?? DefaultCacheDirectory();
            _fetchLatestSha = fetchLatestSha ?? (() => VatGlassesDataClient.FetchLatestCommitShaAsync(_logDebug));
            _listFiles = listFiles ?? (() => VatGlassesDataClient.ListDataFilesAsync(_logDebug));
            _fetchFile = fetchFile ?? (url => VatGlassesDataClient.FetchFileAsync(url, _logDebug));

            LoadFromDiskCache();
        }

        private static string DefaultCacheDirectory() => PathJoin.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "vatglasses-cache");

        /// <summary>Parsed region data keyed by source file name -- empty until at least one successful load (disk cache or network).</summary>
        public IReadOnlyDictionary<string, VatGlassesRegionData> Regions
        {
            get { lock (_gate) { return _regionsByFileName; } }
        }

        /// <summary>Issue #65 -- loaded-data state for the debug snapshot file. A sync that reported success doesn't guarantee what actually parsed into usable data -- this reads Regions directly, same as everything else here.</summary>
        public VatGlassesDebugSnapshot BuildDebugSnapshot()
        {
            lock (_gate)
            {
                return new VatGlassesDebugSnapshot(_regionsByFileName.Keys.ToList(), ReadCachedSha());
            }
        }

        /// <summary>
        /// Runs the check-then-sync described above. Never throws -- a failure before the
        /// per-file loop even starts (SHA check, file listing) leaves whatever was already
        /// loaded untouched; a failure partway through the loop keeps every file that did
        /// succeed (both on disk and in Regions) rather than discarding the whole run.
        /// Only ever invoked once per plugin load today, but still gets its own fresh
        /// operationId (a GUID suffix, not a shared constant) rather than assuming that -- the
        /// same convention FlightPlanModel's user-triggered refresh needs for real, so there's
        /// one rule for operationIds across the whole OperationProgressModel mechanism instead
        /// of a special case per caller.
        /// </summary>
        public async Task SyncAsync()
        {
            var operationId = OperationIdPrefix + "-" + Guid.NewGuid().ToString("N");
            _operationProgress.Report(operationId, "Checking for VatGlasses updates...");

            string latestSha;
            try
            {
                latestSha = await _fetchLatestSha().ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Log("Commit SHA check threw: " + ex.Message);
                latestSha = null;
            }

            if (latestSha == null)
            {
                Log("Update check failed -- using cached data.");
                _operationProgress.Finish(operationId, "VatGlasses update check failed -- using cached data.", success: false);
                return;
            }

            var cachedSha = ReadCachedSha();
            if (cachedSha != null && string.Equals(cachedSha, latestSha, StringComparison.Ordinal))
            {
                Log("Data up to date (commit " + latestSha + ").");
                _operationProgress.Finish(operationId, "VatGlasses data up to date");
                return;
            }

            IReadOnlyList<VatGlassesDataFile> files;
            try
            {
                files = await _listFiles().ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Log("File listing threw: " + ex.Message);
                files = null;
            }

            if (files == null || files.Count == 0)
            {
                Log("File listing failed -- using cached data.");
                _operationProgress.Finish(operationId, "VatGlasses file listing failed -- using cached data.", success: false);
                return;
            }

            // Each region file is independent -- no cross-file references -- so each one is
            // written to disk (and folded into Regions) as soon as it's fetched and parsed,
            // rather than held in memory and only written once the whole batch succeeds. A sync
            // that fails partway through (a rate limit, a transient network blip) still keeps
            // whatever it got instead of discarding it all. Only the commit-SHA marker is
            // deferred to the very end: as long as it's still the old one, the next sync attempt
            // knows the data is incomplete and retries the full list (cheap -- fetches are
            // idempotent overwrites of whatever's already on disk).
            var succeededCount = 0;
            for (var i = 0; i < files.Count; i++)
            {
                var file = files[i];
                _operationProgress.Report(operationId, $"Updating VatGlasses file {i + 1}/{files.Count}");

                string json;
                try
                {
                    json = await _fetchFile(file.DownloadUrl).ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    Log($"Fetch of {file.Name} threw: {ex.Message}");
                    json = null;
                }

                if (json == null)
                {
                    Log($"Skipping {file.Name} -- fetch failed. Keeping {succeededCount}/{files.Count} files already synced this run.");
                    continue;
                }

                VatGlassesRegionData parsed;
                try
                {
                    parsed = VatGlassesDataClient.ParseRegionFile(json);
                }
                catch (Exception ex)
                {
                    Log($"Skipping {file.Name} -- parse failed: {ex.Message}");
                    continue;
                }

                WriteRegionFile(file.Name, json);
                lock (_gate)
                {
                    // IReadOnlyDictionary has no direct copy-constructor overload -- built by
                    // hand rather than via LINQ's ToDictionary purely to avoid an extra `using`.
                    var next = new Dictionary<string, VatGlassesRegionData>(StringComparer.OrdinalIgnoreCase);
                    foreach (var kv in _regionsByFileName) next[kv.Key] = kv.Value;
                    next[file.Name] = parsed;
                    _regionsByFileName = next;
                }
                Changed?.Invoke(this, EventArgs.Empty);
                succeededCount++;
            }

            if (succeededCount == files.Count)
            {
                WriteShaMarker(latestSha);
                Log($"Data updated ({succeededCount}/{files.Count} files, commit {latestSha}).");
                _operationProgress.Finish(operationId, "VatGlasses data updated");
            }
            else
            {
                Log($"Sync incomplete ({succeededCount}/{files.Count} files) -- will retry next startup.");
                _operationProgress.Finish(operationId, $"VatGlasses sync incomplete ({succeededCount}/{files.Count} files) -- will retry next startup.", success: false);
            }
        }

        private void LoadFromDiskCache()
        {
            try
            {
                if (!Directory.Exists(_cacheDirectory)) return;

                // One corrupt/truncated cached file (e.g. from a prior hard crash mid-write)
                // must not discard every other perfectly good cached file -- skip and log just
                // that one, same "a single broken file can't kill the whole batch" reasoning as
                // SyncAsync's per-file loop below.
                var loaded = new Dictionary<string, VatGlassesRegionData>(StringComparer.OrdinalIgnoreCase);
                foreach (var path in Directory.GetFiles(_cacheDirectory, "*.json"))
                {
                    try
                    {
                        var json = File.ReadAllText(path);
                        loaded[Path.GetFileName(path)] = VatGlassesDataClient.ParseRegionFile(json);
                    }
                    catch (Exception ex)
                    {
                        Log($"Skipping cached file {Path.GetFileName(path)} -- failed to load: {ex.Message}");
                    }
                }

                if (loaded.Count > 0)
                {
                    lock (_gate) { _regionsByFileName = loaded; }
                }
            }
            catch (Exception ex)
            {
                Log("Failed to load disk cache: " + ex.Message);
            }
        }

        private string ReadCachedSha()
        {
            try
            {
                var path = PathJoin.Combine(_cacheDirectory, ShaFileName);
                return File.Exists(path) ? File.ReadAllText(path).Trim() : null;
            }
            catch (Exception ex)
            {
                Log("Failed to read cached commit SHA: " + ex.Message);
                return null;
            }
        }

        /// <summary>
        /// Writes one region file's raw JSON straight into the live cache directory -- each file
        /// is independent, so there's nothing to stage/swap the way a single combined write
        /// would need. Overwrites whatever was there for this file name; leaves every other
        /// cached file untouched, including when this call itself fails.
        /// </summary>
        private void WriteRegionFile(string fileName, string json)
        {
            try
            {
                Directory.CreateDirectory(_cacheDirectory);
                File.WriteAllText(PathJoin.Combine(_cacheDirectory, fileName), json);
            }
            catch (Exception ex)
            {
                Log($"Failed to write {fileName} to disk cache: {ex.Message}");
            }
        }

        /// <summary>
        /// Written only once every file in a sync succeeded -- an incomplete sync leaves the old
        /// (or absent) marker in place, so the next attempt's SHA comparison correctly treats the
        /// data as still out of date and retries the full list.
        /// </summary>
        private void WriteShaMarker(string sha)
        {
            try
            {
                Directory.CreateDirectory(_cacheDirectory);
                File.WriteAllText(PathJoin.Combine(_cacheDirectory, ShaFileName), sha);
            }
            catch (Exception ex)
            {
                Log("Failed to write commit SHA marker: " + ex.Message);
            }
        }

        private void Log(string message)
        {
            var line = "VatGlassesDataModel: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
