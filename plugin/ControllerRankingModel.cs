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
    ///   3. Chain tier (DEL -&gt; GND -&gt; TWR -&gt; APP/DEP -&gt; CTR), relative to the current tier --
    ///      the tier immediately following gets IsLikelyNextCandidate, however many entries that
    ///      is.
    ///   4. Within a tier: route match (callsign ICAO prefix vs flight-plan origin/destination).
    ///   5. Within a tier, no route match: distance to ownship, closest first.
    ///
    /// Distance-based ordering (step 5) is the one prone to sensor-noise flapping (a momentary
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
        private readonly Action<string> _logDebug;
        private readonly Func<DateTimeOffset> _now;

        private readonly Dictionary<ControllerTier, string> _committedLeader = new Dictionary<ControllerTier, string>();
        private readonly Dictionary<ControllerTier, string> _pendingChallenger = new Dictionary<ControllerTier, string>();
        private readonly Dictionary<ControllerTier, DateTimeOffset> _pendingSince = new Dictionary<ControllerTier, DateTimeOffset>();

        private IReadOnlyList<RankedController> _current = new List<RankedController>();
        private string _pinnedCallsign;
        private bool _hasTakenOffThisSession;

        public event EventHandler Changed;

        public ControllerRankingModel(ControllerStateModel controllerState, IRadioStateModel radioState, FlightPlanModel flightPlanState, VatsimDataFeedModel vatsimFeed, ContactMeModel contactMe, Action<string> logDebug = null, Func<DateTimeOffset> now = null)
        {
            _controllerState = controllerState ?? throw new ArgumentNullException(nameof(controllerState));
            _radioState = radioState ?? throw new ArgumentNullException(nameof(radioState));
            _flightPlanState = flightPlanState ?? throw new ArgumentNullException(nameof(flightPlanState));
            _vatsimFeed = vatsimFeed ?? throw new ArgumentNullException(nameof(vatsimFeed));
            _contactMe = contactMe ?? throw new ArgumentNullException(nameof(contactMe));
            _logDebug = logDebug;
            _now = now ?? (() => DateTimeOffset.Now);

            _controllerState.Changed += (s, e) => Recompute();
            _radioState.Changed += (s, e) => Recompute();
            _flightPlanState.Changed += (s, e) => Recompute();
            _vatsimFeed.Changed += (s, e) => Recompute();
            _contactMe.Changed += (s, e) => Recompute();

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

            if (telemetry.OnGround == false) _hasTakenOffThisSession = true;

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
            var nextTier = NextTierInChain(controllers, currentTier);

            var routeAirport = _hasTakenOffThisSession ? flightPlan.Destination : flightPlan.Origin;

            var remaining = controllers.Where(c => !string.Equals(c.Callsign, currentCallsign, StringComparison.OrdinalIgnoreCase)).ToList();
            var orderedRemaining = new List<Controller>();
            foreach (var tierGroup in remaining.GroupBy(c => c.Callsign.ParseControllerTier()).OrderBy(g => ChainDistance(g.Key, currentTier)))
            {
                orderedRemaining.AddRange(OrderTierByRouteThenDistance(tierGroup.Key, tierGroup.ToList(), routeAirport, telemetry));
            }

            var contactMeOrdered = orderedRemaining
                .Where(c => contactMeCallsigns.Contains(c.Callsign))
                .OrderBy(c => c.Callsign.ParseControllerTier())
                .ThenBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase)
                .ToList();
            var rest = orderedRemaining.Where(c => !contactMeCallsigns.Contains(c.Callsign)).ToList();

            var finalOrder = new List<Controller>();
            if (currentCallsign != null)
            {
                finalOrder.Add(controllers.First(c => string.Equals(c.Callsign, currentCallsign, StringComparison.OrdinalIgnoreCase)));
            }
            finalOrder.AddRange(contactMeOrdered);
            finalOrder.AddRange(rest);

            var hasCurrent = currentCallsign != null;
            var ranked = finalOrder.Select(c =>
            {
                enrichment.TryGetValue(c.Callsign, out var info);
                var isCurrent = string.Equals(c.Callsign, currentCallsign, StringComparison.OrdinalIgnoreCase);
                var requestsContactMe = contactMeCallsigns.Contains(c.Callsign);
                var isContactMe = !isCurrent && requestsContactMe;
                var tier = c.Callsign.ParseControllerTier();
                var isNextCandidate = !isCurrent && nextTier.HasValue && tier == nextTier.Value;
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

        private static ControllerTier? NextTierInChain(IReadOnlyCollection<Controller> controllers, ControllerTier? currentTier)
        {
            var startRank = currentTier.HasValue ? (int)currentTier.Value : -1;
            var candidates = controllers
                .Select(c => c.Callsign.ParseControllerTier())
                .Where(t => t != ControllerTier.Other && (int)t > startRank)
                .Distinct()
                .OrderBy(t => (int)t)
                .ToList();
            return candidates.Count > 0 ? candidates[0] : (ControllerTier?)null;
        }

        /// <summary>Sorts tiers forward from the current tier first (next candidates), then wraps to earlier tiers.</summary>
        private static int ChainDistance(ControllerTier tier, ControllerTier? currentTier)
        {
            var baseRank = currentTier.HasValue ? (int)currentTier.Value : -1;
            var diff = (int)tier - baseRank;
            return diff >= 0 ? diff : diff + 100;
        }

        private List<Controller> OrderTierByRouteThenDistance(ControllerTier tier, List<Controller> tierControllers, string routeAirport, OwnshipTelemetry telemetry)
        {
            var routeMatched = !string.IsNullOrEmpty(routeAirport)
                ? tierControllers.Where(c => c.Callsign.StartsWith(routeAirport, StringComparison.OrdinalIgnoreCase)).ToList()
                : new List<Controller>();
            var unmatched = tierControllers.Except(routeMatched).ToList();

            var orderedMatched = routeMatched.OrderBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase);
            var orderedUnmatched = ApplyDistanceHysteresis(tier, unmatched, telemetry);

            return orderedMatched.Concat(orderedUnmatched).ToList();
        }

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
