namespace Handoff.Plugin
{
    /// <summary>
    /// Immutable snapshot of a single connected ATC station, as last reported by
    /// vPilot's IBroker controller events.
    /// </summary>
    public sealed class Controller
    {
        public string Callsign { get; }
        public int Frequency { get; }      // vPilot's compressed-integer format, e.g. 23725 == 123.725
        public double Latitude { get; }
        public double Longitude { get; }

        public Controller(string callsign, int frequency, double latitude, double longitude)
        {
            Callsign = callsign;
            Frequency = frequency;
            Latitude = latitude;
            Longitude = longitude;
        }

        internal Controller WithFrequency(int frequency) =>
            new Controller(Callsign, frequency, Latitude, Longitude);

        internal Controller WithLocation(double latitude, double longitude) =>
            new Controller(Callsign, Frequency, latitude, longitude);
    }
}
