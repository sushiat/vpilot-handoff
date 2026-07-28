using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// One entry of a VATGlasses region file's airports{} map -- the precomputed "who owns this
    /// airport's airspace if nobody local is online" fallback chain, as an ordered list of
    /// position IDs (see VatGlassesPosition). See issue #9.
    /// </summary>
    public sealed class VatGlassesAirport
    {
        public string Icao { get; }
        public IReadOnlyList<string> Topdown { get; }

        public VatGlassesAirport(string icao, IReadOnlyList<string> topdown)
        {
            Icao = icao;
            Topdown = topdown;
        }
    }
}
