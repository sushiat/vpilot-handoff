using System.Collections.Generic;

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

        /// <summary>ICAO/FIR prefixes this position belongs to (VATGlasses' "pre" field -- a
        /// position can carry several, e.g. both an ICAO and an "-I" inbound-traffic variant).</summary>
        public IReadOnlyList<string> Prefixes { get; }

        public VatGlassesPosition(string id, string type, string frequency, string callsign, IReadOnlyList<string> prefixes)
        {
            Id = id;
            Type = type;
            Frequency = frequency;
            Callsign = callsign;
            Prefixes = prefixes;
        }
    }
}
