using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Per-controller "why did this land here" explain data (issue #65), only ever populated
    /// when <see cref="ControllerRankingModel.DebugModeEnabled"/> is true -- see
    /// ControllerRankingModel.BuildControllerExplain. Deliberately plain-language/summary shaped
    /// (see docs/controller-ranking.md's "Debug explain view" section) -- the raw internals
    /// (route anchor coordinates, VATGlasses/vatspy sector ids, tie-band math) live only in the
    /// debug snapshot file (RankingSnapshot), not here.
    /// </summary>
    public sealed class ControllerDebugExplain
    {
        public int Bucket { get; }
        public string BucketName { get; }
        public string Reason { get; }
        public double? DistanceNm { get; }
        public bool VatGlassesSectorMatch { get; }
        public bool VatSpyPolygonMatch { get; }
        public bool RouteMatch { get; }
        public string HysteresisState { get; }
        public int? HysteresisPendingBucket { get; }
        public DateTimeOffset? HysteresisPendingSince { get; }
        public int? CandidateRank { get; }

        public ControllerDebugExplain(
            int bucket, string bucketName, string reason, double? distanceNm,
            bool vatGlassesSectorMatch, bool vatSpyPolygonMatch, bool routeMatch,
            string hysteresisState, int? hysteresisPendingBucket, DateTimeOffset? hysteresisPendingSince,
            int? candidateRank)
        {
            Bucket = bucket;
            BucketName = bucketName;
            Reason = reason;
            DistanceNm = distanceNm;
            VatGlassesSectorMatch = vatGlassesSectorMatch;
            VatSpyPolygonMatch = vatSpyPolygonMatch;
            RouteMatch = routeMatch;
            HysteresisState = hysteresisState;
            HysteresisPendingBucket = hysteresisPendingBucket;
            HysteresisPendingSince = hysteresisPendingSince;
            CandidateRank = candidateRank;
        }
    }

    /// <summary>Plugin-wide debug context per docs/protocol.md's top-level `controllers.debug` object -- the plain-language state per-controller reasons are evaluated against.</summary>
    public sealed class RankingDebugExplain
    {
        public string PhaseOfFlight { get; }
        public bool HasTakenOffThisSession { get; }
        public double? OwnshipLatitude { get; }
        public double? OwnshipLongitude { get; }
        public double? OwnshipAltitudeTrue { get; }
        public double? OwnshipAltitudeAgl { get; }
        public double? OwnshipGroundspeedKt { get; }
        public double? OwnshipHeadingTrue { get; }
        public double? OwnshipTrackTrue { get; }
        public string Com1TunedCallsign { get; }
        public string Com2TunedCallsign { get; }
        public string ActiveRouteWaypoint { get; }
        public string LastPassedWaypoint { get; }
        // Bearing (true, 0-360)/distance from ownship's current position to each named waypoint
        // above -- a cheap "does this look right" sanity check, null whenever ownship's position
        // isn't known yet. See ControllerRankingModel.Recompute's debug-explain block.
        public double? ActiveRouteWaypointBearingTrue { get; }
        public double? ActiveRouteWaypointDistanceNm { get; }
        public double? LastPassedWaypointBearingTrue { get; }
        public double? LastPassedWaypointDistanceNm { get; }
        public string EtaCalculationDetail { get; }

        public RankingDebugExplain(
            string phaseOfFlight, bool hasTakenOffThisSession,
            double? ownshipLatitude, double? ownshipLongitude, double? ownshipAltitudeTrue, double? ownshipAltitudeAgl,
            double? ownshipGroundspeedKt, double? ownshipHeadingTrue, double? ownshipTrackTrue,
            string com1TunedCallsign, string com2TunedCallsign,
            string activeRouteWaypoint, string lastPassedWaypoint,
            double? activeRouteWaypointBearingTrue, double? activeRouteWaypointDistanceNm,
            double? lastPassedWaypointBearingTrue, double? lastPassedWaypointDistanceNm,
            string etaCalculationDetail)
        {
            PhaseOfFlight = phaseOfFlight;
            HasTakenOffThisSession = hasTakenOffThisSession;
            OwnshipLatitude = ownshipLatitude;
            OwnshipLongitude = ownshipLongitude;
            OwnshipAltitudeTrue = ownshipAltitudeTrue;
            OwnshipAltitudeAgl = ownshipAltitudeAgl;
            OwnshipGroundspeedKt = ownshipGroundspeedKt;
            OwnshipHeadingTrue = ownshipHeadingTrue;
            OwnshipTrackTrue = ownshipTrackTrue;
            Com1TunedCallsign = com1TunedCallsign;
            Com2TunedCallsign = com2TunedCallsign;
            ActiveRouteWaypoint = activeRouteWaypoint;
            LastPassedWaypoint = lastPassedWaypoint;
            ActiveRouteWaypointBearingTrue = activeRouteWaypointBearingTrue;
            ActiveRouteWaypointDistanceNm = activeRouteWaypointDistanceNm;
            LastPassedWaypointBearingTrue = lastPassedWaypointBearingTrue;
            LastPassedWaypointDistanceNm = lastPassedWaypointDistanceNm;
            EtaCalculationDetail = etaCalculationDetail;
        }
    }
}
