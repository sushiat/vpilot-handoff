using System;
using System.IO;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class UpdateIntervalModelTests : IDisposable
    {
        private readonly string _configPath = PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        [Fact]
        public void DefaultTier_BeforeAnySet_IsNormalMatchingOriginalConstants()
        {
            var model = new UpdateIntervalModel(configPath: _configPath);

            Assert.Equal(UpdateIntervalTier.Normal, model.CurrentTier);
            Assert.Equal("normal", model.CurrentTierWire);
            // Normal must reproduce the plugin's original hardcoded cadences exactly.
            Assert.Equal(1000, model.RadioPollMs);
            Assert.Equal(3000, model.TelemetryPollMs);
            Assert.Equal(1000, model.WsBroadcastMs);
        }

        [Theory]
        [InlineData(UpdateIntervalTier.Fast, "fast", 500, 1000, 500)]
        [InlineData(UpdateIntervalTier.Normal, "normal", 1000, 3000, 1000)]
        [InlineData(UpdateIntervalTier.Slow, "slow", 2000, 5000, 2000)]
        public void SetTier_MapsToExpectedIntervals(UpdateIntervalTier tier, string wire, int radioMs, int telemetryMs, int wsMs)
        {
            var model = new UpdateIntervalModel(configPath: _configPath);

            model.SetTier(tier);

            Assert.Equal(tier, model.CurrentTier);
            Assert.Equal(wire, model.CurrentTierWire);
            Assert.Equal(radioMs, model.RadioPollMs);
            Assert.Equal(telemetryMs, model.TelemetryPollMs);
            Assert.Equal(wsMs, model.WsBroadcastMs);
        }

        [Fact]
        public void SetTier_ToDifferentValue_RaisesChanged()
        {
            var model = new UpdateIntervalModel(configPath: _configPath);
            var raised = 0;
            model.Changed += (s, e) => raised++;

            model.SetTier(UpdateIntervalTier.Fast);

            Assert.Equal(1, raised);
        }

        [Fact]
        public void SetTier_ToSameValue_DoesNotRaiseChanged()
        {
            var model = new UpdateIntervalModel(configPath: _configPath);
            model.SetTier(UpdateIntervalTier.Slow);
            var raised = 0;
            model.Changed += (s, e) => raised++;

            model.SetTier(UpdateIntervalTier.Slow);

            Assert.Equal(0, raised);
        }

        [Fact]
        public void SetTier_Persists_AndReloadsOnNextConstruction()
        {
            new UpdateIntervalModel(configPath: _configPath).SetTier(UpdateIntervalTier.Slow);

            var reloaded = new UpdateIntervalModel(configPath: _configPath);

            Assert.Equal(UpdateIntervalTier.Slow, reloaded.CurrentTier);
        }

        [Theory]
        [InlineData("fast", UpdateIntervalTier.Fast)]
        [InlineData("NORMAL", UpdateIntervalTier.Normal)]
        [InlineData("  slow  ", UpdateIntervalTier.Slow)]
        public void TrySetTierFromWire_RecognizedValue_AppliesAndReturnsTrue(string wire, UpdateIntervalTier expected)
        {
            var model = new UpdateIntervalModel(configPath: _configPath);

            Assert.True(model.TrySetTierFromWire(wire));
            Assert.Equal(expected, model.CurrentTier);
        }

        [Theory]
        [InlineData(null)]
        [InlineData("")]
        [InlineData("turbo")]
        public void TrySetTierFromWire_UnrecognizedValue_LeavesTierUntouchedAndReturnsFalse(string wire)
        {
            var model = new UpdateIntervalModel(configPath: _configPath);
            model.SetTier(UpdateIntervalTier.Fast);

            Assert.False(model.TrySetTierFromWire(wire));
            Assert.Equal(UpdateIntervalTier.Fast, model.CurrentTier);
        }
    }
}
