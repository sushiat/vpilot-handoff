using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Re-ranks the full controller list HandoffControllerStateModel already reports -- nothing is
    /// ever hidden except a recently-disconnected station within its brief grace window (see
    /// HandoffController), every other station stays visible, just reordered with flags the
    /// Android app uses for colour-coding/badges. See docs/controller-ranking.md for the full
    /// bucket-by-bucket design (issue #18) -- this class implements it. Buckets, in rank order:
    ///
    ///   1. Currently-tuned controller(s) -- IsCurrent. COM1 and COM2 can each independently
    ///      match a different real online station; both get IsCurrent, COM1 ordered first.
    ///   2. IsStandbyTuned -- frequency loaded into COM1 or COM2 standby.
    ///   3. IsContactMe -- outstanding contact-me request.
    ///   4. IsSelcalActive -- active SELCAL alert.
    ///   5. IsPinned -- manual bookmark (SetPinnedController), never a stand-in for IsCurrent.
    ///   6. On-ground (AGL &lt; 50ft) relevance: IsHighlighted (flight-plan match, unconditional;
    ///      else polygon containment -- VATGlasses where it has coverage, else a vatspy FIR
    ///      polygon for CTR (issue #11) -- else a tight radius fallback for DEL/GND/TWR/APP; CTR
    ///      is polygon-only, no radius fallback at all) and IsNext/IsLikelyNext (chain-walk over
    ///      the highlighted set, tie-detected).
    ///   7. Airborne TWR/APP relevance: concentric-radius highlight/next for TWR; a flat-radius
    ///      highlight + route/heading-convergence next for APP, confidence-capped when not on
    ///      the flight plan.
    ///   8. Airborne CTR relevance: lateral+vertical convergence prediction (satisfied-or-
    ///      converging), VATGlasses-or-vatspy same as bucket 6, tie-banded next/likely-next, plus
    ///      an independent ETA readout.
    ///   9. Everything else -- the original issue #8 chain-tier-then-distance fallback, except the
    ///      CTR tier group prefers a currently-polygon-contained candidate first (issue #11).
    ///
    /// Within buckets 6/7/8: IsNext first, then IsLikelyNext (distance only -- ties are guaranteed
    /// same-tier by construction), then plain IsHighlighted (chain tier then distance).
    /// </summary>
    public sealed class ControllerRankingModel
    {
        private static readonly TimeSpan HysteresisWindow = TimeSpan.FromSeconds(12);

        // Bucket 6/7 boundary -- "on the ground" is a pure instantaneous check, not a session
        // latch (deliberately simplified per issue #18 flight-test feedback: touch-and-gos and
        // multiple sectors in one session made a sticky phase-of-flight concept messy).
        private const double OnGroundMaxAglFeet = 50;

        // Minimum AGL required before latching _hasTakenOffThisSession and flipping routeAirport
        // from origin to destination. Well above squat-switch flicker (a few feet at most from a
        // ramp bump), but also deliberately well above a bare rotation -- flight-test feedback
        // (issue #17) showed the departure airport's own APP/DEP losing next-candidate status
        // mid-climbout because a ~50ft threshold flipped routeAirport to the destination almost
        // immediately at liftoff, while the flight was still very much dealing with the origin's
        // own airspace. 3000ft AGL keeps origin route-matching active through the initial climb.
        private const double TakeoffAglThresholdFeet = 3000;

        // Bucket 6b/6c ground-relevance radius fallback (no VATGlasses polygon coverage).
        private const double GroundDelGndTwrRadiusNm = 5;
        private const double GroundAppRadiusNm = 20;

        // Bucket 7a: TWR airborne, concentric radii (highlight / confident-next inner radius),
        // wider when the station is on the flight plan.
        private const double TwrAirborneMaxAglFeet = 10000;
        private const double TwrHighlightRadiusFplnNm = 20;
        private const double TwrHighlightRadiusNonFplnNm = 10;
        private const double TwrNextRadiusFplnNm = 10;
        private const double TwrNextRadiusNonFplnNm = 5;

        // Bucket 7b: APP/DEP airborne highlight radius (flat, regardless of flight-plan status)
        // and its altitude ceiling. The ceiling prefers the sector's own published upper FL (+
        // margin) where VATGlasses defines one; falls back to a flat ceiling otherwise. No lower
        // bound at all -- a real gap hit on departure: Tower handed off to APP early, clear of
        // conflict, before being within APP's nominal lower band, and a lower-bound requirement
        // would have wrongly suppressed the flag.
        private const double AppHighlightRadiusNm = 30;
        private const double AppCeilingMarginFl = 50; // 5000ft, in FL units
        private const double AppCeilingFallbackFl = 290; // FL290, used when the polygon has no altitude info at all

        // Bucket 8a: CTR airborne lateral+vertical convergence prediction.
        private const double LateralApproachMaxNauticalMiles = 100;
        private const double RouteApproachMaxNauticalMiles = 150;
        private const double VerticalTrendThresholdFpm = 500;
        private static readonly TimeSpan VerticalTrendSustainWindow = TimeSpan.FromSeconds(5);
        private const double VerticalApproachThresholdFeet = 5000;

        // Bucket 8b: tie-banding -- everyone within (closest x TieBandMultiplier) of the closest
        // qualifying candidate ties with it, rather than only the single closest ever counting.
        private const double TieBandMultiplier = 1.10;

        // Bucket 8c: ETA readout gate for climbing/descending (level flight has no altitude floor
        // at all). FL150 is a single flat threshold, not aircraft-type-aware -- not worth the
        // SimConnect engine/category-detection work this would need to do properly for a soft UX
        // nicety, not a correctness-critical flag.
        private const double EtaClimbDescendMinFl = 150;

        // Spatial dead-band for the numeric radius/tie-band thresholds above (6b/6c's radius
        // fallback, 7b's highlight radius, 8b's tie-band) -- a candidate joins at the real
        // threshold, but once in, only leaves once past DeadbandExitMultiplier x that threshold.
        // Guards against flapping right at a boundary (GPS/telemetry jitter, or distance
        // oscillating right at a tie-band edge) without needing per-tick timing state -- see
        // docs/controller-ranking.md's "Flapping protection" section.
        private const double DeadbandExitMultiplier = 1.20;

        // Same idea for actual polygon containment (6b/6c/6d's preferred path) -- once inside,
        // only actually leave once genuinely more than this far past the nearest boundary edge
        // (VatGlassesSectorLookup.DistanceToPolygonBoundaryNm), not the instant the boolean
        // point-in-polygon check flips. A flat nm margin rather than a percentage multiplier --
        // unlike the radius checks above, there's no natural "threshold value" to scale a
        // percentage against here, just an edge.
        private const double PolygonContainmentDeadbandMarginNm = 1.0;

        private readonly object _gate = new object();
        private readonly HandoffControllerStateModel _controllerState;
        private readonly IRadioStateModel _radioState;
        private readonly FlightPlanModel _flightPlanState;
        private readonly VatsimDataFeedModel _vatsimFeed;
        private readonly PilotSessionModel _pilotSession;
        private readonly VatGlassesDataModel _vatGlassesData;
        private readonly VatSpyDataModel _vatSpyData;
        private readonly Action<string> _logDebug;
        private readonly Func<DateTimeOffset> _now;

        // Committed-inclusion state for the dead-band above -- one set per numeric threshold
        // check, callsign-keyed. Pruned each tick to whatever candidates are actually still being
        // considered for that check, so a callsign's stale membership can't resurrect once it's
        // gone from the relevant candidate set (offline, or moved to a different bucket).
        private readonly HashSet<string> _groundRadiusCommitted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _appRadiusCommitted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _tieBandCommitted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _groundPolygonContainmentCommitted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _ctrSatisfiedCommitted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        // Issue #11: vatspy is a second, coarser polygon tier -- VATGlasses polygon, else vatspy
        // FIR polygon, else nothing/plain distance -- so it gets its own dead-band commit sets,
        // parallel to the VATGlasses ones above, at every site that chain applies (6d, 8a, 9).
        private readonly HashSet<string> _groundVatSpyContainmentCommitted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _ctrVatSpySatisfiedCommitted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        private readonly Dictionary<ControllerTier, string> _committedLeader = new Dictionary<ControllerTier, string>();
        private readonly Dictionary<ControllerTier, string> _pendingChallenger = new Dictionary<ControllerTier, string>();
        private readonly Dictionary<ControllerTier, DateTimeOffset> _pendingSince = new Dictionary<ControllerTier, DateTimeOffset>();

        // Sustained climb/descent trend for vertical-convergence prediction (bucket 8a) --
        // ownship-level, not per-tier. -1 descending, 0 level, +1 climbing; only "sustained" (held
        // for VerticalTrendSustainWindow) counts as a signal.
        private int _verticalTrendSign;
        private DateTimeOffset _verticalTrendSince;

        private IReadOnlyList<RankedController> _current = new List<RankedController>();
        private double? _etaMinutes;
        private bool _hasTakenOffThisSession;
        private string _lastObservedDestination;
        private bool _routeInvalidatedByDiversion;

        public event EventHandler Changed;

        public ControllerRankingModel(HandoffControllerStateModel controllerState, IRadioStateModel radioState, FlightPlanModel flightPlanState, VatsimDataFeedModel vatsimFeed, PilotSessionModel pilotSession, VatGlassesDataModel vatGlassesData, VatSpyDataModel vatSpyData, Action<string> logDebug = null, Func<DateTimeOffset> now = null)
        {
            _controllerState = controllerState ?? throw new ArgumentNullException(nameof(controllerState));
            _radioState = radioState ?? throw new ArgumentNullException(nameof(radioState));
            _flightPlanState = flightPlanState ?? throw new ArgumentNullException(nameof(flightPlanState));
            _vatsimFeed = vatsimFeed ?? throw new ArgumentNullException(nameof(vatsimFeed));
            _pilotSession = pilotSession ?? throw new ArgumentNullException(nameof(pilotSession));
            _vatGlassesData = vatGlassesData ?? throw new ArgumentNullException(nameof(vatGlassesData));
            _vatSpyData = vatSpyData ?? throw new ArgumentNullException(nameof(vatSpyData));
            _logDebug = logDebug;
            _now = now ?? (() => DateTimeOffset.Now);

            _controllerState.Changed += (s, e) => Recompute();
            _radioState.Changed += (s, e) => Recompute();
            _flightPlanState.Changed += (s, e) => Recompute();
            _vatsimFeed.Changed += (s, e) => Recompute();
            _pilotSession.Changed += (s, e) => Recompute();
            _vatGlassesData.Changed += (s, e) => Recompute();
            _vatSpyData.Changed += (s, e) => Recompute();

            Recompute();
        }

        public IReadOnlyList<RankedController> Current
        {
            get { lock (_gate) { return _current; } }
        }

        /// <summary>Bucket 8c -- minutes remaining to the closest bucket-8-qualifying CTR sector, or null if not currently available (below the climb/descend FL150 floor, or nothing to estimate against).</summary>
        public double? EtaMinutes
        {
            get { lock (_gate) { return _etaMinutes; } }
        }

        private void Recompute()
        {
            var controllers = _controllerState.Controllers;
            var radio = _radioState.Current;
            var telemetry = _radioState.Telemetry;
            var flightPlan = _flightPlanState.Current;
            var enrichment = _vatsimFeed.Controllers;

            if (telemetry.OnGround == false && telemetry.AltitudeAboveGroundFeet.GetValueOrDefault() > TakeoffAglThresholdFeet)
            {
                _hasTakenOffThisSession = true;
            }

            var tunedFrequencies = new HashSet<int>();
            if (radio.Com1Frequency.HasValue) tunedFrequencies.Add(radio.Com1Frequency.Value);
            if (radio.Com2Frequency.HasValue) tunedFrequencies.Add(radio.Com2Frequency.Value);

            var standbyFrequencies = new HashSet<int>();
            if (radio.Com1StandbyFrequency.HasValue) standbyFrequencies.Add(radio.Com1StandbyFrequency.Value);
            if (radio.Com2StandbyFrequency.HasValue) standbyFrequencies.Add(radio.Com2StandbyFrequency.Value);

            var currentCallsigns = new HashSet<string>(
                controllers.Where(c => tunedFrequencies.Contains(c.Frequency)).Select(c => c.Callsign),
                StringComparer.OrdinalIgnoreCase);
            foreach (var callsign in currentCallsigns) _controllerState.ClearContactMe(callsign);
            var currentTier = controllers.FirstOrDefault(c => tunedFrequencies.Contains(c.Frequency))?.Callsign?.ParseControllerTier();

            VatsimPilotInfo vatsimPilot = null;
            var vatsimCallsign = _pilotSession.Callsign;
            if (vatsimCallsign != null) _vatsimFeed.Pilots.TryGetValue(vatsimCallsign, out vatsimPilot);
            var origin = vatsimPilot?.Departure ?? flightPlan.Origin;
            var destination = vatsimPilot?.Arrival ?? flightPlan.Destination;
            var routeAirport = _hasTakenOffThisSession ? destination : origin;

            if (_lastObservedDestination != null && destination != null &&
                !string.Equals(_lastObservedDestination, destination, StringComparison.OrdinalIgnoreCase))
            {
                _routeInvalidatedByDiversion = true;
                Log("Destination changed from " + _lastObservedDestination + " to " + destination + " -- treating as a diversion, dropping the filed route for approach prediction.");
            }
            if (destination != null) _lastObservedDestination = destination;

            UpdateVerticalTrend(telemetry);

            var pressureAltitudeFl = telemetry.PressureAltitudeFeet / 100.0;
            var qnhTrueAltitudeFl = telemetry.PressureAltitudeFeet.HasValue && telemetry.SeaLevelPressureHpa.HasValue
                ? PressureAltitude.QnhTrueAltitudeFeet(telemetry.PressureAltitudeFeet.Value, telemetry.SeaLevelPressureHpa.Value) / 100.0
                : (double?)null;

            var isOnGround = telemetry.AltitudeAboveGroundFeet.HasValue
                ? telemetry.AltitudeAboveGroundFeet.Value < OnGroundMaxAglFeet
                : telemetry.OnGround.GetValueOrDefault(true);

            var remaining = controllers.Where(c => !currentCallsigns.Contains(c.Callsign)).ToList();

            var excludedFromRest = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            // Bucket 2 -- standby.
            var standbyCallsigns = new HashSet<string>(
                remaining.Where(c => standbyFrequencies.Contains(c.Frequency)).Select(c => c.Callsign),
                StringComparer.OrdinalIgnoreCase);
            excludedFromRest.UnionWith(standbyCallsigns);

            // Bucket 3 -- contact-me.
            var contactMeCallsigns = new HashSet<string>(
                remaining.Where(c => !excludedFromRest.Contains(c.Callsign) && c.ContactMeExpiresAtUtc.HasValue).Select(c => c.Callsign),
                StringComparer.OrdinalIgnoreCase);
            excludedFromRest.UnionWith(contactMeCallsigns);

            // Bucket 4 -- SELCAL.
            var selcalCallsigns = new HashSet<string>(
                remaining.Where(c => !excludedFromRest.Contains(c.Callsign) && c.SelcalExpiresAtUtc.HasValue).Select(c => c.Callsign),
                StringComparer.OrdinalIgnoreCase);
            excludedFromRest.UnionWith(selcalCallsigns);

            // Bucket 5 -- pinned.
            var pinnedCallsigns = new HashSet<string>(
                remaining.Where(c => !excludedFromRest.Contains(c.Callsign) && c.IsPinned).Select(c => c.Callsign),
                StringComparer.OrdinalIgnoreCase);
            excludedFromRest.UnionWith(pinnedCallsigns);

            // Buckets 6/7/8 -- highlight + next/likely-next, computed over whatever's left. Ground
            // (bucket 6) is one combined bucket spanning DEL/GND/TWR/APP/CTR (6e's chain-walk
            // needs them together); airborne is two separate, sequential buckets -- 7 (TWR/APP)
            // ranked above 8 (CTR) -- since a TWR/APP handoff is more time-critical than an
            // enroute Center prediction.
            var bucketCandidates = remaining.Where(c => !excludedFromRest.Contains(c.Callsign)).ToList();
            var vatGlassesRegions = _vatGlassesData.Regions;
            var vatSpyBoundaries = _vatSpyData.FirBoundaries;
            var highlightBlocks = new List<HighlightResult>();

            if (isOnGround)
            {
                currentTierGroundWalkStartRank = currentTier.HasValue ? (int)currentTier.Value : -1;
                var groundResult = ComputeGroundHighlight(bucketCandidates, controllers, routeAirport, telemetry, vatGlassesRegions, vatSpyBoundaries);
                highlightBlocks.Add(groundResult);
                excludedFromRest.UnionWith(groundResult.HighlightedCallsigns);
                _etaMinutes = null; // Bucket 8c only ever applies airborne.
            }
            else
            {
                var twrAppCandidates = bucketCandidates.Where(c => IsTwrOrApp(c.Callsign.ParseControllerTier())).ToList();
                var bucket7Result = ComputeBucket7Highlight(twrAppCandidates, controllers, routeAirport, flightPlan, telemetry, vatGlassesRegions);
                highlightBlocks.Add(bucket7Result);
                excludedFromRest.UnionWith(bucket7Result.HighlightedCallsigns);

                var ctrCandidates = bucketCandidates.Where(c => c.Callsign.ParseControllerTier() == ControllerTier.Center).ToList();
                var bucket8Result = ComputeBucket8Highlight(ctrCandidates, controllers, flightPlan, telemetry, pressureAltitudeFl, qnhTrueAltitudeFl, vatGlassesRegions, vatSpyBoundaries, currentCallsigns);
                highlightBlocks.Add(bucket8Result);
                excludedFromRest.UnionWith(bucket8Result.HighlightedCallsigns);

                _etaMinutes = ComputeEtaMinutes(telemetry, bucket8Result);
            }

            var highlightedCallsigns = new HashSet<string>(highlightBlocks.SelectMany(b => b.HighlightedCallsigns), StringComparer.OrdinalIgnoreCase);
            var nextCallsigns = new HashSet<string>(highlightBlocks.SelectMany(b => b.NextCallsigns), StringComparer.OrdinalIgnoreCase);
            var likelyNextCallsigns = new HashSet<string>(highlightBlocks.SelectMany(b => b.LikelyNextCallsigns), StringComparer.OrdinalIgnoreCase);

            // Bucket 9 -- everything else, original issue #8 chain-tier-then-distance fallback.
            // Issue #11: within the CTR tier group specifically, candidates whose FIR is
            // currently contained (VATGlasses or vatspy polygon) sort ahead of the rest of the
            // tier -- ordering-only, no new flag, same as the rest of bucket 9.
            var rest = remaining.Where(c => !excludedFromRest.Contains(c.Callsign)).ToList();
            var orderedRest = new List<HandoffController>();
            foreach (var tierGroup in rest.GroupBy(c => c.Callsign.ParseControllerTier()).OrderBy(g => ChainDistance(g.Key, currentTier)))
            {
                if (tierGroup.Key == ControllerTier.Center && telemetry.Latitude.HasValue && telemetry.Longitude.HasValue)
                {
                    var containedNow = ContainedCtrCallsigns(tierGroup, vatGlassesRegions, vatSpyBoundaries, controllers, telemetry.Latitude.Value, telemetry.Longitude.Value);
                    var containedGroup = tierGroup.Where(c => containedNow.Contains(c.Callsign)).ToList();
                    var uncontainedGroup = tierGroup.Where(c => !containedNow.Contains(c.Callsign)).ToList();
                    orderedRest.AddRange(OrderTierByRouteThenDistance(tierGroup.Key, containedGroup, routeAirport, telemetry));
                    orderedRest.AddRange(OrderTierByRouteThenDistance(tierGroup.Key, uncontainedGroup, routeAirport, telemetry));
                }
                else
                {
                    orderedRest.AddRange(OrderTierByRouteThenDistance(tierGroup.Key, tierGroup.ToList(), routeAirport, telemetry));
                }
            }

            var finalOrder = new List<HandoffController>();
            finalOrder.AddRange(OrderCurrentBucket(controllers, currentCallsigns, radio));
            finalOrder.AddRange(OrderByTierThenAlpha(remaining.Where(c => standbyCallsigns.Contains(c.Callsign))));
            finalOrder.AddRange(OrderByTierThenAlpha(remaining.Where(c => contactMeCallsigns.Contains(c.Callsign))));
            finalOrder.AddRange(OrderByTierThenAlpha(remaining.Where(c => selcalCallsigns.Contains(c.Callsign))));
            finalOrder.AddRange(OrderByTierThenAlpha(remaining.Where(c => pinnedCallsigns.Contains(c.Callsign))));
            foreach (var block in highlightBlocks)
            {
                finalOrder.AddRange(OrderHighlightBucket(block));
            }
            finalOrder.AddRange(orderedRest);

            var ranked = finalOrder.Select(c =>
            {
                enrichment.TryGetValue(c.Callsign, out var info);
                var isCurrent = currentCallsigns.Contains(c.Callsign);

                return new RankedController(
                    callsign: c.Callsign,
                    frequency: c.Frequency,
                    latitude: c.Latitude,
                    longitude: c.Longitude,
                    cid: info != null ? (int?)info.Cid : null,
                    name: info?.Name,
                    facility: info != null ? (int?)info.Facility : null,
                    rating: info != null ? (int?)info.Rating : null,
                    requestsContactMe: c.ContactMeExpiresAtUtc.HasValue,
                    isCurrent: isCurrent,
                    isContactMe: !isCurrent && c.ContactMeExpiresAtUtc.HasValue,
                    isHighlighted: highlightedCallsigns.Contains(c.Callsign),
                    isNext: nextCallsigns.Contains(c.Callsign),
                    isLikelyNext: likelyNextCallsigns.Contains(c.Callsign),
                    isPinned: c.IsPinned,
                    isStandbyTuned: !isCurrent && standbyFrequencies.Contains(c.Frequency),
                    isSelcalActive: c.SelcalExpiresAtUtc.HasValue,
                    stationName: VatAtisStationNameExtractor.Extract(info?.TextAtis) ?? VatSpyStationNaming.ComposeDisplayName(c.Callsign, _vatSpyData),
                    textAtis: info?.TextAtis);
            }).ToList();

            lock (_gate) { _current = ranked; }
            Changed?.Invoke(this, EventArgs.Empty);
        }

        /// <summary>Bucket 1 -- COM1's match (if any) always ordered ahead of COM2's.</summary>
        private static IEnumerable<HandoffController> OrderCurrentBucket(IReadOnlyCollection<HandoffController> controllers, HashSet<string> currentCallsigns, RadioState radio)
        {
            var current = controllers.Where(c => currentCallsigns.Contains(c.Callsign)).ToList();
            if (current.Count <= 1) return current;

            var com1Match = radio.Com1Frequency.HasValue ? current.FirstOrDefault(c => c.Frequency == radio.Com1Frequency.Value) : null;
            var rest = current.Where(c => !ReferenceEquals(c, com1Match));
            return com1Match != null ? new[] { com1Match }.Concat(rest) : current;
        }

        private static IEnumerable<HandoffController> OrderByTierThenAlpha(IEnumerable<HandoffController> controllers) =>
            controllers.OrderBy(c => c.Callsign.ParseControllerTier()).ThenBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase);

        /// <summary>Sorts tiers forward from the current tier first (next candidates), then wraps to earlier tiers.</summary>
        private static int ChainDistance(ControllerTier tier, ControllerTier? currentTier)
        {
            if (tier == ControllerTier.Other) return int.MaxValue;

            var baseRank = currentTier.HasValue ? (int)currentTier.Value : -1;
            var diff = (int)tier - baseRank;
            return diff >= 0 ? diff : diff + 100;
        }

        private List<HandoffController> OrderTierByRouteThenDistance(ControllerTier tier, List<HandoffController> tierControllers, string routeAirport, OwnshipTelemetry telemetry)
        {
            var routeMatched = RouteMatched(tierControllers, routeAirport);
            var unmatched = tierControllers.Except(routeMatched).ToList();

            var orderedMatched = routeMatched.OrderBy(c => c.Callsign, StringComparer.OrdinalIgnoreCase);
            var orderedUnmatched = ApplyDistanceHysteresis(tier, unmatched, telemetry);

            return orderedMatched.Concat(orderedUnmatched).ToList();
        }

        private static List<HandoffController> RouteMatched(IEnumerable<HandoffController> tierControllers, string routeAirport) =>
            !string.IsNullOrEmpty(routeAirport)
                ? tierControllers.Where(c => c.Callsign.StartsWith(routeAirport, StringComparison.OrdinalIgnoreCase)).ToList()
                : new List<HandoffController>();

        private static double DistanceNm(HandoffController controller, OwnshipTelemetry telemetry) =>
            GeoDistance.NauticalMiles(telemetry.Latitude.Value, telemetry.Longitude.Value, controller.Latitude, controller.Longitude);

        private static VatGlassesRegionData FindRegionForAirport(IReadOnlyDictionary<string, VatGlassesRegionData> regions, string icao, out VatGlassesAirport airport)
        {
            foreach (var region in regions.Values)
            {
                if (region.Airports.TryGetValue(icao, out airport)) return region;
            }
            airport = null;
            return null;
        }

        private void UpdateVerticalTrend(OwnshipTelemetry telemetry)
        {
            var vs = telemetry.VerticalSpeedFpm;
            var sign = vs.HasValue && vs.Value >= VerticalTrendThresholdFpm ? 1
                : vs.HasValue && vs.Value <= -VerticalTrendThresholdFpm ? -1
                : 0;

            lock (_gate)
            {
                if (sign != _verticalTrendSign)
                {
                    _verticalTrendSign = sign;
                    _verticalTrendSince = _now();
                }
            }
        }

        private List<HandoffController> ApplyDistanceHysteresis(ControllerTier tier, List<HandoffController> controllers, OwnshipTelemetry telemetry)
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

        /// <summary>Accumulates one bucket's IsHighlighted/IsNext/IsLikelyNext callsigns, plus whatever distance figure the tie/order logic needs, and the candidate objects to order them against.</summary>
        private sealed class HighlightResult
        {
            public List<HandoffController> Candidates { get; set; } = new List<HandoffController>();
            public HashSet<string> HighlightedCallsigns { get; } = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            public HashSet<string> NextCallsigns { get; } = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            public HashSet<string> LikelyNextCallsigns { get; } = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, double> DistanceNm { get; } = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        }

        private static bool IsTwrOrApp(ControllerTier tier) => tier == ControllerTier.Tower || tier == ControllerTier.AppDep;

        /// <summary>Spatial dead-band check: passes at <paramref name="enterThreshold"/> if not
        /// already committed, or at <paramref name="enterThreshold"/> x DeadbandExitMultiplier if
        /// it is -- updates <paramref name="committed"/> in place. See DeadbandExitMultiplier's
        /// doc comment for why this exists instead of a time-based hysteresis window.</summary>
        private static bool PassesDeadband(HashSet<string> committed, string callsign, double value, double enterThreshold)
        {
            var wasCommitted = committed.Contains(callsign);
            var threshold = wasCommitted ? enterThreshold * DeadbandExitMultiplier : enterThreshold;
            var isIn = value <= threshold;
            if (isIn) committed.Add(callsign); else committed.Remove(callsign);
            return isIn;
        }

        /// <summary>Drops any committed callsign no longer present in this tick's candidate set -- otherwise a stale committed flag could resurrect if the same callsign reappears in an unrelated context later.</summary>
        private static void PruneDeadbandCommitted(HashSet<string> committed, IEnumerable<string> currentCandidateCallsigns)
        {
            var current = new HashSet<string>(currentCandidateCallsigns, StringComparer.OrdinalIgnoreCase);
            committed.RemoveWhere(cs => !current.Contains(cs));
        }

        /// <summary>
        /// Polygon-containment spatial dead-band: entry requires genuine containment this tick;
        /// once committed, stays included as long as it's either still genuinely contained, or
        /// (lazily, only checked once actually outside) within PolygonContainmentDeadbandMarginNm
        /// of the nearest boundary edge of any sector this controller owns -- reuses
        /// FindAnySectorLevelForController the same way bucket 7b's ceiling/distance resolution
        /// already does. No owning sector found at all -- treated as genuinely out of range,
        /// exits immediately regardless of prior commitment.
        /// </summary>
        private bool PassesContainmentDeadband(
            HashSet<string> committed,
            HandoffController controller,
            bool isContainedNow,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            IReadOnlyCollection<HandoffController> allOnlineControllers,
            double lat,
            double lon)
        {
            if (isContainedNow)
            {
                committed.Add(controller.Callsign);
                return true;
            }

            if (!committed.Contains(controller.Callsign)) return false;

            var level = FindAnySectorLevelForController(controller, regions, allOnlineControllers);
            var staysIn = level != null && VatGlassesSectorLookup.DistanceToPolygonBoundaryNm(lat, lon, level) <= PolygonContainmentDeadbandMarginNm;
            if (!staysIn) committed.Remove(controller.Callsign);
            return staysIn;
        }

        private static HashSet<string> ResolveContainedCallsigns(
            IReadOnlyList<VatGlassesSectorLookup.VatGlassesSectorMatch> matches,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            IReadOnlyCollection<HandoffController> onlineControllers)
        {
            var result = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var match in matches)
            {
                if (!regions.TryGetValue(match.RegionFileName, out var region)) continue;
                foreach (var owner in VatGlassesOwnershipResolver.ResolveOnlineControllers(match.Sector.Owner, region.Positions, onlineControllers))
                {
                    result.Add(owner.Callsign);
                }
            }
            return result;
        }

        /// <summary>
        /// Issue #11: vatspy equivalent of ResolveContainedCallsigns -- every online CTR
        /// controller whose FIR boundary contains ownship right now. Only ever consulted where
        /// VATGlasses had nothing (see the CTR ground/8a "satisfied" call sites) -- VATGlasses
        /// stays strictly preferred wherever it has coverage, since it's the more precise source.
        /// </summary>
        private static HashSet<string> ResolveVatSpyContainedCallsigns(
            IReadOnlyList<VatSpyFirBoundary> matches,
            IReadOnlyCollection<HandoffController> onlineControllers)
        {
            var result = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var boundary in matches)
            {
                foreach (var owner in VatSpyOwnershipResolver.ResolveOnlineControllers(boundary, onlineControllers))
                {
                    result.Add(owner.Callsign);
                }
            }
            return result;
        }

        /// <summary>Issue #11: vatspy equivalent of PassesContainmentDeadband, against VatSpyFirBoundary instead of a VATGlasses sector level.</summary>
        private bool PassesVatSpyContainmentDeadband(
            HashSet<string> committed,
            HandoffController controller,
            bool isContainedNow,
            IReadOnlyList<VatSpyFirBoundary> boundaries,
            double lat,
            double lon)
        {
            if (isContainedNow)
            {
                committed.Add(controller.Callsign);
                return true;
            }

            if (!committed.Contains(controller.Callsign)) return false;

            var boundary = FindAnyVatSpyBoundaryForController(controller, boundaries);
            var staysIn = boundary != null && VatSpyBoundaryLookup.DistanceToBoundaryNm(lat, lon, boundary) <= PolygonContainmentDeadbandMarginNm;
            if (!staysIn) committed.Remove(controller.Callsign);
            return staysIn;
        }

        /// <summary>Issue #11: vatspy equivalent of FindAnySectorLevelForController -- the first vatspy boundary whose callsign prefixes resolve to this specific controller.</summary>
        private static VatSpyFirBoundary FindAnyVatSpyBoundaryForController(HandoffController controller, IReadOnlyList<VatSpyFirBoundary> boundaries)
        {
            foreach (var boundary in boundaries)
            {
                if (boundary.CallsignPrefixes.Any(prefix => controller.Callsign.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                    && controller.Callsign.ParseControllerTier() == ControllerTier.Center)
                {
                    return boundary;
                }
            }
            return null;
        }

        /// <summary>Issue #11, bucket 9's CTR polygon-preference ordering: every candidate (restricted to the given set) whose FIR polygon -- VATGlasses or vatspy -- contains (lat, lon) right now.</summary>
        private static HashSet<string> ContainedCtrCallsigns(
            IEnumerable<HandoffController> candidates,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            IReadOnlyList<VatSpyFirBoundary> vatSpyBoundaries,
            IReadOnlyCollection<HandoffController> allOnlineControllers,
            double lat,
            double lon)
        {
            var candidateCallsigns = new HashSet<string>(candidates.Select(c => c.Callsign), StringComparer.OrdinalIgnoreCase);

            var vatGlassesMatches = VatGlassesSectorLookup.FindContainingSectorsIgnoringAltitude(regions, lat, lon);
            var contained = ResolveContainedCallsigns(vatGlassesMatches, regions, allOnlineControllers);

            var vatSpyMatches = VatSpyBoundaryLookup.FindContainingBoundaries(vatSpyBoundaries, lat, lon);
            contained.UnionWith(ResolveVatSpyContainedCallsigns(vatSpyMatches, allOnlineControllers));

            contained.IntersectWith(candidateCallsigns);
            return contained;
        }

        /// <summary>
        /// Bucket 6 -- on-ground (AGL&lt;50ft) relevance, spanning DEL/GND/TWR/APP/CTR together
        /// since 6e's chain-walk needs them in one combined set. 6a: flight-plan match, any tier
        /// including ATIS/Other, unconditional once online. 6b/6c: DEL/GND/TWR/APP -- VATGlasses
        /// polygon containment where available, else a tight radius fallback. 6d: CTR --
        /// horizontal-only polygon containment, no radius fallback at all. 6e: chain-walk over the
        /// 6a-6d qualifying set from whatever's tuned, tie-detected into IsNext/IsLikelyNext.
        /// </summary>
        private HighlightResult ComputeGroundHighlight(
            List<HandoffController> candidates,
            IReadOnlyCollection<HandoffController> allOnlineControllers,
            string routeAirport,
            OwnshipTelemetry telemetry,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            IReadOnlyList<VatSpyFirBoundary> vatSpyBoundaries)
        {
            var result = new HighlightResult { Candidates = candidates };
            PruneDeadbandCommitted(_groundRadiusCommitted, candidates.Select(c => c.Callsign));
            PruneDeadbandCommitted(_groundPolygonContainmentCommitted, candidates.Select(c => c.Callsign));
            PruneDeadbandCommitted(_groundVatSpyContainmentCommitted, candidates.Select(c => c.Callsign));
            if (candidates.Count == 0) return result;

            var byCallsign = candidates.ToDictionary(c => c.Callsign, c => c, StringComparer.OrdinalIgnoreCase);

            // 6a.
            if (!string.IsNullOrEmpty(routeAirport))
            {
                foreach (var c in candidates)
                {
                    if (c.Callsign.StartsWith(routeAirport, StringComparison.OrdinalIgnoreCase))
                        result.HighlightedCallsigns.Add(c.Callsign);
                }
            }

            var hasPosition = telemetry.Latitude.HasValue && telemetry.Longitude.HasValue;
            var pressureAltitudeFl = telemetry.PressureAltitudeFeet / 100.0;
            var qnhTrueAltitudeFl = telemetry.PressureAltitudeFeet.HasValue && telemetry.SeaLevelPressureHpa.HasValue
                ? PressureAltitude.QnhTrueAltitudeFeet(telemetry.PressureAltitudeFeet.Value, telemetry.SeaLevelPressureHpa.Value) / 100.0
                : (double?)null;

            var polygonContainedNonCtr = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var polygonContainedCtr = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var vatSpyContainedCtr = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (hasPosition)
            {
                var altitudeAware = VatGlassesSectorLookup.FindContainingSectors(regions, telemetry.Latitude.Value, telemetry.Longitude.Value, pressureAltitudeFl, qnhTrueAltitudeFl);
                polygonContainedNonCtr = ResolveContainedCallsigns(altitudeAware, regions, allOnlineControllers);

                var horizontalOnly = VatGlassesSectorLookup.FindContainingSectorsIgnoringAltitude(regions, telemetry.Latitude.Value, telemetry.Longitude.Value);
                polygonContainedCtr = ResolveContainedCallsigns(horizontalOnly, regions, allOnlineControllers);

                // Issue #11: vatspy FIR-polygon fallback, only consulted for CTR (6d) -- vatspy has
                // no airport-level shapes, so DEL/GND/TWR/APP never gain anything from it.
                var vatSpyMatches = VatSpyBoundaryLookup.FindContainingBoundaries(vatSpyBoundaries, telemetry.Latitude.Value, telemetry.Longitude.Value);
                vatSpyContainedCtr = ResolveVatSpyContainedCallsigns(vatSpyMatches, allOnlineControllers);
            }

            // Distance for tie/order purposes only (OrderHighlightBucket's "IsLikelyNext by
            // distance" and "plain IsHighlighted by tier then distance" -- see "Sort order" in
            // docs/controller-ranking.md) -- straight-line to the controller's own reported
            // position, same source the 6b/6c radius fallback already reads below. This was
            // previously never populated at all for bucket 6, silently leaving ties/ordering at
            // whatever arbitrary order the underlying HashSet enumerated in.
            if (hasPosition)
            {
                foreach (var c in candidates) result.DistanceNm[c.Callsign] = DistanceNm(c, telemetry);
            }

            // 6b/6c/6d.
            foreach (var c in candidates)
            {
                if (result.HighlightedCallsigns.Contains(c.Callsign)) continue; // already via 6a

                switch (c.Callsign.ParseControllerTier())
                {
                    case ControllerTier.Delivery:
                    case ControllerTier.Ground:
                    case ControllerTier.Tower:
                        if ((hasPosition && PassesContainmentDeadband(_groundPolygonContainmentCommitted, c, polygonContainedNonCtr.Contains(c.Callsign), regions, allOnlineControllers, telemetry.Latitude.Value, telemetry.Longitude.Value))
                            || (hasPosition && PassesDeadband(_groundRadiusCommitted, c.Callsign, DistanceNm(c, telemetry), GroundDelGndTwrRadiusNm)))
                            result.HighlightedCallsigns.Add(c.Callsign);
                        break;
                    case ControllerTier.AppDep:
                        if ((hasPosition && PassesContainmentDeadband(_groundPolygonContainmentCommitted, c, polygonContainedNonCtr.Contains(c.Callsign), regions, allOnlineControllers, telemetry.Latitude.Value, telemetry.Longitude.Value))
                            || (hasPosition && PassesDeadband(_groundRadiusCommitted, c.Callsign, DistanceNm(c, telemetry), GroundAppRadiusNm)))
                            result.HighlightedCallsigns.Add(c.Callsign);
                        break;
                    case ControllerTier.Center:
                        if ((hasPosition && PassesContainmentDeadband(_groundPolygonContainmentCommitted, c, polygonContainedCtr.Contains(c.Callsign), regions, allOnlineControllers, telemetry.Latitude.Value, telemetry.Longitude.Value))
                            || (hasPosition && PassesVatSpyContainmentDeadband(_groundVatSpyContainmentCommitted, c, vatSpyContainedCtr.Contains(c.Callsign), vatSpyBoundaries, telemetry.Latitude.Value, telemetry.Longitude.Value)))
                            result.HighlightedCallsigns.Add(c.Callsign);
                        break;
                    default:
                        break; // Other/ATIS is fully covered by 6a alone.
                }
            }

            // 6e.
            var startRank = currentTierGroundWalkStartRank; // set by caller via field just before invocation -- see Recompute.
            var winningTierGroup = result.HighlightedCallsigns
                .Select(cs => byCallsign[cs])
                .GroupBy(c => c.Callsign.ParseControllerTier())
                .Where(g => g.Key != ControllerTier.Other && (int)g.Key > startRank)
                .OrderBy(g => (int)g.Key)
                .FirstOrDefault();

            if (winningTierGroup != null)
            {
                var winners = winningTierGroup.ToList();
                if (winners.Count == 1)
                {
                    result.NextCallsigns.Add(winners[0].Callsign);
                }
                else
                {
                    foreach (var w in winners) result.LikelyNextCallsigns.Add(w.Callsign);
                }
            }

            return result;
        }

        // Set by Recompute() immediately before calling ComputeGroundHighlight -- avoids adding yet
        // another parameter to an already-long signature for a value only 6e's chain-walk needs.
        private int currentTierGroundWalkStartRank;

        /// <summary>
        /// Bucket 7 -- airborne TWR/APP relevance. 7a: TWR, AGL&lt;10000ft, concentric radii
        /// (highlight / confident-next inner radius), wider when on the flight plan. 7b: APP/DEP,
        /// flat 30nm highlight radius regardless of flight-plan status, gated by an altitude
        /// ceiling (the sector's own upper FL + margin where VATGlasses defines one for this
        /// controller, else a flat fallback -- no lower bound at all). 7c: TWR gets confident
        /// IsNext within its inner radius (tie-detected); APP/DEP only becomes IsNext/IsLikelyNext
        /// when actually converging/"entering" (lateral only, no vertical trend check), confidence
        /// -capped to IsLikelyNext whenever not on the flight plan or multiple simultaneous
        /// entering candidates exist.
        /// </summary>
        private HighlightResult ComputeBucket7Highlight(
            List<HandoffController> candidates,
            IReadOnlyCollection<HandoffController> allOnlineControllers,
            string routeAirport,
            FlightPlan flightPlan,
            OwnshipTelemetry telemetry,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions)
        {
            var result = new HighlightResult { Candidates = candidates };
            PruneDeadbandCommitted(_appRadiusCommitted, candidates.Select(c => c.Callsign));
            if (candidates.Count == 0 || !telemetry.Latitude.HasValue || !telemetry.Longitude.HasValue) return result;

            var agl = telemetry.AltitudeAboveGroundFeet;
            var pressureAltitudeFl = telemetry.PressureAltitudeFeet / 100.0;
            var twrNextEligible = new List<HandoffController>();

            foreach (var c in candidates)
            {
                var tier = c.Callsign.ParseControllerTier();
                var onFlightPlan = !string.IsNullOrEmpty(routeAirport) && c.Callsign.StartsWith(routeAirport, StringComparison.OrdinalIgnoreCase);

                if (tier == ControllerTier.Tower)
                {
                    if (agl.GetValueOrDefault() > TwrAirborneMaxAglFeet) continue;
                    var distance = DistanceNm(c, telemetry);
                    var highlightRadius = onFlightPlan ? TwrHighlightRadiusFplnNm : TwrHighlightRadiusNonFplnNm;
                    if (distance > highlightRadius) continue;

                    result.HighlightedCallsigns.Add(c.Callsign);
                    result.DistanceNm[c.Callsign] = distance;

                    var nextRadius = onFlightPlan ? TwrNextRadiusFplnNm : TwrNextRadiusNonFplnNm;
                    if (distance <= nextRadius) twrNextEligible.Add(c);
                }
                else if (tier == ControllerTier.AppDep)
                {
                    var ceilingFl = ResolveAppCeilingFl(c, regions, allOnlineControllers);
                    if (pressureAltitudeFl.HasValue && pressureAltitudeFl.Value > ceilingFl) continue;

                    var distance = ResolveAppDistanceNm(c, regions, allOnlineControllers, telemetry);
                    if (!PassesDeadband(_appRadiusCommitted, c.Callsign, distance, AppHighlightRadiusNm)) continue;

                    result.HighlightedCallsigns.Add(c.Callsign);
                    result.DistanceNm[c.Callsign] = distance;
                }
            }

            // 7c (TWR).
            if (twrNextEligible.Count == 1)
            {
                result.NextCallsigns.Add(twrNextEligible[0].Callsign);
            }
            else if (twrNextEligible.Count > 1)
            {
                foreach (var c in twrNextEligible) result.LikelyNextCallsigns.Add(c.Callsign);
            }

            // 7c (APP/DEP) -- entering is independent of the 30nm highlight radius (a route/heading
            // convergence match can exist well beyond it), so a genuinely entering candidate is
            // added to IsHighlighted here too if it wasn't already.
            var entering = FindEnteringOwnerMatches(telemetry, flightPlan, regions, allOnlineControllers, ControllerTier.AppDep, RouteApproachMaxNauticalMiles, LateralApproachMaxNauticalMiles)
                .GroupBy(x => x.Owner.Callsign, StringComparer.OrdinalIgnoreCase)
                .Select(g => g.OrderBy(x => x.Match.DistanceNauticalMiles).First())
                .OrderBy(x => x.Match.DistanceNauticalMiles)
                .ToList();

            if (entering.Count > 0)
            {
                foreach (var (owner, match) in entering)
                {
                    result.HighlightedCallsigns.Add(owner.Callsign);
                    result.DistanceNm[owner.Callsign] = match.DistanceNauticalMiles;
                }

                var allOnFlightPlan = entering.All(x => !string.IsNullOrEmpty(routeAirport) && x.Owner.Callsign.StartsWith(routeAirport, StringComparison.OrdinalIgnoreCase));
                if (entering.Count == 1 && allOnFlightPlan)
                {
                    result.NextCallsigns.Add(entering[0].Owner.Callsign);
                }
                else
                {
                    foreach (var (owner, _) in entering) result.LikelyNextCallsigns.Add(owner.Callsign);
                }
            }

            return result;
        }

        /// <summary>Finds any VatGlasses sector level whose Owner chain resolves to this specific controller -- used to look up "the sector this online APP/DEP position is presumably responsible for" even when ownship isn't inside it (e.g. for 7b's altitude ceiling). Picks the first one found; not expected to matter in practice since an airport rarely has wildly different levels across identically-owned sectors.</summary>
        private static VatGlassesSectorLevel FindAnySectorLevelForController(
            HandoffController controller,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            IReadOnlyCollection<HandoffController> allOnlineControllers)
        {
            foreach (var region in regions.Values)
            {
                foreach (var sector in region.Airspace)
                {
                    var owners = VatGlassesOwnershipResolver.ResolveOnlineControllers(sector.Owner, region.Positions, allOnlineControllers);
                    if (owners.Any(o => string.Equals(o.Callsign, controller.Callsign, StringComparison.OrdinalIgnoreCase)))
                    {
                        return sector.Levels.FirstOrDefault();
                    }
                }
            }
            return null;
        }

        private static double ResolveAppCeilingFl(HandoffController controller, IReadOnlyDictionary<string, VatGlassesRegionData> regions, IReadOnlyCollection<HandoffController> allOnlineControllers)
        {
            var level = FindAnySectorLevelForController(controller, regions, allOnlineControllers);
            if (level?.MaxFlightLevel != null) return level.MaxFlightLevel.Value + AppCeilingMarginFl;
            return AppCeilingFallbackFl;
        }

        /// <summary>
        /// Distance for 7b's highlight radius -- approximated via the resolved sector's own
        /// precomputed bounding box where one exists for this controller (VatGlassesSectorLookup
        /// exposes no general nearest-point-on-polygon function, only direction-gated route/
        /// heading convergence checks, which aren't appropriate for a plain proximity check), else
        /// straight-line distance to the controller's own reported position.
        /// </summary>
        private static double ResolveAppDistanceNm(HandoffController controller, IReadOnlyDictionary<string, VatGlassesRegionData> regions, IReadOnlyCollection<HandoffController> allOnlineControllers, OwnshipTelemetry telemetry)
        {
            var level = FindAnySectorLevelForController(controller, regions, allOnlineControllers);
            if (level != null)
            {
                var clampedLat = Math.Max(level.MinLatitude, Math.Min(level.MaxLatitude, telemetry.Latitude.Value));
                var clampedLon = Math.Max(level.MinLongitude, Math.Min(level.MaxLongitude, telemetry.Longitude.Value));
                return GeoDistance.NauticalMiles(telemetry.Latitude.Value, telemetry.Longitude.Value, clampedLat, clampedLon);
            }
            return GeoDistance.NauticalMiles(telemetry.Latitude.Value, telemetry.Longitude.Value, controller.Latitude, controller.Longitude);
        }

        /// <summary>Shared route/heading-projected "entering" search used by both bucket 7c (APP/DEP) and bucket 8 (CTR) -- walks approach matches nearest-first, resolves each to every online controller matching its chain (see VatGlassesOwnershipResolver -- more than one is possible for an ambiguous same-prefix/tier chain, deliberately not collapsed here so downstream tie-detection sees all of them), and keeps only ones matching tierFilter.</summary>
        private List<(HandoffController Owner, VatGlassesSectorLookup.VatGlassesApproachMatch Match)> FindEnteringOwnerMatches(
            OwnshipTelemetry telemetry,
            FlightPlan flightPlan,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            IReadOnlyCollection<HandoffController> allOnlineControllers,
            ControllerTier tierFilter,
            double routeMaxNm,
            double headingMaxNm)
        {
            var result = new List<(HandoffController, VatGlassesSectorLookup.VatGlassesApproachMatch)>();
            if (!telemetry.Latitude.HasValue || !telemetry.Longitude.HasValue) return result;

            IReadOnlyList<VatGlassesSectorLookup.VatGlassesApproachMatch> approachMatches;
            var remainingWaypoints = _routeInvalidatedByDiversion
                ? new List<FlightPlanWaypoint>()
                : RemainingWaypoints(flightPlan, telemetry.Latitude.Value, telemetry.Longitude.Value);

            if (remainingWaypoints.Count > 0)
            {
                approachMatches = VatGlassesSectorLookup.FindApproachingSectorsAlongRoute(regions, telemetry.Latitude.Value, telemetry.Longitude.Value, remainingWaypoints, routeMaxNm);
            }
            else if (telemetry.HeadingDegrees.HasValue)
            {
                approachMatches = VatGlassesSectorLookup.FindApproachingSectorsAlongHeading(regions, telemetry.Latitude.Value, telemetry.Longitude.Value, telemetry.HeadingDegrees.Value, headingMaxNm);
            }
            else
            {
                return result;
            }

            foreach (var approach in approachMatches)
            {
                if (!regions.TryGetValue(approach.Match.RegionFileName, out var region)) continue;
                foreach (var owner in VatGlassesOwnershipResolver.ResolveOnlineControllers(approach.Match.Sector.Owner, region.Positions, allOnlineControllers))
                {
                    if (owner.Callsign.ParseControllerTier() != tierFilter) continue;
                    result.Add((owner, approach));
                }
            }
            return result;
        }

        /// <summary>
        /// Issue #11: vatspy equivalent of FindEnteringOwnerMatches, CTR-only (vatspy has no
        /// APP/DEP-level polygons at all, see docs/controller-ranking.md) -- used only where
        /// VATGlasses has no sector data for a given candidate at all (callers gate on
        /// FindAnySectorLevelForController returning null), same precedence rule as the
        /// "satisfied" fallback.
        /// </summary>
        private List<(HandoffController Owner, VatSpyBoundaryLookup.VatSpyApproachMatch Match)> FindVatSpyEnteringOwnerMatches(
            OwnshipTelemetry telemetry,
            FlightPlan flightPlan,
            IReadOnlyList<VatSpyFirBoundary> vatSpyBoundaries,
            IReadOnlyCollection<HandoffController> allOnlineControllers,
            double routeMaxNm,
            double headingMaxNm)
        {
            var result = new List<(HandoffController, VatSpyBoundaryLookup.VatSpyApproachMatch)>();
            if (!telemetry.Latitude.HasValue || !telemetry.Longitude.HasValue) return result;

            IReadOnlyList<VatSpyBoundaryLookup.VatSpyApproachMatch> approachMatches;
            var remainingWaypoints = _routeInvalidatedByDiversion
                ? new List<FlightPlanWaypoint>()
                : RemainingWaypoints(flightPlan, telemetry.Latitude.Value, telemetry.Longitude.Value);

            if (remainingWaypoints.Count > 0)
            {
                approachMatches = VatSpyBoundaryLookup.FindApproachingBoundariesAlongRoute(vatSpyBoundaries, telemetry.Latitude.Value, telemetry.Longitude.Value, remainingWaypoints, routeMaxNm);
            }
            else if (telemetry.HeadingDegrees.HasValue)
            {
                approachMatches = VatSpyBoundaryLookup.FindApproachingBoundariesAlongHeading(vatSpyBoundaries, telemetry.Latitude.Value, telemetry.Longitude.Value, telemetry.HeadingDegrees.Value, headingMaxNm);
            }
            else
            {
                return result;
            }

            foreach (var approach in approachMatches)
            {
                foreach (var owner in VatSpyOwnershipResolver.ResolveOnlineControllers(approach.Boundary, allOnlineControllers))
                {
                    result.Add((owner, approach));
                }
            }
            return result;
        }

        /// <summary>
        /// Bucket 8 -- airborne CTR relevance. 8a: lateral route/heading convergence (150nm/100nm,
        /// same as before) AND vertical -- either satisfied (already in the band, any flight
        /// state, no margin) or converging (sustained climb/descent trend within a widened 5000ft
        /// of the band edge). No VATGlasses geometry for a given CTR -- neither flag, full stop.
        /// 8b: band-anchor tie -- the single closest qualifying candidate is the anchor; everyone
        /// within anchor x 1.10 of it ties with it (confident IsNext alone, IsLikelyNext as a
        /// group otherwise), rather than only the strict closest ever counting.
        /// </summary>
        private HighlightResult ComputeBucket8Highlight(
            List<HandoffController> candidates,
            IReadOnlyCollection<HandoffController> allOnlineControllers,
            FlightPlan flightPlan,
            OwnshipTelemetry telemetry,
            double? pressureAltitudeFl,
            double? qnhTrueAltitudeFl,
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            IReadOnlyList<VatSpyFirBoundary> vatSpyBoundaries,
            HashSet<string> currentCallsigns)
        {
            var result = new HighlightResult { Candidates = candidates };
            PruneDeadbandCommitted(_tieBandCommitted, candidates.Select(c => c.Callsign));
            // Pruned against every online controller, not just this bucket's candidates -- "satisfied"
            // resolves ownership against allOnlineControllers directly (see below), independent of
            // bucketCandidates' own exclusions, so a committed callsign should only actually drop
            // once it's genuinely offline, not merely excluded from this tick's candidate list.
            PruneDeadbandCommitted(_ctrSatisfiedCommitted, allOnlineControllers.Select(c => c.Callsign));
            PruneDeadbandCommitted(_ctrVatSpySatisfiedCommitted, allOnlineControllers.Select(c => c.Callsign));
            if (!telemetry.Latitude.HasValue || !telemetry.Longitude.HasValue) return result;

            int verticalTrendSign;
            DateTimeOffset verticalTrendSince;
            lock (_gate) { verticalTrendSign = _verticalTrendSign; verticalTrendSince = _verticalTrendSince; }
            var sustainedTrend = verticalTrendSign != 0 && _now() - verticalTrendSince >= VerticalTrendSustainWindow;

            var containingMatches = VatGlassesSectorLookup.FindContainingSectors(regions, telemetry.Latitude.Value, telemetry.Longitude.Value, pressureAltitudeFl, qnhTrueAltitudeFl);

            var combined = new List<(HandoffController Owner, double DistanceNm)>();

            // "Satisfied" -- already inside the band, regardless of level/climbing/descending.
            var containedCallsigns = ResolveContainedCallsigns(containingMatches, regions, allOnlineControllers);
            foreach (var callsign in containedCallsigns)
            {
                if (currentCallsigns.Contains(callsign)) continue;
                var owner = allOnlineControllers.FirstOrDefault(c => string.Equals(c.Callsign, callsign, StringComparison.OrdinalIgnoreCase));
                if (owner == null || owner.Callsign.ParseControllerTier() != ControllerTier.Center) continue;
                _ctrSatisfiedCommitted.Add(owner.Callsign);
                combined.Add((owner, 0));
            }

            // Dead-band: a previously-satisfied controller that's no longer genuinely contained
            // this tick stays included until it's clearly past the boundary edge (
            // PolygonContainmentDeadbandMarginNm), not the instant containment flips -- same
            // guard against edge-flapping as bucket 6b/6c/6d's ground containment.
            foreach (var callsign in _ctrSatisfiedCommitted.Where(cs => !containedCallsigns.Contains(cs)).ToList())
            {
                if (currentCallsigns.Contains(callsign)) { _ctrSatisfiedCommitted.Remove(callsign); continue; }
                var owner = allOnlineControllers.FirstOrDefault(c => string.Equals(c.Callsign, callsign, StringComparison.OrdinalIgnoreCase));
                if (owner == null) { _ctrSatisfiedCommitted.Remove(callsign); continue; }
                var level = FindAnySectorLevelForController(owner, regions, allOnlineControllers);
                var staysIn = level != null && VatGlassesSectorLookup.DistanceToPolygonBoundaryNm(telemetry.Latitude.Value, telemetry.Longitude.Value, level) <= PolygonContainmentDeadbandMarginNm;
                if (staysIn) combined.Add((owner, 0)); else _ctrSatisfiedCommitted.Remove(callsign);
            }

            // Issue #11: vatspy "satisfied" fallback -- only for CTR controllers VATGlasses has no
            // sector data for at all (FindAnySectorLevelForController null), same precedence rule
            // as bucket 6d. No vertical band to check at all (top-down coverage, same rationale as
            // 6d's CTR containment).
            var vatSpyContainingMatches = VatSpyBoundaryLookup.FindContainingBoundaries(vatSpyBoundaries, telemetry.Latitude.Value, telemetry.Longitude.Value);
            var vatSpyContainedCallsigns = ResolveVatSpyContainedCallsigns(vatSpyContainingMatches, allOnlineControllers);
            foreach (var callsign in vatSpyContainedCallsigns)
            {
                if (currentCallsigns.Contains(callsign) || containedCallsigns.Contains(callsign)) continue;
                var owner = allOnlineControllers.FirstOrDefault(c => string.Equals(c.Callsign, callsign, StringComparison.OrdinalIgnoreCase));
                if (owner == null || owner.Callsign.ParseControllerTier() != ControllerTier.Center) continue;
                if (FindAnySectorLevelForController(owner, regions, allOnlineControllers) != null) continue;
                _ctrVatSpySatisfiedCommitted.Add(owner.Callsign);
                combined.Add((owner, 0));
            }

            foreach (var callsign in _ctrVatSpySatisfiedCommitted.Where(cs => !vatSpyContainedCallsigns.Contains(cs)).ToList())
            {
                if (currentCallsigns.Contains(callsign)) { _ctrVatSpySatisfiedCommitted.Remove(callsign); continue; }
                var owner = allOnlineControllers.FirstOrDefault(c => string.Equals(c.Callsign, callsign, StringComparison.OrdinalIgnoreCase));
                if (owner == null) { _ctrVatSpySatisfiedCommitted.Remove(callsign); continue; }
                var boundary = FindAnyVatSpyBoundaryForController(owner, vatSpyBoundaries);
                var staysIn = boundary != null && VatSpyBoundaryLookup.DistanceToBoundaryNm(telemetry.Latitude.Value, telemetry.Longitude.Value, boundary) <= PolygonContainmentDeadbandMarginNm;
                if (staysIn) combined.Add((owner, 0)); else _ctrVatSpySatisfiedCommitted.Remove(callsign);
            }

            // "Converging" -- lateral entering AND vertical satisfied-or-converging.
            foreach (var (owner, approach) in FindEnteringOwnerMatches(telemetry, flightPlan, regions, allOnlineControllers, ControllerTier.Center, RouteApproachMaxNauticalMiles, LateralApproachMaxNauticalMiles))
            {
                if (currentCallsigns.Contains(owner.Callsign)) continue;
                if (containingMatches.Any(m => ReferenceEquals(m.Level, approach.Match.Level))) continue; // already counted as "satisfied" above
                if (!IsVerticallySatisfiedOrConverging(approach.Match.Level, pressureAltitudeFl, qnhTrueAltitudeFl, verticalTrendSign, sustainedTrend)) continue;
                combined.Add((owner, approach.DistanceNauticalMiles));
            }

            // Issue #11: vatspy "converging" fallback, same VATGlasses-has-no-data precedence gate
            // as the satisfied fallback above. Vertical is trivially satisfied -- no band data to
            // check against at all.
            foreach (var (owner, approach) in FindVatSpyEnteringOwnerMatches(telemetry, flightPlan, vatSpyBoundaries, allOnlineControllers, RouteApproachMaxNauticalMiles, LateralApproachMaxNauticalMiles))
            {
                if (currentCallsigns.Contains(owner.Callsign)) continue;
                if (containedCallsigns.Contains(owner.Callsign) || vatSpyContainedCallsigns.Contains(owner.Callsign)) continue; // already counted as "satisfied"
                if (FindAnySectorLevelForController(owner, regions, allOnlineControllers) != null) continue;
                combined.Add((owner, approach.DistanceNauticalMiles));
            }

            var deduped = combined
                .GroupBy(x => x.Owner.Callsign, StringComparer.OrdinalIgnoreCase)
                .Select(g => g.OrderBy(x => x.DistanceNm).First())
                .OrderBy(x => x.DistanceNm)
                .ToList();

            if (deduped.Count == 0) return result;

            var anchorDistance = deduped[0].DistanceNm;
            var band = deduped.Where(x => PassesDeadband(_tieBandCommitted, x.Owner.Callsign, x.DistanceNm, anchorDistance * TieBandMultiplier)).ToList();

            foreach (var (owner, distance) in band)
            {
                result.HighlightedCallsigns.Add(owner.Callsign);
                result.DistanceNm[owner.Callsign] = distance;
            }

            if (band.Count == 1)
            {
                result.NextCallsigns.Add(band[0].Owner.Callsign);
            }
            else
            {
                foreach (var (owner, _) in band) result.LikelyNextCallsigns.Add(owner.Callsign);
            }

            return result;
        }

        /// <summary>True if ownship's altitude is already inside level's band (satisfied), or outside it but a sustained climb/descent trend brings it within VerticalApproachThresholdFeet of the edge it's headed toward (converging).</summary>
        private static bool IsVerticallySatisfiedOrConverging(VatGlassesSectorLevel level, double? pressureAltitudeFl, double? qnhTrueAltitudeFl, int verticalTrendSign, bool sustainedTrend)
        {
            var useQnh = level.MaxFlightLevel.HasValue && level.MaxFlightLevel.Value <= VatGlassesSectorLookup.TransitionLevelFallbackFl;
            var altitudeFl = useQnh ? qnhTrueAltitudeFl : pressureAltitudeFl;
            if (!altitudeFl.HasValue) return false;

            var min = level.MinFlightLevel;
            var max = level.MaxFlightLevel;
            var insideBand = (!min.HasValue || altitudeFl.Value >= min.Value) && (!max.HasValue || altitudeFl.Value <= max.Value);
            if (insideBand) return true;

            if (!sustainedTrend) return false;

            var altitudeFeet = altitudeFl.Value * 100.0;
            if (verticalTrendSign < 0 && max.HasValue)
            {
                var maxFeet = max.Value * 100.0;
                return altitudeFeet > maxFeet && altitudeFeet - maxFeet <= VerticalApproachThresholdFeet;
            }
            if (verticalTrendSign > 0 && min.HasValue)
            {
                var minFeet = min.Value * 100.0;
                return altitudeFeet < minFeet && minFeet - altitudeFeet <= VerticalApproachThresholdFeet;
            }
            return false;
        }

        /// <summary>Bucket 8c -- ETA to the closest bucket-8-qualifying CTR sector. Independent of IsHighlighted/IsNext/IsLikelyNext -- available during level flight (any altitude) or climbing/descending above FL150, null otherwise.</summary>
        private double? ComputeEtaMinutes(OwnshipTelemetry telemetry, HighlightResult bucket8Result)
        {
            if (bucket8Result.DistanceNm.Count == 0) return null;

            var isLevel = Math.Abs(telemetry.VerticalSpeedFpm.GetValueOrDefault()) < VerticalTrendThresholdFpm;
            var pressureAltitudeFl = telemetry.PressureAltitudeFeet / 100.0;
            var eligible = isLevel || (pressureAltitudeFl.HasValue && pressureAltitudeFl.Value > EtaClimbDescendMinFl);
            if (!eligible) return null;

            var groundSpeed = telemetry.GroundSpeedKnots;
            if (!groundSpeed.HasValue || groundSpeed.Value <= 1) return null;

            var closestDistance = bucket8Result.DistanceNm.Values.Min();
            return closestDistance / groundSpeed.Value * 60.0;
        }

        /// <summary>Within one bucket 6/7/8 block: IsNext first, then IsLikelyNext by distance only (ties are guaranteed same-tier by construction), then plain IsHighlighted by chain tier then distance.</summary>
        private static IEnumerable<HandoffController> OrderHighlightBucket(HighlightResult block)
        {
            var byCallsign = block.Candidates.ToDictionary(c => c.Callsign, c => c, StringComparer.OrdinalIgnoreCase);

            var next = block.NextCallsigns.Select(cs => byCallsign[cs]);
            var likelyNext = block.LikelyNextCallsigns
                .Select(cs => byCallsign[cs])
                .OrderBy(c => block.DistanceNm.TryGetValue(c.Callsign, out var d) ? d : double.MaxValue);
            var highlightedOnly = block.HighlightedCallsigns
                .Where(cs => !block.NextCallsigns.Contains(cs) && !block.LikelyNextCallsigns.Contains(cs))
                .Select(cs => byCallsign[cs])
                .OrderBy(c => c.Callsign.ParseControllerTier())
                .ThenBy(c => block.DistanceNm.TryGetValue(c.Callsign, out var d) ? d : double.MaxValue);

            return next.Concat(likelyNext).Concat(highlightedOnly);
        }

        /// <summary>
        /// The nearest SimBrief waypoint to ownship's current position, plus everything after it
        /// in route order -- the "remaining planned track" used by the route-projected approach
        /// checks (bucket 7c/8's FindEnteringOwnerMatches). No persistent "last passed waypoint"
        /// state -- recomputed fresh every tick, safe in practice since point-to-point routes
        /// don't double back near an earlier waypoint.
        ///
        /// A direct-to breaks that assumption (flight-test feedback, issue #17): cutting a corner
        /// can pass close enough to a *skipped* waypoint that it reads as "nearest" even though
        /// ownship is no longer flying to it, projecting the remaining route through a stale leg.
        /// A heading-vs-bearing-to-waypoint check (skip forward if more than 90 degrees off) was
        /// tried and rejected -- it breaks holding patterns, where heading legitimately sweeps
        /// through the full 360 degrees every circuit. A real fix needs sustained-disagreement
        /// state (similar to _verticalTrendSign/HysteresisWindow elsewhere in this class) to tell
        /// "genuinely passed via direct-to" apart from "briefly pointed away mid-turn" -- left as
        /// a known limitation, not attempted here.
        /// </summary>
        private static List<FlightPlanWaypoint> RemainingWaypoints(FlightPlan flightPlan, double lat, double lon)
        {
            var all = flightPlan.Waypoints;
            if (all == null || all.Count == 0) return new List<FlightPlanWaypoint>();

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

        private void Log(string message)
        {
            var line = "ControllerRankingModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
