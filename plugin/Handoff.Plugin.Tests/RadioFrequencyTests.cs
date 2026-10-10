using System;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class RadioFrequencyTests
    {
        [Theory]
        [InlineData(123.725, 23725)]
        [InlineData(118.000, 18000)]
        [InlineData(136.990, 36990)]
        public void ToVatsimCompressed_ConvertsKnownValues(double megahertz, int expected)
        {
            Assert.Equal(expected, RadioFrequency.ToVatsimCompressed(megahertz));
        }

        [Fact]
        public void ToVatsimCompressed_RoundsToNearestKhz()
        {
            Assert.Equal(23726, RadioFrequency.ToVatsimCompressed(123.7255));
        }

        [Theory]
        [InlineData(122800, 122.800)]
        [InlineData(118000, 118.000)]
        [InlineData(136990, 136.990)]
        public void FromKilohertz_ConvertsInBandValues(double kilohertz, double expected)
        {
            Assert.Equal(expected, RadioFrequency.FromKilohertz(kilohertz).Value, 6);
        }

        [Theory]
        [InlineData(-1)]
        [InlineData(0)]
        [InlineData(117999)]
        [InlineData(136991)]
        public void FromKilohertz_ReturnsNullOutOfBand(double kilohertz)
        {
            Assert.Null(RadioFrequency.FromKilohertz(kilohertz));
        }

        [Theory]
        [InlineData(118.000)]
        [InlineData(136.990)]
        [InlineData(123.725)]
        public void ValidateAirbandRange_AcceptsInBandValues(double megahertz)
        {
            var exception = Record.Exception(() => RadioFrequency.ValidateAirbandRange(megahertz));
            Assert.Null(exception);
        }

        [Theory]
        [InlineData(117.999)]
        [InlineData(136.991)]
        [InlineData(0.0)]
        public void ValidateAirbandRange_RejectsOutOfBandValues(double megahertz)
        {
            Assert.Throws<ArgumentOutOfRangeException>(() => RadioFrequency.ValidateAirbandRange(megahertz));
        }
    }
}
