using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>Parsed contents of a single VATGlasses region file (e.g. "lo.json" for Austria). See issue #9.</summary>
    public sealed class VatGlassesRegionData
    {
        public IReadOnlyDictionary<string, VatGlassesAirport> Airports { get; }
        public IReadOnlyList<VatGlassesSector> Airspace { get; }
        public IReadOnlyDictionary<string, VatGlassesPosition> Positions { get; }

        public VatGlassesRegionData(
            IReadOnlyDictionary<string, VatGlassesAirport> airports,
            IReadOnlyList<VatGlassesSector> airspace,
            IReadOnlyDictionary<string, VatGlassesPosition> positions)
        {
            Airports = airports;
            Airspace = airspace;
            Positions = positions;
        }
    }
}
