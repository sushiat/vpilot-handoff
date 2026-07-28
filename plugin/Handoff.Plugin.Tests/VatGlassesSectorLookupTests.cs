using System.Collections.Generic;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatGlassesSectorLookupTests
    {
        // A simple ~1 degree square rectangle, roughly centered on 48N/16E, FL0-FL245.
        private static VatGlassesSectorLevel RectangleLevel(double? min = 0, double? max = 245) =>
            new VatGlassesSectorLevel(min, max, new List<VatGlassesPoint>
            {
                new VatGlassesPoint("470000", "0150000"), // 47N, 15E
                new VatGlassesPoint("470000", "0170000"), // 47N, 17E
                new VatGlassesPoint("490000", "0170000"), // 49N, 17E
                new VatGlassesPoint("490000", "0150000"), // 49N, 15E
            });

        private static IReadOnlyDictionary<string, VatGlassesRegionData> RegionsWith(VatGlassesSector sector) =>
            new Dictionary<string, VatGlassesRegionData>
            {
                ["test.json"] = new VatGlassesRegionData(
                    new Dictionary<string, VatGlassesAirport>(),
                    new List<VatGlassesSector> { sector },
                    new Dictionary<string, VatGlassesPosition>())
            };

        [Fact]
        public void IsPointInPolygon_PointInside_ReturnsTrue()
        {
            Assert.True(VatGlassesSectorLookup.IsPointInPolygon(48, 16, RectangleLevel()));
        }

        [Fact]
        public void IsPointInPolygon_PointOutside_ReturnsFalse()
        {
            Assert.False(VatGlassesSectorLookup.IsPointInPolygon(45, 16, RectangleLevel()));
        }

        [Fact]
        public void DistanceToPolygonBoundaryNm_PointOutside_ReturnsDistanceToNearestEdge()
        {
            // 45N is ~2 degrees south of the rectangle's near (47N) edge -> ~120nm.
            var distance = VatGlassesSectorLookup.DistanceToPolygonBoundaryNm(45, 16, RectangleLevel());
            Assert.InRange(distance, 110, 130);
        }

        [Fact]
        public void DistanceToPolygonBoundaryNm_PointInside_ReturnsDistanceToNearestEdgeNotZero()
        {
            // Center of the rectangle -- 1 degree from every edge, but the E/W edges (15E/17E)
            // are nearer in nm terms than the N/S ones at this latitude (longitude nm-per-degree
            // shrinks by cos(48deg) =~ 0.67 -> ~40nm vs ~60nm) -- not 0 just because it's
            // contained (unlike IsPointInPolygon, this is a plain distance query).
            var distance = VatGlassesSectorLookup.DistanceToPolygonBoundaryNm(48, 16, RectangleLevel());
            Assert.InRange(distance, 35, 45);
        }

        [Fact]
        public void DistanceToPolygonBoundaryNm_PointOnEdge_ReturnsNearZero()
        {
            var distance = VatGlassesSectorLookup.DistanceToPolygonBoundaryNm(47, 16, RectangleLevel());
            Assert.InRange(distance, 0, 1);
        }

        [Fact]
        public void FindContainingSectors_InsideHorizontallyButOutsideAltitudeBand_NoMatch()
        {
            var sector = new VatGlassesSector("S1", "GRP", new List<string> { "OWN" }, new List<VatGlassesSectorLevel> { RectangleLevel(0, 245) });
            var regions = RegionsWith(sector);

            // FL350 pressure altitude, band only goes to FL245.
            var matches = VatGlassesSectorLookup.FindContainingSectors(regions, 48, 16, 350, null);

            Assert.Empty(matches);
        }

        [Fact]
        public void FindContainingSectors_InsideHorizontallyAndWithinAltitudeBand_Matches()
        {
            var sector = new VatGlassesSector("S1", "GRP", new List<string> { "OWN" }, new List<VatGlassesSectorLevel> { RectangleLevel(0, 245) });
            var regions = RegionsWith(sector);

            var matches = VatGlassesSectorLookup.FindContainingSectors(regions, 48, 16, 150, null);

            Assert.Single(matches);
            Assert.Equal("S1", matches[0].Sector.Id);
        }

        [Fact]
        public void FindContainingSectors_LowBand_UsesQnhTrueAltitude()
        {
            // Band max (50) is below TransitionLevelFallbackFl (100) -> should use the QNH-true figure, not pressure altitude.
            var sector = new VatGlassesSector("S1", "GRP", new List<string> { "OWN" }, new List<VatGlassesSectorLevel> { RectangleLevel(0, 50) });
            var regions = RegionsWith(sector);

            // Pressure altitude (FL80) would be outside the band, but QNH-true (FL30) is inside it.
            var matches = VatGlassesSectorLookup.FindContainingSectors(regions, 48, 16, pressureAltitudeFlightLevel: 80, qnhTrueAltitudeFlightLevel: 30);

            Assert.Single(matches);
        }

        [Fact]
        public void DistanceToPolygonAlongHeadingNm_HeadingTowardPolygon_ReturnsDistance()
        {
            // Starting south of the rectangle (45N, 16E), heading due north (0 degrees).
            var distance = VatGlassesSectorLookup.DistanceToPolygonAlongHeadingNm(45, 16, 0, RectangleLevel());

            Assert.NotNull(distance);
            // ~2 degrees latitude to the 47N edge -> ~120nm.
            Assert.InRange(distance.Value, 110, 130);
        }

        [Fact]
        public void DistanceToPolygonAlongHeadingNm_HeadingAway_ReturnsNull()
        {
            var distance = VatGlassesSectorLookup.DistanceToPolygonAlongHeadingNm(45, 16, 180, RectangleLevel());
            Assert.Null(distance);
        }

        [Fact]
        public void DistanceToPolygonAlongRouteNm_NextLegCrossesPolygon_ReturnsDistance()
        {
            // Current heading (due east, 90) would miss the rectangle entirely, but the filed
            // route turns north into it on the next leg -- this is the "turn just before the
            // boundary" scenario the route-projected check exists for.
            var remainingWaypoints = new List<FlightPlanWaypoint>
            {
                new FlightPlanWaypoint("TURN", 45, 16),
                new FlightPlanWaypoint("INSIDE", 48, 16),
            };

            var headingDistance = VatGlassesSectorLookup.DistanceToPolygonAlongHeadingNm(45, 20, 90, RectangleLevel());
            Assert.Null(headingDistance);

            var routeDistance = VatGlassesSectorLookup.DistanceToPolygonAlongRouteNm(45, 20, remainingWaypoints, RectangleLevel());
            Assert.NotNull(routeDistance);
        }

        [Fact]
        public void DistanceToPolygonAlongRouteNm_RoutePassesByEntirely_ReturnsNull()
        {
            var remainingWaypoints = new List<FlightPlanWaypoint>
            {
                new FlightPlanWaypoint("FAR1", 45, 30),
                new FlightPlanWaypoint("FAR2", 45, 40),
            };

            var distance = VatGlassesSectorLookup.DistanceToPolygonAlongRouteNm(45, 20, remainingWaypoints, RectangleLevel());
            Assert.Null(distance);
        }

        [Fact]
        public void DistanceToPolygonAlongRouteNm_NoWaypoints_ReturnsNull()
        {
            var distance = VatGlassesSectorLookup.DistanceToPolygonAlongRouteNm(45, 16, new List<FlightPlanWaypoint>(), RectangleLevel());
            Assert.Null(distance);
        }
    }
}
