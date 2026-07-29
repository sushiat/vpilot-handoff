using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace Handoff.Plugin
{
    /// <summary>
    /// Owns the vatspy-data-project dataset's fetch-then-cache-then-parse lifecycle (issue #11),
    /// same shape as VatGlassesDataModel (issue #9): synchronous disk-cache-only read at
    /// construction so callers have data immediately, then SyncAsync() (fired fire-and-forget by
    /// HandoffPlugin, never blocking plugin startup) checks the repo's latest commit SHA and only
    /// re-downloads when it's actually changed. vatspy data changes far less often than live
    /// controller data -- same "static snapshot, periodic background refresh" reasoning as
    /// VATGlasses, not VatsimDataFeedModel's 15s poll loop.
    /// </summary>
    public sealed class VatSpyDataModel
    {
        private const string OperationIdPrefix = "vatSpySync";
        private const string ShaFileName = "_commit.sha";
        private const string BoundariesCacheFileName = "Boundaries.geojson";
        private const string VatSpyDatCacheFileName = "VATSpy.dat";

        private readonly object _gate = new object();
        private readonly OperationProgressModel _operationProgress;
        private readonly Action<string> _logDebug;
        private readonly string _cacheDirectory;
        private readonly Func<Task<string>> _fetchLatestSha;
        private readonly Func<Task<string>> _fetchBoundariesJson;
        private readonly Func<Task<string>> _fetchVatSpyDat;

        private IReadOnlyList<VatSpyFirBoundary> _firBoundaries = Array.Empty<VatSpyFirBoundary>();
        private IReadOnlyDictionary<string, VatSpyAirportInfo> _airportsByIcao =
            new Dictionary<string, VatSpyAirportInfo>(StringComparer.OrdinalIgnoreCase);
        private IReadOnlyDictionary<string, string> _ctrSuffixByCountryPrefix =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public event EventHandler Changed;

        public VatSpyDataModel(
            OperationProgressModel operationProgress,
            Action<string> logDebug = null,
            string cacheDirectory = null,
            Func<Task<string>> fetchLatestSha = null,
            Func<Task<string>> fetchBoundariesJson = null,
            Func<Task<string>> fetchVatSpyDat = null)
        {
            _operationProgress = operationProgress ?? throw new ArgumentNullException(nameof(operationProgress));
            _logDebug = logDebug;
            _cacheDirectory = cacheDirectory ?? DefaultCacheDirectory();
            _fetchLatestSha = fetchLatestSha ?? (() => VatSpyDataClient.FetchLatestCommitShaAsync(_logDebug));
            _fetchBoundariesJson = fetchBoundariesJson ?? (() => VatSpyDataClient.FetchBoundariesJsonAsync(_logDebug));
            _fetchVatSpyDat = fetchVatSpyDat ?? (() => VatSpyDataClient.FetchVatSpyDatAsync(_logDebug));

            LoadFromDiskCache();
        }

        private static string DefaultCacheDirectory() => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "vatspy-cache");

        /// <summary>FIR/UIR boundary polygons -- one entry per outer ring, empty until at least one successful load.</summary>
        public IReadOnlyList<VatSpyFirBoundary> FirBoundaries { get { lock (_gate) { return _firBoundaries; } } }

        /// <summary>Airport display names, keyed by ICAO -- for DEL/GND/TWR/APP station-name composition.</summary>
        public IReadOnlyDictionary<string, VatSpyAirportInfo> AirportsByIcao { get { lock (_gate) { return _airportsByIcao; } } }

        /// <summary>CTR suffix word override by 2-letter country ICAO prefix (e.g. "LO" -&gt; "Radar") -- see VatSpyStationNaming.</summary>
        public IReadOnlyDictionary<string, string> CtrSuffixByCountryPrefix { get { lock (_gate) { return _ctrSuffixByCountryPrefix; } } }

        /// <summary>
        /// Checks the repo's latest commit SHA; if changed (or no cache yet), re-fetches both
        /// files and re-parses/re-combines. Never throws -- a failure leaves whatever was already
        /// loaded (disk cache or a prior successful sync) untouched. Both files are fetched as one
        /// unit (unlike VATGlasses' per-file incremental sync) since there are only two of them
        /// and they're joined together (VatSpyFirBoundary needs both to be internally consistent)
        /// -- a partial success (only one file fetched) is treated as a failed sync, old marker
        /// left in place, next attempt retries both.
        /// </summary>
        public async Task SyncAsync()
        {
            var operationId = OperationIdPrefix + "-" + Guid.NewGuid().ToString("N");
            _operationProgress.Report(operationId, "Checking for VatSpy updates...");

            string latestSha;
            try { latestSha = await _fetchLatestSha().ConfigureAwait(false); }
            catch (Exception ex) { Log("Commit SHA check threw: " + ex.Message); latestSha = null; }

            if (latestSha == null)
            {
                _operationProgress.Finish(operationId, "VatSpy update check failed -- using cached data.", success: false);
                return;
            }

            var cachedSha = ReadCachedSha();
            if (cachedSha != null && string.Equals(cachedSha, latestSha, StringComparison.Ordinal))
            {
                _operationProgress.Finish(operationId, "VatSpy data up to date");
                return;
            }

            _operationProgress.Report(operationId, "Downloading VatSpy boundaries...");
            string boundariesJson;
            try { boundariesJson = await _fetchBoundariesJson().ConfigureAwait(false); }
            catch (Exception ex) { Log("Boundaries fetch threw: " + ex.Message); boundariesJson = null; }

            _operationProgress.Report(operationId, "Downloading VatSpy station data...");
            string vatSpyDat;
            try { vatSpyDat = await _fetchVatSpyDat().ConfigureAwait(false); }
            catch (Exception ex) { Log("VATSpy.dat fetch threw: " + ex.Message); vatSpyDat = null; }

            if (boundariesJson == null || vatSpyDat == null)
            {
                _operationProgress.Finish(operationId, "VatSpy sync incomplete -- will retry next startup.", success: false);
                return;
            }

            if (!TryApply(boundariesJson, vatSpyDat, out var error))
            {
                Log("Parse failed: " + error);
                _operationProgress.Finish(operationId, "VatSpy data update failed to parse -- using cached data.", success: false);
                return;
            }

            WriteCacheFile(BoundariesCacheFileName, boundariesJson);
            WriteCacheFile(VatSpyDatCacheFileName, vatSpyDat);
            WriteShaMarker(latestSha);
            Changed?.Invoke(this, EventArgs.Empty);
            _operationProgress.Finish(operationId, "VatSpy data updated");
        }

        private void LoadFromDiskCache()
        {
            try
            {
                var boundariesPath = Path.Combine(_cacheDirectory, BoundariesCacheFileName);
                var vatSpyDatPath = Path.Combine(_cacheDirectory, VatSpyDatCacheFileName);
                if (!File.Exists(boundariesPath) || !File.Exists(vatSpyDatPath)) return;

                if (!TryApply(File.ReadAllText(boundariesPath), File.ReadAllText(vatSpyDatPath), out var error))
                {
                    Log("Failed to load disk cache: " + error);
                }
            }
            catch (Exception ex)
            {
                Log("Failed to load disk cache: " + ex.Message);
            }
        }

        /// <summary>Parses and combines both files, swapping the live snapshot in only if both succeed.</summary>
        private bool TryApply(string boundariesJson, string vatSpyDat, out string error)
        {
            try
            {
                var rings = VatSpyDataClient.ParseBoundaryRings(boundariesJson);
                var dat = VatSpyDataClient.ParseVatSpyDat(vatSpyDat);
                var boundaries = Combine(dat, rings);

                lock (_gate)
                {
                    _firBoundaries = boundaries;
                    _airportsByIcao = dat.AirportsByIcao;
                    _ctrSuffixByCountryPrefix = dat.CtrSuffixByCountryPrefix;
                }
                error = null;
                return true;
            }
            catch (Exception ex)
            {
                error = ex.Message;
                return false;
            }
        }

        /// <summary>
        /// Joins `[FIRs]` rows to Boundaries.geojson rings by boundary id, gathering every
        /// distinct callsign prefix that row-group contributes (see VatSpyFirRow -- multiple rows
        /// commonly share one boundary id for sub-position variants) onto every ring of that
        /// boundary. A `[FIRs]` row with no matching geometry (a purely virtual/unmapped entry) is
        /// silently dropped -- no polygon means no containment ability, nothing else to do with it.
        /// </summary>
        private static IReadOnlyList<VatSpyFirBoundary> Combine(
            VatSpyDatFile dat, IReadOnlyDictionary<string, List<IReadOnlyList<VatSpyPoint>>> boundaryRings)
        {
            var result = new List<VatSpyFirBoundary>();
            foreach (var group in dat.FirRows.GroupBy(r => r.BoundaryId, StringComparer.OrdinalIgnoreCase))
            {
                if (!boundaryRings.TryGetValue(group.Key, out var rings)) continue;

                var name = group.First().Name;
                var prefixes = group.Select(r => r.CallsignPrefix).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
                foreach (var ring in rings)
                {
                    result.Add(new VatSpyFirBoundary(group.Key, name, prefixes, ring));
                }
            }
            return result;
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

        private void WriteCacheFile(string fileName, string content)
        {
            try
            {
                Directory.CreateDirectory(_cacheDirectory);
                File.WriteAllText(Path.Combine(_cacheDirectory, fileName), content);
            }
            catch (Exception ex)
            {
                Log($"Failed to write {fileName} to disk cache: {ex.Message}");
            }
        }

        private void WriteShaMarker(string sha)
        {
            try
            {
                Directory.CreateDirectory(_cacheDirectory);
                File.WriteAllText(Path.Combine(_cacheDirectory, ShaFileName), sha);
            }
            catch (Exception ex)
            {
                Log("Failed to write commit SHA marker: " + ex.Message);
            }
        }

        private void Log(string message)
        {
            var line = "VatSpyDataModel: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
