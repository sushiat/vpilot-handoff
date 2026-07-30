using Xunit;

namespace Handoff.Plugin.Tests
{
    public class GeoDistanceTests
    {
        [Fact]
        public void NauticalMiles_SamePoint_IsZero()
        {
            Assert.Equal(0, GeoDistance.NauticalMiles(51.4775, -0.4614, 51.4775, -0.4614), 6);
        }

        [Fact]
        public void NauticalMiles_KnownDistance_EgllToJfk()
        {
            // EGLL -> KJFK great-circle distance is well documented as ~2996 NM.
            var distance = GeoDistance.NauticalMiles(51.4775, -0.4614, 40.6413, -73.7781);

            Assert.InRange(distance, 2950, 3050);
        }

        [Fact]
        public void NauticalMiles_IsSymmetric()
        {
            var a = GeoDistance.NauticalMiles(51.4775, -0.4614, 40.6413, -73.7781);
            var b = GeoDistance.NauticalMiles(40.6413, -73.7781, 51.4775, -0.4614);

            Assert.Equal(a, b, 6);
        }

        [Fact]
        public void AlongTrackDistanceNm_PointOnCourse_EqualsPlainDistance()
        {
            // (2,0) sits directly on the due-north course from (0,0) to (4,0) -- zero cross-track,
            // so along-track distance should equal the plain great-circle distance to it.
            var alongTrack = GeoDistance.AlongTrackDistanceNm(0, 0, 4, 0, 2, 0);
            var plainDistance = GeoDistance.NauticalMiles(0, 0, 2, 0);

            Assert.Equal(plainDistance, alongTrack, 3);
        }

        [Fact]
        public void AlongTrackDistanceNm_PointBehindFrom_IsNegative()
        {
            var alongTrack = GeoDistance.AlongTrackDistanceNm(0, 0, 4, 0, -1, 0);

            Assert.True(alongTrack < 0);
        }

        [Fact]
        public void AlongTrackDistanceNm_PointOffToTheSide_IsLessThanDistanceToTargetOnceAbeam()
        {
            // A shallow dogleg waypoint at (2, 0.3): a point flying straight up the lon=0 axis
            // passes abeam it well before reaching its own lat -- along-track distance to the
            // dogleg waypoint should exceed the leg length once genuinely abeam/past it.
            var legLength = GeoDistance.NauticalMiles(0, 0, 2, 0.3);
            var alongTrack = GeoDistance.AlongTrackDistanceNm(0, 0, 2, 0.3, 2.5, 0);

            Assert.True(alongTrack >= legLength);
        }
    }
}
