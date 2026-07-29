using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// One point of a vatspy FIR boundary ring -- plain decimal degrees straight from
    /// Boundaries.geojson (RFC7946: coordinates are `[lon, lat]` pairs, already decimal, no DMS
    /// conversion needed here unlike VatGlassesPoint). See issue #11.
    /// </summary>
    public sealed class VatSpyPoint
    {
        public double Latitude { get; }
        public double Longitude { get; }

        public VatSpyPoint(double latitude, double longitude)
        {
            Latitude = latitude;
            Longitude = longitude;
        }
    }

    /// <summary>
    /// One ring of a vatspy FIR/UIR boundary -- horizontal-only (Boundaries.geojson has no
    /// altitude bands at all, unlike VATGlasses' per-level polygons), so there's no equivalent of
    /// VatGlassesSectorLevel's min/max flight level. A single vatspy "boundary" feature can be a
    /// GeoJSON MultiPolygon (e.g. a FIR split across non-contiguous areas) -- each outer ring
    /// becomes its own VatSpyFirBoundary sharing the same BoundaryId/Name/CallsignPrefixes. Holes
    /// (a polygon's inner rings) are deliberately not modeled -- same "no buffer/inset, the
    /// boundary itself is the criterion" simplicity already used for VATGlasses containment, and
    /// real FIR-boundary holes are rare enough (small carved-out enclaves) not to be worth the
    /// extra even-odd-across-rings bookkeeping for a coarse fallback tier.
    ///
    /// BoundaryId is Boundaries.geojson's `properties.id`, which is also VATSpy.dat's `[FIRs]`
    /// section's 4th column ("FIR BOUNDARY") -- the join key between the two files. It is NOT
    /// always the same as the FIR's own ICAO code: a FIR that's split into named sub-regions (e.g.
    /// Adria Radar's "ADR-E"/"ADR-W") has one boundary id per sub-region, distinct from the parent
    /// ICAO "ADR".
    /// </summary>
    public sealed class VatSpyFirBoundary
    {
        public string BoundaryId { get; }
        public string Name { get; }
        public IReadOnlyList<string> CallsignPrefixes { get; }
        public IReadOnlyList<VatSpyPoint> Points { get; }

        public double MinLatitude { get; }
        public double MaxLatitude { get; }
        public double MinLongitude { get; }
        public double MaxLongitude { get; }

        public VatSpyFirBoundary(string boundaryId, string name, IReadOnlyList<string> callsignPrefixes, IReadOnlyList<VatSpyPoint> points)
        {
            BoundaryId = boundaryId;
            Name = name;
            CallsignPrefixes = callsignPrefixes;
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

    /// <summary>One `[Airports]` row from VATSpy.dat -- place name for DEL/GND/TWR/APP display-name composition. See issue #11.</summary>
    public sealed class VatSpyAirportInfo
    {
        public string Icao { get; }
        public string Name { get; }

        public VatSpyAirportInfo(string icao, string name)
        {
            Icao = icao;
            Name = name;
        }
    }
}
