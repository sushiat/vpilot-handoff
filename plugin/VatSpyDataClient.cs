using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Fetches and parses the vatspy-data-project dataset (github.com/vatsimnetwork/
    /// vatspy-data-project) -- FIR/UIR display names, callsign-prefix-to-FIR mapping, and CTR
    /// suffix-word-by-country, plus Boundaries.geojson's FIR polygon rings. See issue #11.
    ///
    /// Two fixed top-level files, unlike VATGlasses' one-file-per-region layout, so there's no
    /// directory-listing step -- just a commit-SHA check (repo-wide, not path-filtered: the
    /// repo has no other data files that would cause a spurious re-sync) and two direct
    /// raw-file fetches. Mirrors VatGlassesDataClient's shape otherwise: static HttpClient,
    /// catch-and-return-null fetch methods, pure Parse* methods for unit testability.
    ///
    /// NOTE: schema confirmed against the live repo (2026-07-29) -- default branch is "master"
    /// (not "main", unlike vatglasses-data), and there is no FIRs.dat/Airports.dat; both live as
    /// sections inside one VATSpy.dat (pipe-delimited, INI-style `[Section]` headers, `;`-prefixed
    /// comment lines). Boundaries.geojson's `properties.id` is the join key against VATSpy.dat's
    /// `[FIRs]` section's 4th column ("FIR BOUNDARY"), not always the same as that row's own ICAO
    /// column (sub-divided FIRs have one row per sub-region boundary, e.g. "EDWW-ALR"). See
    /// VatSpyFirBoundary's own doc comment for more on that join.
    /// </summary>
    public static class VatSpyDataClient
    {
        private const string Owner = "vatsimnetwork";
        private const string Repo = "vatspy-data-project";
        private const string Branch = "master";
        private const string BoundariesFileName = "Boundaries.geojson";
        private const string VatSpyDatFileName = "VATSpy.dat";

        private static readonly HttpClient Http = CreateHttpClient();

        private static HttpClient CreateHttpClient()
        {
            var client = new HttpClient();
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Handoff-vPilot-Plugin", "1.0"));
            return client;
        }

        /// <summary>Latest commit SHA for the whole repo -- used to decide whether a re-fetch of either file is needed. Null on any failure.</summary>
        public static async Task<string> FetchLatestCommitShaAsync(Action<string> logDebug = null)
        {
            try
            {
                var url = $"https://api.github.com/repos/{Owner}/{Repo}/commits?per_page=1&sha={Branch}";
                using (var response = await Http.GetAsync(url).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"VatSpyDataClient: commit lookup failed, HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    var commits = JArray.Parse(body);
                    return commits.Count > 0 ? (string)commits[0]["sha"] : null;
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke("VatSpyDataClient: commit lookup threw: " + ex.Message);
                return null;
            }
        }

        public static Task<string> FetchBoundariesJsonAsync(Action<string> logDebug = null) => FetchRawFileAsync(BoundariesFileName, logDebug);

        public static Task<string> FetchVatSpyDatAsync(Action<string> logDebug = null) => FetchRawFileAsync(VatSpyDatFileName, logDebug);

        private static async Task<string> FetchRawFileAsync(string fileName, Action<string> logDebug)
        {
            try
            {
                var url = $"https://raw.githubusercontent.com/{Owner}/{Repo}/{Branch}/{fileName}";
                using (var response = await Http.GetAsync(url).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"VatSpyDataClient: fetch of {fileName} failed, HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    return await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke($"VatSpyDataClient: fetch of {fileName} threw: " + ex.Message);
                return null;
            }
        }

        /// <summary>
        /// Parses Boundaries.geojson into outer-ring point lists keyed by boundary id
        /// (`properties.id`). Geometry is always a MultiPolygon (confirmed against schema.json);
        /// only each polygon's first (outer) ring is kept -- see VatSpyFirBoundary's doc comment
        /// on why holes aren't modeled. One malformed feature is skipped, not fatal to the batch,
        /// same per-entry tolerance as VatGlassesDataClient.ParseAirspace.
        /// </summary>
        public static IReadOnlyDictionary<string, List<IReadOnlyList<VatSpyPoint>>> ParseBoundaryRings(string geojson)
        {
            var result = new Dictionary<string, List<IReadOnlyList<VatSpyPoint>>>(StringComparer.OrdinalIgnoreCase);
            var root = JObject.Parse(geojson);
            if (!(root["features"] is JArray features)) return result;

            for (var i = 0; i < features.Count; i++)
            {
                var feature = features[i];
                try
                {
                    var id = (string)feature["properties"]?["id"];
                    if (string.IsNullOrEmpty(id)) continue;

                    var polygons = feature["geometry"]?["coordinates"] as JArray;
                    if (polygons == null) continue;

                    if (!result.TryGetValue(id, out var rings))
                    {
                        rings = new List<IReadOnlyList<VatSpyPoint>>();
                        result[id] = rings;
                    }

                    foreach (var polygon in polygons)
                    {
                        // polygon[0] is the outer ring; polygon[1..] (if present) are holes, skipped.
                        if (!(polygon is JArray polygonRings) || polygonRings.Count == 0) continue;
                        if (!(polygonRings[0] is JArray outerRing)) continue;

                        var points = new List<VatSpyPoint>(outerRing.Count);
                        foreach (var coord in outerRing)
                        {
                            // GeoJSON order is [lon, lat] -- reversed from VatGlasses' (lat, lon) convention.
                            points.Add(new VatSpyPoint((double)coord[1], (double)coord[0]));
                        }
                        rings.Add(points);
                    }
                }
                catch (Exception ex)
                {
                    throw new FormatException($"features[{i}]: {ex.Message}", ex);
                }
            }

            return result;
        }

        /// <summary>
        /// Parses VATSpy.dat's `[Countries]`, `[Airports]`, and `[FIRs]` sections (`[UIRs]`/`[IDL]`
        /// are out of scope for this issue -- see docs/controller-ranking.md). One malformed line
        /// is skipped, not fatal to the batch.
        /// </summary>
        public static VatSpyDatFile ParseVatSpyDat(string text)
        {
            var ctrSuffixByCountryPrefix = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            var airportsByIcao = new Dictionary<string, VatSpyAirportInfo>(StringComparer.OrdinalIgnoreCase);
            var firRows = new List<VatSpyFirRow>();

            var section = string.Empty;
            var lines = text.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
            for (var i = 0; i < lines.Length; i++)
            {
                var line = lines[i].Trim();
                if (line.Length == 0 || line[0] == ';') continue;

                if (line[0] == '[' && line[line.Length - 1] == ']')
                {
                    section = line.Substring(1, line.Length - 2);
                    continue;
                }

                try
                {
                    var fields = line.Split('|');
                    switch (section)
                    {
                        case "Countries":
                            // CountryName|IcaoPrefix|CtrSuffix -- CtrSuffix is frequently blank
                            // (meaning "use the default word"), e.g. "USA|KZ|" -- not a parse
                            // failure, just an unset override.
                            if (fields.Length >= 2 && fields[1].Length > 0)
                                ctrSuffixByCountryPrefix[fields[1]] = fields.Length >= 3 ? fields[2] : string.Empty;
                            break;

                        case "Airports":
                            // ICAO|Airport Name|Latitude|Longitude|IATA/LID|FIR|IsPseudo
                            if (fields.Length >= 2 && fields[0].Length > 0)
                                airportsByIcao[fields[0]] = new VatSpyAirportInfo(fields[0], fields[1]);
                            break;

                        case "FIRs":
                            // ICAO|NAME|CALLSIGN PREFIX|FIR BOUNDARY -- CALLSIGN PREFIX is
                            // frequently blank, meaning "use the ICAO column itself as the prefix"
                            // (the plain top-level position, e.g. a bare EDWW_CTR).
                            if (fields.Length >= 4 && fields[0].Length > 0)
                            {
                                var prefix = fields[2].Length > 0 ? fields[2] : fields[0];
                                firRows.Add(new VatSpyFirRow(fields[0], fields[1], prefix, fields[3]));
                            }
                            break;

                        default:
                            break; // [UIRs]/[IDL]/anything else -- not needed for this issue.
                    }
                }
                catch (Exception ex)
                {
                    throw new FormatException($"VATSpy.dat line {i + 1} (section [{section}]): {ex.Message}", ex);
                }
            }

            return new VatSpyDatFile(ctrSuffixByCountryPrefix, airportsByIcao, firRows);
        }
    }

    /// <summary>One `[FIRs]` row from VATSpy.dat -- see VatSpyDataClient.ParseVatSpyDat.</summary>
    public sealed class VatSpyFirRow
    {
        public string Icao { get; }
        public string Name { get; }
        public string CallsignPrefix { get; }
        public string BoundaryId { get; }

        public VatSpyFirRow(string icao, string name, string callsignPrefix, string boundaryId)
        {
            Icao = icao;
            Name = name;
            CallsignPrefix = callsignPrefix;
            BoundaryId = boundaryId;
        }
    }

    /// <summary>Parsed contents of VATSpy.dat -- see VatSpyDataClient.ParseVatSpyDat.</summary>
    public sealed class VatSpyDatFile
    {
        public IReadOnlyDictionary<string, string> CtrSuffixByCountryPrefix { get; }
        public IReadOnlyDictionary<string, VatSpyAirportInfo> AirportsByIcao { get; }
        public IReadOnlyList<VatSpyFirRow> FirRows { get; }

        public VatSpyDatFile(
            IReadOnlyDictionary<string, string> ctrSuffixByCountryPrefix,
            IReadOnlyDictionary<string, VatSpyAirportInfo> airportsByIcao,
            IReadOnlyList<VatSpyFirRow> firRows)
        {
            CtrSuffixByCountryPrefix = ctrSuffixByCountryPrefix;
            AirportsByIcao = airportsByIcao;
            FirRows = firRows;
        }
    }
}
