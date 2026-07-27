using System;
using System.Collections.Generic;
using System.IO;
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
    /// flight history API. Two modes:
    ///
    ///   - Single flight: `Handoff.ReplayTool &lt;vataware-flight-id&gt; [--route]` -- prints the
    ///     sequence of sector containment/approach-prediction transitions to the console.
    ///   - Batch: `Handoff.ReplayTool --random-test &lt;count&gt; [--seed N] [--out dir]` -- picks
    ///     up to &lt;count&gt; random European airports, one recent flight from each, replays all
    ///     of them, and writes a summary.txt plus one detail file per flight for review.
    ///
    /// Both self-check each approach-prediction against what ownship actually flew into next
    /// (see Replay/ReplayResult) -- no external ground truth needed for that part. Deliberately
    /// geometry-only otherwise: VATSIM's public data feed (and vataware's archive of it) carries
    /// no per-pilot tuned-COM-frequency history -- that's only ever broadcast live via the
    /// separate AFV transceivers feed, which nobody archives -- so there's no ground truth to
    /// check ownership-resolution/ranking against here. See issue #9.
    /// </summary>
    internal static class Program
    {
        private const double HeadingApproachMaxNauticalMiles = 100;
        private const double RouteApproachMaxNauticalMiles = 150;

        /// <summary>
        /// A miss right after a gap at/above this is treated as inconclusive (excluded from the
        /// confirmed/missed tally) rather than a real prediction failure -- vataware's position
        /// samples run roughly 30s apart even during dense phases of flight, so a wide gap means
        /// the predicted sector could easily have been entered and exited between two samples,
        /// never observed at all. A rough heuristic, not a scientifically derived cutoff -- just
        /// comfortably above the typical sample cadence seen in real data so far.
        /// </summary>
        private const double GapExclusionThresholdSeconds = 45;

        /// <summary>Deliberate delay between vataware.net requests in batch mode, respecting their "use reasonably" rate-limit policy (see plugin/README.md).</summary>
        private const int BatchRequestDelayMs = 500;

        private static async Task<int> Main(string[] args)
        {
            // VATGlasses sector names include non-ASCII characters (e.g. "Nördlingen") --
            // the default console code page mangles these on Windows unless explicitly set to UTF-8.
            Console.OutputEncoding = System.Text.Encoding.UTF8;

            if (args.Length < 1)
            {
                PrintUsage();
                return 1;
            }

            using (var http = CreateHttpClient())
            {
                if (string.Equals(args[0], "--random-test", StringComparison.OrdinalIgnoreCase))
                {
                    if (args.Length < 2 || !int.TryParse(args[1], out var count) || count <= 0)
                    {
                        Console.WriteLine("Usage: Handoff.ReplayTool --random-test <count> [--seed <n>] [--out <dir>]");
                        return 1;
                    }

                    var seed = TryGetOptionValue(args, "--seed", out var seedText) && int.TryParse(seedText, out var parsedSeed)
                        ? parsedSeed
                        : Environment.TickCount;
                    var outDir = TryGetOptionValue(args, "--out", out var outDirValue)
                        ? outDirValue
                        : Path.Combine("replay-results", DateTime.Now.ToString("yyyyMMdd-HHmmss"));

                    await RunRandomTestAsync(http, count, seed, outDir);
                    return 0;
                }

                var flightId = args[0];
                var useRoute = args.Any(a => string.Equals(a, "--route", StringComparison.OrdinalIgnoreCase));

                Console.WriteLine($"Fetching position history for flight {flightId}...");
                var json = await http.GetStringAsync($"https://vataware.net/flights/{flightId}/positions");
                var positions = ParsePositionsJson(json);
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

                var result = Replay(positions, waypoints, vatGlasses.Regions, Console.Out);
                Console.WriteLine();
                Console.WriteLine(result.Describe());
            }

            return 0;
        }

        private static void PrintUsage()
        {
            Console.WriteLine("Usage:");
            Console.WriteLine("  Handoff.ReplayTool <vataware-flight-id> [--route]");
            Console.WriteLine("  Handoff.ReplayTool --random-test <count> [--seed <n>] [--out <dir>]");
            Console.WriteLine();
            Console.WriteLine("Flight IDs: browse https://vataware.net/airports/<ICAO> with an 'Accept: application/json' header.");
        }

        private static bool TryGetOptionValue(string[] args, string option, out string value)
        {
            for (var i = 0; i < args.Length - 1; i++)
            {
                if (string.Equals(args[i], option, StringComparison.OrdinalIgnoreCase))
                {
                    value = args[i + 1];
                    return true;
                }
            }
            value = null;
            return false;
        }

        private static HttpClient CreateHttpClient()
        {
            var handler = new HttpClientHandler { AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate };
            var client = new HttpClient(handler);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("Handoff-ReplayTool/1.0 (dev tool, issue #9 sector-ranking replay testing)");
            client.DefaultRequestHeaders.Accept.ParseAdd("application/json");
            return client;
        }

        /// <summary>Tallies from one flight's Replay() run -- see GapExclusionThresholdSeconds for why Excluded exists separately from Missed.</summary>
        private sealed class ReplayResult
        {
            public int Confirmed;
            public int Missed;
            public int Excluded;

            public int Conclusive => Confirmed + Missed;
            public double? ConfirmedRate => Conclusive > 0 ? (double?)(100.0 * Confirmed / Conclusive) : null;

            public string Describe() =>
                $"Prediction check: {Confirmed}/{Conclusive} confirmed" +
                (ConfirmedRate.HasValue ? $" ({ConfirmedRate.Value:F0}%)" : "") +
                $", {Excluded} excluded (inconclusive -- sample gap >= {GapExclusionThresholdSeconds:F0}s).";
        }

        private static ReplayResult Replay(
            IReadOnlyList<VatawarePosition> positions,
            IReadOnlyList<FlightPlanWaypoint> waypoints,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            TextWriter output)
        {
            var result = new ReplayResult();
            string lastContainmentSummary = null;
            string lastApproachSummary = null;
            var previousContainingKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string pendingPredictionKey = null;
            string pendingPredictionLabel = null;
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
                    // any online-controller/frequency data this tool doesn't have. A miss right
                    // after a wide sample gap is inconclusive, not a real failure -- see
                    // GapExclusionThresholdSeconds.
                    if (pendingPredictionKey != null)
                    {
                        var newlyEntered = containingKeys.Except(previousContainingKeys).ToList();
                        if (newlyEntered.Count > 0)
                        {
                            var gapSeconds = previousTimestamp.HasValue ? (p.Timestamp - previousTimestamp.Value).TotalSeconds : (double?)null;

                            if (newlyEntered.Contains(pendingPredictionKey, StringComparer.OrdinalIgnoreCase))
                            {
                                output.WriteLine($"    [OK] prediction confirmed: entered {pendingPredictionLabel} as predicted");
                                result.Confirmed++;
                            }
                            else if (gapSeconds.HasValue && gapSeconds.Value >= GapExclusionThresholdSeconds)
                            {
                                output.WriteLine($"    [SKIP] predicted {pendingPredictionLabel}, entered {string.Join(", ", newlyEntered)} instead -- excluded, {gapSeconds:F0}s gap since previous sample likely swallowed an intermediate sector");
                                result.Excluded++;
                            }
                            else
                            {
                                var gapNote = gapSeconds.HasValue ? $" ({gapSeconds:F0}s since previous sample)" : "";
                                output.WriteLine($"    [MISS] predicted {pendingPredictionLabel}, but entered {string.Join(", ", newlyEntered)} instead{gapNote}");
                                result.Missed++;
                            }

                            pendingPredictionKey = null;
                            pendingPredictionLabel = null;
                        }
                    }

                    output.WriteLine($"[{p.Timestamp:HH:mm:ss}] alt={p.Altitude,-6:F0}ft lat={p.Latitude,9:F4} lon={p.Longitude,9:F4} hdg={p.Heading,3:F0}  IN: {(summary.Length == 0 ? "(none)" : summary)}");
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
                    output.WriteLine($"    [{p.Timestamp:HH:mm:ss}] -> approaching {approachSummary}");
                }
                lastApproachSummary = approachSummary;

                if (closest != null)
                {
                    pendingPredictionKey = MatchKey(closest.Match);
                    pendingPredictionLabel = DescribeMatch(closest.Match, regions);
                }

                previousTimestamp = p.Timestamp;
            }

            return result;
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
        /// Individual position records have been observed with a null field (e.g. heading while
        /// stationary) -- one bad record shouldn't cost the whole flight's replay, so anything
        /// missing a value this tool actually needs (timestamp/altitude/lat/lon/heading) is
        /// skipped rather than thrown on; elevation/speed default to 0 since they're much less
        /// critical (elevation is informational, speed isn't used by the geometry at all).
        /// </summary>
        private static List<VatawarePosition> ParsePositionsJson(string json)
        {
            var array = (JArray)JObject.Parse(json)["positions"];
            if (array == null) return new List<VatawarePosition>();

            var positions = new List<VatawarePosition>();
            foreach (var t in array)
            {
                if (t["timestamp"] == null || t["timestamp"].Type == JTokenType.Null) continue;
                if (t["altitude"] == null || t["altitude"].Type == JTokenType.Null) continue;
                if (t["latitude"] == null || t["latitude"].Type == JTokenType.Null) continue;
                if (t["longitude"] == null || t["longitude"].Type == JTokenType.Null) continue;
                if (t["heading"] == null || t["heading"].Type == JTokenType.Null) continue;

                positions.Add(new VatawarePosition(
                    (DateTimeOffset)t["timestamp"],
                    (double)t["altitude"],
                    t["elevation"] != null && t["elevation"].Type != JTokenType.Null ? (double)t["elevation"] : 0,
                    t["speed"] != null && t["speed"].Type != JTokenType.Null ? (double)t["speed"] : 0,
                    (double)t["latitude"],
                    (double)t["longitude"],
                    (double)t["heading"]));
            }

            return positions;
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

        // A spread of European ICAO codes across busy and quieter fields, for --random-test's
        // airport pool -- not exhaustive, just diverse enough geographically (and across
        // VATGlasses' actual coverage: strong in Europe per issue #9) to give a meaningful random
        // sample. Extend freely if a region needs better representation.
        private static readonly string[] EuropeanAirports =
        {
            // UK & Ireland
            "EGLL", "EGKK", "EGCC", "EGGD", "EGPH", "EGPF", "EGBB", "EGNX", "EGSS", "EGGW", "EGAA", "EIDW", "EICK",
            // France
            "LFPG", "LFPO", "LFPB", "LFMN", "LFBO", "LFRS", "LFML", "LFLL", "LFLS", "LFST", "LFBD", "LFRB",
            // Germany
            "EDDF", "EDDM", "EDDB", "EDDL", "EDDH", "EDDK", "EDDS", "EDDN", "EDDW", "EDDP",
            // Benelux
            "EBBR", "EBAW", "EHAM", "EHRD", "EHGG", "ELLX",
            // Scandinavia
            "EKCH", "EKBI", "ENGM", "ENBR", "ENZV", "ESSA", "ESGG", "ESMS", "EFHK", "EFTU", "BIKF",
            // Iberia
            "LPPT", "LPPR", "LEMD", "LEBL", "LEZL", "LEVC", "LEMG", "LEPA", "GCLP", "GCTS",
            // Italy & Switzerland & Austria
            "LIRF", "LIMC", "LIML", "LIME", "LICC", "LIRN", "LIPZ", "LSZH", "LSGG", "LSZB", "LOWW", "LOWS", "LOWI", "LOWL", "LOWG",
            // Greece, Cyprus, Malta
            "LGAV", "LGTS", "LCLK", "LCPH", "LMML",
            // Central/Eastern Europe
            "LKPR", "LZIB", "LHBP", "EPWA", "EPKK", "EPGD", "LJLJ", "LDZA", "LDSP", "LROP", "LRCL",
            // Baltics & Balkans
            "EYVI", "EYKA", "EVRA", "EETN", "LBSF", "LYBE",
        };

        // A confirmed real AIRAC effective date (Australian Airservices' published 2026
        // schedule) -- AIRAC cycles are a fixed, globally-synchronized 28-day cadence published
        // years in advance, so any one confirmed date anchors the whole schedule going forward
        // (and backward) via simple modular arithmetic. Used to keep --random-test's flight
        // pool within the *current* cycle, so the real-world airspace structure a replayed
        // flight flew through is reasonably likely to still match today's cached VATGlasses
        // data (which isn't itself formally AIRAC-versioned, but real sector/boundary changes
        // track real-world AIRAC updates regardless).
        private static readonly DateTime AiracAnchorDate = new DateTime(2026, 7, 9);
        private const int AiracCycleDays = 28;

        private static (DateTime Start, DateTime End) CurrentAiracCycle(DateTime asOfUtc)
        {
            var daysSinceAnchor = (asOfUtc.Date - AiracAnchorDate).TotalDays;
            var cyclesSinceAnchor = Math.Floor(daysSinceAnchor / AiracCycleDays);
            var start = AiracAnchorDate.AddDays(cyclesSinceAnchor * AiracCycleDays);
            return (start, start.AddDays(AiracCycleDays));
        }

        /// <summary>
        /// Picks up to count random European airports (via EuropeanAirports, shuffled with a
        /// seeded Random for reproducibility), one recent arrival/departure from each -- only
        /// considering flights that departed within the current AIRAC cycle (see
        /// CurrentAiracCycle) -- until count distinct flights are found or the airport pool is
        /// exhausted. Uses vataware's direct positions_url/flightplans_url from the airport
        /// listing rather than re-deriving them from a flight ID -- one fewer request/redirect
        /// per flight.
        /// </summary>
        private static async Task<List<RandomTestFlight>> DiscoverRandomFlightsAsync(HttpClient http, int count, int seed)
        {
            var (cycleStart, cycleEnd) = CurrentAiracCycle(DateTime.UtcNow);
            Console.WriteLine($"Restricting to flights departed within the current AIRAC cycle: {cycleStart:yyyy-MM-dd} to {cycleEnd:yyyy-MM-dd}.");

            var rng = new Random(seed);
            var shuffledAirports = EuropeanAirports.OrderBy(_ => rng.Next()).ToList();
            var picked = new List<RandomTestFlight>();
            var seenFlightIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            foreach (var icao in shuffledAirports)
            {
                if (picked.Count >= count) break;

                await Task.Delay(BatchRequestDelayMs);
                try
                {
                    var json = await http.GetStringAsync($"https://vataware.net/airports/{icao}");
                    var root = JObject.Parse(json);
                    var candidates = new List<JToken>();
                    if (root["recent_arrivals"] is JArray arrivals) candidates.AddRange(arrivals);
                    if (root["recent_departures"] is JArray departures) candidates.AddRange(departures);

                    // Two independent, observed vataware quirks mean neither list nor the
                    // "state" field can be trusted alone: "recent_arrivals" has been seen frozen
                    // on the exact same ~9-month-old date across many different airports (a
                    // site-wide staleness bug, not randomness), while "recent_departures" is
                    // reliably current but mostly still-airborne (state=3, arrival_time is just
                    // an ETA). So both lists are pooled together and filtered directly on the
                    // actual timestamps instead: in the current AIRAC cycle (departure_time) AND
                    // actually landed by now (arrival_time in the past) -- self-correcting
                    // against whichever list/state quirk is in play, from either source.
                    // Cast straight to DateTimeOffset rather than via (string): Newtonsoft
                    // auto-detects ISO date-like JSON strings as Date tokens during JObject.Parse,
                    // so a (string) cast round-trips through DateTime.ToString() in the current
                    // culture (losing the UTC offset) instead of returning the original text.
                    var nowUtc = DateTime.UtcNow;
                    var eligible = candidates
                        .Where(c => c["departure_time"] != null && c["departure_time"].Type == JTokenType.Date
                                 && c["arrival_time"] != null && c["arrival_time"].Type == JTokenType.Date)
                        .Where(c =>
                        {
                            var dep = ((DateTimeOffset)c["departure_time"]).UtcDateTime;
                            var arr = ((DateTimeOffset)c["arrival_time"]).UtcDateTime;
                            return dep >= cycleStart && dep < cycleEnd && arr <= nowUtc;
                        })
                        .ToList();
                    if (eligible.Count == 0)
                    {
                        Console.WriteLine($"  [{icao}] no completed flights within the current AIRAC cycle, skipping.");
                        continue;
                    }

                    // Shuffle this airport's own candidates too, then take the first not already
                    // picked via some other airport's listing (a flight can appear at both its
                    // departure and arrival airport).
                    var shuffledCandidates = eligible.OrderBy(_ => rng.Next()).ToList();
                    var chosen = shuffledCandidates.FirstOrDefault(c => (string)c["id"] != null && seenFlightIds.Add((string)c["id"]));
                    if (chosen == null)
                    {
                        Console.WriteLine($"  [{icao}] all eligible candidate flights already picked elsewhere, skipping.");
                        continue;
                    }

                    picked.Add(new RandomTestFlight(
                        icao,
                        (string)chosen["id"],
                        (string)chosen["callsign"],
                        (string)chosen["positions_url"],
                        (string)chosen["flightplans_url"]));
                    Console.WriteLine($"  [{icao}] picked {(string)chosen["callsign"]} ({(string)chosen["id"]}).");
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"  [{icao}] failed to fetch airport listing: {ex.Message}");
                }
            }

            return picked;
        }

        private static async Task RunRandomTestAsync(HttpClient http, int count, int seed, string outDir)
        {
            Directory.CreateDirectory(outDir);
            Console.WriteLine($"Random test: up to {count} flights across random European airports (seed={seed})...");
            Console.WriteLine();

            var flights = await DiscoverRandomFlightsAsync(http, count, seed);
            Console.WriteLine();
            Console.WriteLine($"Found {flights.Count} candidate flights.");

            Console.WriteLine("Loading VATGlasses data from local cache...");
            var vatGlasses = new VatGlassesDataModel(new OperationProgressModel(), null);
            if (vatGlasses.Regions.Count == 0)
            {
                Console.WriteLine("No cached VATGlasses data found locally -- syncing now (this can take a while on first run)...");
                await vatGlasses.SyncAsync();
            }
            Console.WriteLine($"{vatGlasses.Regions.Count} region files loaded.");
            Console.WriteLine();

            var summaryLines = new List<string> { $"Random test run -- seed={seed}, {flights.Count} flights", "" };
            var overall = new ReplayResult();

            foreach (var flight in flights)
            {
                Console.WriteLine($"Replaying {flight.Callsign} ({flight.FlightId}, via {flight.Icao})...");
                try
                {
                    await Task.Delay(BatchRequestDelayMs);
                    var positionsJson = await http.GetStringAsync(flight.PositionsUrl);
                    var positions = ParsePositionsJson(positionsJson);

                    if (positions.Count == 0)
                    {
                        summaryLines.Add($"{flight.FlightId}\t{flight.Callsign}\tSKIPPED (no positions)");
                        continue;
                    }

                    var detailPath = Path.Combine(outDir, $"{flight.FlightId}.txt");
                    using (var writer = new StreamWriter(detailPath, false, System.Text.Encoding.UTF8))
                    {
                        writer.WriteLine($"Flight {flight.Callsign} ({flight.FlightId}), discovered via {flight.Icao}");
                        writer.WriteLine($"{positions.Count} position samples.");
                        writer.WriteLine();

                        // Batch mode is heading-only (no --route) -- waypoint lat/lon resolution
                        // from vataware's raw route string isn't implemented (see
                        // FetchRouteWaypointsAsync), so it wouldn't add anything here either.
                        var result = Replay(positions, new List<FlightPlanWaypoint>(), vatGlasses.Regions, writer);
                        writer.WriteLine();
                        writer.WriteLine(result.Describe());

                        overall.Confirmed += result.Confirmed;
                        overall.Missed += result.Missed;
                        overall.Excluded += result.Excluded;

                        summaryLines.Add($"{flight.FlightId}\t{flight.Callsign}\t{result.Confirmed}/{result.Conclusive}" +
                            (result.ConfirmedRate.HasValue ? $" ({result.ConfirmedRate.Value:F0}%)" : "") +
                            $"\texcluded={result.Excluded}");
                    }
                }
                catch (Exception ex)
                {
                    summaryLines.Add($"{flight.FlightId}\t{flight.Callsign}\tERROR: {ex.Message}");
                    Console.WriteLine($"  ERROR: {ex.Message}");
                }
            }

            summaryLines.Add("");
            summaryLines.Add($"TOTAL: {overall.Describe()}");

            var summaryPath = Path.Combine(outDir, "summary.txt");
            File.WriteAllLines(summaryPath, summaryLines, System.Text.Encoding.UTF8);

            Console.WriteLine();
            Console.WriteLine(overall.Describe());
            Console.WriteLine($"Summary written to {summaryPath}");
            Console.WriteLine($"Per-flight detail files in {outDir}");
        }

        private sealed class RandomTestFlight
        {
            public string Icao { get; }
            public string FlightId { get; }
            public string Callsign { get; }
            public string PositionsUrl { get; }
            public string FlightPlansUrl { get; }

            public RandomTestFlight(string icao, string flightId, string callsign, string positionsUrl, string flightPlansUrl)
            {
                Icao = icao;
                FlightId = flightId;
                Callsign = callsign;
                PositionsUrl = positionsUrl;
                FlightPlansUrl = flightPlansUrl;
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
