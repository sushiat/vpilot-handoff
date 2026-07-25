namespace Handoff.Plugin
{
    /// <summary>
    /// Immutable snapshot of the pilot's filed flight plan, as last fetched from the SimBrief
    /// API (IBroker has no flight-plan members at all -- see CLAUDE.md / issue #1). All fields
    /// are null until the first successful fetch.
    /// </summary>
    public sealed class FlightPlan
    {
        public string Callsign { get; }
        public string Origin { get; }
        public string Destination { get; }
        public string Alternate { get; }

        public FlightPlan(string callsign, string origin, string destination, string alternate)
        {
            Callsign = callsign;
            Origin = origin;
            Destination = destination;
            Alternate = alternate;
        }

        public static readonly FlightPlan Empty = new FlightPlan(null, null, null, null);
    }
}
