using System;
using System.IO;
using System.Threading.Tasks;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class FlightPlanModelTests : IDisposable
    {
        private readonly string _configPath = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");

        public void Dispose()
        {
            if (File.Exists(_configPath)) File.Delete(_configPath);
        }

        [Fact]
        public async Task RefreshAsync_AfterSetSimbriefCredentials_UpdatesCurrentAndRaisesChanged()
        {
            var plan = new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS");
            var model = new FlightPlanModel(fetch: (userId, username) => Task.FromResult(plan), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            var raised = false;
            model.Changed += (s, e) => raised = true;

            await model.RefreshAsync();

            Assert.True(raised);
            Assert.Equal("BAW123", model.Current.Callsign);
            Assert.Equal("EGLL", model.Current.Origin);
            Assert.Equal("KJFK", model.Current.Destination);
            Assert.Equal("KBOS", model.Current.Alternate);
        }

        [Fact]
        public async Task RefreshAsync_FailedFetch_LeavesCurrentNull()
        {
            var model = new FlightPlanModel(fetch: (userId, username) => Task.FromResult<FlightPlan>(null), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();

            Assert.Null(model.Current.Callsign);
        }

        [Fact]
        public async Task RefreshAsync_FetchThrows_LeavesCurrentNullWithoutThrowing()
        {
            var model = new FlightPlanModel(fetch: (userId, username) => throw new InvalidOperationException("boom"), configPath: _configPath);
            model.SetSimbriefCredentials("12345", "someuser");

            await model.RefreshAsync();

            Assert.Null(model.Current.Callsign);
        }

        [Fact]
        public async Task RefreshAsync_NoPersistedCredentials_DoesNotFetch()
        {
            var fetchCalled = false;
            var model = new FlightPlanModel(fetch: (userId, username) =>
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
            var seedModel = new FlightPlanModel(configPath: _configPath);
            seedModel.SetSimbriefCredentials("12345", "someuser");

            string capturedUserId = null, capturedUsername = null;
            var reloadedModel = new FlightPlanModel(fetch: (userId, username) =>
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
            var model = new FlightPlanModel(fetch: (userId, username) =>
            {
                fetchCalled = true;
                return Task.FromResult(new FlightPlan("BAW123", "EGLL", "KJFK", "KBOS"));
            }, configPath: _configPath);

            model.SetSimbriefCredentials("12345", "someuser");

            Assert.False(fetchCalled);
            Assert.Null(model.Current.Callsign);
        }
    }
}
