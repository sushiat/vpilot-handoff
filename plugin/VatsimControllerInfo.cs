namespace Handoff.Plugin
{
    /// <summary>
    /// Enrichment fields for a controller, sourced from the public VATSIM data feed
    /// (https://data.vatsim.net/v3/vatsim-data.json) rather than IBroker -- IBroker's controller
    /// events only expose Callsign/Frequency/Latitude/Longitude, nothing else (confirmed against
    /// the full RossCarlson.Vatsim.Vpilot.Plugins.xml doc). See VatsimDataFeedClient.
    /// </summary>
    public sealed class VatsimControllerInfo
    {
        public string Callsign { get; }
        public int Cid { get; }
        public string Name { get; }
        public int Facility { get; }
        public int Rating { get; }

        public VatsimControllerInfo(string callsign, int cid, string name, int facility, int rating)
        {
            Callsign = callsign;
            Cid = cid;
            Name = name;
            Facility = facility;
            Rating = rating;
        }
    }
}
