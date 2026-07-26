using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Re-ranks the full controller list ControllerStateModel already reports -- nothing is ever
    /// hidden, every station stays visible, just reordered with boolean flags the Android app
    /// uses for colour-coding. See issue #8 for the full design; this class implements its
    /// tiebreak stack:
    ///
    ///   1. Currently-tuned controller (or a manually pinned one) -- rank 0, IsCurrent.
    ///   2. Any controller with an outstanding "contact me" request -- ranked next, any tier.
    ///   3. Any controller with a currently-active SELCAL alert (SelcalActiveModel) -- ranked
    ///      immediately below contact-me, any tier. Unlike contact-me, tuning the alerting
    ///      frequency does NOT clear this -- real SELCAL requires the pilot to already be tuned
    ///      to that frequency (volume down, e.g. on an oceanic crossing) for the controller's
    ///      pulse to reach the aircraft at all, so tune-match is trivially always true here and
    ///      carries no "have they seen it" signal. Only an explicit dismissSelcal client command
    ///      (docs/protocol.md) or the alert's own expiry clears it.
    ///   4. Whichever controller(s) are flagged IsLikelyNextCandidate (see step 5) -- ranked
    ///      next, ahead of every other remaining controller regardless of tier. A station that's
    ///      merely tier-closer-but-unrelated (some other airport's tier sitting earlier in the
    ///      chain) must not outrank the one actually flagged as next for this flight.
    ///   5. Chain tier (DEL -&gt; GND -&gt; TWR -&gt; APP/DEP -&gt; CTR), relative to the current tier --
    ///      walking upward from the current tier, the first tier with an actually-relevant
    ///      controller gets IsLikelyNextCandidate on just that controller (or all of them, if
    ///      several route-match). "Relevant" means route-matching the flight-plan airport where
    ///      one's loaded; for CTR, or when no flight plan is loaded at all, the single distance
    ///      leader counts as relevant instead. A tier whose only online members don't route-match
    ///      (some other airport's DEL/GND/TWR/APP/DEP, e.g. while nothing's tuned and the search
    ///      starts from the bottom of the chain) is skipped entirely rather than shadowing a real
    ///      match further up the chain -- the search isn't "the lowest tier present anywhere on
    ///      the network," it's "the lowest tier with something to do with this flight."
    ///   6. Within a tier: route match (callsign ICAO prefix vs flight-plan origin/destination).
    ///   7. Within a tier, no route match: distance to ownship, closest first. ATIS (tier Other,
    ///      via ControllerTier.ParseControllerTier) never route-matches or gets a next-candidate
    ///      fallback, so it always sorts last of all -- correct, since it's not a station anyone
    ///      needs to "contact next."
    ///
    /// Distance-based ordering (step 7) is the one prone to sensor-noise flapping (a momentary
    /// taxiway stop, pattern work), so a challenger only displaces the tier's committed leader
    /// once it's been strictly closer for the full hysteresis window -- see
    /// ApplyDistanceHysteresis. Tier bucketing and route-match are deterministic and not
    /// hysteresis-gated.
    /// </summary>
    public sealed class ControllerRankingModel
    {
        private static readonly TimeSpan HysteresisWindow = TimeSpan.FromSeconds(12);

        // "Approaching" distance/heading thresholds -- see IsApproaching. Only meaningful when
        // nothing is currently tuned/pinned (e.g. flying uncontrolled and about to enter a
        // station's range). DEL isn't covered (already well-served by route match); CTR isn't
        // covered either (a single lat/lon can't represent a FIR's real shape -- needs actual
        // sector geometry, deferred to issue #11).
        // Minimum AGL required alongside OnGround==false before latching _hasTakenOffThisSession
        // -- well above squat-switch flicker (a few feet at most from a ramp bump) but well
        // below a real rotation, which clears the wheels by tens of feet within seconds.
        private const double TakeoffAglThresholdFeet = 50;

        private const double GroundApproachingNauticalMiles = 10;
        private const double TowerApproachingNauticalMiles = 20;
        private const double AppOmnidirectionalNauticalMiles = 40;
        private const double AppOuterNauticalMiles = 50;
        private const double AppHeadingToleranceDegrees = 45;

        private readonly object _gate = new object();
        private readonly ControllerStateModel _controllerState;
        private readonly IRadioStateModel _radioState;
        private readonly FlightPlanModel _flightPlanState;
        private readonly VatsimDataFeedModel _vatsimFeed;
        private readonly ContactMeModel _contactMe;
        private readonly SelcalActiveModel _selcalActive;
        private readonly PilotSessionModel _pilotSession;
        private readonly Action<string> _logDebug;
        private readonly Func<DateTimeOffset> _now;

        private readonly Dictionary<ControllerTier, string> _committedLeader = new Dictionary<ControllerTier, string>();
        private readonly Dictionary<ControllerTier, string> _pendingChallenger = new Dictionary<ControllerTier, string>();
        private readonly Dictionary<ControllerTier, DateTimeOffset> _pendingSince = new Dictionary<ControllerTier, DateTimeOffset>();

        private IReadOnlyList<RankedController> _current = new List<RankedController>();
        private string _pinnedCallsign;
        private bool _hasTakenOffThisSession;

        public event EventHandler Changed;

        public ControllerRankingModel(ControllerStateModel controllerState, IRadioStateModel radioState, FlightPlanModel flightPlanState, VatsimDataFeedModel vatsimFeed, ContactMeModel contactMe, SelcalActiveModel selcalActive, PilotSessionModel pilotSession, Action<string> logDebug = null, Func<DateTimeOffset> now = null)
        {
            _controllerState = controllerState ?? throw new ArgumentNullException(nameof(controllerState));
            _radioState = radioState ?? throw new ArgumentNullException(nameof(radioState));
            _flightPlanState = flightPlanState ?? throw new ArgumentNullException(nameof(flightPlanState));
            _vatsimFeed = vatsimFeed ?? throw new ArgumentNullException(nameof(vatsimFeed));
            _contactMe = contactMe ?? throw new ArgumentNullException(nameof(contactMe));
            _selcalActive = selcalActive ?? throw new ArgumentNullException(nameof(selcalActive));
            _pilotSession = pilotSession ?? throw new ArgumentNullException(nameof(pilotSession));
            _logDebug = logDebug;
            _now = now ?? (() => DateTimeOffset.Now);

            _controllerState.Changed += (s, e) => Recompute();
            _radioState.Changed += (s, e) => Recompute();
            _flightPlanState.Changed += (s, e) => Recompute();
            _vatsimFeed.Changed += (s, e) => Recompute();
            _contactMe.Changed += (s, e) => Recompute();
            _selcalActive.Changed += (s, e) => Recompute();
            _pilotSession.Changed += (s, e) => Recompute();

            Recompute();
        }

        public IReadOnlyList<RankedController> Current
        {
            get { lock (_gate) { return _current; } }
        }

        /// <summary>Forces the given callsign to rank 0 / IsCurrent, regardless of tuned frequency, until cleared or the controller goes offline.</summary>
        public void SetPinnedController(string callsign)
        {
            lock (_gate) { _pinnedCallsign = callsign; }
            Recompute();
        }

        public void ClearPinnedController()
        {
            lock (_gate) { _pinnedCallsign = null; }
            Recompute();
        }

        private void Recompute()
        {
            var controllers = _controllerState.Controllers;
            var radio = _radioState.Current;
            var telemetry = _radioState.Telemetry;
            var flightPlan = _flightPlanState.Current;
            var enrichment = _vatsimFeed.Controllers;
            var contactMeCallsigns = new HashSet<string>(_contactMe.ActiveCallsigns, StringComparer.OrdinalIgnoreCase);
            var selcalCallsigns = new HashSet<string>(_selcalActive.ActiveCallsigns, StringComparer.OrdinalIgnoreCase);

            // Gated on AGL, not just the OnGround boolean alone -- SimConnect's squat-switch var
            // is known to flicker false for a single sample while still parked (load-in
            // settling, a ramp bump, jetway/pushback jostle), and this latch is one-way with no
            // way back for the rest of the session, so a single bad sample would permanently
            // mislabel the whole flight as post-departure and silently break route matching
            // (e.g. the origin's TWR no longer route-matching once the destination is assumed).
            if (telemetry.OnGround == false && telemetry.AltitudeAboveGroundFeet.GetValueOrDefault() > TakeoffAglThresholdFeet)
            {
                _hasTakenOffThisSession = true;
            }

            string pinned;
            lock (_gate) { pinned = _pinnedCallsign; }
            if (pinned != null && !controllers.Any(c => string.Equals(c.Callsign, pinned, StringComparison.OrdinalIgnoreCase)))
            {
                lock (_gate) { _pinnedCallsign = null; }
                Log("Pinned controller " + pinned + " went offline, clearing pin.");
                pinned = null;
            }

            var tunedFrequencies = new HashSet<int>();
            if (radio.Com1Frequency.HasValue) tunedFrequencies.Add(radio.Com1Frequency.Value);
            if (radio.Com2Frequency.HasValue) tunedFrequencies.Add(radio.Com2Frequency.Value);

            var currentCallsign = pinned ?? controllers.FirstOrDefault(c => tunedFrequencies.Contains(c.Frequency))?.Callsign;
            if (currentCallsign != null) _contactMe.Clear(currentCallsign);
            var currentTier = currentCallsign != null ? currentCallsign.ParseControllerTier() : (ControllerTier?)null;

            // Prefers the actually-filed VATSIM plan (own callsign from PilotSessionModel,
            // cross-referenced against the public data feed's pilots[]) over the SimBrief-derived
            // one -- it's the more authoritative source once it exists. Falls back to SimBrief
            // when it doesn't: pre-connection (ranking needs a route before the pilot has even
            // filed, e.g. sitting at the gate deciding which DEL/GND to expect), the feed hasn't
            // polled it in yet (~15s lag), or the data feed is unreachable.
            VatsimPilotInfo vatsimPilot = null;
            var vatsimCallsign = _pilotSession.Callsign;
            if (vatsimCallsign != null) _vatsimFeed.Pilots.TryGetValue(vatsimCallsign, out vatsimPilot);
            var origin = vatsimPilot?.Departure ?? flightPlan.Origin;
            var destination = vatsimPilot?.Arrival ?? flightPlan.Destination;
            var routeAirport = _hasTakenOffThisSession ? destination : origin;

            var remaining = controllers.Where(c => !string.Equals(c.Callsign, currentCallsign, StringComparison.OrdinalIgnoreCase)).ToList();
            var orderedRemaining = new List<Controller>();
            var orderedByTier = new Dictionary<ControllerTier, List<Controller>>();
            foreach (var tierGroup in remaining.GroupBy(c => c.Callsign.ParseControllerTier()).OrderBy(g => ChainDistance(g.Key, currentTier)))
            {
                var orderedTier = OrderTierByRouteThenDistance(tierGroup.Key, tierGroup.ToList(), routeAirport, telemetry);
                orderedRemaining.AddRange(orderedTier);
                orderedByTier[tierGroup.Key] = orderedTier;
            }

            // "Next tier" isn't just the lowest tier present anywhere on the network -- with
            // nothing tuned that's nearly always Delivery somewhere in the world, which would
            // permanently shadow a real, relevant Tower/Ground at the current airport. Instead
            // walk the chain upward from the current tier and stop at the first tier that has an
            // actually-relevant controller (route-matched, or the CTR/no-flight-plan proximity
            // fallback) -- skipping tiers whose only online members belong to some other airport
            // entirely.
            var nextCandidateCallsigns = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var startRank = currentTier.HasValue ? (int)currentTier.Value : -1;
            foreach (var tier in orderedByTier.Keys.Where(t => t != ControllerTier.Other && (int)t > startRank).OrderBy(t => (int)t))
            {
                var orderedTier = orderedByTier[tier];
                var routeMatched = RouteMatched(orderedTier, routeAirport);
                // Below CTR, callsign ICAO prefix reliably identifies "your" airport (matches
                // the flight plan), so once a flight plan exists, a tier with no route match
                // means every station in it genuinely belongs to a different airport -- skip past
                // it rather than either flagging it or stopping the search there. CTR is always
                // the exception: FIR callsigns (e.g. "VIE_CTR") routinely don't share an
                // airport's ICAO prefix at all, so proximity is the only usable signal there
                // regardless. Without a flight plan at all there's no route data to trust either
                // way, so every tier falls back to proximity same as CTR.
                List<Controller> nextCandidates;
                if (routeMatched.Count > 0) nextCandidates = routeMatched;
                else if (tier == ControllerTier.Center || string.IsNullOrEmpty(routeAirport)) nextCandidates = new List<Controller> { orderedTier[0] };
                else continue;

                foreach (var c in nextCandidates) nextCandidateCallsigns.Add(c.Callsign);
                break;
            }

            var contactMeOrdered = orderedRemaining
                .Where(c => contactMeCallsigns.Contains(c.Callsign))
                .OrderBy(c => c.Callsign.ParseControllerTier())
                .ThenBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase)
                .ToList();
            // Ranked immediately below contact-me, same reasoning and same "any tier" scope --
            // an active SELCAL alert is a controller-initiated attention request just like
            // contact-me, only delivered as a dedicated alert instead of a private message.
            var selcalOrdered = orderedRemaining
                .Where(c => !contactMeCallsigns.Contains(c.Callsign) && selcalCallsigns.Contains(c.Callsign))
                .OrderBy(c => c.Callsign.ParseControllerTier())
                .ThenBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase)
                .ToList();
            // Pulled ahead of everything else remaining, same as contact-me/SELCAL -- ChainDistance
            // alone would rank a merely tier-closer-but-unrelated station (e.g. some other
            // airport's GND, one tier nearer the current one) above the actual flagged next
            // candidate, which defeats the point of the flag: whatever's genuinely next for this
            // flight must outrank anything that just happens to sit in an earlier chain tier.
            var excludedFromRest = new HashSet<string>(contactMeCallsigns, StringComparer.OrdinalIgnoreCase);
            excludedFromRest.UnionWith(selcalCallsigns);
            var nextCandidateOrdered = orderedRemaining
                .Where(c => !excludedFromRest.Contains(c.Callsign) && nextCandidateCallsigns.Contains(c.Callsign))
                .ToList();
            var rest = orderedRemaining
                .Where(c => !excludedFromRest.Contains(c.Callsign) && !nextCandidateCallsigns.Contains(c.Callsign))
                .ToList();

            var finalOrder = new List<Controller>();
            if (currentCallsign != null)
            {
                finalOrder.Add(controllers.First(c => string.Equals(c.Callsign, currentCallsign, StringComparison.OrdinalIgnoreCase)));
            }
            finalOrder.AddRange(contactMeOrdered);
            finalOrder.AddRange(selcalOrdered);
            finalOrder.AddRange(nextCandidateOrdered);
            finalOrder.AddRange(rest);

            var hasCurrent = currentCallsign != null;
            var ranked = finalOrder.Select(c =>
            {
                enrichment.TryGetValue(c.Callsign, out var info);
                var isCurrent = string.Equals(c.Callsign, currentCallsign, StringComparison.OrdinalIgnoreCase);
                var requestsContactMe = contactMeCallsigns.Contains(c.Callsign);
                var isContactMe = !isCurrent && requestsContactMe;
                var tier = c.Callsign.ParseControllerTier();
                var isNextCandidate = !isCurrent && nextCandidateCallsigns.Contains(c.Callsign);
                var isApproaching = !isCurrent && IsApproaching(c, tier, hasCurrent, telemetry);

                return new RankedController(
                    callsign: c.Callsign,
                    frequency: c.Frequency,
                    latitude: c.Latitude,
                    longitude: c.Longitude,
                    cid: info != null ? (int?)info.Cid : null,
                    name: info?.Name,
                    facility: info != null ? (int?)info.Facility : null,
                    rating: info != null ? (int?)info.Rating : null,
                    requestsContactMe: requestsContactMe,
                    isCurrent: isCurrent,
                    isContactMe: isContactMe,
                    isLikelyNextCandidate: isNextCandidate,
                    isApproaching: isApproaching,
                    stationName: null);
            }).ToList();

            lock (_gate) { _current = ranked; }
            Changed?.Invoke(this, EventArgs.Empty);
        }

        /// <summary>Sorts tiers forward from the current tier first (next candidates), then wraps to earlier tiers.</summary>
        private static int ChainDistance(ControllerTier tier, ControllerTier? currentTier)
        {
            // Other (ATIS/unrecognized) always sorts last, full stop -- it's not part of the
            // DEL->CTR chain at all. Without this, its raw ordinal (5, the highest of any tier)
            // would make its un-wrapped diff *smaller* than a genuinely wrapped tier whenever
            // currentTier is Center: e.g. tuned to CTR, Other's diff is 5-4=1 while a wrapped
            // Delivery's is (0-4)+100=96, so ATIS would sort right after current, ahead of every
            // other real tier -- exactly backwards.
            if (tier == ControllerTier.Other) return int.MaxValue;

            var baseRank = currentTier.HasValue ? (int)currentTier.Value : -1;
            var diff = (int)tier - baseRank;
            return diff >= 0 ? diff : diff + 100;
        }

        private List<Controller> OrderTierByRouteThenDistance(ControllerTier tier, List<Controller> tierControllers, string routeAirport, OwnshipTelemetry telemetry)
        {
            var routeMatched = RouteMatched(tierControllers, routeAirport);
            var unmatched = tierControllers.Except(routeMatched).ToList();

            var orderedMatched = routeMatched.OrderBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase);
            var orderedUnmatched = ApplyDistanceHysteresis(tier, unmatched, telemetry);

            return orderedMatched.Concat(orderedUnmatched).ToList();
        }

        /// <summary>Controllers within a tier whose callsign's ICAO prefix matches the relevant
        /// flight-plan airport (origin pre-departure, destination after takeoff) -- shared by
        /// tier ordering and by next-tier-candidate selection so both agree on "relevant to this
        /// flight" the same way.</summary>
        private static List<Controller> RouteMatched(IEnumerable<Controller> tierControllers, string routeAirport) =>
            !string.IsNullOrEmpty(routeAirport)
                ? tierControllers.Where(c => c.Callsign.StartsWith(routeAirport, StringComparison.OrdinalIgnoreCase)).ToList()
                : new List<Controller>();

        /// <summary>
        /// Distance/heading heuristic for "closing in on this station," only meaningful when
        /// nothing is currently tuned/pinned -- e.g. flying uncontrolled (UNICOM) and about to
        /// enter a TWR/APP's range. GND only counts while on the ground; TWR/APP only while
        /// airborne. APP additionally requires ownship's heading to be within
        /// AppHeadingToleranceDegrees of the bearing to the station once past the
        /// omnidirectional inner radius -- close in, any heading counts; farther out, only a
        /// converging heading does.
        /// </summary>
        private static bool IsApproaching(Controller controller, ControllerTier tier, bool hasCurrent, OwnshipTelemetry telemetry)
        {
            if (hasCurrent) return false;
            if (!telemetry.Latitude.HasValue || !telemetry.Longitude.HasValue || !telemetry.OnGround.HasValue) return false;

            switch (tier)
            {
                case ControllerTier.Ground:
                    return telemetry.OnGround.Value && DistanceNm(controller, telemetry) <= GroundApproachingNauticalMiles;

                case ControllerTier.Tower:
                    return !telemetry.OnGround.Value && DistanceNm(controller, telemetry) <= TowerApproachingNauticalMiles;

                case ControllerTier.AppDep:
                    if (telemetry.OnGround.Value) return false;
                    var distance = DistanceNm(controller, telemetry);
                    if (distance > AppOuterNauticalMiles) return false;
                    if (distance <= AppOmnidirectionalNauticalMiles) return true;
                    if (!telemetry.HeadingDegrees.HasValue) return false;
                    var bearing = GeoDistance.InitialBearingDegrees(telemetry.Latitude.Value, telemetry.Longitude.Value, controller.Latitude, controller.Longitude);
                    return GeoDistance.AngularDifferenceDegrees(telemetry.HeadingDegrees.Value, bearing) <= AppHeadingToleranceDegrees;

                default:
                    // DEL: already well-served by route match. CTR/Other: needs real sector
                    // geometry, deferred to issue #11.
                    return false;
            }
        }

        private static double DistanceNm(Controller controller, OwnshipTelemetry telemetry) =>
            GeoDistance.NauticalMiles(telemetry.Latitude.Value, telemetry.Longitude.Value, controller.Latitude, controller.Longitude);

        private List<Controller> ApplyDistanceHysteresis(ControllerTier tier, List<Controller> controllers, OwnshipTelemetry telemetry)
        {
            if (controllers.Count == 0) return controllers;

            if (!telemetry.Latitude.HasValue || !telemetry.Longitude.HasValue)
            {
                return controllers.OrderBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase).ToList();
            }

            var byDistance = controllers
                .OrderBy(c => GeoDistance.NauticalMiles(telemetry.Latitude.Value, telemetry.Longitude.Value, c.Latitude, c.Longitude))
                .ToList();
            var naturalLeader = byDistance[0].Callsign;

            string committedLeader;
            lock (_gate)
            {
                _committedLeader.TryGetValue(tier, out committedLeader);

                if (committedLeader == null || !controllers.Any(c => string.Equals(c.Callsign, committedLeader, StringComparison.OrdinalIgnoreCase)))
                {
                    committedLeader = naturalLeader;
                    _committedLeader[tier] = committedLeader;
                    _pendingChallenger.Remove(tier);
                    _pendingSince.Remove(tier);
                }
                else if (!string.Equals(naturalLeader, committedLeader, StringComparison.OrdinalIgnoreCase))
                {
                    _pendingChallenger.TryGetValue(tier, out var pendingChallenger);
                    if (!string.Equals(pendingChallenger, naturalLeader, StringComparison.OrdinalIgnoreCase))
                    {
                        _pendingChallenger[tier] = naturalLeader;
                        _pendingSince[tier] = _now();
                    }
                    else if (_now() - _pendingSince[tier] >= HysteresisWindow)
                    {
                        committedLeader = naturalLeader;
                        _committedLeader[tier] = committedLeader;
                        _pendingChallenger.Remove(tier);
                        _pendingSince.Remove(tier);
                    }
                }
                else
                {
                    _pendingChallenger.Remove(tier);
                    _pendingSince.Remove(tier);
                }
            }

            var leader = byDistance.First(c => string.Equals(c.Callsign, committedLeader, StringComparison.OrdinalIgnoreCase));
            var rest = byDistance.Where(c => !string.Equals(c.Callsign, committedLeader, StringComparison.OrdinalIgnoreCase));
            return new[] { leader }.Concat(rest).ToList();
        }

        private void Log(string message)
        {
            var line = "ControllerRankingModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
