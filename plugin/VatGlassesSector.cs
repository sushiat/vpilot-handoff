using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// One sector polygon boundary point, still in VATGlasses' raw DMS-string format (e.g.
    /// "475026"/"0124429") -- decimal-degree conversion is deferred to the ranking-integration
    /// follow-up (see issue #9 phase 1's "explicitly deferred" list), not done here.
    /// </summary>
    public sealed class VatGlassesPoint
    {
        public string LatitudeDms { get; }
        public string LongitudeDms { get; }

        public VatGlassesPoint(string latitudeDms, string longitudeDms)
        {
            LatitudeDms = latitudeDms;
            LongitudeDms = longitudeDms;
        }
    }

    /// <summary>One altitude-banded ring within a sector -- a sector can be valid only between a min/max flight level.</summary>
    public sealed class VatGlassesSectorLevel
    {
        public double? MinFlightLevel { get; }
        public double? MaxFlightLevel { get; }
        public IReadOnlyList<VatGlassesPoint> Points { get; }

        public VatGlassesSectorLevel(double? minFlightLevel, double? maxFlightLevel, IReadOnlyList<VatGlassesPoint> points)
        {
            MinFlightLevel = minFlightLevel;
            MaxFlightLevel = maxFlightLevel;
            Points = points;
        }
    }

    /// <summary>
    /// One entry of a VATGlasses region file's airspace[] array -- a named sector (e.g. a CTR
    /// subdivision), its own "who owns this if nobody's staffed" fallback chain (parallel to
    /// VatGlassesAirport.Topdown), and one or more altitude-banded polygon rings. See issue #9.
    /// </summary>
    public sealed class VatGlassesSector
    {
        public string Id { get; }
        public string Group { get; }
        public IReadOnlyList<string> Owner { get; }
        public IReadOnlyList<VatGlassesSectorLevel> Levels { get; }

        public VatGlassesSector(string id, string group, IReadOnlyList<string> owner, IReadOnlyList<VatGlassesSectorLevel> levels)
        {
            Id = id;
            Group = group;
            Owner = owner;
            Levels = levels;
        }
    }
}
