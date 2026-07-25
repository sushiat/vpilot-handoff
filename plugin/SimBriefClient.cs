using System;
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
                        alternate: (string)json.SelectToken("alternate.icao_code"));
                }
            }
            catch (Exception ex)
            {
                logDebug?.Invoke($"SimBriefClient: fetch by {paramName} threw: {ex.Message}");
                return null;
            }
        }
    }
}
