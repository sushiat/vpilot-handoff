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
        public void RefreshNow_WhileStopped_DoesNotPoll_ButSaysWhy()
        {
            var polls = 0;
            var progress = new OperationProgressModel();
            var events = new List<OperationProgressEventArgs>();
            progress.Changed += (s, e) => events.Add(e);
            var model = new VatsimDataFeedModel(fetch: () =>
            {
                Interlocked.Increment(ref polls);
                return Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>()));
            }, operationProgress: progress);

            model.RefreshNow();
            Thread.Sleep(300);

            Assert.Equal(0, polls);
            var only = Assert.Single(events);
            Assert.StartsWith("vatsimFeedRefresh-", only.OperationId);
            Assert.True(only.Finished);
            Assert.False(only.Success);
        }

        [Fact]
        public void RefreshNow_ReportsProgressThenSuccessOnceThePollLands()
        {
            var progress = new OperationProgressModel();
            var events = new List<OperationProgressEventArgs>();
            progress.Changed += (s, e) => { lock (events) events.Add(e); };
            var model = new VatsimDataFeedModel(
                fetch: () => Task.FromResult(new VatsimDataFeedSnapshot(new List<VatsimControllerInfo>(), new List<VatsimPilotInfo>())),
                operationProgress: progress);

            model.Start();
            model.RefreshNow();

            Assert.True(SpinWait.SpinUntil(() => { lock (events) return events.Exists(e => e.Finished); }, TimeSpan.FromSeconds(5)));
            lock (events)
            {
                Assert.False(events[0].Finished);
                var done = events.Find(e => e.Finished);
                Assert.Equal(events[0].OperationId, done.OperationId);
                Assert.True(done.Success);
            }

            model.Stop();
        }

        [Fact]
        public void RefreshNow_ReportsFailureWhenTheFeedIsUnreachable()
        {
            var progress = new OperationProgressModel();
            var events = new List<OperationProgressEventArgs>();
            progress.Changed += (s, e) => { lock (events) events.Add(e); };
            var model = new VatsimDataFeedModel(fetch: () => Task.FromResult<VatsimDataFeedSnapshot>(null), operationProgress: progress);

            model.Start();
            model.RefreshNow();

            Assert.True(SpinWait.SpinUntil(() => { lock (events) return events.Exists(e => e.Finished); }, TimeSpan.FromSeconds(5)));
            lock (events) Assert.False(events.Find(e => e.Finished).Success);

            model.Stop();
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
