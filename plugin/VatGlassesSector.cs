using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// One sector polygon boundary point. Keeps VATGlasses' raw DMS-string format (e.g.
    /// "475026"/"0124429") for fidelity/debugging, plus a constructor-computed decimal-degree
    /// conversion (via DmsCoordinate) -- computed once here rather than repeatedly by every
    /// point-in-polygon/ray-intersection lookup that runs against this immutable data on every
    /// ControllerRankingModel.Recompute() tick. See issue #9 phase 2.
    /// </summary>
    public sealed class VatGlassesPoint
    {
        public string LatitudeDms { get; }
        public string LongitudeDms { get; }
        public double Latitude { get; }
        public double Longitude { get; }

        public VatGlassesPoint(string latitudeDms, string longitudeDms)
        {
            LatitudeDms = latitudeDms;
            LongitudeDms = longitudeDms;
            Latitude = DmsCoordinate.ToDecimalDegrees(latitudeDms);
            Longitude = DmsCoordinate.ToDecimalDegrees(longitudeDms);
        }
    }

    /// <summary>
    /// One altitude-banded ring within a sector -- a sector can be valid only between a min/max
    /// flight level. Also carries a constructor-computed decimal-degree bounding box across
    /// Points -- a cheap reject test before the more expensive point-in-polygon/ray-intersection
    /// math runs against the full point list (see issue #9 phase 2, VatGlassesSectorLookup).
    /// </summary>
    public sealed class VatGlassesSectorLevel
    {
        public double? MinFlightLevel { get; }
        public double? MaxFlightLevel { get; }
        public IReadOnlyList<VatGlassesPoint> Points { get; }

        public double MinLatitude { get; }
        public double MaxLatitude { get; }
        public double MinLongitude { get; }
        public double MaxLongitude { get; }

        public VatGlassesSectorLevel(double? minFlightLevel, double? maxFlightLevel, IReadOnlyList<VatGlassesPoint> points)
        {
            MinFlightLevel = minFlightLevel;
            MaxFlightLevel = maxFlightLevel;
            Points = points;

            if (points.Count > 0)
            {
                double minLat = double.MaxValue, maxLat = double.MinValue;
                double minLon = double.MaxValue, maxLon = double.MinValue;
                foreach (var p in points)
                {
                    if (p.Latitude < minLat) minLat = p.Latitude;
                    if (p.Latitude > maxLat) maxLat = p.Latitude;
                    if (p.Longitude < minLon) minLon = p.Longitude;
                    if (p.Longitude > maxLon) maxLon = p.Longitude;
                }
                MinLatitude = minLat;
                MaxLatitude = maxLat;
                MinLongitude = minLon;
                MaxLongitude = maxLon;
            }
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
