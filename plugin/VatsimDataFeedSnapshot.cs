using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// One poll's worth of the public VATSIM data feed, both sections parsed from the single
    /// fetched JSON body -- controllers[] (existing enrichment) and pilots[] (filed flight plans,
    /// for cross-checking the plugin's own callsign against SimBrief). Kept as one snapshot
    /// rather than two separate fetches so a 15s poll cycle only ever costs one HTTP request.
    /// </summary>
    public sealed class VatsimDataFeedSnapshot
    {
        public IReadOnlyList<VatsimControllerInfo> Controllers { get; }
        public IReadOnlyList<VatsimPilotInfo> Pilots { get; }

        public VatsimDataFeedSnapshot(IReadOnlyList<VatsimControllerInfo> controllers, IReadOnlyList<VatsimPilotInfo> pilots)
        {
            Controllers = controllers;
            Pilots = pilots;
        }
    }
}
