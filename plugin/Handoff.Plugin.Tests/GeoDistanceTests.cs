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
    }
}
