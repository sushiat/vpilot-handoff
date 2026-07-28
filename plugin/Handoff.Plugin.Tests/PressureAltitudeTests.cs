using Xunit;

namespace Handoff.Plugin.Tests
{
    public class PressureAltitudeTests
    {
        [Fact]
        public void QnhTrueAltitudeFeet_StandardPressure_ReturnsPressureAltitudeUnchanged()
        {
            Assert.Equal(10000, PressureAltitude.QnhTrueAltitudeFeet(10000, 1013.25), 3);
        }

        [Fact]
        public void QnhTrueAltitudeFeet_LowQnh_TrueAltitudeIsLowerThanPressureAltitude()
        {
            // Lower QNH than standard -> true altitude is lower than pressure altitude.
            var result = PressureAltitude.QnhTrueAltitudeFeet(5000, 993.25);
            Assert.Equal(4400, result, 3);
        }

        [Fact]
        public void QnhTrueAltitudeFeet_HighQnh_TrueAltitudeIsHigherThanPressureAltitude()
        {
            // Higher QNH than standard -> true altitude is higher than pressure altitude.
            var result = PressureAltitude.QnhTrueAltitudeFeet(5000, 1033.25);
            Assert.Equal(5600, result, 3);
        }
    }
}
