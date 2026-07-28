using System;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class DmsCoordinateTests
    {
        [Fact]
        public void ToDecimalDegrees_ParsesLatitude()
        {
            // 47*50'26" -> 47 + 50/60 + 26/3600
            var expected = 47 + 50 / 60.0 + 26 / 3600.0;
            Assert.Equal(expected, DmsCoordinate.ToDecimalDegrees("475026"), 6);
        }

        [Fact]
        public void ToDecimalDegrees_ParsesLongitude()
        {
            // 012*44'29" -> 12 + 44/60 + 29/3600
            var expected = 12 + 44 / 60.0 + 29 / 3600.0;
            Assert.Equal(expected, DmsCoordinate.ToDecimalDegrees("0124429"), 6);
        }

        [Fact]
        public void ToDecimalDegrees_HandlesLeadingNegativeSign()
        {
            var expected = -(47 + 50 / 60.0 + 26 / 3600.0);
            Assert.Equal(expected, DmsCoordinate.ToDecimalDegrees("-475026"), 6);
        }

        [Theory]
        [InlineData("")]
        [InlineData(null)]
        [InlineData("12AB")]
        [InlineData("12")]
        public void ToDecimalDegrees_ThrowsOnMalformedInput(string dms)
        {
            Assert.Throws<FormatException>(() => DmsCoordinate.ToDecimalDegrees(dms));
        }
    }
}
