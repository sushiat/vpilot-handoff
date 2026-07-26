using System;
using System.Collections.Generic;
using System.IO;
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
        public const string SyncOperationId = "vatGlassesSync";

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

        private static string DefaultCacheDirectory() => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "vatglasses-cache");

        /// <summary>Parsed region data keyed by source file name -- empty until at least one successful load (disk cache or network).</summary>
        public IReadOnlyDictionary<string, VatGlassesRegionData> Regions
        {
            get { lock (_gate) { return _regionsByFileName; } }
        }

        /// <summary>
        /// Runs the check-then-sync described above. Never throws -- every failure path reports
        /// through OperationProgressModel and leaves whatever was already loaded (disk cache or
        /// a prior successful sync) untouched.
        /// </summary>
        public async Task SyncAsync()
        {
            _operationProgress.Report(SyncOperationId, "Checking for VatGlasses updates...");

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
                _operationProgress.Finish(SyncOperationId, "VatGlasses update check failed -- using cached data.");
                return;
            }

            var cachedSha = ReadCachedSha();
            if (cachedSha != null && string.Equals(cachedSha, latestSha, StringComparison.Ordinal))
            {
                _operationProgress.Finish(SyncOperationId, "VatGlasses data up to date");
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
                _operationProgress.Finish(SyncOperationId, "VatGlasses file listing failed -- using cached data.");
                return;
            }

            var fetchedRegions = new Dictionary<string, VatGlassesRegionData>(StringComparer.OrdinalIgnoreCase);
            var rawByFileName = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (var i = 0; i < files.Count; i++)
            {
                var file = files[i];
                _operationProgress.Report(SyncOperationId, $"Updating VatGlasses file {i + 1}/{files.Count}");

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
                    _operationProgress.Finish(SyncOperationId, $"VatGlasses sync failed fetching {file.Name} -- using cached data.");
                    return;
                }

                try
                {
                    fetchedRegions[file.Name] = VatGlassesDataClient.ParseRegionFile(json);
                }
                catch (Exception ex)
                {
                    Log($"Failed to parse {file.Name}: {ex.Message}");
                    _operationProgress.Finish(SyncOperationId, $"VatGlasses sync failed parsing {file.Name} -- using cached data.");
                    return;
                }

                rawByFileName[file.Name] = json;
            }

            WriteDiskCache(rawByFileName, latestSha);

            lock (_gate) { _regionsByFileName = fetchedRegions; }
            Changed?.Invoke(this, EventArgs.Empty);

            _operationProgress.Finish(SyncOperationId, "VatGlasses data updated");
        }

        private void LoadFromDiskCache()
        {
            try
            {
                if (!Directory.Exists(_cacheDirectory)) return;

                var loaded = new Dictionary<string, VatGlassesRegionData>(StringComparer.OrdinalIgnoreCase);
                foreach (var path in Directory.GetFiles(_cacheDirectory, "*.json"))
                {
                    var json = File.ReadAllText(path);
                    loaded[Path.GetFileName(path)] = VatGlassesDataClient.ParseRegionFile(json);
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
                var path = Path.Combine(_cacheDirectory, ShaFileName);
                return File.Exists(path) ? File.ReadAllText(path).Trim() : null;
            }
            catch (Exception ex)
            {
                Log("Failed to read cached commit SHA: " + ex.Message);
                return null;
            }
        }

        /// <summary>
        /// Writes to a staging directory and swaps it in with a single Directory.Move, rather
        /// than overwriting files in the live cache directory one at a time -- so a process
        /// crash or exception partway through never leaves the on-disk cache half-written (the
        /// old cache directory stays fully intact right up until the swap).
        /// </summary>
        private void WriteDiskCache(IReadOnlyDictionary<string, string> rawByFileName, string sha)
        {
            try
            {
                var stagingDirectory = _cacheDirectory + ".staging";
                if (Directory.Exists(stagingDirectory)) Directory.Delete(stagingDirectory, recursive: true);
                Directory.CreateDirectory(stagingDirectory);

                foreach (var entry in rawByFileName)
                {
                    File.WriteAllText(Path.Combine(stagingDirectory, entry.Key), entry.Value);
                }
                File.WriteAllText(Path.Combine(stagingDirectory, ShaFileName), sha);

                if (Directory.Exists(_cacheDirectory)) Directory.Delete(_cacheDirectory, recursive: true);
                Directory.Move(stagingDirectory, _cacheDirectory);
            }
            catch (Exception ex)
            {
                Log("Failed to write disk cache: " + ex.Message);
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
