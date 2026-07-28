using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net.Http;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Fetches the pilot's latest OFP from the SimBrief API. SimBrief accepts either a numeric
    /// user ID or a username in the same "userid"/"username" query parameter slot; ID is
    /// preferred (usernames have occasionally caused lookup issues) with username as a
    /// fallback. A non-200 response means "no such user" or "no OFP filed yet" -- both are
    /// reported as no flight plan available, not thrown as fatal errors.
    ///
    /// NOTE: the field paths below (atc.callsign, origin/destination/alternate.icao_code) are
    /// taken from public SimBrief API documentation/community usage, not confirmed against a
    /// real account yet -- verify empirically, per CLAUDE.md's "Open items to verify
    /// empirically" convention.
    /// </summary>
    public static class SimBriefClient
    {
        private const string Endpoint = "https://www.simbrief.com/api/xml.fetcher.php";
        private static readonly HttpClient Http = new HttpClient();

        public static async Task<FlightPlan> FetchAsync(string userId, string username, Action<string> logDebug = null)
        {
            if (!string.IsNullOrWhiteSpace(userId))
            {
                var byId = await TryFetchAsync("userid", userId, logDebug).ConfigureAwait(false);
                if (byId != null) return byId;
            }

            if (!string.IsNullOrWhiteSpace(username))
            {
                var byUsername = await TryFetchAsync("username", username, logDebug).ConfigureAwait(false);
                if (byUsername != null) return byUsername;
            }

            return null;
        }

        private static async Task<FlightPlan> TryFetchAsync(string paramName, string paramValue, Action<string> logDebug)
        {
            try
            {
                var url = $"{Endpoint}?{paramName}={Uri.EscapeDataString(paramValue)}&json=1";
                using (var response = await Http.GetAsync(url).ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        logDebug?.Invoke($"SimBriefClient: fetch by {paramName} failed, HTTP {(int)response.StatusCode}.");
                        return null;
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    var json = JObject.Parse(body);

                    return new FlightPlan(
                        callsign: (string)json.SelectToken("atc.callsign"),
                        origin: (string)json.SelectToken("origin.icao_code"),
                        destination: (string)json.SelectToken("destination.icao_code"),
                        alternate: (string)json.SelectToken("alternate.icao_code"),
                        waypoints: ParseWaypoints(body, logDebug));
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke($"SimBriefClient: fetch by {paramName} threw: {ex.Message}");
                return null;
            }
        }

        /// <summary>
        /// Parses navlog.fix[] into ordered route waypoints -- used (issue #9 phase 2) to predict
        /// which VATGlasses sector ownship is approaching from its filed route, not just its
        /// current heading. Field names (pos_lat/pos_long) are taken from public SimBrief API
        /// documentation/community usage, not confirmed against a real OFP response yet -- same
        /// "verify empirically" caveat this class's doc-comment already carries for
        /// atc.callsign/origin.icao_code etc. Degrades to an empty list (not null, not a thrown
        /// exception) on any missing/malformed entry -- a broken waypoint list shouldn't fail the
        /// whole flight-plan fetch, callers just fall back to heading-based approach prediction.
        /// </summary>
        public static List<FlightPlanWaypoint> ParseWaypoints(string json, Action<string> logDebug = null)
        {
            var waypoints = new List<FlightPlanWaypoint>();
            var root = JObject.Parse(json);
            if (!(root.SelectToken("navlog.fix") is JArray fixes)) return waypoints;

            foreach (var fix in fixes)
            {
                try
                {
                    var ident = (string)fix["ident"];
                    var lat = (string)fix["pos_lat"];
                    var lon = (string)fix["pos_long"];
                    if (lat == null || lon == null) continue;

                    waypoints.Add(new FlightPlanWaypoint(
                        ident,
                        double.Parse(lat, CultureInfo.InvariantCulture),
                        double.Parse(lon, CultureInfo.InvariantCulture)));
                }
                catch (Exception ex)
                {
                    logDebug?.Invoke("SimBriefClient: skipping malformed navlog fix: " + ex.Message);
                }
            }

            return waypoints;
        }
    }
}
