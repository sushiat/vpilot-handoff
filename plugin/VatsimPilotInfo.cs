namespace Handoff.Plugin
{
    /// <summary>
    /// A pilot's filed VATSIM flight plan, sourced from the public VATSIM data feed's pilots[]
    /// section (IBroker has no flight-plan members at all -- see CLAUDE.md). Looking up the
    /// plugin's own callsign (from PilotSessionModel) in this gives the actual filed
    /// callsign/departure/arrival, for cross-checking against the SimBrief-derived FlightPlanModel
    /// -- the two can drift (a stale OFP, a manually-changed connect callsign, a different
    /// aircraft), and the pilot should see that, not silently trust whichever one loaded first.
    /// </summary>
    public sealed class VatsimPilotInfo
    {
        public string Callsign { get; }
        public string Departure { get; }
        public string Arrival { get; }
        // The feed's cid for whoever is actually flying this callsign right now -- compared
        // against PilotSessionModel.Cid (our own live connection's cid) so a callsign lookup that
        // happens to land on a different pilot (a stale feed snapshot mid-reconnect, a callsign
        // collision window) doesn't get silently trusted as "our" filed plan. String, matching
        // PilotSessionModel.Cid's type, so the two can be compared directly without a parse step.
        public string Cid { get; }

        public VatsimPilotInfo(string callsign, string departure, string arrival, string cid = null)
        {
            Callsign = callsign;
            Departure = departure;
            Arrival = arrival;
            Cid = cid;
        }
    }
}
