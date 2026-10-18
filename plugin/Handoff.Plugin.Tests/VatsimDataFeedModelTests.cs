using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatsimDataFeedModelTests
    {
        [Fact]
        public void IsConnected_BeforeAnyPoll_IsFalse()
        {
            var model = new VatsimDataFeedModel(fetch: () => Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>())));

            Assert.False(model.IsConnected);
        }

        [Fact]
        public void SuccessfulPoll_SetsIsConnectedTrue()
        {
            var model = new VatsimDataFeedModel(fetch: () => Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>())));

            using var raised = new ManualResetEventSlim();
            model.Changed += (s, e) => raised.Set();

            model.Start();
            Assert.True(raised.Wait(TimeSpan.FromSeconds(5)));
            Assert.True(model.IsConnected);

            model.Stop();
        }

        [Fact]
        public void RefreshNow_PollsAgainWithoutWaitingOutTheInterval()
        {
            var polls = 0;
            var model = new VatsimDataFeedModel(fetch: () =>
            {
                Interlocked.Increment(ref polls);
                return Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>()));
            });

            model.Start();
            Assert.True(SpinWait.SpinUntil(() => Volatile.Read(ref polls) >= 1, TimeSpan.FromSeconds(5)));

            // The regular interval is 15s; a refresh must land far sooner than that.
            model.RefreshNow();
            Assert.True(SpinWait.SpinUntil(() => Volatile.Read(ref polls) >= 2, TimeSpan.FromSeconds(5)));

            model.Stop();
        }

        [Fact]
        public void RefreshNow_WhileStopped_DoesNothing()
        {
            var polls = 0;
            var model = new VatsimDataFeedModel(fetch: () =>
            {
                Interlocked.Increment(ref polls);
                return Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>()));
            });

            model.RefreshNow();
            Thread.Sleep(300);

            Assert.Equal(0, polls);
        }

        [Fact]
        public void FailedPoll_SetsIsConnectedFalse()
        {
            var model = new VatsimDataFeedModel(fetch: () => Task.FromResult<VatsimDataFeedSnapshot>(null));

            using var raised = new ManualResetEventSlim();
            model.Changed += (s, e) => raised.Set();

            model.Start();
            Assert.True(raised.Wait(TimeSpan.FromSeconds(5)));
            Assert.False(model.IsConnected);

            model.Stop();
        }

        [Fact]
        public void Stop_SetsIsConnectedFalse()
        {
            var model = new VatsimDataFeedModel(fetch: () => Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>())));

            using var raised = new ManualResetEventSlim();
            model.Changed += (s, e) => raised.Set();
            model.Start();
            Assert.True(raised.Wait(TimeSpan.FromSeconds(5)));

            model.Stop();

            Assert.False(model.IsConnected);
        }

        [Fact]
        public void DuplicateCallsignsInFeed_DoNotFailThePoll()
        {
            // Seen live: the feed listed the same controller callsign twice, which used to throw
            // out of ToDictionary and leave every pilot lookup empty.
            var controller = new VatsimControllerInfo("EPGD_TWR", 1226744, "Test", 4, 3);
            var snapshot = new VatsimDataFeedSnapshot(
                new List<VatsimControllerInfo> { controller, controller },
                new List<VatsimPilotInfo> { new VatsimPilotInfo("EIN76G", "BIKF", "EIDW"), new VatsimPilotInfo("EIN76G", "BIKF", "EIDW") });
            var model = new VatsimDataFeedModel(fetch: () => Task.FromResult(snapshot));

            using var raised = new ManualResetEventSlim();
            model.Changed += (s, e) => raised.Set();

            model.Start();
            Assert.True(raised.Wait(TimeSpan.FromSeconds(5)));
            Assert.True(model.IsConnected);
            Assert.Single(model.Controllers);
            Assert.True(model.Pilots.ContainsKey("EIN76G"));

            model.Stop();
        }
    }
}
