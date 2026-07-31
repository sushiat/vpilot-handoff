using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class FlightPlanModelTests : IDisposable
    {
        private readonly string _configPath = PathJoin.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        [Fact]
        public async Task RefreshAsync_AfterSetSimbriefCredentials_UpdatesCurrentAndRaisesChanged()
        {
            var plan = new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS");
            var model = new FlightPlanModel(new OperationProgressModel(), fetch: (userId, username) => Task.FromResult(plan), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            var raised = false;
            model.Changed += (s, e) => raised = true;

            await model.RefreshAsync();

            Assert.True(raised);
            Assert.Equal("BAW123", model.Current.Callsign);
            Assert.Equal("EGLL", model.Current.Origin);
            Assert.Equal("KJFK", model.Current.Destination);
            Assert.Equal("KBOS", model.Current.Alternate);
            Assert.True(model.HasFetchedSuccessfully);
        }

        [Fact]
        public void HasFetchedSuccessfully_BeforeAnyFetch_IsFalse()
        {
            var model = new FlightPlanModel(new OperationProgressModel(), configPath: _configPath);

            Assert.False(model.HasFetchedSuccessfully);
        }

        [Fact]
        public async Task RefreshAsync_FailedFetch_LeavesCurrentNull()
        {
            var model = new FlightPlanModel(new OperationProgressModel(), fetch: (userId, username) => Task.FromResult<FlightPlan>(null), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();

            Assert.Null(model.Current.Callsign);
        }

        [Fact]
        public async Task RefreshAsync_FetchThrows_LeavesCurrentNullWithoutThrowing()
        {
            var model = new FlightPlanModel(new OperationProgressModel(), fetch: (userId, username) => throw new InvalidOperationException("boom"), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();

            Assert.Null(model.Current.Callsign);
        }

        [Fact]
        public async Task RefreshAsync_NoPersistedCredentials_DoesNotFetch()
        {
            var fetchCalled = false;
            var model = new FlightPlanModel(new OperationProgressModel(), fetch: (userId, username) =>
            {
                fetchCalled = true;
                return Task.FromResult(new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS"));
            }, configPath: _configPath);

            await model.RefreshAsync();

            Assert.False(fetchCalled);
        }

        [Fact]
        public async Task RefreshAsync_UsesCredentialsPersistedByAPriorInstance()
        {
            var seedModel = new FlightPlanModel(new OperationProgressModel(), configPath: _configPath);
            seedModel.SetSimbriefCredentials("12345", "someuser");

            string capturedUserId = null, capturedUsername = null;
            var reloadedModel = new FlightPlanModel(new OperationProgressModel(), fetch: (userId, username) =>
            {
                capturedUserId = userId;
                capturedUsername = username;
                return Task.FromResult(new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS"));
            }, configPath: _configPath);

            await reloadedModel.RefreshAsync();

            Assert.Equal("12345", capturedUserId);
            Assert.Equal("someuser", capturedUsername);
        }

        [Fact]
        public void SetSimbriefCredentials_DoesNotFetch()
        {
            var fetchCalled = false;
            var model = new FlightPlanModel(new OperationProgressModel(), fetch: (userId, username) =>
            {
                fetchCalled = true;
                return Task.FromResult(new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS"));
            }, configPath: _configPath);

            model.SetSimbriefCredentials("12345", "someuser");

            Assert.False(fetchCalled);
            Assert.Null(model.Current.Callsign);
        }

        [Fact]
        public async Task RefreshAsync_NoCredentials_ReportsNoOperationProgressAtAll()
        {
            var progress = new OperationProgressModel();
            var events = new List<OperationProgressEventArgs>();
            progress.Changed += (s, e) => events.Add(e);
            var model = new FlightPlanModel(progress, configPath: _configPath);

            await model.RefreshAsync();

            Assert.Empty(events);
        }

        [Fact]
        public async Task RefreshAsync_SuccessfulFetch_ReportsThenFinishesWithSuccess()
        {
            var progress = new OperationProgressModel();
            var events = new List<OperationProgressEventArgs>();
            progress.Changed += (s, e) => events.Add(e);
            var plan = new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS");
            var model = new FlightPlanModel(progress, fetch: (userId, username) => Task.FromResult(plan), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();

            Assert.Equal(2, events.Count);
            Assert.False(events[0].Finished);
            Assert.True(events[1].Finished);
            Assert.True(events[1].Success);
            Assert.Equal(events[0].OperationId, events[1].OperationId);
        }

        [Fact]
        public async Task RefreshAsync_FailedFetch_FinishesWithFailure()
        {
            var progress = new OperationProgressModel();
            OperationProgressEventArgs lastEvent = null;
            progress.Changed += (s, e) => lastEvent = e;
            var model = new FlightPlanModel(progress, fetch: (userId, username) => Task.FromResult<FlightPlan>(null), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();

            Assert.True(lastEvent.Finished);
            Assert.False(lastEvent.Success);
        }

        [Fact]
        public async Task RefreshAsync_FetchThrows_FinishesWithFailure()
        {
            var progress = new OperationProgressModel();
            OperationProgressEventArgs lastEvent = null;
            progress.Changed += (s, e) => lastEvent = e;
            var model = new FlightPlanModel(progress, fetch: (userId, username) => throw new InvalidOperationException("boom"), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();

            Assert.True(lastEvent.Finished);
            Assert.False(lastEvent.Success);
        }

        [Fact]
        public async Task RefreshAsync_CalledRepeatedly_EachCallGetsItsOwnOperationId()
        {
            // The real-world case this guards: a pilot tapping the refresh button several times
            // in quick succession must never have one call's Finish stomp on another's -- each
            // RefreshAsync() invocation needs its own identity, not a shared constant.
            var progress = new OperationProgressModel();
            var operationIds = new List<string>();
            progress.Changed += (s, e) => { if (!e.Finished) operationIds.Add(e.OperationId); };
            var plan = new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS");
            var model = new FlightPlanModel(progress, fetch: (userId, username) => Task.FromResult(plan), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();
            await model.RefreshAsync();
            await model.RefreshAsync();

            Assert.Equal(3, operationIds.Count);
            Assert.Equal(3, new HashSet<string>(operationIds).Count);
        }
    }
}
