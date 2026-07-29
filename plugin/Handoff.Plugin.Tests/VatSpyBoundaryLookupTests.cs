using System.Collections.Generic;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatSpyBoundaryLookupTests
    {
        // Same ~1 degree square rectangle as VatGlassesSectorLookupTests' RectangleLevel, roughly
        // centered on 48N/16E, but with plain decimal points (no DMS, no altitude band -- vatspy
        // has neither).
        private static VatSpyFirBoundary Rectangle(string boundaryId = "TEST", params string[] prefixes) =>
            new VatSpyFirBoundary(boundaryId, "Test", prefixes.Length > 0 ? prefixes : new[] { "TEST" }, new List<VatSpyPoint>
            {
                new VatSpyPoint(47, 15),
                new VatSpyPoint(47, 17),
                new VatSpyPoint(49, 17),
                new VatSpyPoint(49, 15),
            });

        [Fact]
        public void IsPointInPolygon_PointInside_ReturnsTrue()
        {
            Assert.True(VatSpyBoundaryLookup.IsPointInPolygon(48, 16, Rectangle()));
        }

        [Fact]
        public void IsPointInPolygon_PointOutside_ReturnsFalse()
        {
            Assert.False(VatSpyBoundaryLookup.IsPointInPolygon(45, 16, Rectangle()));
        }

        [Fact]
        public void FindContainingBoundaries_ReturnsOnlyMatchingBoundaries()
        {
            var inside = Rectangle("IN");
            var outside = new VatSpyFirBoundary("OUT", "Elsewhere", new[] { "OUT" }, new List<VatSpyPoint>
            {
                new VatSpyPoint(0, 0), new VatSpyPoint(0, 1), new VatSpyPoint(1, 1), new VatSpyPoint(1, 0),
            });

            var matches = VatSpyBoundaryLookup.FindContainingBoundaries(new List<VatSpyFirBoundary> { inside, outside }, 48, 16);

            Assert.Equal(new[] { inside }, matches);
        }

        [Fact]
        public void DistanceToBoundaryNm_PointOutside_ReturnsDistanceToNearestEdge()
        {
            var distance = VatSpyBoundaryLookup.DistanceToBoundaryNm(45, 16, Rectangle());
            Assert.InRange(distance, 110, 130);
        }

        [Fact]
        public void DistanceToBoundaryNm_PointOnEdge_ReturnsNearZero()
        {
            var distance = VatSpyBoundaryLookup.DistanceToBoundaryNm(47, 16, Rectangle());
            Assert.InRange(distance, 0, 1);
        }

        [Fact]
        public void DistanceToPolygonAlongHeadingNm_HeadingTowardBoundary_ReturnsDistance()
        {
            // Starting 3 degrees (~180nm) south of the rectangle, heading due north (000).
            var distance = VatSpyBoundaryLookup.DistanceToPolygonAlongHeadingNm(44, 16, 0, Rectangle());
            Assert.NotNull(distance);
            Assert.InRange(distance.Value, 170, 190);
        }

        [Fact]
        public void DistanceToPolygonAlongHeadingNm_HeadingAway_ReturnsNull()
        {
            var distance = VatSpyBoundaryLookup.DistanceToPolygonAlongHeadingNm(44, 16, 180, Rectangle());
            Assert.Null(distance);
        }

        [Fact]
        public void FindApproachingBoundariesAlongHeading_WithinRange_ReturnsMatch()
        {
            var matches = VatSpyBoundaryLookup.FindApproachingBoundariesAlongHeading(
                new List<VatSpyFirBoundary> { Rectangle() }, 44, 16, 0, 200);

            Assert.Single(matches);
        }

        [Fact]
        public void FindApproachingBoundariesAlongHeading_OutOfRange_ReturnsEmpty()
        {
            var matches = VatSpyBoundaryLookup.FindApproachingBoundariesAlongHeading(
                new List<VatSpyFirBoundary> { Rectangle() }, 44, 16, 0, 50);

            Assert.Empty(matches);
        }
    }
}
