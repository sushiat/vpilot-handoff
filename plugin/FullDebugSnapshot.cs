using System;
using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// The complete debug snapshot file (issue #65 section 4) -- everything DebugSnapshotService
    /// gathers from every subsystem model at the instant `saveDebugSnapshot` is received.
    /// Deliberately more than what's on the wire even in debug mode (RankedController.DebugExplain/
    /// RankingDebugExplain), since a snapshot is meant to stand completely alone for offline
    /// analysis, without needing the live session it was taken from.
    /// </summary>
    public sealed class FullDebugSnapshot
    {
        public string SnapshotId { get; }
        public DateTimeOffset TimestampUtc { get; }
        public string PluginVersion { get; }
        public string AppVersion { get; }
        // Issue #73b -- pilot-chosen label attached after the fact via nameDebugSnapshot; null for
        // never-named snapshots (the normal case at write time -- SaveSnapshot always passes null
        // here, DebugSnapshotService.RenameSnapshot patches it into the already-written JSON
        // in-place rather than reconstructing this object).
        public string Name { get; }

        public string VatsimCallsign { get; }
        public string VatsimCid { get; }

        public IReadOnlyList<RankedController> ComputedControllers { get; }
        public RankingDebugExplain RankingContext { get; }
        public RankingSnapshot Ranking { get; }

        public ControllerStateDebugSnapshot ControllerState { get; }
        public RadioDebugSnapshot Radio { get; }
        public VatsimFeedDebugSnapshot VatsimFeed { get; }
        public FlightPlanDebugSnapshot FlightPlan { get; }
        public VatGlassesDebugSnapshot VatGlasses { get; }
        public VatSpyDebugSnapshot VatSpy { get; }
        public PairingDebugSnapshot Pairing { get; }
        public int AuthenticatedSocketCount { get; }
        public IReadOnlyDictionary<string, string> ActiveOperations { get; }

        public FullDebugSnapshot(
            string snapshotId, DateTimeOffset timestampUtc, string pluginVersion, string appVersion, string name,
            string vatsimCallsign, string vatsimCid,
            IReadOnlyList<RankedController> computedControllers, RankingDebugExplain rankingContext, RankingSnapshot ranking,
            ControllerStateDebugSnapshot controllerState, RadioDebugSnapshot radio, VatsimFeedDebugSnapshot vatsimFeed,
            FlightPlanDebugSnapshot flightPlan, VatGlassesDebugSnapshot vatGlasses, VatSpyDebugSnapshot vatSpy,
            PairingDebugSnapshot pairing, int authenticatedSocketCount, IReadOnlyDictionary<string, string> activeOperations)
        {
            SnapshotId = snapshotId;
            TimestampUtc = timestampUtc;
            PluginVersion = pluginVersion;
            AppVersion = appVersion;
            Name = name;
            VatsimCallsign = vatsimCallsign;
            VatsimCid = vatsimCid;
            ComputedControllers = computedControllers;
            RankingContext = rankingContext;
            Ranking = ranking;
            ControllerState = controllerState;
            Radio = radio;
            VatsimFeed = vatsimFeed;
            FlightPlan = flightPlan;
            VatGlasses = vatGlasses;
            VatSpy = vatSpy;
            Pairing = pairing;
            AuthenticatedSocketCount = authenticatedSocketCount;
            ActiveOperations = activeOperations;
        }
    }
}
