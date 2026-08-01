using System;
using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>One waypoint's along-track projection breakdown, as computed by
    /// ControllerRankingModel.SequenceRemainingWaypoints -- included for every waypoint from
    /// the committed index onward, not just the active one, since the whole point of a debug
    /// snapshot is seeing the sweep's raw per-waypoint math (issue #66's stuck-sequencer bug
    /// showed up exactly here: alongTrackNm staying short of legDistanceNm for a waypoint
    /// ownship had obviously already overflown).</summary>
    public sealed class RankingSnapshotWaypoint
    {
        public string Ident { get; }
        public double Latitude { get; }
        public double Longitude { get; }
        public double LegDistanceNm { get; }
        public double AlongTrackNm { get; }

        public RankingSnapshotWaypoint(string ident, double latitude, double longitude, double legDistanceNm, double alongTrackNm)
        {
            Ident = ident;
            Latitude = latitude;
            Longitude = longitude;
            LegDistanceNm = legDistanceNm;
            AlongTrackNm = alongTrackNm;
        }
    }

    /// <summary>One tier's tier-chain-walk hysteresis state (bucket 9's ApplyDistanceHysteresis) -- committed leader plus, if any, a pending challenger not yet promoted.</summary>
    public sealed class RankingSnapshotHysteresisEntry
    {
        public string Tier { get; }
        public string CommittedLeader { get; }
        public string PendingChallenger { get; }
        public DateTimeOffset? PendingSince { get; }

        public RankingSnapshotHysteresisEntry(string tier, string committedLeader, string pendingChallenger, DateTimeOffset? pendingSince)
        {
            Tier = tier;
            CommittedLeader = committedLeader;
            PendingChallenger = pendingChallenger;
            PendingSince = pendingSince;
        }
    }

    /// <summary>
    /// Full internal-state dump of ControllerRankingModel (issue #65 section 4) -- reads private
    /// sequencer/hysteresis fields directly, the same way any other debugger would, rather than
    /// promoting them to a wire contract. Only ever produced on demand (saveDebugSnapshot), not
    /// part of the ~1s controllers broadcast.
    /// </summary>
    public sealed class RankingSnapshot
    {
        public double? RouteAnchorLatitude { get; }
        public double? RouteAnchorLongitude { get; }
        public int CommittedWaypointIndex { get; }
        public int? PendingWaypointIndex { get; }
        public string PendingWaypointName { get; }
        public DateTimeOffset? PendingWaypointSince { get; }
        public int NaturalWaypointIndex { get; }
        public IReadOnlyList<RankingSnapshotWaypoint> RemainingWaypointProjection { get; }
        public bool RouteInvalidatedByDiversion { get; }
        public string PendingDiversionDestination { get; }
        public IReadOnlyList<RankingSnapshotHysteresisEntry> TierChainHysteresis { get; }
        public double? EtaMinutes { get; }
        public string EtaCalculationDetail { get; }
        // Issue #73c -- mirrors RankingDebugExplain.LastWaypointAdvanceMechanism/At (see
        // ControllerRankingModel's WaypointAdvanceMechanism* constants); the live-view and
        // snapshot-file copies of the same underlying model fields.
        public string LastWaypointAdvanceMechanism { get; }
        public DateTimeOffset? LastWaypointAdvanceAt { get; }

        public RankingSnapshot(
            double? routeAnchorLatitude, double? routeAnchorLongitude,
            int committedWaypointIndex, int? pendingWaypointIndex, string pendingWaypointName, DateTimeOffset? pendingWaypointSince,
            int naturalWaypointIndex, IReadOnlyList<RankingSnapshotWaypoint> remainingWaypointProjection,
            bool routeInvalidatedByDiversion, string pendingDiversionDestination,
            IReadOnlyList<RankingSnapshotHysteresisEntry> tierChainHysteresis,
            double? etaMinutes, string etaCalculationDetail,
            string lastWaypointAdvanceMechanism, DateTimeOffset? lastWaypointAdvanceAt)
        {
            RouteAnchorLatitude = routeAnchorLatitude;
            RouteAnchorLongitude = routeAnchorLongitude;
            CommittedWaypointIndex = committedWaypointIndex;
            PendingWaypointIndex = pendingWaypointIndex;
            PendingWaypointName = pendingWaypointName;
            PendingWaypointSince = pendingWaypointSince;
            NaturalWaypointIndex = naturalWaypointIndex;
            RemainingWaypointProjection = remainingWaypointProjection;
            RouteInvalidatedByDiversion = routeInvalidatedByDiversion;
            PendingDiversionDestination = pendingDiversionDestination;
            TierChainHysteresis = tierChainHysteresis;
            EtaMinutes = etaMinutes;
            EtaCalculationDetail = etaCalculationDetail;
            LastWaypointAdvanceMechanism = lastWaypointAdvanceMechanism;
            LastWaypointAdvanceAt = lastWaypointAdvanceAt;
        }
    }
}
