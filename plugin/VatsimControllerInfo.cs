using System.Collections.Generic;

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

        // The controller's self-authored ATIS/info lines (feed's "text_atis" array), most
        // commonly populated even for non-ATIS positions -- see VatAtisStationNameExtractor,
        // which reads the first line for a human station name (e.g. "Bremen Radar") that beats
        // vatspy's composed one when it's actually present and parses cleanly. Empty (never null)
        // when the feed omits the field or a controller hasn't set one.
        public IReadOnlyList<string> TextAtis { get; }

        public VatsimControllerInfo(string callsign, int cid, string name, int facility, int rating, IReadOnlyList<string> textAtis = null)
        {
            Callsign = callsign;
            Cid = cid;
            Name = name;
            Facility = facility;
            Rating = rating;
            TextAtis = textAtis ?? System.Array.Empty<string>();
        }
    }
}
