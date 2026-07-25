using Xunit;

namespace Handoff.Plugin.Tests
{
    public class ControllerTierTests
    {
        [Theory]
        [InlineData("LOWW_DEL", ControllerTier.Delivery)]
        [InlineData("LOWW_GND", ControllerTier.Ground)]
        [InlineData("LOWW_TWR", ControllerTier.Tower)]
        [InlineData("LOWW_APP", ControllerTier.AppDep)]
        [InlineData("LOWW_DEP", ControllerTier.AppDep)]
        [InlineData("LOWW_CTR", ControllerTier.Center)]
        public void ParseControllerTier_RecognizedSuffix(string callsign, ControllerTier expected)
        {
            Assert.Equal(expected, callsign.ParseControllerTier());
        }

        [Fact]
        public void ParseControllerTier_SplitGroundFrequency_UsesLastToken()
        {
            Assert.Equal(ControllerTier.Ground, "LOWW_N_GND".ParseControllerTier());
        }

        [Theory]
        [InlineData("LOWW_OBS")]
        [InlineData("LOWW_FSS")]
        [InlineData("NOUNDERSCORE")]
        [InlineData("")]
        [InlineData(null)]
        public void ParseControllerTier_UnrecognizedOrMissingSuffix_ReturnsOther(string callsign)
        {
            Assert.Equal(ControllerTier.Other, callsign.ParseControllerTier());
        }

        [Fact]
        public void ParseControllerTier_IsCaseInsensitive()
        {
            Assert.Equal(ControllerTier.Tower, "loww_twr".ParseControllerTier());
        }
    }
}
