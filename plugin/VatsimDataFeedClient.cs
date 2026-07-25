using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Fetches and parses the public VATSIM data feed's controllers[] section. Mirrors
    /// SimBriefClient's pattern (static HttpClient, catch-and-return-null on failure, never
    /// throws to the caller). Parsing is split into a pure ParseControllers so it's unit
    /// testable against a fixture JSON string without any network I/O, same reasoning as
    /// ProtocolMessages being kept separate from the socket-handling code that calls it.
    /// </summary>
    public static class VatsimDataFeedClient
    {
        private const string Endpoint = "https://data.vatsim.net/v3/vatsim-data.json";
        private static readonly HttpClient Http = new HttpClient();

        public static async Task<IReadOnlyList<VatsimControllerInfo>> FetchAsync(Action<string> logDebug = null)
        {
            try
            {
                using (var response = await Http.GetAsync(Endpoint).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"VatsimDataFeedClient: fetch failed, HTTP {(int)response.StatusCode}.");
                        return Array.Empty<VatsimControllerInfo>();
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    return ParseControllers(body);
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke("VatsimDataFeedClient: fetch threw: " + ex.Message);
                return Array.Empty<VatsimControllerInfo>();
            }
        }

        /// <summary>
        /// Parses the controllers[] array out of the feed's top-level JSON. Facility 0 (OBS) is
        /// filtered out entirely -- observers aren't ATC and never participate in ranking.
        /// </summary>
        public static IReadOnlyList<VatsimControllerInfo> ParseControllers(string json)
        {
            var result = new List<VatsimControllerInfo>();

            var root = JObject.Parse(json);
            var controllers = root["controllers"] as JArray;
            if (controllers == null) return result;

            foreach (var entry in controllers)
            {
                var facility = (int?)entry["facility"] ?? 0;
                if (facility == 0) continue; // OBS

                var callsign = (string)entry["callsign"];
                if (string.IsNullOrEmpty(callsign)) continue;

                result.Add(new VatsimControllerInfo(
                    callsign: callsign,
                    cid: (int?)entry["cid"] ?? 0,
                    name: (string)entry["name"],
                    facility: facility,
                    rating: (int?)entry["rating"] ?? 0));
            }

            return result;
        }
    }
}
