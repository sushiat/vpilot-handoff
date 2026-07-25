using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class ControllerRankingModelTests : IDisposable
    {
        private readonly string _configPath = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");
        private readonly FakeBroker _broker = new FakeBroker();
        private readonly FakeRadioStateModel _radio = new FakeRadioStateModel();
        private readonly VatsimDataFeedModel _vatsimFeed = new VatsimDataFeedModel();
        private readonly ControllerStateModel _controllers;
        private readonly ContactMeModel _contactMe;

        public ControllerRankingModelTests()
        {
            _controllers = new ControllerStateModel(_broker);
            var chat = new ChatModel(_broker);
            _contactMe = new ContactMeModel(chat, _controllers);
        }

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        private ControllerRankingModel CreateModel(FlightPlanModel flightPlan = null, Func<DateTimeOffset> now = null) =>
            new ControllerRankingModel(_controllers, _radio, flightPlan ?? NoOpFlightPlan(), _vatsimFeed, _contactMe, now: now);

        private FlightPlanModel NoOpFlightPlan() =>
            new FlightPlanModel(fetch: (u, n) => Task.FromResult(Plugin.FlightPlan.Empty), configPath: _configPath);

        private async Task<FlightPlanModel> CreateFlightPlanAsync(string origin, string destination)
        {
            var plan = new Plugin.FlightPlan("BAW123", origin, destination, null);
            var model = new FlightPlanModel(fetch: (u, n) => Task.FromResult(plan), configPath: _configPath);
            model.SetSimbriefCredentials("1", null);
            await model.RefreshAsync();
            return model;
        }

        private void AddController(string callsign, int frequency, double lat = 0, double lon = 0) =>
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs(callsign, frequency, lat, lon));

        [Fact]
        public void TunedFrequency_MatchesController_MarksItCurrent()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel();

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.True(ranked[0].IsCurrent);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_GND").IsCurrent);
        }

        [Fact]
        public void NothingTuned_NoControllerIsCurrent()
        {
            AddController("EGLL_TWR", 23725);
            var model = CreateModel();

            Assert.All(model.Current, c => Assert.False(c.IsCurrent));
        }

        [Fact]
        public void PinnedController_OverridesTunedFrequency()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel();

            model.SetPinnedController("EGLL_GND");

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_GND").IsCurrent);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_TWR").IsCurrent);
        }

        [Fact]
        public void ClearPinnedController_RevertsToTunedFrequency()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel();
            model.SetPinnedController("EGLL_GND");

            model.ClearPinnedController();

            Assert.True(model.Current.Single(c => c.Callsign == "EGLL_TWR").IsCurrent);
        }

        [Fact]
        public void PinnedController_GoingOffline_ClearsThePin()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            var model = CreateModel();
            model.SetPinnedController("EGLL_GND");

            _broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_GND"));

            Assert.All(model.Current, c => Assert.False(c.IsCurrent));
        }

        [Fact]
        public void ContactMeController_RanksJustBelowCurrent()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_CTR", 12345); // far tier, would otherwise sort last
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel();

            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_CTR", "contact me"));

            var ranked = model.Current;
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.Equal("EGLL_CTR", ranked[1].Callsign);
            Assert.True(ranked[1].IsContactMe);
        }

        [Fact]
        public void NextTierInChain_GetsFlaggedAsLikelyNextCandidate()
        {
            AddController("EGLL_GND", 21800);
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_CTR", 12345);
            _radio.Current = new RadioState(21800, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel();

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_TWR").IsLikelyNextCandidate);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_CTR").IsLikelyNextCandidate);
        }

        [Fact]
        public void NothingTuned_LowestPresentTierIsLikelyNextCandidate()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_CTR", 12345);
            var model = CreateModel();

            Assert.True(model.Current.Single(c => c.Callsign == "EGLL_TWR").IsLikelyNextCandidate);
            Assert.False(model.Current.Single(c => c.Callsign == "EGLL_CTR").IsLikelyNextCandidate);
        }

        [Fact]
        public async Task RouteMatch_PreDeparture_PrefersOrigin()
        {
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            AddController("LOWW_DEL", 12100);
            AddController("LZIB_DEL", 12200);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 48.11, 16.57, DateTimeOffset.Now);

            var model = CreateModel(flightPlan);

            var delTier = model.Current.Where(c => c.Callsign.EndsWith("_DEL")).ToList();
            Assert.Equal("LOWW_DEL", delTier[0].Callsign);
        }

        [Fact]
        public async Task RouteMatch_AfterTakeoff_PrefersDestination()
        {
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            AddController("LOWW_CTR", 12100);
            AddController("LZIB_CTR", 12200);
            var model = CreateModel(flightPlan);

            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 500, 90, 48.5, 16.9, DateTimeOffset.Now);
            _radio.RaiseChanged();

            var ctrTier = model.Current.Where(c => c.Callsign.EndsWith("_CTR")).ToList();
            Assert.Equal("LZIB_CTR", ctrTier[0].Callsign);
        }

        [Fact]
        public void NoRouteMatch_FallsBackToDistance_ClosestFirst()
        {
            AddController("EGKK_TWR", 20000, 51.15, -0.19); // ~24nm from EGLL
            AddController("EGLC_TWR", 20100, 51.505, 0.0489); // ~10nm from EGLL
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 51.4775, -0.4614, DateTimeOffset.Now);
            var model = CreateModel();

            var twrTier = model.Current.Where(c => c.Callsign.EndsWith("_TWR")).ToList();
            Assert.Equal("EGLC_TWR", twrTier[0].Callsign);
        }

        [Fact]
        public void DistanceLeader_DoesNotFlapBeforeHysteresisWindowElapses()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            AddController("AAA_TWR", 20000, 0, 0);
            AddController("BBB_TWR", 20100, 0, 1);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0.4, now); // AAA closer initially
            var model = CreateModel(now: () => now);

            Assert.Equal("AAA_TWR", model.Current.First(c => c.Callsign.EndsWith("_TWR")).Callsign);

            // Ownship moves closer to BBB, but not for the full hysteresis window yet.
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0.9, now);
            _radio.RaiseChanged();
            Assert.Equal("AAA_TWR", model.Current.First(c => c.Callsign.EndsWith("_TWR")).Callsign);

            now = now.AddSeconds(13);
            _radio.RaiseChanged();

            Assert.Equal("BBB_TWR", model.Current.First(c => c.Callsign.EndsWith("_TWR")).Callsign);
        }

        [Fact]
        public void EmptyControllerList_ProducesEmptyRanking()
        {
            var model = CreateModel();

            Assert.Empty(model.Current);
        }

        [Theory]
        [InlineData(5, true)]  // within 10nm
        [InlineData(15, false)] // beyond 10nm
        public void Approaching_Ground_OnlyWhileOnGroundAndWithinThreshold(double nauticalMiles, bool expected)
        {
            AddController("EGLL_GND", 21800, 0, nauticalMiles / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.Equal(expected, model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_Ground_FalseWhileAirborne()
        {
            AddController("EGLL_GND", 21800, 0, 5 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 100, 2000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsApproaching);
        }

        [Theory]
        [InlineData(15, true)]  // within 20nm
        [InlineData(25, false)] // beyond 20nm
        public void Approaching_Tower_OnlyWhileAirborneAndWithinThreshold(double nauticalMiles, bool expected)
        {
            AddController("EGLL_TWR", 23725, 0, nauticalMiles / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 100, 2000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.Equal(expected, model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_Tower_FalseWhileOnGround()
        {
            AddController("EGLL_TWR", 23725, 0, 15 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_App_WithinOmniRadius_TrueRegardlessOfHeading()
        {
            AddController("EGLL_APP", 12900, 0, 35 / 60.0); // within 40nm
            // Heading due west (270) while the station is due east -- opposite direction.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 10000, 0, 270, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.True(model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_App_BetweenOmniAndOuterRadius_TrueWhenHeadingConverges()
        {
            AddController("EGLL_APP", 12900, 0, 45 / 60.0); // between 40nm and 50nm
            // Station is due east (bearing 90); heading straight toward it.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 10000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.True(model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_App_BetweenOmniAndOuterRadius_FalseWhenHeadingDiverges()
        {
            AddController("EGLL_APP", 12900, 0, 45 / 60.0); // between 40nm and 50nm
            // Station is due east (bearing 90); heading directly away from it.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 10000, 0, 270, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_App_BeyondOuterRadius_FalseRegardlessOfHeading()
        {
            AddController("EGLL_APP", 12900, 0, 55 / 60.0); // beyond 50nm
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 10000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_Delivery_NeverFlagged()
        {
            AddController("EGLL_DEL", 12100, 0, 1 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_Center_NeverFlaggedYet()
        {
            AddController("EGLL_CTR", 12345, 0, 1 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 10000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsApproaching);
        }

        [Fact]
        public void Approaching_NeverFlaggedWhenAControllerIsAlreadyCurrent()
        {
            AddController("EGLL_TWR", 23725, 0, 5 / 60.0);
            AddController("EGLL_APP", 12900, 0, 10 / 60.0);
            _radio.Current = new RadioState(23725, null, null, null, false, null, DateTimeOffset.Now);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 10000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.All(model.Current, c => Assert.False(c.IsApproaching));
        }
    }
}
