namespace Handoff.Plugin
{
    /// <summary>
    /// One entry of a VATGlasses region file's positions{} map -- what gets cross-referenced
    /// against IBroker's live controller list to know whether a given link in an
    /// airspace/airport ownership chain is actually staffed. See issue #9.
    /// </summary>
    public sealed class VatGlassesPosition
    {
        public string Id { get; }
        public string Type { get; }
        public string Frequency { get; }
        public string Callsign { get; }

        /// <summary>ICAO/FIR prefix this position belongs to (VATGlasses' "pre" field).</summary>
        public string Prefix { get; }

        public VatGlassesPosition(string id, string type, string frequency, string callsign, string prefix)
        {
            Id = id;
            Type = type;
            Frequency = frequency;
            Callsign = callsign;
            Prefix = prefix;
        }
    }
}
