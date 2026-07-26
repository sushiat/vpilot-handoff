using System;
using System.Collections.Generic;
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
        private readonly string _configPath = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");
        private readonly FakeBroker _broker = new FakeBroker();
        private readonly FakeRadioStateModel _radio = new FakeRadioStateModel();
        private readonly VatsimDataFeedModel _vatsimFeed = new VatsimDataFeedModel();
        private readonly ControllerStateModel _controllers;
        private readonly ChatModel _chat;
        private readonly ContactMeModel _contactMe;
        private readonly SelcalActiveModel _selcalActive;
        private readonly PilotSessionModel _pilotSession = new PilotSessionModel();

        public ControllerRankingModelTests()
        {
            _controllers = new ControllerStateModel(_broker);
            _chat = new ChatModel(_broker);
            _contactMe = new ContactMeModel(_chat, _controllers);
            _selcalActive = new SelcalActiveModel(_chat, _controllers);
        }

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        private ControllerRankingModel CreateModel(FlightPlanModel flightPlan = null, Func<DateTimeOffset> now = null) =>
            new ControllerRankingModel(_controllers, _radio, flightPlan ?? NoOpFlightPlan(), _vatsimFeed, _contactMe, _selcalActive, _pilotSession, now: now);

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
        public void SelcalActiveController_RanksJustBelowContactMe_AboveEverythingElse()
        {
            AddController("EGLL_TWR", 23725);
            AddController("EGLL_GND", 21800); // contact-me, must still outrank SELCAL
            AddController("EGLL_CTR", 12345); // SELCAL, far tier, would otherwise sort last
            _radio.Current = new RadioState(23725, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel();

            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_GND", "contact me"));
            _broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));

            var ranked = model.Current;
            Assert.Equal("EGLL_TWR", ranked[0].Callsign);
            Assert.Equal("EGLL_GND", ranked[1].Callsign);
            Assert.Equal("EGLL_CTR", ranked[2].Callsign);
        }

        [Fact]
        public void SelcalActiveController_NotClearedByTuningTheAlertingFrequency()
        {
            // Real SELCAL requires the pilot to already be tuned (volume down) for the pulse to
            // reach the aircraft at all -- tune-match is trivially always true and must not clear
            // the alert the way it clears a contact-me request.
            AddController("EGLL_CTR", 12345);
            var model = CreateModel();
            _broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));

            _radio.Current = new RadioState(12345, null, null, null, false, null, DateTimeOffset.Now);
            _radio.RaiseChanged();

            Assert.True(model.Current.Single(c => c.Callsign == "EGLL_CTR").IsCurrent);
            Assert.True(_selcalActive.IsActive("EGLL_CTR"));
        }

        [Fact]
        public void DismissSelcal_ClearsTheAlert()
        {
            AddController("EGLL_CTR", 12345);
            var model = CreateModel();
            _broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));
            Assert.True(model.Current.Single(c => c.Callsign == "EGLL_CTR").IsCurrent == false); // sanity: not current

            _selcalActive.Clear("EGLL_CTR");

            Assert.False(_selcalActive.IsActive("EGLL_CTR"));
        }

        [Fact]
        public void ChainDistance_AtisNeverOutranksAWrappedRealTier_EvenWhenTunedToCtr()
        {
            // Regression: Other (ATIS)'s raw ordinal (5) is higher than every real tier's, so its
            // un-wrapped ChainDistance from CTR (5-4=1) used to be smaller than a wrapped tier's
            // (e.g. Delivery: (0-4)+100=96) -- ATIS would sort right after current instead of
            // last, exactly backwards.
            AddController("EGLL_ATIS", 12800);
            AddController("EGLL_DEL", 12100);
            AddController("EGLL_CTR", 12345);
            _radio.Current = new RadioState(12345, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel();

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.Equal("EGLL_ATIS", ranked.Last().Callsign);
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
        public async Task NextTierCandidate_RouteMatchedStationOnly_UnrelatedAirportSameTierNotFlagged()
        {
            // Regression: parked at LOWW with a LOWW-LZIB plan and DEL tuned, only LOWW_GND
            // should be "likely next" -- an unrelated airport's GND (LZIB_GND) sharing the next
            // tier must not be flagged just because it's also Ground.
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            AddController("LOWW_DEL", 12100);
            AddController("LOWW_GND", 12150);
            AddController("LZIB_GND", 12200);
            _radio.Current = new RadioState(12100, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel(flightPlan);

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "LOWW_GND").IsLikelyNextCandidate);
            Assert.False(ranked.Single(c => c.Callsign == "LZIB_GND").IsLikelyNextCandidate);
        }

        [Fact]
        public async Task NextTierCandidate_OutranksTierCloserButUnrelatedController()
        {
            // Regression: LZIB_GND (tier Ground, one chain-step from nothing-tuned) must not
            // outrank LOWW_TWR (tier Tower, flagged as the actual next candidate) just because
            // Ground sits earlier in the chain -- "likely next" must win the overall ranking,
            // not just get the badge while still sorting behind an unrelated closer-tier station.
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LOWI");
            AddController("LZIB_GND", 12200);
            AddController("LOWW_TWR", 12180);
            var model = CreateModel(flightPlan);

            var ranked = model.Current;
            Assert.Equal("LOWW_TWR", ranked[0].Callsign);
            Assert.True(ranked[0].IsLikelyNextCandidate);
            Assert.Equal("LZIB_GND", ranked[1].Callsign);
        }

        [Fact]
        public async Task NextTierCandidate_NothingTuned_UnrelatedGlobalDelDoesNotShadowLocalTower()
        {
            // Regression: nothing tuned, only LOWW_TWR online (no LOWW DEL/GND at all), but some
            // unrelated airport's DEL is connected elsewhere on the network. The old global
            // "lowest tier present anywhere" search would lock onto that foreign DEL and never
            // even look at Tower. LOWW_TWR route-matches the loaded flight plan and must win.
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LOWI");
            AddController("EDDF_DEL", 12100); // unrelated airport, lowest tier present globally
            AddController("LOWW_TWR", 12180);
            var model = CreateModel(flightPlan);

            Assert.True(model.Current.Single(c => c.Callsign == "LOWW_TWR").IsLikelyNextCandidate);
            Assert.False(model.Current.Single(c => c.Callsign == "EDDF_DEL").IsLikelyNextCandidate);
        }

        [Fact]
        public async Task MomentaryOnGroundFlicker_WhileParked_DoesNotLatchHasTakenOff()
        {
            // Regression: a single spurious OnGround==false sample at ~0ft AGL (squat-switch
            // flicker from a ramp bump/load-in settle, not a real takeoff) must not permanently
            // flip _hasTakenOffThisSession -- otherwise route matching silently swaps to the
            // destination airport for the rest of the session while still parked at the origin.
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LOWI");
            AddController("LOWW_GND", 12150, 0, 0);
            AddController("LOWW_TWR", 12180, 0, 0);
            _radio.Current = new RadioState(12150, null, null, null, false, null, DateTimeOffset.Now);
            var model = CreateModel(flightPlan);

            // Flicker: momentarily reports airborne at ~2ft AGL, still parked.
            _radio.Telemetry = new OwnshipTelemetry(false, 0, 2, 0, 0, 0, 0, DateTimeOffset.Now);
            _radio.RaiseChanged();
            // Settles back to on-ground.
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0, DateTimeOffset.Now);
            _radio.RaiseChanged();

            Assert.True(model.Current.Single(c => c.Callsign == "LOWW_TWR").IsLikelyNextCandidate);
        }

        [Fact]
        public async Task NextTierCandidate_NoRouteMatchInTier_FlightPlanLoaded_NothingFlaggedBelowCtr()
        {
            // A flight plan is loaded, so callsign prefix is trusted: neither TWR belongs to
            // LOWW or LZIB, so neither is "likely next" -- proximity must not paper over that
            // for DEL/GND/TWR/APP tiers once real route data exists.
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            AddController("AAA_TWR", 20000, 0, 0);
            AddController("BBB_TWR", 20100, 0, 1);
            AddController("LOWW_DEL", 12100, 0, 0);
            _radio.Current = new RadioState(12100, null, null, null, false, null, DateTimeOffset.Now);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0.4, DateTimeOffset.Now); // AAA closer
            var model = CreateModel(flightPlan);

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.False(ranked.Single(c => c.Callsign == "AAA_TWR").IsLikelyNextCandidate);
            Assert.False(ranked.Single(c => c.Callsign == "BBB_TWR").IsLikelyNextCandidate);
        }

        [Fact]
        public void NextTierCandidate_NoRouteMatchInTier_NoFlightPlanAtAll_FallsBackToClosestBelowCtr()
        {
            // No flight plan loaded at all (NoOpFlightPlan) -- there's no route data to trust
            // either way, so below-CTR tiers still fall back to proximity same as CTR would.
            AddController("AAA_TWR", 20000, 0, 0);
            AddController("BBB_TWR", 20100, 0, 1);
            AddController("EGLL_DEL", 12100, 0, 0);
            _radio.Current = new RadioState(12100, null, null, null, false, null, DateTimeOffset.Now);
            _radio.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 0, 0.4, DateTimeOffset.Now); // AAA closer
            var model = CreateModel();

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "AAA_TWR").IsLikelyNextCandidate);
            Assert.False(ranked.Single(c => c.Callsign == "BBB_TWR").IsLikelyNextCandidate);
        }

        [Fact]
        public async Task NextTierCandidate_Ctr_NoRouteMatch_AlwaysFallsBackToClosest()
        {
            // CTR is the exception even with a flight plan loaded: FIR callsigns (e.g. "VIE_CTR")
            // routinely don't share an airport's ICAO prefix, so proximity always applies there.
            var flightPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            AddController("VIE_CTR", 20000, 0, 0);
            AddController("BRA_CTR", 20100, 0, 1);
            AddController("LOWW_APP", 12100, 0, 0);
            _radio.Current = new RadioState(12100, null, null, null, false, null, DateTimeOffset.Now);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 0, 90, 0, 0.4, DateTimeOffset.Now); // VIE closer
            var model = CreateModel(flightPlan);

            _radio.RaiseChanged();

            var ranked = model.Current;
            Assert.True(ranked.Single(c => c.Callsign == "VIE_CTR").IsLikelyNextCandidate);
            Assert.False(ranked.Single(c => c.Callsign == "BRA_CTR").IsLikelyNextCandidate);
        }

        /// <summary>Starts a real VatsimDataFeedModel with a canned single-poll snapshot containing
        /// one filed pilot, waiting for that poll to land before returning -- mirrors
        /// VatsimDataFeedModelTests' own Start()+wait pattern, since Pilots is only ever populated
        /// via the background poll loop.</summary>
        private VatsimDataFeedModel CreateVatsimFeedWithPilot(string callsign, string departure, string arrival)
        {
            var snapshot = new VatsimDataFeedSnapshot(
                new List<VatsimControllerInfo>(),
                new List<VatsimPilotInfo> { new VatsimPilotInfo(callsign, departure, arrival) });
            var feed = new VatsimDataFeedModel(fetch: () => Task.FromResult(snapshot));
            var raised = new ManualResetEventSlim();
            feed.Changed += (s, e) => raised.Set();
            feed.Start();
            raised.Wait(TimeSpan.FromSeconds(5));
            return feed;
        }

        [Fact]
        public async Task RouteMatch_PrefersVatsimFiledPlanOverSimbrief_WhenAvailable()
        {
            // SimBrief says LOWW-LZIB, but the pilot actually filed LOWW-EDDF on the network --
            // the VATSIM-filed plan is the more authoritative source once it exists, and route
            // match should follow it, not the (now-stale) SimBrief OFP.
            var simbriefPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            var vatsimFeed = CreateVatsimFeedWithPilot("BAW123", "LOWW", "EDDF");
            _pilotSession.OnNetworkConnected("BAW123", "1234567");
            AddController("EDDF_CTR", 12100);
            AddController("LZIB_CTR", 12200);

            var model = new ControllerRankingModel(_controllers, _radio, simbriefPlan, vatsimFeed, _contactMe, _selcalActive, _pilotSession);
            _radio.Telemetry = new OwnshipTelemetry(false, 250, 15000, 500, 90, 48.5, 16.9, DateTimeOffset.Now);
            _radio.RaiseChanged();

            var ctrTier = model.Current.Where(c => c.Callsign.EndsWith("_CTR")).ToList();
            Assert.Equal("EDDF_CTR", ctrTier[0].Callsign);

            vatsimFeed.Stop();
        }

        [Fact]
        public async Task RouteMatch_FallsBackToSimbrief_WhenConnectedButNothingFiledOnVatsimYet()
        {
            // Connected (own callsign known) but the data feed's pilots[] has no entry for it yet
            // (not filed on the network, or the ~15s poll just hasn't caught up) -- SimBrief
            // should still drive route match rather than route match going dark.
            var simbriefPlan = await CreateFlightPlanAsync("LOWW", "LZIB");
            var vatsimFeed = new VatsimDataFeedModel(fetch: () => Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>())));
            var raised = new ManualResetEventSlim();
            vatsimFeed.Changed += (s, e) => raised.Set();
            vatsimFeed.Start();
            raised.Wait(TimeSpan.FromSeconds(5));
            _pilotSession.OnNetworkConnected("BAW123", "1234567");
            AddController("LOWW_DEL", 12100);
            AddController("LZIB_DEL", 12200);

            var model = new ControllerRankingModel(_controllers, _radio, simbriefPlan, vatsimFeed, _contactMe, _selcalActive, _pilotSession);

            var delTier = model.Current.Where(c => c.Callsign.EndsWith("_DEL")).ToList();
            Assert.Equal("LOWW_DEL", delTier[0].Callsign);

            vatsimFeed.Stop();
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
