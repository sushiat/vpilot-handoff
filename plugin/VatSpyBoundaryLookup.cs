using System;
using System.Collections.Generic;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Pure geometry against parsed vatspy FIR boundary data (issue #11) -- no network/disk/live-
    /// controller dependency, directly unit-testable. Mirrors VatGlassesSectorLookup's shape
    /// (containment vs. approach-prediction split, same local flat equirectangular nm projection),
    /// deliberately re-implemented against VatSpyFirBoundary rather than generalizing/reusing
    /// VatGlassesSectorLookup directly -- that class's types (VatGlassesSectorLevel) carry
    /// altitude-band concepts vatspy boundaries simply don't have.
    /// </summary>
    public static class VatSpyBoundaryLookup
    {
        private const double NmPerDegreeLatitude = 60.0;

        /// <summary>One boundary matched against ownship's current position or a projected path, paired with the along-path distance (nm) for the approach-prediction queries.</summary>
        public sealed class VatSpyApproachMatch
        {
            public VatSpyFirBoundary Boundary { get; }
            public double DistanceNauticalMiles { get; }

            public VatSpyApproachMatch(VatSpyFirBoundary boundary, double distanceNauticalMiles)
            {
                Boundary = boundary;
                DistanceNauticalMiles = distanceNauticalMiles;
            }
        }

        /// <summary>Standard ray-casting point-in-polygon test against a boundary's ring (closed last-&gt;first).</summary>
        public static bool IsPointInPolygon(double lat, double lon, VatSpyFirBoundary boundary)
        {
            var points = boundary.Points;
            if (points.Count < 3) return false;

            var inside = false;
            for (int i = 0, j = points.Count - 1; i < points.Count; j = i++)
            {
                var pi = points[i];
                var pj = points[j];
                var intersects = (pi.Latitude > lat) != (pj.Latitude > lat) &&
                    lon < (pj.Longitude - pi.Longitude) * (lat - pi.Latitude) / (pj.Latitude - pi.Latitude) + pi.Longitude;
                if (intersects) inside = !inside;
            }
            return inside;
        }

        /// <summary>Every boundary containing (lat, lon) right now -- horizontal-only, there's no altitude-aware variant since vatspy has no bands at all.</summary>
        public static IReadOnlyList<VatSpyFirBoundary> FindContainingBoundaries(IReadOnlyList<VatSpyFirBoundary> boundaries, double lat, double lon)
        {
            var matches = new List<VatSpyFirBoundary>();
            foreach (var boundary in boundaries.Where(boundary => BoundingBoxMayContain(lat, lon, boundary, 0)))
            {
                if (IsPointInPolygon(lat, lon, boundary)) matches.Add(boundary);
            }
            return matches;
        }

        /// <summary>Nearest-point distance (nm) from (lat, lon) to this boundary's ring -- works whether the point is inside or outside. Used for the same spatial-dead-band pattern as VatGlassesSectorLookup.DistanceToPolygonBoundaryNm.</summary>
        public static double DistanceToBoundaryNm(double lat, double lon, VatSpyFirBoundary boundary)
        {
            var points = boundary.Points;
            if (points.Count < 2) return double.PositiveInfinity;

            var minDistance = double.PositiveInfinity;
            for (int i = 0, j = points.Count - 1; i < points.Count; j = i++)
            {
                var a = Project(lat, lon, points[j].Latitude, points[j].Longitude);
                var b = Project(lat, lon, points[i].Latitude, points[i].Longitude);

                var edgeX = b.X - a.X;
                var edgeY = b.Y - a.Y;
                var lengthSquared = edgeX * edgeX + edgeY * edgeY;

                double distance;
                if (lengthSquared < 1e-9)
                {
                    distance = Math.Sqrt(a.X * a.X + a.Y * a.Y);
                }
                else
                {
                    var t = Math.Max(0.0, Math.Min(1.0, (-(a.X * edgeX) - (a.Y * edgeY)) / lengthSquared));
                    var closestX = a.X + t * edgeX;
                    var closestY = a.Y + t * edgeY;
                    distance = Math.Sqrt(closestX * closestX + closestY * closestY);
                }

                if (distance < minDistance) minDistance = distance;
            }
            return minDistance;
        }

        /// <summary>Nearest along-heading distance (nm) from (lat, lon) to this boundary's ring, or null if the heading ray doesn't intersect it.</summary>
        public static double? DistanceToPolygonAlongHeadingNm(double lat, double lon, double headingDegrees, VatSpyFirBoundary boundary)
        {
            if (boundary.Points.Count < 3) return null;

            var headingRad = headingDegrees * Math.PI / 180.0;
            var dx = Math.Sin(headingRad);
            var dy = Math.Cos(headingRad);

            return NearestEdgeIntersectionNm(lat, lon, 0, 0, dx, dy, 0, double.PositiveInfinity, boundary);
        }

        /// <summary>Nearest along-route distance (nm) from (lat, lon), walking the remaining SimBrief waypoints leg by leg, to this boundary's ring -- or null if no upcoming leg crosses it.</summary>
        public static double? DistanceToPolygonAlongRouteNm(double lat, double lon, IReadOnlyList<FlightPlanWaypoint> remainingWaypoints, VatSpyFirBoundary boundary)
        {
            if (remainingWaypoints == null || remainingWaypoints.Count == 0) return null;
            if (boundary.Points.Count < 3) return null;

            var legStartX = 0.0;
            var legStartY = 0.0;
            var cumulativeNm = 0.0;

            foreach (var legEnd in remainingWaypoints.Select(wp => Project(lat, lon, wp.Latitude, wp.Longitude)))
            {
                var dx = legEnd.X - legStartX;
                var dy = legEnd.Y - legStartY;
                var legLength = Math.Sqrt(dx * dx + dy * dy);

                if (legLength > 1e-9)
                {
                    var onLeg = NearestEdgeIntersectionNm(lat, lon, legStartX, legStartY, dx, dy, 0, 1, boundary);
                    if (onLeg.HasValue) return cumulativeNm + onLeg.Value;
                }

                cumulativeNm += legLength;
                legStartX = legEnd.X;
                legStartY = legEnd.Y;
            }

            return null;
        }

        /// <summary>Every boundary whose ring the current heading ray crosses within maxNauticalMiles, ordered nearest-first.</summary>
        public static IReadOnlyList<VatSpyApproachMatch> FindApproachingBoundariesAlongHeading(
            IReadOnlyList<VatSpyFirBoundary> boundaries, double lat, double lon, double headingDegrees, double maxNauticalMiles)
        {
            var results = new List<VatSpyApproachMatch>();
            foreach (var boundary in boundaries.Where(boundary => DistanceToBoundingBoxNm(lat, lon, boundary) <= maxNauticalMiles))
            {
                var distance = DistanceToPolygonAlongHeadingNm(lat, lon, headingDegrees, boundary);
                if (distance.HasValue && distance.Value <= maxNauticalMiles)
                {
                    results.Add(new VatSpyApproachMatch(boundary, distance.Value));
                }
            }
            results.Sort((a, b) => a.DistanceNauticalMiles.CompareTo(b.DistanceNauticalMiles));
            return results;
        }

        /// <summary>Every boundary whose ring the remaining SimBrief route crosses within maxNauticalMiles, ordered nearest-first (along-route distance).</summary>
        public static IReadOnlyList<VatSpyApproachMatch> FindApproachingBoundariesAlongRoute(
            IReadOnlyList<VatSpyFirBoundary> boundaries, double lat, double lon, IReadOnlyList<FlightPlanWaypoint> remainingWaypoints, double maxNauticalMiles)
        {
            var results = new List<VatSpyApproachMatch>();
            if (remainingWaypoints == null || remainingWaypoints.Count == 0) return results;

            foreach (var boundary in boundaries.Where(boundary => DistanceToBoundingBoxNm(lat, lon, boundary) <= maxNauticalMiles))
            {
                var distance = DistanceToPolygonAlongRouteNm(lat, lon, remainingWaypoints, boundary);
                if (distance.HasValue && distance.Value <= maxNauticalMiles)
                {
                    results.Add(new VatSpyApproachMatch(boundary, distance.Value));
                }
            }
            results.Sort((a, b) => a.DistanceNauticalMiles.CompareTo(b.DistanceNauticalMiles));
            return results;
        }

        private static double? NearestEdgeIntersectionNm(double originLat, double originLon, double originX, double originY, double dirX, double dirY, double tMin, double tMax, VatSpyFirBoundary boundary)
        {
            var dirLength = Math.Sqrt(dirX * dirX + dirY * dirY);
            if (dirLength < 1e-9) return null;

            var points = boundary.Points;
            double? nearestT = null;

            for (int i = 0, j = points.Count - 1; i < points.Count; j = i++)
            {
                var a = Project(originLat, originLon, points[j].Latitude, points[j].Longitude);
                var b = Project(originLat, originLon, points[i].Latitude, points[i].Longitude);

                var t = SegmentIntersectionT(originX, originY, dirX, dirY, tMin, tMax, a.X, a.Y, b.X, b.Y);
                if (t.HasValue && (!nearestT.HasValue || t.Value < nearestT.Value))
                {
                    nearestT = t.Value;
                }
            }

            return nearestT.HasValue ? nearestT.Value * dirLength : (double?)null;
        }

        private static double? SegmentIntersectionT(double originX, double originY, double dirX, double dirY, double tMin, double tMax, double ax, double ay, double bx, double by)
        {
            var edgeX = bx - ax;
            var edgeY = by - ay;
            var denom = dirX * edgeY - dirY * edgeX;
            if (Math.Abs(denom) < 1e-12) return null;

            var t = ((ax - originX) * edgeY - (ay - originY) * edgeX) / denom;
            var u = ((ax - originX) * dirY - (ay - originY) * dirX) / denom;

            if (t >= tMin && t <= tMax && u >= 0 && u <= 1) return t;
            return null;
        }

        private static (double X, double Y) Project(double originLat, double originLon, double pointLat, double pointLon)
        {
            var y = (pointLat - originLat) * NmPerDegreeLatitude;
            var x = (pointLon - originLon) * NmPerDegreeLatitude * Math.Cos(originLat * Math.PI / 180.0);
            return (x, y);
        }

        private static bool BoundingBoxMayContain(double lat, double lon, VatSpyFirBoundary boundary, double marginNauticalMiles)
        {
            var marginDegreesLat = marginNauticalMiles / NmPerDegreeLatitude;
            var cosLat = Math.Cos(lat * Math.PI / 180.0);
            var marginDegreesLon = cosLat > 1e-6 ? marginNauticalMiles / (NmPerDegreeLatitude * cosLat) : 180;

            return lat >= boundary.MinLatitude - marginDegreesLat && lat <= boundary.MaxLatitude + marginDegreesLat &&
                   lon >= boundary.MinLongitude - marginDegreesLon && lon <= boundary.MaxLongitude + marginDegreesLon;
        }

        private static double DistanceToBoundingBoxNm(double lat, double lon, VatSpyFirBoundary boundary)
        {
            var clampedLat = Math.Max(boundary.MinLatitude, Math.Min(boundary.MaxLatitude, lat));
            var clampedLon = Math.Max(boundary.MinLongitude, Math.Min(boundary.MaxLongitude, lon));
            return GeoDistance.NauticalMiles(lat, lon, clampedLat, clampedLon);
        }
    }
}
