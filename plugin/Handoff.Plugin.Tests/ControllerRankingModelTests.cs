using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class ControllerRankingModelTests : IDisposable
    {
        private readonly string _configPath = PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");
        private readonly FakeBroker _broker = new FakeBroker();
        private readonly FakeRadioStateModel _radio = new FakeRadioStateModel();
        private readonly VatsimDataFeedModel _vatsimFeed = new VatsimDataFeedModel();
        private readonly ChatModel _chat;
        private readonly HandoffControllerStateModel _controllerState;
        private readonly PilotSessionModel _pilotSession = new PilotSessionModel();
        // Nonexistent cache directory -> VatGlassesDataModel.LoadFromDiskCache is a no-op -> Regions
        // stays empty, so tests that don't care about VATGlasses geometry exercise the distance/
        // route-match fallback path unchanged. VatGlasses-specific tests build their own model
        // via CreateVatGlassesDataModel below.
        private readonly VatGlassesDataModel _vatGlassesData = new VatGlassesDataModel(
            new OperationProgressModel(), cacheDirectory: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()));
        // Same "nonexistent cache directory -> stays empty" shape as _vatGlassesData above --
        // tests that don't care about vatspy geometry/naming exercise the unchanged fallback
        // path. VatSpy-specific tests build their own model via CreateVatSpyDataModel below.
        private readonly VatSpyDataModel _vatSpyData = new VatSpyDataModel(
            new OperationProgressModel(), cacheDirectory: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()));

        public ControllerRankingModelTests()
        {
            _chat = new ChatModel(_broker);
            _controllerState = new HandoffControllerStateModel(_broker, _chat);
        }

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        private ControllerRankingModel CreateModel(FlightPlanModel flightPlan = null, Func<DateTimeOffset> now = null, VatGlassesDataModel vatGlassesData = null, VatSpyDataModel vatSpyData = null) =>
            new ControllerRankingModel(_controllerState, _radio, flightPlan ?? NoOpFlightPlan(), _vatsimFeed, _pilotSession, vatGlassesData ?? _vatGlassesData, vatSpyData ?? _vatSpyData, now: now);

        private FlightPlanModel NoOpFlightPlan() =>
            new FlightPlanModel(new OperationProgressModel(), fetch: (u, n) => Task.FromResult(Plugin.FlightPlan.Empty), configPath: _configPath);

        private async Task<FlightPlanModel> CreateFlightPlanAsync(string origin, string destination, IReadOnlyList<FlightPlanWaypoint> waypoints = null)
        {
            var plan = new Plugin.FlightPlan("BAW123", origin, destination, null, waypoints);
            var model = new FlightPlanModel(new OperationProgressModel(), fetch: (u, n) => Task.FromResult(plan), configPath: _configPath);
            model.SetSimbriefCredentials("1", null);
            await model.RefreshAsync();
            return model;
        }

        private void AddController(string callsign, int frequency, double lat = 0, double lon = 0) =>
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs(callsign, frequency, lat, lon));

        // ---- Buckets 1-5 -------------------------------------------------------------------

        [Fact]
        public void TunedFrequency_MatchesController_MarksItCurrent()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.True(ranked[0].IsCurrent);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_GND").IsCurrent);
        }

        [Fact]
        public void BothComFrequenciesTunedToDifferentStations_BothMarkedCurrent()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_APP", 12345);
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, 12345, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_TWR").IsCurrent);
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_APP").IsCurrent);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_GND").IsCurrent);
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.Equal("EGLL_APP", ranked[1].Callsign);
        }

        [Fact]
        public void NothingTuned_NoControllerIsCurrent()
        {
            AddController("EGLL_TWR", 23725);
            var model = CreateModel();

            Assert.All(model.Current, c => Assert.False(c.IsCurrent));
        }

        [Fact]
        public void StandbyTunedController_RanksImmediatelyBelowCurrent()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_APP", 12345); // prepared in standby
            AddController("EGLL_GND", 21800); // unrelated, no signal
            _radio.Current = new RadioState(23725, null, 12345, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            var ranked = model.Current.ToList();
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.Equal("EGLL_APP", ranked[1].Callsign);
            Assert.True(ranked[1].IsStandbyTuned);
        }

        [Fact]
        public void BothComStandbyFrequenciesLoaded_Com1OrderedAheadOfCom2RegardlessOfTierOrAlpha()
        {
            // Callsigns/tiers deliberately chosen so the old tier-then-alpha-only fallback would
            // have ranked the COM2 match first (APP tier and "AAAA" both sort ahead of GND/"ZZZZ")
            // -- issue #21 Android-side feedback caught bucket 2 missing the COM1-before-COM2 rule
            // bucket 1 already had.
            AddController("AAAA_APP", 12345); // COM2 standby match
            AddController("ZZZZ_GND", 23725); // COM1 standby match
            _radio.Current = new RadioState(null, null, 23725, 12345, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            var ranked = model.Current.ToList();
            Assert.Equal("ZZZZ_GND", ranked[0].Callsign);
            Assert.Equal("AAAA_APP", ranked[1].Callsign);
            Assert.True(ranked[0].IsStandbyTuned);
            Assert.True(ranked[1].IsStandbyTuned);
        }

        [Fact]
        public void ContactMeController_RanksJustBelowCurrent()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_CTR", 12345); // far tier, would otherwise sort last
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_CTR", "contact me"));

            var ranked = model.Current;
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.Equal("EGLL_CTR", ranked[1].Callsign);
            Assert.True(ranked[1].IsContactMe);
        }

        [Fact]
        public void TuningContactMeFrequency_ClearsTheRequest()
        {
            AddController("EGLL_GND", 21800);
            var model = CreateModel();
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_GND", "contact me"));
            Assert.True(model.Current.Single().IsContactMe);

            _radio.Current = new RadioState(21800, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            _radio.RaiseChanged();

            Assert.False(model.Current.Single().IsContactMe);
            Assert.True(model.Current.Single().IsCurrent);
        }

        [Fact]
        public void SelcalActiveController_RanksJustBelowContactMe_AboveEverythingElse()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800); // contact-me, must still outrank SELCAL
            AddController("EGLL_CTR", 12345); // SELCAL, far tier, would otherwise sort last
            _radio.Current = new RadioState(23725, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_GND", "contact me"));
            _broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));

            var ranked = model.Current;
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.Equal("EGLL_GND", ranked[1].Callsign);
            Assert.Equal("EGLL_CTR", ranked[2].Callsign);
            Assert.True(ranked[2].IsSelcalActive);
        }

        [Fact]
        public void SelcalActiveController_NotClearedByTuningTheAlertingFrequency()
        {
            AddController("EGLL_CTR", 12345);
            var model = CreateModel();
            _broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));

            _radio.Current = new RadioState(12345, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            _radio.RaiseChanged();

            Assert.True(model.Current.Single(c => c.Callsign == "EGLL_CTR").IsCurrent);
            Assert.True(model.Current.Single(c => c.Callsign == "EGLL_CTR").IsSelcalActive);
        }

        [Fact]
        public void DismissSelcal_ClearsTheAlert()
        {
            AddController("EGLL_CTR", 12345);
            var model = CreateModel();
            _broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));
            Assert.True(model.Current.Single().IsSelcalActive);

            _controllerState.ClearSelcal("EGLL_CTR");

            Assert.False(model.Current.Single().IsSelcalActive);
        }

        [Fact]
        public void PinnedController_DoesNotOverrideTunedFrequency()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            _controllerState.SetPinnedController("EGLL_GND");

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_TWR").IsCurrent);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_GND").IsCurrent);
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_GND").IsPinned);
        }

        [Fact]
        public void PinnedController_RanksAheadOfUnrelatedTierCloserStation()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            AddController("EGLC_TWR", 20100); // unrelated, tier-closer to current (Tower)
            _radio.Current = new RadioState(23725, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            _controllerState.SetPinnedController("EGLL_GND");

            var ranked = model.Current.ToList();
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.Equal("EGLL_GND", ranked[1].Callsign);
            Assert.True(ranked.FindIndex(c => c.Callsign == "EGLL_GND") < ranked.FindIndex(c => c.Callsign == "EGLC_TWR"));
        }

        [Fact]
        public void ClearPinnedController_RemovesItFromPinnedBucket()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            _radio.Current = new RadioState(23725, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();
            _controllerState.SetPinnedController("EGLL_GND");

            _controllerState.ClearPinnedController("EGLL_GND");

            Assert.False(model.Current.Single(c => c.Callsign == "EGLL_GND").IsPinned);
        }

        [Fact]
        public void MultiplePinnedControllers_AllStayPinnedUntilIndividuallyCleared()
        {
            // Regression: pinning used to clear any other pinned callsign first (an undiscussed,
            // self-imposed single-slot design) -- the pilot should be able to pin as many
            // stations as they like, and only an explicit unpin (or the controller going
            // offline) should ever remove one, never pinning a second station.
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            AddController("EGLL_DEL", 12100);
            var model = CreateModel();

            _controllerState.SetPinnedController("EGLL_GND");
            _controllerState.SetPinnedController("EGLL_DEL");

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_GND").IsPinned);
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_DEL").IsPinned);

            _controllerState.ClearPinnedController("EGLL_GND");

            var afterClear = model.Current;
            Assert.False(afterClear.Single(c => c.Callsign == "EGLL_GND").IsPinned);
            Assert.True(afterClear.Single(c => c.Callsign == "EGLL_DEL").IsPinned);
        }

        [Fact]
        public void PinnedController_GoingOffline_ImmediatelyHiddenFromRanking()
        {
            // Hide-then-expire (issue #18): a disconnected station drops out of Controllers
            // (what ranking ever sees) right away, pin or not -- it only actually forgets the
            // pin/contact-me/SELCAL if it stays disconnected past the 5-minute expiry window,
            // which HandoffControllerStateModelTests covers directly.
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800);
            var model = CreateModel();
            _controllerState.SetPinnedController("EGLL_GND");

            _broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_GND"));

            Assert.DoesNotContain(model.Current, c => c.Callsign == "EGLL_GND");
        }

        [Fact]
        public void ChainDistance_AtisNeverOutranksAWrappedRealTier_EvenWhenTunedToCtr()
        {
            AddController("EGLL_ATIS", 12800);
            AddController("EGLL_DEL", 12100);
            AddController("EGLL_CTR", 12345);
            _radio.Current = new RadioState(12345, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            var model = CreateModel();

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.Equal("EGLL_ATIS", ranked.Last().Callsign);
        }

        // ---- Bucket 9 (fallback) -----------------------------------------------------------

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

            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0.9, now);
            _radio.RaiseChanged();
            Assert.Equal("AAA_TWR", model.Current.First(c => c.Callsign.EndsWith("_TWR")).Callsign);

            now = now.AddSeconds(13);
            _radio.RaiseChanged();

            Assert.Equal("BBB_TWR", model.Current.First(c => c.Callsign.EndsWith("_TWR")).Callsign);
        }

        [Fact]
        public async Task RouteMatch_PrefersVatsimFiledPlanOverSimbrief_WhenAvailable()
        {
            var simbriefPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            var vatsimFeed = CreateVatsimFeedWithPilot("BAW123", "LOWW", "EDDF");
            _pilotSession.OnNetworkConnected("BAW123", "1234567");
            AddController("EDDF_CTR", 12100);
            AddController("LZIB_CTR", 12200);

            var model = new ControllerRankingModel(_controllerState, _radio, simbriefPlan, vatsimFeed, _pilotSession, _vatGlassesData, _vatSpyData);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 500, 90, 48.5, 16.9, DateTimeOffset.Now);
            _radio.RaiseChanged();

            var ctrTier = model.Current.Where(c => c.Callsign.EndsWith("_CTR")).ToList();
            Assert.Equal("EDDF_CTR", ctrTier[0].Callsign);

            vatsimFeed.Stop();
        }

        private VatsimDataFeedModel CreateVatsimFeedWithPilot(string callsign, string departure, string arrival)
        {
            var snapshot = new VatsimDataFeedSnapshot(
                new List<VatsimControllerInfo>(),
                new List<VatsimPilotInfo> { new VatsimPilotInfo(callsign, departure, arrival) });
            var feed = new VatsimDataFeedModel(fetch: () => Task.FromResult(snapshot));
            using var raised = new ManualResetEventSlim();
            feed.Changed += (s, e) => raised.Set();
            feed.Start();
            raised.Wait(TimeSpan.FromSeconds(5));
            return feed;
        }

        private VatsimDataFeedModel CreateVatsimFeedWithController(string callsign, IReadOnlyList<string> textAtis)
        {
            var snapshot = new VatsimDataFeedSnapshot(
                new List<VatsimControllerInfo> { new VatsimControllerInfo(callsign, 1, "Test Controller", 4, 3, textAtis) },
                new List<VatsimPilotInfo>());
            var feed = new VatsimDataFeedModel(fetch: () => Task.FromResult(snapshot));
            using var raised = new ManualResetEventSlim();
            feed.Changed += (s, e) => raised.Set();
            feed.Start();
            raised.Wait(TimeSpan.FromSeconds(5));
            return feed;
        }

        // ---- Bucket 6 -- on-ground relevance -------------------------------------------------

        [Fact]
        public async Task Bucket6a_FlightPlanMatch_HighlightsAnyTier_UnconditionalOfDistance()
        {
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LOWI");
            AddController("LOWW_ATIS", 12800, 60, 60); // far away, ATIS/Other tier
            var model = CreateModel(flightPlan);

            Assert.True(model.Current.Single(c => c.Callsign == "LOWW_ATIS").IsHighlighted);
        }

        [Theory]
        [InlineData(3, true)]  // within 5nm
        [InlineData(8, false)] // beyond 5nm
        public void Bucket6b_GroundRadiusFallback_DelGndTwr(double nauticalMiles, bool expectedHighlighted)
        {
            AddController("EGLL_GND", 21800, 0, nauticalMiles / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.Equal(expectedHighlighted, model.Current.Single().IsHighlighted);
        }

        [Theory]
        [InlineData(15, true)]  // within 20nm
        [InlineData(25, false)] // beyond 20nm
        public void Bucket6c_GroundRadiusFallback_App(double nauticalMiles, bool expectedHighlighted)
        {
            AddController("EGLL_APP", 12345, 0, nauticalMiles / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.Equal(expectedHighlighted, model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket6d_Ctr_NeverHasRadiusFallback_EvenWhenVeryClose()
        {
            AddController("EGLL_CTR", 12345, 0, 1 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket6d_Ctr_PolygonContainment_HorizontalOnly_IgnoresAltitudeBand()
        {
            // Sector band is FL0-FL50 but ownship reports a wildly out-of-band pressure altitude
            // -- 6d ignores the vertical band entirely for CTR (real-world top-down coverage).
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.2, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 50));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 36000);
            var model = CreateModel(vatGlassesData: vatGlasses);

            Assert.True(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket6b_PolygonContainment_PreferredOverRadius_DelGndTwr()
        {
            // Well beyond the 5nm radius fallback, but inside the polygon.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.5, "GND", "POS_GND", "TEST_GND", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_GND", 21800, 0, 0.4); // ~24nm -- beyond the 5nm radius fallback
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 0);
            var model = CreateModel(vatGlassesData: vatGlasses);

            Assert.True(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket6e_ChainWalk_NothingTuned_LowestHighlightedTierWinsNext()
        {
            AddController("EGLL_GND", 21800, 0, 2 / 60.0); // within 5nm -> highlighted
            AddController("EGLL_TWR", 23725, 0, 2 / 60.0); // within 5nm -> highlighted too
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_GND").IsNext);
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_TWR").IsHighlighted);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_TWR").IsNext);
            Assert.False(ranked.Single(c => c.Callsign == "EGLL_TWR").IsLikelyNext);
        }

        [Fact]
        public void Bucket6_PlainHighlightedTies_OrderedByDistance_NotArbitrarily()
        {
            // Regression: ComputeGroundHighlight never populated HighlightResult.DistanceNm at
            // all, so ties silently fell back to whatever arbitrary order the underlying HashSet
            // enumerated in -- the doc's "IsHighlighted ordered by tier then distance" (Sort
            // order) had no effect for bucket 6. Tower (tuned to APP, tier above it) is already
            // "passed" so 6e's chain-walk skips both -- pure IsHighlighted-only ties, isolating
            // the ordering fix from any IsNext/IsLikelyNext tie-detection.
            AddController("ZZZZ_APP", 12345, 0, 0);
            AddController("LFPG_TWR", 23730, 0, 3 / 60.0); // ~3nm
            AddController("EGLL_TWR", 23725, 0, 1 / 60.0); // ~1nm -- closer, added second
            _radio.Current = new RadioState(12345, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            var ranked = model.Current.ToList();
            Assert.True(ranked.Single(c => c.Callsign == "EGLL_TWR").IsHighlighted);
            Assert.True(ranked.Single(c => c.Callsign == "LFPG_TWR").IsHighlighted);
            Assert.DoesNotContain(ranked, c => c.IsNext || c.IsLikelyNext);
            Assert.True(ranked.FindIndex(c => c.Callsign == "EGLL_TWR") < ranked.FindIndex(c => c.Callsign == "LFPG_TWR"));
        }

        [Fact]
        public void Bucket6_PolygonContainmentDeadband_StaysHighlightedJustBeyondTheEdge()
        {
            // Box edge sits at 0.1 degrees (~6nm) from center in every direction.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.1, "GND", "POS_GND", "TEST_GND", "TEST", minFl: 0, maxFl: 660));
            // Controller placed well outside the 5nm radius fallback (~30nm away) so every
            // assertion below is only ever satisfied by polygon containment, never masked by the
            // radius dead-band also passing.
            AddController("TEST_GND", 21800, 1, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 0); // inside
            var model = CreateModel(vatGlassesData: vatGlasses);
            Assert.True(model.Current.Single().IsHighlighted);

            // ~0.1nm past the edge -- genuinely outside the polygon now, but well within the 1nm
            // containment dead-band margin -- should stay highlighted rather than flap off.
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0.1017, 0, DateTimeOffset.Now, pressureAltitudeFeet: 0);
            _radio.RaiseChanged();
            Assert.True(model.Current.Single().IsHighlighted);

            // ~1.2nm past the edge -- should now genuinely drop out.
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0.12, 0, DateTimeOffset.Now, pressureAltitudeFeet: 0);
            _radio.RaiseChanged();
            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket6e_ChainWalk_StartsFromCurrentTier()
        {
            AddController("EGLL_DEL", 12100, 0, 0);
            AddController("EGLL_GND", 21800, 0, 2 / 60.0);
            _radio.Current = new RadioState(12100, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            _radio.RaiseChanged();

            Assert.True(model.Current.Single(c => c.Callsign == "EGLL_GND").IsNext);
        }

        [Fact]
        public async Task Bucket6e_ChainWalk_TiedSameTierCandidates_BothLikelyNext()
        {
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LOWI");
            AddController("LOWW_S_GND", 12150, 60, 60); // split-frequency GND, both route-matched
            AddController("LOWW_N_GND", 12160, 61, 61);
            var model = CreateModel(flightPlan);

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "LOWW_S_GND").IsLikelyNext);
            Assert.True(ranked.Single(c => c.Callsign == "LOWW_N_GND").IsLikelyNext);
            Assert.DoesNotContain(ranked, c => c.IsNext);
        }

        // ---- Bucket 7 -- airborne TWR/APP relevance ------------------------------------------

        [Fact]
        public void Bucket7a_Twr_NeverHighlighted_AboveAglCeiling()
        {
            AddController("EGLL_TWR", 23725, 0, 5 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now); // above 10000ft AGL
            var model = CreateModel();

            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Theory]
        [InlineData(8, true)]   // within the non-fpln 10nm highlight radius
        [InlineData(15, false)] // beyond it
        public void Bucket7a_Twr_HighlightRadius_NotOnFlightPlan(double nauticalMiles, bool expectedHighlighted)
        {
            AddController("EGLL_TWR", 23725, 0, nauticalMiles / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 5000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.Equal(expectedHighlighted, model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket7a_Twr_HighlightedButNotWithinInnerRadius_NoNextFlag()
        {
            AddController("EGLL_TWR", 23725, 0, 8 / 60.0); // within 10nm highlight, beyond 5nm inner
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 5000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            var ranked = model.Current.Single();
            Assert.True(ranked.IsHighlighted);
            Assert.False(ranked.IsNext);
            Assert.False(ranked.IsLikelyNext);
        }

        [Fact]
        public void Bucket7c_Twr_SingleWithinInnerRadius_ConfidentNext()
        {
            AddController("EGLL_TWR", 23725, 0, 3 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 5000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.True(model.Current.Single().IsNext);
        }

        [Fact]
        public void Bucket7c_Twr_TwoWithinInnerRadius_BothLikelyNext()
        {
            AddController("AAA_TWR", 20000, 0, 3 / 60.0);
            AddController("BBB_TWR", 20100, 0, 3.2 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 5000, 0, 90, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "AAA_TWR").IsLikelyNext);
            Assert.True(ranked.Single(c => c.Callsign == "BBB_TWR").IsLikelyNext);
            Assert.DoesNotContain(ranked, c => c.IsNext);
        }

        [Fact]
        public void Bucket7b_AppDep_FlatRadius_NoVatGlassesCoverage_FallbackCeiling()
        {
            AddController("EGLL_APP", 12345, 0, 25 / 60.0); // within 30nm
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000); // FL200, below fallback FL290 ceiling
            var model = CreateModel();

            Assert.True(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket7b_AppDep_FallbackCeiling_ExcludesAboveFl290()
        {
            AddController("EGLL_APP", 12345, 0, 25 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 480, 36000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 36000); // FL360
            var model = CreateModel();

            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket7b_AppDep_SectorOwnCeiling_ExcludesAboveItEvenBelowFallback()
        {
            // Sector's own published ceiling (FL100 + 50 margin = FL150) is tighter than the
            // flat FL290 fallback -- FL200 clears the fallback but must still be excluded. No
            // heading set -- isolates the 7b highlight-radius/ceiling check from 7c's separate
            // "entering" signal, which (per docs/controller-ranking.md's open question on APP/DEP
            // vertical gating) isn't altitude-gated at all today.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.3, "APP", "POS_APP", "EGLL_APP", "EGLL", minFl: 0, maxFl: 100));
            AddController("EGLL_APP", 12345, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0.2, DateTimeOffset.Now, pressureAltitudeFeet: 20000); // FL200
            var model = CreateModel(vatGlassesData: vatGlasses);

            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket7b_AppDep_SectorOwnCeiling_HighlightsBelowIt()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.3, "APP", "POS_APP", "EGLL_APP", "EGLL", minFl: 0, maxFl: 100));
            AddController("EGLL_APP", 12345, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0.2, DateTimeOffset.Now, pressureAltitudeFeet: 10000); // FL100 <= 150 ceiling
            var model = CreateModel(vatGlassesData: vatGlasses);

            Assert.True(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public async Task Bucket7c_AppDep_Entering_OnFlightPlan_SingleCandidate_ConfidentNext()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(2, 0, 0.2, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            // Destination (not origin) -- AGL 15000 > TakeoffAglThresholdFeet latches
            // _hasTakenOffThisSession, flipping routeAirport to the destination.
            var flightPlan = await CreateFlightPlanAsync("YYYY", "TEST");
            AddController("TEST_APP", 12345, 2, 0);
            // South of the box, heading due north -- converging, well within the 100nm cap.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 0, 1.5, 0, DateTimeOffset.Now);
            var model = CreateModel(flightPlan, vatGlassesData: vatGlasses);

            var ranked = model.Current.Single(c => c.Callsign == "TEST_APP");
            Assert.True(ranked.IsHighlighted);
            Assert.True(ranked.IsNext);
        }

        [Fact]
        public void Bucket7c_AppDep_Entering_NotOnFlightPlan_CappedToLikelyNext()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(2, 0, 0.2, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_APP", 12345, 2, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 0, 1.5, 0, DateTimeOffset.Now);
            var model = CreateModel(vatGlassesData: vatGlasses); // NoOpFlightPlan -- not on the plan

            var ranked = model.Current.Single(c => c.Callsign == "TEST_APP");
            Assert.True(ranked.IsHighlighted);
            Assert.False(ranked.IsNext);
            Assert.True(ranked.IsLikelyNext);
        }

        [Fact]
        public async Task Bucket7c_AppDep_MultipleEnteringCandidates_BothLikelyNext_EvenOnFlightPlan()
        {
            var json = TwoAppDepSectorsRegionJson();
            var vatGlasses = CreateVatGlassesDataModel(json);
            var flightPlan = await CreateFlightPlanAsync("AAA", "YYYY");
            AddController("AAA_APP", 12345, 2, 0);
            AddController("BBB_APP", 12355, 2, 0.3);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 0, 1.5, 0.15, DateTimeOffset.Now);
            var model = CreateModel(flightPlan, vatGlassesData: vatGlasses);

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "AAA_APP").IsLikelyNext);
            Assert.True(ranked.Single(c => c.Callsign == "BBB_APP").IsLikelyNext);
            Assert.DoesNotContain(ranked, c => c.IsNext);
        }

        // ---- Bucket 8 -- airborne CTR relevance ----------------------------------------------

        [Fact]
        public void Bucket8_Ctr_NoVatGlassesCoverage_NeverHighlighted_RegardlessOfProximity()
        {
            AddController("EDDM_HOF_CTR", 12345, 0, 1 / 60.0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000);
            var model = CreateModel();

            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket8a_Ctr_Satisfied_AlreadyInsideBand_HighlightedAndNext()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.5, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000); // inside band, level
            var model = CreateModel(vatGlassesData: vatGlasses);

            var ranked = model.Current.Single();
            Assert.True(ranked.IsHighlighted);
            Assert.True(ranked.IsNext);
        }

        [Fact]
        public void Bucket8a_Ctr_ContainmentDeadband_StaysHighlightedJustBeyondTheEdge()
        {
            // Box edge sits at 0.1 degrees (~6nm) from center in every direction. Controller
            // position itself is irrelevant here -- CTR has no radius fallback at all, so only
            // containment (and this dead-band) can ever produce a highlight.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.1, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_CTR", 13350, 5, 5);
            // Heading due east while moving north in latitude -- perpendicular to the box, so the
            // "converging" heading-projected check can't independently pick this up; only
            // containment (and its dead-band) is in play.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000); // inside
            var model = CreateModel(vatGlassesData: vatGlasses);
            Assert.True(model.Current.Single().IsHighlighted);

            // ~0.1nm past the edge -- within the 1nm containment dead-band margin -- stays highlighted.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0.1017, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000);
            _radio.RaiseChanged();
            Assert.True(model.Current.Single().IsHighlighted);

            // ~1.2nm past the edge -- beyond the margin -- genuinely drops out.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0.12, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000);
            _radio.RaiseChanged();
            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket8a_Ctr_Converging_SustainedDescentTowardBand_HighlightedOnceSustained()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            // Band tops out at FL200; ownship starts 2000ft above it, descending -- within the
            // widened 5000ft convergence margin once the trend has sustained for 5s.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.2, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 200));
            AddController("TEST_CTR", 13350, 0, 0);
            // South of the box, heading north, descending through 22000ft.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, -800, 0, -0.5, 0, now, pressureAltitudeFeet: 22000);
            var model = CreateModel(now: () => now, vatGlassesData: vatGlasses);

            Assert.False(model.Current.Single().IsHighlighted); // trend not sustained yet

            now = now.AddSeconds(6);
            _radio.RaiseChanged();

            Assert.True(model.Current.Single().IsHighlighted);
            Assert.True(model.Current.Single().IsNext);
        }

        [Fact]
        public void Bucket8a_Ctr_LevelFlightOutsideBand_NeverConverges()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.2, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 200));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 0, -0.5, 0, DateTimeOffset.Now, pressureAltitudeFeet: 22000); // level, above the band

            var model = CreateModel(vatGlassesData: vatGlasses);

            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket8b_TieBand_TwoOverlappingSatisfiedSectors_BothLikelyNext()
        {
            var json = TwoOverlappingCtrSectorsRegionJson();
            var vatGlasses = CreateVatGlassesDataModel(json);
            AddController("AAA_CTR", 13350, 0, 0);
            AddController("BBB_CTR", 13360, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000);
            var model = CreateModel(vatGlassesData: vatGlasses);

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "AAA_CTR").IsLikelyNext);
            Assert.True(ranked.Single(c => c.Callsign == "BBB_CTR").IsLikelyNext);
            Assert.DoesNotContain(ranked, c => c.IsNext);
        }

        [Fact]
        public void Bucket8c_Eta_LevelFlight_AnyAltitude_IsAvailable()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.5, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 5000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 5000); // low level cruise
            var model = CreateModel(vatGlassesData: vatGlasses);
            model.Current.ToList(); // force recompute (already computed in ctor, but be explicit)

            Assert.NotNull(model.EtaMinutes);
        }

        [Fact]
        public void Bucket8c_Eta_ClimbingBelowFl150_IsNull()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.5, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 8000, 1500, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 8000); // FL80, climbing
            var model = CreateModel(vatGlassesData: vatGlasses);

            Assert.Null(model.EtaMinutes);
        }

        [Fact]
        public void Bucket8c_Eta_ClimbingAboveFl150_IsAvailable()
        {
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.5, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 20000, 1500, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000); // FL200, climbing
            var model = CreateModel(vatGlassesData: vatGlasses);

            Assert.NotNull(model.EtaMinutes);
        }

        // ---- Numeric spatial dead-band (flapping protection) --------------------------------

        [Fact]
        public void Bucket6_RadiusDeadband_StaysHighlightedJustBeyondRawThreshold_ButNotWellBeyond()
        {
            AddController("EGLL_GND", 21800, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 4 / 60.0, 0, DateTimeOffset.Now); // ~4nm, inside the 5nm radius
            var model = CreateModel();
            Assert.True(model.Current.Single().IsHighlighted);

            // ~5.5nm -- past the raw 5nm threshold but within the dead-band's 20% exit margin
            // (6nm) -- should stay highlighted rather than flap off.
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 5.5 / 60.0, 0, DateTimeOffset.Now);
            _radio.RaiseChanged();
            Assert.True(model.Current.Single().IsHighlighted);

            // ~6.5nm -- past the dead-band's exit margin -- should now genuinely drop out.
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 6.5 / 60.0, 0, DateTimeOffset.Now);
            _radio.RaiseChanged();
            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket7b_AppDep_RadiusDeadband_StaysHighlightedJustBeyondRawThreshold()
        {
            AddController("EGLL_APP", 12345, 0, 25 / 60.0); // ~25nm, inside the 30nm radius
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000);
            var model = CreateModel();
            Assert.True(model.Current.Single().IsHighlighted);

            // ~33nm -- past the raw 30nm threshold but within the dead-band's 20% exit margin
            // (36nm) -- should stay highlighted.
            _broker.RaiseControllerLocationChanged(new ControllerLocationChangedEventArgs("EGLL_APP", 33 / 60.0, 0));
            _radio.RaiseChanged();
            Assert.True(model.Current.Single().IsHighlighted);

            // ~40nm -- past the dead-band's exit margin -- should now genuinely drop out.
            _broker.RaiseControllerLocationChanged(new ControllerLocationChangedEventArgs("EGLL_APP", 40 / 60.0, 0));
            _radio.RaiseChanged();
            Assert.False(model.Current.Single().IsHighlighted);
        }

        // Bucket 8b's tie-band reuses this exact same PassesDeadband helper (see
        // ComputeBucket8Highlight), already exercised end-to-end by the two tests above -- no
        // separate geometry-based test for it here. Constructing a *nonzero*-anchor tie scenario
        // needs the route/heading-projected "converging" path (the "satisfied"/contained case
        // used by the existing bucket 8b tie tests anchors at distance 0, which trivially
        // collapses the dead-band's multiplier to 0 too), and reliably placing two converging
        // sectors at controllable, adjustable distances is significantly more setup than this is
        // worth for what's otherwise identical logic to the two cases already covered.

        // ---- Diversion invalidates the filed route -----------------------------------------

        [Fact]
        public async Task Diversion_DestinationChange_ArmsPendingConfirmation_WithoutDroppingRouteYet()
        {
            // Heading deliberately null throughout -- isolates the route-projected check
            // entirely (FindEnteringOwnerMatches only falls back to heading when there ARE no
            // remaining waypoints), so a match before the diversion can only have come from the
            // filed route, and the absence of a match afterward can only mean that route stopped
            // being used, not that some other heading-based fallback happened to also miss.
            var waypoints = new List<FlightPlanWaypoint> { new FlightPlanWaypoint("WPT1", 4, 0) };
            Plugin.FlightPlan currentPlan = new Plugin.FlightPlan("BAW123", "AAAA", "YYYY", null, waypoints);
            var flightPlan = new FlightPlanModel(new OperationProgressModel(), fetch: (u, n) => Task.FromResult(currentPlan), configPath: _configPath);
            flightPlan.SetSimbriefCredentials("1", null);
            await flightPlan.RefreshAsync();

            // Box at (2,0) sits directly on the route from ownship (0,0) to the waypoint at (4,0).
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(2, 0, 0.2, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_APP", 12345, 2, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0, DateTimeOffset.Now);
            var model = CreateModel(flightPlan, vatGlassesData: vatGlasses);

            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);
            Assert.Null(model.PendingDiversionDestination);

            // Same waypoints (the stale route toward the original destination), only the
            // destination itself changes -- exactly the scenario the doc describes: a
            // controller-issued diversion updates the effective destination immediately, but
            // flightPlan.Waypoints on its own has no way to know it's now stale. The route keeps
            // being used until the pilot actually confirms the diversion, not the instant the
            // feed shows a changed destination.
            currentPlan = new Plugin.FlightPlan("BAW123", "AAAA", "ZZZZ", null, waypoints);
            await flightPlan.RefreshAsync();

            Assert.Equal("ZZZZ", model.PendingDiversionDestination);
            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);

            model.ConfirmDiversion();

            Assert.Null(model.PendingDiversionDestination);
            Assert.False(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);
        }

        [Fact]
        public async Task Diversion_Dismissed_KeepsUsingFiledRoute()
        {
            var waypoints = new List<FlightPlanWaypoint> { new FlightPlanWaypoint("WPT1", 4, 0) };
            Plugin.FlightPlan currentPlan = new Plugin.FlightPlan("BAW123", "AAAA", "YYYY", null, waypoints);
            var flightPlan = new FlightPlanModel(new OperationProgressModel(), fetch: (u, n) => Task.FromResult(currentPlan), configPath: _configPath);
            flightPlan.SetSimbriefCredentials("1", null);
            await flightPlan.RefreshAsync();

            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(2, 0, 0.2, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_APP", 12345, 2, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0, DateTimeOffset.Now);
            var model = CreateModel(flightPlan, vatGlassesData: vatGlasses);

            currentPlan = new Plugin.FlightPlan("BAW123", "AAAA", "ZZZZ", null, waypoints);
            await flightPlan.RefreshAsync();
            Assert.Equal("ZZZZ", model.PendingDiversionDestination);

            model.DismissDiversion();

            Assert.Null(model.PendingDiversionDestination);
            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);
        }

        // ---- Issue #22: abeam-point waypoint sequencing --------------------------------------

        [Fact]
        public async Task AbeamSequencing_DirectToPastAWaypoint_ExcludesItOnlyAfterSustainedCrossing()
        {
            // B sits just off the direct A->C line (a shallow dogleg -- a normal route shape, not
            // a huge detour), so plain nearest-by-distance would keep reading it as "nearest" for
            // a long stretch after a direct-to-C clearance has flown straight past it.
            var waypoints = new List<FlightPlanWaypoint> { new FlightPlanWaypoint("B", 2, 0.3), new FlightPlanWaypoint("C", 4, 0) };
            var flightPlan = await CreateFlightPlanAsync("AAAA", "CCCC", waypoints);

            // Box centered exactly on B -- only reachable via a leg that actually heads to B.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(2, 0.3, 0.05, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_APP", 12345, 2, 0.3);

            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            // Heading deliberately null -- isolates the route-projected check (see the diversion
            // test above for why), and ownship starts at A itself so SequenceRemainingWaypoints'
            // anchor initializes there.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0, now);
            var model = CreateModel(flightPlan, now: () => now, vatGlassesData: vatGlasses);

            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);

            // Direct-to C, cutting the corner: ownship is now well past B's abeam point on the
            // A->B leg, despite B (35nm away) still being nearer than C (90nm away).
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 2.5, 0, now);
            _radio.RaiseChanged();
            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted); // not yet -- sustained-disagreement hysteresis hasn't elapsed

            now = now.AddSeconds(13);
            _radio.RaiseChanged();
            Assert.False(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted); // committed: B sequenced past, remaining route no longer detours through it
        }

        [Fact]
        public async Task AbeamSequencing_NewFlightPlanFetch_ResetsCommittedIndex()
        {
            var waypoints = new List<FlightPlanWaypoint> { new FlightPlanWaypoint("B", 2, 0.3), new FlightPlanWaypoint("C", 4, 0) };
            Plugin.FlightPlan currentPlan = new Plugin.FlightPlan("BAW123", "AAAA", "CCCC", null, waypoints);
            var flightPlan = new FlightPlanModel(new OperationProgressModel(), fetch: (u, n) => Task.FromResult(currentPlan), configPath: _configPath);
            flightPlan.SetSimbriefCredentials("1", null);
            await flightPlan.RefreshAsync();

            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(2, 0.3, 0.05, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_APP", 12345, 2, 0.3);

            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0, now);
            var model = CreateModel(flightPlan, now: () => now, vatGlassesData: vatGlasses);

            // Sequence past B and let the commit land, same as above.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 2.5, 0, now);
            _radio.RaiseChanged();
            now = now.AddSeconds(13);
            _radio.RaiseChanged();
            Assert.False(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);

            // A fresh SimBrief fetch (a new FlightPlan instance with its own new Waypoints list,
            // same route by value) is a genuinely new plan as far as this model is concerned --
            // sequencing starts over rather than staying stuck at the previously committed index.
            var refetchedWaypoints = new List<FlightPlanWaypoint> { new FlightPlanWaypoint("B", 2, 0.3), new FlightPlanWaypoint("C", 4, 0) };
            currentPlan = new Plugin.FlightPlan("BAW123", "AAAA", "CCCC", null, refetchedWaypoints);
            await flightPlan.RefreshAsync();
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0, now);
            _radio.RaiseChanged();

            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);
        }

        [Fact]
        public async Task AbeamSequencing_DirectToFarAheadWaypoint_RecoversViaProximity()
        {
            // B and C sit due east of A (bearing 90 deg), while the direct-to target D sits
            // southwest (bearing ~225 deg) -- more than 90 degrees off, so
            // AlongTrackDistanceNm (negative "if the projection falls behind `from`", see its
            // doc comment) stays negative for both B and C no matter how far towards D ownship
            // flies. The anchor-relative sweep can never naturally advance past them -- exactly
            // the stuck case #66 reported -- so only the proximity catch-up can recover.
            var waypoints = new List<FlightPlanWaypoint>
            {
                new FlightPlanWaypoint("B", 0, 0.8),
                new FlightPlanWaypoint("C", 0, 1.6),
                new FlightPlanWaypoint("D", -1, -1),
            };
            var flightPlan = await CreateFlightPlanAsync("AAAA", "DDDD", waypoints);

            // Box centered exactly on B -- only reachable via a leg that actually heads to B (same
            // route-projected entering check as the sustained-crossing test above). Ownship's
            // post-direct-to position is still within RouteApproachMaxNauticalMiles (150nm) of B
            // along the (uncommitted) remaining route, so this isolates the committed-index bug
            // rather than an unrelated route-distance cap.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0.8, 0.2, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_APP", 12345, 0, 0.8);

            var now = new DateTimeOffset(2026, 8, 1, 12, 0, 0, TimeSpan.Zero);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0, now);
            var model = CreateModel(flightPlan, now: () => now, vatGlassesData: vatGlasses);

            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);

            // Direct-to D, cutting across to the southwest -- the opposite general direction from
            // B/C.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, -1, -1, now);
            _radio.RaiseChanged();
            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted); // not yet -- sustained-disagreement hysteresis hasn't elapsed

            now = now.AddSeconds(13);
            _radio.RaiseChanged();
            Assert.False(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted); // committed: proximity catch-up sequenced past B, C, and D in one step
        }

        [Fact]
        public async Task AbeamSequencing_NearMissNotOverflight_DoesNotFalsePositive()
        {
            // Same stuck-sweep setup as above, but ownship only ever comes within ~6nm of D --
            // outside WaypointOverflightRadiusNm (2nm) -- so the proximity catch-up must not
            // fire either. Guards the "tight enough not to false-positive on a route that merely
            // passes near a waypoint without actually sequencing through it" requirement.
            var waypoints = new List<FlightPlanWaypoint>
            {
                new FlightPlanWaypoint("B", 0, 0.8),
                new FlightPlanWaypoint("C", 0, 1.6),
                new FlightPlanWaypoint("D", -1, -1),
            };
            var flightPlan = await CreateFlightPlanAsync("AAAA", "DDDD", waypoints);

            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0.8, 0.2, "APP", "POS_APP", "TEST_APP", "TEST", minFl: 0, maxFl: 660));
            AddController("TEST_APP", 12345, 0, 0.8);

            var now = new DateTimeOffset(2026, 8, 1, 12, 0, 0, TimeSpan.Zero);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, 0, 0, now);
            var model = CreateModel(flightPlan, now: () => now, vatGlassesData: vatGlasses);

            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted);

            // ~6nm short of D (0.1 degrees longitude at this latitude), not an overflight.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, null, -1, -0.9, now);
            _radio.RaiseChanged();
            now = now.AddSeconds(13);
            _radio.RaiseChanged();
            Assert.True(model.Current.Single(c => c.Callsign == "TEST_APP").IsHighlighted); // still stuck -- correctly so, this was never an overflight
        }

        // ---- Issue #11: vatspy station names and FIR-polygon fallback -----------------------

        [Fact]
        public void Bucket6d_Ctr_NoVatGlassesCoverage_VatSpyPolygonContainment_HighlightedAndNext()
        {
            var vatSpy = CreateVatSpyDataModel(VatSpyBoundaryGeoJson(0, 0, 0.2, "TEST"), VatSpyDatWithFir("TEST", "Test Center", "TEST"));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel(vatSpyData: vatSpy); // default (empty) _vatGlassesData -- vatspy is the only coverage.

            var ranked = model.Current.Single();
            Assert.True(ranked.IsHighlighted);
            Assert.True(ranked.IsNext);
        }

        [Fact]
        public void Bucket8a_Ctr_NoVatGlassesCoverage_VatSpySatisfied_HighlightedAndNext()
        {
            var vatSpy = CreateVatSpyDataModel(VatSpyBoundaryGeoJson(0, 0, 0.5, "TEST"), VatSpyDatWithFir("TEST", "Test Center", "TEST"));
            AddController("TEST_CTR", 13350, 0, 0);
            // Airborne, inside the boundary -- vatspy has no altitude band at all, so this is
            // "satisfied" regardless of altitude/vertical trend.
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 38000);
            var model = CreateModel(vatSpyData: vatSpy);

            var ranked = model.Current.Single();
            Assert.True(ranked.IsHighlighted);
            Assert.True(ranked.IsNext);
        }

        [Fact]
        public void Bucket8a_Ctr_VatGlassesKnowsController_VatSpyFallbackSkipped_EvenIfVatSpyWouldContain()
        {
            // VATGlasses has a sector for TEST_CTR (via matching prefix+tier), but it's centered
            // far from ownship -- not currently containing it. Vatspy's boundary, meanwhile, DOES
            // contain ownship. Precedence rule: VATGlasses stays preferred wherever it has *any*
            // sector data for a controller, so the vatspy fallback must not kick in here even
            // though it would (on its own) say "contained."
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(50, 50, 0.2, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 660));
            var vatSpy = CreateVatSpyDataModel(VatSpyBoundaryGeoJson(0, 0, 0.5, "TEST"), VatSpyDatWithFir("TEST", "Test Center", "TEST"));
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000);
            var model = CreateModel(vatGlassesData: vatGlasses, vatSpyData: vatSpy);

            Assert.False(model.Current.Single().IsHighlighted);
        }

        [Fact]
        public void Bucket6d_Ctr_VatSpyAmbiguousMultipleOnlineControllers_BothLikelyNext()
        {
            // Two simultaneously-online CTR positions sharing one FIR's callsign prefix -- same
            // ambiguity VatGlassesOwnershipResolver.ResolveOnlineControllers already handles for
            // VATGlasses (issue #17); VatSpyOwnershipResolver must return both, not guess.
            var vatSpy = CreateVatSpyDataModel(VatSpyBoundaryGeoJson(0, 0, 0.2, "ESMM"), VatSpyDatWithFir("ESMM", "Sweden Control", "ESMM"));
            AddController("ESMM_5_CTR", 13350, 0, 0);
            AddController("ESMM_7_CTR", 13360, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel(vatSpyData: vatSpy);

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "ESMM_5_CTR").IsLikelyNext);
            Assert.True(ranked.Single(c => c.Callsign == "ESMM_7_CTR").IsLikelyNext);
            Assert.DoesNotContain(ranked, c => c.IsNext);
        }

        [Fact]
        public void Bucket9_Ctr_HorizontalContainmentDespiteAltitudeMismatch_OrdersAheadOfCloserUncontainedPeer()
        {
            // VATGlasses band is FL0-FL50 (0-5000ft), horizontally centered on and containing
            // ownship -- but ownship is level at 20000ft, well outside the band and not
            // converging (no vertical trend), so bucket 8a's altitude-gated "satisfied"/
            // "converging" checks both miss it entirely; it falls to bucket 9. A second CTR
            // controller with no polygon coverage at all sits much closer by raw distance. Bucket
            // 9's horizontal-only containment preference (issue #11) should still sort the
            // contained-but-out-of-band controller first, ahead of the closer uncontained one.
            var vatGlasses = CreateVatGlassesDataModel(GroundBoxRegionJson(0, 0, 0.3, "CTR", "POS_CTR", "TEST_CTR", "TEST", minFl: 0, maxFl: 50));
            AddController("TEST_CTR", 13350, 0.2, 0); // inside the box laterally
            AddController("OTHER_CTR", 13360, 0, 0.01); // ~0.6nm from ownship -- much closer, no polygon coverage
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0, DateTimeOffset.Now, pressureAltitudeFeet: 20000);
            var model = CreateModel(vatGlassesData: vatGlasses);

            var ranked = model.Current.ToList();
            Assert.False(ranked.Single(c => c.Callsign == "TEST_CTR").IsHighlighted);
            Assert.False(ranked.Single(c => c.Callsign == "OTHER_CTR").IsHighlighted);
            var testIndex = ranked.FindIndex(c => c.Callsign == "TEST_CTR");
            var otherIndex = ranked.FindIndex(c => c.Callsign == "OTHER_CTR");
            Assert.True(testIndex < otherIndex);
        }

        [Fact]
        public void StationName_AtisText_PreferredOverVatSpyComposedName()
        {
            var vatSpy = CreateVatSpyDataModel(VatSpyBoundaryGeoJson(0, 0, 0.2, "TEST"), VatSpyDatWithFir("TEST", "Test Place", "TEST"));
            var vatsimFeed = CreateVatsimFeedWithController("TEST_CTR", new[] { "Custom Radar", "Some boilerplate" });
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = new ControllerRankingModel(_controllerState, _radio, NoOpFlightPlan(), vatsimFeed, _pilotSession, _vatGlassesData, vatSpy);

            Assert.Equal("Custom Radar", model.Current.Single().StationName);
            vatsimFeed.Stop();
        }

        [Fact]
        public void TextAtis_FlowsThroughToRankedController_Unprocessed()
        {
            var lines = new[] { "Custom Radar", "Some boilerplate", "vats.im/feedback" };
            var vatsimFeed = CreateVatsimFeedWithController("TEST_CTR", lines);
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = new ControllerRankingModel(_controllerState, _radio, NoOpFlightPlan(), vatsimFeed, _pilotSession, _vatGlassesData, _vatSpyData);

            Assert.Equal(lines, model.Current.Single().TextAtis);
            vatsimFeed.Stop();
        }

        [Fact]
        public void TextAtis_NoAtisSet_IsNull()
        {
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = CreateModel();

            Assert.Null(model.Current.Single().TextAtis);
        }

        [Fact]
        public void StationName_AtisTextDoesNotParseCleanly_FallsBackToVatSpyComposedName()
        {
            var vatSpy = CreateVatSpyDataModel(VatSpyBoundaryGeoJson(0, 0, 0.2, "TEST"), VatSpyDatWithFir("TEST", "Test Place", "TEST"));
            var vatsimFeed = CreateVatsimFeedWithController("TEST_CTR", new[] { "Welcome to our airspace, enjoy the flight today!" });
            AddController("TEST_CTR", 13350, 0, 0);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            var model = new ControllerRankingModel(_controllerState, _radio, NoOpFlightPlan(), vatsimFeed, _pilotSession, _vatGlassesData, vatSpy);

            Assert.Equal("Test Place Center", model.Current.Single().StationName);
            vatsimFeed.Stop();
        }

        // ---- VatSpy fixture helpers -----------------------------------------------------------

        /// <summary>A square FIR boundary feature (GeoJSON MultiPolygon, plain decimal [lon, lat] pairs) of the given half-width (degrees) centered on (centerLat, centerLon).</summary>
        private static string VatSpyBoundaryGeoJson(double centerLat, double centerLon, double halfWidthDeg, string boundaryId) => $@"{{
            ""features"": [
                {{ ""properties"": {{ ""id"": ""{boundaryId}"" }}, ""geometry"": {{ ""type"": ""MultiPolygon"", ""coordinates"": [[[
                    [{(centerLon - halfWidthDeg).ToString(CultureInfo.InvariantCulture)}, {(centerLat - halfWidthDeg).ToString(CultureInfo.InvariantCulture)}],
                    [{(centerLon + halfWidthDeg).ToString(CultureInfo.InvariantCulture)}, {(centerLat - halfWidthDeg).ToString(CultureInfo.InvariantCulture)}],
                    [{(centerLon + halfWidthDeg).ToString(CultureInfo.InvariantCulture)}, {(centerLat + halfWidthDeg).ToString(CultureInfo.InvariantCulture)}],
                    [{(centerLon - halfWidthDeg).ToString(CultureInfo.InvariantCulture)}, {(centerLat + halfWidthDeg).ToString(CultureInfo.InvariantCulture)}]
                ]]] }} }}
            ]
        }}";

        /// <summary>A minimal VATSpy.dat with a single `[FIRs]` row -- no `[Countries]`/`[Airports]` needed for the ranking-fallback tests above (only the naming tests care about those).</summary>
        private static string VatSpyDatWithFir(string boundaryId, string name, string callsignPrefix) =>
            $"[FIRs]\n{boundaryId}|{name}|{callsignPrefix}|{boundaryId}\n";

        // ---- VATGlasses fixture helpers ------------------------------------------------------

        /// <summary>Converts a decimal-degree coordinate to VATGlasses' fixed-width DMS string format (see DmsCoordinate) -- avoids hand-computing DMS strings for every fixture point.</summary>
        private static string Dms(double decimalDegrees, bool isLongitude)
        {
            var negative = decimalDegrees < 0;
            var abs = Math.Abs(decimalDegrees);
            var degrees = (int)abs;
            var minutesDecimal = (abs - degrees) * 60;
            var minutes = (int)minutesDecimal;
            var seconds = (int)Math.Round((minutesDecimal - minutes) * 60);
            var degreeDigits = isLongitude ? 3 : 2;
            var s = degrees.ToString(CultureInfo.InvariantCulture).PadLeft(degreeDigits, '0')
                + minutes.ToString(CultureInfo.InvariantCulture).PadLeft(2, '0')
                + seconds.ToString(CultureInfo.InvariantCulture).PadLeft(2, '0');
            return negative ? "-" + s : s;
        }

        private static string Point(double lat, double lon) =>
            $"[\"{Dms(lat, false)}\",\"{Dms(lon, true)}\"]";

        /// <summary>A square box of the given half-width (degrees) centered on (centerLat, centerLon), owned by a single position of the given type/callsign/prefix.</summary>
        private static string GroundBoxRegionJson(double centerLat, double centerLon, double halfWidthDeg, string type, string positionId, string callsign, string prefix, double? minFl, double? maxFl) => $@"{{
            ""airports"": {{}},
            ""airspace"": [
                {{
                    ""id"": ""S1"",
                    ""group"": ""{type}"",
                    ""owner"": [""{positionId}""],
                    ""sectors"": [
                        {{ ""min"": {minFl?.ToString(CultureInfo.InvariantCulture) ?? "null"}, ""max"": {maxFl?.ToString(CultureInfo.InvariantCulture) ?? "null"}, ""points"": [
                            {Point(centerLat - halfWidthDeg, centerLon - halfWidthDeg)},
                            {Point(centerLat - halfWidthDeg, centerLon + halfWidthDeg)},
                            {Point(centerLat + halfWidthDeg, centerLon + halfWidthDeg)},
                            {Point(centerLat + halfWidthDeg, centerLon - halfWidthDeg)}
                        ] }}
                    ]
                }}
            ],
            ""positions"": {{
                ""{positionId}"": {{ ""type"": ""{type}"", ""frequency"": ""133.500"", ""callsign"": ""{callsign}"", ""pre"": [""{prefix}""] }}
            }}
        }}";

        /// <summary>Two side-by-side APP boxes (AAA/BBB), both plausibly entering from due south -- bucket 7c's "multiple simultaneously-entering candidates" tie scenario.</summary>
        private static string TwoAppDepSectorsRegionJson() => $@"{{
            ""airports"": {{}},
            ""airspace"": [
                {{ ""id"": ""S_AAA"", ""group"": ""APP"", ""owner"": [""POS_AAA""], ""sectors"": [
                    {{ ""min"": 0, ""max"": 660, ""points"": [{Point(1.8, -0.2)},{Point(1.8, 0.2)},{Point(2.2, 0.2)},{Point(2.2, -0.2)}] }}
                ] }},
                {{ ""id"": ""S_BBB"", ""group"": ""APP"", ""owner"": [""POS_BBB""], ""sectors"": [
                    {{ ""min"": 0, ""max"": 660, ""points"": [{Point(1.8, 0.1)},{Point(1.8, 0.5)},{Point(2.2, 0.5)},{Point(2.2, 0.1)}] }}
                ] }}
            ],
            ""positions"": {{
                ""POS_AAA"": {{ ""type"": ""APP"", ""frequency"": ""124.900"", ""callsign"": ""AAA_APP"", ""pre"": [""AAA""] }},
                ""POS_BBB"": {{ ""type"": ""APP"", ""frequency"": ""125.900"", ""callsign"": ""BBB_APP"", ""pre"": [""BBB""] }}
            }}
        }}";

        /// <summary>Two overlapping CTR boxes both containing the origin -- bucket 8b's tie-band scenario (both "satisfied" at distance 0).</summary>
        private static string TwoOverlappingCtrSectorsRegionJson() => $@"{{
            ""airports"": {{}},
            ""airspace"": [
                {{ ""id"": ""S_AAA"", ""group"": ""CTR"", ""owner"": [""POS_AAA""], ""sectors"": [
                    {{ ""min"": 0, ""max"": 660, ""points"": [{Point(-0.5, -0.5)},{Point(-0.5, 0.5)},{Point(0.5, 0.5)},{Point(0.5, -0.5)}] }}
                ] }},
                {{ ""id"": ""S_BBB"", ""group"": ""CTR"", ""owner"": [""POS_BBB""], ""sectors"": [
                    {{ ""min"": 0, ""max"": 660, ""points"": [{Point(-0.4, -0.4)},{Point(-0.4, 0.4)},{Point(0.4, 0.4)},{Point(0.4, -0.4)}] }}
                ] }}
            ],
            ""positions"": {{
                ""POS_AAA"": {{ ""type"": ""CTR"", ""frequency"": ""133.500"", ""callsign"": ""AAA_CTR"", ""pre"": [""AAA""] }},
                ""POS_BBB"": {{ ""type"": ""CTR"", ""frequency"": ""134.500"", ""callsign"": ""BBB_CTR"", ""pre"": [""BBB""] }}
            }}
        }}";

        private static VatGlassesDataModel CreateVatGlassesDataModel(string regionJson) =>
            CreateVatGlassesDataModelAsync(regionJson).GetAwaiter().GetResult();

        private static async Task<VatGlassesDataModel> CreateVatGlassesDataModelAsync(string regionJson)
        {
            var model = new VatGlassesDataModel(
                new OperationProgressModel(),
                cacheDirectory: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()),
                fetchLatestSha: () => Task.FromResult("sha1"),
                listFiles: () => Task.FromResult<IReadOnlyList<VatGlassesDataFile>>(new List<VatGlassesDataFile> { new VatGlassesDataFile("test.json", "http://test/test.json") }),
                fetchFile: url => Task.FromResult(regionJson));
            await model.SyncAsync();
            return model;
        }

        private static VatSpyDataModel CreateVatSpyDataModel(string boundariesJson, string vatSpyDat) =>
            CreateVatSpyDataModelAsync(boundariesJson, vatSpyDat).GetAwaiter().GetResult();

        private static async Task<VatSpyDataModel> CreateVatSpyDataModelAsync(string boundariesJson, string vatSpyDat)
        {
            var model = new VatSpyDataModel(
                new OperationProgressModel(),
                cacheDirectory: PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()),
                fetchLatestSha: () => Task.FromResult("sha1"),
                fetchBoundariesJson: () => Task.FromResult(boundariesJson),
                fetchVatSpyDat: () => Task.FromResult(vatSpyDat));
            await model.SyncAsync();
            return model;
        }
    }
}
