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

            var raised = new ManualResetEventSlim();
            model.Changed += (s, e) => raised.Set();

            model.Start();
            Assert.True(raised.Wait(TimeSpan.FromSeconds(5)));
            Assert.True(model.IsConnected);

            model.Stop();
        }

        [Fact]
        public void FailedPoll_SetsIsConnectedFalse()
        {
            var model = new VatsimDataFeedModel(fetch: () => Task.FromResult<VatsimDataFeedSnapshot>(null));

            var raised = new ManualResetEventSlim();
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

            var raised = new ManualResetEventSlim();
            model.Changed += (s, e) => raised.Set();
            model.Start();
            Assert.True(raised.Wait(TimeSpan.FromSeconds(5)));

            model.Stop();

            Assert.False(model.IsConnected);
        }
    }
}
