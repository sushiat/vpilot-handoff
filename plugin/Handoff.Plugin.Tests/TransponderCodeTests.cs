using System;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class TransponderCodeTests
    {
        [Theory]
        [InlineData(1200, 0x1200)]
        [InlineData(7777, 0x7777)]
        [InlineData(0, 0x0000)]
        [InlineData(2000, 0x2000)]
        public void ToBcd_ConvertsKnownValues(int squawk, int expectedBcd)
        {
            Assert.Equal(expectedBcd, TransponderCode.ToBcd(squawk));
        }

        [Theory]
        [InlineData(0x1200, 1200)]
        [InlineData(0x7777, 7777)]
        [InlineData(0x0000, 0)]
        [InlineData(0x2000, 2000)]
        public void FromBcd_ConvertsKnownValues(int bcd, int expectedSquawk)
        {
            Assert.Equal(expectedSquawk, TransponderCode.FromBcd(bcd));
        }

        [Theory]
        [InlineData(0)]
        [InlineData(1200)]
        [InlineData(7777)]
        public void ValidateSquawkRange_AcceptsValidCodes(int squawk)
        {
            var exception = Record.Exception(() => TransponderCode.ValidateSquawkRange(squawk));
            Assert.Null(exception);
        }

        [Theory]
        [InlineData(-1)]
        [InlineData(7778)]
        [InlineData(8000)]
        [InlineData(1800)]
        public void ValidateSquawkRange_RejectsInvalidCodes(int squawk)
        {
            Assert.Throws<ArgumentOutOfRangeException>(() => TransponderCode.ValidateSquawkRange(squawk));
        }
    }
}
