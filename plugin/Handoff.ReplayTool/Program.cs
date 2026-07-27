using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using Handoff.Plugin;

namespace Handoff.ReplayTool
{
    /// <summary>
    /// Standalone dev tool (not shipped with the plugin) for validating VatGlassesSectorLookup's
    /// geometry against real recorded VATSIM flights, pulled from vataware.net's free, no-auth
    /// flight history API. Prints the sequence of sector containment/approach-prediction
    /// transitions for a given flight so they can be cross-checked by eye against the live
    /// VATGlasses map (vatglasses.uk). See issue #9.
    ///
    /// Deliberately geometry-only: VATSIM's public data feed (and vataware's archive of it)
    /// carries no per-pilot tuned-COM-frequency history -- that's only ever broadcast live via
    /// the separate AFV transceivers feed, which nobody archives -- so there's no ground truth
    /// to check ownership-resolution/ranking against here, only "does the sector/altitude-band
    /// math pick the polygon a human would expect."
    /// </summary>
    internal static class Program
    {
        private const double HeadingApproachMaxNauticalMiles = 100;
        private const double RouteApproachMaxNauticalMiles = 150;

        private static async Task<int> Main(string[] args)
        {
            // VATGlasses sector names include non-ASCII characters (e.g. "Nördlingen") --
            // the default console code page mangles these on Windows unless explicitly set to UTF-8.
            Console.OutputEncoding = System.Text.Encoding.UTF8;

            if (args.Length < 1)
            {
                Console.WriteLine("Usage: Handoff.ReplayTool <vataware-flight-id> [--route]");
                Console.WriteLine("Flight IDs: browse https://vataware.net/airports/<ICAO> with an 'Accept: application/json' header.");
                return 1;
            }

            var flightId = args[0];
            var useRoute = args.Any(a => string.Equals(a, "--route", StringComparison.OrdinalIgnoreCase));

            using (var http = CreateHttpClient())
            {
                Console.WriteLine($"Fetching position history for flight {flightId}...");
                var positions = await FetchPositionsAsync(http, flightId);
                Console.WriteLine($"Loaded {positions.Count} position samples.");

                if (positions.Count == 0)
                {
                    Console.WriteLine("No positions returned -- check the flight ID.");
                    return 1;
                }

                var waypoints = new List<FlightPlanWaypoint>();
                if (useRoute)
                {
                    waypoints = await FetchRouteWaypointsAsync(http, flightId);
                    Console.WriteLine($"Loaded {waypoints.Count} route waypoints.");
                }

                Console.WriteLine("Loading VATGlasses data from local cache...");
                var vatGlasses = new VatGlassesDataModel(new OperationProgressModel(), Console.WriteLine);
                if (vatGlasses.Regions.Count == 0)
                {
                    Console.WriteLine("No cached VATGlasses data found locally -- syncing now (this can take a while on first run)...");
                    await vatGlasses.SyncAsync();
                }
                Console.WriteLine($"{vatGlasses.Regions.Count} region files loaded.");
                Console.WriteLine();

                Replay(positions, waypoints, vatGlasses.Regions);
            }

            return 0;
        }

        private static HttpClient CreateHttpClient()
        {
            var handler = new HttpClientHandler { AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate };
            var client = new HttpClient(handler);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("Handoff-ReplayTool/1.0 (dev tool, issue #9 sector-ranking replay testing)");
            client.DefaultRequestHeaders.Accept.ParseAdd("application/json");
            return client;
        }

        private static void Replay(
            IReadOnlyList<VatawarePosition> positions,
            IReadOnlyList<FlightPlanWaypoint> waypoints,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions)
        {
            string lastContainmentSummary = null;
            string lastApproachSummary = null;
            var previousContainingKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string pendingPredictionKey = null;
            string pendingPredictionLabel = null;
            var confirmedCount = 0;
            var missedCount = 0;
            DateTimeOffset? previousTimestamp = null;

            foreach (var p in positions.OrderBy(p => p.Timestamp))
            {
                var pressureAltitudeFl = p.Altitude / 100.0;

                var containing = VatGlassesSectorLookup.FindContainingSectors(regions, p.Latitude, p.Longitude, pressureAltitudeFl, null);
                var containingKeys = new HashSet<string>(containing.Select(MatchKey), StringComparer.OrdinalIgnoreCase);
                var summary = string.Join(", ", containing.Select(m => DescribeMatch(m, regions)));
                if (summary != lastContainmentSummary)
                {
                    // Self-consistency check (no external ground truth needed): did the sector
                    // most recently predicted as "approaching" actually become the next one
                    // ownship entered? Confirms the prediction geometry is sound independent of
                    // any online-controller/frequency data this tool doesn't have.
                    if (pendingPredictionKey != null)
                    {
                        var newlyEntered = containingKeys.Except(previousContainingKeys).ToList();
                        if (newlyEntered.Count > 0)
                        {
                            if (newlyEntered.Contains(pendingPredictionKey, StringComparer.OrdinalIgnoreCase))
                            {
                                Console.WriteLine($"    [OK] prediction confirmed: entered {pendingPredictionLabel} as predicted");
                                confirmedCount++;
                            }
                            else
                            {
                                var gapSeconds = previousTimestamp.HasValue ? (p.Timestamp - previousTimestamp.Value).TotalSeconds : (double?)null;
                                var gapNote = gapSeconds.HasValue ? $" (gap since previous sample: {gapSeconds:F0}s -- a wide gap likely means the predicted sector was briefly transited between samples, not a real miss)" : "";
                                Console.WriteLine($"    [MISS] predicted {pendingPredictionLabel}, but entered {string.Join(", ", newlyEntered)} instead{gapNote}");
                                missedCount++;
                            }
                            pendingPredictionKey = null;
                            pendingPredictionLabel = null;
                        }
                    }

                    Console.WriteLine($"[{p.Timestamp:HH:mm:ss}] alt={p.Altitude,-6:F0}ft lat={p.Latitude,9:F4} lon={p.Longitude,9:F4} hdg={p.Heading,3:F0}  IN: {(summary.Length == 0 ? "(none)" : summary)}");
                    lastContainmentSummary = summary;
                    previousContainingKeys = containingKeys;
                }

                IReadOnlyList<VatGlassesSectorLookup.VatGlassesApproachMatch> approaching;
                if (waypoints.Count > 0)
                {
                    var remaining = RemainingWaypoints(waypoints, p.Latitude, p.Longitude);
                    approaching = VatGlassesSectorLookup.FindApproachingSectorsAlongRoute(regions, p.Latitude, p.Longitude, remaining, RouteApproachMaxNauticalMiles);
                }
                else
                {
                    approaching = VatGlassesSectorLookup.FindApproachingSectorsAlongHeading(regions, p.Latitude, p.Longitude, p.Heading, HeadingApproachMaxNauticalMiles);
                }

                // Mirrors ControllerRankingModel.FindApproachingVatGlassesCallsigns: candidates
                // are sorted nearest-first, and only the single closest one not already contained
                // counts as "approaching" -- flying straight across a whole FIR shouldn't show
                // both the near and far sector at once. (This tool has no online-controller data
                // to filter by, unlike the real model, so it's "closest not-yet-entered sector,"
                // not "closest one someone's actually staffing.")
                var closest = approaching.FirstOrDefault(a => !containing.Any(c => ReferenceEquals(c.Level, a.Match.Level)));
                var approachSummary = closest != null ? $"{DescribeMatch(closest.Match, regions)} in {closest.DistanceNauticalMiles:F0}nm" : null;
                if (approachSummary != null && approachSummary != lastApproachSummary)
                {
                    Console.WriteLine($"    [{p.Timestamp:HH:mm:ss}] -> approaching {approachSummary}");
                }
                lastApproachSummary = approachSummary;

                if (closest != null)
                {
                    pendingPredictionKey = MatchKey(closest.Match);
                    pendingPredictionLabel = DescribeMatch(closest.Match, regions);
                }

                previousTimestamp = p.Timestamp;
            }

            Console.WriteLine();
            Console.WriteLine($"Prediction check: {confirmedCount} confirmed, {missedCount} missed (predictions never checked because the flight ended, or no prediction existed yet, don't count either way).");
        }

        private static string MatchKey(VatGlassesSectorLookup.VatGlassesSectorMatch match) =>
            $"{match.Sector.Id}@{match.RegionFileName}";

        /// <summary>
        /// Labels a sector match with its first owner chain entry's callsign/frequency, for
        /// readers more used to thinking in frequencies than VATGlasses sector names. Best-effort
        /// only -- this is always the *first* entry in the sector's Owner chain, not necessarily
        /// who'd actually be online at the historical moment being replayed (this tool has no
        /// online-controller data at all -- see the class doc comment), so it's "the sector's
        /// primary/nominal owner," not a claim about who was really working it that day.
        /// </summary>
        private static string DescribeMatch(VatGlassesSectorLookup.VatGlassesSectorMatch match, IReadOnlyDictionary<string, VatGlassesRegionData> regions)
        {
            var label = $"{match.Sector.Id}({match.Sector.Group})@{match.RegionFileName}";

            if (regions.TryGetValue(match.RegionFileName, out var region))
            {
                var ownerId = match.Sector.Owner.FirstOrDefault();
                if (ownerId != null && region.Positions.TryGetValue(ownerId, out var position))
                {
                    label += $" [{position.Callsign} {position.Frequency}]";
                }
            }

            return label;
        }

        private static List<FlightPlanWaypoint> RemainingWaypoints(IReadOnlyList<FlightPlanWaypoint> all, double lat, double lon)
        {
            var nearestIndex = 0;
            var nearestDistance = double.MaxValue;
            for (var i = 0; i < all.Count; i++)
            {
                var d = GeoDistance.NauticalMiles(lat, lon, all[i].Latitude, all[i].Longitude);
                if (d < nearestDistance)
                {
                    nearestDistance = d;
                    nearestIndex = i;
                }
            }
            return all.Skip(nearestIndex).ToList();
        }

        /// <summary>
        /// vataware.net's positions endpoint 301-redirects to archive.vataware.net (a CDN-backed
        /// static file) -- HttpClient follows the redirect automatically; the body is plain JSON
        /// wrapped as {"positions": [...]}, gzip-negotiated transparently via
        /// HttpClientHandler.AutomaticDecompression, not a literal .gz file to unwrap by hand.
        /// </summary>
        private static async Task<List<VatawarePosition>> FetchPositionsAsync(HttpClient http, string flightId)
        {
            var json = await http.GetStringAsync($"https://vataware.net/flights/{flightId}/positions");
            var array = (JArray)JObject.Parse(json)["positions"];
            return array.Select(t => new VatawarePosition(
                (DateTimeOffset)t["timestamp"],
                (double)t["altitude"],
                t["elevation"] != null ? (double)t["elevation"] : 0,
                t["speed"] != null ? (double)t["speed"] : 0,
                (double)t["latitude"],
                (double)t["longitude"],
                (double)t["heading"])).ToList();
        }

        /// <summary>
        /// Best-effort only -- vataware's flightplans schema isn't confirmed, and waypoint
        /// lat/lon resolution from a route string isn't implemented here yet (needs navdata,
        /// out of scope for this tool). Degrades to an empty list (heading-based fallback)
        /// rather than failing the run.
        /// </summary>
        private static async Task<List<FlightPlanWaypoint>> FetchRouteWaypointsAsync(HttpClient http, string flightId)
        {
            try
            {
                var json = await http.GetStringAsync($"https://vataware.net/flights/{flightId}/flightplans");
                var plans = (JArray)JObject.Parse(json)["flightplans"];
                var latest = plans?.LastOrDefault();
                var routeText = (string)latest?["route"];
                Console.WriteLine(routeText != null
                    ? $"(Route text: {routeText} -- waypoint lat/lon resolution not implemented yet, falling back to heading-based prediction.)"
                    : "(No parseable route found -- falling back to heading-based prediction.)");
                return new List<FlightPlanWaypoint>();
            }
            catch (Exception ex)
            {
                Console.WriteLine("Failed to fetch/parse route (falling back to heading-based prediction): " + ex.Message);
                return new List<FlightPlanWaypoint>();
            }
        }
    }

    internal sealed class VatawarePosition
    {
        public DateTimeOffset Timestamp { get; }
        public double Altitude { get; }
        public double Elevation { get; }
        public double Speed { get; }
        public double Latitude { get; }
        public double Longitude { get; }
        public double Heading { get; }

        public VatawarePosition(DateTimeOffset timestamp, double altitude, double elevation, double speed, double latitude, double longitude, double heading)
        {
            Timestamp = timestamp;
            Altitude = altitude;
            Elevation = elevation;
            Speed = speed;
            Latitude = latitude;
            Longitude = longitude;
            Heading = heading;
        }
    }
}
