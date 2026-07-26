namespace Handoff.Plugin
{
    /// <summary>
    /// A single nearby aircraft as reported by NearbyAircraftModel -- ownship-relative distance,
    /// already filtered to the reporting radius and sorted closest-first.
    /// </summary>
    public sealed class NearbyAircraft
    {
        public string Callsign { get; }
        public string AircraftType { get; }
        public double DistanceNm { get; }

        public NearbyAircraft(string callsign, string aircraftType, double distanceNm)
        {
            Callsign = callsign;
            AircraftType = aircraftType;
            DistanceNm = distanceNm;
        }
    }
}
