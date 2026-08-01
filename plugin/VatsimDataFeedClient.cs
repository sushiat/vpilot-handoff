using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Fetches and parses the public VATSIM data feed's controllers[] and pilots[] sections in a
    /// single request. Mirrors SimBriefClient's pattern (static HttpClient, catch-and-return-null
    /// on failure, never throws to the caller). Parsing is split into pure ParseControllers/
    /// ParsePilots methods so they're unit testable against a fixture JSON string without any
    /// network I/O, same reasoning as ProtocolMessages being kept separate from the
    /// socket-handling code that calls it.
    /// </summary>
    public static class VatsimDataFeedClient
    {
        private const string Endpoint = "https://data.vatsim.net/v3/vatsim-data.json";
        private static readonly HttpClient Http = new HttpClient();

        /// <summary>
        /// Returns null on failure (HTTP error or any exception) rather than an empty snapshot,
        /// so callers (VatsimDataFeedModel) can distinguish "feed unreachable" from "feed
        /// returned zero controllers/pilots" for the subsystemStatus connectivity signal -- see
        /// docs/protocol.md.
        /// </summary>
        public static async Task<VatsimDataFeedSnapshot> FetchAsync(Action<string> logDebug = null)
        {
            try
            {
                using (var response = await Http.GetAsync(Endpoint).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"VatsimDataFeedClient: fetch failed, HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    return new VatsimDataFeedSnapshot(ParseControllers(body), ParsePilots(body));
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke("VatsimDataFeedClient: fetch threw: " + ex.Message);
                return null;
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
                    rating: (int?)entry["rating"] ?? 0,
                    textAtis: ParseTextAtis(entry["text_atis"])));
            }

            return result;
        }

        /// <summary>"text_atis" is an array of strings (multi-line) when present, but frequently absent entirely -- never a parse failure either way, just an empty list.</summary>
        private static List<string> ParseTextAtis(JToken token) =>
            (token as JArray)?.Where(t => t.Type == JTokenType.String).Select(t => (string)t).ToList() ?? new List<string>();

        /// <summary>
        /// Parses the pilots[] array, keeping only entries with a filed flight plan -- a pilot
        /// connected but not yet filed has no "flight_plan" key at all (or it's null), and that's
        /// not a useful cross-check signal, just absence of one.
        /// </summary>
        public static IReadOnlyList<VatsimPilotInfo> ParsePilots(string json)
        {
            var result = new List<VatsimPilotInfo>();

            var root = JObject.Parse(json);
            var pilots = root["pilots"] as JArray;
            if (pilots == null) return result;

            foreach (var entry in pilots)
            {
                var callsign = (string)entry["callsign"];
                if (string.IsNullOrEmpty(callsign)) continue;

                var flightPlan = entry["flight_plan"];
                if (flightPlan == null || flightPlan.Type == JTokenType.Null) continue;

                var cidToken = entry["cid"];
                var cid = cidToken == null || cidToken.Type == JTokenType.Null ? null : cidToken.ToString();

                result.Add(new VatsimPilotInfo(
                    callsign: callsign,
                    departure: (string)flightPlan["departure"],
                    arrival: (string)flightPlan["arrival"],
                    cid: cid));
            }

            return result;
        }
    }
}
