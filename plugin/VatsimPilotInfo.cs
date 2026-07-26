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

        public VatsimPilotInfo(string callsign, string departure, string arrival)
        {
            Callsign = callsign;
            Departure = departure;
            Arrival = arrival;
        }
    }
}
