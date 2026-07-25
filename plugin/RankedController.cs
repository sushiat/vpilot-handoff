namespace Handoff.Plugin
{
    /// <summary>
    /// A single controller as re-ranked by ControllerRankingModel -- the full station list
    /// IBroker reports, reordered, plus boolean flags the Android app uses for colour-coding.
    /// Nothing is ever hidden: every connected station appears exactly once. Cid/Name/Facility/
    /// Rating are null until VatsimDataFeedModel solidifies enrichment for that callsign.
    /// </summary>
    public sealed class RankedController
    {
        public string Callsign { get; }
        public int Frequency { get; }
        public double Latitude { get; }
        public double Longitude { get; }

        public int? Cid { get; }
        public string Name { get; }
        public int? Facility { get; }
        public int? Rating { get; }

        public bool RequestsContactMe { get; }
        public bool IsCurrent { get; }
        public bool IsContactMe { get; }
        public bool IsLikelyNextCandidate { get; }

        public RankedController(string callsign, int frequency, double latitude, double longitude, int? cid, string name, int? facility, int? rating, bool requestsContactMe, bool isCurrent, bool isContactMe, bool isLikelyNextCandidate)
        {
            Callsign = callsign;
            Frequency = frequency;
            Latitude = latitude;
            Longitude = longitude;
            Cid = cid;
            Name = name;
            Facility = facility;
            Rating = rating;
            RequestsContactMe = requestsContactMe;
            IsCurrent = isCurrent;
            IsContactMe = isContactMe;
            IsLikelyNextCandidate = isLikelyNextCandidate;
        }
    }
}
