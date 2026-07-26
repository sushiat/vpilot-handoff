using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Lists and fetches the VATGlasses sector/boundary dataset
    /// (github.com/lennycolton/vatglasses-data, CC BY-NC-SA 4.0) via the GitHub REST API, and
    /// parses a single region file's JSON. Mirrors SimBriefClient/VatsimDataFeedClient's
    /// pattern: static HttpClient, catch-and-return-null on any failure, never throws to the
    /// caller. Parsing is a separate pure method (ParseRegionFile) for unit testability against
    /// fixture JSON, same reasoning as VatsimDataFeedClient's ParseControllers/ParsePilots.
    ///
    /// NOTE: the "data" directory path, "main" branch, and ParseRegionFile's field shapes have
    /// been confirmed against a live file (data/eg.json, fetched 2026-07-26) -- 155 files, one
    /// "ei" entry is itself a subdirectory rather than a flat file and is silently skipped by
    /// ListDataFilesAsync's file-only filter (a coverage gap, not a bug). Not re-verified against
    /// a pinned commit, so a future upstream schema change could still break this -- VATGlasses
    /// has no published schema doc to pin against.
    /// </summary>
    public static class VatGlassesDataClient
    {
        private const string Owner = "lennycolton";
        private const string Repo = "vatglasses-data";
        private const string DataPath = "data";
        private const string Branch = "main";

        private static readonly HttpClient Http = CreateHttpClient();

        private static HttpClient CreateHttpClient()
        {
            var client = new HttpClient();
            // GitHub's REST API rejects requests with no User-Agent header.
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Handoff-vPilot-Plugin", "1.0"));
            return client;
        }

        /// <summary>
        /// Returns the data directory's latest commit SHA -- a single lightweight request used
        /// to decide whether a full per-file sync is even needed. Null on any failure.
        /// </summary>
        public static async Task<string> FetchLatestCommitShaAsync(Action<string> logDebug = null)
        {
            try
            {
                var url = $"https://api.github.com/repos/{Owner}/{Repo}/commits?path={DataPath}&per_page=1&sha={Branch}";
                using (var response = await Http.GetAsync(url).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"VatGlassesDataClient: commit lookup failed, HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    var commits = JArray.Parse(body);
                    return commits.Count > 0 ? (string)commits[0]["sha"] : null;
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke("VatGlassesDataClient: commit lookup threw: " + ex.Message);
                return null;
            }
        }

        /// <summary>Lists every JSON file in the data directory (name + raw download URL). Null on any failure.</summary>
        public static async Task<IReadOnlyList<VatGlassesDataFile>> ListDataFilesAsync(Action<string> logDebug = null)
        {
            try
            {
                var url = $"https://api.github.com/repos/{Owner}/{Repo}/contents/{DataPath}?ref={Branch}";
                using (var response = await Http.GetAsync(url).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"VatGlassesDataClient: directory listing failed, HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    var entries = JArray.Parse(body);
                    return entries
                        .Where(e => (string)e["type"] == "file" && ((string)e["name"] ?? string.Empty).EndsWith(".json", StringComparison.OrdinalIgnoreCase))
                        .Select(e => new VatGlassesDataFile((string)e["name"], (string)e["download_url"]))
                        .ToList();
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke("VatGlassesDataClient: directory listing threw: " + ex.Message);
                return null;
            }
        }

        /// <summary>Fetches one file's raw JSON content. Null on any failure.</summary>
        public static async Task<string> FetchFileAsync(string downloadUrl, Action<string> logDebug = null)
        {
            try
            {
                using (var response = await Http.GetAsync(downloadUrl).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"VatGlassesDataClient: file fetch failed ({downloadUrl}), HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    return await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke($"VatGlassesDataClient: file fetch threw ({downloadUrl}): " + ex.Message);
                return null;
            }
        }

        /// <summary>
        /// Parses one region file's JSON (see issue #9's description of the airports/airspace/
        /// positions schema). Missing/malformed fields degrade to null/empty rather than
        /// throwing, except for a fundamentally unparseable document (not a JSON object at all),
        /// which throws -- the caller (VatGlassesDataModel) treats that as a failed sync for that
        /// file, same as a failed fetch.
        /// </summary>
        public static VatGlassesRegionData ParseRegionFile(string json)
        {
            var root = JObject.Parse(json);

            var airports = new Dictionary<string, VatGlassesAirport>(StringComparer.OrdinalIgnoreCase);
            if (root["airports"] is JObject airportsObj)
            {
                foreach (var property in airportsObj.Properties())
                {
                    var topdown = (property.Value["topdown"] as JArray)?.Select(t => (string)t).ToList()
                        ?? new List<string>();
                    airports[property.Name] = new VatGlassesAirport(property.Name, topdown);
                }
            }

            var airspace = new List<VatGlassesSector>();
            if (root["airspace"] is JArray airspaceArray)
            {
                foreach (var entry in airspaceArray)
                {
                    var owner = (entry["owner"] as JArray)?.Select(o => (string)o).ToList() ?? new List<string>();
                    var levels = new List<VatGlassesSectorLevel>();
                    if (entry["sectors"] is JArray sectorsArray)
                    {
                        foreach (var sector in sectorsArray)
                        {
                            // Each point is a raw 2-element [lat, lon] array (DMS strings), not
                            // an {lat, lng} object -- confirmed against a live region file
                            // (data/eg.json), not just the issue's prose description.
                            var points = (sector["points"] as JArray)?
                                .Select(p => new VatGlassesPoint((string)p[0], (string)p[1]))
                                .ToList() ?? new List<VatGlassesPoint>();
                            levels.Add(new VatGlassesSectorLevel((double?)sector["min"], (double?)sector["max"], points));
                        }
                    }

                    airspace.Add(new VatGlassesSector((string)entry["id"], (string)entry["group"], owner, levels));
                }
            }

            var positions = new Dictionary<string, VatGlassesPosition>(StringComparer.OrdinalIgnoreCase);
            if (root["positions"] is JObject positionsObj)
            {
                foreach (var property in positionsObj.Properties())
                {
                    var value = property.Value;
                    // "pre" is always an array (confirmed against a live region file), even
                    // when a position only carries one prefix -- e.g. ["LON"], or
                    // ["EGTT", "EGTT-I", "LON", "LON-I"] for one that carries several.
                    var prefixes = (value["pre"] as JArray)?.Select(p => (string)p).ToList() ?? new List<string>();
                    positions[property.Name] = new VatGlassesPosition(
                        id: property.Name,
                        type: (string)value["type"],
                        frequency: (string)value["frequency"],
                        callsign: (string)value["callsign"],
                        prefixes: prefixes);
                }
            }

            return new VatGlassesRegionData(airports, airspace, positions);
        }
    }

    /// <summary>One entry from the GitHub contents API listing -- a region file's name and raw download URL.</summary>
    public sealed class VatGlassesDataFile
    {
        public string Name { get; }
        public string DownloadUrl { get; }

        public VatGlassesDataFile(string name, string downloadUrl)
        {
            Name = name;
            DownloadUrl = downloadUrl;
        }
    }
}
