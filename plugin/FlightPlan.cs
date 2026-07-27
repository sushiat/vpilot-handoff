using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>One route waypoint from the SimBrief OFP's navlog, decimal-degree lat/lon (unlike VATGlasses' DMS format -- SimBrief's JSON gives these directly). See issue #9 phase 2.</summary>
    public sealed class FlightPlanWaypoint
    {
        public string Ident { get; }
        public double Latitude { get; }
        public double Longitude { get; }

        public FlightPlanWaypoint(string ident, double latitude, double longitude)
        {
            Ident = ident;
            Latitude = latitude;
            Longitude = longitude;
        }
    }

    /// <summary>
    /// Immutable snapshot of the pilot's filed flight plan, as last fetched from the SimBrief
    /// API (IBroker has no flight-plan members at all -- see CLAUDE.md / issue #1). All fields
    /// are null until the first successful fetch. Waypoints (issue #9 phase 2) is the ordered
    /// route from the OFP's navlog -- used to predict which VATGlasses sector ownship is
    /// approaching from its filed route rather than just its current instantaneous heading.
    /// </summary>
    public sealed class FlightPlan
    {
        public string Callsign { get; }
        public string Origin { get; }
        public string Destination { get; }
        public string Alternate { get; }
        public IReadOnlyList<FlightPlanWaypoint> Waypoints { get; }

        public FlightPlan(string callsign, string origin, string destination, string alternate, IReadOnlyList<FlightPlanWaypoint> waypoints = null)
        {
            Callsign = callsign;
            Origin = origin;
            Destination = destination;
            Alternate = alternate;
            Waypoints = waypoints ?? new List<FlightPlanWaypoint>();
        }

        public static readonly FlightPlan Empty = new FlightPlan(null, null, null, null);
    }
}
