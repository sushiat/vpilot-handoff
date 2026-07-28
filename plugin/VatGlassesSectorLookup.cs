using System;
using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// Pure geometry against parsed VATGlasses region data (see issue #9 phase 2) -- no
    /// network/disk/live-controller dependency, so every method here is directly unit-testable.
    /// Two distinct kinds of query, matching ControllerRankingModel's IsLikelyNextCandidate vs
    /// IsApproaching split:
    ///
    ///   - Containment (FindContainingSectors): exact point-in-polygon + altitude-band test --
    ///     "which sector(s) is ownship in right now." No distance/heading involved at all; the
    ///     polygon boundary itself is the criterion.
    ///   - Approach prediction (FindApproachingSectorsAlongHeading/Route): "which sector(s) is
    ///     ownship heading toward, but not in yet" -- a ray (current heading) or a polyline
    ///     (remaining SimBrief route legs) cast forward and intersected against polygon edges,
    ///     returning the along-path distance to the nearest edge crossing.
    ///
    /// All lat/lon math below uses a local flat, nm-based equirectangular projection centered on
    /// ownship's current position (longitude scaled by cos(latitude)) -- an approximation that's
    /// fine at the regional scale these polygons and lookahead caps operate at (at most ~150nm),
    /// same simplification GeoDistance's short-span usage elsewhere in this codebase already
    /// implicitly relies on.
    /// </summary>
    public static class VatGlassesSectorLookup
    {
        /// <summary>
        /// VATGlasses doesn't carry real per-region transition altitude/level data, so this is a
        /// placeholder cutoff (FL100) deciding which of pressure-altitude-FL or
        /// QNH-true-altitude-FL to compare a given band against -- same "reasonable default, not
        /// a modeled boundary" spirit as the ranking model's other placeholder constants.
        /// </summary>
        public const double TransitionLevelFallbackFl = 100;

        private const double NmPerDegreeLatitude = 60.0;

        /// <summary>One sector level match -- which region file, which sector, and which altitude-banded level of it matched.</summary>
        public sealed class VatGlassesSectorMatch
        {
            public string RegionFileName { get; }
            public VatGlassesSector Sector { get; }
            public VatGlassesSectorLevel Level { get; }

            public VatGlassesSectorMatch(string regionFileName, VatGlassesSector sector, VatGlassesSectorLevel level)
            {
                RegionFileName = regionFileName;
                Sector = sector;
                Level = level;
            }
        }

        /// <summary>A sector match paired with the along-path distance (nm) to its nearest edge crossing -- see FindApproachingSectorsAlongHeading/Route.</summary>
        public sealed class VatGlassesApproachMatch
        {
            public VatGlassesSectorMatch Match { get; }
            public double DistanceNauticalMiles { get; }

            public VatGlassesApproachMatch(VatGlassesSectorMatch match, double distanceNauticalMiles)
            {
                Match = match;
                DistanceNauticalMiles = distanceNauticalMiles;
            }
        }

        /// <summary>
        /// Every sector level whose polygon contains (lat, lon) and whose min/max flight-level
        /// band brackets the appropriate altitude figure -- QNH-true below
        /// TransitionLevelFallbackFl, pressure altitude at/above it (see PressureAltitude,
        /// ControllerRankingModel's §2 telemetry plumbing). Either altitude figure may be null
        /// (e.g. QNH not received yet) -- a level is simply skipped if the figure it needs is
        /// unavailable. Multiple overlapping matches (adjacent FIRs' data occasionally overlaps
        /// at shared boundaries) are all returned; the caller (VatGlassesOwnershipResolver.
        /// ResolveOnlineControllers) resolves each to every online controller it matches, not
        /// just one -- see that method's doc comment for why "just the first match" isn't safe.
        /// </summary>
        public static IReadOnlyList<VatGlassesSectorMatch> FindContainingSectors(
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            double lat,
            double lon,
            double? pressureAltitudeFlightLevel,
            double? qnhTrueAltitudeFlightLevel)
        {
            var matches = new List<VatGlassesSectorMatch>();

            foreach (var kv in regions)
            {
                foreach (var sector in kv.Value.Airspace)
                {
                    foreach (var level in sector.Levels)
                    {
                        var useQnh = level.MaxFlightLevel.HasValue && level.MaxFlightLevel.Value <= TransitionLevelFallbackFl;
                        var altitudeFl = useQnh ? qnhTrueAltitudeFlightLevel : pressureAltitudeFlightLevel;
                        if (!altitudeFl.HasValue) continue;
                        if (level.MinFlightLevel.HasValue && altitudeFl.Value < level.MinFlightLevel.Value) continue;
                        if (level.MaxFlightLevel.HasValue && altitudeFl.Value > level.MaxFlightLevel.Value) continue;

                        if (!BoundingBoxMayContain(lat, lon, level, 0)) continue;
                        if (IsPointInPolygon(lat, lon, level))
                        {
                            matches.Add(new VatGlassesSectorMatch(kv.Key, sector, level));
                        }
                    }
                }
            }

            return matches;
        }

        /// <summary>
        /// Same as FindContainingSectors, but skips the altitude-band check entirely -- horizontal
        /// polygon containment only. Used for CTR (bucket 6d, docs/controller-ranking.md): real
        /// VATSIM top-down coverage means an online enroute Center covers straight to the ground
        /// for anything inside its lateral boundary once staffed, regardless of the nominal FL its
        /// data lists as a floor (that FL shows up in the controller's own info string, not as a
        /// hard boundary on responsibility) -- so gating CTR containment on the published band
        /// would wrongly exclude a legitimately-covering Center.
        /// </summary>
        public static IReadOnlyList<VatGlassesSectorMatch> FindContainingSectorsIgnoringAltitude(
            IReadOnlyDictionary<string, VatGlassesRegionData> regions,
            double lat,
            double lon)
        {
            var matches = new List<VatGlassesSectorMatch>();

            foreach (var kv in regions)
            {
                foreach (var sector in kv.Value.Airspace)
                {
                    foreach (var level in sector.Levels)
                    {
                        if (!BoundingBoxMayContain(lat, lon, level, 0)) continue;
                        if (IsPointInPolygon(lat, lon, level))
                        {
                            matches.Add(new VatGlassesSectorMatch(kv.Key, sector, level));
                        }
                    }
                }
            }

            return matches;
        }

        /// <summary>Standard ray-casting point-in-polygon test against a sector level's ring (closed last-&gt;first).</summary>
        public static bool IsPointInPolygon(double lat, double lon, VatGlassesSectorLevel level)
        {
            var points = level.Points;
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

        /// <summary>
        /// Nearest along-heading distance (nm) from (lat, lon) to this level's polygon boundary,
        /// or null if the heading ray doesn't intersect it at all (flying past or away).
        /// </summary>
        public static double? DistanceToPolygonAlongHeadingNm(double lat, double lon, double headingDegrees, VatGlassesSectorLevel level)
        {
            if (level.Points.Count < 3) return null;

            var headingRad = headingDegrees * Math.PI / 180.0;
            var dx = Math.Sin(headingRad);
            var dy = Math.Cos(headingRad);

            return NearestEdgeIntersectionNm(lat, lon, 0, 0, dx, dy, 0, double.PositiveInfinity, level);
        }

        /// <summary>
        /// Nearest along-route distance (nm) from (lat, lon), walking the remaining SimBrief
        /// waypoints leg by leg, to this level's polygon boundary -- or null if no upcoming leg
        /// crosses it (or there are no remaining waypoints). Steadier than the heading-based
        /// version through a turn shortly before the boundary, since it reflects the filed route
        /// rather than one instant's heading.
        /// </summary>
        public static double? DistanceToPolygonAlongRouteNm(double lat, double lon, IReadOnlyList<FlightPlanWaypoint> remainingWaypoints, VatGlassesSectorLevel level)
        {
            if (remainingWaypoints == null || remainingWaypoints.Count == 0) return null;
            if (level.Points.Count < 3) return null;

            var legStartX = 0.0;
            var legStartY = 0.0;
            var legStartLat = lat;
            var legStartLon = lon;
            var cumulativeNm = 0.0;

            foreach (var wp in remainingWaypoints)
            {
                var legEnd = Project(lat, lon, wp.Latitude, wp.Longitude);
                var dx = legEnd.X - legStartX;
                var dy = legEnd.Y - legStartY;
                var legLength = Math.Sqrt(dx * dx + dy * dy);

                if (legLength > 1e-9)
                {
                    var onLeg = NearestEdgeIntersectionNm(lat, lon, legStartX, legStartY, dx, dy, 0, 1, level);
                    if (onLeg.HasValue) return cumulativeNm + onLeg.Value;
                }

                cumulativeNm += legLength;
                legStartX = legEnd.X;
                legStartY = legEnd.Y;
                legStartLat = wp.Latitude;
                legStartLon = wp.Longitude;
            }

            return null;
        }

        /// <summary>
        /// Shared ray/segment-vs-polygon-edge intersection core. (originX, originY) + t*(dirX,
        /// dirY) for t in [tMin, tMax] against every edge of level's ring; returns the smallest
        /// valid t*|dir| (i.e. actual nm distance along the path), or null if nothing intersects.
        /// originX/originY/dirX/dirY are already in the local nm plane centered on (originLat,
        /// originLon); polygon points are projected into that same plane on the fly.
        /// </summary>
        private static double? NearestEdgeIntersectionNm(double originLat, double originLon, double originX, double originY, double dirX, double dirY, double tMin, double tMax, VatGlassesSectorLevel level)
        {
            var dirLength = Math.Sqrt(dirX * dirX + dirY * dirY);
            if (dirLength < 1e-9) return null;

            var points = level.Points;
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

        /// <summary>
        /// Intersects the path origin+t*dir (t in [tMin,tMax]) against the edge a-&gt;b, returning
        /// t if they cross within both the path's range and the edge's own [0,1] span, else null.
        /// </summary>
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

        /// <summary>Projects (pointLat, pointLon) into a local nm-based flat plane centered on (originLat, originLon) -- x = east, y = north.</summary>
        private static (double X, double Y) Project(double originLat, double originLon, double pointLat, double pointLon)
        {
            var y = (pointLat - originLat) * NmPerDegreeLatitude;
            var x = (pointLon - originLon) * NmPerDegreeLatitude * Math.Cos(originLat * Math.PI / 180.0);
            return (x, y);
        }

        /// <summary>
        /// Cheap reject test using the level's precomputed decimal-degree bounding box (see
        /// VatGlassesSectorLevel) before the more expensive per-edge math runs -- true if (lat,
        /// lon) is within the bounding box expanded by marginNauticalMiles in every direction.
        /// </summary>
        private static bool BoundingBoxMayContain(double lat, double lon, VatGlassesSectorLevel level, double marginNauticalMiles)
        {
            var marginDegreesLat = marginNauticalMiles / NmPerDegreeLatitude;
            var cosLat = Math.Cos(lat * Math.PI / 180.0);
            var marginDegreesLon = cosLat > 1e-6 ? marginNauticalMiles / (NmPerDegreeLatitude * cosLat) : 180;

            return lat >= level.MinLatitude - marginDegreesLat && lat <= level.MaxLatitude + marginDegreesLat &&
                   lon >= level.MinLongitude - marginDegreesLon && lon <= level.MaxLongitude + marginDegreesLon;
        }

        /// <summary>
        /// Distance in nm from (lat, lon) to the nearest point of the level's bounding box --
        /// used to cheaply reject sectors clearly farther away than an approach-search cap before
        /// running the per-edge ray/route intersection math on them.
        /// </summary>
        private static double DistanceToBoundingBoxNm(double lat, double lon, VatGlassesSectorLevel level)
        {
            var clampedLat = Math.Max(level.MinLatitude, Math.Min(level.MaxLatitude, lat));
            var clampedLon = Math.Max(level.MinLongitude, Math.Min(level.MaxLongitude, lon));
            return GeoDistance.NauticalMiles(lat, lon, clampedLat, clampedLon);
        }

        /// <summary>
        /// Every sector level whose polygon the current heading ray crosses within
        /// maxNauticalMiles, ordered nearest-first. Bounding-box pre-check keeps this a cheap
        /// per-tick scan across every loaded region.
        /// </summary>
        public static IReadOnlyList<VatGlassesApproachMatch> FindApproachingSectorsAlongHeading(
            IReadOnlyDictionary<string, VatGlassesRegionData> regions, double lat, double lon, double headingDegrees, double maxNauticalMiles)
        {
            var results = new List<VatGlassesApproachMatch>();

            foreach (var kv in regions)
            {
                foreach (var sector in kv.Value.Airspace)
                {
                    foreach (var level in sector.Levels)
                    {
                        if (DistanceToBoundingBoxNm(lat, lon, level) > maxNauticalMiles) continue;

                        var distance = DistanceToPolygonAlongHeadingNm(lat, lon, headingDegrees, level);
                        if (distance.HasValue && distance.Value <= maxNauticalMiles)
                        {
                            results.Add(new VatGlassesApproachMatch(new VatGlassesSectorMatch(kv.Key, sector, level), distance.Value));
                        }
                    }
                }
            }

            results.Sort((a, b) => a.DistanceNauticalMiles.CompareTo(b.DistanceNauticalMiles));
            return results;
        }

        /// <summary>
        /// Every sector level whose polygon the remaining SimBrief route crosses within
        /// maxNauticalMiles, ordered nearest-first (along-route distance). Same bounding-box
        /// pre-check as the heading-based version; the box margin here uses maxNauticalMiles
        /// since a distant leg could still cross a nearby-looking box.
        /// </summary>
        public static IReadOnlyList<VatGlassesApproachMatch> FindApproachingSectorsAlongRoute(
            IReadOnlyDictionary<string, VatGlassesRegionData> regions, double lat, double lon, IReadOnlyList<FlightPlanWaypoint> remainingWaypoints, double maxNauticalMiles)
        {
            var results = new List<VatGlassesApproachMatch>();
            if (remainingWaypoints == null || remainingWaypoints.Count == 0) return results;

            foreach (var kv in regions)
            {
                foreach (var sector in kv.Value.Airspace)
                {
                    foreach (var level in sector.Levels)
                    {
                        if (DistanceToBoundingBoxNm(lat, lon, level) > maxNauticalMiles) continue;

                        var distance = DistanceToPolygonAlongRouteNm(lat, lon, remainingWaypoints, level);
                        if (distance.HasValue && distance.Value <= maxNauticalMiles)
                        {
                            results.Add(new VatGlassesApproachMatch(new VatGlassesSectorMatch(kv.Key, sector, level), distance.Value));
                        }
                    }
                }
            }

            results.Sort((a, b) => a.DistanceNauticalMiles.CompareTo(b.DistanceNauticalMiles));
            return results;
        }
    }
}
